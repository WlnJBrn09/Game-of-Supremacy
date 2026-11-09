# Architecture

The game is a single Java code base (`com.supremacy`) shared by an Android launcher and a desktop launcher. It is split into a headless rules layer and a libGDX presentation layer.

```text
 AndroidLauncher / DesktopLauncher
              │
           AtlasApp ── AtlasCamera      draws map, routes touch input
              │
           GameHud                      setup, handover, cards, offers, reports
              │
 ┌────────────┴─────────────┐
 │ GameEngine ── BotStrategy│           rules + bots (package game, no rendering)
 │     │            │       │
 │ GameState     BattleOdds │
 │     │                    │
 │ SaveStore                │
 └──────────────────────────┘
              │
            Board  ◄── board.json       static geometry and adjacency
```

## Packages and classes

### `com.supremacy.game` (headless)

| Class | Responsibility |
| --- | --- |
| `GameEngine` | Single owner of the rules. Exposes commands (`deploy`, `finishDeployment`, `attack`, `occupy`, `finishAttacks`, `fortify`, `endTurn`, `tradeCards`, `sendOffer`, `answer`, `counter`, `revokeBorders`) and queries (`count`, `income`, `canAttack`, `connected`, `report`, ...). Each command validates itself and throws on illegal input. `onChange` listeners fire after each command. |
| `GameState` | Plain mutable save data: phase, players, owners and armies per territory, deck, offers, notices, objectives, movement history, pending occupation and the RNG state. Has a `version` field. |
| `BotStrategy` | Plays a bot turn through the same engine commands a human uses. Keeps continent objectives between turns and searches attack campaigns to a depth set by `GameState.Difficulty`. |
| `BattleOdds` | Exact Risk dice distributions for conquest-probability estimates, with a bounded approximation for very large stacks. |
| `SaveStore` | Atomic JSON persistence with a backup and recovery. |

### `com.supremacy.board` (libGDX)

| Class | Responsibility |
| --- | --- |
| `Board` | Loads `board.json` into `Region`s (polygons, anchor, continent) and `Connection`s (land or sea, with a route polyline). Provides triangulation and hit testing. |
| `AtlasCamera` | Flat, fixed overhead pan/zoom transform (zoom 1–6, clamped to the map). No rotation or perspective. Pacific "wrapping" is done by sea lanes across the map edges, not by the camera. |
| `AtlasApp` | The `ApplicationAdapter`. Renders the map and armies, and translates touches into engine commands via hit rectangles. |
| `GameHud` | Everything that is not the map: setup, handover screen, cards, diplomacy composer, reports and modals. |
| `DesktopLauncher` | LWJGL3 entry point. Reads the system properties described in the [development guide](DEVELOPMENT.md#desktop-system-properties). |

The Android entry point, `AndroidLauncher`, is in `android/` and is the only Android-specific code. The `core` Gradle module excludes `DesktopLauncher`.

## Design rules

1. **The engine is the only authority.** The UI enables controls from engine queries, and bots go through the same commands. A bug in presentation cannot produce an illegal game.
2. **Rules code is headless.** `game` imports no rendering classes except `Board`, which supplies only graph data. This keeps the tests fast and lets bots run without graphics.
3. **Armies are `long`** with checked arithmetic and no artificial cap.
4. **Determinism.** All randomness comes from a seeded generator whose state is saved, so seeded games replay exactly, including after a save and reload. Do not introduce `Math.random()` or unseeded `Random` into rules or bots.
5. **State is saved before presentation.** The exact dice roll and any pending occupation are written to state before animation, so an app pause cannot lose or reroll a battle.
6. **Bots see only public information.** They do not read private card faces or future dice.

## Turn state machine

`GameState.Phase` drives the turn: setup deployment → reinforce → attack → occupy (after a conquest) → fortify → end turn. Entering a phase out of order is rejected by the engine. Mandatory states interrupt the normal order: holding five or more cards blocks leaving reinforce, and an elimination that raises a hand to six or more forces a trade and deployment before attacks continue.

## Diplomacy

Offers are `GameState.Offer` records with a `Status` and are stored in state, so they persist across turns and saves. `sendOffer` validates and queues one. `answer` runs on the recipient's own turn and applies the swap immediately, and `counter` creates a new offer with the parties reversed. Territory swaps redistribute garrison armies to conserve the total. See [`GAME_RULES.md`](GAME_RULES.md#offers-army-gifts-and-open-borders) for the rules and the algorithm summary.

## Saves and compatibility

`SaveStore` writes to a temporary file, validates it, then replaces the primary file atomically where supported. The previous valid save is kept as a backup. A damaged primary falls back to the backup, and an unreadable save is reported to the user and never silently overwritten. `GameEngine.validate()` checks invariants after load.

`GameState.version` is currently `1`. If you change the shape of saved data, or the board's territory list or order, bump the version and handle the old one in load, or explicitly reject it with a clear message. There is currently no migration code.

## Board data

`board.json` holds the canvas size, continents with colours, territories (id, name, polygons, label, anchor, included regions) and connections. It is produced by `tools/generate_board.py` from pinned Natural Earth sources (see [`DEVELOPMENT.md`](DEVELOPMENT.md#changing-the-board)). Sea lanes are defined by hand in the generator (with waypoints so they cross ocean, not land), and land borders are derived from geometry.

## Extending

- **New rule:** change `GameEngine`, add a test in `GameEngineTest`, update `GAME_RULES.md`. If it adds state, add fields to `GameState` and review save compatibility.
- **New bot level:** add to `GameState.Difficulty`, add its parameters (depth, node budget, minimum conquest chance) where levels are configured in `BotStrategy`, extend the full-match test, and update the table in `GAME_RULES.md`.
- **New UI panel:** add a `GameHud.Modal` value and draw it in `GameHud`. Call engine commands only.
