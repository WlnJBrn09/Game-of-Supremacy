//! Fair-information, bounded strategic planning. A continent objective persists across turns.

use super::engine::{bonus, GameEngine, Result};
use super::odds;
use super::state::*;

const DEPTH: [i32; 6] = [0, 1, 2, 3, 5, 7];
const BUDGET: [i32; 6] = [0, 20, 80, 240, 700, 1800];
const CONFIDENCE: [f64; 6] = [0.35, 0.50, 0.66, 0.73, 0.78, 0.80];

#[derive(Debug, Clone)]
pub struct BotStrategy {
    planned_turn: i32,
    actions: i32,
    search_budget: i32,
}

impl Default for BotStrategy {
    fn default() -> Self {
        Self::new()
    }
}

impl BotStrategy {
    pub fn new() -> Self {
        BotStrategy {
            planned_turn: -1,
            actions: 0,
            search_budget: 0,
        }
    }

    /// Performs one command for the active computer player. Does nothing on a human turn.
    pub fn step(&mut self, game: &mut GameEngine) -> Result<()> {
        if game.state.phase == Phase::Finished || game.state.active_player().human {
            return Ok(());
        }
        if self.planned_turn != game.state.turn {
            self.planned_turn = game.state.turn;
            self.actions = 0;
            choose_objective(game);
        }
        let active = game.state.active;
        if !matches!(game.state.phase, Phase::Setup | Phase::Occupy) {
            if let Some(o) = game.inbox().first().map(|o| (*o).clone()) {
                if !game.offer_valid(&o) {
                    game.answer(o.id, true)?;
                    return Ok(());
                }
                let value = offer_value(game, &o);
                if value >= 0.0 {
                    game.answer(o.id, true)?;
                    return Ok(());
                }
                let pending = game
                    .state
                    .offers
                    .iter()
                    .filter(|x| x.from == active && x.status == OfferStatus::Pending)
                    .count();
                if value > -12.0 && pending < 3 {
                    let counter = Offer {
                        from: o.to,
                        to: o.from,
                        give_territories: o.want_territories.clone(),
                        want_territories: o.give_territories.clone(),
                        give_armies: o.want_armies,
                        give_source: o.want_source,
                        give_target: o.want_target,
                        want_armies: o.give_armies,
                        want_source: o.give_source,
                        want_target: o.give_target,
                        open_borders: true,
                        ..Offer::default()
                    };
                    if game.offer_valid(&counter) {
                        game.counter(o.id, &counter)?;
                        return Ok(());
                    }
                }
                game.answer(o.id, false)?;
                return Ok(());
            }
        }
        match game.state.phase {
            Phase::Setup | Phase::Reinforce => {
                if game.state.phase == Phase::Reinforce {
                    let set = game.find_set(active);
                    if !set.is_empty() {
                        return game.trade_cards(&set);
                    }
                }
                if game.state.reserve > 0 {
                    let target = best_deployment(game);
                    // Allocate in batches so threatened bonus borders and campaign fronts both get troops.
                    let reserve = game.state.reserve;
                    let batch = if game.state.difficulty.level() < 2 {
                        reserve
                    } else {
                        (reserve / 4).max(1)
                    };
                    game.deploy(target, batch)
                } else {
                    game.finish_deployment()
                }
            }
            Phase::Attack => {
                let first = self.actions == 0;
                self.actions += 1;
                if first && game.state.difficulty.level() >= 2 {
                    propose_borders(game)?;
                }
                if self.actions > 300 {
                    return game.finish_attacks();
                }
                match self.best_attack(game) {
                    None => game.finish_attacks(),
                    Some((a, b)) => {
                        let dice = 3.min(game.state.armies[a] - 1) as usize;
                        game.attack(a, b, dice)
                    }
                }
            }
            Phase::Occupy => {
                let s = &game.state;
                let from = s.occupation_from as usize;
                let leave = (s.armies[from] - s.occupation_minimum)
                    .min(defence_reserve(game, from))
                    .max(1);
                let amount = s.occupation_minimum.max(s.armies[from] - leave);
                game.occupy(amount)
            }
            Phase::Fortify => {
                let s = &game.state;
                let n = s.owners.len();
                let (mut from, mut to, mut best) = (None, 0, 0.0);
                for a in 0..n {
                    if s.owners[a] == active
                        && game.movable(a) > 0
                        && s.armies[a] > defence_reserve(game, a)
                    {
                        for b in 0..n {
                            if a != b && s.owners[b] == active && game.connected(a, b, active) {
                                let score = front_value(game, b) - front_value(game, a);
                                if score > best {
                                    best = score;
                                    from = Some(a);
                                    to = b;
                                }
                            }
                        }
                    }
                }
                let Some(from) = from else {
                    return game.end_turn();
                };
                let over = self.actions > 330;
                self.actions += 1;
                if over {
                    return game.end_turn();
                }
                let amount = game
                    .movable(from)
                    .min((s.armies[from] - defence_reserve(game, from)).max(0));
                if amount > 0 {
                    game.fortify(from, to, amount)
                } else {
                    game.end_turn()
                }
            }
            Phase::Finished => Ok(()),
        }
    }

