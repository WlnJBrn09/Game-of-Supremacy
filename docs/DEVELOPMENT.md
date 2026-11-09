# Development guide

## Prerequisites

| Goal | Requirements |
| --- | --- |
| Desktop build and tests | JDK 17 or newer. The Maven wrapper (`mvnw`) fetches Maven. |
| Android APK | JDK 17 or 21, Android SDK platform 35 and build tools 35.0.0. Set `ANDROID_HOME`, or put `sdk.dir=...` in an untracked `local.properties`. |
| Regenerating the board (optional) | Python 3 and `tools/requirements.txt` (Shapely 2.2.0). |

## Build and test

```bash
./gradlew :core:test :android:assembleDebug
```

Runs the JUnit suite and produces `android/build/outputs/apk/debug/android-debug.apk`. The wrapper downloads Gradle 8.9, and Android Gradle Plugin 8.7.3 is pinned.

```bash
./mvnw clean verify
java -jar target/supremacy-game-0.2.0.jar
```

Builds the desktop launcher as a shaded JAR and runs the same tests. On macOS add `-XstartOnFirstThread`. On Windows use `gradlew.bat` / `mvnw.cmd`. Android Studio can open the repository root directly.

Run a single test class with Gradle:

```bash
./gradlew :core:test --tests com.supremacy.game.GameEngineTest
```

## Build systems

Both builds compile the **same sources** in `src/`.

- **Maven** (`pom.xml`) builds the desktop launcher and shaded JAR. Java 17 bytecode, libGDX 1.13.1 with LWJGL3.
- **Gradle** has two modules. `core/` compiles `src/main/java` (excluding `DesktopLauncher`) and runs the tests. `android/` adds `AndroidLauncher`, extracts libGDX natives for `armeabi-v7a`, `arm64-v8a` and `x86_64`, and bundles `src/main/resources` as assets.

When you add a dependency or bump a version, change it in **both** `pom.xml` and the Gradle files. libGDX is currently 1.13.1 and JUnit 5.11.4 in each.

The version is declared in `pom.xml` (`0.2.0`) and in `android/build.gradle` (`versionName`, plus `versionCode`). Keep them in step.

## Desktop system properties

| Property | Effect |
| --- | --- |
| `-Dsupremacy.saveDir=<dir>` | Where saves and preferences live. Defaults to `~/.supremacy`. Use a temporary directory for tests so they never touch a real save. |
| `-Dsupremacy.noAudio=true` | Skips audio initialisation on machines with no audio device (CI, Xvfb). |

On Android the data directory is the app's local storage.

## Tests

JUnit 5 tests live in `src/test/java`: `BoardTest` (geometry and topology), `GameEngineTest` (rules, cards, offers, saves, seeded full matches) and `BotStrategyTest` (odds, objectives, difficulty behaviour). Engine and bot tests are headless and need no graphics context.

When changing rules, add or update a test in `GameEngineTest` first. When changing bot logic, check that the seeded full-match test still finishes at all six difficulties.

## Changing the board

`src/main/resources/board.json` is generated, so do not hand-edit it. To change territory groupings, borders, labels or sea lanes:

```bash
python -m venv tools/.venv
source tools/.venv/bin/activate
pip install -r tools/requirements.txt
python tools/generate_board.py
```

The script downloads Natural Earth GeoJSON from a pinned commit into `tools/cache/` (git-ignored) and checks the SHA-256 of each file. After regenerating:

1. Run the tests, since `BoardTest` checks territory count and connectivity.
2. Check whether `docs/TERRITORIES.md` was rewritten by the generator, and update any counts quoted in the README and `GAME_RULES.md`.
3. Update the continent bonus table in `GAME_RULES.md` if continents changed.

Changing the territory count or order invalidates existing saves. See [`ARCHITECTURE.md`](ARCHITECTURE.md#saves-and-compatibility).

## Releasing a debug APK

1. Bump the version in `pom.xml` and `android/build.gradle`.
2. `./gradlew :core:test :android:assembleDebug`, then `./mvnw verify`.
3. Copy `android/build/outputs/apk/debug/android-debug.apk` to `downloads/GameOfSupremacy-debug.apk`.
4. Refresh the digest: `sha256sum downloads/GameOfSupremacy-debug.apk > downloads/SHA256SUMS`.
5. Update the test counts and any findings in [`VERIFICATION.md`](VERIFICATION.md).

Never commit signing keys, keystores, `local.properties`, emulator images or caches. They are git-ignored.

## Conventions

- All game rules live in `GameEngine`. The UI and bots call its public commands and must never mutate `GameState` directly.
- Rendering code must not decide legality. Ask the engine.
- Keep `docs/GAME_RULES.md` in step with any rule or default you change, including the bot difficulty table.
