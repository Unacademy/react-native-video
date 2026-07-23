package com.twg.video.view.greenscreen;

import android.view.Surface;

/**
 * Invoked from the GL thread once {@link VideoRenderer} has created the {@link Surface}
 * ExoPlayer should decode into.
 */
public interface SurfaceReadyCallback {
  void onSurfaceReady(Surface surface);
}
