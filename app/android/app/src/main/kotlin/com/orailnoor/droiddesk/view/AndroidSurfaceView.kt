package com.orailnoor.droiddesk.view

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.View
import android.os.Handler
import android.os.Looper
import android.system.Os
import io.flutter.plugin.platform.PlatformView
import com.termux.x11.MainActivity
import com.termux.x11.LorieView
import com.termux.x11.CmdEntryPoint
import java.io.File

class AndroidSurfaceView(
    val context: Context,
    id: Int,
    creationParams: Map<String?, Any?>?
) : PlatformView {

    private val lorieView: LorieView
    private val cmdEntryPoint: CmdEntryPoint = CmdEntryPoint()

    companion object {
        private const val TAG = "AndroidSurfaceView"
    }

    init {
        MainActivity.getInstance().initLorieView(context)
        lorieView = MainActivity.getInstance().lorieView
        lorieView.setBackgroundColor(Color.TRANSPARENT)
        lorieView.setZOrderMediaOverlay(true)
        startNativeX11()
    }

    private fun startNativeX11() {
        if (!LorieView.connected()) {
            Thread {
                try {
                    // Use the SAME tmp dir that PRoot binds to /tmp.
                    // LinuxRuntime.tmpDir = File(context.filesDir, "tmp")
                    // PRoot uses: --bind=${context.filesDir}/tmp:/tmp
                    val tmpDir = File(context.filesDir, "tmp")
                    tmpDir.mkdirs()

                    // Pre-create .X11-unix directory so the X server can create
                    // its socket file there
                    val x11Dir = File(tmpDir, ".X11-unix")
                    x11Dir.mkdirs()

                    Log.i(TAG, "TMPDIR=${tmpDir.absolutePath}")
                    Log.i(TAG, ".X11-unix dir exists: ${x11Dir.exists()}, writable: ${x11Dir.canWrite()}")

                    Os.setenv("TMPDIR", tmpDir.absolutePath, true)
                    Os.setenv("PREFIX", "", true)
                    Os.setenv("HOME", context.filesDir.absolutePath, true)

                    // Set XKB_CONFIG_ROOT to suppress the warning
                    val xkbRoot = File(context.filesDir, "rootfs/usr/share/X11/xkb")
                    if (xkbRoot.exists()) {
                        Os.setenv("XKB_CONFIG_ROOT", xkbRoot.absolutePath, true)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set environment", e)
                }

                Log.i(TAG, "Starting X server with :0 -nolock -extension MIT-SHM")

                // CmdEntryPoint.start() uses AChoreographer internally, which
                // requires a Looper on the calling thread. Without it, the
                // native code crashes with SIGSEGV (null Choreographer).
                Looper.prepare()

                val success = CmdEntryPoint.start(arrayOf(":0", "-nolock"))
                Log.i(TAG, "X server start returned: $success")

                if (success) {
                    // Wait a moment for the server to fully initialize its sockets
                    Thread.sleep(500)

                    // LorieView.connect and triggerCallback must run on the
                    // MAIN thread because triggerCallback calls requestFocus()
                    Handler(Looper.getMainLooper()).post {
                        val fd = cmdEntryPoint.xConnection
                        Log.i(TAG, "X connection fd: $fd")
                        if (fd != null) {
                            LorieView.connect(fd.detachFd())
                            lorieView.triggerCallback()
                            Log.i(TAG, "LorieView connected!")
                        } else {
                            Log.e(TAG, "Failed to get X connection fd!")
                        }
                    }

                    // Keep this thread's Looper running for Choreographer callbacks
                    Looper.loop()
                } else {
                    Log.e(TAG, "X server failed to start!")
                }
            }.start()
        }
    }

    override fun getView(): View {
        return lorieView
    }

    override fun dispose() {
        // Stop X11 connection if needed
    }
}
