# Debug APK

`GameOfSupremacy-debug.apk` is the installable Android debug build of the current source (version 0.2.0).

- **Requires** Android 8.0 (API 26) or newer.
- **Architectures:** ARMv7, ARM64 and x86-64.
- **Permissions:** no Internet permission. The game is fully offline.
- **Signing:** debug key only. This is not a Play Store release, and no private signing key is in the repository.

## Install

Download the file on the device and open it, allowing installs from your browser or file manager when asked. Or use adb:

```bash
adb install -r GameOfSupremacy-debug.apk
```

## Check the download

```bash
sha256sum -c SHA256SUMS
```

Run it from the **repository root**; `SHA256SUMS` lists the digest of the included build with its `downloads/` path.

## Rebuild

```bash
./gradlew :core:test :android:assembleDebug
```

The output is `android/build/outputs/apk/debug/android-debug.apk`. To publish a rebuild, follow [the release steps](../docs/DEVELOPMENT.md#releasing-a-debug-apk). Update the APK and `SHA256SUMS` together.
