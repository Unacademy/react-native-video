package com.margelo.nitro.video

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
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

  var wasAutoPaused = false

  // Buffer Config
  private var bufferConfig: BufferConfig? = null
    get() = source.config.bufferConfig

  // Time updates
  private val progressHandler = Handler(Looper.getMainLooper())
  private var progressRunnable: Runnable? = null
  private var lastLiveDiagAtMs: Long = 0L
  private var lastLoggedPlaybackState: Int = Player.STATE_IDLE

  // Listeners
  private val audioFocusChangedListener = OnAudioFocusChangedListener()
  private val audioBecomingNoisyReceiver = AudioBecomingNoisyReceiver()

  // Service Connection
  private val videoPlaybackServiceConnection = VideoPlaybackServiceConnection(WeakReference(this))

  // Text track selection state
  private var selectedExternalTrackIndex: Int? = null

  /** Last HLS segment URL reported through onTimedMetadata (legacy manifestFileChange-style hook). */
  private var lastNotifiedHlsSegmentUrl: String? = null

  private companion object {
    const val PROGRESS_UPDATE_INTERVAL_MS = 250L
    private const val TAG = "HybridVideoPlayer"
    private const val LIVE_DIAG_TAG = "RNVLiveDiag"
    private const val LIVE_DIAG_INTERVAL_MS = 2000L
    private const val DEFAULT_MIN_BUFFER_DURATION_MS = 5000
    private const val DEFAULT_MAX_BUFFER_DURATION_MS = 10000
    private const val DEFAULT_BUFFER_FOR_PLAYBACK_DURATION_MS = 1000
    private const val DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_DURATION_MS = 2000
    private const val DEFAULT_BACK_BUFFER_DURATION_MS = 0
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
      // Use Media3 defaults for codec queueing — forced async queueing caused PPT/stutter
      // on some 16KB / OEM devices while sitting near the live tip.
      .setEnableDecoderFallback(true)

    val trackSelector = DefaultTrackSelector(context)
    trackSelector.parameters = trackSelector.buildUponParameters()
      .setMaxVideoBitrate(resolveMaxVideoBitrateBps())
      .build()

    // When JS opts into livePlayback (LLHLS), attach DefaultLivePlaybackSpeedControl so
    // LiveConfiguration catch-up speeds apply. Offset growth stays at Media3 default +500ms;
    // LiveConfiguration max/target offsets bound tip starvation and lag.
    val playerBuilder = ExoPlayer.Builder(context)
      .setLoadControl(loadControl)
      .setLooper(Looper.getMainLooper())
      .setRenderersFactory(renderersFactory)
      .setTrackSelector(trackSelector)

    if (bufferConfig?.livePlayback != null) {
      playerBuilder.setLivePlaybackSpeedControl(
        DefaultLivePlaybackSpeedControl.Builder().build()
      )
    }

    player = playerBuilder.build()

    loadedWithSource = true

    player.addListener(playerListener)
    player.addAnalyticsListener(analyticsListener)
    player.setMediaSource(hybridSource.mediaSource)

    // Emit onLoadStart
    val sourceType = if (hybridSource.uri.startsWith("http")) SourceType.NETWORK else SourceType.LOCAL
    eventEmitter.onLoadStart(onLoadStartData(sourceType = sourceType, source = hybridSource))
    status = VideoPlayerStatus.LOADING
    logLiveDiagnostics("initialize")
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
    currentTime = (currentTime + time).coerceIn(0.0, duration)
  }

  override fun seekTo(time: Double) {
    currentTime = time.coerceIn(0.0, duration)
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

      eventEmitter.clearAllListeners()

      player.removeListener(playerListener)
      player.removeAnalyticsListener(analyticsListener)
      player.release() // Release player

      // Clean Listeners
      audioFocusChangedListener.removeEventEmitter()
      audioBecomingNoisyReceiver.removeEventEmitter()

      // Update status
      status = VideoPlayerStatus.IDLE
    }
  }

  fun movePlayerToVideoView(videoView: VideoView) {
    VideoManager.addViewToPlayer(videoView, this)

    runOnMainThreadSync {
      if (videoView.useGreenScreen) {
        currentPlayerView = null
        val gl = videoView.ensureGreenScreenGlAttached()
        gl.setSurfaceReadyCallback { surface ->
          runOnMainThread {
            player.setVideoSurface(surface)
          }
        }
      } else {
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
    progressRunnable = object : Runnable {
      override fun run() {
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
          maybeLogLiveDiagnosticsPeriodic()
          progressHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS)
        }
      }
    }
    progressHandler.post(progressRunnable ?: return)
  }

  /** RCA logs for tip-starvation vs smooth playback. Filter: adb logcat -s RNVLiveDiag */
  private fun logLiveDiagnostics(reason: String) {
    try {
      val state = player.playbackState
      val currentMs = player.currentPosition
      val bufferedMs = player.bufferedPosition
      val aheadMs = max(0L, bufferedMs - currentMs)
      val liveOffsetMs = try {
        val offsetUs = player.currentLiveOffset
        if (offsetUs == C.TIME_UNSET) -1L else offsetUs / 1000L
      } catch (_: Throwable) {
        -1L
      }
      val format = player.videoFormat
      Log.i(
        LIVE_DIAG_TAG,
        "reason=$reason state=${playbackStateName(state)} playing=${player.isPlaying} " +
          "playWhenReady=${player.playWhenReady} posMs=$currentMs bufferedMs=$bufferedMs " +
          "aheadMs=$aheadMs liveOffsetMs=$liveOffsetMs " +
          "speed=${player.playbackParameters.speed} " +
          "format=${format?.width}x${format?.height}@${format?.bitrate} " +
          "livePlayback=${bufferConfig?.livePlayback != null}"
      )
      lastLoggedPlaybackState = state
      lastLiveDiagAtMs = android.os.SystemClock.elapsedRealtime()
    } catch (t: Throwable) {
      Log.w(LIVE_DIAG_TAG, "logLiveDiagnostics failed: ${t.message}")
    }
  }

  private fun maybeLogLiveDiagnosticsPeriodic() {
    val now = android.os.SystemClock.elapsedRealtime()
    val aheadMs = max(0L, player.bufferedPosition - player.currentPosition)
    val stateChanged = player.playbackState != lastLoggedPlaybackState
    val lowBuffer = aheadMs < 2500L
    val due = now - lastLiveDiagAtMs >= LIVE_DIAG_INTERVAL_MS
    if (stateChanged || (due && (lowBuffer || player.playbackState == Player.STATE_BUFFERING))) {
      logLiveDiagnostics(
        when {
          stateChanged -> "state"
          lowBuffer -> "lowBuffer"
          else -> "periodic"
        }
      )
    }
  }

  private fun playbackStateName(state: Int): String {
    return when (state) {
      Player.STATE_IDLE -> "IDLE"
      Player.STATE_BUFFERING -> "BUFFERING"
      Player.STATE_READY -> "READY"
      Player.STATE_ENDED -> "ENDED"
      else -> "UNKNOWN($state)"
    }
  }

  private fun stopProgressUpdates() {
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
    }

    override fun onDroppedVideoFrames(
      eventTime: AnalyticsListener.EventTime,
      droppedFrames: Int,
      elapsedMs: Long
    ) {
      if (droppedFrames > 0) {
        Log.i(
          LIVE_DIAG_TAG,
          "droppedFrames=$droppedFrames elapsedMs=$elapsedMs " +
            "aheadMs=${max(0L, player.bufferedPosition - player.currentPosition)}"
        )
      }
    }
  }

  private val playerListener = object : Player.Listener {
    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
      maybeNotifyHlsSegmentChange()
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

      logLiveDiagnostics("onPlaybackStateChanged")

      when (playbackState) {
        Player.STATE_IDLE -> {
          status = VideoPlayerStatus.IDLE
          eventEmitter.onBuffer(false)
        }
        Player.STATE_BUFFERING -> {
          status = VideoPlayerStatus.LOADING
          eventEmitter.onBuffer(true)
        }
        Player.STATE_READY -> {
          status = VideoPlayerStatus.READYTOPLAY
          eventEmitter.onBuffer(false)

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
      status = VideoPlayerStatus.ERROR
      stopProgressUpdates()
    }

    override fun onPositionDiscontinuity(
      oldPosition: Player.PositionInfo,
      newPosition: Player.PositionInfo,
      reason: Int
    ) {
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

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
      eventEmitter.onPlaybackRateChange(playbackParameters.speed.toDouble())
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

    override fun onTracksChanged(tracks: Tracks) {
      super.onTracksChanged(tracks)
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
