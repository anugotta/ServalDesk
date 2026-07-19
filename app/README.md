# DroidDesk Launcher (app)

Flutter + Android app for **DroidDesk** — full Linux desktop on ARM64 Android, with optional **home launcher** routing and phone-oriented XFCE defaults.

## Features (this fork)

- Standalone APK with embedded Termux:X11 (`DISPLAY=:0`)
- Setup wizard + Flutter dashboard
- **Home launcher:** boot / Home → Linux desktop when setup is complete; otherwise Flutter
- Floating overlay: **Keyboard**, input mode (**Trackpad** default), **Dashboard**, **Android** (stock home)
- XFCE mobile profile: bottom dock (incl. VNC submenu), top tasklist/clock, Unsplash wallpapers, safe-area letterboxing, rotate-safe layout
- **Share VNC:** dock helpers start `x11vnc` on port **5901** and switch X to **1920×1080** for Mac/Pi/laptop viewers; Stop restores phone scale
- **Fit Windows to Screen** helper for rematching windows after rotate / VNC mode changes
- Hardened session start (FGS / surface timing) to reduce black screens on Home and boot

Screenshots and full docs: [repository README](../README.md).

## Build

```bash
cd app
flutter pub get
flutter build apk --release
```

APK: `build/app/outputs/flutter-apk/app-release.apk`

## Key Android packages

| Component | Role |
|-----------|------|
| `LauncherRouterActivity` | `HOME` / `DEFAULT` entry → desktop or Flutter |
| `DesktopActivity` | Fullscreen X11 surface + floating controls + VNC display-mode watch |
| `MainActivity` | Flutter dashboard / setup |
| `XfceMobileProfile` | Touch-oriented XFCE config + dock/VNC helpers (versioned markers) |
| `X11InputController` | Trackpad / direct touch / touchscreen; phone vs VNC display prefs |

## Related

- [APP_TROUBLESHOOTING.md](../APP_TROUBLESHOOTING.md) — Electron/Chromium `--no-sandbox`, VNC, launcher notes
- [NOTICE.md](../NOTICE.md) — attribution
