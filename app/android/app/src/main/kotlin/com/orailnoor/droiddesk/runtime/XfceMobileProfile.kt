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
    private const val PROFILE_MARKER = ".droiddesk-xfce-mobile-v10"
    private const val WALLPAPER_ASSET = "droiddesk/ubuntu-touch-wallpaper.jpg"

    fun install(
        context: Context,
        homeDir: File,
        wallpaperFile: File,
        wallpaperPathInSession: String = wallpaperFile.absolutePath,
        binDir: File? = null,
    ): Boolean {
        val marker = File(homeDir, PROFILE_MARKER)
        if (marker.exists()) return true

        return try {
            wallpaperFile.parentFile?.mkdirs()
            context.assets.open(WALLPAPER_ASSET).use { input ->
                wallpaperFile.outputStream().use(input::copyTo)
            }

            val xfconfDir = File(
                homeDir,
                ".config/xfce4/xfconf/xfce-perchannel-xml",
            ).apply { mkdirs() }
            File(xfconfDir, "xfce4-panel.xml").writeText(panelConfig())
            File(xfconfDir, "xfce4-desktop.xml").writeText(
                desktopConfig(xmlEscape(wallpaperPathInSession)),
            )
            File(xfconfDir, "xsettings.xml").writeText(xsettingsConfig())
            File(xfconfDir, "xfwm4.xml").writeText(xfwm4Config())

            installPanelCss(homeDir)
            installGtkSettings(homeDir)
            cleanupAndroidAppsArtifacts(homeDir, binDir)

            val panelDir = File(homeDir, ".config/xfce4/panel")
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

            homeDir.listFiles { file ->
                file.isFile &&
                    file.name.startsWith(".droiddesk-xfce-mobile-") &&
                    file.name != PROFILE_MARKER
            }?.forEach { it.delete() }
            marker.writeText("1\n")
            Log.i(TAG, "Installed XFCE mobile profile v10 in ${homeDir.absolutePath}")
            true
        } catch (error: Exception) {
            Log.e(TAG, "Failed to install XFCE mobile profile — leaving previous config intact", error)
            false
        }
    }

    private fun cleanupAndroidAppsArtifacts(homeDir: File, binDir: File?) {
        File(homeDir, ".local/share/applications/droiddesk-android-apps.desktop").delete()
        File(homeDir, ".local/bin/droiddesk-android-apps").delete()
        File(homeDir, ".config/xfce4/panel/launcher-27").deleteRecursively()
        binDir?.let { File(it, "droiddesk-android-apps").delete() }
    }

    private fun writeLauncher(
        file: File,
        name: String,
        comment: String,
        exec: String,
        icon: String,
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
            Categories=Utility;System;
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
            <property name="plugin-3" type="string" value="clock"/>
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
          </property>
        </channel>
    """.trimIndent() + "\n"

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
