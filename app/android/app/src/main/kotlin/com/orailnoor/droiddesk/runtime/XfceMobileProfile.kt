package com.orailnoor.droiddesk.runtime

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Touch-friendlier XFCE defaults that keep a real Linux desktop.
 *
 * Profile installs are atomic: the marker is written only after all files succeed,
 * so a failed upgrade cannot leave a half-broken session (black desktop on restart).
 *
 * Top panel includes a wide left spacer so the window list clears the vertical dock.
 */
object XfceMobileProfile {
    private const val TAG = "XfceMobileProfile"
    private const val PROFILE_MARKER = ".droiddesk-xfce-mobile-v19"
    private const val WALLPAPER_ASSET = "droiddesk/ubuntu-touch-wallpaper.jpg"
    private const val WALLPAPER_DIR_ASSET = "droiddesk/wallpapers"

    fun install(
        context: Context,
        homeDir: File,
        wallpaperDir: File,
        wallpaperPathPrefixInSession: String = wallpaperDir.absolutePath,
        binDir: File? = null,
    ): Boolean {
        val marker = File(homeDir, PROFILE_MARKER)
        if (marker.exists()) return true

        return try {
            val defaultWallpaper = installWallpapers(context, wallpaperDir)
            val defaultPathInSession =
                "$wallpaperPathPrefixInSession/${defaultWallpaper.name}"

            val xfconfDir = File(
                homeDir,
                ".config/xfce4/xfconf/xfce-perchannel-xml",
            ).apply { mkdirs() }
            File(xfconfDir, "xfce4-panel.xml").writeText(panelConfig())
            File(xfconfDir, "xfce4-desktop.xml").writeText(
                desktopConfig(xmlEscape(defaultPathInSession)),
            )
            File(xfconfDir, "xsettings.xml").writeText(xsettingsConfig())
            File(xfconfDir, "xfwm4.xml").writeText(xfwm4Config())

            installPanelCss(homeDir)
            installGtkSettings(homeDir)
            ensureHelpers(homeDir, binDir)

            val panelDir = File(homeDir, ".config/xfce4/panel")
            val localBin = File(homeDir, ".local/bin")
            val shell = resolveShell(binDir)
            fun helperExec(name: String): String =
                "\"$shell\" \"${File(localBin, name).absolutePath}\""

            writeLauncher(
                File(panelDir, "launcher-21/droiddesk-terminal.desktop"),
                name = "Terminal",
                comment = "Open the Linux terminal",
                exec = "xfce4-terminal",
                icon = "org.xfce.terminalemulator",
            )
            writeLauncher(
                File(panelDir, "launcher-22/droiddesk-files.desktop"),
                name = "Files",
                comment = "Browse files",
                exec = "thunar %u",
                icon = "org.xfce.filemanager",
            )
            writeLauncher(
                File(panelDir, "launcher-23/droiddesk-browser.desktop"),
                name = "Web Browser",
                comment = "Browse the web",
                exec = "exo-open --launch WebBrowser %u",
                icon = "org.xfce.webbrowser",
            )
            writeLauncher(
                File(panelDir, "launcher-26/droiddesk-vnc-share.desktop"),
                name = "Share VNC",
                comment = "Share this desktop to a Pi or laptop (port 5901)",
                exec = helperExec("droiddesk-vnc-share"),
                icon = "network-transmit-symbolic",
            )
            writeLauncher(
                File(panelDir, "launcher-26/droiddesk-vnc-connect.desktop"),
                name = "VNC Connect",
                comment = "Connect to another computer via VNC",
                exec = helperExec("droiddesk-vnc-connect"),
                icon = "network-receive-symbolic",
            )
            writeLauncher(
                File(panelDir, "launcher-26/droiddesk-vnc-stop.desktop"),
                name = "Stop VNC Share",
                comment = "Stop sharing this desktop over VNC",
                exec = helperExec("droiddesk-vnc-stop"),
                icon = "process-stop-symbolic",
            )
            writeLauncher(
                File(panelDir, "launcher-26/droiddesk-show-ip.desktop"),
                name = "Show IP",
                comment = "Show this phone's IP addresses for VNC / SSH",
                exec = helperExec("droiddesk-show-ip"),
                icon = "network-workgroup-symbolic",
            )
            // Drop older per-action dock slots that crowded the panel.
            File(panelDir, "launcher-28").deleteRecursively()
            File(panelDir, "launcher-29").deleteRecursively()
            cleanupAndroidAppsArtifacts(homeDir, binDir)

            homeDir.listFiles { file ->
                file.isFile &&
                    file.name.startsWith(".droiddesk-xfce-mobile-") &&
                    file.name != PROFILE_MARKER
            }?.forEach { it.delete() }
            marker.writeText("1\n")
            Log.i(TAG, "Installed XFCE mobile profile v18 in ${homeDir.absolutePath}")
            true
        } catch (error: Exception) {
            Log.e(TAG, "Failed to install XFCE mobile profile — leaving previous config intact", error)
            false
        }
    }

