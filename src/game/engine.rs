//! All rules live here; the UI and bots use exactly the same validated commands.

use super::odds;
use super::state::*;
use crate::board::Board;
use std::collections::{BTreeSet, HashSet, VecDeque};
use std::fmt;
use std::sync::Arc;

/// A refused command or an invalid saved state. The message is shown to players.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RuleError(pub String);

impl fmt::Display for RuleError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for RuleError {}

pub type Result<T> = std::result::Result<T, RuleError>;

pub(crate) fn check(condition: bool, message: impl Into<String>) -> Result<()> {
    if condition {
        Ok(())
    } else {
        Err(RuleError(message.into()))
    }
}

fn add(a: i64, b: i64) -> Result<i64> {
    a.checked_add(b)
        .ok_or_else(|| RuleError("long overflow".into()))
}

/// Continent bonuses by continent name.
pub fn bonus(continent: &str) -> i64 {
    match continent {
        "North America" => 9,
        "South America" => 3,
        "Europe" => 6,
        "Africa" => 5,
        "Asia" => 8,
        "Australia" => 4,
        "Middle-East" => 3,
        _ => 0,
    }
}

/// Armies lost by `[attacker, defender]`: highest dice are compared in pairs, ties defend.
pub fn losses(attacker: &[i32], defender: &[i32]) -> Result<[i64; 2]> {
    check(
        (1..=3).contains(&attacker.len()) && (1..=2).contains(&defender.len()),
        "Invalid dice",
    )?;
    let (mut a, mut d) = (attacker.to_vec(), defender.to_vec());
    a.sort_unstable();
    d.sort_unstable();
    let (mut al, mut dl) = (0, 0);
    for i in 1..=a.len().min(d.len()) {
        if a[a.len() - i] > d[d.len() - i] {
            dl += 1;
        } else {
            al += 1;
        }
    }
    Ok([al, dl])
}

/// Names are trimmed, single-spaced and limited to [`MAX_NAME`] characters.
pub fn clean_name(raw: &str) -> Result<String> {
    let name = raw.split_whitespace().collect::<Vec<_>>().join(" ");
    check(!name.is_empty(), "Enter a name")?;
    check(
        name.chars().count() <= MAX_NAME,
        "Names can have up to 12 characters",
    )?;
    Ok(name)
}

/// Colours for all five seats: humans keep their (distinct) choice and computer players take
/// their traditional colour when it is free, otherwise the first unused one.
pub fn assign_colors(humans: usize, human_colors: Option<&[i32]>) -> Result<[usize; PLAYERS]> {
    let mut result = [0usize; PLAYERS];
    let mut used = [false; COLORS.len()];
    for (i, slot) in result.iter_mut().enumerate().take(humans) {
        let c = human_colors
            .and_then(|h| h.get(i).copied())
            .unwrap_or(i as i32);
        check(c >= 0 && (c as usize) < used.len(), "Unknown colour")?;
        let c = c as usize;
        check(!used[c], format!("Two players chose {}", COLOR_NAMES[c]))?;
        used[c] = true;
        *slot = c;
    }
    for (i, slot) in result.iter_mut().enumerate().skip(humans) {
        let c = if used[i] {
            used.iter().position(|u| !u).expect("free colour")
        } else {
            i
        };
        used[c] = true;
        *slot = c;
    }
    Ok(result)
}

struct Exchange {
    owners: Vec<usize>,
    armies: Vec<i64>,
}

#[derive(Clone)]
pub struct GameEngine {
    pub board: Arc<Board>,
    pub state: GameState,
    adjacent: Vec<Vec<usize>>,
    revision: u64,
}

impl GameEngine {
    /// Starts a match with default human names and colours.
    pub fn new(
        board: Arc<Board>,
        humans: usize,
        difficulty: Difficulty,
        seed: u64,
    ) -> Result<Self> {
        Self::with_players(board, humans, difficulty, seed, None, None)
    }

