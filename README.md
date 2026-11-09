# Game of Supremacy

An **offline Android conquest game in Java/libGDX**, built around a custom 72-territory world board. Play with exactly five participants: one to five humans sharing a device, filled out by Charles, George, Douglas and Dan.

The board stays directly overhead. Armies are numbered game-piece tokens in player colours, and play is laid out like the classic board game: the world map on top, a command console underneath with the commanders, the phase track (Reinforce → Attack → Fortify) and one big next-step button. Matches include random territories, 55 starting armies per player, Risk dice and escalating cards, world conquest, private hands, automatic saves, asynchronous trade offers, adjacent army gifts and open borders. Six shared AI difficulties use persistent objectives and progressively deeper planning; bot turns are summarized at the next human turn.

![Game screen](docs/game-preview.png)

See [the full rules and chosen defaults](docs/GAME_RULES.md), [territory groupings](docs/TERRITORIES.md), and [verification](docs/VERIFICATION.md).

## Install on Android

Download [GameOfSupremacy-debug.apk](downloads/GameOfSupremacy-debug.apk) from this branch and open it on **Android 8.0 or newer**. Allow installation from the browser/file manager when Android asks. This is a debug-signed build for direct installation. It contains ARMv7, ARM64 and x86-64 native libraries, works offline, and requests no Internet permission. Landscape orientation is enforced. All map, font and audio assets are bundled.

## Build the APK

Use JDK 17 or 21 and an Android SDK with platform 35 and build tools 35.0.0. Set `ANDROID_HOME` or set `sdk.dir` in an untracked `local.properties`. The wrapper downloads Gradle 8.9; Android Gradle Plugin 8.7.3 is pinned.

```sh
./gradlew :core:test :android:assembleDebug
# APK: android/build/outputs/apk/debug/android-debug.apk
```

On Windows use `gradlew.bat`. Android Studio can open this directory directly. The Android launcher lives in `android/`; `core/` compiles the shared Java source and rules tests. Java 17 APIs are desugared for older Android versions.

## Desktop development

A desktop launcher remains available for development and automated interaction checks:

```sh
./mvnw clean verify
java -jar target/supremacy-game-0.2.0.jar
```

On macOS add `-XstartOnFirstThread` to the Java command. On Windows use `mvnw.cmd`. Desktop data defaults to `~/.supremacy`; `-Dsupremacy.saveDir=/path/to/test-data` isolates saves and preferences for tests. `-Dsupremacy.noAudio=true` disables desktop audio initialization on machines without an audio device.

## Playing

On the title screen, tap a seat to switch it between **Human** and **Computer**, pick one computer skill level, and start a new game (or resume the autosave). With one human there is no pass-the-device screen; with several, a handover screen keeps each player's cards private.

The console under the map always tells you what to do next:

- **Reinforce / initial placement:** your territories are ringed in gold. Tap one, drag the slider (or use − / +) and tap **Place**. The big button stays locked until every army is placed; once your last army is down and you have no card set to trade, the game moves straight to Attack. A **Trade cards** button appears whenever you hold a matching set, and the console shows where your reinforcements came from.
- **Attack:** tap one of your ringed territories, then a pulsing enemy neighbour, or tap the enemy first and your strongest bordering territory is picked for you. Pick 1–3 dice and **Roll**, or **Blitz** to keep rolling with maximum dice until you capture it or run out of attackers. The console shows the win chance and every roll's dice, with the losing die of each pair faded. After a capture, drag the slider to choose how many armies move in; your next attack starts from the captured territory.
- **Fortify:** tap a territory, then one of the highlighted connected destinations, and move armies. **End turn** when done.

The **Continent bonuses** panel on the map shows each bonus, your progress and who holds a whole continent; tap its header to hide it. Tap an opponent in the commander list to open diplomacy with them. **Cards**, **Diplomacy**, **Log** and **Menu** are in the top bar. Drag to pan; pinch, scroll or use + / − / **Fit** to zoom. On desktop, hovering a territory shows its owner and armies; Enter performs the next step, R rolls, B blitzes and Esc goes back. The app saves after every action and hides private cards when paused.

## Geography and implementation

Seven continents: North America, South America, Europe, Africa, Asia, Australia and Middle-East. The board retains the requested revisions: Yakutsk and Irkutsk; Belgium with France; Tanzania with Ethiopia; Tasmania with Victoria; Chad as its own territory with Cameroon, the Central African Republic and eastern Niger; northern Mauritania with Algeria. It has 113 land connections and 34 sea lanes, with original nautical decoration and Pacific wrapping.

`GameEngine` owns rules and validates commands independently of the screen or bot. `GameState` is versioned save data. `BotStrategy` plans through the same commands as humans. `SaveStore` handles durable JSON saves and recovery. `AtlasApp` draws the map, army tokens and touch controls; `GameHud` draws the command console and manages setup, handovers, cards, offers and reports. `DesktopLauncher` and `AndroidLauncher` use the same game.

The checked-in board is generated from pinned public-domain Natural Earth sources. To modify it, install `tools/requirements.txt` in a virtual environment and run `python tools/generate_board.py`; Python is not needed to build or play. See [third-party notices](docs/THIRD_PARTY.md).