    fn best_attack(&mut self, game: &mut GameEngine) -> Option<(usize, usize)> {
        let level = game.state.difficulty.level();
        let active = game.state.active;
        let n = game.state.owners.len();
        let mut best = 0.0;
        let mut result = None;
        for a in 0..n {
            if game.state.owners[a] != active || game.state.armies[a] <= 1 {
                continue;
            }
            for &b in game.neighbours(a).to_vec().iter() {
                if !game.can_attack(a, b) {
                    continue;
                }
                let s = &game.state;
                let force = s.armies[a] as f64 - 1.0;
                let odds = odds::win(force, s.armies[b] as f64);
                if odds < CONFIDENCE[level] {
                    continue;
                }
                let mut captured = vec![false; n];
                captured[b] = true;
                let remaining = (force - s.armies[b] as f64 * 0.9 - 1.0).max(0.0);
                let mut reward =
                    capture_value(game, b, &captured) + if !s.conquered { 3.0 } else { 0.0 };
                self.search_budget = BUDGET[level];
                reward += self.campaign(game, b, remaining, DEPTH[level], &mut captured) * 0.5;
                let s = &game.state;
                let mut exposure = 0.0;
                if level >= 2 {
                    // The campaign must also survive the other four players' next turns.
                    let mut retaliation = 0.0f64;
                    for &m in game.neighbours(b) {
                        if s.owners[m] != active && !captured[m] {
                            retaliation = retaliation.max(s.armies[m] as f64 - 1.0);
                        }
                    }
                    exposure = (retaliation - remaining).max(0.0) / force.max(1.0) * 4.0;
                    if owns_continent(game, active, a) {
                        exposure += (defence_reserve(game, a) as f64
                            - (s.armies[a] as f64 - force))
                            .max(0.0)
                            / force.max(1.0)
                            * 4.0;
                    }
                }
                let mut score = odds * reward
                    - (1.0 - odds) * force.min(30.0)
                    - s.armies[b] as f64 / force.max(1.0) * 3.0
                    - exposure;
                if level < 2 {
                    score += (game.random(101) as f64 / 100.0 - 0.5) * (3 - level as i32) as f64;
                }
                if score > best {
                    best = score;
                    result = Some((a, b));
                }
            }
        }
        result
    }

    fn campaign(
        &mut self,
        game: &GameEngine,
        r: usize,
        force: f64,
        depth: i32,
        captured: &mut [bool],
    ) -> f64 {
        if depth == 0 || force < 2.0 {
            return 0.0;
        }
        let budget = self.search_budget;
        self.search_budget -= 1;
        if budget <= 0 {
            return 0.0;
        }
        let s = &game.state;
        let mut best = 0.0f64;
        for &n in game.neighbours(r) {
            if captured[n] || s.owners[n] == s.active {
                continue;
            }
            let odds = odds::win(force, s.armies[n] as f64);
            if odds < CONFIDENCE[s.difficulty.level()] {
                continue;
            }
            captured[n] = true;
            let remaining = force - s.armies[n] as f64 * 0.9 - 1.0;
            let value = odds
                * (capture_value(game, n, captured)
                    + 0.55 * self.campaign(game, n, remaining, depth - 1, captured))
                - s.armies[n] as f64 / force.max(1.0) * 4.0
                - (1.0 - odds) * force.min(30.0);
            captured[n] = false;
            best = best.max(value);
            if self.search_budget <= 0 {
                break;
            }
        }
        best
    }
}

fn continent_name(game: &GameEngine, r: usize) -> &str {
    &game.board.continent_of(r).name
}