    private fun installWallpapers(context: Context, wallpaperDir: File): File {
        wallpaperDir.mkdirs()
        context.assets.open(WALLPAPER_ASSET).use { input ->
            File(wallpaperDir, "ubuntu-touch.jpg").outputStream().use(input::copyTo)
        }
        context.assets.list(WALLPAPER_DIR_ASSET)?.forEach { name ->
            if (!name.endsWith(".jpg", ignoreCase = true) &&
                !name.equals("CREDITS.txt", ignoreCase = true)
            ) {
                return@forEach
            }
            context.assets.open("$WALLPAPER_DIR_ASSET/$name").use { input ->
                File(wallpaperDir, name).outputStream().use(input::copyTo)
            }
        }
        // Prefer a fresh Unsplash default; fall back to the classic image.
        return sequenceOf("mountains.jpg", "ocean.jpg", "ubuntu-touch.jpg")
            .map { File(wallpaperDir, it) }
            .firstOrNull { it.exists() }
            ?: File(wallpaperDir, "ubuntu-touch.jpg")
    }

    /**
     * Install/refresh helper scripts + Applications menu entries.
     * Safe to call on every session start (does not require a profile bump).
     *
     * Termux-style prefixes have no usable `/bin/sh`, so scripts must use the
     * real bash under [binDir] and .desktop files must Exec that interpreter.
     */
    fun ensureHelpers(homeDir: File, binDir: File? = null) {
        // Keep panel/menu contrast CSS current even when the profile marker exists.
        installPanelCss(homeDir)
        installGtkSettings(homeDir)

        val localBin = File(homeDir, ".local/bin").apply { mkdirs() }
        val apps = File(homeDir, ".local/share/applications").apply { mkdirs() }
        val shell = resolveShell(binDir)

        fun installScript(name: String, body: String) {
            val file = File(localBin, name)
            val cleaned = body.lineSequence()
                .dropWhile { it.startsWith("#!") || it.isBlank() }
                .joinToString("\n")
                .trimStart()
            file.writeText("#!$shell\n$cleaned\n")
            file.setExecutable(true, false)
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "755", file.absolutePath)).waitFor()
            } catch (_: Exception) {
            }
            binDir?.let { dir ->
                dir.mkdirs()
                val copy = File(dir, name)
                copy.writeText(file.readText())
                copy.setExecutable(true, false)
                try {
                    Runtime.getRuntime().exec(arrayOf("chmod", "755", copy.absolutePath)).waitFor()
                } catch (_: Exception) {
                }
            }
        }

        fun helperExec(name: String): String =
            "\"$shell\" \"${File(localBin, name).absolutePath}\""

        installScript("droiddesk-fit-windows", fitWindowsScript())
        installScript("droiddesk-vnc-share", vncShareScript())
        installScript("droiddesk-vnc-stop", vncStopScript())
        installScript("droiddesk-vnc-connect", vncConnectScript())
        installScript("droiddesk-show-ip", showIpScript())

        writeMenuDesktop(
            File(apps, "droiddesk-vnc-share.desktop"),
            name = "Share Desktop (VNC)",
            comment = "Share this Linux desktop on port 5901 for a Pi or laptop",
            exec = helperExec("droiddesk-vnc-share"),
            icon = "network-transmit-symbolic",
        )
        writeMenuDesktop(
            File(apps, "droiddesk-vnc-stop.desktop"),
            name = "Stop VNC Share",
            comment = "Stop sharing this desktop over VNC",
            exec = helperExec("droiddesk-vnc-stop"),
            icon = "process-stop-symbolic",
        )
        writeMenuDesktop(
            File(apps, "droiddesk-vnc-connect.desktop"),
            name = "Connect to VNC…",
            comment = "Open a VNC viewer to another computer",
            exec = helperExec("droiddesk-vnc-connect"),
            icon = "network-receive-symbolic",
        )
        writeMenuDesktop(
            File(apps, "droiddesk-show-ip.desktop"),
            name = "Show Network / IP",
            comment = "Show IP addresses for VNC, SSH, or the Pi bridge",
            exec = helperExec("droiddesk-show-ip"),
            icon = "network-workgroup-symbolic",
        )
        writeMenuDesktop(
            File(apps, "droiddesk-fit-windows.desktop"),
            name = "Fit Windows to Screen",
            comment = "Maximize open windows to the current display size",
            exec = helperExec("droiddesk-fit-windows"),
            icon = "zoom-fit-best-symbolic",
        )

        // Keep a single dock launcher with a VNC submenu (avoids icon overcrowding).
        val panelDir = File(homeDir, ".config/xfce4/panel")
        writeLauncher(
            File(panelDir, "launcher-26/droiddesk-vnc-share.desktop"),
            name = "Share VNC",
            comment = "Share this desktop to a Pi or laptop (port 5901)",
            exec = helperExec("droiddesk-vnc-share"),
            icon = "network-transmit-symbolic",
        )
        writeLauncher(
            File(panelDir, "launcher-26/droiddesk-vnc-connect.desktop"),
            name = "VNC Connect",
            comment = "Connect to another computer via VNC",
            exec = helperExec("droiddesk-vnc-connect"),
            icon = "network-receive-symbolic",
        )
        writeLauncher(
            File(panelDir, "launcher-26/droiddesk-vnc-stop.desktop"),
            name = "Stop VNC Share",
            comment = "Stop sharing this desktop over VNC",
            exec = helperExec("droiddesk-vnc-stop"),
            icon = "process-stop-symbolic",
        )
        writeLauncher(
            File(panelDir, "launcher-26/droiddesk-show-ip.desktop"),
            name = "Show IP",
            comment = "Show this phone's IP addresses for VNC / SSH",
            exec = helperExec("droiddesk-show-ip"),
            icon = "network-workgroup-symbolic",
        )
        File(panelDir, "launcher-28").deleteRecursively()
        File(panelDir, "launcher-29").deleteRecursively()
    }

    private fun resolveShell(binDir: File?): String {
        if (binDir != null) {
            listOf("bash", "sh").forEach { name ->
                val candidate = File(binDir, name)
                if (candidate.exists()) return candidate.absolutePath
            }
        }
        listOf("/bin/bash", "/usr/bin/bash", "/bin/sh").forEach { path ->
            if (File(path).exists()) return path
        }
        return "bash"
    }

    /** @deprecated Use [ensureHelpers]. Kept for call-site compatibility. */
    fun ensureFitWindowsHelper(homeDir: File, binDir: File? = null) =
        ensureHelpers(homeDir, binDir)

    fun fitWindowsScript(): String = """
        #!/bin/sh
        # Fit visible XFCE/app windows to the current X screen size.
        export DISPLAY="${'$'}{DISPLAY:-:0}"
        dim=""
        i=0
        while [ "${'$'}i" -lt 12 ]; do
          dim=${'$'}(xdpyinfo 2>/dev/null | awk '/dimensions/{print ${'$'}2; exit}')
          if [ -n "${'$'}dim" ]; then
            break
          fi
          i=${'$'}((i + 1))
          sleep 0.15
        done
        [ -n "${'$'}dim" ] || exit 0
        w=${'$'}{dim%x*}
        h=${'$'}{dim#*x}
        case "${'$'}w" in (*[!0-9]*|"") exit 0 ;; esac
        case "${'$'}h" in (*[!0-9]*|"") exit 0 ;; esac
        [ "${'$'}w" -gt 0 ] && [ "${'$'}h" -gt 0 ] || exit 0

        if command -v wmctrl >/dev/null 2>&1; then
          wmctrl -lx 2>/dev/null | while read -r id _ class _; do
            [ -n "${'$'}id" ] || continue
            case "${'$'}class" in
              *xfce4-panel*|*Xfce4-panel*|*xfdesktop*|*Xfdesktop*|*wrapper-2.0*|*Wrapper*|*polybar*|*plank*)
                continue
                ;;
            esac
            wmctrl -i -r "${'$'}id" -b remove,fullscreen,maximized_vert,maximized_horz >/dev/null 2>&1 || true
            wmctrl -i -r "${'$'}id" -e "0,0,0,${'$'}w,${'$'}h" >/dev/null 2>&1 || true
            wmctrl -i -r "${'$'}id" -b add,maximized_vert,maximized_horz >/dev/null 2>&1 || true
          done
          exit 0
        fi

        if command -v xdotool >/dev/null 2>&1; then
          xdotool search --onlyvisible --name '' 2>/dev/null | while read -r id; do
            [ -n "${'$'}id" ] || continue
            xdotool windowmove "${'$'}id" 0 0 >/dev/null 2>&1 || true
            xdotool windowsize "${'$'}id" "${'$'}w" "${'$'}h" >/dev/null 2>&1 || true
          done
        fi
    """.trimIndent() + "\n"

    private fun shellNotifyHelpers(): String = """
        _dd_notify() {
          title="${'$'}1"; body="${'$'}2"
          if command -v notify-send >/dev/null 2>&1; then
            notify-send -a DroidDesk "${'$'}title" "${'$'}body" 2>/dev/null || true
          fi
          if command -v zenity >/dev/null 2>&1; then
            zenity --info --title="${'$'}title" --text="${'$'}body" --width=380 2>/dev/null &
          fi
        }
        _dd_error() {
          title="${'$'}1"; body="${'$'}2"
          if command -v notify-send >/dev/null 2>&1; then
            notify-send -u critical -a DroidDesk "${'$'}title" "${'$'}body" 2>/dev/null || true
          fi
          if command -v zenity >/dev/null 2>&1; then
            zenity --error --title="${'$'}title" --text="${'$'}body" --width=380 2>/dev/null || true
          else
            xfce4-terminal -T "${'$'}title" -e "bash -c 'echo \"${'$'}body\"; echo; read -n1 -p \"Press any key…\"'" 2>/dev/null || true
          fi
        }
        _dd_primary_ip() {
          ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if(${'$'}i=="src"){print ${'$'}(i+1); exit}}'
        }
        _dd_all_ips() {
          ip -4 -o addr show scope global 2>/dev/null | awk '{gsub(/\/.*/,"",${'$'}4); print ${'$'}2": "${'$'}4}'
          # USB tether / Wi‑Fi fallbacks on Android
          for iface in wlan0 rndis0 usb0 eth0; do
            addr=${'$'}(ip -4 -o addr show dev "${'$'}iface" 2>/dev/null | awk '{gsub(/\/.*/,"",${'$'}4); print ${'$'}4}')
            [ -n "${'$'}addr" ] && echo "${'$'}iface: ${'$'}addr"
          done
        }
    """.trimIndent()

    private fun vncShareScript(): String = """
        # Share the live DroidDesk X session over VNC at a laptop-sized resolution.
        export DISPLAY="${'$'}{DISPLAY:-:0}"
        export PATH="${'$'}HOME/.local/bin:${'$'}PREFIX/bin:/usr/bin:/bin:${'$'}PATH"
        ${shellNotifyHelpers()}

        if ! command -v x11vnc >/dev/null 2>&1; then
          _dd_error "VNC Share" "x11vnc is not installed.

Install once in Terminal:
  pkg install x11vnc

Then tap Share VNC again."
          exit 1
        fi

        mkdir -p "${'$'}HOME/.cache"
        # Ask Android to switch X to 1920x1080 before advertising VNC.
        echo vnc > "${'$'}HOME/.cache/droiddesk-display-mode"
        _dd_notify "VNC Share" "Switching desktop to 1920×1080 for Mac/laptop…
Then starting VNC on port 5901."
        # Give DesktopActivity time to apply exact resolution + remaximize windows.
        sleep 2.5

        pkill -x x11vnc >/dev/null 2>&1 || true
        sleep 0.3
        LOG="${'$'}HOME/.cache/droiddesk-x11vnc.log"
        # LAN-tuned: threads + light defer, client pixmap cache, low poll wait.
        # Phone CPU still caps smoothness at 1080p over Wi‑Fi.
        x11vnc \
          -display :0 \
          -forever -shared \
          -rfbport 5901 -nopw \
          -threads \
          -speeds lan \
          -defer 10 -wait 10 \
          -ncache 10 -ncache_cr \
          -pointer_mode 1 \
          -bg -o "${'$'}LOG" >/dev/null 2>&1
        sleep 0.5
        if ! pgrep -x x11vnc >/dev/null 2>&1; then
          echo phone > "${'$'}HOME/.cache/droiddesk-display-mode"
          _dd_error "VNC Share" "Failed to start x11vnc.
See ${'$'}LOG"
          exit 1
        fi
        IP=${'$'}(_dd_primary_ip)
        [ -z "${'$'}IP" ] && IP="(enable Wi‑Fi or USB tethering)"
        _dd_notify "VNC sharing (1920×1080)" "Connect from Mac / laptop:

  ${'$'}IP:5901

Tip: USB tethering is snappier than Wi‑Fi.
In TigerVNC: F8 → Full screen (optional).
Stop VNC Share when done (restores phone layout)."
    """.trimIndent() + "\n"

    private fun vncStopScript(): String = """
        export PATH="${'$'}HOME/.local/bin:${'$'}PREFIX/bin:/usr/bin:/bin:${'$'}PATH"
        ${shellNotifyHelpers()}
        mkdir -p "${'$'}HOME/.cache"
        if pgrep -x x11vnc >/dev/null 2>&1; then
          pkill -x x11vnc >/dev/null 2>&1 || true
        fi
        echo phone > "${'$'}HOME/.cache/droiddesk-display-mode"
        _dd_notify "VNC Share" "Stopped. Restoring phone display layout…"
    """.trimIndent() + "\n"

    private fun vncConnectScript(): String = """
        #!/bin/sh
        export DISPLAY="${'$'}{DISPLAY:-:0}"
        export PATH="${'$'}HOME/.local/bin:${'$'}PREFIX/bin:/usr/bin:/bin:${'$'}PATH"
        ${shellNotifyHelpers()}

        VIEWER=""
        for c in vncviewer xtigervncviewer tigervnc; do
          if command -v "${'$'}c" >/dev/null 2>&1; then VIEWER="${'$'}c"; break; fi
        done
        if [ -z "${'$'}VIEWER" ]; then
          _dd_error "VNC Connect" "No VNC viewer found.

Install once in Terminal:
  pkg install tigervnc-viewer"
          exit 1
        fi

        HOST=""
        if command -v zenity >/dev/null 2>&1; then
          HOST=${'$'}(zenity --entry --title="VNC Connect" --text="Host IP or hostname (optional :port):" --entry-text="192.168." 2>/dev/null) || exit 0
        else
          HOST=${'$'}(xfce4-terminal --disable-server -T "VNC Connect" -e "bash -c 'read -rp \"Host IP (optional :port): \" h; echo \"${'$'}h\" > /tmp/droiddesk-vnc-host'" 2>/dev/null; cat /tmp/droiddesk-vnc-host 2>/dev/null)
        fi
        [ -n "${'$'}HOST" ] || exit 0
        case "${'$'}HOST" in
          *:*) TARGET="${'$'}HOST" ;;
          *)   TARGET="${'$'}HOST:5901" ;;
        esac
        exec "${'$'}VIEWER" "${'$'}TARGET"
    """.trimIndent() + "\n"

    private fun showIpScript(): String = """
        #!/bin/sh
        export DISPLAY="${'$'}{DISPLAY:-:0}"
        export PATH="${'$'}HOME/.local/bin:${'$'}PREFIX/bin:/usr/bin:/bin:${'$'}PATH"
        ${shellNotifyHelpers()}
        IPS=${'$'}(_dd_all_ips | awk '!seen[${'$'}0]++')
        PRIMARY=${'$'}(_dd_primary_ip)
        if [ -z "${'$'}IPS" ] && [ -z "${'$'}PRIMARY" ]; then
          _dd_error "Network / IP" "No IPv4 address found.
Enable Wi‑Fi or USB tethering, then try again."
          exit 1
        fi
        MSG="Primary: ${'$'}{PRIMARY:-(none)}

${'$'}IPS

VNC share uses port 5901 (Share VNC on the dock).
Pi bridge: run pi-launch_phone.sh on the Pi after USB tethering."
        _dd_notify "Network / IP" "${'$'}MSG"
    """.trimIndent() + "\n"

    private fun cleanupAndroidAppsArtifacts(homeDir: File, binDir: File?) {
        File(homeDir, ".local/share/applications/droiddesk-android-apps.desktop").delete()
        File(homeDir, ".local/bin/droiddesk-android-apps").delete()
        File(homeDir, ".config/xfce4/panel/launcher-27").deleteRecursively()
        binDir?.let { File(it, "droiddesk-android-apps").delete() }
    }

    private fun writeMenuDesktop(
        file: File,
        name: String,
        comment: String,
        exec: String,
        icon: String,
    ) {
        writeLauncher(file, name, comment, exec, icon, categories = "Network;System;Utility;")
    }

    private fun writeLauncher(
        file: File,
        name: String,
        comment: String,
        exec: String,
        icon: String,
        categories: String = "Utility;System;",
    ) {
        file.parentFile?.mkdirs()
        file.writeText(
            """
            [Desktop Entry]
            Version=1.0
            Type=Application
            Name=$name
            Comment=$comment
            Exec=$exec
            Icon=$icon
            Categories=$categories
            StartupNotify=true
            Terminal=false
            """.trimIndent() + "\n",
        )
    }

    private fun installGtkSettings(homeDir: File) {
        val settings = File(homeDir, ".config/gtk-3.0/settings.ini")
        settings.parentFile?.mkdirs()
        settings.writeText(
            """
            [Settings]
            gtk-theme-name=Adwaita-dark
            gtk-icon-theme-name=Adwaita
            gtk-font-name=Sans 12
            gtk-cursor-theme-size=32
            gtk-xft-dpi=120000
            gtk-enable-animations=true
            """.trimIndent() + "\n",
        )
    }

    private fun installPanelCss(homeDir: File) {
        val cssFile = File(homeDir, ".config/gtk-3.0/gtk.css")
        cssFile.parentFile?.mkdirs()
        val startMarker = "/* DroidDesk mobile panel start */"
        val endMarker = "/* DroidDesk mobile panel end */"
        // Keep tasklist icons centered in their highlight; broad panel button
        // padding previously shifted window icons inside the selection box.
        val managedBlock = """
            $startMarker
            entry {
              min-height: 32px;
            }
            scrollbar slider {
              min-width: 14px;
              min-height: 14px;
            }
            .xfce4-panel button {
              min-height: 0;
              min-width: 0;
              padding: 4px;
              margin: 0;
              color: #f2f4f8;
            }
            .xfce4-panel .tasklist button,
            .xfce4-panel .tasklist .button {
              padding: 4px;
              margin: 0 2px;
              min-width: 32px;
              min-height: 32px;
            }
            .xfce4-panel .tasklist button image,
            .xfce4-panel .tasklist .button image {
              margin: 0;
              padding: 0;
            }
            /* Dark custom panel: light chrome + symbolic icons. */
            .xfce4-panel {
              color: #f2f4f8;
              -gtk-icon-style: symbolic;
            }
            .xfce4-panel > menubar,
            .xfce4-panel > widget > box,
            .xfce4-panel button,
            .xfce4-panel button label,
            .xfce4-panel .clock label,
            .xfce4-panel .digital-clock label,
            #clock-button label {
              color: #f2f4f8;
              opacity: 1;
            }
            .xfce4-panel image,
            .xfce4-panel button image,
            .xfce4-panel .image {
              color: #f2f4f8;
              -gtk-icon-recoloring: true;
              opacity: 1;
            }
            /*
             * Panel label color must NOT leak into launcher popups (that made
             * light text on a white menu). Force a dark readable menu instead.
             */
            menu,
            .menu,
            .xfce4-panel menu,
            window.popup menu {
              background-color: #1e222a;
              color: #f2f4f8;
              border: 1px solid #3a4150;
            }
            menu menuitem,
            .menu menuitem,
            .xfce4-panel menu menuitem {
              color: #f2f4f8;
              padding: 8px 12px;
            }
            menu menuitem label,
            .menu menuitem label,
            .xfce4-panel menu menuitem label,
            menu menuitem image,
            .xfce4-panel menu menuitem image {
              color: #f2f4f8 !important;
            }
            menu menuitem:hover,
            .menu menuitem:hover,
            .xfce4-panel menu menuitem:hover {
              background-color: #2f3642;
              color: #ffffff !important;
            }
            menu menuitem:hover label,
            .xfce4-panel menu menuitem:hover label {
              color: #ffffff !important;
            }
            $endMarker
        """.trimIndent()
        val existing = if (cssFile.exists()) cssFile.readText() else ""
        val withoutOldBlock = existing.replace(
            Regex(
                Regex.escape(startMarker) + ".*?" + Regex.escape(endMarker),
                setOf(RegexOption.DOT_MATCHES_ALL),
            ),
            "",
        ).trimEnd()
        cssFile.writeText(
            if (withoutOldBlock.isEmpty()) "$managedBlock\n"
            else "$withoutOldBlock\n\n$managedBlock\n",
        )
    }

    private fun panelConfig(): String = """
        <?xml version="1.1" encoding="UTF-8"?>

        <channel name="xfce4-panel" version="1.0">
          <property name="configver" type="int" value="2"/>
          <property name="panels" type="array">
            <value type="int" value="1"/>
            <value type="int" value="2"/>
            <property name="dark-mode" type="bool" value="true"/>
            <property name="panel-1" type="empty">
              <property name="position" type="string" value="p=9;x=0;y=0"/>
              <property name="length" type="uint" value="100"/>
              <property name="position-locked" type="bool" value="true"/>
              <property name="autohide-behavior" type="uint" value="0"/>
              <property name="size" type="uint" value="36"/>
              <property name="icon-size" type="uint" value="24"/>
              <property name="background-style" type="uint" value="1"/>
              <property name="background-rgba" type="array">
                <value type="double" value="0.000000"/>
                <value type="double" value="0.000000"/>
                <value type="double" value="0.000000"/>
                <value type="double" value="0.940000"/>
              </property>
              <property name="plugin-ids" type="array">
                <value type="int" value="2"/>
                <value type="int" value="1"/>
                <value type="int" value="3"/>
              </property>
            </property>
            <property name="panel-2" type="empty">
              <property name="position" type="string" value="p=8;x=0;y=0"/>
              <property name="mode" type="uint" value="0"/>
              <property name="length" type="uint" value="100"/>
              <property name="length-adjust" type="bool" value="false"/>
              <property name="position-locked" type="bool" value="true"/>
              <property name="autohide-behavior" type="uint" value="0"/>
              <property name="size" type="uint" value="56"/>
              <property name="icon-size" type="uint" value="40"/>
              <property name="background-style" type="uint" value="1"/>
              <property name="background-rgba" type="array">
                <value type="double" value="0.000000"/>
                <value type="double" value="0.000000"/>
                <value type="double" value="0.000000"/>
                <value type="double" value="0.940000"/>
              </property>
              <property name="plugin-ids" type="array">
                <value type="int" value="20"/>
                <value type="int" value="21"/>
                <value type="int" value="22"/>
                <value type="int" value="23"/>
                <value type="int" value="26"/>
                <value type="int" value="24"/>
                <value type="int" value="25"/>
              </property>
            </property>
          </property>
          <property name="plugins" type="empty">
            <property name="plugin-1" type="string" value="separator">
              <property name="expand" type="bool" value="true"/>
              <property name="style" type="uint" value="0"/>
            </property>
            <property name="plugin-2" type="string" value="tasklist">
              <property name="show-labels" type="bool" value="false"/>
              <property name="show-handle" type="bool" value="false"/>
              <property name="grouping" type="uint" value="0"/>
              <property name="flat-buttons" type="bool" value="true"/>
              <property name="show-tooltips" type="bool" value="true"/>
            </property>
            <property name="plugin-3" type="string" value="clock">
              <property name="mode" type="uint" value="2"/>
              <property name="digital-layout" type="uint" value="3"/>
              <property name="digital-time-format" type="string" value="%R"/>
              <property name="digital-date-format" type="string" value="%a %d %b"/>
              <property name="digital-time-font" type="string" value="Sans Bold 11"/>
              <property name="digital-date-font" type="string" value="Sans 9"/>
            </property>
            <property name="plugin-20" type="string" value="applicationsmenu">
              <property name="show-button-title" type="bool" value="false"/>
            </property>
            <property name="plugin-21" type="string" value="launcher">
              <property name="items" type="array">
                <value type="string" value="droiddesk-terminal.desktop"/>
              </property>
            </property>
            <property name="plugin-22" type="string" value="launcher">
              <property name="items" type="array">
                <value type="string" value="droiddesk-files.desktop"/>
              </property>
            </property>
            <property name="plugin-23" type="string" value="launcher">
              <property name="items" type="array">
                <value type="string" value="droiddesk-browser.desktop"/>
              </property>
            </property>
            <property name="plugin-26" type="string" value="launcher">
              <property name="items" type="array">
                <value type="string" value="droiddesk-vnc-share.desktop"/>
                <value type="string" value="droiddesk-vnc-connect.desktop"/>
                <value type="string" value="droiddesk-vnc-stop.desktop"/>
                <value type="string" value="droiddesk-show-ip.desktop"/>
              </property>
            </property>
            <property name="plugin-24" type="string" value="separator">
              <property name="expand" type="bool" value="true"/>
              <property name="style" type="uint" value="0"/>
            </property>
            <property name="plugin-25" type="string" value="showdesktop"/>
          </property>
        </channel>
    """.trimIndent() + "\n"

    private fun desktopConfig(wallpaperPath: String): String = """
        <?xml version="1.1" encoding="UTF-8"?>

        <channel name="xfce4-desktop" version="1.0">
          <property name="last-settings-migration-version" type="uint" value="1"/>
          <property name="desktop-icons" type="empty">
            <property name="icon-size" type="uint" value="48"/>
            <property name="font-size" type="double" value="11.000000"/>
          </property>
          <property name="backdrop" type="empty">
            <property name="screen0" type="empty">
              <property name="monitorbuiltin" type="empty">
                <property name="workspace0" type="empty">
                  <property name="color-style" type="int" value="0"/>
                  <property name="image-style" type="int" value="5"/>
                  <property name="last-image" type="string" value="$wallpaperPath"/>
                </property>
              </property>
            </property>
          </property>
        </channel>
    """.trimIndent() + "\n"

    private fun xsettingsConfig(): String = """
        <?xml version="1.1" encoding="UTF-8"?>

        <channel name="xsettings" version="1.0">
          <property name="Net" type="empty">
            <property name="ThemeName" type="string" value="Adwaita-dark"/>
            <property name="IconThemeName" type="string" value="Adwaita"/>
          </property>
          <property name="Xft" type="empty">
            <property name="DPI" type="int" value="120"/>
            <property name="Antialias" type="int" value="1"/>
            <property name="Hinting" type="int" value="1"/>
            <property name="HintStyle" type="string" value="hintslight"/>
          </property>
          <property name="Gtk" type="empty">
            <property name="FontName" type="string" value="Sans 12"/>
            <property name="CursorThemeSize" type="int" value="32"/>
          </property>
        </channel>
    """.trimIndent() + "\n"

    private fun xfwm4Config(): String = """
        <?xml version="1.1" encoding="UTF-8"?>

        <channel name="xfwm4" version="1.0">
          <property name="general" type="empty">
            <property name="theme" type="string" value="Default"/>
            <property name="title_font" type="string" value="Sans Bold 11"/>
            <property name="button_layout" type="string" value="O|HMC"/>
            <property name="easy_click" type="string" value="Alt"/>
            <property name="snap_to_border" type="bool" value="true"/>
            <property name="snap_to_windows" type="bool" value="true"/>
            <property name="snap_width" type="int" value="20"/>
            <property name="wrap_windows" type="bool" value="false"/>
            <property name="wrap_workspaces" type="bool" value="false"/>
            <property name="box_move" type="bool" value="false"/>
            <property name="box_resize" type="bool" value="false"/>
            <property name="raise_with_any_button" type="bool" value="true"/>
            <property name="click_to_focus" type="bool" value="true"/>
            <property name="focus_delay" type="int" value="0"/>
            <property name="double_click_distance" type="int" value="10"/>
            <property name="double_click_time" type="int" value="400"/>
            <property name="tile_on_move" type="bool" value="true"/>
            <property name="margin_top" type="int" value="0"/>
            <property name="margin_bottom" type="int" value="0"/>
            <property name="margin_left" type="int" value="0"/>
            <property name="margin_right" type="int" value="0"/>
          </property>
        </channel>
    """.trimIndent() + "\n"

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
