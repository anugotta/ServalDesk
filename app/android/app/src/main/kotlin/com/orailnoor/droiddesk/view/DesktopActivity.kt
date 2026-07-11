package com.orailnoor.droiddesk.view

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.util.Log
import java.io.File
import com.termux.x11.MainActivity as TermuxMainActivity
import com.termux.x11.LorieView
import com.termux.x11.CmdEntryPoint

class DesktopActivity : Activity() {
    private var lorieView: LorieView? = null
    private var isX11Started = false
    private var isSetupDone = false

    companion object {
        private const val TAG = "DesktopActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // CRITICAL: Do NOT create LorieView here. Use a plain placeholder.
        // This ensures onCreate is ultra-fast so the focus event is processed
        // before the 5-second ANR timeout.
        val placeholder = FrameLayout(this)
        placeholder.setBackgroundColor(Color.BLACK)
        setContentView(placeholder)

        Log.i(TAG, "DesktopActivity created with placeholder view")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isSetupDone) {
            isSetupDone = true
            Log.i(TAG, "Window focused — scheduling LorieView setup")

            // Set up the view immediately
            setupLorieView()

            // Wait 500ms to ensure the Android SurfaceView is fully attached,
            // measured, and its native surface (surfaceChanged) is created.
            // Connecting XCB before the surface exists causes a silent native failure.
            Handler(Looper.getMainLooper()).postDelayed({
                startNativeX11()
            }, 500)
        }
    }

    private fun setupLorieView() {
        Log.i(TAG, "Setting up LorieView")
        TermuxMainActivity.getInstance().initLorieView(this)
        lorieView = TermuxMainActivity.getInstance().lorieView
        lorieView!!.setBackgroundColor(Color.TRANSPARENT)
        
        // Force the SurfaceView to composite ON TOP of the Activity window.
        // This guarantees that even if the Android window has a black background
        // or failed to punch a hole for the SurfaceView, the X11 surface will 
        // be visible over everything.
        lorieView!!.setZOrderOnTop(true)
        
        setContentView(lorieView)
        Log.i(TAG, "LorieView set as content view")
    }

    private fun startNativeX11() {
        if (isX11Started || LorieView.connected()) {
            lorieView?.requestFocus()
            return
        }
        isX11Started = true

        Thread {
            try {
                val tmpDir = File(filesDir, "tmp")
                tmpDir.mkdirs()

                val x11Dir = File(tmpDir, ".X11-unix")
                x11Dir.mkdirs()

                Os.setenv("TMPDIR", tmpDir.absolutePath, true)
                Os.setenv("PREFIX", "", true)
                Os.setenv("HOME", filesDir.absolutePath, true)

                val xkbRoot = File(filesDir, "rootfs/usr/share/X11/xkb")
                if (xkbRoot.exists()) {
                    Os.setenv("XKB_CONFIG_ROOT", xkbRoot.absolutePath, true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set environment", e)
            }

            Log.i(TAG, "Starting X server with :0 -nolock")

            Looper.prepare()

            val success = CmdEntryPoint.start(arrayOf(":0", "-nolock"))
            Log.i(TAG, "X server start returned: $success")

            if (success) {
                val cmdEntryPoint = CmdEntryPoint()
                val fd = cmdEntryPoint.xConnection
                val logcatFd = cmdEntryPoint.logcatOutput

                // Prevent X server from deadlocking by consuming its log output pipe!
                if (logcatFd != null) {
                    Thread {
                        try {
                            val reader = java.io.BufferedReader(java.io.InputStreamReader(java.io.FileInputStream(logcatFd.fileDescriptor)))
                            while (true) {
                                val line = reader.readLine() ?: break
                                Log.d("Xlorie-Internal", line)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error reading X server logs", e)
                        }
                    }.start()
                }

                if (fd != null) {
                    // Only the UI callbacks and JNI calls that expect the main thread's JNIEnv
                    // can be posted to the main thread.
                    Handler(Looper.getMainLooper()).post {
                        try {
                            LorieView.connect(fd.detachFd())
                            Log.i(TAG, "LorieView connected on main thread!")
                            
                            lorieView?.triggerCallback()
                            lorieView?.requestFocus()
                            Log.i(TAG, "LorieView focused!")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to connect LorieView", e)
                        }
                    }
                } else {
                    Log.e(TAG, "getXConnection returned null")
                }

                Looper.loop()
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
