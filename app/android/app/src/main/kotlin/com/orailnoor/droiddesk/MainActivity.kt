package com.orailnoor.droiddesk

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.content.Context
import android.net.Uri
import android.provider.Settings
import com.orailnoor.droiddesk.service.DroidDeskService
import com.orailnoor.droiddesk.runtime.LinuxRuntime
import com.orailnoor.droiddesk.runtime.RootfsManager
import com.orailnoor.droiddesk.view.AndroidSurfaceViewFactory
import kotlin.concurrent.thread
import android.os.Handler

class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "com.droiddesk/core"
    }

    private lateinit var linuxRuntime: LinuxRuntime
    private lateinit var rootfsManager: RootfsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        linuxRuntime = LinuxRuntime(this)
        rootfsManager = RootfsManager(this)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        flutterEngine
            .platformViewsController
            .registry
            .registerViewFactory("droiddesk-surface", AndroidSurfaceViewFactory())

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {

                // ── Runtime Status ──
                "getRuntimeStatus" -> {
                    result.success(mapOf(
                        "isBootstrapped" to linuxRuntime.isBootstrapped(),
                        "isRunning" to linuxRuntime.isRunning(),
                        "distro" to rootfsManager.getInstalledDistro(),
                        "installedDE" to rootfsManager.getInstalledDE(),
                        "rootfsPath" to rootfsManager.getRootfsPath(),
                        "rootfsSizeMB" to rootfsManager.getRootfsSizeMB()
                    ))
                }

                // ── Device Info ──
                "getDeviceInfo" -> {
                    result.success(mapOf(
                        "model" to Build.MODEL,
                        "brand" to Build.BRAND,
                        "androidVersion" to Build.VERSION.RELEASE,
                        "sdkVersion" to Build.VERSION.SDK_INT,
                        "cpuAbi" to Build.SUPPORTED_ABIS.firstOrNull(),
                        "gpuVendor" to getGpuVendor(),
                        "totalRamMB" to getTotalRam(),
                        "availableStorageMB" to getAvailableStorage()
                    ))
                }

                // ── Rootfs Management ──
                "downloadRootfs" -> {
                    val distro = call.argument<String>("distro") ?: "ubuntu"
                    rootfsManager.downloadRootfs(distro) { progress, status ->
                        runOnUiThread {
                            flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                MethodChannel(messenger, CHANNEL).invokeMethod(
                                    "onDownloadProgress",
                                    mapOf("progress" to progress, "status" to status)
                                )
                            }
                        }
                    }
                    result.success(true)
                }

                "extractRootfs" -> {
                    rootfsManager.extractRootfs { progress, status ->
                        runOnUiThread {
                            flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                MethodChannel(messenger, CHANNEL).invokeMethod(
                                    "onExtractProgress",
                                    mapOf("progress" to progress, "status" to status)
                                )
                            }
                        }
                    }
                    result.success(true)
                }
                
                "installDesktopEnvironment" -> {
                    val desktopEnv = call.argument<String>("de") ?: "xfce4"
                    val installType = call.argument<String>("type") ?: "minimal"
                    rootfsManager.installDesktopEnvironment(
                        desktopEnv, 
                        installType,
                        linuxRuntime, 
                        { progress, status ->
                            runOnUiThread {
                                flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                    MethodChannel(messenger, CHANNEL).invokeMethod(
                                        "onInstallProgress",
                                        mapOf("progress" to progress, "status" to status)
                                    )
                                }
                            }
                        },
                        { logChunk ->
                            runOnUiThread {
                                flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                    MethodChannel(messenger, CHANNEL).invokeMethod(
                                        "onTerminalOutput", 
                                        mapOf("text" to logChunk)
                                    )
                                }
                            }
                        }
                    )
                    result.success(true)
                }

                "startLinux" -> {
                    val desktopEnv = call.argument<String>("de") ?: "xfce4"
                    val mode = call.argument<String>("mode") ?: "vnc"
                    var width = call.argument<Int>("width") ?: 1920
                    var height = call.argument<Int>("height") ?: 1080
                    
                    // Enforce 720p maximum resolution for massive performance boost
                    if (height > 720) {
                        val scale = 720.0 / height
                        width = (width * scale).toInt()
                        height = 720
                    }
                    
                    startForegroundService()
                    thread {
                        linuxRuntime.startSession(desktopEnv, mode, width, height)
                    }
                    result.success(true)
                }

                "launchDesktopActivity" -> {
                    val intent = Intent(this@MainActivity, com.orailnoor.droiddesk.view.DesktopActivity::class.java)
                    startActivity(intent)
                    result.success(true)
                }

                "stopLinux" -> {
                    linuxRuntime.stopSession()
                    stopForegroundService()
                    result.success(true)
                }

                "executeCommand" -> {
                    val command = call.argument<String>("command") ?: ""
                    Thread {
                        val output = linuxRuntime.executeCommand(command) { chunk ->
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                    MethodChannel(messenger, CHANNEL).invokeMethod("onTerminalOutput", mapOf("text" to chunk))
                                }
                            }
                        }
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            result.success(output)
                        }
                    }.start()
                }

                "interruptCommand" -> {
                    linuxRuntime.interruptCommand()
                    result.success(true)
                }

                // ── System ──
                "requestBatteryOptimization" -> {
                    requestIgnoreBatteryOptimization()
                    result.success(true)
                }

                "isBatteryOptimized" -> {
                    result.success(isBatteryOptimized())
                }

                "setupBootstrap" -> {
                    linuxRuntime.setupBootstrap()
                    result.success(true)
                }

                else -> result.notImplemented()
            }
        }
    }

    // ── Foreground Service ──

    private fun startForegroundService() {
        val intent = Intent(this, DroidDeskService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopForegroundService() {
        val intent = Intent(this, DroidDeskService::class.java)
        stopService(intent)
    }

    // ── Battery Optimization ──

    private fun isBatteryOptimized(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimization() {
        if (isBatteryOptimized()) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    // ── Hardware Detection ──

    private fun getGpuVendor(): String {
        return try {
            val prop = Runtime.getRuntime().exec(arrayOf("getprop", "ro.hardware.egl"))
            val result = prop.inputStream.bufferedReader().readText().trim()
            prop.waitFor()
            if (result.isNotEmpty()) result else "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun getTotalRam(): Long {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024 * 1024)
    }

    private fun getAvailableStorage(): Long {
        val stat = android.os.StatFs(filesDir.absolutePath)
        return stat.availableBytes / (1024 * 1024)
    }
}