    /// Starts a match. `human_names` and `human_colors` (palette indexes) describe the human seats
    /// in order; missing or blank entries use defaults. Computer players take the remaining colours.
    pub fn with_players(
        board: Arc<Board>,
        humans: usize,
        difficulty: Difficulty,
        seed: u64,
        human_names: Option<&[String]>,
        human_colors: Option<&[i32]>,
    ) -> Result<Self> {
        check((1..=PLAYERS).contains(&humans), "Choose one to five humans")?;
        let n = board.regions.len();
        let mut state = GameState {
            difficulty,
            rng: if seed == 0 { 1 } else { seed },
            owners: vec![0; n],
            armies: vec![0; n],
            moved: vec![0; n],
            territory_ids: board.regions.iter().map(|r| r.id.clone()).collect(),
            ..GameState::default()
        };
        let colors = assign_colors(humans, human_colors)?;
        for (i, &color) in colors.iter().enumerate() {
            let human = i < humans;
            let chosen = if human {
                human_names.and_then(|h| h.get(i))
            } else {
                None
            };
            let name = if !human {
                BOT_NAMES[i - humans].to_string()
            } else {
                match chosen {
                    Some(c) if !c.trim().is_empty() => clean_name(c)?,
                    _ => format!("Player {}", i + 1),
                }
            };
            state.players.push(Player {
                name,
                human,
                color: color as i32,
                cards: Vec::new(),
                objective: String::new(),
            });
        }
        let mut seen = HashSet::new();
        for p in &state.players {
            check(
                seen.insert(p.name.to_lowercase()),
                format!("Two players are named {}", p.name),
            )?;
        }
        let mut game = GameEngine {
            adjacent: adjacency(&board),
            board,
            state,
            revision: 0,
        };
        let mut deal: Vec<usize> = (0..n).collect();
        game.shuffle_deal(&mut deal);
        for (i, &r) in deal.iter().enumerate() {
            game.state.owners[r] = i % PLAYERS;
            game.state.armies[r] = 1;
        }
        let mut deck: Vec<usize> = (0..n + 2).collect();
        game.shuffle_deal(&mut deck);
        game.state.deck = deck;
        game.state.reserve = 55 - game.count(0) as i64;
        game.log("Random territories assigned. Each player starts with 55 armies.".into());
        Ok(game)
    }

    /// Restores a saved match, migrating saves written before colours were selectable.
    pub fn restore(board: Arc<Board>, mut state: GameState) -> Result<Self> {
        if state.players.iter().any(|p| p.color < 0) {
            for (i, p) in state.players.iter_mut().enumerate() {
                p.color = i as i32;
            }
        }
        let game = GameEngine {
            adjacent: adjacency(&board),
            board,
            state,
            revision: 0,
        };
        game.validate()?;
        Ok(game)
    }

    /// Increments on every successful state change; observers save when it moves.
    pub fn revision(&self) -> u64 {
        self.revision
    }

    fn changed(&mut self) {
        self.revision += 1;
    }

    pub fn neighbours(&self, r: usize) -> &[usize] {
        &self.adjacent[r]
    }

    pub fn name(&self, r: usize) -> &str {
        &self.board.regions[r].name
    }

    pub fn index(&self, name: &str) -> usize {
        self.board.index(name)
    }

    pub fn count(&self, player: usize) -> usize {
        self.state.owners.iter().filter(|&&o| o == player).count()
    }

    pub fn total(&self, player: usize) -> i64 {
        self.state
            .owners
            .iter()
            .zip(&self.state.armies)
            .filter(|(&o, _)| o == player)
            .map(|(_, &a)| a)
            .fold(0i64, |n, a| n.saturating_add(a))
    }

    pub fn alive(&self, player: usize) -> bool {
        self.count(player) > 0
    }

    pub fn continent_income(&self, player: usize) -> i64 {
        let mut n = 0;
        for (c, continent) in self.board.continents.iter().enumerate() {
            let all = (0..self.state.owners.len())
                .all(|i| self.board.regions[i].continent != c || self.state.owners[i] == player);
            if all {
                n += bonus(&continent.name);
            }
        }
        n
    }

    pub fn income(&self, player: usize) -> Result<i64> {
        add(
            (self.count(player) as i64 / 3).max(3) + self.continent_income(player),
            self.total(player) / 5,
        )
    }

    pub fn adjacent(&self, a: usize, b: usize) -> bool {
        self.valid_region(a as i64) && self.valid_region(b as i64) && self.adjacent[a].contains(&b)
    }

    pub fn movable(&self, r: usize) -> i64 {
        (self.state.armies[r] - self.state.moved[r] - 1).max(0)
    }

    pub fn can_attack(&self, from: usize, to: usize) -> bool {
        let s = &self.state;
        s.phase == Phase::Attack
            && self.valid_region(from as i64)
            && self.valid_region(to as i64)
            && s.owners[from] == s.active
            && s.owners[to] != s.active
            && s.armies[from] > 1
            && self.adjacent(from, to)
    }

    /// Chance that attacking with every spare army eventually conquers `to`.
    pub fn conquest_chance(&self, from: usize, to: usize) -> f64 {
        odds::win(
            (self.state.armies[from] - 1) as f64,
            self.state.armies[to] as f64,
        )
    }

