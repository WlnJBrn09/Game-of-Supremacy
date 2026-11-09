use std::sync::{Arc, OnceLock};
use supremacy::board::Board;
use supremacy::game::*;

pub fn board() -> Arc<Board> {
    static BOARD: OnceLock<Arc<Board>> = OnceLock::new();
    BOARD.get_or_init(|| Arc::new(Board::bundled())).clone()
}

fn game() -> GameEngine {
    GameEngine::new(board(), 1, Difficulty::Hard, 123456).unwrap()
}

fn first_owned(g: &GameEngine, player: usize) -> usize {
    (0..g.state.owners.len())
        .find(|&i| g.state.owners[i] == player)
        .unwrap()
}

fn finish_setup(g: &mut GameEngine) {
    for p in 0..5 {
        let r = first_owned(g, p);
        g.deploy(r, g.state.reserve).unwrap();
        g.finish_deployment().unwrap();
    }
}

fn assign(g: &mut GameEngine, territory: &str, owner: usize, armies: i64) {
    let r = g.index(territory);
    g.state.owners[r] = owner;
    g.state.armies[r] = armies;
}

fn take_card(g: &mut GameEngine, player: usize, card: usize) {
    g.state.deck.retain(|&c| c != card);
    g.state.players[player].cards.push(card);
}

fn offer() -> Offer {
    let mut o = Offer::new(0, 1);
    o.open_borders = true;
    o
}

fn names(g: &GameEngine) -> Vec<&str> {
    g.state.players.iter().map(|p| p.name.as_str()).collect()
}

#[test]
fn five_players_random_deal_fifty_five_each_and_population_income() {
    let mut g = game();
    assert_eq!(5, g.state.players.len());
    assert_eq!(
        vec!["Player 1", "Charles", "George", "Douglas", "Dan"],
        names(&g)
    );
    assert_eq!(72, g.state.owners.len());
    finish_setup(&mut g);
    for p in 0..5 {
        assert_eq!(55, g.total(p));
    }
    assert_eq!(Phase::Reinforce, g.state.phase);
    assert_eq!(
        (g.count(0) as i64 / 3).max(3) + g.continent_income(0) + 11,
        g.state.reserve
    );
    g.validate().unwrap();
}

#[test]
fn ties_defend_and_dice_compared_highest_first() {
    assert_eq!([2, 0], losses(&[6, 5, 1], &[6, 5]).unwrap());
    assert_eq!([0, 2], losses(&[6, 5, 1], &[4, 3]).unwrap());
    assert_eq!([1, 1], losses(&[2, 6, 3], &[5, 4]).unwrap());
    assert!(losses(&[], &[1]).is_err());
}

#[test]
fn conquest_occupation_elimination_and_world_victory() {
    let mut g = game();
    g.state.phase = Phase::Attack;
    g.state.active = 0;
    g.state.owners.fill(0);
    g.state.armies.fill(1);
    let (a, b) = (g.index("US-West"), g.index("US-Midwest"));
    g.state.owners[b] = 1;
    g.state.armies[a] = 100;
    while g.state.phase == Phase::Attack {
        g.attack(a, b, 3).unwrap();
    }
    assert_eq!(Phase::Occupy, g.state.phase);
    assert_eq!(3, g.state.occupation_minimum);
    assert!(g.occupy(2).is_err());
    g.occupy(99).unwrap();
    assert_eq!(Phase::Finished, g.state.phase);
    assert_eq!(0, g.state.winner);
    g.validate().unwrap();
}

#[test]
fn no_second_fortification_of_arrivals_and_open_borders_transit_only() {
    let mut g = game();
    g.state.active = 0;
    g.state.phase = Phase::Fortify;
    assign(&mut g, "US-West", 0, 12);
    assign(&mut g, "US-Midwest", 0, 1);
    assign(&mut g, "US-East", 0, 1);
    let (a, b, c) = (
        g.index("US-West"),
        g.index("US-Midwest"),
        g.index("US-East"),
    );
    g.fortify(a, b, 6).unwrap();
    assert_eq!(0, g.movable(b));
    assert!(g.fortify(b, c, 1).is_err());
    g.fortify(a, c, 3).unwrap();
    assert_eq!(4, g.state.armies[c]);
    // Isolate a simple path through another owner by making all other nodes hostile.
    g.state.owners.fill(2);
    assign(&mut g, "Hawaii", 0, 10);
    assign(&mut g, "Japan", 1, 2);
    assign(&mut g, "Korea", 0, 2);
    let (h, k) = (g.index("Hawaii"), g.index("Korea"));
    assert!(!g.connected(h, k, 0));
    g.state.borders[0][1] = true;
    g.state.borders[1][0] = true;
    assert!(g.connected(h, k, 0));
    assert!(!g.can_attack(h, k));
}

