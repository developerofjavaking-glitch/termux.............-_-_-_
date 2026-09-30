# Termux Lite

A minimal Termux-style terminal app for Android Studio, built from the terminal modules of
Termux (via AndroidIDE). GPLv3.

## Modules
- `terminal-emulator` – VT100/xterm emulator + native pty code (`jni/termux.c`, built with ndk-build)
- `terminal-view` – the `TerminalView` widget (rendering, selection, gestures)
- `app` – `MainActivity`: multi-session shell (`/system/bin/sh`), extra-keys row, pinch to zoom

## Build
1. Open this folder in Android Studio (Koala 2024.1.1 or newer; AGP 8.5.0 / Gradle 8.8).
2. SDK Manager -> SDK Tools: install **NDK (Side by side)** and **CMake is not needed** (ndk-build is used).
3. Run the `app` configuration on a device or emulator.

## Notes
- `targetSdk` is 28 on purpose: Android 10+ blocks executing binaries from the app data dir for
  targetSdk >= 29. The stock `/system/bin/sh` and toolbox commands work regardless.
- This is only the terminal. A package manager (`apt`/`pkg`) needs a bootstrap archive built for your
  own app prefix (`/data/data/<your.package>/files/usr`); see `TermuxInstaller` in AndroidIDE's
  `termux/application` for how that is done.
- License: GPLv3 (see LICENSE). Derived code keeps its original copyright.
