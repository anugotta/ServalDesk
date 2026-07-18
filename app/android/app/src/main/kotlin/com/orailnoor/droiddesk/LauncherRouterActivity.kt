package com.orailnoor.droiddesk

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.orailnoor.droiddesk.runtime.ChrootRuntime
import com.orailnoor.droiddesk.runtime.LinuxRuntime
import com.orailnoor.droiddesk.view.DesktopActivity

/**
 * Default-home entry point. Routes boot / Home presses to the Linux desktop
 * when setup is complete, otherwise opens the Flutter setup/dashboard.
 */
class LauncherRouterActivity : Activity() {

    companion object {
        private const val TAG = "LauncherRouter"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        route()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route()
    }

    private fun route() {
        val chrootRuntime = ChrootRuntime(this)
        val linuxRuntime = LinuxRuntime(this)
        val rooted = chrootRuntime.hasRoot()
        val setupComplete = if (rooted) {
            chrootRuntime.isRootfsReady() && chrootRuntime.isDesktopInstalled()
        } else {
            linuxRuntime.isBootstrapped() && linuxRuntime.getInstalledDE().isNotEmpty()
        }

        if (setupComplete) {
            val desktopEnv = if (rooted) {
                chrootRuntime.getInstalledDE().ifEmpty { "xfce4" }
            } else {
                linuxRuntime.getInstalledDE().ifEmpty { "xfce4" }
            }
            val mode = if (rooted) "chroot" else "termux"
            Log.i(TAG, "Setup complete — launching desktop mode=$mode de=$desktopEnv")
            startActivity(
                Intent(this, DesktopActivity::class.java).apply {
                    putExtra("startSession", true)
                    putExtra("mode", mode)
                    putExtra("de", desktopEnv)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
        } else {
            Log.i(TAG, "Setup incomplete — opening Flutter dashboard")
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
        }
        finish()
    }
}
