package com.brentvatne.exoplayer;

import android.annotation.TargetApi;
import android.content.Context;

import androidx.core.content.ContextCompat;

import android.util.AttributeSet;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Tracks;

import android.view.Surface;

import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.source.hls.HlsManifest;
import com.google.android.exoplayer2.source.hls.playlist.HlsMediaPlaylist;
import com.google.android.exoplayer2.text.Cue;
import com.google.common.collect.ImmutableList;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.ui.SubtitleView;
import com.google.android.exoplayer2.video.VideoSize;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@TargetApi(16)
public final class ExoPlayerView extends FrameLayout {

  private View surfaceView;
  private final View shutterView;
  private final SubtitleView subtitleLayout;
  private final AspectRatioFrameLayout layout;
  private final ComponentListener componentListener;
  private ExoPlayer player;
  private Context context;
  private ViewGroup.LayoutParams layoutParams;
  private FileChangeListener fileChangeListener;
  private boolean useTextureView = true;
  private boolean useSecureView = false;
  private boolean useGreenScreen = false;
  private boolean hideShutterView = false;

  public ExoPlayerView(Context context) {
    this(context, null);
  }

  public void setFileChangeListener(FileChangeListener listener) {
    fileChangeListener = listener;
  }

  public ExoPlayerView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public ExoPlayerView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);

    this.context = context;

    layoutParams = new ViewGroup.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.MATCH_PARENT);

    componentListener = new ComponentListener();

    FrameLayout.LayoutParams aspectRatioParams = new FrameLayout.LayoutParams(
      FrameLayout.LayoutParams.MATCH_PARENT,
      FrameLayout.LayoutParams.MATCH_PARENT);
    aspectRatioParams.gravity = Gravity.CENTER;
    layout = new AspectRatioFrameLayout(context);
    layout.setLayoutParams(aspectRatioParams);

    shutterView = new View(getContext());
    shutterView.setLayoutParams(layoutParams);
    shutterView.setBackgroundColor(ContextCompat.getColor(context, android.R.color.black));

    subtitleLayout = new SubtitleView(context);
    subtitleLayout.setLayoutParams(layoutParams);
    subtitleLayout.setUserDefaultStyle();
    subtitleLayout.setUserDefaultTextSize();

    updateSurfaceView();

    layout.addView(shutterView, 1, layoutParams);
    layout.addView(subtitleLayout, 2, layoutParams);

    addViewInLayout(layout, 0, aspectRatioParams);
  }

  private void clearVideoView() {
    if (surfaceView instanceof TextureView) {
      player.clearVideoTextureView((TextureView) surfaceView);
    } else if (surfaceView instanceof SurfaceView) {
      player.clearVideoSurfaceView((SurfaceView) surfaceView);
    }
  }

  private void setVideoView() {
    if (surfaceView instanceof TextureView) {
      player.setVideoTextureView((TextureView) surfaceView);
    } else if (surfaceView instanceof SurfaceView) {
      player.setVideoSurfaceView((SurfaceView) surfaceView);
    }
  }

  private void updateSurfaceView() {
    View view;
    if (!useTextureView || useSecureView) {
      view = new SurfaceView(context);
      if (useSecureView) {
        ((SurfaceView) view).setSecure(true);
      }
    } else {
      view = new TextureView(context);
    }
    view.setLayoutParams(layoutParams);

    surfaceView = view;
    if (layout.getChildAt(0) != null) {
      layout.removeViewAt(0);
    }
    layout.addView(surfaceView, 0, layoutParams);
    // if (view instanceof GLTextureView) {
    //     GLTextureView glTextureView = (GLTextureView) view;
    //     glTextureView.setOpaque(false);
    //     glTextureView.setOnSurfaceCreatedCallBack(new OnSurfaceCreatedCallBack() {
    //         @Override
    //         public void onSurfaceCreated() {
    //             if (ExoPlayerView.this.player != null) {
    //                 setVideoView();
    //                 player.addVideoListener(componentListener);
    //                 player.addListener(componentListener);
    //                 player.addTextOutput(componentListener);
    //             }
    //         }
    //     });
    // } else {
    if (this.player != null) {
      setVideoView();
    }
    //}
  }

  private void updateShutterViewVisibility() {
    shutterView.setVisibility(this.hideShutterView ? View.INVISIBLE : View.VISIBLE);
  }

  public interface OnSurfaceCreatedCallBack {
    void onSurfaceCreated(Surface surface);
  }

  /**
   * Set the {@link SimpleExoPlayer} to use. The {@link SimpleExoPlayer#} and
   * {@link SimpleExoPlayer#} method of the player will be called and previous
   * assignments are overridden.
   *
   * @param player The {@link SimpleExoPlayer} to use.
   */
  public void setPlayer(ExoPlayer player) {
    if (this.player == player) {
      return;
    }
    if (this.player != null) {
      // this.player.removeTextOutput(componentListener);
      // this.player.removeVideoListener(componentListener);
      this.player.removeListener(componentListener);
      clearVideoView();
    }
    this.player = player;
    shutterView.setVisibility(this.hideShutterView ? View.INVISIBLE : View.VISIBLE);
    if (player != null) {
      setVideoView();
      // player.addVideoListener(componentListener);
      player.addListener(componentListener);
      // player.addTextOutput(componentListener);
    }
  }

  /**
   * Sets the resize mode which can be of value {@link ResizeMode.Mode}
   *
   * @param resizeMode The resize mode.
   */
  public void setResizeMode(@ResizeMode.Mode int resizeMode) {
    if (layout.getResizeMode() != resizeMode) {
      layout.setResizeMode(resizeMode);
      post(measureAndLayout);
    }

  }

  /**
   * Get the view onto which video is rendered. This is either a {@link SurfaceView} (default)
   * or a {@link TextureView} if the {@code use_texture_view} view attribute has been set to true.
   *
   * @return either a {@link SurfaceView} or a {@link TextureView}.
   */
  public View getVideoSurfaceView() {
    return surfaceView;
  }

  public void setUseTextureView(boolean useTextureView) {
    if (useTextureView != this.useTextureView) {
      this.useTextureView = useTextureView;
      updateSurfaceView();
    }
  }

  public void setHideShutterView(boolean hideShutterView) {
    this.hideShutterView = hideShutterView;
    updateShutterViewVisibility();
  }

  public void useSecureView(boolean useSecureView) {
    if (useSecureView != this.useSecureView) {
      this.useSecureView = useSecureView;
      updateSurfaceView();
    }
  }

  public void setUseGreenScreen(boolean useGreenScreen) {
    this.useGreenScreen = useGreenScreen;
    if (useGreenScreen) {
      updateSurfaceView();
    }
  }

  private final Runnable measureAndLayout = new Runnable() {
    @Override
    public void run() {
      measure(
        MeasureSpec.makeMeasureSpec(getWidth(), MeasureSpec.EXACTLY),
        MeasureSpec.makeMeasureSpec(getHeight(), MeasureSpec.EXACTLY));
      layout(getLeft(), getTop(), getRight(), getBottom());
    }
  };

  private void updateForCurrentTrackSelections() {
    if (player == null) {
      return;
    }
    TrackSelectionArray selections = player.getCurrentTrackSelections();
    if (selections == null) {
      return;
    }
    for (int i = 0; i < selections.length; i++) {
      if (player.getRendererType(i) == C.TRACK_TYPE_VIDEO && selections.get(i) != null) {
        // Video enabled so artwork must be hidden. If the shutter is closed, it will be opened in
        // onRenderedFirstFrame().
        return;
      }
    }
    // Video disabled so the shutter must be closed.
    shutterView.setVisibility(this.hideShutterView ? View.INVISIBLE : View.VISIBLE);
  }

  public int compareL(long x, long y) {
    return (x < y) ? -1 : ((x == y) ? 0 : 1);
  }

  public void sendFileChangeEventForTime(long time) {
    Object manifest = player.getCurrentManifest();
    if (manifest instanceof HlsManifest) {
      HlsMediaPlaylist.Segment segment = new HlsMediaPlaylist.Segment("", null, "", 0, 0, time * 1000, null, "", "", 0, 0, false, ImmutableList.of());

      int index = Collections.binarySearch(((HlsManifest) manifest).mediaPlaylist.segments, segment, new Comparator<HlsMediaPlaylist.Segment>() {
        @Override
        public int compare(HlsMediaPlaylist.Segment o1, HlsMediaPlaylist.Segment o2) {
          return compareL(o1.relativeStartTimeUs, o2.relativeStartTimeUs);
        }
      });

      if (index < 0) {
        index = -1 * index - 2;
      }

      if (index >= 0 && index < ((HlsManifest) manifest).mediaPlaylist.segments.size()) {
        try {
          String url = ((HlsManifest) manifest).mediaPlaylist.segments.get(index).url;
          String file = "";
          if (url.contains("ts")) {
            String[] urlSplit = ((HlsManifest) manifest).mediaPlaylist.segments.get(index).url.split("-");
            long val = Long.parseLong(urlSplit[urlSplit.length - 1].replace(".ts", ""));
            file = val + "";
          } else if (url.contains("m4s")) {
            file = url.replace(".m4s", "");
          } else {
            file = url;
          }
          if (fileChangeListener != null) {
            try {
              fileChangeListener.onFileChange(file, ((HlsManifest) manifest).mediaPlaylist.segments.get(index).relativeStartTimeUs, ((HlsManifest) manifest).mediaPlaylist.durationUs);
            } catch (Exception ignore) {
//                            ignore.printStackTrace();
            }
          }
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    }
  }

  public void sendFileChangeEventForTime() {
    if (player == null) {
      return;
    }
    Long currentPosition = player.getCurrentPosition();
    if (currentPosition != null) {
      sendFileChangeEventForTime(currentPosition);
    }
  }

  public interface FileChangeListener {
    public void onFileChange(String file, long time, long duration);
  }

  public void invalidateAspectRatio() {
    // Resetting aspect ratio will force layout refresh on next video size changed
    layout.invalidateAspectRatio();
  }

  private final class ComponentListener implements Player.Listener {

    // TextRenderer.Output implementation

    @Override
    public void onCues(List<Cue> cues) {
      subtitleLayout.setCues(cues);
    }

    // SimpleExoPlayer.VideoListener implementation

    @Override
    public void onVideoSizeChanged(VideoSize videoSize) {
      boolean isInitialRatio = layout.getAspectRatio() == 0;
      layout.setAspectRatio(videoSize.height == 0 ? 1 : (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height);

      // React native workaround for measuring and layout on initial load.
      if (isInitialRatio) {
        post(measureAndLayout);
      }
    }

    @Override
    public void onRenderedFirstFrame() {
      shutterView.setVisibility(INVISIBLE);
    }

    // ExoPlayer.EventListener implementation

    @Override
    public void onIsLoadingChanged(boolean isLoading) {
      sendFileChangeEventForTime();
      // Do nothing.
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
      sendFileChangeEventForTime();
      // Do nothing.
    }

    @Override
    public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
      // Do nothing.
    }

    @Override
    public void onPlayerError(PlaybackException e) {
      // Do nothing.
    }

    // @Override
    // public void onPositionDiscontinuity(int reason) {
    //     sendFileChangeEventForTime();
    //     // Do nothing.
    // }
    @Override
    public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
      sendFileChangeEventForTime();
      // Do nothing.
    }


    @Override
    public void onTimelineChanged(Timeline timeline, int reason) {
//            if (manifest instanceof HlsManifest && (reason == Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)) {
//                sendFileChangeEventForTime();
//            }
      // Do nothing.
    }

    // @Override
    // public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
    //     updateForCurrentTrackSelections();
    // }

    @Override
    public void onTracksChanged(Tracks tracks) {
      updateForCurrentTrackSelections();
    }

    @Override
    public void onPlaybackParametersChanged(PlaybackParameters params) {
      sendFileChangeEventForTime();
      // Do nothing
    }

    // @Override
    // public void onSeekProcessed() {
    //     sendFileChangeEventForTime();
    //     // Do nothing.
    // }

    @Override
    public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
      // Do nothing.
    }

    @Override
    public void onRepeatModeChanged(int repeatMode) {
      // Do nothing.
    }
  }

}