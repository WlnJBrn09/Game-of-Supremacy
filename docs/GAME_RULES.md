# Game rules

## Match and setup

Exactly five players. Choose one to five humans; remaining seats are filled, in order, by Charles, George, Douglas and Dan. Each human enters a name (1–12 characters, unique among all players, case-insensitive) and picks one of eight colours; no two players share a colour. Computer players keep their traditional colour when it is free and otherwise take the first unused one. All bots share the chosen difficulty: Beginner, Easy, Medium, Hard, MegaHard or TeraHard. The 72 territories are shuffled and dealt round-robin; each receives one army. Every player deploys the remainder of their **starting armies**: 55 by default, or 35, 45 or 70 when chosen on the title screen. Human players deploy their own armies (by hand, or with AUTO-PLACE); bots deploy automatically. Human turn order is the setup order.

### Match options

| Option | Choices | Default |
| --- | --- | --- |
| Starting armies | 35, 45, 55, 70 | 55 |
| Population bonus | on, off | on |

Both are stored in the save, so a resumed match keeps its options. Saves from before options existed load with the defaults.

World conquest wins. Every territory and army count is public. Card faces are private; a handover screen covers the game between human turns and after returning from the background.

## Turn sequence

1. **Reinforce:** receive `max(3, floor(territories / 3)) + continent bonuses + floor(on-board armies / 5)`. The last term is the population bonus and is zero when the option is off. Population is counted once at the start of the turn, before card trades and deployment. Deploy all reserve armies before attacking. Each tap places 1, 3, 5 or all remaining armies; AUTO places the whole reserve using the stronger bots' deployment planning (without randomness), spread over several fronts.
2. **Attack:** select your source and an adjacent enemy, including connected sea neighbours. Roll one battle at a time with one to three attack dice, limited by armies available after leaving one behind. **Blitz** repeats maximum-dice rolls until the territory falls or only one attacker remains; it uses exactly the same engine command and dice as individual rolls. Defence automatically uses up to two dice. Compare highest against highest, then second highest if available; ties favour defence. Stop attacking whenever you choose.
3. **Occupy:** after conquest, move at least the number of attacking dice into the captured territory, leaving at least one behind. The exact roll and pending occupation are saved before any animation.
4. **Fortify:** (drag from a territory's army badge onto a connected territory of yours, or tap both; the move is confirmed with the amount slider before anything changes) make as many transfers as desired between territories connected through friendly land or agreed open borders, including sea connections. Leave one behind. Each individual army may be transferred only once during this phase; arrivals cannot be forwarded again.
5. **End turn:** if you captured at least one territory, draw exactly one card. Elimination transfers only the eliminated player's cards, without an extra army reward.

**Undo.** Placements (including a whole AUTO placement) and fortification moves can be taken back one step at a time until something irreversible happens: a dice roll, a card trade, an accepted offer, revoking open borders, finishing setup, entering fortification or ending the turn. Undoing the last placement after the game has moved on to attacking returns to the reinforce step. Undo history is not saved; it is cleared when the app restarts.

Armies use checked 64-bit counts without an artificial gameplay cap. The 20% population rule is deliberately substantial; balancing it remains a playtesting task, which is why it can be switched off per match.

## Continent bonuses

| Continent | Armies per turn |
| --- | ---: |
| North America | 9 |
| South America | 3 |
| Europe | 6 |
| Africa | 5 |
| Asia | 8 |
| Australia | 4 |
| Middle-East | 3 |

## Cards

There is one territory card per territory and two wilds. The three card symbols are infantry, cavalry and artillery; on the map, armies are shown as numbered tokens in each player's colour rather than individual pieces. Valid sets are three of one symbol, or one of each; wilds substitute for either. Trade values increase globally: **4, 6, 8, 10, 12, 15, then +5** per subsequent set. If the set includes an owned territory, the first matching card receives two extra armies on that territory.

Trade sets during reinforcement. Holding five or more cards prevents leaving that phase. Reaching six or more by elimination interrupts attacks for mandatory trading and deployment; it does not grant another regular turn income.

## Offers, army gifts and open borders

An offer may include multiple territories in either direction, one adjacent army shipment in each direction, and mutual open borders. An empty side is allowed, so gifts are possible. Each side must retain at least one of its existing territories. Gift sources and destinations cannot simultaneously be traded; shipments leave one army at their source.

The recipient can accept, reject or counter during their own turn. Acceptance executes immediately. The sender sees the response on their following turn. Open borders and ownership are public facts, so their effects may be visible before the private notification. Pending assets remain usable; an impossible offer expires when accepted. Counteroffers are new pending offers with the participants reversed. Up to twelve outgoing offers may be pending per player.

For each traded territory, all original garrison armies return automatically to bordering territories of their original owner. Without such a border, breadth-first distance over board connections (including sea paths) selects the nearest friendly destinations. Armies are divided evenly, with ties resolved by board order. The incoming owner supplies the mandatory one-army garrison from spare troops on unchanged territory. An offer is invalid if they lack enough spare garrison troops. This conserves armies instead of creating or converting units.

Open borders allows **mutual transit during fortification**. Armies must finish on their own land. It is not a ceasefire and does not allow attacks originating on another player's territory. Either participant may revoke it on their turn. Arrival armies from an accepted offer are also marked as moved if acceptance happens during fortification.

## Bots and reports

Bots use the same engine commands and legal moves as humans. They retain continent objectives between turns, evaluate reinforcements and border threats, plan attack chains with increasing lookahead, seek eliminations and continent completion, and move reserves toward useful fronts. They can propose territory swaps, adjacent aid and open borders, and accept/reject/counter offers. They do not inspect private enemy card faces or future dice.

Bots retain reachable continent objectives across turns, balancing conquest cost against income and the number of borders to defend. They estimate conquest chances from exact Risk dice distributions for battles up to 180 attacking and defending armies (a bounded approximation handles larger stacks). Campaign searches account for attrition, required garrisons, continent completion, breaking rival bonuses, elimination and counterattacks. Medium and above split reinforcement batches and retain defensive reserves; completed continents receive extra protection during movement and trade valuation.

| Level | Additional campaign depth | Search nodes per candidate | Minimum conquest chance |
| --- | ---: | ---: | ---: |
| Beginner | 0 | 0 | 35% |
| Easy | 1 | 20 | 50% |
| Medium | 2 | 80 | 66% |
| Hard | 3 | 240 | 73% |
| MegaHard | 5 | 700 | 78% |
| TeraHard | 7 | 1,800 | 80% |

Beginner and Easy include decision noise; Medium and higher make deterministic evaluations of the visible board. Search limits bound mobile work, and deeper planning uses estimated remaining forces rather than simulating every possible dice outcome. Higher difficulties increase planning depth and reduce decision noise; they do not receive extra armies or loaded dice. These are bounded strategic heuristics, not a trained opponent.

Computer turns play out on the map one action at a time: placements, attacks (with an arrow in the attacker's colour and rising loss numbers), captures and moves, with the camera following when the action leaves the screen. **Normal** speed takes a few seconds per computer turn, **Fast** about a third of that, and **Skip** resolves everything up to the next human turn at once. At the start of a human turn, territories lost since that player's previous turn are outlined in red and gained ones in green, with a summary; the battle log lists every event, with those since the player's last turn marked. If every human is eliminated, a defeat screen with match statistics offers to watch the rest or start a new game; world conquest shows the same statistics (territories captured, armies destroyed and lost, elimination turn).

## Saves

One automatic match slot. The game saves after every human action, at every change of player or phase, while an occupation is pending, at least every few seconds while computers play, and synchronously when the app is paused. Writing happens on a background thread so animation never stalls. Writes use a validated temporary file and atomic replacement where supported, plus the previous valid save as a backup. Save data includes the deck, random generator state, offers, objectives, movement history and pending occupation. A damaged primary save can recover from the backup; an unreadable save is reported, never silently replaced. Starting a new match requires confirmation when a save already exists.
