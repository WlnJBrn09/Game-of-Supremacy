# Verification

What has been checked for version 0.3.0, and what has not.

## Builds

- `./gradlew :core:test :android:assembleDebug` succeeds (wrapper Gradle 8.9) with JDK 21, Android Gradle Plugin 8.7.3, SDK platform 35 and build tools 35.0.0. This is how `GameofSupremacy_v0.3.0.apk` (version 0.3.0, versionCode 4) was built.
- `./mvnw verify` builds the shared game and desktop launcher with Java 17 bytecode.
- The APK requests one permission, `VIBRATE` (haptic feedback), plus AndroidX's app-scoped signature permission. No Internet permission. Minimum Android API 26, target 35, sensor-landscape, ARMv7/ARM64/x86-64 native libraries.
- `downloads/SHA256SUMS` records the included APK's digest; check it with `sha256sum -c downloads/SHA256SUMS` from the repository root.

## Automated rules checks

**36 tests pass** (Maven and Gradle): six board and camera tests, twenty-six game tests and four bot-strategy tests.

New in 0.3.0: match options (starting armies, population bonus off, invalid values refused); undo of placements and moves, including undoing the last placement after the game moved on to attacking, and undo being cleared by a dice roll; AUTO placement plans that use the whole reserve, consume no randomness, leave the board unchanged until applied and undo as one step; board events, statistics and the "since you last looked" recap; saves written before these fields existed loading with the original defaults; the camera fitting the uncovered part of the map, flying to targets near the map edge and settling after a fling.

Still covered from earlier versions: player names and colours, the 72-territory geography and custom assignments, symmetric connected topology, triangulation and hit testing, the five-player deal, population income, defending ties, conquest, occupation and world victory, fortification and open-border transit, card sets and escalation, elimination cards and forced trades, offers, counteroffers and garrison redistribution, autosave, backup recovery, and seeded complete bot matches reaching world conquest at all six difficulties.

## Interface checks (scripted tour)

`tools/screenshot_tour.sh` runs the real game (desktop backend, under Xvfb) and plays a match through the interface by pressing controls by their labels, at four sizes:

| Size | Window | Density | Layout units | Layout |
| --- | --- | --- | --- | --- |
| phone | 2400×1080 | 2.625 | 914×411 | phone (map + dock) |
| small | 1600×720 | 2.0 | 800×360 | phone |
| tablet | 1280×800 | 1.333 | 960×600 | wide (map + console) |
| desktop | 1600×1000 | 1.25 | 1280×800 | wide |

Each run covers: title screen and match options, name entry, the first-game guide, tap placement with 1/5 per tap, AUTO-PLACE and UNDO, finishing setup, computer setup, reinforcing until the game moves on to attacking, choosing an attacker and target, blitz, occupation, territory details, zooming, the continent spotlight, cards, diplomacy and the offer composer, the commander list (phones), log, pause menu, rules, fortifying by dragging armies (a drop on an enemy territory is refused, a drop on a connected territory of ours selects the move, and a drag that starts away from a badge still pans), ending the turn, computer turns played back on the map, skipping to the next turn, the recap of lost territory, and a forged near-win played to the victory screen. At every screenshot it fails if any control is partly off screen or smaller than 28×28 units. A fifth run plays two humans sharing the device and checks the handover screens, that they hide the game controls, and that pausing hides the board.

All five runs pass. The screenshots in `docs/` come from these runs.

## Android

This workspace has no hardware virtualization, so the APK is exercised in CI instead: `.github/workflows/build.yml` runs `tools/android_smoke.sh` on an Android 14 emulator with a Pixel 6 profile (1080×2400, 420 dpi) on every push. It installs the APK, opens the title screen, starts a seeded match and taps the map, then lets the computer play a match unattended for 90 seconds, and fails on any crash in the log or if the app has stopped. On failure it prints the crash and the app's log lines into the job log.

The smoke test also recreates the activity twice right after a cold start (by changing the display density, which always recreates it) and checks the game survives.

Results: about half of the early emulator runs failed because the app died at launch. The crash, captured once the test printed it, was in libGDX 1.13.1: Android recreated the activity straight after launch, and `AndroidApplication.onDestroy()` then called `AndroidXKeyboardHeightProvider.close()`, which passes a never-set (null) view to `ViewCompat` and throws. The recreation came from forcing landscape: when the game is opened while the device is upright, the window's smallest side changes (system bars move), a `smallestScreenSize` configuration change the manifest did not declare as handled. Version 0.3.0 now declares it, and `AndroidLauncher.onDestroy()` absorbs that one libGDX exception (Android's own `Activity.onDestroy()` has already run by then; anything else is rethrown). The same crash could have hit real phones opened in portrait. The CI screenshot tour also passes at all four sizes plus pass-and-play.

Not yet checked anywhere: performance and battery use on a real mid-range phone (the map is now drawn from prebuilt GPU meshes instead of ~194,000 CPU-built vertices per frame, but frame times have not been measured on a device), haptics, Android audio, the native name-entry dialog on different keyboards, and balance of the population rule and the top difficulty levels.
