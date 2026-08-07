package com.margelo.nitro.video

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.extractor.metadata.emsg.EventMessage
import androidx.media3.extractor.metadata.id3.Id3Frame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.ui.PlayerView
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise
import com.twg.video.core.LibraryError
import com.twg.video.core.PlayerError
import com.twg.video.core.VideoManager
import com.twg.video.core.extensions.startService
import com.twg.video.core.extensions.stopService
import com.twg.video.core.player.OnAudioFocusChangedListener
import com.twg.video.core.recivers.AudioBecomingNoisyReceiver
import com.twg.video.core.services.playback.VideoPlaybackService
import com.twg.video.core.services.playback.VideoPlaybackServiceConnection
import com.twg.video.core.utils.TextTrackUtils
import com.twg.video.core.utils.Threading.mainThreadProperty
import com.twg.video.core.utils.Threading.runOnMainThread
import com.twg.video.core.utils.Threading.runOnMainThreadSync
import com.twg.video.core.utils.VideoOrientationUtils
import com.twg.video.view.VideoView
import java.lang.ref.WeakReference
import kotlin.math.max

@UnstableApi
@DoNotStrip
class HybridVideoPlayer() : HybridVideoPlayerSpec(), AutoCloseable {
  override lateinit var source: HybridVideoPlayerSourceSpec
  override var eventEmitter = HybridVideoPlayerEventEmitter()
    set(value) {
      if (field != value) {
        audioFocusChangedListener.setEventEmitter(value)
        audioBecomingNoisyReceiver.setEventEmitter(value)
      }
      field = value
    }

  private var allocator: DefaultAllocator? = null
  private var context = NitroModules.applicationContext
    ?: run {
    throw LibraryError.ApplicationContextNotFound
  }

  var player: ExoPlayer = runOnMainThreadSync {
    // Build Temporary player that will be replaced when source is loaded
    return@runOnMainThreadSync ExoPlayer.Builder(context).build()
  }

  var loadedWithSource = false
  private var currentPlayerView: WeakReference<PlayerView>? = null

  /*  Green screen renders into a GL-owned Surface instead of the PlayerView. `player` starts out
      as a throwaway instance and is swapped for the real one in initializePlayer(), so a Surface
      that arrives before that swap (or that outlives it) has to be kept and re-applied. */
  private var greenScreenSurface: Surface? = null

  var wasAutoPaused = false

  // Buffer Config
  private var bufferConfig: BufferConfig? = null
    get() = source.config.bufferConfig

  // Time updates
  private val progressHandler = Handler(Looper.getMainLooper())
  private var progressRunnable: Runnable? = null
  /** Bumped on stop/release so an in-flight progress tick cannot re-schedule after stop. */
  private var progressGeneration = 0
  private var playerReleased = false

  // Listeners
  private val audioFocusChangedListener = OnAudioFocusChangedListener()
  private val audioBecomingNoisyReceiver = AudioBecomingNoisyReceiver()

  // Service Connection
  private val videoPlaybackServiceConnection = VideoPlaybackServiceConnection(WeakReference(this))

  // Text track selection state
  private var selectedExternalTrackIndex: Int? = null

  /** Last HLS segment URL reported through onTimedMetadata (legacy manifestFileChange-style hook). */
  private var lastNotifiedHlsSegmentUrl: String? = null

  /** Emit onLoad only on first READY after prepare/source change — not on every rebuffer READY. */
  private var hasEmittedOnLoad = false

  /**
   * Tip LLHLS only: once we pin a single video rung, do not re-evaluate ABR (Media3 #2299 —
   * adaptive switches near the live tip can freeze with a healthy buffer).
   */
  private var tipVideoTrackPinned = false

  /** Tip-only Media3 EventLogger — full Exo internals to logcat tag TIP-EXO. */
  private var tipEventLogger: EventLogger? = null
  private var lastTipLoadLogMs = 0L
  private var lastTipTimelineLogMs = 0L

  // Tip-only diagnostics (A–J matrix): format / speed / live-offset / heartbeat
  private var lastTipDiagHeartbeatMs = 0L
  private var lastTipVideoHeight = -1
  private var lastTipVideoBitrate = -1
  private var lastTipPlaybackSpeed = Float.NaN
  private var lastTipStateLogMs = 0L
  private var lastTipLoggedState = -1

  // Tip soft recover: frozen playhead + healthy buf + BUFFERING thrash → seekToDefaultPosition.
  private var tipLastPosMs = -1L
  private var tipPosFrozenSinceElapsedMs = 0L
  private var tipLastSoftSeekElapsedMs = 0L
  private var tipBufferingFlipCount = 0
  private var tipBufferingFlipWindowElapsedMs = 0L

  private companion object {
    const val PROGRESS_UPDATE_INTERVAL_MS = 250L
    private const val TAG = "HybridVideoPlayer"
    private const val TIP_TAG = "[TIP]"
    private const val DEFAULT_MIN_BUFFER_DURATION_MS = 5000
    private const val DEFAULT_MAX_BUFFER_DURATION_MS = 10000
    private const val DEFAULT_BUFFER_FOR_PLAYBACK_DURATION_MS = 1000
    private const val DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_DURATION_MS = 2000
    private const val DEFAULT_BACK_BUFFER_DURATION_MS = 0
    private const val TIP_DIAG_HEARTBEAT_MS = 5000L
    private const val TIP_SOFT_SEEK_MIN_INTERVAL_MS = 12000L
    private const val TIP_SOFT_SEEK_FROZEN_MS = 2000L
    private const val TIP_SOFT_SEEK_MIN_BUF_AHEAD_MS = 1000L
    private const val TIP_THRASH_FLIP_WINDOW_MS = 2000L
    private const val TIP_THRASH_FLIP_MIN = 8
  }

  private fun isTipLivePlayback(): Boolean = bufferConfig?.livePlayback != null

  private fun tipLog(event: String, detail: String = "") {
    if (!isTipLivePlayback()) {
      return
    }
    if (detail.isEmpty()) {
      Log.w(TAG, "$TIP_TAG $event")
    } else {
      Log.w(TAG, "$TIP_TAG $event $detail")
    }
  }

  private fun tipLogState(playbackState: Int, label: String) {
    if (!isTipLivePlayback()) {
      return
    }
    val now = SystemClock.elapsedRealtime()
    // Thrash can flip BUFFERING↔READY at ~10Hz — keep edges but rate-limit repeats.
    if (playbackState == lastTipLoggedState && now - lastTipStateLogMs < 1000L) {
      return
    }
    lastTipLoggedState = playbackState
    lastTipStateLogMs = now
    tipLog(label, tipDiagDetail())
  }