fn choose_objective(game: &mut GameEngine) {
    let s = &game.state;
    if s.phase == Phase::Finished {
        return;
    }
    let old = s.active_player().objective.clone();
    let mut best = f64::MIN;
    let mut chosen = old.clone();
    let n = s.owners.len();
    for (c, continent) in game.board.continents.iter().enumerate() {
        let in_c = |i: usize| game.board.regions[i].continent == c;
        let (mut total, mut owned, mut resistance) = (0usize, 0usize, 0i64);
        for i in (0..n).filter(|&i| in_c(i)) {
            total += 1;
            if s.owners[i] == s.active {
                owned += 1;
            } else {
                resistance += s.armies[i];
            }
        }
        if owned == total {
            continue;
        }
        // Prefer a reachable, affordable campaign; retain a viable goal across turns.
        let (mut access, mut perimeter, mut force) = (0, 0, 0.0f64);
        for i in 0..n {
            if s.owners[i] == s.active {
                let reaches = in_c(i) || game.neighbours(i).iter().any(|&m| in_c(m));
                if reaches {
                    access += 1;
                    force += (s.armies[i] - 1).max(0) as f64;
                }
            }
            if in_c(i) && game.neighbours(i).iter().any(|&m| !in_c(m)) {
                perimeter += 1;
            }
        }
        if access == 0 {
            continue;
        }
        let cost = resistance as f64 * 0.9 + (total - owned) as f64;
        let score = owned as f64 * 8.0 / total as f64
            + bonus(&continent.name) as f64 * 2.0 / (1.0 + perimeter as f64 * 0.3)
            - cost / (force + s.reserve as f64).max(4.0) * 3.0
            + if continent.name == old { 1.8 } else { 0.0 };
        if score > best {
            best = score;
            chosen = continent.name.clone();
        }
    }
    let active = game.state.active;
    game.state.players[active].objective = chosen;
}

fn owns_continent(game: &GameEngine, player: usize, region: usize) -> bool {
    let c = game.board.regions[region].continent;
    (0..game.state.owners.len())
        .all(|i| game.board.regions[i].continent != c || game.state.owners[i] == player)
}

fn defence_reserve(game: &GameEngine, r: usize) -> i64 {
    let s = &game.state;
    let strongest = game
        .neighbours(r)
        .iter()
        .filter(|&&n| s.owners[n] != s.active)
        .map(|&n| s.armies[n] - 1)
        .fold(0, i64::max);
    if strongest == 0 {
        return 1;
    }
    let fraction = if owns_continent(game, s.active, r) {
        0.85
    } else {
        0.35
    };
    ((strongest as f64 * fraction).ceil() as i64).max(1)
}

fn front_value(game: &GameEngine, r: usize) -> f64 {
    let s = &game.state;
    if enemy_pressure(game, r) == 0 {
        return -10.0 - s.armies[r] as f64 * 0.01;
    }
    let deficit = (defence_reserve(game, r) - s.armies[r]) as f64;
    deficit.max(-8.0)
        * if owns_continent(game, s.active, r) {
            2.0
        } else {
            0.5
        }
        - (s.armies[r] as f64).ln_1p()
        + if continent_name(game, r) == s.active_player().objective {
            4.0
        } else {
            0.0
        }
}

fn best_deployment(game: &mut GameEngine) -> usize {
    let n = game.state.owners.len();
    let active = game.state.active;
    let mut best = 0;
    let mut value = f64::MIN;
    for r in 0..n {
        if game.state.owners[r] != active {
            continue;
        }
        let mut score = front_value(game, r);
        let objective = &game.state.active_player().objective;
        for &m in game.neighbours(r) {
            if game.state.owners[m] != active && continent_name(game, m) == objective {
                score += 8.0;
            }
        }
        if game.state.difficulty.level() < 2 {
            score += game.random(20) as f64;
        }
        if score > value {
            value = score;
            best = r;
        }
    }
    best
}

