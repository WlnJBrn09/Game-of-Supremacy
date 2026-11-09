# Game of Supremacy

An offline, five-player world-conquest game for Android, written in Java with libGDX. It plays on a custom 71-territory world board with Risk-style dice and cards, plus asynchronous diplomacy.

![Game screen](docs/game-preview.png)

## Highlights

- **Five players, always.** One to five humans share a device (pass-and-play). Charles, George, Douglas and Dan fill the remaining seats.
- **Six bot difficulties:** Beginner, Easy, Medium, Hard, MegaHard and TeraHard. Higher levels plan deeper; none get extra armies or loaded dice.
- **Custom board:** seven continents, 112 land connections, 34 sea lanes and sea lanes across the Pacific, generated from Natural Earth data.
- **Diplomacy:** territory swaps, adjacent army gifts and open borders, with counteroffers.
- **Private hands:** a handover screen hides cards between human turns.
- **Autosave** after every action, with backup recovery.
- **Fully offline.** No Internet permission, and all map, font and audio assets are bundled.

## Play

### Install the Android build

Download [`downloads/GameOfSupremacy-debug.apk`](downloads/GameOfSupremacy-debug.apk) and open it on a device running **Android 8.0 (API 26) or newer**. Allow installs from your browser or file manager when prompted. This is a debug-signed build for direct installation, not a Play Store release. See [`downloads/README.md`](downloads/README.md) for how to check its SHA-256.

### Run on desktop

```bash
./mvnw clean verify
java -jar target/supremacy-game-0.2.0.jar
```

On macOS add `-XstartOnFirstThread` to the `java` command. On Windows use `mvnw.cmd`.

### How a match works

Choose the number of humans and one shared bot difficulty, then start. During setup, deploy your 55 starting armies. Each turn then runs Reinforce → Attack → Occupy → Fortify → End turn. Tap your territory, then an adjacent enemy, to attack. Pinch or use the zoom buttons to zoom, and drag to pan.

Full rules, defaults and bot behaviour are in [`docs/GAME_RULES.md`](docs/GAME_RULES.md). Territory groupings are in [`docs/TERRITORIES.md`](docs/TERRITORIES.md).

## Developing

| Document | Contents |
| --- | --- |
| [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) | Prerequisites, build and test commands, system properties, regenerating the board, release checklist |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Module layout, class responsibilities, engine and save invariants |
| [`docs/VERIFICATION.md`](docs/VERIFICATION.md) | What has been tested, and what has not |
| [`docs/THIRD_PARTY.md`](docs/THIRD_PARTY.md) | Data, font, library and licence notices |

Quick start for contributors:

```bash
./gradlew :core:test :android:assembleDebug   # needs JDK 17/21 and Android SDK 35
./mvnw verify                                  # desktop build and tests, JDK 17+ only
```

## Project layout

```text
src/main/java/com/supremacy/
  game/    Rules engine, state, bots, odds, saves (no rendering)
  board/   Board data, camera, map rendering, HUD, desktop launcher
src/main/resources/   board.json, fonts, audio
src/test/java/        JUnit 5 tests
android/              Android launcher and APK packaging
core/                 Gradle module compiling the shared source
tools/                Optional Python board generator
docs/                 Rules and developer documentation
downloads/            Prebuilt debug APK and checksum
```

## Status

Version 0.2.0. Rules and bots are covered by automated tests. The Android build has not been run on a physical device or emulator, and the 20% population income rule still needs balance playtesting. See [`docs/VERIFICATION.md`](docs/VERIFICATION.md).

## Licence and credits

Geography derives from public-domain Natural Earth data; fonts are Noto under the SIL OFL 1.1. Details are in [`docs/THIRD_PARTY.md`](docs/THIRD_PARTY.md). No licence file for the game's own code is currently present in the repository.