  /** Snapshot for freeze RCA: pos/buf/liveOffset + live window geometry. */
  private fun tipDiagDetail(): String {
    if (playerReleased || !loadedWithSource) {
      return "released=1"
    }
    return try {
      val posMs = player.currentPosition
      val bufAheadMs = max(0L, player.bufferedPosition - posMs)
      val liveOffsetMs =
        try {
          val offset = player.currentLiveOffset
          if (offset == C.TIME_UNSET) -1L else offset
        } catch (_: Exception) {
          -1L
        }
      val speed = player.playbackParameters.speed
      val vf = player.videoFormat
      val h = vf?.height ?: -1
      val br = vf?.bitrate ?: -1
      val state =
        when (player.playbackState) {
          Player.STATE_IDLE -> "IDLE"
          Player.STATE_BUFFERING -> "BUFFERING"
          Player.STATE_READY -> "READY"
          Player.STATE_ENDED -> "ENDED"
          else -> "UNK"
        }
      var winDurMs = -1L
      var defPosMs = -1L
      var isLive = false
      var isDynamic = false
      var isSeekable = false
      try {
        val timeline = player.currentTimeline
        if (!timeline.isEmpty) {
          val window = Timeline.Window()
          timeline.getWindow(player.currentMediaItemIndex, window)
          winDurMs = if (window.durationMs == C.TIME_UNSET) -1L else window.durationMs
          defPosMs = if (window.defaultPositionMs == C.TIME_UNSET) -1L else window.defaultPositionMs
          isLive = window.isLive
          isDynamic = window.isDynamic
          isSeekable = window.isSeekable
        }
      } catch (_: Exception) {
      }
      "posMs=$posMs bufAheadMs=$bufAheadMs liveOffsetMs=$liveOffsetMs speed=$speed " +
        "h=$h br=$br state=$state playing=${player.isPlaying} pwr=${player.playWhenReady} " +
        "winDurMs=$winDurMs defPosMs=$defPosMs live=$isLive dyn=$isDynamic seekable=$isSeekable " +
        "pinned=$tipVideoTrackPinned"
    } catch (e: Exception) {
      "err=${e.message}"
    }
  }

  private fun attachTipMedia3Logging() {
    if (!isTipLivePlayback()) {
      tipEventLogger = null
      return
    }
    // Full Media3 EventLogger → logcat tag TIP-EXO (loads, timeline, renderer, errors).
    val logger = EventLogger("TIP-EXO")
    tipEventLogger = logger
    player.addAnalyticsListener(logger)
    tipLog("EXO-LOGGER-ON", tipDiagDetail())
  }

  private fun detachTipMedia3Logging() {
    tipEventLogger?.let { logger ->
      try {
        player.removeAnalyticsListener(logger)
      } catch (_: Exception) {
      }
    }
    tipEventLogger = null
  }

  private fun tipDataTypeLabel(dataType: Int): String =
    when (dataType) {
      C.DATA_TYPE_MANIFEST -> "MANIFEST"
      C.DATA_TYPE_MEDIA -> "MEDIA"
      C.DATA_TYPE_MEDIA_INITIALIZATION -> "INIT"
      C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE -> "PROG_LIVE"
      C.DATA_TYPE_TIME_SYNCHRONIZATION -> "TIME_SYNC"
      C.DATA_TYPE_UNKNOWN -> "UNKNOWN"
      else -> "T$dataType"
    }

  private fun tipUriShort(uri: android.net.Uri?): String {
    if (uri == null) {
      return "-"
    }
    val s = uri.toString()
    val slash = s.lastIndexOf('/')
    return if (slash >= 0 && slash < s.length - 1) s.substring(slash + 1) else s.takeLast(48)
  }

  private fun maybeTipDiagHeartbeat() {
    if (!isTipLivePlayback() || playerReleased || !loadedWithSource) {
      return
    }
    val now = SystemClock.elapsedRealtime()
    if (now - lastTipDiagHeartbeatMs < TIP_DIAG_HEARTBEAT_MS) {
      return
    }
    lastTipDiagHeartbeatMs = now
    tipLog("NATIVE-OK", tipDiagDetail())
    emitTipTimedMetadata("rnv-tip-heartbeat", tipDiagDetail())
  }

  private fun emitTipTimedMetadata(identifier: String, value: String) {
    if (!isTipLivePlayback() || playerReleased) {
      return
    }
    try {
      eventEmitter.onTimedMetadata(
        TimedMetadata(
          metadata = arrayOf(TimedMetadataObject(identifier, value))
        )
      )
    } catch (_: Exception) {
    }
  }

  private fun onTipVideoFormatMaybeChanged(format: Format?) {
    if (!isTipLivePlayback() || format == null) {
      return
    }
    val h = format.height
    val br = format.bitrate
    if (h == lastTipVideoHeight && br == lastTipVideoBitrate) {
      return
    }
    val detail =
      "fromH=$lastTipVideoHeight fromBr=$lastTipVideoBitrate toH=$h toBr=$br ${tipDiagDetail()}"
    lastTipVideoHeight = h
    lastTipVideoBitrate = br
    tipLog("FORMAT", detail)
    emitTipTimedMetadata("rnv-tip-format", detail)
  }

  private fun onTipPlaybackSpeedMaybeChanged(speed: Float) {
    if (!isTipLivePlayback()) {
      return
    }
    if (!lastTipPlaybackSpeed.isNaN() && kotlin.math.abs(lastTipPlaybackSpeed - speed) < 0.005f) {
      return
    }
    val detail = "from=${lastTipPlaybackSpeed} to=$speed ${tipDiagDetail()}"
    lastTipPlaybackSpeed = speed
    tipLog("SPEED", detail)
    emitTipTimedMetadata("rnv-tip-speed", detail)
  }

  override var status: VideoPlayerStatus = VideoPlayerStatus.IDLE
    set(value) {
      if (field != value) {
        eventEmitter.onStatusChange(value)
      }
      field = value
    }

