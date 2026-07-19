package com.orailnoor.droiddesk.x11

import android.view.MotionEvent
import android.view.View
import com.termux.x11.LorieView
import com.termux.x11.MainActivity
import com.termux.x11.input.InputEventSender
import com.termux.x11.input.TouchInputHandler

/** Connects LorieView to the gesture/input implementation imported from Termux:X11. */
class X11InputController(private val lorieView: LorieView) {
    private val inputHandler = TouchInputHandler(
        MainActivity.getInstance(),
        InputEventSender(lorieView),
    )

    var mode: Int = TouchInputHandler.InputMode.TRACKPAD
        private set

    init {
        setMode(mode)
        MainActivity.getInstance().setKeyHandler(inputHandler::sendKeyEvent)
        lorieView.setCallback { width, height, transform ->
            inputHandler.handleInputTransformChanged(width, height, transform)
        }
        lorieView.setOnTouchListener(::handleMotionEvent)
        lorieView.setOnGenericMotionListener(::handleMotionEvent)
    }

    fun nextMode(): Int {
        val next = when (mode) {
            TouchInputHandler.InputMode.TOUCH -> TouchInputHandler.InputMode.TRACKPAD
            TouchInputHandler.InputMode.TRACKPAD -> TouchInputHandler.InputMode.SIMULATED_TOUCH
            else -> TouchInputHandler.InputMode.TOUCH
        }
        setMode(next)
        return next
    }

    fun modeLabel(): String = when (mode) {
        TouchInputHandler.InputMode.SIMULATED_TOUCH -> "Touchscreen"
        TouchInputHandler.InputMode.TOUCH -> "Direct touch"
        else -> "Trackpad"
    }

    fun dispose() {
        MainActivity.getInstance().setKeyHandler(null)
        lorieView.setOnTouchListener(null)
        lorieView.setOnGenericMotionListener(null)
        lorieView.setCallback(null)
    }

    private fun setMode(newMode: Int) {
        mode = newMode
        val prefs = MainActivity.getPrefs()
        prefs.touchMode.put(newMode.toString())
        inputHandler.reloadPreferences(prefs)
    }

    private fun handleMotionEvent(view: View, event: MotionEvent): Boolean =
        inputHandler.handleTouchEvent(lorieView, view, event)

    companion object {
        /** Phone UI default — changing this previously correlated with black screens. */
        const val DISPLAY_SCALE_PERCENT = 200

        /** Full desktop size used while VNC is shared to a laptop/monitor. */
        const val VNC_DESKTOP_WIDTH = 1920
        const val VNC_DESKTOP_HEIGHT = 1080

        const val DISPLAY_MODE_PHONE = "phone"
        const val DISPLAY_MODE_VNC = "vnc"

        /** Written by droiddesk-vnc-share / stop under \$HOME/.cache/ */
        const val DISPLAY_MODE_FILENAME = "droiddesk-display-mode"

        /** Must run before LorieView is measured so Xwayland starts at the scaled resolution. */
        fun configureDisplayScale() {
            applyPhoneDisplayPrefs()
        }

        fun applyPhoneDisplayPrefs() {
            MainActivity.getPrefs().apply {
                displayResolutionMode.put("scaled")
                displayScale.put(DISPLAY_SCALE_PERCENT)
                displayStretch.put(true)
                scaleTouchpad.put(true)
                adjustResolution.put(true)
                touchMode.put(TouchInputHandler.InputMode.TRACKPAD.toString())
            }
        }

        /**
         * Laptop-sized framebuffer for VNC viewers. The phone still shows this
         * surface (letterboxed/scaled); Mac/Pi get a real desktop resolution.
         */
        fun applyVncDesktopPrefs(
            width: Int = VNC_DESKTOP_WIDTH,
            height: Int = VNC_DESKTOP_HEIGHT,
        ) {
            MainActivity.getPrefs().apply {
                displayResolutionMode.put("exact")
                displayResolutionExact.put("${width}x${height}")
                displayStretch.put(true)
                scaleTouchpad.put(true)
                // Keep landscape/portrait swap off for a fixed VNC desktop.
                adjustResolution.put(false)
                touchMode.put(TouchInputHandler.InputMode.TRACKPAD.toString())
            }
        }
    }
}
