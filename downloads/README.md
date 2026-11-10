# Installable test build

`GameofSupremscy_v0.2.1.apk` is the installable Android debug build produced from the source in this branch, including the redesigned console interface, the 72-territory board and player names and colours. It supports Android 8.0+ on ARMv7, ARM64 and x86-64, and does not request Internet permission. Download the file and open it on Android to install.

The debug APK is included here so the game can be tried directly without setting up Android Studio. It is not a Play Store release. Regenerate it with `./gradlew :core:test :android:assembleDebug`; output is `android/build/outputs/apk/debug/android-debug.apk`. `SHA256SUMS` records the included build's digest. No private signing key is checked in.

**If you installed an earlier build:** this APK is signed with a different debug key, so Android will refuse to install it over the old one. Uninstall the old app first (its saved game is deleted with it). Saves from the earlier 71-territory board cannot be resumed anyway. A debug key is generated per build machine, so any APK you build yourself will likewise need the previous one uninstalled.

This build has been built and statically inspected, but not run on an Android device or emulator; see `docs/VERIFICATION.md`.