#[test]
fn cards_escalate_and_owned_match_bonus() {
    let mut g = game();
    g.state.phase = Phase::Reinforce;
    g.state.reserve = 0;
    for c in [0, 3, 6] {
        take_card(&mut g, 0, c);
    }
    g.state.owners[0] = 0;
    let before = g.state.armies[0];
    g.trade_cards(&[0, 3, 6]).unwrap();
    assert_eq!(4, g.state.reserve);
    assert_eq!(before + 2, g.state.armies[0]);
    assert_eq!(6, g.next_set_value());
    g.state.trade_count = 5;
    assert_eq!(15, g.next_set_value());
    g.state.trade_count = 6;
    assert_eq!(20, g.next_set_value());
    let n = g.state.owners.len();
    assert!(g.valid_set(&[0, 1, n]));
    assert_eq!(3, g.symbol(n));
    assert!(!g.valid_set(&[0, 3, 1]));
    g.validate().unwrap();
}

#[test]
fn pending_offers_do_not_reserve_assets_and_acceptance_is_immediate() {
    let mut g = game();
    g.state.phase = Phase::Attack;
    assign(&mut g, "US-West", 0, 15);
    assign(&mut g, "US-Midwest", 1, 3);
    let mut o = offer();
    o.give_armies = 4;
    o.give_source = g.index("US-West") as i32;
    o.give_target = g.index("US-Midwest") as i32;
    let id = g.send_offer(&o).unwrap();
    let (source, target) = (o.give_source as usize, o.give_target as usize);
    assert_eq!(15, g.state.armies[source]);
    g.state.active = 1;
    assert!(g.answer(id, true).unwrap());
    assert_eq!(11, g.state.armies[source]);
    assert_eq!(7, g.state.armies[target]);
    assert!(g.state.borders[0][1]);
    assert!(g.responses().is_empty());
    g.state.active = 0;
    assert_eq!(1, g.responses().len());
    g.validate().unwrap();
}

#[test]
fn stale_offer_invalidates_without_changing_armies() {
    let mut g = game();
    g.state.phase = Phase::Attack;
    assign(&mut g, "US-West", 0, 15);
    assign(&mut g, "US-Midwest", 1, 3);
    let mut o = offer();
    o.give_armies = 10;
    o.give_source = g.index("US-West") as i32;
    o.give_target = g.index("US-Midwest") as i32;
    let id = g.send_offer(&o).unwrap();
    g.state.armies[o.give_source as usize] = 5;
    let before = g.state.armies.clone();
    g.state.active = 1;
    assert!(!g.answer(id, true).unwrap());
    assert_eq!(OfferStatus::Invalid, g.offer(id).unwrap().status);
    assert_eq!(before, g.state.armies);
}

#[test]
fn counteroffer_waits_for_original_senders_turn() {
    let mut g = game();
    g.state.phase = Phase::Attack;
    let old = g.send_offer(&offer()).unwrap();
    g.state.active = 1;
    let mut replacement = Offer::new(1, 0);
    replacement.open_borders = true;
    let next = g.counter(old, &replacement).unwrap();
    assert_eq!(OfferStatus::Countered, g.offer(old).unwrap().status);
    assert!(g.inbox().is_empty());
    g.state.active = 0;
    assert_eq!(next, g.inbox()[0].id);
    assert!(g.answer(next, true).unwrap());
}