    pub fn deploy(&mut self, r: usize, amount: i64) -> Result<()> {
        check(
            matches!(self.state.phase, Phase::Setup | Phase::Reinforce),
            "Not a deployment phase",
        )?;
        self.own(r)?;
        check(
            amount > 0 && amount <= self.state.reserve,
            "Invalid reinforcement amount",
        )?;
        let next = add(self.state.armies[r], amount)?;
        self.state.armies[r] = next;
        self.state.reserve -= amount;
        self.changed();
        Ok(())
    }

    pub fn finish_deployment(&mut self) -> Result<()> {
        let s = &self.state;
        check(
            matches!(s.phase, Phase::Setup | Phase::Reinforce) && s.reserve == 0,
            "Place all remaining armies first",
        )?;
        if s.phase == Phase::Setup {
            if s.active < 4 {
                self.state.active += 1;
                self.state.reserve = 55 - self.count(self.state.active) as i64;
            } else {
                self.state.active = 0;
                self.begin_turn()?;
            }
        } else {
            check(
                s.active_player().cards.len() < 5,
                "Trade cards while holding five or more",
            )?;
            self.state.phase = Phase::Attack;
            self.state.return_to_attack = false;
        }
        self.changed();
        Ok(())
    }

    pub fn attack(&mut self, from: usize, to: usize, dice: usize) -> Result<()> {
        check(
            self.can_attack(from, to),
            "Choose adjacent enemy territory and leave one army behind",
        )?;
        check(
            dice >= 1 && (dice as i64) <= 3.min(self.state.armies[from] - 1),
            "Invalid attack dice",
        )?;
        let defender = self.state.owners[to];
        let attack = self.roll(dice);
        let defend = self.roll(2.min(self.state.armies[to]) as usize);
        let lost = losses(&attack, &defend)?;
        self.state.attack_dice = attack;
        self.state.defend_dice = defend;
        self.state.armies[from] -= lost[0];
        self.state.armies[to] -= lost[1];
        let attacker_name = self.state.active_player().name.clone();
        let defender_name = self.state.players[defender].name.clone();
        let failed = format!(
            "{attacker_name} attacked {defender_name} at {} without capturing it.",
            self.name(to)
        );
        if self.state.armies[to] == 0 {
            let s = &mut self.state;
            s.owners[to] = s.active;
            s.phase = Phase::Occupy;
            s.occupation_from = from as i32;
            s.occupation_to = to as i32;
            s.occupation_minimum = dice as i64;
            s.conquered = true;
            let turn = s.turn;
            s.history
                .retain(|n| !(n.created_turn == turn && n.message == failed));
            let won = format!(
                "{attacker_name} attacked {defender_name} and won {}.",
                self.name(to)
            );
            self.log(won);
            if !self.alive(defender) {
                let s = &mut self.state;
                let cards = std::mem::take(&mut s.players[defender].cards);
                s.players[s.active].cards.extend(cards);
                for p in 0..PLAYERS {
                    s.borders[defender][p] = false;
                    s.borders[p][defender] = false;
                }
                self.log(format!(
                    "{defender_name} was eliminated; their cards transfer to {attacker_name}."
                ));
            }
        } else {
            let turn = self.state.turn;
            let already = self
                .state
                .history
                .iter()
                .any(|n| n.created_turn == turn && n.message == failed);
            if !already {
                self.log(failed);
            }
        }
        self.changed();
        Ok(())
    }

    fn roll(&mut self, n: usize) -> Vec<i32> {
        (0..n).map(|_| 1 + self.random(6) as i32).collect()
    }

    pub fn occupy(&mut self, amount: i64) -> Result<()> {
        check(self.state.phase == Phase::Occupy, "No occupation pending")?;
        let (from, to) = (
            self.state.occupation_from as usize,
            self.state.occupation_to as usize,
        );
        check(
            amount >= self.state.occupation_minimum && amount < self.state.armies[from],
            "Move at least the attacking dice count and leave one behind",
        )?;
        let s = &mut self.state;
        s.armies[from] -= amount;
        s.armies[to] = amount;
        s.occupation_from = -1;
        s.occupation_to = -1;
        s.phase = Phase::Attack;
        if self.count(self.state.active) == self.state.owners.len() {
            self.state.winner = self.state.active as i32;
            self.state.phase = Phase::Finished;
            self.log(format!(
                "{} conquered the world!",
                self.state.active_player().name
            ));
        } else if self.state.active_player().cards.len() >= 6 {
            self.state.phase = Phase::Reinforce;
            self.state.reserve = 0;
            self.state.return_to_attack = true;
        }
        self.changed();
        Ok(())
    }

