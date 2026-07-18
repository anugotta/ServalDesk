# DroidDesk Launcher

Flutter/Android app for **DroidDesk** — full Linux desktop on ARM64 Android, with an optional **home launcher** mode.

## Features

- Standalone APK with embedded Termux:X11 (`DISPLAY=:0`)
- Setup wizard + dashboard (Flutter)
- Optional default-home routing: boot / Home → Linux desktop, or Flutter if setup is incomplete
- Overlay escape hatches: **Dashboard** (Flutter) and **Android** (change default home)

## Build

```bash
cd app
flutter pub get
flutter build apk --release
```

APK output: `build/app/outputs/flutter-apk/app-release.apk`

## Docs

See the repository [README](../README.md) for install, home-launcher usage, and Termux script paths.
