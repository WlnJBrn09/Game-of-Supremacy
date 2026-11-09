//! Exact maximum-dice conquest odds for ordinary battles; bounded approximation for huge armies.

use super::engine::losses;
use std::sync::OnceLock;

const LIMIT: usize = 180;

fn table() -> &'static Vec<f64> {
    static WIN: OnceLock<Vec<f64>> = OnceLock::new();
    WIN.get_or_init(|| {
        let mut outcomes = [[[0.0f64; 3]; 3]; 4];
        for a in 1..=3usize {
            for d in 1..=2usize {
                let combinations = 6usize.pow((a + d) as u32);
                for roll in 0..combinations {
                    let mut code = roll;
                    let mut attack = vec![0; a];
                    let mut defend = vec![0; d];
                    for v in attack.iter_mut() {
                        *v = (code % 6 + 1) as i32;
                        code /= 6;
                    }
                    for v in defend.iter_mut() {
                        *v = (code % 6 + 1) as i32;
                        code /= 6;
                    }
                    let lost = losses(&attack, &defend).expect("valid dice")[0];
                    outcomes[a][d][lost as usize] += 1.0 / combinations as f64;
                }
            }
        }
        let n = LIMIT + 1;
        let mut win = vec![0.0f64; n * n];
        for a in 1..=LIMIT {
            win[a * n] = 1.0;
        }
        for a in 1..=LIMIT {
            for d in 1..=LIMIT {
                let (ad, dd) = (a.min(3), d.min(2));
                let comparisons = ad.min(dd);
                let mut p = 0.0;
                for loss in 0..=comparisons {
                    p += outcomes[ad][dd][loss] * win[(a - loss) * n + (d - (comparisons - loss))];
                }
                win[a * n + d] = p;
            }
        }
        win
    })
}

/// Chance that `attackers` (excluding the mandatory source garrison) eventually conquer
/// `defenders` attacking with maximum dice. Does not consume game randomness.
pub fn win(attackers: f64, defenders: f64) -> f64 {
    if defenders <= 0.0 {
        return 1.0;
    }
    if attackers < 1.0 {
        return 0.0;
    }
    if attackers <= LIMIT as f64 && defenders <= LIMIT as f64 {
        return table()[attackers as usize * (LIMIT + 1) + defenders.ceil() as usize];
    }
    // Large battles concentrate around the three-versus-two attrition rate.
    let margin = (attackers - 0.86 * defenders) / (attackers + defenders).sqrt();
    1.0 / (1.0 + (-1.4 * margin).exp())
}
