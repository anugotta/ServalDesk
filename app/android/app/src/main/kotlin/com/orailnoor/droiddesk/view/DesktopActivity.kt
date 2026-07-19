package com.orailnoor.droiddesk.view

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.graphics.Color
import android.graphics.Rect
import android.provider.Settings
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.SurfaceHolder
import android.view.ViewGroup
import android.view.View
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Log
import android.widget.Toast
import android.widget.Button
import android.view.Gravity
import android.content.res.ColorStateList
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import java.io.File
import com.termux.x11.MainActivity as TermuxMainActivity
import com.termux.x11.LorieView
import com.orailnoor.droiddesk.MainActivity
import com.orailnoor.droiddesk.runtime.LinuxRuntime
import com.orailnoor.droiddesk.runtime.ChrootRuntime
import com.orailnoor.droiddesk.runtime.XfceMobileProfile
import com.orailnoor.droiddesk.service.DroidDeskService
import com.orailnoor.droiddesk.x11.X11ServiceClient
import com.orailnoor.droiddesk.x11.X11InputController

class DesktopActivity : Activity() {
    private var lorieView: LorieView? = null
    private var connectionRequested = false
    private var isSetupDone = false
    private var shouldStartSession = false
    private var sessionMode = "termux"
    private var desktopEnv = "xfce4"
    private lateinit var linuxRuntime: LinuxRuntime
    private lateinit var chrootRuntime: ChrootRuntime
    private lateinit var placeholder: FrameLayout
    private lateinit var desktopSurface: FrameLayout
    private var statusText: TextView? = null
    private var x11ServiceClient: X11ServiceClient? = null
    private var inputController: X11InputController? = null
    private var inputModeButton: Button? = null
    private var controlOverlay: LinearLayout? = null
    private var collapsedControl: Button? = null
    private var surfaceCallback: SurfaceHolder.Callback? = null
    private var x11RetryUsed = false
    private var lastSurfaceW = 0
    private var lastSurfaceH = 0
    private var geometryChangeGeneration = 0
    private var displayModeObserver: FileObserver? = null
    private var lastDisplayMode: String = X11InputController.DISPLAY_MODE_PHONE
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollDisplayMode = object : Runnable {
        override fun run() {
            applyDisplayModeFromFlag(force = false)
            mainHandler.postDelayed(this, 1500)
        }
    }

