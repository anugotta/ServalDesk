package com.droiddesk.droiddesk.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Core Linux runtime engine.
 *
 * Manages the proot-based Linux environment entirely within DroidDesk's
 * private data directory. No Termux dependency — we bundle our own proot
 * binary and bootstrap a standard Linux rootfs.
 *
 * Architecture:
 *   DroidDesk App (Kotlin)
 *     └─ ProcessBuilder → proot binary
 *         └─ rootfs (Ubuntu/Debian/Kali arm64)
 *             └─ Desktop Environment (XFCE/LXQt/MATE/KDE)
 *                 └─ Linux applications
 */
class LinuxRuntime(private val context: Context) {

    companion object {
        private const val TAG = "LinuxRuntime"
        private const val BOOTSTRAP_MARKER = ".droiddesk_bootstrapped"
    }

    private var prootProcess: Process? = null
    private var desktopProcess: Process? = null
    @Volatile private var activeCommandProcess: Process? = null

    // ── Base directories (all inside app's private storage) ──

    private val baseDir: File get() = context.filesDir
    private val binDir: File get() = File(baseDir, "usr/bin")
    private val rootfsDir: File get() = File(baseDir, "rootfs")
    private val tmpDir: File get() = File(baseDir, "tmp")
    private val homeDir: File get() = File(rootfsDir, "root")

    // ── Status ──

    fun isBootstrapped(): Boolean {
        return File(baseDir, "SETUP_COMPLETE").exists()
    }

    fun isRunning(): Boolean {
        return prootProcess?.isAlive == true
    }

    // ── Bootstrap ──

    /**
     * Set up the minimal bootstrap environment.
     * Extracts the bundled proot binary from APK assets and prepares directories.
     */
    fun setupBootstrap() {
        Log.i(TAG, "Setting up bootstrap environment...")

        // Create directory structure
        listOf(binDir, rootfsDir, tmpDir, homeDir).forEach { it.mkdirs() }

        // We no longer extract proot from assets.
        // It is bundled as jniLibs/arm64-v8a/libproot.so and installed by the package manager.
        val prootBin = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        if (!prootBin.exists()) {
            Log.e(TAG, "libproot.so not found in nativeLibraryDir!")
        } else {
            Log.i(TAG, "libproot.so found at ${prootBin.absolutePath}")
        }

        // Mark as bootstrapped
        File(baseDir, BOOTSTRAP_MARKER).writeText("DroidDesk v0.1.0\n")

        Log.i(TAG, "Bootstrap complete. Base: ${baseDir.absolutePath}")
    }

    // ── Session Management ──

