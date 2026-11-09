# Verification

This records what has been checked, how, and what has not. Update it whenever you rebuild the release APK.

## Build status

| Check | Result |
| --- | --- |
| `./gradlew :core:test :android:assembleDebug` | Passes with JDK 21, Gradle 8.9, AGP 8.7.3, SDK 35, build tools 35.0.0 |
| `./mvnw verify` | Passes. Java 17 bytecode, desktop launcher and shaded JAR |
| APK manifest | Min SDK 26, landscape, ARMv7 / ARM64 / x86-64 natives, no Internet permission. AndroidX adds only its app-scoped signature permission for dynamic receivers |
| Repository hygiene | SDKs, emulator images, caches, signing keys and proxy configuration are not committed |

The debug APK in `downloads/` has its digest in `downloads/SHA256SUMS`.

## Automated tests

**25 tests:** 5 board, 16 engine, 4 bot strategy.

- **Board:** 71-territory geography and custom assignments, symmetric and connected topology, polygon triangulation and hit testing, fixed overhead camera.
- **Engine:** five-player random setup with 55 armies each, population income, defending ties, conquest, occupation and world victory, repeated fortification and open-border transit, escalating card sets and matched-land bonuses, elimination cards and forced trading, immediate / stale / counter offers, garrison redistribution and army conservation, movement history after trades, autosave, RNG state preservation, backup recovery and pending occupation.
- **Bots:** exact conquest odds, probability monotonicity, declining hopeless battles at every level, prioritising continent completion, reinforcement batching and persistent objectives.
- **Full matches:** three seeds at each of the six difficulties (18 matches), each running to world conquest.

## Interactive checks

The real libGDX desktop game was driven under Xvfb/Mesa with keyboard and pointer input, and screenshots were inspected:

- New match, handover, territory selection, 55-army setup and bot setup.
- Reinforcement, attack controls, cards, diplomacy composer and an open-borders offer.
- Quiet bot turns, and the next human's report and handover.
- Resuming an autosave, a real battle, occupying captured land, advancing to a second human and confirming private cards stay behind the handover screen.

The preview images in `docs/` come from this desktop runtime. They are **not** Android screenshots.

## Known gaps

- **Not run on Android.** Emulators (API 35 and 30) failed to boot in the available workspace because of disk and data-partition limits, before the Android framework was ready, so the APK was never installed or launched. Real-device behaviour, Android touch and pinch input, and Android audio are unverified.
- **Balance.** The 20% per-turn population growth rule and the gaps between the upper bot difficulties need playtesting. Higher levels use deeper bounded heuristics, not extra armies or favourable dice.
- **Save migration.** There is none, and the save format is at version 1.
- **Release signing.** Only a debug-signed build exists.

## How to re-verify

```bash
./gradlew :core:test :android:assembleDebug
./mvnw clean verify
```

For an interactive desktop check on a headless machine, run the JAR under `xvfb-run` with `-Dsupremacy.saveDir=<temp dir> -Dsupremacy.noAudio=true`.