    companion object {
        private const val TAG = "DesktopActivity"
        const val EXTRA_DESKTOP_ERROR = "desktopError"
        /** Minimum black inset so rounded phone corners don't clip the desktop. */
        private const val MIN_SAFE_INSET_DP = 18f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        linuxRuntime = LinuxRuntime(this)
        chrootRuntime = ChrootRuntime(this)
        applyIntentExtras(intent)

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (shouldStartSession) {
            startLinuxForegroundService()
        }

        placeholder = FrameLayout(this)
        placeholder.setBackgroundColor(Color.BLACK)
        desktopSurface = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        statusText = TextView(this).apply {
            text = "Starting Linux desktop…"
            setTextColor(Color.LTGRAY)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        placeholder.addView(
            desktopSurface,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        placeholder.addView(
            statusText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        setContentView(placeholder)
        enableImmersiveMode()
        applySafeAreaInsets()

        // Do NOT wait only for window focus — home/boot launch can stay unfocused
        // briefly and previously left users on a permanent black screen.
        placeholder.post { ensureLorieSetup("onCreate-post") }
        placeholder.postDelayed({ ensureLorieSetup("onCreate-delayed") }, 700)

        Log.i(TAG, "DesktopActivity created mode=$sessionMode startSession=$shouldStartSession")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntentExtras(intent)
        if (shouldStartSession && LorieView.connected()) {
            startDesktopSessionIfRequested()
        }
    }

    private fun applyIntentExtras(intent: Intent?) {
        if (intent == null) return
        shouldStartSession = intent.getBooleanExtra("startSession", false) || shouldStartSession
        sessionMode = intent.getStringExtra("mode")
            ?: if (chrootRuntime.hasRoot()) "chroot" else "termux"
        desktopEnv = intent.getStringExtra("de") ?: desktopEnv.ifEmpty { "xfce4" }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveMode()
            applySafeAreaInsets()
            ensureLorieSetup("window-focus")
        }
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
        applySafeAreaInsets()
        ensureLorieSetup("onResume")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.i(
            TAG,
            "onConfigurationChanged orientation=${newConfig.orientation} " +
                "size=${newConfig.screenWidthDp}x${newConfig.screenHeightDp}",
        )
        handleDisplayGeometryChange("configuration-changed")
    }

    private fun ensureLorieSetup(reason: String) {
        if (isSetupDone || isFinishing) return
        isSetupDone = true
        Log.i(TAG, "Setting up LorieView ($reason)")
        setupLorieView()
    }

    /**
     * Portrait/landscape (and foldable/size) changes: re-apply the black safe
     * border, force LorieView to publish a new X11 root size, then best-effort
     * maximize XFCE windows so they follow the new bounds.
     */
    private fun handleDisplayGeometryChange(reason: String) {
        if (!isSetupDone || isFinishing) return
        val generation = ++geometryChangeGeneration
        Log.i(TAG, "Handling display geometry change ($reason) gen=$generation")
        enableImmersiveMode()
        applySafeAreaInsets()
        desktopSurface.requestLayout()
        lorieView?.requestLayout()
        placeholder.requestLayout()

        // Layout → surface size update can take a frame or two after rotate.
        desktopSurface.post {
            if (generation != geometryChangeGeneration || isFinishing) return@post
            applySafeAreaInsets()
            refreshX11Viewport("post-layout")
        }
        desktopSurface.postDelayed({
            if (generation != geometryChangeGeneration || isFinishing) return@postDelayed
            applySafeAreaInsets()
            refreshX11Viewport("post-layout-delayed")
            resizeLinuxWindowsToFit()
        }, 400)
        desktopSurface.postDelayed({
            if (generation != geometryChangeGeneration || isFinishing) return@postDelayed
            refreshX11Viewport("post-layout-settle")
            resizeLinuxWindowsToFit()
        }, 1000)
        desktopSurface.postDelayed({
            if (generation != geometryChangeGeneration || isFinishing) return@postDelayed
            resizeLinuxWindowsToFit()
        }, 1800)
    }

    private fun refreshX11Viewport(reason: String) {
        val view = lorieView ?: return
        val w = view.width
        val h = view.height
        Log.i(TAG, "refreshX11Viewport ($reason) view=${w}x${h} connected=${LorieView.connected()}")
        if (w <= 0 || h <= 0) {
            view.requestLayout()
            return
        }
        try {
            // Force measure → updateViewport → sendWindowChange with new geometry.
            view.requestLayout()
            view.triggerCallback()
        } catch (error: Throwable) {
            Log.w(TAG, "triggerCallback failed ($reason)", error)
        }
    }

    private fun resizeLinuxWindowsToFit() {
        if (!isSessionRunning()) return
        Thread({
            // Do NOT run `xfce4-panel -r` — it triggers GDBus panel dialogs.
            val script = """
                export DISPLAY=:0
                export PATH="${'$'}HOME/.local/bin:${'$'}PREFIX/bin:/usr/bin:/bin:${'$'}PATH"
                ${XfceMobileProfile.fitWindowsScript()}
            """.trimIndent()
            try {
                val result = if (sessionMode == "chroot") {
                    chrootRuntime.executeCommand(script)
                } else {
                    linuxRuntime.executeDetached(script)
                }
                if (result.startsWith("Error:")) {
                    Log.w(TAG, "resizeLinuxWindowsToFit: $result")
                } else {
                    Log.i(TAG, "resizeLinuxWindowsToFit finished")
                }
            } catch (error: Throwable) {
                Log.w(TAG, "resizeLinuxWindowsToFit failed", error)
            }
        }, "ResizeLinuxWindows").start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Home-launcher surface: do not finish into a blank task.
    }

    @Suppress("DEPRECATION")
    private fun enableImmersiveMode() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.decorView.windowInsetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
            )
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun applySafeAreaInsets() {
        val density = resources.displayMetrics.density
        val minInset = (MIN_SAFE_INSET_DP * density).toInt()
        val cutout = Rect()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val insets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                window.decorView.rootWindowInsets
            } else {
                null
            }
            val displayCutout = insets?.displayCutout
            if (displayCutout != null) {
                cutout.set(
                    displayCutout.safeInsetLeft,
                    displayCutout.safeInsetTop,
                    displayCutout.safeInsetRight,
                    displayCutout.safeInsetBottom,
                )
            }
        }
        val left = maxOf(minInset, cutout.left)
        val top = maxOf(minInset, cutout.top)
        val right = maxOf(minInset, cutout.right)
        val bottom = maxOf(minInset, cutout.bottom)

