# Changelog

## 0.2.2

- Release APK is now named `GameofSupremacy_v<version>.apk`; `./gradlew :android:packageApk` builds it and copies it to `downloads/`.
- Added an adaptive launcher icon (gold star on the console palette, with a monochrome variant) instead of the default Android icon.
- Added GitHub Actions CI: rules tests, the Android debug APK and the desktop Maven build run on every push and pull request, and the APK is uploaded as a workflow artifact.
- Version bumped to 0.2.2 (versionCode 3) in Gradle and Maven. No gameplay, rules or save-format changes: saves from 0.2.0 still load.

## 0.2.0

- Classic conquest-board console UI, player names and colours, 72-territory board, six bot difficulties.