    pub fn finish_attacks(&mut self) -> Result<()> {
        check(
            self.state.phase == Phase::Attack,
            "Finish deployment or occupation first",
        )?;
        self.state.phase = Phase::Fortify;
        self.state.moved.fill(0);
        self.changed();
        Ok(())
    }

    /// Open borders permits transit during fortification, never ownership or attacking from
    /// allied land.
    pub fn connected(&self, from: usize, to: usize, player: usize) -> bool {
        let s = &self.state;
        if !self.valid_region(from as i64)
            || !self.valid_region(to as i64)
            || s.owners[from] != player
            || s.owners[to] != player
        {
            return false;
        }
        let mut seen = vec![false; self.adjacent.len()];
        let mut q = VecDeque::from([from]);
        while let Some(r) = q.pop_front() {
            if r == to {
                return true;
            }
            if seen[r] {
                continue;
            }
            seen[r] = true;
            for &n in &self.adjacent[r] {
                if !seen[n] && (s.owners[n] == player || s.borders[player][s.owners[n]]) {
                    q.push_back(n);
                }
            }
        }
        false
    }

    pub fn fortify(&mut self, from: usize, to: usize, amount: i64) -> Result<()> {
        check(
            self.state.phase == Phase::Fortify,
            "Fortify at the end of your turn",
        )?;
        self.own(from)?;
        self.own(to)?;
        check(
            from != to
                && amount > 0
                && amount <= self.movable(from)
                && self.connected(from, to, self.state.active),
            "Use connected friendly territory; each army may move only once",
        )?;
        let next = add(self.state.armies[to], amount)?;
        let moved = add(self.state.moved[to], amount)?;
        self.state.armies[from] -= amount;
        self.state.armies[to] = next;
        self.state.moved[to] = moved;
        self.changed();
        Ok(())
    }

    pub fn end_turn(&mut self) -> Result<()> {
        check(self.state.phase == Phase::Fortify, "Finish attacking first")?;
        if self.state.conquered {
            self.draw_card();
        }
        self.state.active = (self.state.active + 1) % PLAYERS;
        while !self.alive(self.state.active) {
            self.state.active = (self.state.active + 1) % PLAYERS;
        }
        self.begin_turn()?;
        self.changed();
        Ok(())
    }

    fn begin_turn(&mut self) -> Result<()> {
        let income = self.income(self.state.active)?;
        let s = &mut self.state;
        s.turn += 1;
        s.phase = Phase::Reinforce;
        s.conquered = false;
        s.return_to_attack = false;
        s.moved.fill(0);
        s.attack_dice.clear();
        s.defend_dice.clear();
        s.reserve = income;
        self.log(format!(
            "{} begins turn {} with {} reinforcements.",
            self.state.active_player().name,
            self.state.turn,
            self.state.reserve
        ));
        Ok(())
    }

    fn draw_card(&mut self) {
        if self.state.deck.is_empty() {
            let mut deck = std::mem::take(&mut self.state.discard);
            self.shuffle_deal(&mut deck);
            self.state.deck = deck;
        }
        if let Some(card) = self.state.deck.pop() {
            let active = self.state.active;
            self.state.players[active].cards.push(card);
        }
    }

    /// 0 infantry, 1 cavalry, 2 artillery, 3 wild.
    pub fn symbol(&self, card: usize) -> usize {
        if card >= self.state.owners.len() {
            3
        } else {
            card % 3
        }
    }

    pub fn valid_set(&self, cards: &[usize]) -> bool {
        if cards.len() != 3 || cards.iter().collect::<HashSet<_>>().len() != 3 {
            return false;
        }
        let mut c = [0usize; 3];
        let mut wild = 0;
        for &id in cards {
            if id >= self.state.owners.len() + 2 {
                return false;
            }
            match self.symbol(id) {
                3 => wild += 1,
                s => c[s] += 1,
            }
        }
        c.iter().max().copied().unwrap_or(0) + wild == 3
            || c.iter().filter(|&&x| x > 0).count() + wild == 3
    }

    pub fn find_set(&self, player: usize) -> Vec<usize> {
        let cards = &self.state.players[player].cards;
        for a in 0..cards.len() {
            for b in a + 1..cards.len() {
                for c in b + 1..cards.len() {
                    let set = vec![cards[a], cards[b], cards[c]];
                    if self.valid_set(&set) {
                        return set;
                    }
                }
            }
        }
        Vec::new()
    }