#[test]
fn territory_trade_returns_garrisons_and_conserves_armies() {
    let mut g = game();
    g.state.phase = Phase::Attack;
    assign(&mut g, "US-West", 0, 10);
    assign(&mut g, "US-Midwest", 0, 3);
    assign(&mut g, "US-East", 1, 10);
    assign(&mut g, "Alaska", 1, 5);
    let total: i64 = g.state.armies.iter().sum();
    let mut o = offer();
    o.give_territories.push(g.index("US-West"));
    o.want_territories.push(g.index("Alaska"));
    let id = g.send_offer(&o).unwrap();
    g.state.active = 1;
    assert!(g.answer(id, true).unwrap());
    assert_eq!(1, g.state.owners[g.index("US-West")]);
    assert_eq!(0, g.state.owners[g.index("Alaska")]);
    assert_eq!(1, g.state.armies[g.index("US-West")]);
    assert_eq!(total, g.state.armies.iter().sum::<i64>());
    g.validate().unwrap();
}

#[test]
fn autosave_restores_pending_occupation_random_sequence_and_recovers_backup() {
    let dir = tempfile::tempdir().unwrap();
    let mut g = game();
    finish_setup(&mut g);
    let mut store = SaveStore::new(dir.path().join("auto.json"));
    store.save(&g).unwrap();
    let r = first_owned(&g, g.state.active);
    g.deploy(r, g.state.reserve).unwrap();
    store.save(&g).unwrap();
    let mut loaded = store.load(&g.board).unwrap();
    assert_eq!(g.state.armies, loaded.state.armies);
    assert_eq!(g.state.rng, loaded.state.rng);
    assert_eq!(g.random(100), loaded.random(100));
    std::fs::write(dir.path().join("auto.json"), "broken").unwrap();
    let backup = store.load(&g.board).unwrap();
    assert_eq!(Phase::Reinforce, backup.state.phase);
    assert!(!store.warning().is_empty());
}

#[test]
fn trade_does_not_spend_unaffected_fortification_armies() {
    let mut g = game();
    g.state.phase = Phase::Fortify;
    g.state.active = 0;
    assign(&mut g, "US-West", 0, 20);
    assign(&mut g, "US-Midwest", 1, 10);
    assign(&mut g, "Ontario", 1, 10);
    let mut o = offer();
    o.give_armies = 4;
    o.give_source = g.index("US-West") as i32;
    o.give_target = g.index("US-Midwest") as i32;
    let id = g.send_offer(&o).unwrap();
    g.state.active = 1;
    assert!(g.answer(id, true).unwrap());
    assert_eq!(9, g.movable(g.index("US-Midwest")));
    assert_eq!(9, g.movable(g.index("Ontario")));
}

#[test]
fn pending_occupation_is_durable_and_dice_cannot_be_rerolled() {
    let dir = tempfile::tempdir().unwrap();
    let mut g = game();
    g.state.phase = Phase::Attack;
    assign(&mut g, "US-West", 0, 100);
    assign(&mut g, "US-Midwest", 1, 1);
    let (a, b) = (g.index("US-West"), g.index("US-Midwest"));
    while g.state.phase == Phase::Attack {
        g.attack(a, b, 3).unwrap();
    }
    let mut store = SaveStore::new(dir.path().join("match.json"));
    store.save(&g).unwrap();
    let mut copy = store.load(&g.board).unwrap();
    assert_eq!(Phase::Occupy, copy.state.phase);
    assert_eq!(0, copy.state.armies[b]);
    assert_eq!(g.state.attack_dice, copy.state.attack_dice);
    assert_eq!(g.state.rng, copy.state.rng);
    assert!(copy.attack(a, b, 3).is_err());
    copy.occupy(3).unwrap();
    copy.validate().unwrap();
}

#[test]
fn elimination_transfers_only_cards_and_awards_one_conquest_card() {
    let mut g = game();
    g.state.phase = Phase::Attack;
    g.state.owners.fill(2);
    g.state.armies.fill(1);
    assign(&mut g, "US-West", 0, 100);
    assign(&mut g, "US-Midwest", 1, 1);
    take_card(&mut g, 1, 4);
    let before = g.total(0);
    let (a, b) = (g.index("US-West"), g.index("US-Midwest"));
    while g.state.phase == Phase::Attack {
        g.attack(a, b, 3).unwrap();
    }
    assert_eq!(vec![4], g.state.players[0].cards);
    assert!(g.state.players[1].cards.is_empty());
    g.occupy(3).unwrap();
    assert!(g.total(0) <= before);
    g.finish_attacks().unwrap();
    g.end_turn().unwrap();
    assert_eq!(2, g.state.players[0].cards.len());
    g.validate().unwrap();
}