fn capture_value(game: &GameEngine, r: usize, captured: &[bool]) -> f64 {
    let s = &game.state;
    let continent = game.board.regions[r].continent;
    let in_c = |i: usize| game.board.regions[i].continent == continent;
    let mut enemy_bonus = owns_continent(game, s.owners[r], r);
    if (0..s.owners.len()).any(|i| i != r && captured[i] && in_c(i)) {
        enemy_bonus = false;
    }
    let (mut missing, mut enemy_remaining) = (0, 0);
    for i in 0..s.owners.len() {
        if !captured[i] && s.owners[i] != s.active && in_c(i) {
            missing += 1;
        }
        if !captured[i] && s.owners[i] == s.owners[r] {
            enemy_remaining += 1;
        }
    }
    let name = &game.board.continents[continent].name;
    let mut value = 2.0;
    if *name == s.active_player().objective {
        value += 3.0;
    }
    if missing == 0 {
        value += bonus(name) as f64 * 8.0;
    }
    if enemy_bonus {
        value += bonus(name) as f64 * 2.0;
    }
    if enemy_remaining == 0 {
        value += 12.0; // Never inspect another player's private cards.
    }
    value
}

fn enemy_pressure(game: &GameEngine, r: usize) -> i64 {
    let s = &game.state;
    game.neighbours(r)
        .iter()
        .filter(|&&n| s.owners[n] != s.active)
        .map(|&n| s.armies[n])
        .fold(0i64, |a, b| a.saturating_add(b))
}

fn offer_value(game: &GameEngine, o: &Offer) -> f64 {
    let mut value = (o.give_armies - o.want_armies) as f64;
    for &r in &o.give_territories {
        value += territory_value(game, r);
    }
    for &r in &o.want_territories {
        value -= territory_value(game, r);
    }
    if o.open_borders {
        value += 2.0;
    }
    value
}

fn territory_value(game: &GameEngine, r: usize) -> f64 {
    let s = &game.state;
    let name = continent_name(game, r);
    let base = if owns_continent(game, s.active, r) {
        6.0 + bonus(name) as f64 * 4.0
    } else {
        6.0
    };
    base + if name == s.active_player().objective {
        5.0
    } else {
        0.0
    }
}

fn propose_borders(game: &mut GameEngine) -> Result<()> {
    let s = &game.state;
    let active = s.active;
    if s.turn < 5
        || s.offers
            .iter()
            .any(|o| o.from == active && o.status == OfferStatus::Pending)
    {
        return Ok(());
    }
    let mut strongest = 0;
    for p in 1..PLAYERS {
        if game.total(p) > game.total(strongest) {
            strongest = p;
        }
    }
    let n = s.owners.len();
    let continent = |r: usize| game.board.regions[r].continent;
    let cname = |r: usize| game.board.continents[continent(r)].name.as_str();
    for p in 0..PLAYERS {
        if p == active || p == strongest || !game.alive(p) || s.borders[active][p] {
            continue;
        }
        let bordering = (0..n)
            .any(|r| s.owners[r] == active && game.neighbours(r).iter().any(|&m| s.owners[m] == p));
        if !bordering {
            continue;
        }
        let mut o = Offer::new(active, p);
        o.open_borders = true;
        // Swap peripheral land for land in each player's persistent objective.
        let ours = s.players[active].objective.as_str();
        let theirs = s.players[p].objective.as_str();
        'outer: for a in 0..n {
            if s.owners[a] == active && cname(a) != ours && cname(a) == theirs {
                let complete =
                    (0..n).all(|r| continent(r) != continent(a) || s.owners[r] == active);
                if complete {
                    continue;
                }
                for b in 0..n {
                    if s.owners[b] == p && cname(b) == ours && cname(b) != theirs {
                        o.give_territories.push(a);
                        o.want_territories.push(b);
                        if game.offer_valid(&o) {
                            break 'outer;
                        }
                        o.give_territories.clear();
                        o.want_territories.clear();
                    }
                }
            }
        }
        // A small adjacent gift may strengthen a buffer against the leading opponent.
        if o.give_territories.is_empty() && strongest != active {
            'gift: for a in 0..n {
                if s.owners[a] == active && s.armies[a] > 12 {
                    for &b in game.neighbours(a) {
                        if s.owners[b] == p {
                            for &m in game.neighbours(b) {
                                if s.owners[m] == strongest && s.armies[m] > s.armies[b] {
                                    o.give_source = a as i32;
                                    o.give_target = b as i32;
                                    o.give_armies = 3.min((s.armies[a] - 1) / 5);
                                    break 'gift;
                                }
                            }
                        }
                    }
                }
            }
        }
        game.send_offer(&o)?;
        return Ok(());
    }
    Ok(())
}