    pub fn next_set_value(&self) -> i64 {
        let t = self.state.trade_count as i64;
        if t < 5 {
            4 + t * 2
        } else {
            15 + (t - 5) * 5
        }
    }

    pub fn trade_cards(&mut self, cards: &[usize]) -> Result<()> {
        check(
            self.state.phase == Phase::Reinforce,
            "Trade at the start of a turn or after an elimination",
        )?;
        let active = self.state.active;
        let hand = &self.state.players[active].cards;
        check(
            self.valid_set(cards) && cards.iter().all(|c| hand.contains(c)),
            "Select a valid three-card set",
        )?;
        let reward = self.next_set_value();
        let reserve = add(self.state.reserve, reward)?;
        let bonus = cards
            .iter()
            .copied()
            .find(|&c| c < self.state.owners.len() && self.state.owners[c] == active);
        if let Some(b) = bonus {
            add(self.state.armies[b], 2)?;
        }
        let s = &mut self.state;
        s.players[active].cards.retain(|c| !cards.contains(c));
        s.discard.extend_from_slice(cards);
        s.trade_count += 1;
        s.reserve = reserve;
        if let Some(b) = bonus {
            s.armies[b] += 2;
        }
        self.log(format!(
            "{} traded a card set for {reward} armies.",
            self.state.active_player().name
        ));
        self.changed();
        Ok(())
    }

    /// Sends an offer from the active player; returns its id.
    pub fn send_offer(&mut self, draft: &Offer) -> Result<i32> {
        let id = self.create_offer(draft)?;
        self.changed();
        Ok(id)
    }

    fn create_offer(&mut self, draft: &Offer) -> Result<i32> {
        let s = &self.state;
        check(
            !matches!(s.phase, Phase::Setup | Phase::Occupy | Phase::Finished),
            "Finish setup or occupation first",
        )?;
        check(
            draft.from == s.active
                && draft.to < PLAYERS
                && draft.to != draft.from
                && self.alive(draft.to),
            "Choose another active player",
        )?;
        let mut offer = Offer {
            from: draft.from,
            to: draft.to,
            give_territories: draft.give_territories.clone(),
            want_territories: draft.want_territories.clone(),
            give_source: draft.give_source,
            give_target: draft.give_target,
            give_armies: draft.give_armies,
            want_source: draft.want_source,
            want_target: draft.want_target,
            want_armies: draft.want_armies,
            open_borders: draft.open_borders,
            ..Offer::default()
        };
        check(
            offer.open_borders
                || !offer.give_territories.is_empty()
                || !offer.want_territories.is_empty()
                || offer.give_armies > 0
                || offer.want_armies > 0,
            "Offer is empty",
        )?;
        check(
            self.offer_valid(&offer),
            "Offer terms are not currently possible",
        )?;
        check(
            s.offers
                .iter()
                .filter(|o| o.from == s.active && o.status == OfferStatus::Pending)
                .count()
                < 12,
            "At most twelve pending offers",
        )?;
        offer.id = self.state.next_offer_id;
        self.state.next_offer_id += 1;
        offer.created_turn = self.state.turn;
        let id = offer.id;
        self.state.offers.push(offer);
        Ok(id)
    }

    /// Pending offers addressed to the active player.
    pub fn inbox(&self) -> Vec<&Offer> {
        self.state
            .offers
            .iter()
            .filter(|o| o.to == self.state.active && o.status == OfferStatus::Pending)
            .collect()
    }

    /// Answered offers from the active player that they have not yet seen.
    pub fn responses(&self) -> Vec<&Offer> {
        let s = &self.state;
        s.offers
            .iter()
            .filter(|o| {
                o.from == s.active
                    && o.status != OfferStatus::Pending
                    && o.response_turn > s.last_read_turn[s.active]
            })
            .collect()
    }

    pub fn mark_read(&mut self) {
        let active = self.state.active;
        self.state.last_read_turn[active] = self.state.turn;
        self.changed();
    }