        val lp = desktopSurface.layoutParams as FrameLayout.LayoutParams
        lp.setMargins(left, top, right, bottom)
        desktopSurface.layoutParams = lp
        // Outer placeholder stays black → visible border around the Linux surface.
        placeholder.setBackgroundColor(Color.BLACK)
    }

    private fun setupLorieView() {
        try {
            X11InputController.configureDisplayScale()
            TermuxMainActivity.getInstance().initLorieView(this)
            lorieView = TermuxMainActivity.getInstance().lorieView

            lorieView!!.setZOrderOnTop(false)
            desktopSurface.setBackgroundColor(Color.BLACK)

            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            (lorieView!!.parent as? ViewGroup)?.removeView(lorieView)
            desktopSurface.addView(lorieView, params)
            statusText?.text = "Connecting display…"
            Log.i(TAG, "LorieView added to safe-area surface")

            surfaceCallback = object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    Log.i(TAG, "LorieView surfaceCreated")
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    Log.i(TAG, "LorieView surfaceChanged ${width}x${height}")
                    if (width <= 0 || height <= 0) return
                    val sizeChanged = width != lastSurfaceW || height != lastSurfaceH
                    lastSurfaceW = width
                    lastSurfaceH = height
                    synchronized(this@DesktopActivity) {
                        if (!connectionRequested) {
                            connectionRequested = true
                            connectToX11Service()
                        } else if (sizeChanged && LorieView.connected()) {
                            // Rotate / resize after the first connect.
                            refreshX11Viewport("surfaceChanged")
                            resizeLinuxWindowsToFit()
                        }
                    }
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    Log.i(TAG, "LorieView surfaceDestroyed")
                }
            }.also { lorieView!!.holder.addCallback(it) }
        } catch (error: Throwable) {
            Log.e(TAG, "setupLorieView failed", error)
            showX11Error("Failed to create desktop surface", error)
        }
    }

    private fun connectToX11Service() {
        if (LorieView.connected()) {
            attachDesktopInput()
            return
        }

        x11ServiceClient = X11ServiceClient(
            context = this,
            onConnected = { connectionFd, logcatFd ->
                try {
                    LorieView.connect(connectionFd.detachFd())
                    logcatFd?.let { LorieView.startLogcat(it.detachFd()) }
                    Log.i(TAG, "LorieView connected to the :x11 service process")
                    runOnUiThread { statusText?.visibility = View.GONE }
                    attachDesktopInput()
                } catch (error: Throwable) {
                    connectionFd.close()
                    logcatFd?.close()
                    showX11Error("Failed to attach LorieView to the X11 service", error)
                }
            },
            onError = { message, error ->
                if (!x11RetryUsed) {
                    x11RetryUsed = true
                    Log.w(TAG, "X11 connect failed once, retrying: $message", error)
                    runOnUiThread {
                        statusText?.text = "Retrying display…"
                        connectionRequested = false
                        placeholder.postDelayed({
                            if (!LorieView.connected()) connectToX11Service()
                        }, 800)
                    }
                } else {
                    showX11Error(message, error)
                }
            },
        ).also { it.connect() }
    }

    private fun attachDesktopInput() {
        if (inputController == null) {
            inputController = X11InputController(lorieView!!)
        }
        // Rewrite helper scripts / dock Exec lines (fixes Termux #!/bin/sh permission denied).
        Thread({
            try {
                if (sessionMode == "chroot") chrootRuntime.refreshDesktopHelpers()
                else linuxRuntime.refreshDesktopHelpers()
            } catch (error: Throwable) {
                Log.w(TAG, "refreshDesktopHelpers failed", error)
            }
        }, "RefreshDesktopHelpers").start()
        startDisplayModeWatch()
        addDesktopControls()
        lorieView?.requestFocus()
        startDesktopSessionIfRequested()
    }

    private fun displayModeFile(): File =
        File(filesDir, "home/.cache/${X11InputController.DISPLAY_MODE_FILENAME}")

    private fun startDisplayModeWatch() {
        displayModeFile().parentFile?.mkdirs()
        applyDisplayModeFromFlag(force = true)
        mainHandler.removeCallbacks(pollDisplayMode)
        mainHandler.postDelayed(pollDisplayMode, 1500)

        try {
            @Suppress("DEPRECATION")
            displayModeObserver?.stopWatching()
            val dir = displayModeFile().parentFile ?: return
            displayModeObserver = object : FileObserver(
                dir.path,
                FileObserver.CREATE or FileObserver.MODIFY or FileObserver.MOVED_TO or
                    FileObserver.CLOSE_WRITE or FileObserver.DELETE,
            ) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null || path == X11InputController.DISPLAY_MODE_FILENAME ||
                        event and FileObserver.DELETE != 0
                    ) {
                        mainHandler.post { applyDisplayModeFromFlag(force = false) }
                    }
                }
            }.also { it.startWatching() }
        } catch (error: Throwable) {
            Log.w(TAG, "Display mode FileObserver unavailable; using poll only", error)
        }
    }

    private fun applyDisplayModeFromFlag(force: Boolean) {
        if (isFinishing || lorieView == null) return
        val mode = try {
            val file = displayModeFile()
            if (!file.exists()) X11InputController.DISPLAY_MODE_PHONE
            else file.readText().trim().lowercase().ifEmpty {
                X11InputController.DISPLAY_MODE_PHONE
            }
        } catch (_: Exception) {
            X11InputController.DISPLAY_MODE_PHONE
        }
        val normalized = when {
            mode == X11InputController.DISPLAY_MODE_VNC || mode.startsWith("1920") ->
                X11InputController.DISPLAY_MODE_VNC
            else -> X11InputController.DISPLAY_MODE_PHONE
        }
        if (!force && normalized == lastDisplayMode) return
        lastDisplayMode = normalized
        Log.i(TAG, "Applying display mode=$normalized")
        if (normalized == X11InputController.DISPLAY_MODE_VNC) {
            X11InputController.applyVncDesktopPrefs()
            Toast.makeText(this, "VNC desktop: 1920×1080", Toast.LENGTH_SHORT).show()
        } else {
            X11InputController.applyPhoneDisplayPrefs()
        }
        refreshX11Viewport("display-mode-$normalized")
        desktopSurface.postDelayed({ resizeLinuxWindowsToFit() }, 500)
        desktopSurface.postDelayed({ resizeLinuxWindowsToFit() }, 1200)
    }

    private fun addDesktopControls() {
        if (controlOverlay != null) return
        val density = resources.displayMetrics.density

        fun controlButton(label: String) = Button(this).apply {
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
            backgroundTintList = ColorStateList.valueOf(Color.argb(220, 28, 38, 52))
            elevation = 6 * density
            text = label
        }

        val dragHandle = controlButton("⋮").apply {
            contentDescription = "Drag desktop controls"
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
        }
        val keyboardButton = controlButton("Keyboard").apply {
            setOnClickListener { showKeyboard() }
        }
        inputModeButton = controlButton(inputController?.modeLabel() ?: "Trackpad").apply {
            setOnClickListener {
                inputController?.nextMode()
                text = inputController?.modeLabel() ?: "Trackpad"
                Toast.makeText(this@DesktopActivity, "Input mode: $text", Toast.LENGTH_SHORT).show()
            }
        }
        val dashboardButton = controlButton("Dashboard").apply {
            setOnClickListener { openFlutterDashboard() }
            setOnLongClickListener {
                openHomeSettings()
                true
            }
        }
        val androidButton = controlButton("Android").apply {
            contentDescription = "Open Android home screen"
            setOnClickListener { openAndroidHome() }
            setOnLongClickListener {
                openHomeSettings()
                true
            }
        }
        val hideButton = controlButton("−").apply {
            contentDescription = "Hide desktop controls"
            setOnClickListener { setControlsCollapsed(true) }
            setPadding((9 * density).toInt(), 0, (9 * density).toInt(), 0)
        }

        controlOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dragHandle, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, (42 * density).toInt(),
            ))
            addView(keyboardButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, (42 * density).toInt(),
            ))
            addView(inputModeButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, (42 * density).toInt(),
            ))
            addView(dashboardButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, (42 * density).toInt(),
            ))
            addView(androidButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, (42 * density).toInt(),
            ))
            addView(hideButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, (42 * density).toInt(),
            ))
        }

        collapsedControl = controlButton("☰").apply {
            contentDescription = "Show desktop controls"
            visibility = View.INVISIBLE
        }

        val overlayParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END,
        ).apply {
            rightMargin = (8 * density).toInt()
            topMargin = (52 * density).toInt()
        }
        val collapsedParams = FrameLayout.LayoutParams(
            (48 * density).toInt(),
            (42 * density).toInt(),
            Gravity.TOP or Gravity.END,
        ).apply {
            rightMargin = overlayParams.rightMargin
            topMargin = overlayParams.topMargin
        }

        placeholder.addView(controlOverlay, overlayParams)
        placeholder.addView(collapsedControl, collapsedParams)
        dragHandle.setOnTouchListener(dragListener(controlOverlay!!))
        collapsedControl?.setOnTouchListener(dragListener(collapsedControl!!) {
            setControlsCollapsed(false)
        })
        controlOverlay?.bringToFront()
    }

    private fun openFlutterDashboard() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            },
        )
    }

    /**
     * Leave the Linux desktop for the phone's stock Android home.
     * A plain CATEGORY_HOME intent would bounce back here while we are the
     * default launcher, so we start another HOME activity explicitly.
     */
    private fun openAndroidHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PackageManager.MATCH_ALL
        } else {
            0
        }
        val others = packageManager.queryIntentActivities(homeIntent, flags)
            .mapNotNull { it.activityInfo }
            .filter { it.packageName != packageName && it.exported }
            .sortedByDescending { info ->
                val system = (info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (system) 1 else 0
            }

        val target = others.firstOrNull()
        if (target == null) {
            Toast.makeText(
                this,
                "No other Android home app found — long-press Android to change default",
                Toast.LENGTH_LONG,
            ).show()
            openHomeSettings()
            return
        }

        try {
            startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    setClassName(target.packageName, target.name)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
                    )
                },
            )
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to open Android home ${target.packageName}/${target.name}", error)
            Toast.makeText(this, "Could not open Android home", Toast.LENGTH_SHORT).show()
            openHomeSettings()
        }
    }

    private fun openHomeSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(RoleManager::class.java)
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
                    return
                }
            }
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to open home settings", error)
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Throwable) {
                Toast.makeText(this, "Open Settings → Apps → Default home", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun dragListener(target: View, onTap: (() -> Unit)? = null): View.OnTouchListener {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0f
        var startY = 0f
        var dragged = false
        val threshold = resources.displayMetrics.density * 6

        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = target.x
                    startY = target.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (kotlin.math.abs(dx) > threshold || kotlin.math.abs(dy) > threshold) {
                        dragged = true
                    }
                    target.x = (startX + dx).coerceIn(0f, (placeholder.width - target.width).coerceAtLeast(0).toFloat())
                    target.y = (startY + dy).coerceIn(0f, (placeholder.height - target.height).coerceAtLeast(0).toFloat())
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) onTap?.invoke()
                    true
                }
                else -> false
            }
        }
    }

    private fun setControlsCollapsed(collapsed: Boolean) {
        val from = if (collapsed) controlOverlay else collapsedControl
        val to = if (collapsed) collapsedControl else controlOverlay
        to?.x = from?.x ?: 0f
        to?.y = from?.y ?: 0f
        from?.visibility = View.INVISIBLE
        to?.visibility = View.VISIBLE
        to?.bringToFront()
    }

    private fun showKeyboard() {
        val view = lorieView ?: return
        val inputMethod = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        view.requestFocus()
        inputMethod.restartInput(view)
        view.post {
            inputMethod.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun isSessionRunning(): Boolean {
        return if (sessionMode == "chroot") {
            chrootRuntime.isRunning()
        } else {
            linuxRuntime.isRunning()
        }
    }

    private fun startDesktopSessionIfRequested() {
        if (!shouldStartSession) return
        shouldStartSession = false
        if (isSessionRunning()) {
            Log.i(TAG, "Desktop session already running — skipping restart")
            runOnUiThread { statusText?.visibility = View.GONE }
            return
        }
        Thread({
            Log.i(TAG, "Starting Linux desktop session after X server connection")
            try {
                if (sessionMode == "chroot") {
                    chrootRuntime.startSession(desktopEnv)
                } else {
                    linuxRuntime.startSession(desktopEnv, "x11")
                }
                runOnUiThread { statusText?.visibility = View.GONE }
            } catch (error: Throwable) {
                Log.e(TAG, "Desktop session failed", error)
                fallbackToFlutter("Desktop session failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }, "LinuxDesktopSession").start()
    }

    private fun showX11Error(message: String, error: Throwable?) {
        Log.e(TAG, message, error)
        val detail = if (error != null) "$message (${error.message ?: error.javaClass.simpleName})" else message
        Toast.makeText(this, "X11 Error: $message", Toast.LENGTH_LONG).show()
        fallbackToFlutter(detail)
    }

    private fun fallbackToFlutter(message: String) {
        runOnUiThread {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    putExtra(EXTRA_DESKTOP_ERROR, message)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
            )
            finish()
        }
    }

    private fun startLinuxForegroundService() {
        try {
            val intent = Intent(this, DroidDeskService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Could not start DroidDeskService", error)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(pollDisplayMode)
        try {
            displayModeObserver?.stopWatching()
        } catch (_: Throwable) {
        }
        displayModeObserver = null
        surfaceCallback?.let { callback -> lorieView?.holder?.removeCallback(callback) }
        surfaceCallback = null
        inputController?.dispose()
        inputController = null
        x11ServiceClient?.disconnect()
        x11ServiceClient = null
        super.onDestroy()
    }
}
