package com.brentvatne.react;

import android.content.Context;
import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Surface;

public class ChromaSurfaceView extends GLSurfaceView {

    private final static String TAG = "ChromaSurfaceView";

    public ChromaRenderer renderer;
    private Surface mSurface;
    private ChromaRenderer.OnSurfacePrepareListener onSurfacePrepareListener;

    public ChromaSurfaceView(Context context) {
        this(context, null);
    }

    public ChromaSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.setPreserveEGLContextOnPause(true);
        this.setEGLContextClientVersion(2);
        renderer = new ChromaRenderer();
        renderer.setOnSurfacePrepareListener(new ChromaRenderer.OnSurfacePrepareListener() {
            @Override
            public void surfacePrepared(final Surface surface) {
                mSurface = surface;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        if(onSurfacePrepareListener != null){
                            onSurfacePrepareListener.surfacePrepared(surface);
                        }
                    }
                });
            }
        });

        this.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        this.getHolder().setFormat(PixelFormat.RGBA_8888);
        this.setZOrderOnTop(true);
        setRenderer(renderer);
        this.setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    public void setOnSurfacePrepareListener(ChromaRenderer.OnSurfacePrepareListener onSurfacePrepareListener) {
        this.onSurfacePrepareListener = onSurfacePrepareListener;
    }
}