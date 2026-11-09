//! Rules, bots and saves. Nothing here depends on rendering or the platform.

pub mod bot;
pub mod engine;
pub mod odds;
pub mod save;
pub mod state;

pub use bot::BotStrategy;
pub use engine::{assign_colors, bonus, clean_name, losses, GameEngine, RuleError};
pub use save::SaveStore;
pub use state::{Difficulty, GameState, Notice, Offer, OfferStatus, Phase, Player};
