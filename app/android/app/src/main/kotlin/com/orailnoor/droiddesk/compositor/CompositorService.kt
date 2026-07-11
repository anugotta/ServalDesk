package com.orailnoor.droiddesk.compositor

import android.view.Surface

class CompositorService {

    init {
        System.loadLibrary("droiddesk_compositor")
    }

    /**
     * Starts the native Wayland compositor backend using the provided Android Surface.
     */
    fun startCompositor(surface: Surface) {
        nativeStartCompositor(surface)
    }

    /**
     * Stops the native Wayland compositor.
     */
    fun stopCompositor() {
        nativeStopCompositor()
    }

    /**
     * Injects a touch event into the Wayland compositor.
     */
    fun sendTouchEvent(action: Int, x: Float, y: Float) {
        nativeSendTouchEvent(action, x, y)
    }

    private external fun nativeStartCompositor(surface: Surface)
    private external fun nativeStopCompositor()
    private external fun nativeSendTouchEvent(action: Int, x: Float, y: Float)
}