    pub fn offer_valid(&self, o: &Offer) -> bool {
        if o.from >= PLAYERS
            || o.to >= PLAYERS
            || o.from == o.to
            || !self.alive(o.from)
            || !self.alive(o.to)
        {
            return false;
        }
        let unique = |v: &[usize]| v.iter().collect::<HashSet<_>>().len() == v.len();
        if !unique(&o.give_territories) || !unique(&o.want_territories) {
            return false;
        }
        let s = &self.state;
        if o.give_territories
            .iter()
            .any(|&r| !self.valid_region(r as i64) || s.owners[r] != o.from)
            || o.want_territories
                .iter()
                .any(|&r| !self.valid_region(r as i64) || s.owners[r] != o.to)
        {
            return false;
        }
        // Neither side may be eliminated by diplomacy. This also guarantees a refuge for all garrisons.
        if self.count(o.from) < 1 + o.give_territories.len()
            || self.count(o.to) < 1 + o.want_territories.len()
        {
            return false;
        }
        if !self.army_gift_valid(o.give_source, o.give_target, o.give_armies, o.from, o.to, o)
            || !self.army_gift_valid(o.want_source, o.want_target, o.want_armies, o.to, o.from, o)
        {
            return false;
        }
        if o.give_armies < 0 || o.want_armies < 0 {
            return false;
        }
        self.prepare_exchange(o).is_ok()
    }

    fn army_gift_valid(
        &self,
        source: i32,
        target: i32,
        amount: i64,
        from: usize,
        to: usize,
        o: &Offer,
    ) -> bool {
        if amount == 0 {
            return true;
        }
        if amount < 0 || !self.valid_region(source as i64) || !self.valid_region(target as i64) {
            return false;
        }
        let (source, target) = (source as usize, target as usize);
        let listed = |r: usize| o.give_territories.contains(&r) || o.want_territories.contains(&r);
        self.state.owners[source] == from
            && self.state.owners[target] == to
            && !listed(source)
            && !listed(target)
            && self.adjacent(source, target)
            && self.state.armies[source] > amount
    }

    /// Answers an offer in the active player's inbox. Returns whether an exchange happened.
    pub fn answer(&mut self, id: i32, accept: bool) -> Result<bool> {
        let index = self.offer_index(id)?;
        let o = &self.state.offers[index];
        check(
            o.to == self.state.active && o.status == OfferStatus::Pending,
            "Offer is not in your inbox",
        )?;
        check(
            matches!(
                self.state.phase,
                Phase::Reinforce | Phase::Attack | Phase::Fortify
            ),
            "Cannot answer now",
        )?;
        let turn = self.state.turn;
        if !accept || !self.offer_valid(o) {
            let o = &mut self.state.offers[index];
            o.status = if accept {
                OfferStatus::Invalid
            } else {
                OfferStatus::Rejected
            };
            o.response_turn = turn;
            self.changed();
            return Ok(false);
        }
        let Exchange { owners, armies } = self.prepare_exchange(o)?;
        let (from, to, open) = (o.from, o.to, o.open_borders);
        // Preserve movement history for unaffected armies; only arrivals become spent.
        let old_armies = std::mem::replace(&mut self.state.armies, armies);
        let old_owners = std::mem::replace(&mut self.state.owners, owners);
        let s = &mut self.state;
        if s.phase == Phase::Fortify {
            for i in 0..s.armies.len() {
                if s.owners[i] == s.active {
                    let spare = (s.armies[i] - 1).max(0);
                    s.moved[i] = if old_owners[i] != s.active {
                        spare
                    } else {
                        spare.min(s.moved[i] + (s.armies[i] - old_armies[i]).max(0))
                    };
                }
            }
        }
        if open {
            s.borders[from][to] = true;
            s.borders[to][from] = true;
        }
        let o = &mut s.offers[index];
        o.status = OfferStatus::Accepted;
        o.response_turn = turn;
        self.changed();
        Ok(true)
    }

    fn prepare_exchange(&self, o: &Offer) -> Result<Exchange> {
        let s = &self.state;
        let mut owners = s.owners.clone();
        let mut armies = s.armies.clone();
        if o.give_armies > 0 {
            armies[o.give_source as usize] -= o.give_armies;
            let t = o.give_target as usize;
            armies[t] = add(armies[t], o.give_armies)?;
        }
        if o.want_armies > 0 {
            armies[o.want_source as usize] -= o.want_armies;
            let t = o.want_target as usize;
            armies[t] = add(armies[t], o.want_armies)?;
        }
        for &r in &o.give_territories {
            owners[r] = o.to;
        }
        for &r in &o.want_territories {
            owners[r] = o.from;
        }
        let transferred: Vec<usize> = o
            .give_territories
            .iter()
            .chain(&o.want_territories)
            .copied()
            .collect();
        let returning = armies.clone();
        // Every incoming land gets a garrison from its new owner's unchanged land.
        // Original garrisons all return to their original owner; no army is minted or converted.
        for &source in &transferred {
            let receiving = owners[source];
            let donor = (0..owners.len()).find(|&r| {
                !transferred.contains(&r)
                    && owners[r] == receiving
                    && s.owners[r] == receiving
                    && armies[r] > 1
            });
            let Some(donor) = donor else {
                return Err(RuleError(
                    "The recipient needs a spare army to garrison each incoming territory".into(),
                ));
            };
            armies[donor] -= 1;
            armies[source] = 1;
        }
        for &source in &transferred {
            self.redistribute(
                source,
                s.owners[source],
                returning[source],
                &owners,
                &mut armies,
            )?;
        }
        Ok(Exchange { owners, armies })
    }

