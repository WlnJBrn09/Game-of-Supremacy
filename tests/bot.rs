use std::sync::{Arc, OnceLock};
use supremacy::board::Board;
use supremacy::game::{odds, BotStrategy, Difficulty, GameEngine, Phase};

fn board() -> Arc<Board> {
    static BOARD: OnceLock<Arc<Board>> = OnceLock::new();
    BOARD.get_or_init(|| Arc::new(Board::bundled())).clone()
}

fn fixture(difficulty: Difficulty) -> GameEngine {
    let mut g = GameEngine::new(board(), 1, difficulty, 42).unwrap();
    for p in &mut g.state.players {
        p.human = false;
    }
    g.state.active = 0;
    g.state.phase = Phase::Attack;
    g.state.owners.fill(1);
    g.state.armies.fill(10);
    g
}

#[test]
fn exact_battle_probabilities_respect_defending_ties_and_monotonicity() {
    assert!((odds::win(1.0, 1.0) - 15.0 / 36.0).abs() < 1e-12);
    assert!((odds::win(2.0, 1.0) - 0.7542438271604938).abs() < 1e-12);
    for a in 1..40 {
        for d in 1..40 {
            let (a, d) = (a as f64, d as f64);
            assert!(odds::win(a + 1.0, d) >= odds::win(a, d));
            assert!(odds::win(a, d + 1.0) <= odds::win(a, d));
        }
    }
    assert!(odds::win(1e12, 1e12).is_finite());
    assert_eq!(0.0, odds::win(0.0, 1.0));
    assert_eq!(1.0, odds::win(1.0, 0.0));
}

#[test]
fn every_level_declines_hopeless_attacks() {
    for level in Difficulty::ALL {
        let mut g = fixture(level);
        g.state.owners[0] = 0;
        g.state.armies[0] = 2;
        BotStrategy::new().step(&mut g).unwrap();
        assert_eq!(Phase::Fortify, g.state.phase, "{level:?}");
    }
}

#[test]
fn strong_bot_completes_continent_before_taking_peripheral_land() {
    let mut g = fixture(Difficulty::TeraHard);
    let c = g.board.region("Brazil").continent;
    let n = g.state.owners.len();
    for i in 0..n {
        if g.board.regions[i].continent == c {
            g.state.owners[i] = 0;
        }
    }
    let (source, target) = (0..n)
        .filter(|&i| g.board.regions[i].continent == c)
        .find_map(|i| {
            g.neighbours(i)
                .iter()
                .find(|&&m| g.board.regions[m].continent == c)
                .map(|&m| (i, m))
        })
        .unwrap();
    g.state.owners[target] = 1;
    g.state.armies.fill(1);
    g.state.armies[source] = 30;
    // All other owned regions cannot attack; source has several possible conquests.
    let mut bot = BotStrategy::new();
    for _ in 0..20 {
        if g.state.phase != Phase::Attack {
            break;
        }
        bot.step(&mut g).unwrap();
    }
    assert_eq!(Phase::Occupy, g.state.phase);
    assert_eq!(target as i32, g.state.occupation_to);
    for i in 0..n {
        if i != source && i != target {
            assert_eq!(1, g.state.armies[i]);
        }
    }
}

#[test]
fn reinforcement_is_split_and_objective_survives_bot_recreation() {
    let mut g = fixture(Difficulty::Hard);
    g.state.phase = Phase::Reinforce;
    g.state.reserve = 20;
    g.state.owners[0] = 0;
    BotStrategy::new().step(&mut g).unwrap();
    assert_eq!(15, g.state.reserve);
    let goal = g.state.players[0].objective.clone();
    assert!(!goal.is_empty());
    BotStrategy::new().step(&mut g).unwrap();
    assert_eq!(goal, g.state.players[0].objective);
}
