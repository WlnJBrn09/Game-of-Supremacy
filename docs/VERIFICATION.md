# Verification

## Builds

- `./gradlew :core:test :android:assembleDebug` succeeds with JDK 21, Gradle 8.9, Android Gradle Plugin 8.7.3, SDK 35 and build tools 35.0.0.
- `./mvnw verify` builds the shared game and desktop launcher with Java 17 bytecode.
- APK manifest inspection confirms minimum Android API 26, landscape orientation, ARMv7/ARM64/x86-64 native libraries, and no Internet permission. AndroidX adds only its app-scoped signature permission for dynamic receivers.
- The installable debug APK is included in `downloads/`, with a SHA-256 digest. SDKs, emulator images, caches, signing keys and environment-specific proxy configuration are excluded.

## Automated rules checks

**26 tests pass:** five board tests, seventeen game tests and four bot-strategy tests. Bot coverage checks exact conquest odds, probability monotonicity, declining hopeless battles at every level, prioritizing continent completion, reinforcement batching and persistent objectives. The full-match test runs three seeds at each of the six difficulties (18 completed matches).

Coverage includes the 72-territory geography and requested custom assignments; connected/symmetric topology; polygon triangulation and hit testing; fixed overhead camera; five-player random setup and 55 armies each; population income; defending ties; conquest/occupation/world victory; repeated fortification and open-border transit; escalating sets and matched-land bonuses; elimination cards and forced trade/deployment; immediate offers, stale offers and counteroffers; garrison redistribution and army conservation; movement history after trades; autosave, random-state preservation, backup recovery and pending occupation; and seeded complete bot matches reaching world conquest at all six difficulties.

## Interactive game checks

Exercised the actual libGDX desktop game under Xvfb/Mesa using keyboard/pointer input and inspected screenshots:

- New match, human handover, territory selection, 55-army setup and bot setup.
- Reinforcement deployment, attack controls, cards, diplomacy composer and an open-borders offer.
- Quiet bot turns and the next human's report/handover.
- Resuming an autosave, rolling a real battle, occupying captured land, advancing to a second human and checking that their private cards are separated by the handover screen.
- The map uses the existing orthographic pan/zoom transform; Android adds two-finger pinch zoom.

The console redesign (game-piece army tokens, phase track, roster, slider placement, blitz, win chance, dice results, card faces, continent panel and victory screen) was checked the same way with a seeded match (`-Dsupremacy.seed=42`): seat/skill selection, initial placement with the slider, automatic advance to Attack, auto-picked attack source, single rolls and blitz, occupation, continuing the attack from captured land, fortifying, ending the turn, the next turn's loss summary, cards, diplomacy, log, pause menu, a two-human handover and a forged near-victory save played through to world conquest.

The Android SDK could not be downloaded in the environment used for the redesign, so `downloads/GameOfSupremacy-debug.apk` was **not** rebuilt and still contains the previous interface. Rebuild it with `./gradlew :core:test :android:assembleDebug`.

`game-preview.png`, `diplomacy-preview.png` and `handover-preview.png` are screenshots from that shared desktop runtime. They are not Android-device screenshots.

## Android runtime limit

An Android emulator was installed and attempted with API 35 and API 30 images. Its default data partition requires more disk space than available in this workspace. A sparse test partition allowed a guest to start, but guest data-partition/journal initialization failed before the Android framework became ready. This is an emulator setup failure; the APK could not be installed or launched there. Physical-device behaviour, Android touch/pinch interaction and Android audio therefore remain unverified. The APK build and shared rules/UI checks above succeeded.

The game still needs device and balance playtesting, especially the requested 20% per-turn population growth and the differences among the upper AI difficulty levels. Higher levels use deeper bounded heuristics, not extra armies or favourable dice.