    fn redistribute(
        &self,
        source: usize,
        player: usize,
        total: i64,
        owners: &[usize],
        armies: &mut [i64],
    ) -> Result<()> {
        let mut destinations: BTreeSet<usize> = self.adjacent[source]
            .iter()
            .copied()
            .filter(|&r| owners[r] == player)
            .collect();
        if destinations.is_empty() {
            let mut seen = vec![false; owners.len()];
            let mut q = VecDeque::from([source]);
            seen[source] = true;
            while !q.is_empty() && destinations.is_empty() {
                for _ in 0..q.len() {
                    let r = q.pop_front().expect("queued");
                    if owners[r] == player {
                        destinations.insert(r);
                    } else {
                        for &next in &self.adjacent[r] {
                            if !seen[next] {
                                seen[next] = true;
                                q.push_back(next);
                            }
                        }
                    }
                }
            }
        }
        check(
            !destinations.is_empty(),
            "No territory available for returning armies",
        )?;
        let n = destinations.len() as i64;
        for (i, r) in destinations.into_iter().enumerate() {
            let share = total / n + if (i as i64) < total % n { 1 } else { 0 };
            armies[r] = add(armies[r], share)?;
        }
        Ok(())
    }

    /// Replaces a pending offer in the active player's inbox with a counteroffer; returns its id.
    pub fn counter(&mut self, id: i32, replacement: &Offer) -> Result<i32> {
        let index = self.offer_index(id)?;
        let old = &self.state.offers[index];
        check(
            old.to == self.state.active && old.status == OfferStatus::Pending,
            "Offer is not pending",
        )?;
        check(
            replacement.from == old.to && replacement.to == old.from,
            "Counteroffer must reverse the participants",
        )?;
        let next = self.create_offer(replacement)?;
        let turn = self.state.turn;
        let old = &mut self.state.offers[index];
        old.status = OfferStatus::Countered;
        old.response_turn = turn;
        self.changed();
        Ok(next)
    }

    pub fn revoke_borders(&mut self, other: usize) -> Result<()> {
        check(
            other < PLAYERS && other != self.state.active,
            "Invalid player",
        )?;
        let active = self.state.active;
        self.state.borders[active][other] = false;
        self.state.borders[other][active] = false;
        self.changed();
        Ok(())
    }

    fn offer_index(&self, id: i32) -> Result<usize> {
        self.state
            .offers
            .iter()
            .position(|o| o.id == id)
            .ok_or_else(|| RuleError("Unknown offer".into()))
    }

    pub fn offer(&self, id: i32) -> Result<&Offer> {
        Ok(&self.state.offers[self.offer_index(id)?])
    }

    /// Events since the player's last turn, followed by answers to their offers.
    pub fn report(&self, player: usize) -> Vec<String> {
        let s = &self.state;
        let mut lines: Vec<String> = s
            .history
            .iter()
            .filter(|n| n.created_turn > s.last_read_turn[player])
            .map(|n| n.message.clone())
            .collect();
        for o in self.responses() {
            lines.push(format!(
                "Offer #{} to {}: {}",
                o.id,
                s.players[o.to].name,
                o.status.name()
            ));
        }
        lines
    }

    fn log(&mut self, message: String) {
        let turn = self.state.turn;
        self.state.history.push(Notice {
            message,
            created_turn: turn,
        });
        if self.state.history.len() > 1000 {
            self.state.history.remove(0);
        }
    }

    /// Deterministic xorshift draw in `0..bound`; the state is saved with the match.
    pub fn random(&mut self, bound: u64) -> u64 {
        assert!(bound > 0, "Invalid random range");
        let mut x = self.state.rng;
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        self.state.rng = x;
        x % bound
    }

    fn shuffle_deal(&mut self, values: &mut [usize]) {
        for i in (1..values.len()).rev() {
            let j = self.random(i as u64 + 1) as usize;
            values.swap(i, j);
        }
    }

    fn valid_region(&self, r: i64) -> bool {
        r >= 0 && (r as usize) < self.state.owners.len()
    }

    fn region(&self, r: i64) -> Result<()> {
        check(self.valid_region(r), "Unknown territory")
    }

