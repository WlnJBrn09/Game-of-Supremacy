//! Prints a one-line summary of seeded all-computer matches (used to compare engine ports).
use std::sync::Arc;
use supremacy::board::Board;
use supremacy::game::{BotStrategy, Difficulty, GameEngine, Phase};

fn main() {
    let board = Arc::new(Board::bundled());
    for d in Difficulty::ALL {
        for seed in [721u64, 42, 2026, 7] {
            let mut g = GameEngine::new(board.clone(), 1, d, seed).unwrap();
            for p in &mut g.state.players {
                p.human = false;
            }
            let mut bots = BotStrategy::new();
            let mut actions = 0;
            while actions < 50000 && g.state.phase != Phase::Finished {
                bots.step(&mut g).unwrap();
                actions += 1;
            }
            let s = &g.state;
            println!(
                "{} {seed} turn={} winner={} rng={} offers={} history={} trades={} actions={actions}",
                d.name(), s.turn, s.winner, s.rng, s.offers.len(), s.history.len(), s.trade_count
            );
        }
    }
}
