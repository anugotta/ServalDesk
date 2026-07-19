# App Troubleshooting Guide

Welcome to the DroidDesk troubleshooting guide. This document explains how to fix common issues when installing and running Linux applications inside the DroidDesk container.

## The "Running as root without --no-sandbox is not supported" Error

Because DroidDesk utilizes a PRoot environment, you are logged in as the `root` user by default. Modern Electron and Chromium-based applications have strict security sandboxes that refuse to run as root.

Apps known to have this issue include:
- Visual Studio Code
- Google Chrome / Chromium
- Discord
- Brave Browser
- Microsoft Edge
- Obsidian
- Cursor

### 1. Automatic Fixes (Recommended)
DroidDesk includes an **Auto-Patcher** that automatically fixes these applications. It hooks into the package manager so you don't have to do anything manually.

If you install an app using:
- `apt install <package>`
- `dpkg -i <package>.deb`

The auto-patcher will instantly and silently patch the app in the background so it launches perfectly from the desktop menu.

### 2. Manual Fixes (For Tarballs and Standalone Binaries)
If you download an application as a standalone binary (e.g., a `.tar.gz`, an `.AppImage`, or extracting a zip file) without using `apt` or `dpkg`, the auto-patcher will **not** trigger automatically. 

If your manually installed application refuses to launch, follow these steps:

#### Method A: Run the Global Patch Script
If you manually copied the application into a system directory (like `/usr/share` or `/opt`), you can run the auto-patcher manually:

```bash
/usr/local/bin/patch-root-binaries.sh
```
This script will scan standard installation directories, rename the real binary, and create a transparent shell wrapper in its place that automatically passes the `--no-sandbox` flag.

#### Method B: Manual Terminal Wrapper (For Custom Directories)
If you extracted the app into a custom folder (e.g., `~/Downloads/My-App`), you can create a simple bash wrapper yourself so you don't have to type the flag every time:

```bash
# Rename the real binary
mv ~/Downloads/My-App/app ~/Downloads/My-App/app.real

# Create a wrapper script in its place
cat << 'EOF' > ~/Downloads/My-App/app
#!/bin/bash
exec ~/Downloads/My-App/app.real --no-sandbox "$@"
EOF

# Make it executable
chmod +x ~/Downloads/My-App/app
```

#### Method C: Launching from the Terminal
If you are launching the standalone binary directly from the terminal, simply pass the flag manually when you run the command:
```bash
./my-electron-app --no-sandbox
```

## "VLC is not supposed to be run as root" Error

VLC Media Player also has a hardcoded block preventing it from running as root. 
If you install VLC via `apt`, our auto-patcher handles it. 

If you compile or install VLC manually, you can bypass the root check by patching the binary with this command:
```bash
sed -i 's/geteuid/getppid/g' /path/to/your/vlc/binary
```

## Home launcher / phone desktop (DroidDeskLauncher)

### Black screen on boot or Home

1. Open DroidDesk from the **app drawer** (not only Home) and use **Stop Server**, then **Launch Desktop** again.
2. Set battery usage for DroidDesk to **Unrestricted** (Samsung: Settings → Apps → DroidDesk → Battery).
3. Disable **child process** restrictions in Developer Options if your ROM kills background processes.
4. If setup never finished, Home correctly opens the Flutter dashboard — complete setup first.

### Floating bar: Android vs Dashboard

- **Dashboard** → Flutter UI (return to desktop, stop server, settings).
- **Android** → stock Android home (One UI / other launcher). Long-press opens the default-home picker.
- A plain system Home press while DroidDesk is the default launcher returns to the Linux desktop by design.

### Soft keyboard

X11 cannot reliably detect “text field focused.” Use the floating **Keyboard** button (or keep a BT keyboard connected).

### Share VNC (Mac / Pi / laptop)

1. Dock **VNC** → **Share VNC**. Wait for the toast / notify that the desktop is **1920×1080**, then connect to `PHONE_IP:5901` (no password by default).
2. If the viewer shows a tiny phone-sized desktop, stop VNC, **Launch Desktop** once (refreshes helpers), share again, and reconnect.
3. Some lag is normal over Wi‑Fi at 1080p. Prefer USB tethering when possible. USB-C HDMI / DeX on DP Alt Mode phones is the low-latency external-display path.
4. **Stop VNC Share** restores the phone-scaled layout. If Share fails, install once from Terminal: `pkg install x11vnc`.
5. Dock scripts need a working bash shebang; if you see “Permission denied” on Share/Fit helpers, open the desktop once after updating so helpers refresh.