  override var showNotificationControls: Boolean = false
    set(value) {
      val wasRunning = (field || playInBackground)
      val shouldRun = (value || playInBackground)

      if (shouldRun && !wasRunning) {
        VideoPlaybackService.startService(context, videoPlaybackServiceConnection)
      }
      if (!shouldRun && wasRunning) {
        VideoPlaybackService.stopService(this, videoPlaybackServiceConnection)
      }

      field = value
      // Inform service to refresh notification/session layout
      try { videoPlaybackServiceConnection.serviceBinder?.service?.updatePlayerPreferences(this) } catch (_: Exception) {}
    }

  // Player Properties
  override var currentTime: Double by mainThreadProperty(
    get = { player.currentPosition.toDouble() / 1000.0 },
    set = { value -> runOnMainThread { player.seekTo((value * 1000).toLong()) } }
  )

  // volume defined by user
  var userVolume: Double = 1.0

  override var volume: Double by mainThreadProperty(
    get = { player.volume.toDouble() },
    set = { value ->
      userVolume = value
      player.volume = value.toFloat()
    }
  )

  override val duration: Double by mainThreadProperty(
    get = {
      val duration = player.duration
      return@mainThreadProperty if (duration == C.TIME_UNSET) Double.NaN else duration.toDouble() / 1000.0
    }
  )

  override var loop: Boolean by mainThreadProperty(
    get = {
      player.repeatMode == Player.REPEAT_MODE_ONE
    },
    set = { value ->
      player.repeatMode = if (value) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }
  )

  override var muted: Boolean by mainThreadProperty(
    get = {
      val playerVolume = player.volume.toDouble()
      return@mainThreadProperty playerVolume == 0.0
    },
    set = { value ->
      if (value) {
        userVolume = volume
        player.volume = 0f
      } else {
        player.volume = userVolume.toFloat()
      }
      eventEmitter.onVolumeChange(onVolumeChangeData(
        volume = player.volume.toDouble(),
        muted = muted
      ))
    }
  )

  override var rate: Double by mainThreadProperty(
    get = { player.playbackParameters.speed.toDouble() },
    set = { value ->
      player.playbackParameters = player.playbackParameters.withSpeed(value.toFloat())
    }
  )

  override var mixAudioMode: MixAudioMode = MixAudioMode.AUTO
    set(value) {
      VideoManager.audioFocusManager.requestAudioFocusUpdate()
      field = value
    }

  // iOS only property
  override var ignoreSilentSwitchMode: IgnoreSilentSwitchMode = IgnoreSilentSwitchMode.AUTO

  // iOS only property - no-op on Android
  override var disableAudioSessionManagement: Boolean = false

  override var playInBackground: Boolean = false
    set(value) {
      val shouldRun = (value || showNotificationControls)
      val wasRunning = (field || showNotificationControls)

      if (shouldRun && !wasRunning) {
        VideoPlaybackService.startService(context, videoPlaybackServiceConnection)
      }
      if (!shouldRun && wasRunning) {
        VideoPlaybackService.stopService(this, videoPlaybackServiceConnection)
      }
      field = value
      // Update preferences to refresh notifications/registration
      try { videoPlaybackServiceConnection.serviceBinder?.service?.updatePlayerPreferences(this) } catch (_: Exception) {}
    }

  override var playWhenInactive: Boolean = false

  override var isPlaying: Boolean by mainThreadProperty(
    get = { player.isPlaying == true }
  )

  private fun initializePlayer() {
    // Avoid double-init tearing down an already-prepared live player.
    if (loadedWithSource) {
      return
    }

    if (NitroModules.applicationContext == null) {
      throw LibraryError.ApplicationContextNotFound
    }

    val hybridSource = source as? HybridVideoPlayerSource ?: throw PlayerError.InvalidSource

    // Initialize the allocator
    allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)

    // Create a LoadControl with the allocator
    val loadControl = DefaultLoadControl.Builder()
      .setAllocator(allocator!!)
      .setBufferDurationsMs(
        bufferConfig?.minBufferMs?.toInt() ?: DEFAULT_MIN_BUFFER_DURATION_MS, // minBufferMs
        bufferConfig?.maxBufferMs?.toInt() ?: DEFAULT_MAX_BUFFER_DURATION_MS, // maxBufferMs
        bufferConfig?.bufferForPlaybackMs?.toInt()
          ?: DEFAULT_BUFFER_FOR_PLAYBACK_DURATION_MS, // bufferForPlaybackMs
        bufferConfig?.bufferForPlaybackAfterRebufferMs?.toInt()
          ?: DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_DURATION_MS // bufferForPlaybackAfterRebufferMs
      )
      .setBackBuffer(
        bufferConfig?.backBufferDurationMs?.toInt()
          ?: DEFAULT_BACK_BUFFER_DURATION_MS, // backBufferDurationMs,
        false // retainBackBufferFromKeyframe
      )
      .build()

    val renderersFactory = DefaultRenderersFactory(context)
      .setEnableDecoderFallback(true)
    // Tip LLHLS only: async MediaCodec queueing has caused BUFFERING↔READY thrash with a
    // healthy forward buffer while the playhead stays frozen (~2 min into class on device).
    // Keep async queueing for replay/VOD/regular live (no livePlayback).
    if (bufferConfig?.livePlayback == null) {
      renderersFactory.forceEnableMediaCodecAsynchronousQueueing()
    }

    val trackSelector = DefaultTrackSelector(context)
    trackSelector.parameters = trackSelector.buildUponParameters()
      .setMaxVideoBitrate(resolveMaxVideoBitrateBps())
      .build()

    val playerBuilder = ExoPlayer.Builder(context)
      .setLoadControl(loadControl)
      .setLooper(Looper.getMainLooper())
      .setRenderersFactory(renderersFactory)
      .setTrackSelector(trackSelector)

    // LLHLS passes livePlayback so Media3 can hold a stable live offset.
    // Media3 docs: each rebuffer adds targetLiveOffsetIncrementOnRebufferMs (default 500).
    // +1000 caused lag/buf to ratchet up during BUFFERING↔READY thrash until the playhead froze.
    // 0 disables the ratchet (https://developer.android.com/media/media3/exoplayer/live-streaming).
    if (bufferConfig?.livePlayback != null) {
      playerBuilder.setLivePlaybackSpeedControl(
        DefaultLivePlaybackSpeedControl.Builder()
          .setTargetLiveOffsetIncrementOnRebufferMs(0L)
          .build()
      )
    }