    fn own(&self, r: usize) -> Result<()> {
        self.region(r as i64)?;
        check(
            self.state.owners[r] == self.state.active,
            "Choose your own territory",
        )
    }

    /// Checks every invariant of the match; used after loading and before saving.
    pub fn validate(&self) -> Result<()> {
        let s = &self.state;
        check(
            s.version == 1 && s.players.len() == PLAYERS,
            "Unsupported or corrupt save",
        )?;
        let colors: HashSet<i32> = s
            .players
            .iter()
            .map(|p| p.color)
            .filter(|&c| c >= 0 && (c as usize) < COLORS.len())
            .collect();
        check(colors.len() == PLAYERS, "Invalid player colours")?;
        check(s.active < PLAYERS && s.rng != 0, "Invalid turn state")?;
        check(
            s.territory_ids.len() == self.board.regions.len()
                && s.territory_ids
                    .iter()
                    .zip(&self.board.regions)
                    .all(|(a, r)| *a == r.id),
            "Save belongs to a different board",
        )?;
        let n = self.board.regions.len();
        check(
            s.owners.len() == n && s.armies.len() == n && s.moved.len() == n,
            "Invalid territory data",
        )?;
        check(s.reserve >= 0 && s.trade_count >= 0, "Invalid economy")?;
        check(
            s.borders.len() == PLAYERS && s.last_read_turn.len() == PLAYERS,
            "Invalid diplomacy state",
        )?;
        for p in 0..PLAYERS {
            check(
                s.borders[p].len() == PLAYERS && !s.borders[p][p],
                "Invalid borders",
            )?;
        }
        for p in 0..PLAYERS {
            for q in 0..PLAYERS {
                check(s.borders[p][q] == s.borders[q][p], "Asymmetric borders")?;
            }
        }
        for i in 0..n {
            check(s.owners[i] < PLAYERS, "Invalid owner")?;
            check(
                s.armies[i] >= 0
                    && (s.armies[i] > 0 || s.phase == Phase::Occupy && s.occupation_to == i as i32),
                "Invalid army count",
            )?;
            check(s.moved[i] >= 0, "Invalid movement state")?;
        }
        check(self.alive(s.active), "Active player has no territory")?;
        if s.phase == Phase::Occupy {
            let (f, t) = (s.occupation_from as i64, s.occupation_to as i64);
            let ok = self.valid_region(f) && self.valid_region(t) && {
                let (f, t) = (f as usize, t as usize);
                (1..=3).contains(&s.occupation_minimum)
                    && s.owners[f] == s.active
                    && s.owners[t] == s.active
                    && self.adjacent(f, t)
                    && s.armies[f] > s.occupation_minimum
                    && s.armies[t] == 0
            };
            check(ok, "Invalid occupation")?;
        }
        if s.phase == Phase::Finished {
            check(
                s.winner == s.active as i32 && self.count(s.active) == n,
                "Invalid winner",
            )?;
        }
        for player in &s.players {
            check(!player.name.trim().is_empty(), "Invalid player")?;
        }
        let mut ids = HashSet::new();
        for o in &s.offers {
            check(
                o.id > 0
                    && ids.insert(o.id)
                    && o.id < s.next_offer_id
                    && o.from < PLAYERS
                    && o.to < PLAYERS
                    && o.from != o.to,
                "Invalid saved offer",
            )?;
            check(
                o.give_armies >= 0 && o.want_armies >= 0,
                "Invalid saved gift",
            )?;
            for &r in o.give_territories.iter().chain(&o.want_territories) {
                self.region(r as i64)?;
            }
            if o.give_armies > 0 {
                self.region(o.give_source as i64)?;
                self.region(o.give_target as i64)?;
            }
            if o.want_armies > 0 {
                self.region(o.want_source as i64)?;
                self.region(o.want_target as i64)?;
            }
        }
        for notice in &s.history {
            check(notice.created_turn <= s.turn, "Invalid report")?;
        }
        let mut cards = HashSet::new();
        for p in &s.players {
            for &c in &p.cards {
                check(c < n + 2 && cards.insert(c), "Duplicate card")?;
            }
        }
        for &c in &s.deck {
            check(c < n + 2 && cards.insert(c), "Duplicate deck card")?;
        }
        for &c in &s.discard {
            check(c < n + 2 && cards.insert(c), "Duplicate discard")?;
        }
        check(cards.len() == n + 2, "Missing cards")
    }
}

fn adjacency(board: &Board) -> Vec<Vec<usize>> {
    (0..board.regions.len())
        .map(|r| board.links(r).map(|e| e.other(r)).collect())
        .collect()
}
