# Installable test build

`GameOfSupremacy-debug.apk` is the installable Android debug build produced from the source in this PR. It supports Android 8.0+ on ARMv7, ARM64 and x86-64, and does not request Internet permission. Download the file and open it on Android to install.

The debug APK is included here so the game can be tried directly without setting up Android Studio. It is not a Play Store release. Regenerate it with `./gradlew :core:test :android:assembleDebug`; output is `android/build/outputs/apk/debug/android-debug.apk`. `SHA256SUMS` records the included build's digest. No private signing key is checked in.
