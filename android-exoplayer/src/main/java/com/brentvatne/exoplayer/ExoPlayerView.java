package com.brentvatne.exoplayer;

import android.annotation.TargetApi;
import android.content.Context;
import androidx.core.content.ContextCompat;

import android.graphics.Color;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.brentvatne.react.GLTextureView;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.hls.HlsManifest;
import com.google.android.exoplayer2.source.hls.playlist.HlsMediaPlaylist;
import com.google.android.exoplayer2.video.VideoListener;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.TextRenderer;
import com.google.android.exoplayer2.text.TextOutput;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.ui.SubtitleView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;

@TargetApi(16)
public final class ExoPlayerView extends FrameLayout {

    private View surfaceView;
    private final View shutterView;
    private final SubtitleView subtitleLayout;
    private final AspectRatioFrameLayout layout;
    private final ComponentListener componentListener;
    private SimpleExoPlayer player;
    private Context context;
    private ViewGroup.LayoutParams layoutParams;

    private boolean useTextureView = true;
    private boolean hideShutterView = false;

    private ManifestFileChangeListener manifestFileChangeListener;

    private boolean useGreenScreen = false;

    public ExoPlayerView(Context context) {
        this(context, null);
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

    public void setManifestFileChangeListener(ManifestFileChangeListener listener) {
        this.manifestFileChangeListener = listener;
    }

    private int comparatorLong(long x, long y) {
        if(x == y) {
            return 0;
        }

        return x < y ? -1 : 1;
    }

    private void onManifestFileChange(long time) {
        Object manifest = player.getCurrentManifest();
        if(manifest instanceof HlsManifest) {
            List<HlsMediaPlaylist.Part> list = new ArrayList();

            HlsMediaPlaylist.Segment segment = new HlsMediaPlaylist.Segment(
                    "",
                    null,
                    "",
                    0,
                    0,
                    time * 1000,
                    null,
                    "",
                    "",
                    0,
                    0,
                    false,
                    list
            );

            int index = Collections.binarySearch(
                    ((HlsManifest) manifest).mediaPlaylist.segments,
                    segment,
                    (s1, s2) -> comparatorLong(s1.relativeStartTimeUs, s2.relativeStartTimeUs)
            );

            if(index < 0) {
                index = -1 * index - 2;
            }

            if(index >= 0 && index < ((HlsManifest) manifest).mediaPlaylist.segments.size()) {
                try {
                    String[] urlSplit = ((HlsManifest) manifest).mediaPlaylist.segments.get(index).url.split("-");
                    long val = Long.parseLong(urlSplit[urlSplit.length - 1].replace(".ts", ""));
                    long closestStartTimeUs = ((HlsManifest) manifest).mediaPlaylist.segments.get(index).relativeStartTimeUs;
                    if(manifestFileChangeListener != null) {
                        try {
                            manifestFileChangeListener.onManifestFileChange(
                                    Long.toString(val),
                                    closestStartTimeUs,
                                    ((HlsManifest) manifest).mediaPlaylist.durationUs
                            );
                        } catch (Exception ignore) {}
                    }
                } catch (Exception e) {}
            }
        }
    }

    private void onManifestFileChange() {
        onManifestFileChange(player.getCurrentPosition());
    }

    private void updateSurfaceView() {
        View view;
        if(this.useGreenScreen) {
            view = new GLTextureView(context);
        } else {
            view = useTextureView ? new TextureView(context) : new SurfaceView(context);
        }

        view.setLayoutParams(layoutParams);

        surfaceView = view;
        if (layout.getChildAt(0) != null) {
            layout.removeViewAt(0);
        }
        layout.addView(surfaceView, 0, layoutParams);

        if(view instanceof GLTextureView) {
            GLTextureView glTextureView = (GLTextureView) view;
            glTextureView.setOpaque(false);
            glTextureView.setOnSurfaceCreatedCallBack(() -> {
                if (ExoPlayerView.this.player != null) {
                    setVideoView();
                    this.player.addVideoListener(componentListener);
                    this.player.addListener(componentListener);
                    this.player.addTextOutput(componentListener);
                }
            });
        } else {
            if (this.player != null) {
                setVideoView();
            }
        }
    }

    private void updateShutterViewVisibility() {
        shutterView.setVisibility(this.hideShutterView ? View.INVISIBLE : View.VISIBLE);
    }

    /**
     * Set the {@link SimpleExoPlayer} to use. The {@link SimpleExoPlayer#addTextOutput} and
     * {@link SimpleExoPlayer#addVideoListener} method of the player will be called and previous
     * assignments are overridden.
     *
     * @param player The {@link SimpleExoPlayer} to use.
     */
    public void setPlayer(SimpleExoPlayer player) {
        if (this.player == player) {
            return;
        }
        if (this.player != null) {
            this.player.removeTextOutput(componentListener);
            this.player.removeVideoListener(componentListener);
            this.player.removeListener(componentListener);
            clearVideoView();
        }
        this.player = player;
        shutterView.setVisibility(VISIBLE);
        if (player != null && !this.useGreenScreen) {
            setVideoView();
            player.addVideoListener(componentListener);
            player.addListener(componentListener);
            player.addTextOutput(componentListener);
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
        for (int i = 0; i < selections.length; i++) {
            if (player.getRendererType(i) == C.TRACK_TYPE_VIDEO && selections.get(i) != null) {
                // Video enabled so artwork must be hidden. If the shutter is closed, it will be opened in
                // onRenderedFirstFrame().
                return;
            }
        }
        // Video disabled so the shutter must be closed.
        shutterView.setVisibility(VISIBLE);
    }

    public void invalidateAspectRatio() {
        // Resetting aspect ratio will force layout refresh on next video size changed
        layout.invalidateAspectRatio();
    }

    private final class ComponentListener implements VideoListener,
            TextOutput, ExoPlayer.EventListener {

        // TextRenderer.Output implementation

        @Override
        public void onCues(List<Cue> cues) {
            subtitleLayout.onCues(cues);
        }

        // SimpleExoPlayer.VideoListener implementation

        @Override
        public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
            boolean isInitialRatio = layout.getAspectRatio() == 0;
            layout.setAspectRatio(height == 0 ? 1 : (width * pixelWidthHeightRatio) / height);

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
        public void onLoadingChanged(boolean isLoading) {
            onManifestFileChange();
            // Do nothing.
        }

        @Override
        public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
            onManifestFileChange();
            // Do nothing.
        }

        @Override
        public void onPlayerError(ExoPlaybackException e) {
            // Do nothing.
        }

        @Override
        public void onPositionDiscontinuity(int reason) {
            onManifestFileChange();
            // Do nothing.
        }

        @Override
        public void onTimelineChanged(Timeline timeline, Object manifest, int reason) {
            if(manifest instanceof HlsManifest && reason == 2) {
                onManifestFileChange();
            }
            // Do nothing.
        }

        @Override
        public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
            updateForCurrentTrackSelections();
        }

        @Override
        public void onPlaybackParametersChanged(PlaybackParameters params) {
            onManifestFileChange();
            // Do nothing
        }

        @Override
        public void onSeekProcessed() {
            onManifestFileChange();
            // Do nothing.
        }

        @Override
        public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
            // Do nothing.
        }

        @Override
        public void onRepeatModeChanged(int repeatMode) {
            // Do nothing.
        }
    }

    public void setUseGreenScreen(@Nullable boolean useGreenScreen) {
        this.useGreenScreen = useGreenScreen;
        if(useGreenScreen) {
            updateSurfaceView();
        }
    }

    public interface ManifestFileChangeListener {
        void onManifestFileChange(String file, long time, long duration);
    }

    public interface OnSurfaceCreatedCallBack {
        void onSurfaceCreated();
    }

}
