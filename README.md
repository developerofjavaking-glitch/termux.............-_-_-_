# BoomAux

A Termux-style terminal app for Android, built from the terminal modules of
[Termux](https://github.com/termux/termux-app). On first launch it downloads the official
Termux bootstrap, so `bash`, `pkg`, `apt`, `python` and `pip` work inside the app. GPLv3.

## Features
- Multi-session terminal (`bash -l`) with a session switcher
- Extra keys row (ESC, TAB, CTRL, ALT, arrows, `-` `/` `|` `~`), pinch to zoom
- Automatic bootstrap download and install on first run, with a retry button
- Foreground service with a WakeLock, so long-running scripts and bots keep running
- Works with the official Termux package repository (`pkg install python`, `pip install requests`, ...)

## Modules
| Module | Description |
|---|---|
| `terminal-emulator` | VT100/xterm emulator and native pty code (`jni/termux.c`, built with ndk-build) |
| `terminal-view` | `TerminalView` widget (rendering, selection, gestures) |
| `app` | `MainActivity`, `TermuxService`, `BootstrapInstaller` |

## Requirements
- Android Studio Koala (2024.1.1) or newer
- AGP 8.5.0, Gradle 8.8, JDK 17
- SDK Tools: **NDK (Side by side)** (CMake is not needed, `ndk-build` is used)
- minSdk 24, compileSdk 34, targetSdk 28

## Build
1. Open this folder in Android Studio.
2. Install the NDK from SDK Manager -> SDK Tools.
3. Run the `app` configuration on a device or emulator, or build from the terminal:

```bash
./gradlew assembleDebug
```

The APK is created at `app/build/outputs/apk/debug/`.

## Important: package name
The official Termux bootstrap and package repository are built for the package name
**`com.termux`** (prefix `/data/data/com.termux/files/usr`). Binaries and `.deb` files contain
that path, so `pkg` / `apt` / `pip` only work if the app uses it:

```kotlin
// app/build.gradle.kts
applicationId = "com.termux"
```

- Uninstall the original Termux app first (two apps cannot share one package name).
- Do not patch paths or preload redirect libraries; they do not cover the dynamic linker.
- To use a different package name you must build your own bootstrap and apt repository with
  [termux-packages](https://github.com/termux/termux-packages) (`TERMUX_APP__PACKAGE_NAME`).

## targetSdk 28
`targetSdk` is 28 on purpose: Android 10+ blocks executing binaries from the app data directory
for `targetSdk >= 29`. Newer Android versions may show a warning when installing such apps, and
support can be removed in the future. The stock `/system/bin/sh` works regardless.

## First run
1. Open the app and wait for the bootstrap download to finish (internet required).
2. Update and install tools:

```bash
pkg update
pkg install python git
pip install requests
```

If the download fails, tap **Retry**. You can also place `bootstrap-<arch>.zip`
(`aarch64`, `arm`, `x86_64`, `i686`) in `/sdcard/Download/` for an offline install.

## Security notes
- Download the bootstrap only from the official
  [termux-packages releases](https://github.com/termux/termux-packages/releases).
- Keep TLS verification enabled. Do not use `insecure` curl options, `Verify-Peer "false"` in
  apt, or `PYTHONHTTPSVERIFY=0`.
- Do not install packages with `--force-depends`.

## Troubleshooting
| Problem | Fix |
|---|---|
| `pkg: inaccessible or not found` | Bootstrap is not installed. Check internet, tap Retry, or check the package name is `com.termux` |
| `pkg update` fails | Check the date/time and network; run `termux-change-repo` or set another official mirror |
| `Permission denied` when running binaries | Make sure `targetSdk` is 28 and the file is executable (`chmod +x`) |
| Bot stops when screen is off | Turn on the WakeLock and disable battery optimization for the app |
| Reinstall environment | Clear app data, or use the reset option if available |

## Roadmap
- Settings screen (font, colors, vibration, auto-start)
- Boot auto-start and notification actions
- Backup and restore of `usr` and `home`
- Storage access (`~/storage`) and file import/export
- Signed release builds with GitHub Actions

## License
GPLv3, see [LICENSE](LICENSE). Derived code keeps its original copyright.
