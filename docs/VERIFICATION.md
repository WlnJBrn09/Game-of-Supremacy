# Verification

The game, its tests, the Android packaging and the board generator were migrated from Java/libGDX (with a Python board generator) to Rust. This page records how the Rust version was checked, including against the original.

## Builds

- `cargo build --release` and `cargo clippy --all-targets` succeed without warnings on Rust 1.97 (minimum 1.82).
- `android/build-apk.sh` builds the checked-in APK with NDK r27c (27.2.12479018), platform 35 and build-tools 35.0.0. Inspection with `aapt2`, `apksigner`, `llvm-nm` and `llvm-readelf` confirms package `com.supremacy.game`, version 0.3.0 (code 3), minimum Android API 26, target 35, sensor-landscape orientation, OpenGL ES 2, no permissions, ARMv7/ARM64/x86-64 libraries, a valid signature, the exported `quad_main`, `JNI_OnLoad` and all miniquad JNI callbacks, and dependencies only on Android system libraries (`libandroid`, `liblog`, `libOpenSLES`, `libc`, `libm`, `libdl`).
- The installable debug APK is included in `downloads/` with a SHA-256 digest. SDKs, caches, signing keys and environment-specific proxy configuration are excluded.

## Automated rules checks

**32 tests pass** (`cargo test --release`): six board and camera tests, twenty-two game tests and four bot-strategy tests. They port every one of the previous 30 JUnit tests and add checks for rejected board files and tampered or foreign saves. Bot coverage checks exact conquest odds, probability monotonicity, declining hopeless battles at every level, prioritizing continent completion, reinforcement batching and persistent objectives. The full-match test runs three seeds at each of the six difficulties (18 completed matches).

Coverage includes player names and colours (trimming, defaults, duplicate and over-long names, duplicate colours, computer colour assignment, save round-trip and migration of saves without colours); the 72-territory geography and requested custom assignments; connected/symmetric topology; polygon triangulation and hit testing; fixed overhead camera; five-player random setup and 55 armies each; population income; defending ties; conquest/occupation/world victory; repeated fortification and open-border transit; escalating sets and matched-land bonuses; elimination cards and forced trade/deployment; immediate offers, stale offers and counteroffers; garrison redistribution and army conservation; movement history after trades; autosave, random-state preservation, backup recovery and pending occupation; and seeded complete bot matches reaching world conquest at all six difficulties.

## Equivalence with the Java engine

The Rust engine and bots are a rule-for-rule port, including the xorshift random generator, shuffles and every bot heuristic. `examples/parity_dump.rs` plays 24 seeded all-computer matches (six difficulties × seeds 721, 42, 2026 and 7) and prints the final turn, winner, random state, number of offers, history length, card trades and action count. The same harness run against the original Java engine before it was removed produced **identical output for all 24 matches**, so dice, card draws, diplomacy and bot decisions match the Java game exactly for a given seed.

## Board generator

`tools/board-gen` ports the Python/Shapely generator to Rust on GEOS 3.14.1, the version bundled with Shapely 2.2.0. From the pinned Natural Earth sources (downloaded and checksum-verified by the tool) it regenerates `assets/board.json` and `docs/TERRITORIES.md` **byte for byte identical** to the checked-in files, which the original Python generator also reproduces exactly.

## Interactive game checks

Exercised the Rust desktop build under Xvfb/Mesa with scripted input (`--script`) and inspected screenshots:

- Title screen, seat and skill selection, remembered names, resuming the autosave.
- The player editor with on-screen and physical keyboard input, capitalisation, colour selection with the held-colour marker, case-insensitive duplicate-name rejection, and the Next player → Start game flow for two humans; the handover screen for the first human.
- Initial placement with the slider, the selected territory's highlighted sea lane, automatic bot setup, the reinforcement income breakdown, automatic advance to Attack, the auto-picked attack source, win chance, single rolls and blitz with faded losing dice, occupation, fortify, ending the turn, quiet bot turns and the next turn's loss summary with paging.
- Cards, diplomacy, reviewing an incoming bot offer, the counteroffer composer (territories and armies/open borders pages), the pause menu and the rules pages.
- A forged near-victory save played through to the world-conquest screen.

Rendering cost was measured at about 3.4 ms of CPU per frame on the desktop build. `game-preview.png`, `players-preview.png`, `diplomacy-preview.png` and `handover-preview.png` are screenshots from these runs. They are not Android-device screenshots.

## Android runtime limit

This workspace has no hardware virtualization, so an Android emulator could not run, and no physical device was available. The APK could not be installed or launched; physical-device behaviour, Android touch/pinch interaction, the Back key and Android audio therefore remain unverified. The Android build shares all game and interface code with the desktop build checked above; only the entry point, the save-directory lookup (`Context.getFilesDir()`, as before) and packaging are Android-specific.

The game still needs device and balance playtesting, especially the requested 20% per-turn population growth and the differences among the upper AI difficulty levels. Higher levels use deeper bounded heuristics, not extra armies or favourable dice.
