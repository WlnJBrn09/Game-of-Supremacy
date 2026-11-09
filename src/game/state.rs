//! Versioned, platform-neutral save data. UI objects and platform types never enter a save.

use serde::{Deserialize, Serialize};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Phase {
    Setup,
    Reinforce,
    Attack,
    Occupy,
    Fortify,
    Finished,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub enum Difficulty {
    Beginner,
    Easy,
    Medium,
    Hard,
    MegaHard,
    TeraHard,
}

impl Difficulty {
    pub const ALL: [Difficulty; 6] = [
        Difficulty::Beginner,
        Difficulty::Easy,
        Difficulty::Medium,
        Difficulty::Hard,
        Difficulty::MegaHard,
        Difficulty::TeraHard,
    ];

    pub fn level(self) -> usize {
        self as usize
    }

    pub fn name(self) -> &'static str {
        match self {
            Difficulty::Beginner => "Beginner",
            Difficulty::Easy => "Easy",
            Difficulty::Medium => "Medium",
            Difficulty::Hard => "Hard",
            Difficulty::MegaHard => "MegaHard",
            Difficulty::TeraHard => "TeraHard",
        }
    }
}

pub const BOT_NAMES: [&str; 4] = ["Charles", "George", "Douglas", "Dan"];
/// Selectable player colours (hex RGB). Every player holds a different one.
pub const COLORS: [&str; 8] = [
    "D35E59", "4D9EC4", "D8B456", "81B581", "AA8EC5", "E08A4A", "4FB8A8", "DE7FA6",
];
pub const COLOR_NAMES: [&str; 8] = [
    "Red", "Blue", "Gold", "Green", "Purple", "Orange", "Teal", "Pink",
];
pub const MAX_NAME: usize = 12;
pub const PLAYERS: usize = 5;

fn no_color() -> i32 {
    -1
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Player {
    pub name: String,
    pub human: bool,
    /// Index into [`COLORS`]; -1 only while reading a save written before colours were selectable.
    #[serde(default = "no_color")]
    pub color: i32,
    pub cards: Vec<usize>,
    /// A persistent strategic objective, reconsidered when the position changes.
    pub objective: String,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Notice {
    pub message: String,
    pub created_turn: i32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum OfferStatus {
    #[default]
    Pending,
    Accepted,
    Rejected,
    Countered,
    Invalid,
}

impl OfferStatus {
    pub fn name(self) -> &'static str {
        match self {
            OfferStatus::Pending => "PENDING",
            OfferStatus::Accepted => "ACCEPTED",
            OfferStatus::Rejected => "REJECTED",
            OfferStatus::Countered => "COUNTERED",
            OfferStatus::Invalid => "INVALID",
        }
    }
}

/// A diplomatic offer. Territory and army terms refer to region indexes; -1 means unset.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Offer {
    pub id: i32,
    pub from: usize,
    pub to: usize,
    pub created_turn: i32,
    pub response_turn: i32,
    pub give_territories: Vec<usize>,
    pub want_territories: Vec<usize>,
    pub give_source: i32,
    pub give_target: i32,
    pub want_source: i32,
    pub want_target: i32,
    pub give_armies: i64,
    pub want_armies: i64,
    pub open_borders: bool,
    pub status: OfferStatus,
}

impl Default for Offer {
    fn default() -> Self {
        Offer {
            id: 0,
            from: 0,
            to: 0,
            created_turn: 0,
            response_turn: -1,
            give_territories: Vec::new(),
            want_territories: Vec::new(),
            give_source: -1,
            give_target: -1,
            want_source: -1,
            want_target: -1,
            give_armies: 0,
            want_armies: 0,
            open_borders: false,
            status: OfferStatus::Pending,
        }
    }
}

impl Offer {
    pub fn new(from: usize, to: usize) -> Offer {
        Offer {
            from,
            to,
            ..Offer::default()
        }
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GameState {
    pub version: i32,
    pub active: usize,
    pub turn: i32,
    pub winner: i32,
    pub trade_count: i32,
    pub next_offer_id: i32,
    pub phase: Phase,
    pub difficulty: Difficulty,
    pub players: Vec<Player>,
    pub territory_ids: Vec<String>,
    pub owners: Vec<usize>,
    pub armies: Vec<i64>,
    pub moved: Vec<i64>,
    pub reserve: i64,
    /// Xorshift random state; part of the save so reloading cannot reroll dice.
    pub rng: u64,
    pub conquered: bool,
    pub return_to_attack: bool,
    pub occupation_from: i32,
    pub occupation_to: i32,
    pub occupation_minimum: i64,
    pub attack_dice: Vec<i32>,
    pub defend_dice: Vec<i32>,
    pub deck: Vec<usize>,
    pub discard: Vec<usize>,
    pub borders: Vec<Vec<bool>>,
    pub offers: Vec<Offer>,
    pub history: Vec<Notice>,
    pub last_read_turn: Vec<i32>,
}

impl Default for GameState {
    fn default() -> Self {
        GameState {
            version: 1,
            active: 0,
            turn: 0,
            winner: -1,
            trade_count: 0,
            next_offer_id: 1,
            phase: Phase::Setup,
            difficulty: Difficulty::Medium,
            players: Vec::new(),
            territory_ids: Vec::new(),
            owners: Vec::new(),
            armies: Vec::new(),
            moved: Vec::new(),
            reserve: 0,
            rng: 0,
            conquered: false,
            return_to_attack: false,
            occupation_from: -1,
            occupation_to: -1,
            occupation_minimum: 0,
            attack_dice: Vec::new(),
            defend_dice: Vec::new(),
            deck: Vec::new(),
            discard: Vec::new(),
            borders: vec![vec![false; PLAYERS]; PLAYERS],
            offers: Vec::new(),
            history: Vec::new(),
            last_read_turn: vec![-1; PLAYERS],
        }
    }
}

impl GameState {
    /// Hex RGB string of a player's chosen colour.
    pub fn color(&self, player: usize) -> &'static str {
        COLORS[self.players[player].color as usize]
    }

    pub fn active_player(&self) -> &Player {
        &self.players[self.active]
    }
}