    // Release the constructor placeholder (or any prior instance) before replacing.
    // LLHLS remounts create a new HybridVideoPlayer; leaking the temp player accumulates codecs.
    stopProgressUpdates()
    try {
      player.removeListener(playerListener)
      player.removeAnalyticsListener(analyticsListener)
      detachTipMedia3Logging()
    } catch (_: Exception) {
    }
    try {
      player.release()
    } catch (_: Exception) {
    }
    playerReleased = false
    tipVideoTrackPinned = false
    resetTipSoftSeekState()
    tipLastSoftSeekElapsedMs = 0L
    player = playerBuilder.build()

    loadedWithSource = true

    player.addListener(playerListener)
    player.addAnalyticsListener(analyticsListener)
    attachTipMedia3Logging()
    attachGreenScreenSurface()
    player.setMediaSource(hybridSource.mediaSource)

    // Emit onLoadStart
    val sourceType = if (hybridSource.uri.startsWith("http")) SourceType.NETWORK else SourceType.LOCAL
    hasEmittedOnLoad = false
    eventEmitter.onLoadStart(onLoadStartData(sourceType = sourceType, source = hybridSource))
    status = VideoPlayerStatus.LOADING
    startProgressUpdates()
  }

  override fun initialize(): Promise<Unit> {
    return Promise.async {
      return@async runOnMainThreadSync {
        initializePlayer()
        player.prepare()
      }
    }
  }

  constructor(source: HybridVideoPlayerSource) : this() {
    this.source = source

    runOnMainThread {
      if (source.config.initializeOnCreation == true) {
        initializePlayer()
        player.prepare()
      }
    }

    VideoManager.registerPlayer(this)
  }

  override fun play() {
    runOnMainThread {
      player.play()
    }
  }

  override fun pause() {
    runOnMainThread {
      player.pause()
    }
  }

  override fun seekBy(time: Double) {
    seekTo(currentTime + time)
  }

  override fun seekTo(time: Double) {
    if (!time.isFinite() || time < 0.0) {
      return
    }
    currentTime = clampSeekTimeSeconds(time)
  }

  /**
   * Opt-in via `bufferConfig.livePlayback` only (LLHLS live). Do not key off
   * `isCurrentMediaItemLive` — that would also soften seeks for traditional
   * Live.js HLS DVR / other live playlists that must scrub to duration.
   *
   * Plain `coerceIn(0, duration)` was promoting JS soft-edge seeks up to the
   * absolute tip when player.duration lagged seekableDuration → aheadMs≈0
   * BUFFERING/READY freeze on 16KB.
   */
  private fun clampSeekTimeSeconds(time: Double): Double {
    val dur = duration
    if (!dur.isFinite() || dur <= 0.0) {
      return time
    }
    val livePlayback = bufferConfig?.livePlayback
    if (livePlayback == null) {
      return time.coerceIn(0.0, dur)
    }
    val cushionSec = ((livePlayback.targetOffsetMs ?: 2000.0) / 1000.0).coerceAtLeast(1.5)
    val maxSeek = (dur - cushionSec).coerceAtLeast(0.0)
    val clamped = time.coerceIn(0.0, maxSeek)
    if (kotlin.math.abs(clamped - time) > 0.05) {
      tipLog(
        "SEEK-CLAMP",
        "req=$time clamped=$clamped dur=$dur cushion=$cushionSec maxSeek=$maxSeek ${tipDiagDetail()}"
      )
    }
    return clamped
  }

  override fun replaceSourceAsync(source: Variant_NullType_HybridVideoPlayerSourceSpec?): Promise<Unit> {
    return Promise.async {
      val source = source?.asSecondOrNull()

      if (source == null) {
        release()
        return@async
      }

      val hybridSource = source as? HybridVideoPlayerSource ?: throw PlayerError.InvalidSource

      val oldSource = this.source as? HybridVideoPlayerSource
      oldSource?.sourceLoader?.cancel()

      runOnMainThreadSync {
        // Update source
        this.source = source
        lastNotifiedHlsSegmentUrl = null
        hasEmittedOnLoad = false
        tipVideoTrackPinned = false
        resetTipSoftSeekState()
        tipLastSoftSeekElapsedMs = 0L
        applyMaxVideoBitrateTrackConstraint()
        player.setMediaSource(hybridSource.mediaSource)

        // Prepare player
        player.prepare()
      }
    }
  }

  override fun preload(): Promise<Unit> {
    return Promise.async {
      runOnMainThreadSync {
        if (!loadedWithSource) {
          initializePlayer()
        }

        if (player.playbackState != Player.STATE_IDLE) {
          return@runOnMainThreadSync
        }

        player.prepare()
      }
    }
  }

  override fun release() {
    if (playInBackground || showNotificationControls) {
      VideoPlaybackService.stopService(this, videoPlaybackServiceConnection)
    }

    runOnMainThread {
      VideoManager.unregisterPlayer(this)
      stopProgressUpdates()
      loadedWithSource = false
      lastNotifiedHlsSegmentUrl = null
      hasEmittedOnLoad = false
      playerReleased = true

      eventEmitter.clearAllListeners()

      player.removeListener(playerListener)
      player.removeAnalyticsListener(analyticsListener)
      detachTipMedia3Logging()
      player.release() // Release player

      // Clean Listeners
      audioFocusChangedListener.removeEventEmitter()
      audioBecomingNoisyReceiver.removeEventEmitter()

      // Update status
      status = VideoPlayerStatus.IDLE
    }
  }

  /** Re-runs whenever either half of the handshake becomes available; the last one in wins. */
  private fun attachGreenScreenSurface() {
    val surface = greenScreenSurface ?: return
    if (playerReleased || !loadedWithSource) {
      return
    }
    try {
      player.setVideoSurface(surface)
    } catch (e: Exception) {
      Log.w(TAG, "setVideoSurface skipped after player teardown", e)
    }
  }

  fun movePlayerToVideoView(videoView: VideoView) {
    VideoManager.addViewToPlayer(videoView, this)

    runOnMainThreadSync {
      // LLHLS tip only (livePlayback opt-in): keep last frame on reset. Replay/VOD stay default.
      videoView.playerView.setKeepContentOnPlayerReset(bufferConfig?.livePlayback != null)

      if (videoView.useGreenScreen) {
        currentPlayerView = null
        val gl = videoView.ensureGreenScreenGlAttached()
        gl.setSurfaceReadyCallback { surface ->
          runOnMainThread {
            greenScreenSurface = surface
            attachGreenScreenSurface()
          }
        }
      } else {
        greenScreenSurface = null
        videoView.greenScreenGlView?.setSurfaceReadyCallback(null)
        player.clearVideoSurface()
        PlayerView.switchTargetView(player, currentPlayerView?.get(), videoView.playerView)
        currentPlayerView = WeakReference(videoView.playerView)
      }
    }
  }

  override fun dispose() {
    release()
  }

  override fun close() {
    release()
  }

  override val memorySize: Long
    // 1 MiB by default
    get() = allocator?.totalBytesAllocated?.toLong() ?: (1024L * 1024L)

  private fun startProgressUpdates() {
    stopProgressUpdates() // Ensure no multiple runnables
    val generation = progressGeneration
    progressRunnable = object : Runnable {
      override fun run() {
        // Drop ticks scheduled before stop/release (removeCallbacks does not cancel an in-flight run).
        if (generation != progressGeneration || playerReleased || !loadedWithSource) {
          return
        }
        try {
          if (player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED) {
            val currentTimeSeconds = player.currentPosition / 1000.0
            val bufferedDurationSeconds = player.bufferedPosition / 1000.0
            // bufferDuration is the time from current time that is buffered.
            val playableDurationFromNow = max(0.0, bufferedDurationSeconds - currentTimeSeconds)

            eventEmitter.onProgress(
              onProgressData(
                currentTime = currentTimeSeconds,
                bufferDuration = playableDurationFromNow
              )
            )
            maybeNotifyHlsSegmentChange()
            maybeTipDiagHeartbeat()
            if (generation == progressGeneration && !playerReleased) {
              progressHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS)
            }
          }
        } catch (e: Exception) {
          Log.w(TAG, "progress tick skipped after player teardown", e)
        }
      }
    }
    progressHandler.post(progressRunnable ?: return)
  }

  private fun stopProgressUpdates() {
    progressGeneration += 1
    progressRunnable?.let { progressHandler.removeCallbacks(it) }
    progressRunnable = null
  }

  private fun resolveMaxVideoBitrateBps(): Int {
    val peak = source.config.bufferConfig?.preferredPeakBitRate ?: return Int.MAX_VALUE
    if (peak <= 0.0) {
      return Int.MAX_VALUE
    }
    return peak.toInt().coerceAtLeast(1)
  }

  private fun applyMaxVideoBitrateTrackConstraint() {
    val selector = player.trackSelector as? DefaultTrackSelector ?: return
    selector.parameters = selector.buildUponParameters()
      .setMaxVideoBitrate(resolveMaxVideoBitrateBps())
      .build()
  }

  /**
   * Tip LLHLS only (`livePlayback`): pin one video track under preferredPeakBitRate.
   * Max bitrate alone still allows ABR under the cap; switches near tip freeze playhead
   * (androidx/media#2299). Replay / regular live keep adaptive selection.
   */
  private fun maybePinTipVideoTrack(tracks: Tracks) {
    if (!isTipLivePlayback() || tipVideoTrackPinned || playerReleased || !loadedWithSource) {
      return
    }
    val selector = player.trackSelector as? DefaultTrackSelector ?: return
    val maxBitrate = resolveMaxVideoBitrateBps()

    var bestGroup: Tracks.Group? = null
    var bestIndex = -1
    var bestBitrate = -1
    var bestHeight = -1

    for (group in tracks.groups) {
      if (group.type != C.TRACK_TYPE_VIDEO || group.length == 0) {
        continue
      }
      for (i in 0 until group.length) {
        if (!group.isTrackSupported(i)) {
          continue
        }
        val format = group.getTrackFormat(i)
        val bitrate = if (format.bitrate != Format.NO_VALUE) format.bitrate else 0
        if (maxBitrate != Int.MAX_VALUE && bitrate > maxBitrate) {
          continue
        }
        val height = if (format.height != Format.NO_VALUE) format.height else 0
        val better =
          height > bestHeight ||
            (height == bestHeight && bitrate > bestBitrate)
        if (better) {
          bestGroup = group
          bestIndex = i
          bestBitrate = bitrate
          bestHeight = height
        }
      }
    }

    if (bestGroup == null || bestIndex < 0) {
      tipLog("ABR-PIN-SKIP", "noSuitableVideoTrack maxBr=$maxBitrate ${tipDiagDetail()}")
      return
    }

    try {
      selector.parameters = selector.buildUponParameters()
        .setMaxVideoBitrate(maxBitrate)
        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        .setOverrideForType(
          TrackSelectionOverride(bestGroup.mediaTrackGroup, listOf(bestIndex))
        )
        .build()
      tipVideoTrackPinned = true
      tipLog(
        "ABR-PIN",
        "h=$bestHeight br=$bestBitrate idx=$bestIndex maxBr=$maxBitrate ${tipDiagDetail()}"
      )
    } catch (e: Exception) {
      Log.e(TAG, "Tip ABR pin failed", e)
      tipLog("ABR-PIN-FAIL", e.message ?: "err")
    }
  }

  private fun resetTipSoftSeekState() {
    tipLastPosMs = -1L
    tipPosFrozenSinceElapsedMs = 0L
    tipBufferingFlipCount = 0
    tipBufferingFlipWindowElapsedMs = 0L
  }

  /**
   * Tip LLHLS only: Media3 audio-renderer ready flap freezes playhead with healthy buffer.
   * Soft seekToDefaultPosition (same as JS goLive) clears it without remount/black frame.
   */
  private fun maybeRecoverTipAudioFreeze(playbackState: Int) {
    if (!isTipLivePlayback() || playerReleased || !loadedWithSource) {
      return
    }
    if (!player.playWhenReady) {
      return
    }
    val now = SystemClock.elapsedRealtime()
    val posMs: Long
    val bufAheadMs: Long
    try {
      posMs = player.currentPosition
      bufAheadMs = max(0L, player.bufferedPosition - posMs)
    } catch (_: Exception) {
      return
    }

    if (tipLastPosMs >= 0L && Math.abs(posMs - tipLastPosMs) < 50L) {
      if (tipPosFrozenSinceElapsedMs == 0L) {
        tipPosFrozenSinceElapsedMs = now
      }
    } else {
      tipLastPosMs = posMs
      tipPosFrozenSinceElapsedMs = 0L
      tipBufferingFlipCount = 0
      tipBufferingFlipWindowElapsedMs = 0L
      return
    }
    tipLastPosMs = posMs

    if (playbackState == Player.STATE_BUFFERING) {
      if (now - tipBufferingFlipWindowElapsedMs > TIP_THRASH_FLIP_WINDOW_MS) {
        tipBufferingFlipWindowElapsedMs = now
        tipBufferingFlipCount = 1
      } else {
        tipBufferingFlipCount += 1
      }
    }

    val frozenMs =
      if (tipPosFrozenSinceElapsedMs > 0L) now - tipPosFrozenSinceElapsedMs else 0L
    if (frozenMs < TIP_SOFT_SEEK_FROZEN_MS) {
      return
    }
    if (bufAheadMs < TIP_SOFT_SEEK_MIN_BUF_AHEAD_MS) {
      return
    }
    if (tipBufferingFlipCount < TIP_THRASH_FLIP_MIN) {
      return
    }
    if (
      tipLastSoftSeekElapsedMs > 0L &&
      now - tipLastSoftSeekElapsedMs < TIP_SOFT_SEEK_MIN_INTERVAL_MS
    ) {
      return
    }

    tipLastSoftSeekElapsedMs = now
    val flips = tipBufferingFlipCount
    tipPosFrozenSinceElapsedMs = 0L
    tipBufferingFlipCount = 0
    tipBufferingFlipWindowElapsedMs = 0L
    tipLog(
      "SOFT-SEEK-LIVE",
      "frozenMs=$frozenMs flips=$flips ${tipDiagDetail()}"
    )
    try {
      player.seekToDefaultPosition()
      player.playWhenReady = true
      tipLog("SOFT-SEEK-LIVE-DONE", tipDiagDetail())
    } catch (e: Exception) {
      Log.e(TAG, "Tip soft seek live failed", e)
      tipLog("SOFT-SEEK-LIVE-FAIL", e.message ?: "err")
    }
  }

  /**
   * Replaces the old `onManifestFileChange` event: when playing HLS, emits
   * [onTimedMetadata] entries with identifiers `rnv-manifest-segment-url` and
   * `rnv-manifest-segment-start-us` whenever the active segment changes.
   */
  private fun maybeNotifyHlsSegmentChange() {
    if (!loadedWithSource) {
      return
    }
    val manifest = player.currentManifest
    if (manifest !is HlsManifest) {
      return
    }
    val playlist = manifest.mediaPlaylist ?: return
    val segments = playlist.segments
    if (segments.isEmpty()) {
      return
    }

    val positionUs = player.currentPosition * 1000L
    var active: HlsMediaPlaylist.Segment? = null
    for (i in segments.indices) {
      val seg = segments[i]
      val endUs = if (i + 1 < segments.size) {
        segments[i + 1].relativeStartTimeUs
      } else if (playlist.durationUs != C.TIME_UNSET) {
        playlist.durationUs
      } else {
        Long.MAX_VALUE
      }
      if (positionUs >= seg.relativeStartTimeUs && positionUs < endUs) {
        active = seg
        break
      }
    }
    if (active == null) {
      active = segments[segments.size - 1]
    }

    val url = active.url?.toString() ?: return
    if (url == lastNotifiedHlsSegmentUrl) {
      return
    }
    lastNotifiedHlsSegmentUrl = url

    val metadataEntries = mutableListOf(
      TimedMetadataObject(url, "rnv-manifest-segment-url"),
      TimedMetadataObject(active.relativeStartTimeUs.toString(), "rnv-manifest-segment-start-us"),
    )
    if (playlist.durationUs != C.TIME_UNSET) {
      metadataEntries.add(
        TimedMetadataObject(playlist.durationUs.toString(), "rnv-manifest-duration-us")
      )
    }

    eventEmitter.onTimedMetadata(
      TimedMetadata(
        metadata = metadataEntries.toTypedArray()
      )
    )
  }

  private val analyticsListener = object: AnalyticsListener {
    override fun onBandwidthEstimate(
      eventTime: AnalyticsListener.EventTime,
      totalLoadTimeMs: Int,
      totalBytesLoaded: Long,
      bitrateEstimate: Long
    ) {
      val videoFormat = player.videoFormat
      eventEmitter.onBandwidthUpdate(
        BandwidthData(
          bitrate = bitrateEstimate.toDouble(),
          width = if (videoFormat != null) videoFormat.width.toDouble() else null,
          height = if (videoFormat != null) videoFormat.height.toDouble() else null
        )
      )
      if (isTipLivePlayback()) {
        // Throttle: bandwidth callbacks are frequent; only heartbeat-cadence BW lines.
        val now = SystemClock.elapsedRealtime()
        if (now - lastTipDiagHeartbeatMs >= TIP_DIAG_HEARTBEAT_MS - 500L) {
          tipLog(
            "BW",
            "est=$bitrateEstimate loadMs=$totalLoadTimeMs bytes=$totalBytesLoaded ${tipDiagDetail()}"
          )
        }
      }
    }

    override fun onDownstreamFormatChanged(
      eventTime: AnalyticsListener.EventTime,
      mediaLoadData: MediaLoadData
    ) {
      if (mediaLoadData.trackType == C.TRACK_TYPE_VIDEO) {
        onTipVideoFormatMaybeChanged(mediaLoadData.trackFormat)
      }
      if (isTipLivePlayback()) {
        tipLog(
          "DOWNSTREAM-FMT",
          "trackType=${mediaLoadData.trackType} h=${mediaLoadData.trackFormat?.height ?: -1} " +
            "br=${mediaLoadData.trackFormat?.bitrate ?: -1} ${tipDiagDetail()}"
        )
      }
    }

    override fun onVideoInputFormatChanged(
      eventTime: AnalyticsListener.EventTime,
      format: Format,
      decoderReuseEvaluation: DecoderReuseEvaluation?
    ) {
      onTipVideoFormatMaybeChanged(format)
      if (isTipLivePlayback()) {
        tipLog(
          "VIDEO-INPUT-FMT",
          "h=${format.height} br=${format.bitrate} reuse=${decoderReuseEvaluation?.result} ${tipDiagDetail()}"
        )
      }
    }

    override fun onLoadStarted(
      eventTime: AnalyticsListener.EventTime,
      loadEventInfo: LoadEventInfo,
      mediaLoadData: MediaLoadData
    ) {
      if (!isTipLivePlayback()) {
        return
      }
      val now = SystemClock.elapsedRealtime()
      val isManifest = mediaLoadData.dataType == C.DATA_TYPE_MANIFEST
      // Manifest always; media loads throttled (LL-HLS parts are very chatty).
      if (!isManifest && now - lastTipLoadLogMs < 1000L) {
        return
      }
      lastTipLoadLogMs = now
      tipLog(
        "LOAD-START",
        "type=${tipDataTypeLabel(mediaLoadData.dataType)} uri=${tipUriShort(loadEventInfo.uri)} ${tipDiagDetail()}"
      )
    }

    override fun onLoadCompleted(
      eventTime: AnalyticsListener.EventTime,
      loadEventInfo: LoadEventInfo,
      mediaLoadData: MediaLoadData
    ) {
      if (!isTipLivePlayback()) {
        return
      }
      if (mediaLoadData.dataType == C.DATA_TYPE_MANIFEST) {
        tipLog(
          "LOAD-OK",
          "type=MANIFEST uri=${tipUriShort(loadEventInfo.uri)} " +
            "ms=${loadEventInfo.loadDurationMs} bytes=${loadEventInfo.bytesLoaded} ${tipDiagDetail()}"
        )
      }
    }

    override fun onLoadError(
      eventTime: AnalyticsListener.EventTime,
      loadEventInfo: LoadEventInfo,
      mediaLoadData: MediaLoadData,
      error: java.io.IOException,
      wasCanceled: Boolean
    ) {
      if (!isTipLivePlayback()) {
        return
      }
      tipLog(
        "LOAD-ERROR",
        "type=${tipDataTypeLabel(mediaLoadData.dataType)} uri=${tipUriShort(loadEventInfo.uri)} " +
          "canceled=$wasCanceled err=${error.javaClass.simpleName}:${error.message} ${tipDiagDetail()}"
      )
    }

    override fun onAudioUnderrun(
      eventTime: AnalyticsListener.EventTime,
      bufferSize: Int,
      bufferSizeMs: Long,
      elapsedSinceLastFeedMs: Long
    ) {
      if (isTipLivePlayback()) {
        tipLog(
          "AUDIO-UNDERRUN",
          "bufSize=$bufferSize bufSizeMs=$bufferSizeMs sinceFeedMs=$elapsedSinceLastFeedMs ${tipDiagDetail()}"
        )
      }
    }

    override fun onDroppedVideoFrames(
      eventTime: AnalyticsListener.EventTime,
      droppedFrames: Int,
      elapsedMs: Long
    ) {
      if (isTipLivePlayback() && droppedFrames > 0) {
        tipLog("DROPPED-FRAMES", "n=$droppedFrames elapsedMs=$elapsedMs ${tipDiagDetail()}")
      }
    }

    override fun onAudioSinkError(
      eventTime: AnalyticsListener.EventTime,
      audioSinkError: Exception
    ) {
      if (isTipLivePlayback()) {
        tipLog(
          "AUDIO-SINK-ERR",
          "${audioSinkError.javaClass.simpleName}:${audioSinkError.message} ${tipDiagDetail()}"
        )
      }
    }

    override fun onVideoCodecError(
      eventTime: AnalyticsListener.EventTime,
      videoCodecError: Exception
    ) {
      if (isTipLivePlayback()) {
        tipLog(
          "VIDEO-CODEC-ERR",
          "${videoCodecError.javaClass.simpleName}:${videoCodecError.message} ${tipDiagDetail()}"
        )
      }
    }

    override fun onAudioCodecError(
      eventTime: AnalyticsListener.EventTime,
      audioCodecError: Exception
    ) {
      if (isTipLivePlayback()) {
        tipLog(
          "AUDIO-CODEC-ERR",
          "${audioCodecError.javaClass.simpleName}:${audioCodecError.message} ${tipDiagDetail()}"
        )
      }
    }

    override fun onRenderedFirstFrame(
      eventTime: AnalyticsListener.EventTime,
      output: Any,
      renderTimeMs: Long
    ) {
      if (isTipLivePlayback()) {
        tipLog("FIRST-FRAME", "renderMs=$renderTimeMs ${tipDiagDetail()}")
      }
    }
  }

  private val playerListener = object : Player.Listener {
    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
      maybeNotifyHlsSegmentChange()
      if (isTipLivePlayback()) {
        val now = SystemClock.elapsedRealtime()
        // Tip window slides often — keep reason edges, rate-limit spam.
        if (now - lastTipTimelineLogMs >= 2000L || reason != Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE) {
          lastTipTimelineLogMs = now
          tipLog("TIMELINE", "reason=$reason ${tipDiagDetail()}")
        }
      }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
      val isPlayingUpdate = player.isPlaying
      val isBufferingUpdate = playbackState == Player.STATE_BUFFERING

      eventEmitter.onPlaybackStateChange(
        onPlaybackStateChangeData(
          isPlaying = isPlayingUpdate,
          isBuffering = isBufferingUpdate
        )
      )

      when (playbackState) {
        Player.STATE_IDLE -> {
          status = VideoPlayerStatus.IDLE
          eventEmitter.onBuffer(false)
        }
        Player.STATE_BUFFERING -> {
          status = VideoPlayerStatus.LOADING
          eventEmitter.onBuffer(true)
          tipLogState(Player.STATE_BUFFERING, "STATE-BUFFERING")
          maybeRecoverTipAudioFreeze(Player.STATE_BUFFERING)
        }
        Player.STATE_READY -> {
          status = VideoPlayerStatus.READYTOPLAY
          eventEmitter.onBuffer(false)
          tipLogState(Player.STATE_READY, "STATE-READY")
          maybeRecoverTipAudioFreeze(Player.STATE_READY)
          onTipVideoFormatMaybeChanged(player.videoFormat)

          // Rebuffers also hit READY; only first READY after prepare/source should fire onLoad.
          if (!hasEmittedOnLoad) {
            hasEmittedOnLoad = true
            val generalVideoFormat = player.videoFormat
            val currentTracks = player.currentTracks

            val selectedVideoTrackGroup = currentTracks.groups.find { group -> group.type == C.TRACK_TYPE_VIDEO && group.isSelected }
            val selectedVideoTrackFormat = if (selectedVideoTrackGroup != null && selectedVideoTrackGroup.length > 0) {
              selectedVideoTrackGroup.getTrackFormat(0)
            } else {
              null
            }

            val width = selectedVideoTrackFormat?.width ?: generalVideoFormat?.width ?: 0
            val height = selectedVideoTrackFormat?.height ?: generalVideoFormat?.height ?: 0
            val rotationDegrees = selectedVideoTrackFormat?.rotationDegrees ?: generalVideoFormat?.rotationDegrees

            eventEmitter.onLoad(
              onLoadData(
                currentTime = player.currentPosition / 1000.0,
                duration = if (player.duration == C.TIME_UNSET) Double.NaN else player.duration / 1000.0,
                width = width.toDouble(),
                height = height.toDouble(),
                orientation = VideoOrientationUtils.fromWHR(width, height, rotationDegrees)
              )
            )
          }
          // If player becomes ready and is set to play, start progress updates
          if (player.playWhenReady) {
            startProgressUpdates()
          }

          eventEmitter.onReadyToDisplay()
        }
        Player.STATE_ENDED -> {
          status = VideoPlayerStatus.IDLE // Or a specific 'COMPLETED' status if you add one
          eventEmitter.onEnd()
          eventEmitter.onBuffer(false)
          stopProgressUpdates()
        }
      }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
      super.onIsPlayingChanged(isPlaying)
      eventEmitter.onPlaybackStateChange(
        onPlaybackStateChangeData(
          isPlaying = isPlaying,
          isBuffering = player.playbackState == Player.STATE_BUFFERING
        )
      )
      if (isPlaying) {
        VideoManager.setLastPlayedPlayer(this@HybridVideoPlayer)
        startProgressUpdates()
      } else {
        if (player.playbackState == Player.STATE_ENDED || player.playbackState == Player.STATE_IDLE) {
          stopProgressUpdates()
        }
      }
    }

    override fun onPlayerError(error: PlaybackException) {
      // Google live recovery: fall behind window → seek to default live position, not remount-first.
      // Remount stays as JS last resort when this path cannot recover.
      if (
        error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW &&
        bufferConfig?.livePlayback != null
      ) {
        Log.w(TAG, "BehindLiveWindow — seekToDefaultPosition + prepare")
        tipLog("BEHIND-LIVE-WINDOW", tipDiagDetail())
        try {
          hasEmittedOnLoad = false
          player.seekToDefaultPosition()
          player.prepare()
          player.playWhenReady = true
          status = VideoPlayerStatus.LOADING
          startProgressUpdates()
          tipLog("BEHIND-LIVE-WINDOW-DONE", tipDiagDetail())
          return
        } catch (e: Exception) {
          Log.e(TAG, "BehindLiveWindow recovery failed", e)
          tipLog("BEHIND-LIVE-WINDOW-FAIL", e.message ?: "err")
        }
      }
      tipLog("PLAYER-ERROR", "code=${error.errorCode} msg=${error.message} ${tipDiagDetail()}")
      status = VideoPlayerStatus.ERROR
      stopProgressUpdates()
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
      onTipPlaybackSpeedMaybeChanged(playbackParameters.speed)
      eventEmitter.onPlaybackRateChange(playbackParameters.speed.toDouble())
    }

    override fun onTracksChanged(tracks: Tracks) {
      super.onTracksChanged(tracks)
      if (isTipLivePlayback()) {
        maybePinTipVideoTrack(tracks)
        val videoGroup = tracks.groups.find { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
        val fmt = if (videoGroup != null && videoGroup.length > 0) videoGroup.getTrackFormat(0) else null
        tipLog(
          "TRACKS",
          "h=${fmt?.height ?: -1} br=${fmt?.bitrate ?: -1} groups=${tracks.groups.size} pinned=$tipVideoTrackPinned ${tipDiagDetail()}"
        )
        onTipVideoFormatMaybeChanged(fmt)
      }
    }

    override fun onPositionDiscontinuity(
      oldPosition: Player.PositionInfo,
      newPosition: Player.PositionInfo,
      reason: Int
    ) {
      if (isTipLivePlayback()) {
        tipLog(
          "DISCONTINUITY",
          "reason=$reason fromMs=${oldPosition.positionMs} toMs=${newPosition.positionMs} ${tipDiagDetail()}"
        )
      }
      if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
        eventEmitter.onSeek(newPosition.positionMs / 1000.0)
      }
      // Update progress immediately after a discontinuity if needed by your logic
       val currentTimeSeconds = newPosition.positionMs / 1000.0
       val bufferedDurationSeconds = player.bufferedPosition / 1000.0
       eventEmitter.onProgress(
         onProgressData(
           currentTime = currentTimeSeconds,
           bufferDuration = max(0.0, bufferedDurationSeconds - currentTimeSeconds)
         )
       )
      maybeNotifyHlsSegmentChange()
    }

    override fun onVolumeChanged(volume: Float) {
      // We get here device volume changes, and if
      // player is not muted we will sync it
      if (!muted) {
        this@HybridVideoPlayer.volume = volume.toDouble()
      }

      VideoManager.audioFocusManager.requestAudioFocusUpdate()
      eventEmitter.onVolumeChange(onVolumeChangeData(
        volume = volume.toDouble(),
        muted = muted
      ))
    }

    override fun onCues(cueGroup: CueGroup) {
      val texts = cueGroup.cues.mapNotNull { it.text?.toString() }
      if (texts.isNotEmpty()) {
        eventEmitter.onTextTrackDataChanged(texts.toTypedArray())
      }
    }

    override fun onMetadata(metadata: Metadata) {
      val timedMetadataObjects = mutableListOf<TimedMetadataObject>()
      for (i in 0 until metadata.length()) {
        val entry = metadata.get(i)

        when (entry) {
          is Id3Frame -> {
            var value = ""

            if (entry is TextInformationFrame) {
              value = entry.values.first()
            }

            timedMetadataObjects.add(TimedMetadataObject(entry.id, value))
          }
          is EventMessage ->
            timedMetadataObjects.add(TimedMetadataObject(entry.schemeIdUri, entry.value))
          else -> Log.d(TAG, "Unknown metadata: $entry")
        }
      }
      if (timedMetadataObjects.isNotEmpty()) {
        eventEmitter.onTimedMetadata(TimedMetadata(metadata = timedMetadataObjects.toTypedArray()))
      }
    }
  }

  // MARK: - Text Track Management

  override fun getAvailableTextTracks(): Array<TextTrack> {
    return TextTrackUtils.getAvailableTextTracks(player, source)
  }

  override fun selectTextTrack(textTrack: Variant_NullType_TextTrack?) {
    selectedExternalTrackIndex = TextTrackUtils.selectTextTrack(
      player = player,
      textTrack = textTrack?.asSecondOrNull(),
      source = source,
      onTrackChange = { track -> eventEmitter.onTrackChange(track) }
    )
  }

  override val selectedTrack: TextTrack?
    get() = TextTrackUtils.getSelectedTrack(player, source)
}