#[test]
fn territory_exchange_needs_the_recipients_own_garrison() {
    let mut g = game();
    g.state.phase = Phase::Attack;
    g.state.owners.fill(1);
    g.state.armies.fill(1);
    assign(&mut g, "US-West", 0, 10);
    assign(&mut g, "Alaska", 0, 1);
    let mut o = offer();
    o.give_territories.push(g.index("US-West"));
    assert!(!g.offer_valid(&o));
    assert!(g.send_offer(&o).is_err());
}

#[test]
fn elimination_card_trade_interrupts_attacks_until_deployment() {
    let mut g = game();
    g.state.phase = Phase::Attack;
    g.state.owners.fill(2);
    g.state.armies.fill(1);
    assign(&mut g, "US-West", 0, 100);
    assign(&mut g, "US-Midwest", 1, 1);
    for c in [0, 3, 6] {
        take_card(&mut g, 0, c);
    }
    for c in [1, 4, 7] {
        take_card(&mut g, 1, c);
    }
    let (a, b) = (g.index("US-West"), g.index("US-Midwest"));
    while g.state.phase == Phase::Attack {
        g.attack(a, b, 3).unwrap();
    }
    g.occupy(3).unwrap();
    assert_eq!(Phase::Reinforce, g.state.phase);
    assert_eq!(0, g.state.reserve);
    assert!(g.finish_deployment().is_err());
    let set = g.find_set(0);
    g.trade_cards(&set).unwrap();
    assert_eq!(4, g.state.reserve);
    g.deploy(a, 4).unwrap();
    g.finish_deployment().unwrap();
    assert_eq!(Phase::Attack, g.state.phase);
    g.validate().unwrap();
}

#[test]
fn seeded_complete_bot_matches_reach_world_conquest() {
    for difficulty in Difficulty::ALL {
        for seed in [721, 42, 2026] {
            let mut g = GameEngine::new(board(), 1, difficulty, seed).unwrap();
            for p in &mut g.state.players {
                p.human = false;
            }
            let mut bots = BotStrategy::new();
            let mut actions = 0;
            while actions < 50000 && g.state.phase != Phase::Finished {
                bots.step(&mut g).unwrap();
                if actions % 100 == 0 {
                    g.validate().unwrap();
                }
                actions += 1;
            }
            assert_eq!(
                Phase::Finished,
                g.state.phase,
                "Unfinished {difficulty:?} seed {seed} turn {}",
                g.state.turn
            );
            g.validate().unwrap();
        }
    }
}

#[test]
fn conquest_chance_reflects_both_stacks_without_using_randomness() {
    let mut g = game();
    finish_setup(&mut g);
    assign(&mut g, "Brazil", 0, 2);
    assign(&mut g, "Peru", 1, 5);
    let rng = g.state.rng;
    let (brazil, peru) = (g.index("Brazil"), g.index("Peru"));
    let weak = g.conquest_chance(brazil, peru);
    g.state.armies[brazil] = 30;
    let strong = g.conquest_chance(brazil, peru);
    assert!(
        weak < 0.05 && strong > 0.95 && strong <= 1.0,
        "{weak} {strong}"
    );
    assert_eq!(rng, g.state.rng);
}

#[test]
fn humans_choose_names_and_colours_and_computers_take_the_rest() {
    let names_in = ["  Waylon   B ".to_string(), "Dan".to_string()];
    let g = GameEngine::with_players(
        board(),
        2,
        Difficulty::Medium,
        7,
        Some(&names_in),
        Some(&[1, 7]),
    )
    .unwrap();
    let players = &g.state.players;
    assert_eq!("Waylon B", players[0].name);
    assert_eq!("Dan", players[1].name);
    assert_eq!(
        vec!["Charles", "George", "Douglas"],
        names(&g)[2..].to_vec()
    );
    assert_eq!(1, players[0].color);
    assert_eq!(7, players[1].color);
    let distinct: std::collections::HashSet<i32> = players.iter().map(|p| p.color).collect();
    assert_eq!(5, distinct.len());
    // Computers keep their traditional colour when free and never reuse a human's.
    assert_eq!(2, players[2].color);
    assert_eq!(3, players[3].color);
    assert_eq!(4, players[4].color);
    assert_eq!("4D9EC4", g.state.color(0));
    g.validate().unwrap();
}