    /**
     * Start a proot Linux session with the specified desktop environment.
     */
    fun startSession(desktopEnv: String = "xfce4", mode: String = "vnc", width: Int = 1920, height: Int = 1080) {
        if (isRunning()) {
            Log.w(TAG, "Session already running")
            return
        }

        if (!isBootstrapped()) {
            Log.e(TAG, "Cannot start session — not bootstrapped")
            return
        }

        val prootBin = File(context.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath
        val rootfs = rootfsDir.absolutePath

        val runScript = if (mode == "vnc") {
            """
            # ── Fix Locale ──
            export LANG=C.UTF-8
            export LC_ALL=C.UTF-8
            export LANGUAGE=C.UTF-8

            # ── Disable AT-SPI accessibility bus ──
            export NO_AT_BRIDGE=1
            export GTK_A11Y=none

            # Auto-heal missing VNC utilities if the user skipped a fresh install
            export PATH=${'$'}PATH:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            if ! command -v vncpasswd &> /dev/null; then
                echo "DIAG: Missing vncpasswd, auto-healing..."
                DEBIAN_FRONTEND=noninteractive apt-get update >/dev/null 2>&1
                DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends tigervnc-common tigervnc-tools >/dev/null 2>&1
            fi

            # Ensure VNC config exists (takes milliseconds, offline)
            export XDG_RUNTIME_DIR=/tmp/run/user/0
            mkdir -p /tmp/run/user/0
            
            mkdir -p ~/.vnc
            echo "password" | vncpasswd -f > ~/.vnc/passwd
            chmod 600 ~/.vnc/passwd

            # Setup an automatic apt hook so any newly installed app gets patched instantly
            cat << 'HOOK' > /usr/local/bin/patch-root-binaries.sh
            #!/bin/bash
            
            # 1. Binary patches for apps that still need geteuid spoofing (VLC)
            VLC_BIN="/usr/bin/vlc"
            if [ -f "${'$'}VLC_BIN" ] && ! grep -q "getppid" "${'$'}VLC_BIN"; then
                sed -i 's/geteuid/getppid/g' "${'$'}VLC_BIN" || true
            fi
            
            # 2. Foolproof wrapper method for Electron/Chromium apps
            # We rename the real binary and put a script in its place that always adds --no-sandbox
            BINS=(
                "/usr/share/code/code"
                "/usr/share/code-insiders/code-insiders"
                "/usr/share/discord/Discord"
                "/opt/Discord/Discord"
                "/opt/google/chrome/chrome"
                "/opt/google/chrome/google-chrome"
                "/usr/lib/chromium/chromium"
                "/usr/lib/chromium-browser/chromium-browser"
                "/opt/brave.com/brave/brave"
                "/opt/microsoft/msedge/msedge"
                "/opt/vivaldi/vivaldi"
            )
            
            for bin in "${'$'}{BINS[@]}"; do
                if [ -f "${'$'}bin" ]; then
                    # Check if it's already our wrapper script
                    if ! head -n 1 "${'$'}bin" | grep -q "bash"; then
                        mv "${'$'}bin" "${'$'}bin.real"
                        cat << WRAPPER > "${'$'}bin"
            #!/bin/bash
            exec "${'$'}bin.real" --no-sandbox "\${'$'}@"
            WRAPPER
                        chmod +x "${'$'}bin"
                    fi
                fi
            done
            HOOK
            chmod +x /usr/local/bin/patch-root-binaries.sh
            mkdir -p /etc/apt/apt.conf.d
            echo 'DPkg::Post-Invoke {"/usr/local/bin/patch-root-binaries.sh";};' > /etc/apt/apt.conf.d/99patch-root

            # Transparent wrapper for manual 'dpkg -i' installations
            cat << 'DPKG_WRAPPER' > /usr/local/bin/dpkg
            #!/bin/bash
            /usr/bin/dpkg "${'$'}@"
            RET=${'$'}?
            /usr/local/bin/patch-root-binaries.sh
            exit ${'$'}RET
            DPKG_WRAPPER
            chmod +x /usr/local/bin/dpkg
            
            cat << 'EOF' > ~/.vnc/xstartup
            #!/bin/bash
            export DISPLAY=:0
            export LIBGL_ALWAYS_SOFTWARE=1
            export GALLIUM_DRIVER=llvmpipe
            export MESA_LOADER_DRIVER_OVERRIDE=llvmpipe
            export QT_X11_NO_MITSHM=1
            export NO_AT_BRIDGE=1
            export GTK_A11Y=none
            export SAL_USE_VCLPLUGIN=gen
            echo "Xft.dpi: 144" > ${'$'}HOME/.Xresources
            xrdb ${'$'}HOME/.Xresources || true
            
            # Ensure any pre-existing unpatched binaries are fixed automatically
            if [ -x /usr/local/bin/patch-root-binaries.sh ]; then
                /usr/local/bin/patch-root-binaries.sh
            fi
            
            if command -v dbus-launch >/dev/null; then
                exec dbus-launch --exit-with-session startxfce4
            else
                exec startxfce4
            fi
            EOF
            chmod +x ~/.vnc/xstartup
            
            # Kill existing VNC if any
            vncserver -kill :0 >/dev/null 2>&1 || true
            rm -rf /tmp/.X11-unix/X0 /tmp/.X0-lock
            
            echo "DIAG: Launching VNC Server on :0 ..."
            # Use VncAuth to ensure compatibility with flutter_rfb
            vncserver :0 -localhost no -geometry ${width}x${height} -depth 24 -dpi 144 -SecurityTypes VncAuth
            
            echo "DIAG: VNC Server started. Tailing log..."
            tail -f ~/.vnc/*:0.log
            """.trimIndent()
        } else {
            """
            # ── Fix Locale ──
            export LANG=C.UTF-8
            export LC_ALL=C.UTF-8
            export LANGUAGE=C.UTF-8

            # ── Disable AT-SPI accessibility bus ──
            export NO_AT_BRIDGE=1
            export GTK_A11Y=none

            # ── Wait for X11 server ──
            echo "DIAG: Waiting 3s for X server..."
            sleep 3
            
            echo "DIAG: Checking /tmp/.X11-unix/"
            ls -la /tmp/.X11-unix/ 2>&1

            # ── Display & rendering ──
            export DISPLAY=:0
            export XDG_RUNTIME_DIR=/tmp/run/user/0
            export LIBGL_ALWAYS_SOFTWARE=1
            export GALLIUM_DRIVER=llvmpipe
            export MESA_LOADER_DRIVER_OVERRIDE=llvmpipe
            # Launch XFCE4 Session
            echo "DIAG: Launching xfce4-session on DISPLAY=:0 ..."
            
            # Use software rendering for xfwm4 to prevent GL errors
            export LIBGL_ALWAYS_SOFTWARE=1
            export GALLIUM_DRIVER=llvmpipe
            
            # Suppress dbus warnings by making sure we have a session bus
            dbus-launch --exit-with-session xfce4-session
            """.trimIndent()
        }

        val shmDir = File(context.filesDir, "shm").apply { mkdirs() }
        
        // Spoof /proc/version to fix LibreOffice
        val procVersion = File(context.filesDir, "fake_proc_version")
        if (!procVersion.exists()) {
            procVersion.writeText("Linux version 4.19.0 (android@localhost) (gcc version 4.9.x) #1 SMP PREEMPT Thu Jan 1 00:00:00 UTC 1970\n")
        }

        // Build proot command
        val command = mutableListOf(
            prootBin,
            "--rootfs=$rootfs",
            "-w", "/root",
            "--bind=/dev",
            "--bind=/proc",
            "--bind=${procVersion.absolutePath}:/proc/version",
            "--bind=/sys",
            "--bind=${tmpDir.absolutePath}:/tmp",
            "--bind=${shmDir.absolutePath}:/dev/shm",
            "--sysvipc",          // Enable sysvipc for shared memory (fixes Chromium/LibreOffice crashes)
            "--root-id",          // Fake root (uid 0)
            "--kill-on-exit",     // Clean up child processes
            "--link2symlink",     // Handle hardlink limitations
            "/bin/bash", "-c", runScript
        )

        Log.i(TAG, "Starting proot session for $desktopEnv in mode $mode")

        val env = buildEnvironment(desktopEnv)

        prootProcess = ProcessBuilder(command)
            .directory(rootfsDir)
            .redirectErrorStream(true)
            .also { pb ->
                pb.environment().putAll(env)
            }
            .start()
        
        // Log output in background thread for debugging
        Thread {
            val reader = java.io.InputStreamReader(prootProcess!!.inputStream)
            val buffer = CharArray(1024)
            var charsRead: Int
            while (reader.read(buffer).also { charsRead = it } != -1) {
                Log.d(TAG, "DESKTOP: " + String(buffer, 0, charsRead))
            }
        }.start()

        Log.i(TAG, "Proot session started")
    }

    /**
     * Stop the running proot session.
     */
    fun stopSession() {
        Log.i(TAG, "Stopping Linux session...")

        desktopProcess?.let {
            it.destroyForcibly()
            it.waitFor()
        }
        desktopProcess = null

        prootProcess?.let {
            it.destroyForcibly()
            it.waitFor()
        }
        prootProcess = null

        Log.i(TAG, "Session stopped")
    }

    // ── Command Execution ──

    /**
     * Execute a command inside the proot environment and return output.
     */
    fun executeCommand(command: String, onOutput: ((String) -> Unit)? = null): String {
        if (!isBootstrapped()) return "Error: Runtime not bootstrapped"

        val prootBin = File(context.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath

        val fullCommand = listOf(
            prootBin,
            "--rootfs=${rootfsDir.absolutePath}",
            "-w", "/root",
            "--bind=/dev",
            "--bind=/proc",
            "--bind=/sys",
            "--bind=${tmpDir.absolutePath}:/tmp",
            "--root-id",
            "--link2symlink",
            "/bin/bash", "-c", command
        )

        return try {
            Log.d(TAG, "Executing command: $command")
            Log.d(TAG, "Full args: $fullCommand")
            
            val process = ProcessBuilder(fullCommand)
                .directory(rootfsDir)
                .redirectErrorStream(true)
                .also { pb ->
                    pb.environment().clear()
                    pb.environment().putAll(buildEnvironment())
                }
                .start()
                
            activeCommandProcess = process

            val output = StringBuilder()
            val reader = java.io.InputStreamReader(process.inputStream)
            val buffer = CharArray(1024)
            var charsRead: Int
            while (reader.read(buffer).also { charsRead = it } != -1) {
                val chunk = String(buffer, 0, charsRead)
                Log.d(TAG, "CHUNK: $chunk")
                output.append(chunk)
                onOutput?.invoke(chunk)
            }
            process.waitFor()
            activeCommandProcess = null
            Log.d(TAG, "Command finished with exit code: ${process.exitValue()}")
            Log.d(TAG, "Final output length: ${output.length}")
            
            if (process.exitValue() != 0) {
                throw Exception("Command failed with exit code ${process.exitValue()}. Output: \n$output")
            }
            
            output.toString()
        } catch (e: Exception) {
            activeCommandProcess = null
            Log.e(TAG, "Command execution failed: ${e.message}")
            "Error: ${e.message}"
        }
    }

    /**
     * Interrupts the currently executing command.
     */
    fun interruptCommand() {
        activeCommandProcess?.let {
            Log.d(TAG, "Interrupting active command...")
            it.destroy()
        }
        activeCommandProcess = null
    }

    // ── Environment ──

    private fun buildEnvironment(desktopEnv: String = ""): Map<String, String> {
        val env = mutableMapOf(
            "HOME" to "/root",
            "USER" to "root",
            "LOGNAME" to "root",
            "HOSTNAME" to "droiddesk",
            "TERM" to "xterm-256color",
            "LANG" to "en_US.UTF-8",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TMPDIR" to "/tmp",
            "PROOT_TMP_DIR" to tmpDir.absolutePath,
            "SHELL" to "/bin/bash",
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system",
            "LD_LIBRARY_PATH" to context.applicationInfo.nativeLibraryDir,
            "PROOT_LOADER" to File(context.applicationInfo.nativeLibraryDir, "libproot-loader.so").absolutePath,
            "PROOT_NO_SECCOMP" to "1",
            "DISPLAY" to ":0",

            // Firefox / Browser sandboxing fixes for PRoot
            "MOZ_FAKE_NO_SANDBOX" to "1",
            "MOZ_DISABLE_CONTENT_SANDBOX" to "1",
            "MOZ_DISABLE_OOP_LAYER" to "1",

            // GPU acceleration (Mesa/Turnip/Zink)
            "MESA_NO_ERROR" to "1",
            "MESA_GL_VERSION_OVERRIDE" to "4.6",
            "MESA_GLES_VERSION_OVERRIDE" to "3.2",
            "GALLIUM_DRIVER" to "zink",
            "MESA_LOADER_DRIVER_OVERRIDE" to "zink",
            "TU_DEBUG" to "noconform",
            "ZINK_DESCRIPTORS" to "lazy",
            "MESA_VK_WSI_PRESENT_MODE" to "immediate"
        )

        if (desktopEnv.isNotEmpty()) {
            env["XDG_SESSION_TYPE"] = "x11"
            env["XDG_RUNTIME_DIR"] = "/tmp/runtime-root"
            env["XDG_DATA_DIRS"] = "/usr/share:/usr/local/share"
            env["XDG_CONFIG_DIRS"] = "/etc/xdg"
        }

        return env
    }

    // ── Asset Extraction ──
    // (Removed extractAsset since proot is now handled as a native library)
}
