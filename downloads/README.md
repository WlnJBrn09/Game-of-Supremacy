# Installable test build

`GameOfSupremacy-debug.apk` is the installable Android debug build produced from the Rust source in this branch by `android/build-apk.sh`: the 72-territory board, the console interface, player names and colours, bots, diplomacy and autosave. It supports Android 8.0+ on ARMv7, ARM64 and x86-64, and requests no permissions. Download the file and open it on Android to install.

The debug APK is included here so the game can be tried directly without setting up the Android SDK. It is not a Play Store release. Regenerate it with `ANDROID_HOME=/path/to/sdk ./android/build-apk.sh` (see the README); output is `target/android/GameOfSupremacy-debug.apk`. `SHA256SUMS` records the included build's digest. No private signing key is checked in.

**If you installed an earlier build:** this APK is signed with a different debug key, so Android will refuse to install it over the old one. Uninstall the old app first (its saved game is deleted with it). Saves written by the earlier Java build use a different file format and cannot be resumed. A debug key is generated per build machine, so any APK you build yourself will likewise need the previous one uninstalled.

This build has been built and statically inspected (manifest, ABIs, exported entry points, signature), but not run on an Android device or emulator; see `docs/VERIFICATION.md`.