#[test]
fn rejects_duplicate_or_invalid_names_and_colours() {
    let b = board();
    let d = Difficulty::Easy;
    let names = |v: &[&str]| v.iter().map(|s| s.to_string()).collect::<Vec<_>>();
    assert!(
        GameEngine::with_players(b.clone(), 2, d, 1, Some(&names(&["Sam", "sam"])), None).is_err()
    );
    assert!(
        GameEngine::with_players(b.clone(), 1, d, 1, Some(&names(&["charles"])), None).is_err()
    );
    assert!(GameEngine::with_players(
        b.clone(),
        1,
        d,
        1,
        Some(&names(&["ThisNameIsFarTooLong"])),
        None
    )
    .is_err());
    assert!(GameEngine::with_players(b.clone(), 2, d, 1, None, Some(&[3, 3])).is_err());
    assert!(GameEngine::with_players(b.clone(), 1, d, 1, None, Some(&[8])).is_err());
    assert!(GameEngine::new(b.clone(), 0, d, 1).is_err());
    // A blank name falls back to the default seat name.
    let g = GameEngine::with_players(b, 1, d, 1, Some(&names(&["  "])), None).unwrap();
    assert_eq!("Player 1", g.state.players[0].name);
}

#[test]
fn colour_assignment_skips_human_choices() {
    assert_eq!([2, 1, 0, 3, 4], assign_colors(1, Some(&[2])).unwrap());
    assert_eq!([4, 5, 2, 3, 0], assign_colors(2, Some(&[4, 5])).unwrap());
}

#[test]
fn names_and_colours_survive_saves_and_old_saves_get_default_colours() {
    let dir = tempfile::tempdir().unwrap();
    let b = board();
    let g = GameEngine::with_players(
        b.clone(),
        1,
        Difficulty::Hard,
        99,
        Some(&["Waylon".to_string()]),
        Some(&[6]),
    )
    .unwrap();
    let mut store = SaveStore::new(dir.path().join("autosave.json"));
    store.save(&g).unwrap();
    let loaded = store.load(&b).unwrap();
    assert_eq!("Waylon", loaded.state.players[0].name);
    assert_eq!(6, loaded.state.players[0].color);
    assert_eq!(g.state.color(3), loaded.state.color(3));
    // A save written before colours existed has colour -1 everywhere.
    let mut legacy = loaded.state.clone();
    for p in &mut legacy.players {
        p.color = -1;
    }
    let migrated = GameEngine::restore(b.clone(), legacy).unwrap();
    for i in 0..5 {
        assert_eq!(i as i32, migrated.state.players[i].color);
    }
    // Saves without a colour field at all also migrate.
    let mut json: serde_json::Value = serde_json::to_value(&loaded.state).unwrap();
    for p in json["players"].as_array_mut().unwrap() {
        p.as_object_mut().unwrap().remove("color");
    }
    let state: GameState = serde_json::from_value(json).unwrap();
    assert_eq!(
        2,
        GameEngine::restore(b, state).unwrap().state.players[2].color
    );
}

#[test]
fn saves_reject_tampering_and_other_boards() {
    let dir = tempfile::tempdir().unwrap();
    let g = game();
    let mut bad = g.state.clone();
    bad.territory_ids[0] = "atlantis".into();
    assert_eq!(
        "Save belongs to a different board",
        GameEngine::restore(board(), bad).err().unwrap().0
    );
    let mut dup = g.state.clone();
    let card = dup.deck[0];
    dup.players[0].cards.push(card);
    assert!(GameEngine::restore(board(), dup).is_err());
    let mut store = SaveStore::new(dir.path().join("none.json"));
    assert!(!store.exists());
    assert_eq!("No saved match", store.load(&board()).err().unwrap());
}
