//! Pass-and-play interaction layer, laid out like a classic conquest board game: the world map on
//! top, a command console underneath with the commanders, the current phase and the next step.
//! Every action delegates to the shared rules engine.

use super::camera::AtlasCamera;
use super::gfx::{alpha, hex, lerp, mul, Area, BIG, BODY, GAME, HEADING, SMALL, TITLE};
use super::{rgb, App, PickAction, Res, GOLD, INK, MUTED, PANEL, PAPER};
use crate::game::state::{BOT_NAMES, COLORS, COLOR_NAMES, MAX_NAME};
use crate::game::{
    assign_colors, bonus, clean_name, Difficulty, GameEngine, Offer, Phase, RuleError,
};
use macroquad::miniquad::KeyCode;
use macroquad::prelude::{get_frame_time, Color, BLACK};
use std::rc::Rc;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Modal {
    None,
    Menu,
    Handover,
    Report,
    Cards,
    Diplomacy,
    Offer,
    Compose,
    Pick,
    Settings,
    Rules,
    Player,
}

/// How a territory is emphasised on the map this frame.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Role {
    Normal,
    Dim,
    Hint,
    Option,
    Source,
    Target,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Style {
    Normal,
    Primary,
    Selected,
    Danger,
    Disabled,
}

#[derive(Clone, Debug)]
pub struct Floater {
    pub region: usize,
    pub text: String,
    pub color: Color,
    pub born: f32,
}

const CONSOLE: Color = rgb(0x0A1621);
const RAISED: Color = rgb(0x1D3748);
const RAISED_HI: Color = rgb(0x29495E);
const LINE: Color = rgb(0x2A4A5C);
const RED: Color = rgb(0xE0625A);
const GREEN: Color = rgb(0x7DC383);
const PARCHMENT: Color = rgb(0xEFE6CF);
const OFF: Color = rgb(0x152837);
const GOLD_DARK: Color = rgb(0xA88A4E);
const ALARM: Color = rgb(0x3A1B1E);
const ALARM_TEXT: Color = rgb(0xF2A39D);
const CARD_TYPES: [&str; 4] = ["INFANTRY", "CAVALRY", "ARTILLERY", "WILD"];
const SKILL_NOTES: [&str; 6] = [
    "Learning the ropes",
    "Casual",
    "Balanced",
    "Plans ahead",
    "Ruthless",
    "Relentless",
];

/// The match in progress, borrowing only the `game` field.
macro_rules! game {
    ($app:ident) => {
        $app.game.as_ref().expect("match in progress")
    };
}

impl From<RuleError> for String {
    fn from(e: RuleError) -> String {
        e.0
    }
}

fn refuse<T>(reason: impl Into<String>) -> Result<T, String> {
    Err(reason.into())
}

fn phase_name(p: Phase) -> &'static str {
    match p {
        Phase::Setup => "PLACE ARMIES",
        Phase::Reinforce => "REINFORCE",
        Phase::Attack => "ATTACK",
        Phase::Occupy => "OCCUPY",
        Phase::Fortify => "FORTIFY",
        Phase::Finished => "VICTORY",
    }
}

fn plural(n: i64, one: &str, many: &str) -> String {
    format!("{n}{}", if n == 1 { one } else { many })
}

impl App {
    fn g(&self) -> &GameEngine {
        self.game.as_ref().expect("match in progress")
    }

    fn gm(&mut self) -> &mut GameEngine {
        self.game.as_mut().expect("match in progress")
    }

    fn fr(&self) -> usize {
        self.from as usize
    }

    fn tr(&self) -> usize {
        self.to as usize
    }

    // ---------------------------------------------------------------- player setup

    pub(super) fn load_setup(&mut self) {
        let prefs = self.prefs.clone();
        self.humans = prefs.humans.clamp(1, 5);
        self.difficulty = prefs.difficulty.min(5);
        let mut used = [false; COLORS.len()];
        for i in 0..5 {
            let c = prefs.colors.get(i).copied().unwrap_or(i as i32);
            if c < 0 || c as usize >= COLORS.len() || used[c as usize] {
                return;
            }
            used[c as usize] = true;
            self.seat_color[i] = c;
        }
        for i in 0..5 {
            let saved = prefs.names.get(i).cloned().unwrap_or_default();
            if saved.is_empty() {
                continue;
            }
            // Unreadable preferences just mean default names.
            let Ok(name) = clean_name(&saved) else { return };
            self.seat_name[i] = name;
            self.named[i] = true;
        }
    }

    fn save_setup(&mut self) {
        self.prefs.humans = self.humans;
        self.prefs.difficulty = self.difficulty;
        self.prefs.names = (0..5)
            .map(|i| {
                if self.named[i] {
                    self.seat_name[i].clone()
                } else {
                    String::new()
                }
            })
            .collect();
        self.prefs.colors = self.seat_color.to_vec();
        self.store_preferences();
    }

    fn preview_colors(&self) -> [usize; 5] {
        assign_colors(self.humans, Some(&self.seat_color)).unwrap_or([0, 1, 2, 3, 4])
    }

    fn next_unnamed(&self) -> Option<usize> {
        (0..self.humans).find(|&i| !self.named[i])
    }

    /// Validates a name for a human seat against the other humans and the computer players in use.
    fn validated_name(&self, seat: usize, raw: &str) -> Result<String, String> {
        let name = clean_name(raw)?;
        let lower = name.to_lowercase();
        for i in 0..self.humans {
            if i != seat && self.named[i] && self.seat_name[i].to_lowercase() == lower {
                return refuse(format!("Another player is already called {name}."));
            }
        }
        for bot in BOT_NAMES.iter().take(5 - self.humans) {
            if bot.to_lowercase() == lower {
                return refuse(format!("{name} is a computer player's name. Pick another."));
            }
        }
        Ok(name)
    }

    fn open_editor(&mut self, seat: usize, flow: bool) {
        self.editing = seat;
        self.edit_flow = flow;
        self.edit_text = if self.named[seat] {
            self.seat_name[seat].clone()
        } else {
            String::new()
        };
        self.edit_fresh = false;
        self.shift = false;
        self.modal = Modal::Player;
    }

    fn pick_color(&mut self, seat: usize, color: i32) {
        if let Some(j) = (0..5).find(|&j| j != seat && self.seat_color[j] == color) {
            self.seat_color[j] = self.seat_color[seat]; // taking another seat's colour swaps the two
        }
        self.seat_color[seat] = color;
        self.save_setup();
    }

    fn insert(&mut self, c: char) {
        if self.edit_fresh {
            self.edit_text.clear();
        }
        self.edit_fresh = false;
        if self.edit_text.chars().count() >= MAX_NAME {
            return;
        }
        self.edit_text.push(c);
    }

    /// On-screen key: letters capitalise at the start of each word or after SHIFT.
    fn press(&mut self, c: char) {
        let mut c = c;
        if c.is_alphabetic() {
            let upper = self.shift
                || self.edit_text.is_empty()
                || self.edit_text.ends_with(' ')
                || self.edit_fresh;
            c = if upper {
                c.to_uppercase().next().unwrap_or(c)
            } else {
                c.to_lowercase().next().unwrap_or(c)
            };
            self.shift = false;
        }
        self.insert(c);
    }

    fn backspace(&mut self) {
        if self.edit_fresh {
            self.edit_text.clear();
        }
        self.edit_fresh = false;
        self.edit_text.pop();
    }

    /// Physical keyboard text, keeping the case the player typed.
    pub(super) fn typed(&mut self, c: char) -> bool {
        if self.modal != Modal::Player {
            return false;
        }
        if c.is_alphanumeric() || matches!(c, ' ' | '-' | '\'' | '.') {
            self.insert(c);
        }
        true
    }

    /// Backspace and Enter on a physical keyboard while the name editor is open.
    pub(super) fn edit_key(&mut self, key: KeyCode) -> bool {
        if self.modal != Modal::Player {
            return false;
        }
        match key {
            KeyCode::Backspace => self.backspace(),
            KeyCode::Enter | KeyCode::KpEnter => self.run_action(|a| a.finish_edit()),
            _ => {}
        }
        true
    }

    fn finish_edit(&mut self) -> Res {
        let name = self.validated_name(self.editing, &self.edit_text.clone())?;
        self.seat_name[self.editing] = name;
        self.named[self.editing] = true;
        self.save_setup();
        if self.edit_flow {
            if let Some(next) = self.next_unnamed() {
                self.open_editor(next, true);
                return Ok(());
            }
            self.modal = Modal::Menu;
            self.start_pressed()
        } else {
            self.modal = Modal::Menu;
            Ok(())
        }
    }

    fn start_pressed(&mut self) -> Res {
        if let Some(unnamed) = self.next_unnamed() {
            self.open_editor(unnamed, true);
            let message = format!("Enter a name for {} to begin.", self.seat_name[unnamed]);
            self.say(&message, false);
            return Ok(());
        }
        for i in 0..self.humans {
            self.seat_name[i] = self.validated_name(i, &self.seat_name[i].clone())?;
        }
        if (self.game.is_some() || self.saves.exists()) && !self.confirm_new {
            self.confirm_new = true;
            self.say("Starting a new game replaces your saved game.", false);
            return Ok(());
        }
        self.confirm_new = false;
        self.new_match()
    }

    fn attach(&mut self, engine: GameEngine) {
        self.game = Some(engine);
        self.bots = crate::game::BotStrategy::new();
        self.saved_revision = u64::MAX;
        self.sync_save();
        self.from = -1;
        self.to = -1;
        self.cards.clear();
        self.floaters.clear();
        self.last_active = -1;
        self.modal = Modal::None;
        self.camera.reset();
        self.check_turn();
    }

    fn new_match(&mut self) -> Res {
        self.save_setup();
        let seed = self.options.seed.unwrap_or_else(|| {
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_nanos() as u64)
                .unwrap_or(1)
        });
        let names: Vec<String> = self.seat_name[..self.humans].to_vec();
        let colors: Vec<i32> = self.seat_color[..self.humans].to_vec();
        let engine = GameEngine::with_players(
            self.board.clone(),
            self.humans,
            Difficulty::ALL[self.difficulty],
            seed,
            Some(&names),
            Some(&colors),
        )?;
        self.attach(engine);
        Ok(())
    }

    // ---------------------------------------------------------------- state helpers

    /// Runs a player command: refusals become a toast, success may hand the turn over.
    pub(super) fn run_action(&mut self, command: impl FnOnce(&mut App) -> Res) {
        match command(self) {
            Ok(()) => self.check_turn(),
            Err(e) => self.say(&e, true),
        }
        self.sync_save();
    }

    /// Saves after every rules change.
    fn sync_save(&mut self) {
        let Some(game) = &self.game else { return };
        if game.revision() == self.saved_revision {
            return;
        }
        self.saved_revision = game.revision();
        if let Err(e) = self.saves.save(game) {
            self.say(&e, true);
        }
    }

    fn say(&mut self, message: &str, error: bool) {
        self.toast = message.to_string();
        self.toast_error = error;
        self.toast_at = self.clock;
    }

    fn hotseat(&self) -> bool {
        game!(self).state.players.iter().filter(|p| p.human).count() > 1
    }

    fn check_turn(&mut self) {
        let Some(game) = &self.game else { return };
        let s = &game.state;
        if s.active as i32 == self.last_active {
            return;
        }
        self.last_active = s.active as i32;
        self.from = -1;
        self.to = -1;
        self.amount = 1;
        self.cards.clear();
        self.page = 0;
        self.shown_attack.clear();
        self.shown_defend.clear();
        self.roll_summary.clear();
        self.income = None;
        if s.phase == Phase::Reinforce && Ok(s.reserve) == game.income(s.active) {
            let lands = (game.count(s.active) as i64 / 3).max(3);
            let continents = game.continent_income(s.active);
            self.income = Some([lands, continents, s.reserve - lands - continents]);
        }
        if s.active_player().human {
            self.report = game.report(s.active);
            if self.hotseat() {
                self.modal = Modal::Handover;
            } else {
                self.show_report();
            }
        } else {
            self.modal = Modal::None;
        }
    }

    /// Shows what happened since this player's last turn, once, then marks it read.
    fn show_report(&mut self) {
        self.log_mode = false;
        self.page = 0;
        let game = game!(self);
        self.report = game.report(game.state.active);
        self.modal = if game.state.phase == Phase::Setup || self.visible_report().is_empty() {
            Modal::None
        } else {
            Modal::Report
        };
        let s = &game!(self).state;
        if s.last_read_turn[s.active] < s.turn {
            self.gm().mark_read();
        }
    }

    fn human_turn(&self) -> bool {
        self.game
            .as_ref()
            .is_some_and(|g| g.state.active_player().human && g.state.phase != Phase::Finished)
    }

    fn interactive(&self) -> bool {
        self.human_turn() && self.modal == Modal::None && self.animation <= 0.0
    }

    pub fn blocks_board(&self) -> bool {
        self.modal != Modal::None || self.game.is_none()
    }

    /// The region whose sea lanes the map highlights.
    pub fn focus(&self) -> i32 {
        let Some(game) = &self.game else { return -1 };
        if game.state.phase == Phase::Occupy {
            return game.state.occupation_to;
        }
        if self.human_turn() {
            self.from
        } else {
            -1
        }
    }

    pub fn arrow_from(&self) -> i32 {
        if !self.human_turn() {
            return -1;
        }
        let s = &game!(self).state;
        if s.phase == Phase::Occupy {
            s.occupation_from
        } else if self.to >= 0 {
            self.from
        } else {
            -1
        }
    }

    pub fn arrow_to(&self) -> i32 {
        if !self.human_turn() {
            return -1;
        }
        let s = &game!(self).state;
        if s.phase == Phase::Occupy {
            s.occupation_to
        } else {
            self.to
        }
    }

    /// Deployment only fades enemy land a little; targeting fades everything irrelevant.
    pub fn dim_strength(&self) -> f32 {
        match &self.game {
            Some(g) if matches!(g.state.phase, Phase::Setup | Phase::Reinforce) => 0.32,
            _ => 0.5,
        }
    }

    pub fn action_color(&self) -> Color {
        match &self.game {
            Some(g) if matches!(g.state.phase, Phase::Attack | Phase::Occupy) => RED,
            _ => GOLD,
        }
    }

    fn can_attack_from(&self, r: usize) -> bool {
        let g = game!(self);
        let s = &g.state;
        s.owners[r] == s.active
            && s.armies[r] >= 2
            && g.neighbours(r).iter().any(|&n| s.owners[n] != s.active)
    }

    pub fn role(&self, r: usize) -> Role {
        if !self.human_turn() || self.modal != Modal::None {
            return Role::Normal;
        }
        let g = game!(self);
        let s = &g.state;
        let own = s.owners[r] == s.active;
        let ri = r as i32;
        match s.phase {
            Phase::Setup | Phase::Reinforce => {
                if ri == self.from {
                    Role::Source
                } else if !own {
                    Role::Dim
                } else if s.reserve > 0 && self.from < 0 {
                    Role::Hint
                } else {
                    Role::Normal
                }
            }
            Phase::Attack => {
                if self.from < 0 {
                    if self.can_attack_from(r) {
                        Role::Hint
                    } else {
                        Role::Normal
                    }
                } else if ri == self.from {
                    Role::Source
                } else if ri == self.to {
                    Role::Target
                } else if g.can_attack(self.fr(), r) {
                    Role::Option
                } else {
                    Role::Dim
                }
            }
            Phase::Occupy => {
                if ri == s.occupation_from {
                    Role::Source
                } else if ri == s.occupation_to {
                    Role::Target
                } else {
                    Role::Dim
                }
            }
            Phase::Fortify => {
                if self.from < 0 {
                    if own && g.movable(r) > 0 {
                        Role::Hint
                    } else {
                        Role::Normal
                    }
                } else if ri == self.from {
                    Role::Source
                } else if ri == self.to {
                    Role::Target
                } else if own && g.connected(self.fr(), r, s.active) {
                    Role::Option
                } else {
                    Role::Dim
                }
            }
            Phase::Finished => Role::Normal,
        }
    }

    // ---------------------------------------------------------------- map input

    pub(super) fn tap(&mut self, r: usize) {
        if self.interactive() {
            self.run_action(|a| a.choose(r));
        }
    }

    pub(super) fn tap_ocean(&mut self) {
        if self.interactive() && game!(self).state.phase != Phase::Occupy {
            self.from = -1;
            self.to = -1;
        }
    }

    fn choose(&mut self, r: usize) -> Res {
        let g = game!(self);
        let s = &g.state;
        let own = s.owners[r] == s.active;
        let owner = &s.players[s.owners[r]].name;
        let name = g.name(r).to_string();
        let ri = r as i32;
        match s.phase {
            Phase::Setup | Phase::Reinforce => {
                if !own {
                    return refuse(format!(
                        "{name} belongs to {owner}. Place armies on your own land."
                    ));
                }
                if s.reserve == 0 {
                    return refuse(if s.phase == Phase::Setup {
                        "All armies placed. Tap FINISH PLACEMENT."
                    } else {
                        "No armies left to place. Trade cards or start attacking."
                    });
                }
                let reserve = s.reserve;
                self.from = if self.from == ri { -1 } else { ri };
                self.to = -1;
                self.amount = reserve;
            }
            Phase::Attack => {
                if own {
                    if ri == self.from {
                        self.from = -1;
                        self.to = -1;
                        return Ok(());
                    }
                    if s.armies[r] < 2 {
                        return refuse(format!("{name} needs at least 2 armies to attack."));
                    }
                    if !self.can_attack_from(r) {
                        return refuse(format!("{name} has no enemy neighbours."));
                    }
                    self.from = ri;
                    self.to = -1;
                } else if self.from >= 0 && g.can_attack(self.fr(), r) {
                    self.to = if self.to == ri { -1 } else { ri };
                } else {
                    // Tapping an enemy first picks your strongest bordering territory for you.
                    let mut best: Option<usize> = None;
                    for &n in g.neighbours(r) {
                        if s.owners[n] == s.active
                            && s.armies[n] > 1
                            && best.is_none_or(|b| s.armies[n] > s.armies[b])
                        {
                            best = Some(n);
                        }
                    }
                    let Some(best) = best else {
                        return refuse(format!(
                            "None of your territories with 2+ armies borders {name}."
                        ));
                    };
                    self.from = best as i32;
                    self.to = ri;
                }
                let s = &game!(self).state;
                self.dice = 3.min(s.armies[self.fr()] - 1);
            }
            Phase::Occupy => {
                return refuse(format!(
                    "Choose how many armies move into {}.",
                    g.name(s.occupation_to as usize)
                ));
            }
            Phase::Fortify => {
                if !own {
                    return refuse("You can only move armies between your own territories.");
                }
                if ri == self.from {
                    self.from = -1;
                    self.to = -1;
                } else if self.from >= 0 && g.connected(self.fr(), r, s.active) {
                    self.to = ri;
                    self.amount = g.movable(self.fr());
                } else {
                    if g.movable(r) <= 0 {
                        return refuse(if s.armies[r] < 2 {
                            format!("{name} has only one army; it must stay.")
                        } else {
                            format!("Armies in {name} already moved this turn.")
                        });
                    }
                    self.from = ri;
                    self.to = -1;
                }
            }
            Phase::Finished => {}
        }
        Ok(())
    }

    pub(super) fn key(&mut self, key: KeyCode) -> bool {
        if !self.interactive() {
            return false;
        }
        let phase = game!(self).state.phase;
        let reserve = game!(self).state.reserve;
        match key {
            KeyCode::Enter | KeyCode::KpEnter | KeyCode::Space => {
                if matches!(phase, Phase::Setup | Phase::Reinforce) && self.from >= 0 && reserve > 0
                {
                    self.run_action(|a| a.deploy());
                } else if phase == Phase::Fortify && self.from >= 0 && self.to >= 0 {
                    self.run_action(|a| a.fortify());
                } else {
                    self.run_action(|a| a.primary());
                }
                true
            }
            KeyCode::B => {
                if phase == Phase::Attack && self.from >= 0 && self.to >= 0 {
                    self.run_action(|a| a.roll(true));
                }
                true
            }
            KeyCode::R => {
                if phase == Phase::Attack && self.from >= 0 && self.to >= 0 {
                    self.run_action(|a| a.roll(false));
                }
                self.to >= 0
            }
            _ => false,
        }
    }

    // ---------------------------------------------------------------- commands

    fn pop(&mut self, region: usize, text: String, color: Color) {
        self.floaters.push(Floater {
            region,
            text,
            color,
            born: self.clock,
        });
    }

    fn deploy(&mut self) -> Res {
        if self.from < 0 {
            return refuse("Tap one of your territories first.");
        }
        let from = self.fr();
        let n = self.amount.min(game!(self).state.reserve).max(1);
        self.gm().deploy(from, n)?;
        self.pop(from, format!("+{n}"), GOLD);
        let g = game!(self);
        let s = &g.state;
        if s.reserve == 0 {
            self.from = -1;
            if s.phase == Phase::Reinforce
                && g.find_set(s.active).is_empty()
                && s.active_player().cards.len() < 5
            {
                self.gm().finish_deployment()?;
                self.say("All armies placed. Attack!", false);
            }
        } else {
            self.amount = s.reserve;
        }
        Ok(())
    }

    fn roll(&mut self, blitz: bool) -> Res {
        if self.from < 0 || self.to < 0 {
            return refuse("Pick your territory, then an enemy neighbour.");
        }
        let (f, t) = (self.fr(), self.tr());
        let (a0, d0) = (game!(self).state.armies[f], game!(self).state.armies[t]);
        let target = game!(self).name(t).to_string();
        let dice = self.dice;
        let mut rolls = 0;
        loop {
            let s = &game!(self).state;
            let n = (if blitz { 3 } else { dice }).min(s.armies[f] - 1);
            self.gm().attack(f, t, n.max(0) as usize)?;
            rolls += 1;
            let s = &game!(self).state;
            if !(blitz && s.phase == Phase::Attack && s.armies[f] > 1) {
                break;
            }
        }
        let s = &game!(self).state;
        self.shown_attack = s.attack_dice.clone();
        self.shown_defend = s.defend_dice.clone();
        let (lost_a, lost_d) = (a0 - s.armies[f], d0 - s.armies[t]);
        let captured =
            matches!(s.phase, Phase::Occupy | Phase::Finished) || s.owners[t] == s.active;
        let (phase, armies_f) = (s.phase, s.armies[f]);
        if lost_a > 0 {
            self.pop(f, format!("-{lost_a}"), RED);
        }
        if lost_d > 0 {
            self.pop(t, format!("-{lost_d}"), RED);
        }
        self.animation = 0.5;
        self.play(true, 0.45);
        self.roll_summary = format!(
            "{}You lost {lost_a} · They lost {lost_d}",
            if blitz {
                format!("Blitz · {}", plural(rolls, " roll · ", " rolls · "))
            } else {
                String::new()
            }
        );
        if captured {
            self.amount = if phase == Phase::Occupy {
                armies_f - 1
            } else {
                0
            };
            self.play(false, 0.5);
            if phase != Phase::Finished {
                self.say(&format!("{target} captured!"), false);
            }
        } else if armies_f < 2 {
            let message = format!("{} has no armies left to attack with.", game!(self).name(f));
            self.say(&message, false);
            self.from = -1;
            self.to = -1;
        } else {
            self.dice = self.dice.min(armies_f - 1);
        }
        Ok(())
    }

    fn occupy(&mut self) -> Res {
        let s = &game!(self).state;
        let (f, t) = (s.occupation_from as usize, s.occupation_to as usize);
        let n = s.occupation_minimum.max(self.amount.min(s.armies[f] - 1));
        self.gm().occupy(n)?;
        self.pop(t, format!("+{n}"), GOLD);
        self.to = -1;
        // Keep the push going from the newly captured territory when it can attack again.
        if game!(self).state.phase == Phase::Attack {
            self.from = if self.can_attack_from(t) {
                t as i32
            } else if self.can_attack_from(f) {
                f as i32
            } else {
                -1
            };
            if self.from >= 0 {
                self.dice = 3.min(game!(self).state.armies[self.fr()] - 1);
            }
        } else {
            self.from = -1;
        }
        Ok(())
    }

    fn fortify(&mut self) -> Res {
        if self.from < 0 || self.to < 0 {
            return refuse("Pick a territory to move from, then a destination.");
        }
        let (f, t) = (self.fr(), self.tr());
        let n = self.amount.min(game!(self).movable(f)).max(1);
        self.gm().fortify(f, t, n)?;
        self.pop(f, format!("-{n}"), MUTED);
        self.pop(t, format!("+{n}"), GOLD);
        let message = format!(
            "Moved {n} from {} to {}.",
            game!(self).name(f),
            game!(self).name(t)
        );
        self.say(&message, false);
        self.from = -1;
        self.to = -1;
        Ok(())
    }

    fn trade(&mut self) -> Res {
        let g = game!(self);
        let set = if self.cards.len() == 3 {
            self.cards.clone()
        } else {
            g.find_set(g.state.active)
        };
        if set.is_empty() {
            return refuse("You do not have a matching set yet.");
        }
        let before = g.state.reserve;
        let armies = g.state.armies.clone();
        self.gm().trade_cards(&set)?;
        self.cards.clear();
        let mut bonus = String::new();
        for (i, &old) in armies.iter().enumerate() {
            let now = game!(self).state.armies[i];
            if now > old {
                self.pop(i, format!("+{}", now - old), GOLD);
                bonus = format!(" (+2 on {})", game!(self).name(i));
            }
        }
        let reserve = game!(self).state.reserve;
        self.say(
            &format!("Cards traded for {} armies{bonus}.", reserve - before),
            false,
        );
        self.amount = reserve;
        Ok(())
    }

    fn primary(&mut self) -> Res {
        let s = &game!(self).state;
        match s.phase {
            Phase::Setup => {
                if s.reserve > 0 {
                    return refuse(format!("Place all {} remaining armies first.", s.reserve));
                }
                self.from = -1;
                self.gm().finish_deployment()?;
            }
            Phase::Reinforce => {
                if s.active_player().cards.len() >= 5 {
                    return refuse("You hold five or more cards. Trade a set first.");
                }
                if s.reserve > 0 {
                    return refuse(format!("Place all {} remaining armies first.", s.reserve));
                }
                self.from = -1;
                self.gm().finish_deployment()?;
            }
            Phase::Attack => {
                self.from = -1;
                self.to = -1;
                self.gm().finish_attacks()?;
            }
            Phase::Occupy => self.occupy()?,
            Phase::Fortify => {
                self.from = -1;
                self.to = -1;
                self.gm().end_turn()?;
            }
            Phase::Finished => {}
        }
        Ok(())
    }

    // ---------------------------------------------------------------- frame

    /// Advances clocks and computer turns, then settles selections the panels depend on.
    pub(super) fn hud_update(&mut self) {
        let delta = get_frame_time().min(0.1);
        self.clock += delta;
        if self.game.is_some() {
            self.check_turn();
            if !game!(self).state.active_player().human
                && game!(self).state.phase != Phase::Finished
                && self.modal == Modal::None
            {
                let mut failure = None;
                for _ in 0..12 {
                    let s = &game!(self).state;
                    if s.active_player().human || s.phase == Phase::Finished {
                        break;
                    }
                    let mut bots = std::mem::take(&mut self.bots);
                    let result = bots.step(self.gm());
                    self.bots = bots;
                    if let Err(e) = result {
                        failure = Some(e.0);
                        break;
                    }
                    self.sync_save();
                    if !self.saves.warning().is_empty() {
                        failure = Some(self.saves.warning().to_string());
                        break;
                    }
                }
                match failure {
                    Some(e) => {
                        self.say(&format!("Bot paused: {e}"), true);
                        self.modal = Modal::Settings;
                    }
                    None => self.check_turn(),
                }
            }
            if self.animation > 0.0 {
                self.animation = (self.animation - delta).max(0.0);
            }
            let clock = self.clock;
            self.floaters.retain(|f| clock - f.born <= 1.4);
            self.normalize();
        }
        self.sync_save();
    }

    /// Clears selections that no longer apply and keeps amounts inside their sliders' ranges.
    fn normalize(&mut self) {
        if self.modal == Modal::Diplomacy {
            let g = game!(self);
            if self.recipient == g.state.active || !g.alive(self.recipient) {
                self.cycle_recipient();
            }
        }
        if !self.human_turn() {
            return;
        }
        let g = game!(self);
        let s = &g.state;
        match s.phase {
            Phase::Setup | Phase::Reinforce => {
                if s.reserve > 0 {
                    if self.from < 0 || s.owners[self.fr()] != s.active {
                        self.from = -1;
                    } else {
                        self.amount = self.amount.min(s.reserve).max(1);
                    }
                }
            }
            Phase::Attack => {
                if self.from >= 0 && (s.owners[self.fr()] != s.active || s.armies[self.fr()] < 2) {
                    self.from = -1;
                    self.to = -1;
                }
                if self.to >= 0 && (self.from < 0 || !g.can_attack(self.fr(), self.tr())) {
                    self.to = -1;
                }
                if self.from >= 0 && self.to >= 0 {
                    let max_dice = 3.min(s.armies[self.fr()] - 1);
                    self.dice = self.dice.min(max_dice).max(1);
                }
            }
            Phase::Occupy => {
                let max = s.armies[s.occupation_from as usize] - 1;
                self.amount = self.amount.min(max).max(s.occupation_minimum);
            }
            Phase::Fortify => {
                if self.from >= 0 && (s.owners[self.fr()] != s.active || g.movable(self.fr()) <= 0)
                {
                    self.from = -1;
                    self.to = -1;
                }
                if self.from >= 0 && self.to >= 0 {
                    self.amount = self.amount.min(g.movable(self.fr())).max(1);
                }
            }
            Phase::Finished => {}
        }
    }

    pub(super) fn hud_render(&self) {
        self.map_overlay();
        self.top_bar();
        self.console();
        if self.modal != Modal::None {
            self.clear_hits();
            self.draw_modal();
        }
        self.draw_toast();
    }

    // ---------------------------------------------------------------- drawing primitives

    fn txt(&self, s: &str, x: f32, y: f32, c: Color) {
        self.gfx.text(GAME, s, x, y, c);
    }

    fn player_color(&self, p: usize) -> Color {
        hex(game!(self).state.color(p))
    }

    fn token(&self, x: f32, y: f32, r: f32, c: Color) {
        let g = &self.gfx;
        g.circle(x, y, r + 1.5, 20, INK);
        g.circle(x, y, r, 20, mul(c, 0.68));
        g.circle(x, y, r * 0.83, 20, lerp(c, PAPER, 0.1));
    }

    fn btn(&self, label: &str, x: f32, y: f32, w: f32, run: impl Fn(&mut App) -> Res + 'static) {
        self.button(label, x, y, w, 52.0, Style::Normal, run);
    }

    fn btn_sel(
        &self,
        label: &str,
        x: f32,
        y: f32,
        w: f32,
        active: bool,
        run: impl Fn(&mut App) -> Res + 'static,
    ) {
        self.button(
            label,
            x,
            y,
            w,
            52.0,
            if active {
                Style::Selected
            } else {
                Style::Normal
            },
            run,
        );
    }

    #[allow(clippy::too_many_arguments)]
    fn button(
        &self,
        label: &str,
        x: f32,
        y: f32,
        w: f32,
        h: f32,
        style: Style,
        run: impl Fn(&mut App) -> Res + 'static,
    ) {
        let g = &self.gfx;
        let bg = match style {
            Style::Primary => GOLD,
            Style::Selected => RAISED_HI,
            Style::Danger => RED,
            Style::Disabled => OFF,
            Style::Normal => RAISED,
        };
        let fg = match style {
            Style::Primary => INK,
            Style::Disabled => MUTED,
            Style::Selected => GOLD,
            _ => PAPER,
        };
        match style {
            Style::Selected => g.round(x - 2.0, y - 2.0, w + 4.0, h + 4.0, 10.0, GOLD),
            Style::Primary => g.round(x, y - 3.0, w, h, 9.0, GOLD_DARK),
            Style::Danger => g.round(x, y - 3.0, w, h, 9.0, mul(RED, 0.7)),
            Style::Normal => g.round(x, y - 2.0, w, h, 9.0, INK),
            Style::Disabled => {}
        }
        g.round(x, y, w, h, 8.0, bg);
        self.add_hit(Area::new(x, y, w, h), run);
        if label == "−" {
            g.rect(x + w / 2.0 - 9.0, y + h / 2.0 - 1.5, 18.0, 3.0, fg);
            return;
        }
        let scale = ((w - 20.0) / g.width(GAME, label).max(1.0)).min(1.0);
        let style = GAME.scaled(scale);
        g.centered(
            style,
            label,
            x + w / 2.0,
            y + h / 2.0 + style.cap_height() / 2.0,
            fg,
        );
    }

    fn die(&self, x: f32, y: f32, size: f32, n: i32, face: Color, pip: Color) {
        let g = &self.gfx;
        g.round(x + 1.0, y - 2.0, size, size, size * 0.18, alpha(BLACK, 0.4));
        g.round(x, y, size, size, size * 0.18, face);
        let (c, o, pr) = (size / 2.0, size * 0.27, size * 0.085);
        let spots: &[(f32, f32)] = match n {
            1 => &[(0.0, 0.0)],
            2 => &[(-1.0, -1.0), (1.0, 1.0)],
            3 => &[(-1.0, -1.0), (0.0, 0.0), (1.0, 1.0)],
            4 => &[(-1.0, -1.0), (1.0, 1.0), (-1.0, 1.0), (1.0, -1.0)],
            5 => &[
                (-1.0, -1.0),
                (1.0, 1.0),
                (-1.0, 1.0),
                (1.0, -1.0),
                (0.0, 0.0),
            ],
            _ => &[
                (-1.0, -1.0),
                (1.0, 1.0),
                (-1.0, 1.0),
                (1.0, -1.0),
                (-1.0, 0.0),
                (1.0, 0.0),
            ],
        };
        for (px, py) in spots {
            g.circle(x + c + px * o, y + c + py * o, pr, 12, pip);
        }
    }

    /// A horizontal amount slider with − / + steppers on either side.
    fn amount_control(&self, x: f32, y: f32, w: f32, min: i64, max: i64) {
        let g = &self.gfx;
        let amount = self.amount.min(max).max(min);
        self.button("−", x, y, 52.0, 52.0, Style::Normal, move |a| {
            a.amount = min.max(a.amount - 1);
            Ok(())
        });
        self.button("+", x + w - 52.0, y, 52.0, 52.0, Style::Normal, move |a| {
            a.amount = max.min(a.amount + 1);
            Ok(())
        });
        let (sx, sw, cy) = (x + 76.0, w - 152.0, y + 26.0);
        let f = if max <= min {
            1.0
        } else {
            (amount - min) as f32 / (max - min) as f32
        };
        g.round(sx, cy - 4.0, sw, 8.0, 4.0, INK);
        g.round(sx, cy - 4.0, (sw * f).max(8.0), 8.0, 4.0, GOLD);
        g.circle(sx + sw * f, cy, 16.0, 24, INK);
        g.circle(sx + sw * f, cy, 13.0, 24, GOLD);
        g.text(SMALL, &min.to_string(), sx, y - 2.0, MUTED);
        g.right(SMALL, &max.to_string(), sx + sw, y - 2.0, MUTED);
        if max > min {
            self.add_slider(Area::new(sx - 16.0, y, sw + 32.0, 52.0), move |a, v| {
                a.amount = min + (v * (max - min) as f32).round() as i64;
            });
        }
    }

    // ---------------------------------------------------------------- top bar

    fn top_bar(&self) {
        let g = &self.gfx;
        g.rect(0.0, 944.0, 1600.0, 56.0, CONSOLE);
        g.rect(0.0, 944.0, 1600.0, 2.0, LINE);
        g.text(HEADING, "SUPREMACY", 20.0, 984.0, PAPER);
        if let Some(game) = &self.game {
            let s = &game.state;
            g.text(
                BODY,
                &format!("TURN {}", s.turn.max(1)),
                196.0,
                978.0,
                MUTED,
            );
            if s.phase != Phase::Finished {
                self.token(300.0, 972.0, 9.0, self.player_color(s.active));
                self.txt(
                    &format!("{}  ·  {}", s.active_player().name, phase_name(s.phase)),
                    318.0,
                    982.0,
                    PAPER,
                );
            }
        }
        let human = self.human_turn();
        let hand = if human {
            game!(self).state.active_player().cards.len()
        } else {
            0
        };
        let set = human && !game!(self).find_set(game!(self).state.active).is_empty();
        self.button(
            &if human {
                format!("CARDS  {hand}")
            } else {
                "CARDS".into()
            },
            980.0,
            952.0,
            150.0,
            40.0,
            if set { Style::Selected } else { Style::Normal },
            |a| {
                a.open(Modal::Cards);
                Ok(())
            },
        );
        let inbox = if human { game!(self).inbox().len() } else { 0 };
        self.button(
            &if inbox > 0 {
                format!("DIPLOMACY  {inbox}")
            } else {
                "DIPLOMACY".into()
            },
            1140.0,
            952.0,
            190.0,
            40.0,
            if inbox > 0 {
                Style::Selected
            } else {
                Style::Normal
            },
            |a| {
                a.open(Modal::Diplomacy);
                Ok(())
            },
        );
        self.button("LOG", 1340.0, 952.0, 110.0, 40.0, Style::Normal, |a| {
            let Some(game) = &a.game else { return Ok(()) };
            a.report = game
                .state
                .history
                .iter()
                .rev()
                .take(54)
                .map(|n| n.message.clone())
                .collect();
            a.open(Modal::Report);
            if a.modal == Modal::Report {
                a.log_mode = true;
            }
            Ok(())
        });
        self.button("MENU", 1460.0, 952.0, 128.0, 40.0, Style::Normal, |a| {
            a.open(Modal::Settings);
            Ok(())
        });
    }

    // ---------------------------------------------------------------- map overlays

    fn map_overlay(&self) {
        let y0 = AtlasCamera::Y;
        self.button("+", 1540.0, y0 + 116.0, 48.0, 48.0, Style::Normal, |a| {
            a.camera.zoom_at(1.35, 800.0, 606.0);
            Ok(())
        });
        self.button("−", 1540.0, y0 + 64.0, 48.0, 48.0, Style::Normal, |a| {
            a.camera.zoom_at(0.74, 800.0, 606.0);
            Ok(())
        });
        self.button("FIT", 1540.0, y0 + 12.0, 48.0, 48.0, Style::Normal, |a| {
            a.camera.reset();
            Ok(())
        });
        let Some(game) = &self.game else {
            return;
        };
        self.legend();
        if game.state.phase == Phase::Finished {
            self.victory_banner();
        } else if !game.state.active_player().human {
            self.banner(&format!(
                "{} is taking their turn…",
                game.state.active_player().name
            ));
        }
        self.tooltip();
    }

    fn banner(&self, message: &str) {
        let g = &self.gfx;
        let w = g.width(GAME, message) + 48.0;
        g.round(800.0 - w / 2.0, 884.0, w, 46.0, 23.0, alpha(INK, 0.9));
        g.centered(GAME, message, 800.0, 915.0, PAPER);
    }

    fn victory_banner(&self) {
        let g = &self.gfx;
        let s = &game!(self).state;
        let winner = s.winner as usize;
        let y = 500.0;
        g.round(450.0, y, 700.0, 190.0, 18.0, INK);
        g.round(
            450.0,
            y + 178.0,
            700.0,
            12.0,
            6.0,
            self.player_color(winner),
        );
        g.centered(TITLE, "WORLD CONQUERED", 800.0, y + 152.0, GOLD);
        g.centered(
            GAME,
            &format!(
                "{} rules all {} territories.",
                s.players[winner].name,
                s.owners.len()
            ),
            800.0,
            y + 96.0,
            PAPER,
        );
        self.button(
            "NEW GAME",
            660.0,
            y + 18.0,
            280.0,
            52.0,
            Style::Primary,
            |a| {
                a.modal = Modal::Menu;
                Ok(())
            },
        );
    }

    fn legend(&self) {
        let g = &self.gfx;
        let (x, y0, w) = (12.0, AtlasCamera::Y + 12.0, 262.0);
        let list = &self.board.continents;
        let h = if self.legend_open {
            42.0 + list.len() as f32 * 25.0
        } else {
            36.0
        };
        g.round(x, y0, w, h, 10.0, alpha(INK, 0.86));
        g.text(SMALL, "CONTINENT BONUSES", x + 14.0, y0 + h - 12.0, GOLD);
        g.text(
            SMALL,
            if self.legend_open { "HIDE" } else { "SHOW" },
            x + w - 46.0,
            y0 + h - 12.0,
            MUTED,
        );
        self.add_hit(Area::new(x, y0 + h - 36.0, w, 36.0), |a| {
            a.legend_open = !a.legend_open;
            Ok(())
        });
        if !self.legend_open {
            return;
        }
        let s = &game!(self).state;
        let viewer = s.active;
        for (i, c) in list.iter().enumerate() {
            let y = y0 + h - 44.0 - i as f32 * 25.0;
            let (mut total, mut mine, mut holder) = (0, 0, -2i32);
            for r in 0..s.owners.len() {
                if self.board.regions[r].continent == i {
                    total += 1;
                    if s.owners[r] == viewer {
                        mine += 1;
                    }
                    let o = s.owners[r] as i32;
                    holder = if holder == -2 || holder == o { o } else { -1 };
                }
            }
            g.rect(x + 14.0, y - 12.0, 12.0, 12.0, hex(&c.color));
            g.text(BODY, &c.name, x + 34.0, y, PAPER);
            g.right(BODY, &format!("+{}", bonus(&c.name)), x + w - 58.0, y, GOLD);
            if holder >= 0 {
                self.token(
                    x + w - 22.0,
                    y - 6.0,
                    7.0,
                    self.player_color(holder as usize),
                );
            } else if self.human_turn() {
                g.right(
                    SMALL,
                    &format!("{mine}/{total}"),
                    x + w - 10.0,
                    y - 1.0,
                    MUTED,
                );
            }
        }
    }

    fn tooltip(&self) {
        let Some(r) = self.hovered else { return };
        if self.modal != Modal::None {
            return;
        }
        let g = &self.gfx;
        let s = &game!(self).state;
        let region = &self.board.regions[r];
        let continent = &self.board.continents[region.continent].name;
        let line1 = region.name.clone();
        let line2 = format!(
            "{} · {} {}",
            s.players[s.owners[r]].name,
            s.armies[r],
            if s.armies[r] == 1 { "army" } else { "armies" }
        );
        let line3 = format!("{continent} (+{})", bonus(continent));
        let w = g
            .width(BODY, &line1)
            .max(g.width(BODY, &line2))
            .max(g.width(SMALL, &line3))
            + 28.0;
        let (mx, my) = self.mouse;
        let x = (mx + 18.0).min(1590.0 - w);
        let y = (my - 18.0).max(AtlasCamera::Y + 84.0);
        let owner = self.player_color(s.owners[r]);
        g.round(x, y - 78.0, w, 78.0, 8.0, alpha(INK, 0.94));
        g.rect(x, y - 78.0, 4.0, 78.0, owner);
        g.text(BODY, &line1, x + 14.0, y - 10.0, PAPER);
        g.text(BODY, &line2, x + 14.0, y - 34.0, owner);
        g.text(SMALL, &line3, x + 14.0, y - 58.0, MUTED);
    }

    fn draw_toast(&self) {
        let age = self.clock - self.toast_at;
        if self.toast.is_empty() || age > 3.5 {
            return;
        }
        let g = &self.gfx;
        let a = (3.5 - age).clamp(0.0, 1.0);
        let measured = g.width(GAME, &self.toast);
        let w = 1200f32.min(measured + 56.0);
        let y = if self.modal == Modal::None {
            818.0
        } else {
            40.0
        };
        let error = self.toast_error;
        g.round(
            800.0 - w / 2.0,
            y,
            w,
            50.0,
            25.0,
            alpha(if error { ALARM } else { INK }, 0.95 * a),
        );
        g.round(
            800.0 - w / 2.0,
            y,
            8.0,
            50.0,
            4.0,
            alpha(if error { RED } else { GOLD }, a),
        );
        let style = GAME.scaled((1140.0 / measured.max(1.0)).min(1.0));
        g.centered(
            style,
            &self.toast,
            800.0,
            y + 33.0,
            alpha(if error { ALARM_TEXT } else { PAPER }, a),
        );
    }

    // ---------------------------------------------------------------- console

    fn console(&self) {
        let g = &self.gfx;
        g.rect(0.0, 0.0, 1600.0, AtlasCamera::Y, CONSOLE);
        g.rect(0.0, AtlasCamera::Y - 2.0, 1600.0, 2.0, LINE);
        g.round(12.0, 12.0, 420.0, 244.0, 12.0, PANEL);
        g.round(444.0, 12.0, 752.0, 244.0, 12.0, PANEL);
        g.round(1208.0, 12.0, 380.0, 244.0, 12.0, PANEL);
        if self.game.is_none() {
            g.centered(
                GAME,
                "Open the menu to start or resume a game.",
                820.0,
                140.0,
                MUTED,
            );
            return;
        }
        self.roster();
        self.action_panel();
        self.command_panel();
    }

    fn roster(&self) {
        let g = &self.gfx;
        let game = game!(self);
        let s = &game.state;
        g.text(SMALL, "COMMANDERS", 28.0, 246.0, MUTED);
        g.right(SMALL, "LANDS", 288.0, 246.0, MUTED);
        g.right(SMALL, "ARMIES", 360.0, 246.0, MUTED);
        g.right(SMALL, "CARDS", 418.0, 246.0, MUTED);
        for p in 0..5 {
            let y = 186.0 - p as f32 * 42.0;
            let alive = game.alive(p);
            if p == s.active && s.phase != Phase::Finished {
                g.round(20.0, y, 404.0, 40.0, 8.0, RAISED_HI);
                g.round(20.0, y, 6.0, 40.0, 3.0, self.player_color(p));
            }
            self.token(
                46.0,
                y + 20.0,
                10.0,
                if alive {
                    self.player_color(p)
                } else {
                    alpha(MUTED, 0.5)
                },
            );
            let pl = &s.players[p];
            g.fit_text(
                GAME,
                &pl.name,
                66.0,
                y + 30.0,
                132.0,
                if alive { PAPER } else { MUTED },
            );
            let tag_x = 66.0 + 132f32.min(g.width(GAME, &pl.name)) + 8.0;
            g.text(
                SMALL,
                if pl.human { "HUMAN" } else { "CPU" },
                tag_x,
                y + 26.0,
                MUTED,
            );
            if !alive {
                g.right(BODY, "ELIMINATED", 418.0, y + 28.0, MUTED);
                continue;
            }
            g.right(BODY, &game.count(p).to_string(), 288.0, y + 28.0, PAPER);
            g.right(BODY, &game.total(p).to_string(), 360.0, y + 28.0, PAPER);
            g.right(BODY, &pl.cards.len().to_string(), 418.0, y + 28.0, PAPER);
            self.add_hit(Area::new(20.0, y, 404.0, 40.0), move |a| {
                if a.interactive() && p != a.g().state.active && a.g().alive(p) {
                    a.recipient = p;
                    a.open(Modal::Diplomacy);
                }
                Ok(())
            });
        }
    }

    fn stepper(&self) {
        let g = &self.gfx;
        let s = &game!(self).state;
        if s.phase == Phase::Setup {
            g.round(460.0, 214.0, 720.0, 34.0, 17.0, GOLD);
            g.centered(
                BODY,
                "INITIAL PLACEMENT  ·  EVERY COMMANDER PLACES 55 ARMIES",
                820.0,
                237.0,
                INK,
            );
            return;
        }
        let steps = ["1  REINFORCE", "2  ATTACK", "3  FORTIFY"];
        let current = match s.phase {
            Phase::Reinforce => 0,
            Phase::Attack | Phase::Occupy => 1,
            Phase::Fortify => 2,
            _ => 3,
        };
        for (i, step) in steps.iter().enumerate() {
            let x = 460.0 + i as f32 * 244.0;
            let fill = if i == current {
                GOLD
            } else if i < current {
                RAISED
            } else {
                OFF
            };
            let ink = if i == current {
                INK
            } else if i < current {
                MUTED
            } else {
                alpha(MUTED, 0.7)
            };
            g.round(x, 214.0, 232.0, 34.0, 17.0, fill);
            g.centered(BODY, step, x + 116.0, 237.0, ink);
            if i < 2 {
                g.triangle(
                    x + 234.0,
                    224.0,
                    x + 234.0,
                    238.0,
                    x + 242.0,
                    231.0,
                    if i < current { GOLD } else { LINE },
                );
            }
        }
    }

    fn action_panel(&self) {
        let s = &game!(self).state;
        self.stepper();
        let (x, w) = (464.0, 712.0);
        if s.phase == Phase::Finished {
            let winner = &s.players[s.winner as usize].name;
            self.gfx.wrap(
                GAME,
                &format!("{winner} conquered the world!"),
                x,
                192.0,
                w,
                GOLD,
            );
            self.button(
                "START A NEW GAME",
                x,
                40.0,
                300.0,
                56.0,
                Style::Primary,
                |a| {
                    a.modal = Modal::Menu;
                    Ok(())
                },
            );
            return;
        }
        if !s.active_player().human {
            self.txt(
                &format!("{} is taking their turn…", s.active_player().name),
                x,
                192.0,
                PAPER,
            );
            return;
        }
        match s.phase {
            Phase::Setup | Phase::Reinforce => self.deploy_panel(x, w),
            Phase::Attack => self.attack_panel(x, w),
            Phase::Occupy => self.occupy_panel(x),
            Phase::Fortify => self.fortify_panel(x, w),
            Phase::Finished => {}
        }
    }

    fn instruction(&self, message: &str, c: Color) {
        self.gfx.fit_text(GAME, message, 464.0, 192.0, 712.0, c);
    }

    /// A small right-pointing arrow aligned with a line of game text whose top is `y`.
    fn pointer(&self, x: f32, y: f32, c: Color) {
        let mid = y - 8.0;
        self.gfx.line(x + 2.0, mid, x + 16.0, mid, 3.0, c);
        self.gfx
            .triangle(x + 14.0, mid + 7.0, x + 14.0, mid - 7.0, x + 24.0, mid, c);
    }

    fn territory_line(&self, r: usize, armies: i64, x: f32, y: f32) {
        let game = game!(self);
        self.token(
            x + 12.0,
            y - 8.0,
            11.0,
            self.player_color(game.state.owners[r]),
        );
        self.txt(game.name(r), x + 32.0, y, PAPER);
        let nx = x + 40.0 + self.gfx.width(GAME, game.name(r));
        self.txt(&armies.to_string(), nx, y, GOLD);
    }

    fn deploy_panel(&self, x: f32, w: f32) {
        let g = &self.gfx;
        let game = game!(self);
        let s = &game.state;
        let hand = s.active_player().cards.len();
        if s.reserve == 0 {
            if s.phase == Phase::Setup {
                self.instruction("All armies placed. Tap FINISH PLACEMENT to continue.", GOLD);
            } else if hand >= 5 {
                self.instruction(
                    &format!("You hold {hand} cards: trade a set before attacking."),
                    GOLD,
                );
            } else {
                self.instruction(
                    "All armies placed. Trade cards for more, or start attacking.",
                    GOLD,
                );
            }
            return;
        }
        if self.from < 0 {
            self.instruction(
                &format!(
                    "Tap one of your highlighted territories to place {}.",
                    plural(s.reserve, " army", " armies")
                ),
                PAPER,
            );
            let tip = if hand >= 3 {
                format!("• You have {hand} cards. Open CARDS to trade a matching set.")
            } else {
                "• Capture a territory each turn to earn a card.".into()
            };
            g.wrap(
                BODY,
                &format!(
                    "• Reinforce borders that face enemies and continents you want to hold.\n\
                     • Use the slider to split your armies across several territories.\n{tip}"
                ),
                x,
                140.0,
                w,
                MUTED,
            );
            return;
        }
        let from = self.fr();
        let amount = self.amount.min(s.reserve).max(1);
        self.instruction(&format!("Place armies on {}.", game.name(from)), PAPER);
        self.territory_line(from, s.armies[from], x, 150.0);
        let nx =
            x + 54.0 + g.width(GAME, game.name(from)) + g.width(GAME, &s.armies[from].to_string());
        self.pointer(nx, 150.0, GOLD);
        self.txt(
            &(s.armies[from] + amount).to_string(),
            nx + 34.0,
            150.0,
            GOLD,
        );
        self.amount_control(x, 46.0, 500.0, 1, s.reserve);
        self.button(
            &format!("PLACE {amount}"),
            x + 516.0,
            46.0,
            196.0,
            52.0,
            Style::Primary,
            |a| a.deploy(),
        );
    }

    fn attack_panel(&self, x: f32, w: f32) {
        let g = &self.gfx;
        let game = game!(self);
        let s = &game.state;
        if self.from < 0 {
            self.instruction(
                "Choose where to attack from, or tap an enemy territory.",
                PAPER,
            );
            g.wrap(
                BODY,
                "• Gold rings mark your territories that can attack (2+ armies next to an enemy).\n\
                 • Tap an enemy first and your strongest neighbour is picked for you.\n\
                 • Finished? Tap END ATTACKS to move armies and end your turn.",
                x,
                140.0,
                w,
                MUTED,
            );
            self.last_roll(x, 30.0);
            return;
        }
        let from = self.fr();
        if self.to < 0 {
            self.instruction(
                &format!(
                    "Attack from {} ({}) — pick a target.",
                    game.name(from),
                    s.armies[from]
                ),
                PAPER,
            );
            let mut targets: Vec<usize> = game
                .neighbours(from)
                .iter()
                .copied()
                .filter(|&n| game.can_attack(from, n))
                .collect();
            targets.sort_by_key(|&n| s.armies[n]);
            for (i, &t) in targets.iter().take(8).enumerate() {
                let bx = x + (i % 4) as f32 * 180.0;
                let by = 98.0 - (i / 4) as f32 * 60.0;
                let label = format!("{}  {}", game.name(t), s.armies[t]);
                self.button(&label, bx, by, 172.0, 52.0, Style::Normal, move |a| {
                    a.to = t as i32;
                    Ok(())
                });
                self.token(bx + 12.0, by + 40.0, 6.0, self.player_color(s.owners[t]));
            }
            return;
        }
        let to = self.tr();
        self.instruction(
            &format!(
                "Battle for {} ({})",
                game.name(to),
                s.players[s.owners[to]].name
            ),
            PAPER,
        );
        self.territory_line(from, s.armies[from], x, 152.0);
        let mx =
            x + 60.0 + g.width(GAME, game.name(from)) + g.width(GAME, &s.armies[from].to_string());
        self.txt("vs", mx, 152.0, MUTED);
        self.territory_line(to, s.armies[to], mx + 36.0, 152.0);
        let odds = game.conquest_chance(from, to);
        let oc = if odds >= 0.7 {
            GREEN
        } else if odds >= 0.4 {
            GOLD
        } else {
            RED
        };
        g.right(BODY, "BLITZ WIN CHANCE", x + w - 70.0, 147.0, MUTED);
        g.right(
            GAME,
            &format!("{}%", (odds * 100.0).round() as i64),
            x + w,
            152.0,
            oc,
        );
        let max_dice = 3.min(s.armies[from] - 1);
        let dice = self.dice.min(max_dice).max(1);
        for d in 1..=3i64 {
            let bx = x + (d - 1) as f32 * 60.0;
            let ok = d <= max_dice;
            if d == dice {
                g.round(bx - 3.0, 69.0, 58.0, 58.0, 10.0, GOLD);
            }
            let face = if !ok {
                OFF
            } else if d == dice {
                PARCHMENT
            } else {
                hex("B9B3A3")
            };
            self.die(bx, 72.0, 52.0, d as i32, face, if ok { INK } else { MUTED });
            self.add_hit(Area::new(bx, 72.0, 52.0, 52.0), move |a| {
                if d > max_dice {
                    return refuse(format!("Attacking with {d} dice needs {} armies.", d + 1));
                }
                a.dice = d;
                Ok(())
            });
        }
        g.text(SMALL, "ATTACK DICE", x, 64.0, MUTED);
        self.button("ROLL", x + 188.0, 72.0, 160.0, 52.0, Style::Primary, |a| {
            a.roll(false)
        });
        self.button("BLITZ", x + 360.0, 72.0, 160.0, 52.0, Style::Danger, |a| {
            a.roll(true)
        });
        self.button("CANCEL", x + 532.0, 72.0, 180.0, 52.0, Style::Normal, |a| {
            a.to = -1;
            Ok(())
        });
        self.last_roll(x + 188.0, 30.0);
    }

    fn last_roll(&self, x: f32, y: f32) {
        if self.shown_attack.is_empty() {
            return;
        }
        let rolling = self.animation > 0.0;
        let tick = (self.clock * 24.0) as i32;
        let (mut a, mut d) = (self.shown_attack.clone(), self.shown_defend.clone());
        a.sort_unstable();
        d.sort_unstable();
        let pairs = a.len().min(d.len());
        let top = |v: &[i32], i: usize| v[v.len() - 1 - i];
        for i in 0..a.len() {
            let v = if rolling {
                1 + (tick + i as i32 * 3) % 6
            } else {
                top(&a, i)
            };
            // After the roll, the losing die of each compared pair is faded.
            let lost = !rolling && i < pairs && top(&a, i) <= top(&d, i);
            self.die(
                x + i as f32 * 36.0,
                y,
                30.0,
                v,
                if lost { alpha(RED, 0.35) } else { RED },
                PAPER,
            );
        }
        let dx = x + 3.0 * 36.0 + 18.0;
        for i in 0..d.len() {
            let v = if rolling {
                1 + (tick + i as i32 * 2) % 6
            } else {
                top(&d, i)
            };
            let lost = !rolling && i < pairs && top(&a, i) > top(&d, i);
            self.die(
                dx + i as f32 * 36.0,
                y,
                30.0,
                v,
                if lost {
                    alpha(PARCHMENT, 0.3)
                } else {
                    PARCHMENT
                },
                INK,
            );
        }
        let sx = dx + 2.0 * 36.0 + 16.0;
        if !rolling {
            self.gfx
                .fit_text(BODY, &self.roll_summary, sx, y + 22.0, 1180.0 - sx, PAPER);
        }
    }

    fn occupy_panel(&self, x: f32) {
        let game = game!(self);
        let s = &game.state;
        let (f, t) = (s.occupation_from as usize, s.occupation_to as usize);
        let (max, min) = (s.armies[f] - 1, s.occupation_minimum);
        let amount = self.amount.min(max).max(min);
        self.instruction(
            &format!(
                "{} captured!  Move armies in from {}.",
                game.name(t),
                game.name(f)
            ),
            GOLD,
        );
        self.amount_control(x, 86.0, 500.0, min, max);
        self.gfx.text(
            BODY,
            &format!("{} keeps {}", game.name(f), s.armies[f] - amount),
            x + 76.0,
            152.0,
            MUTED,
        );
        self.button(
            &format!("MOVE {amount}"),
            x + 516.0,
            86.0,
            196.0,
            52.0,
            Style::Primary,
            |a| a.occupy(),
        );
        self.last_roll(x, 24.0);
    }

    fn fortify_panel(&self, x: f32, w: f32) {
        let game = game!(self);
        let s = &game.state;
        if self.from < 0 {
            self.instruction(
                "Move armies between connected territories, or end your turn.",
                PAPER,
            );
            self.gfx.wrap(
                BODY,
                "• Tap a territory to move from, then a highlighted destination.\n\
                 • Armies travel through your own connected land (and allies with open borders).\n\
                 • Each army can move once per turn. Moving is optional.",
                x,
                140.0,
                w,
                MUTED,
            );
            return;
        }
        let from = self.fr();
        if self.to < 0 {
            self.instruction(
                &format!(
                    "Move from {} ({} can move) — pick a highlighted destination.",
                    game.name(from),
                    game.movable(from)
                ),
                PAPER,
            );
            self.button("CANCEL", x, 46.0, 180.0, 52.0, Style::Normal, |a| {
                a.from = -1;
                a.to = -1;
                Ok(())
            });
            return;
        }
        let to = self.tr();
        let amount = self.amount.min(game.movable(from)).max(1);
        self.instruction(
            &format!("Move armies from {} to {}.", game.name(from), game.name(to)),
            PAPER,
        );
        self.territory_line(from, s.armies[from] - amount, x, 150.0);
        let mx = x
            + 60.0
            + self.gfx.width(GAME, game.name(from))
            + self.gfx.width(GAME, &(s.armies[from] - amount).to_string());
        self.pointer(mx, 150.0, MUTED);
        self.territory_line(to, s.armies[to] + amount, mx + 32.0, 150.0);
        self.amount_control(x, 46.0, 500.0, 1, game.movable(from));
        self.button(
            &format!("MOVE {amount}"),
            x + 516.0,
            46.0,
            196.0,
            52.0,
            Style::Primary,
            |a| a.fortify(),
        );
    }

    fn command_panel(&self) {
        let g = &self.gfx;
        let game = game!(self);
        let s = &game.state;
        let x = 1226.0;
        if s.phase == Phase::Finished {
            let winner = s.winner as usize;
            g.text(
                HEADING,
                &s.players[winner].name,
                x,
                244.0,
                self.player_color(winner),
            );
            g.text(SMALL, "WINNER", x, 206.0, MUTED);
            return;
        }
        let pl = s.active_player();
        g.fit_text(
            HEADING,
            &pl.name,
            x,
            244.0,
            340.0,
            self.player_color(s.active),
        );
        let role = if pl.human {
            "HUMAN COMMANDER".to_string()
        } else {
            format!("COMPUTER  ·  {}", s.difficulty.name().to_uppercase())
        };
        g.text(SMALL, &role, x, 208.0, MUTED);
        if !pl.human {
            return;
        }
        let hand = pl.cards.len();
        let can_trade = s.phase == Phase::Reinforce && !game.find_set(s.active).is_empty();
        match s.phase {
            Phase::Setup | Phase::Reinforce => {
                let reserve = s.reserve.to_string();
                g.text(BIG, &reserve, x, 186.0, GOLD);
                g.text(
                    BODY,
                    if s.reserve == 1 {
                        "ARMY TO PLACE"
                    } else {
                        "ARMIES TO PLACE"
                    },
                    x + 14.0 + g.width(BIG, &reserve),
                    168.0,
                    PAPER,
                );
                if let (Some(income), Phase::Reinforce) = (self.income, s.phase) {
                    g.text(
                        SMALL,
                        &format!(
                            "Lands +{}   Continents +{}   Army bonus +{}",
                            income[0], income[1], income[2]
                        ),
                        x,
                        150.0,
                        MUTED,
                    );
                }
            }
            Phase::Attack | Phase::Occupy => g.wrap(
                BODY,
                if s.conquered {
                    "You have captured territory this turn and will draw a card."
                } else {
                    "Capture at least one territory to draw a card at the end of your turn."
                },
                x,
                180.0,
                344.0,
                if s.conquered { GREEN } else { MUTED },
            ),
            Phase::Fortify => g.wrap(
                BODY,
                &format!(
                    "Optional: reposition your armies, then end your turn.{}",
                    if s.conquered {
                        " You will draw a card."
                    } else {
                        ""
                    }
                ),
                x,
                180.0,
                344.0,
                MUTED,
            ),
            Phase::Finished => {}
        }
        if can_trade {
            self.button(
                &format!("TRADE CARDS  +{}", game.next_set_value()),
                x - 2.0,
                98.0,
                348.0,
                40.0,
                if hand >= 5 {
                    Style::Primary
                } else {
                    Style::Selected
                },
                |a| a.trade(),
            );
        }
        let (label, style) = match s.phase {
            Phase::Setup if s.reserve > 0 => (format!("PLACE {} MORE", s.reserve), Style::Disabled),
            Phase::Setup => ("FINISH PLACEMENT".into(), Style::Primary),
            Phase::Reinforce if hand >= 5 => ("TRADE CARDS FIRST".into(), Style::Disabled),
            Phase::Reinforce if s.reserve > 0 => {
                (format!("PLACE {} MORE", s.reserve), Style::Disabled)
            }
            Phase::Reinforce => ("START ATTACKING".into(), Style::Primary),
            Phase::Attack => ("END ATTACKS".into(), Style::Primary),
            Phase::Occupy => {
                let max = s.armies[s.occupation_from as usize] - 1;
                (
                    format!("MOVE {} IN", self.amount.min(max).max(s.occupation_minimum)),
                    Style::Primary,
                )
            }
            Phase::Fortify => ("END TURN".into(), Style::Primary),
            Phase::Finished => (String::new(), Style::Primary),
        };
        self.button(&label, x - 2.0, 26.0, 348.0, 62.0, style, |a| a.primary());
    }

    // ---------------------------------------------------------------- modals

    fn open(&mut self, next: Modal) {
        if !self.human_turn() {
            if next == Modal::Settings || (self.game.is_some() && next == Modal::Report) {
                self.modal = next;
            }
            return;
        }
        if self.animation > 0.0 {
            return;
        }
        self.modal = next;
        self.page = 0;
        self.cards.clear();
    }

    fn panel(&self, title: &str) {
        let g = &self.gfx;
        g.rect(0.0, 0.0, 1600.0, 1000.0, Color::new(0.02, 0.045, 0.07, 0.9));
        g.round(220.0, 86.0, 1160.0, 828.0, 18.0, PANEL);
        g.round(220.0, 902.0, 1160.0, 12.0, 6.0, GOLD);
        g.text(HEADING, title, 260.0, 870.0, GOLD);
        if self.modal != Modal::Menu && self.modal != Modal::Handover {
            self.button("CLOSE", 1210.0, 828.0, 140.0, 48.0, Style::Normal, |a| {
                a.close_modal();
                Ok(())
            });
        }
    }

    fn close_modal(&mut self) {
        self.modal = match self.modal {
            Modal::Pick => self.return_modal,
            Modal::Player => Modal::Menu,
            Modal::Offer | Modal::Compose => Modal::Diplomacy,
            Modal::Rules => self.rules_return,
            _ if self.game.is_none() => Modal::Menu,
            _ => Modal::None,
        };
        if self.modal == Modal::Settings && self.game.is_none() {
            self.modal = Modal::Menu;
        }
    }

    fn draw_modal(&self) {
        match self.modal {
            Modal::Menu => self.menu(),
            Modal::Handover => self.handover(),
            Modal::Report => self.report_modal(),
            Modal::Cards => self.cards_modal(),
            Modal::Diplomacy => self.diplomacy(),
            Modal::Offer => self.offer_modal(),
            Modal::Compose => self.compose(),
            Modal::Pick => self.picker(),
            Modal::Settings => self.settings(),
            Modal::Rules => self.rules_modal(),
            Modal::Player => self.editor(),
            Modal::None => {}
        }
    }

    fn menu(&self) {
        let g = &self.gfx;
        self.panel("");
        g.text(TITLE, "GAME OF SUPREMACY", 260.0, 880.0, PAPER);
        g.text(
            BODY,
            "Five commanders. Seven continents. One world.",
            262.0,
            826.0,
            MUTED,
        );
        g.text(
            SMALL,
            "COMMANDERS  ·  TAP A HUMAN'S NAME TO EDIT NAME AND COLOUR",
            260.0,
            780.0,
            GOLD,
        );
        let preview = self.preview_colors();
        for seat in 0..5 {
            let y = 704.0 - seat as f32 * 66.0;
            let human = seat < self.humans;
            g.round(260.0, y, 500.0, 58.0, 10.0, RAISED);
            self.token(292.0, y + 29.0, 14.0, hex(COLORS[preview[seat]]));
            if human {
                let named = self.named[seat];
                g.fit_text(
                    GAME,
                    &self.seat_name[seat],
                    320.0,
                    y + 40.0,
                    230.0,
                    if named { PAPER } else { MUTED },
                );
                g.text(
                    SMALL,
                    if named {
                        "Tap to edit name and colour"
                    } else {
                        "Tap to enter your name"
                    },
                    320.0,
                    y + 17.0,
                    if named { MUTED } else { GOLD },
                );
                self.add_hit(Area::new(260.0, y, 320.0, 58.0), move |a| {
                    a.open_editor(seat, false);
                    Ok(())
                });
            } else {
                self.txt(BOT_NAMES[seat - self.humans], 320.0, y + 39.0, PAPER);
            }
            self.button(
                if human { "HUMAN" } else { "COMPUTER" },
                590.0,
                y + 9.0,
                156.0,
                40.0,
                if human {
                    Style::Selected
                } else {
                    Style::Normal
                },
                move |a| {
                    a.confirm_new = false;
                    if seat == 0 {
                        return refuse("Seat 1 is always a human player.");
                    }
                    a.humans = if seat < a.humans { seat } else { seat + 1 };
                    a.save_setup();
                    Ok(())
                },
            );
        }
        g.text(SMALL, "COMPUTER SKILL", 800.0, 780.0, GOLD);
        for (i, level) in Difficulty::ALL.iter().enumerate() {
            let bx = 800.0 + (i % 2) as f32 * 280.0;
            let by = 704.0 - (i / 2) as f32 * 76.0;
            let chosen = i == self.difficulty;
            if chosen {
                g.round(bx - 2.0, by - 2.0, 268.0, 66.0, 11.0, GOLD);
            }
            g.round(
                bx,
                by,
                264.0,
                62.0,
                10.0,
                if chosen { RAISED_HI } else { RAISED },
            );
            self.txt(
                level.name(),
                bx + 16.0,
                by + 46.0,
                if chosen { GOLD } else { PAPER },
            );
            g.text(SMALL, SKILL_NOTES[i], bx + 16.0, by + 20.0, MUTED);
            self.add_hit(Area::new(bx, by, 264.0, 62.0), move |a| {
                a.difficulty = i;
                a.confirm_new = false;
                a.save_setup();
                Ok(())
            });
        }
        let bots = 5 - self.humans;
        g.wrap(
            BODY,
            &format!(
                "{} Territories are dealt at random and every commander starts with 55 armies.{}",
                if bots == 0 {
                    "All five seats are human.".to_string()
                } else {
                    format!("{bots} computer opponents share this skill level.")
                },
                if self.humans > 1 {
                    " Pass the device when prompted; cards stay private."
                } else {
                    ""
                }
            ),
            800.0,
            448.0,
            540.0,
            MUTED,
        );
        self.button(
            "HOW TO PLAY",
            260.0,
            120.0,
            240.0,
            64.0,
            Style::Normal,
            |a| {
                a.rules(Modal::Menu);
                Ok(())
            },
        );
        if self
            .game
            .as_ref()
            .is_some_and(|g| g.state.phase != Phase::Finished)
        {
            self.button(
                "RETURN TO GAME",
                520.0,
                120.0,
                330.0,
                64.0,
                Style::Normal,
                |a| {
                    a.last_active = -1;
                    a.check_turn();
                    Ok(())
                },
            );
        } else if self.saves.exists() {
            self.button(
                "RESUME SAVED GAME",
                520.0,
                120.0,
                330.0,
                64.0,
                Style::Normal,
                |a| {
                    let restored = a.saves.load(&a.board).map_err(|e| {
                        if e.contains("different board") {
                            "This saved game uses an older version of the map. Start a new game."
                                .to_string()
                        } else {
                            e
                        }
                    })?;
                    let warning = a.saves.warning().to_string();
                    a.attach(restored);
                    if !warning.is_empty() {
                        a.say(&warning, true);
                    }
                    Ok(())
                },
            );
        }
        self.button(
            if self.confirm_new {
                "TAP AGAIN TO REPLACE SAVE"
            } else {
                "START NEW GAME"
            },
            870.0,
            120.0,
            470.0,
            64.0,
            if self.confirm_new {
                Style::Danger
            } else {
                Style::Primary
            },
            |a| a.start_pressed(),
        );
    }

    // ---------------------------------------------------------------- player editor

    fn editor(&self) {
        let g = &self.gfx;
        let editing = self.editing;
        self.panel(&format!("PLAYER {}", editing + 1));
        let current = hex(COLORS[self.seat_color[editing] as usize]);
        self.token(1160.0, 862.0, 20.0, current);
        g.text(SMALL, "NAME", 260.0, 828.0, GOLD);
        g.round(256.0, 738.0, 612.0, 66.0, 12.0, GOLD);
        g.round(259.0, 741.0, 606.0, 60.0, 10.0, INK);
        if self.edit_text.is_empty() {
            g.text(GAME, "Type your name", 276.0, 780.0, MUTED);
        } else {
            g.text(GAME, &self.edit_text, 276.0, 780.0, PAPER);
        }
        if (self.clock * 2.0) as i32 % 2 == 0 {
            let cx = 276.0
                + if self.edit_text.is_empty() {
                    0.0
                } else {
                    g.width(GAME, &self.edit_text)
                }
                + 3.0;
            g.rect(cx, 752.0, 3.0, 38.0, GOLD);
        }
        g.right(
            SMALL,
            &format!("{} / {MAX_NAME}", self.edit_text.chars().count()),
            856.0,
            760.0,
            MUTED,
        );
        g.text(SMALL, "COLOUR", 260.0, 722.0, GOLD);
        for (k, color) in COLORS.iter().enumerate() {
            let (cx, cy) = (290.0 + k as f32 * 72.0, 670.0);
            let chosen = self.seat_color[editing] == k as i32;
            let holder = (0..self.humans)
                .filter(|&j| j != editing && self.seat_color[j] == k as i32)
                .last();
            if chosen {
                self.token(cx, cy, 31.0, GOLD);
            }
            self.token(cx, cy, 25.0, hex(color));
            if let Some(holder) = holder {
                g.centered(SMALL, &(holder + 1).to_string(), cx, cy + 5.0, INK);
            }
            self.add_hit(Area::new(cx - 34.0, cy - 34.0, 68.0, 68.0), move |a| {
                a.pick_color(a.editing, k as i32);
                Ok(())
            });
        }
        g.text(
            GAME,
            COLOR_NAMES[self.seat_color[editing] as usize],
            880.0,
            680.0,
            current,
        );
        g.text(
            SMALL,
            "A number marks a colour another human holds; picking it swaps the two.",
            260.0,
            620.0,
            MUTED,
        );
        let rows = ["1234567890", "QWERTYUIOP", "ASDFGHJKL"];
        let ys = [540.0, 472.0, 404.0];
        for (row, y) in rows.iter().zip(ys) {
            let x = 800.0 - (row.len() as f32 * 104.0 - 8.0) / 2.0;
            for (i, c) in row.chars().enumerate() {
                self.button(
                    &c.to_string(),
                    x + i as f32 * 104.0,
                    y,
                    96.0,
                    56.0,
                    Style::Normal,
                    move |a| {
                        a.press(c.to_ascii_lowercase());
                        Ok(())
                    },
                );
            }
        }
        let x3 = 292.0;
        self.button(
            "SHIFT",
            x3,
            336.0,
            140.0,
            56.0,
            if self.shift {
                Style::Selected
            } else {
                Style::Normal
            },
            |a| {
                a.shift = !a.shift;
                Ok(())
            },
        );
        for (i, c) in "ZXCVBNM".chars().enumerate() {
            self.button(
                &c.to_string(),
                x3 + 148.0 + i as f32 * 104.0,
                336.0,
                96.0,
                56.0,
                Style::Normal,
                move |a| {
                    a.press(c.to_ascii_lowercase());
                    Ok(())
                },
            );
        }
        self.button(
            "DEL",
            x3 + 148.0 + 7.0 * 104.0,
            336.0,
            140.0,
            56.0,
            Style::Normal,
            |a| {
                a.backspace();
                Ok(())
            },
        );
        for (label, x, w, c) in [
            ("-", 444.0, 96.0, '-'),
            ("'", 548.0, 96.0, '\''),
            ("SPACE", 652.0, 400.0, ' '),
            (".", 1060.0, 96.0, '.'),
        ] {
            self.button(label, x, 268.0, w, 56.0, Style::Normal, move |a| {
                a.press(c);
                Ok(())
            });
        }
        let next = if self.edit_flow {
            if (editing + 1..self.humans).any(|i| !self.named[i]) {
                "NEXT PLAYER"
            } else {
                "START GAME"
            }
        } else {
            "DONE"
        };
        self.button(next, 1010.0, 106.0, 340.0, 56.0, Style::Primary, |a| {
            a.finish_edit()
        });
    }

    fn visible_report(&self) -> Vec<String> {
        let game = game!(self);
        let me = format!("{} ", game.state.active_player().name);
        self.report
            .iter()
            .filter(|l| !l.contains("; losses"))
            .filter(|l| {
                self.log_mode || (!l.starts_with(&me) && !l.starts_with("Random territories"))
            })
            .cloned()
            .collect()
    }

    fn handover(&self) {
        let g = &self.gfx;
        let s = &game!(self).state;
        let name = &s.active_player().name;
        let c = self.player_color(s.active);
        g.rect(
            0.0,
            0.0,
            1600.0,
            1000.0,
            Color::new(0.02, 0.045, 0.07, 0.97),
        );
        g.round(400.0, 230.0, 800.0, 540.0, 20.0, PANEL);
        g.round(400.0, 752.0, 800.0, 18.0, 9.0, c);
        self.token(800.0, 650.0, 34.0, c);
        g.centered(TITLE, &name.to_uppercase(), 800.0, 590.0, PAPER);
        g.centered(
            GAME,
            &if s.phase == Phase::Setup {
                "Place your starting armies.".to_string()
            } else {
                format!("Your turn {} is ready.", s.turn)
            },
            800.0,
            530.0,
            GOLD,
        );
        g.centered(
            BODY,
            &format!("Pass the device to {name}. Everyone else, look away —"),
            800.0,
            478.0,
            MUTED,
        );
        g.centered(
            BODY,
            "cards stay private until this player continues.",
            800.0,
            452.0,
            MUTED,
        );
        let events = self.visible_report().len() as i64;
        if events > 0 && s.phase != Phase::Setup {
            g.centered(
                BODY,
                &format!(
                    "{} since your last turn.",
                    plural(events, " event", " events")
                ),
                800.0,
                410.0,
                PAPER,
            );
        }
        self.button(
            &format!("I'M {}  —  START", name.to_uppercase()),
            520.0,
            280.0,
            560.0,
            72.0,
            Style::Primary,
            |a| {
                a.show_report();
                Ok(())
            },
        );
    }

    fn report_modal(&self) {
        let g = &self.gfx;
        self.panel(if self.log_mode {
            "BATTLE LOG  ·  NEWEST FIRST"
        } else {
            "WHILE YOU WERE AWAY"
        });
        let lines = self.visible_report();
        let me = &game!(self).state.active_player().name;
        let mut top = 784.0;
        if !self.log_mode {
            // Lead with what matters most to the returning player: the land they lost.
            let marker = format!(" attacked {me} and won ");
            let lost: Vec<String> = lines
                .iter()
                .filter_map(|l| {
                    l.find(&marker)
                        .map(|i| l[i + marker.len()..].replace('.', ""))
                })
                .collect();
            let summary = if lost.is_empty() {
                "Your territories held. No land was lost.".to_string()
            } else {
                format!(
                    "You lost {}{}.",
                    plural(lost.len() as i64, " territory: ", " territories: "),
                    lost.join(", ")
                )
            };
            g.round(
                260.0,
                742.0,
                1080.0,
                58.0,
                10.0,
                if lost.is_empty() { RAISED } else { ALARM },
            );
            g.fit_text(
                GAME,
                &summary,
                280.0,
                780.0,
                1040.0,
                if lost.is_empty() { GREEN } else { ALARM_TEXT },
            );
            top = 700.0;
        }
        if lines.is_empty() {
            self.txt("Nothing new to report.", 260.0, top, MUTED);
        }
        let per = if self.log_mode { 9 } else { 8 };
        let attacked_me = format!("attacked {me}");
        for (k, line) in lines.iter().enumerate().skip(self.page * per).take(per) {
            let y = top - (k - self.page * per) as f32 * 66.0;
            let dot =
                if line.contains(&attacked_me) || line.contains(&format!("{me} was eliminated")) {
                    RED
                } else if line.contains(" won ") || line.contains("conquered") {
                    GOLD
                } else {
                    MUTED
                };
            self.token(272.0, y - 12.0, 6.0, dot);
            g.wrap(
                GAME,
                line,
                296.0,
                y,
                1040.0,
                if line.contains(&attacked_me) {
                    ALARM_TEXT
                } else {
                    PAPER
                },
            );
        }
        self.paging(lines.len(), per);
        self.button(
            "CONTINUE",
            1050.0,
            106.0,
            300.0,
            56.0,
            Style::Primary,
            |a| {
                a.modal = Modal::None;
                Ok(())
            },
        );
    }

    fn cards_modal(&self) {
        let g = &self.gfx;
        let game = game!(self);
        let s = &game.state;
        self.panel("YOUR CARDS");
        let hand = &s.active_player().cards;
        g.text(
            BODY,
            &format!(
                "Trade three of a kind, or one of each type. Wilds match anything. Next set: +{} armies.",
                game.next_set_value()
            ),
            262.0,
            826.0,
            MUTED,
        );
        if hand.is_empty() {
            g.wrap(
                GAME,
                "No cards yet. Capture at least one territory during your turn to draw a card.",
                260.0,
                700.0,
                1000.0,
                MUTED,
            );
        }
        let per = 12;
        for (i, &card) in hand.iter().enumerate().skip(self.page * per).take(per) {
            let k = i - self.page * per;
            let chosen = self.cards.contains(&card);
            let cx = 270.0 + (k % 6) as f32 * 178.0;
            let cy = if k < 6 { 560.0 } else { 300.0 } + if chosen { 16.0 } else { 0.0 };
            self.draw_card(card, cx, cy, chosen);
            self.add_hit(Area::new(cx, cy, 160.0, 220.0), move |a| {
                if let Some(pos) = a.cards.iter().position(|&c| c == card) {
                    a.cards.remove(pos);
                } else if a.cards.len() < 3 {
                    a.cards.push(card);
                }
                Ok(())
            });
        }
        if hand.len() > per {
            self.paging(hand.len(), per);
        }
        let valid = self.cards.len() == 3 && game.valid_set(&self.cards);
        let phase_ok = s.phase == Phase::Reinforce;
        self.button(
            "SELECT A SET FOR ME",
            if hand.len() > per { 640.0 } else { 260.0 },
            106.0,
            330.0,
            56.0,
            Style::Normal,
            |a| {
                a.cards = a.g().find_set(a.g().state.active);
                if a.cards.is_empty() {
                    return refuse("No matching set in your hand yet.");
                }
                Ok(())
            },
        );
        self.button(
            &format!("TRADE IN  +{}", game.next_set_value()),
            1010.0,
            106.0,
            340.0,
            56.0,
            if valid && phase_ok {
                Style::Primary
            } else {
                Style::Disabled
            },
            move |a| {
                if !phase_ok {
                    return refuse("Cards can be traded during the reinforce step of your turn.");
                }
                if !valid {
                    return refuse("Select three cards: three of a kind or one of each.");
                }
                a.trade()?;
                a.modal = Modal::None;
                Ok(())
            },
        );
    }

    fn draw_card(&self, card: usize, x: f32, y: f32, selected: bool) {
        let g = &self.gfx;
        let game = game!(self);
        let symbol = game.symbol(card);
        let owned = card < game.state.owners.len() && game.state.owners[card] == game.state.active;
        let paper = hex("E3D7BA");
        if selected {
            g.round(x - 5.0, y - 5.0, 170.0, 230.0, 14.0, GOLD);
        }
        g.round(x + 2.0, y - 4.0, 160.0, 220.0, 11.0, alpha(BLACK, 0.45));
        g.round(x, y, 160.0, 220.0, 11.0, PARCHMENT);
        g.round(x + 8.0, y + 8.0, 144.0, 204.0, 7.0, paper);
        if symbol < 3 {
            g.rect(
                x + 8.0,
                y + 172.0,
                144.0,
                6.0,
                hex(&self.board.continent_of(card).color),
            );
        }
        let (cx, cy) = (x + 80.0, y + 104.0);
        let ink = hex("3A3324");
        match symbol {
            0 => self.infantry(cx, cy, ink),
            1 => self.cavalry(cx, cy, ink, paper),
            2 => self.artillery(cx, cy, ink),
            _ => self.star(cx, cy + 6.0, 34.0, hex("9C7A2E")),
        }
        let name = if symbol == 3 {
            "WILD CARD"
        } else {
            game.name(card)
        };
        let style = SMALL.scaled((136.0 / g.width(SMALL, name).max(1.0)).min(1.0));
        g.centered(style, name, x + 80.0, y + 202.0, ink);
        g.centered(SMALL, CARD_TYPES[symbol], x + 80.0, y + 40.0, hex("6E6247"));
        if owned {
            g.round(x + 104.0, y + 12.0, 44.0, 20.0, 10.0, GOLD);
            g.centered(SMALL, "+2", x + 126.0, y + 27.0, INK);
        }
    }

    fn infantry(&self, x: f32, y: f32, c: Color) {
        let g = &self.gfx;
        g.circle(x, y + 30.0, 11.0, 20, c);
        g.triangle(x - 20.0, y - 22.0, x + 20.0, y - 22.0, x, y + 22.0, c);
        g.rect(x - 24.0, y - 32.0, 48.0, 10.0, c);
    }

    fn cavalry(&self, x: f32, y: f32, c: Color, paper: Color) {
        let g = &self.gfx;
        for i in 0..18 {
            let a = (-50.0 + i as f32 * 280.0 / 18.0f32).to_radians();
            let b = (-50.0 + (i + 1) as f32 * 280.0 / 18.0f32).to_radians();
            g.line(
                x + a.cos() * 26.0,
                y + a.sin() * 26.0,
                x + b.cos() * 26.0,
                y + b.sin() * 26.0,
                11.0,
                c,
            );
        }
        for i in 0..6 {
            let a = (-20.0 + i as f32 * 44.0f32).to_radians();
            g.circle(x + a.cos() * 26.0, y + a.sin() * 26.0, 2.2, 8, paper);
        }
    }

    fn artillery(&self, x: f32, y: f32, c: Color) {
        let g = &self.gfx;
        g.line(x - 14.0, y - 8.0, x + 30.0, y + 22.0, 13.0, c);
        g.line(x - 14.0, y - 8.0, x - 34.0, y - 26.0, 5.0, c);
        g.ring(x - 8.0, y - 16.0, 14.0, 4.0, c);
        g.circle(x - 8.0, y - 16.0, 4.0, 10, c);
        g.rect(x - 40.0, y - 34.0, 70.0, 4.0, c);
    }

    fn star(&self, x: f32, y: f32, r: f32, c: Color) {
        use std::f32::consts::{PI, TAU};
        for k in 0..5 {
            let a = PI / 2.0 + k as f32 * TAU / 5.0;
            let (l, rr, inner) = (a - PI / 5.0, a + PI / 5.0, r * 0.42);
            let (ox, oy) = (x + a.cos() * r, y + a.sin() * r);
            self.gfx
                .triangle(x, y, x + l.cos() * inner, y + l.sin() * inner, ox, oy, c);
            self.gfx
                .triangle(x, y, ox, oy, x + rr.cos() * inner, y + rr.sin() * inner, c);
        }
    }

    fn diplomacy(&self) {
        let g = &self.gfx;
        let game = game!(self);
        let s = &game.state;
        self.panel("DIPLOMACY");
        g.text(
            BODY,
            "Offers are delivered on the recipient's turn. Responses arrive on your next turn.",
            262.0,
            826.0,
            MUTED,
        );
        let inbox = game.inbox();
        g.text(SMALL, "INCOMING OFFERS", 280.0, 780.0, GOLD);
        for (i, o) in inbox.iter().enumerate().skip(self.page * 5).take(5) {
            let id = o.id;
            self.btn(
                &format!(
                    "#{}  FROM {}  —  REVIEW",
                    o.id,
                    s.players[o.from].name.to_uppercase()
                ),
                280.0,
                700.0 - (i - self.page * 5) as f32 * 64.0,
                970.0,
                move |a| {
                    a.view_offer = id;
                    a.page = 0;
                    a.modal = Modal::Offer;
                    Ok(())
                },
            );
        }
        if inbox.is_empty() {
            self.txt("No pending offers.", 280.0, 740.0, MUTED);
        }
        if inbox.len() > 5 {
            self.paging(inbox.len(), 5);
        }
        let recipient = self.recipient;
        let other = &s.players[recipient].name;
        g.text(SMALL, "NEW OFFER", 280.0, 368.0, GOLD);
        self.btn(
            &format!("TO: {other}  (change)"),
            280.0,
            300.0,
            470.0,
            |a| {
                a.cycle_recipient();
                Ok(())
            },
        );
        self.token(306.0, 326.0, 10.0, self.player_color(recipient));
        self.button(
            "COMPOSE OFFER",
            780.0,
            300.0,
            470.0,
            52.0,
            Style::Primary,
            move |a| {
                a.draft = Offer::new(a.g().state.active, recipient);
                a.counter_id = -1;
                a.army_page = false;
                a.page = 0;
                a.modal = Modal::Compose;
                Ok(())
            },
        );
        let borders = s.borders[s.active][recipient];
        self.button(
            &format!(
                "{} {}",
                if borders {
                    "REVOKE OPEN BORDERS WITH"
                } else {
                    "NO OPEN BORDERS WITH"
                },
                other.to_uppercase()
            ),
            280.0,
            230.0,
            970.0,
            52.0,
            if borders {
                Style::Danger
            } else {
                Style::Disabled
            },
            move |a| {
                if !borders {
                    return refuse("Open borders are agreed through an accepted offer.");
                }
                a.gm().revoke_borders(recipient)?;
                a.say("Open borders revoked.", false);
                Ok(())
            },
        );
    }

    fn cycle_recipient(&mut self) {
        let game = game!(self);
        let mut r = self.recipient;
        loop {
            r = (r + 1) % 5;
            if r != game.state.active && game.alive(r) {
                break;
            }
        }
        self.recipient = r;
    }

    fn gift(&self, n: i64, source: i32, target: i32) -> String {
        if n == 0 {
            return String::new();
        }
        let game = game!(self);
        format!(
            "; {n} armies from {} to {}",
            game.name(source as usize),
            game.name(target as usize)
        )
    }

    fn offer_modal(&self) {
        let g = &self.gfx;
        let game = game!(self);
        let Ok(o) = game.offer(self.view_offer) else {
            return;
        };
        self.panel(&format!(
            "OFFER #{}  FROM {}",
            o.id,
            game.state.players[o.from].name.to_uppercase()
        ));
        let mut lines: Vec<String> = Vec::new();
        for &r in &o.give_territories {
            lines.push(format!("YOU RECEIVE TERRITORY: {}", game.name(r)));
        }
        for &r in &o.want_territories {
            lines.push(format!("YOU GIVE TERRITORY: {}", game.name(r)));
        }
        if o.give_armies > 0 {
            lines.push(format!(
                "YOU RECEIVE{}",
                self.gift(o.give_armies, o.give_source, o.give_target)
            ));
        }
        if o.want_armies > 0 {
            lines.push(format!(
                "YOU SEND{}",
                self.gift(o.want_armies, o.want_source, o.want_target)
            ));
        }
        lines.push(format!(
            "MUTUAL OPEN BORDERS: {}",
            if o.open_borders {
                "included"
            } else {
                "not included"
            }
        ));
        lines.push("Garrisons return automatically. Terms are rechecked when accepted.".into());
        let last = lines.len() - 1;
        for (i, line) in lines.iter().enumerate().skip(self.page * 7).take(7) {
            let y = 780.0 - (i - self.page * 7) as f32 * 62.0;
            g.wrap(
                GAME,
                line,
                280.0,
                y,
                1020.0,
                if i == last { MUTED } else { PAPER },
            );
        }
        if lines.len() > 7 {
            self.paging(lines.len(), 7);
        }
        let id = o.id;
        self.button(
            "ACCEPT",
            280.0,
            230.0,
            300.0,
            56.0,
            Style::Primary,
            move |a| {
                let accepted = a.gm().answer(id, true)?;
                a.say(
                    if accepted {
                        "Exchange completed."
                    } else {
                        "Offer expired because its terms changed."
                    },
                    !accepted,
                );
                a.modal = Modal::Diplomacy;
                Ok(())
            },
        );
        self.button(
            "REJECT",
            610.0,
            230.0,
            300.0,
            56.0,
            Style::Danger,
            move |a| {
                a.gm().answer(id, false)?;
                a.modal = Modal::Diplomacy;
                Ok(())
            },
        );
        self.button(
            "COUNTER",
            940.0,
            230.0,
            300.0,
            56.0,
            Style::Normal,
            move |a| {
                let o = a.g().offer(id)?.clone();
                a.draft = Offer {
                    from: o.to,
                    to: o.from,
                    give_territories: o.want_territories.clone(),
                    want_territories: o.give_territories.clone(),
                    give_source: o.want_source,
                    give_target: o.want_target,
                    give_armies: o.want_armies,
                    want_source: o.give_source,
                    want_target: o.give_target,
                    want_armies: o.give_armies,
                    open_borders: o.open_borders,
                    ..Offer::default()
                };
                a.counter_id = id;
                a.army_page = false;
                a.page = 0;
                a.modal = Modal::Compose;
                Ok(())
            },
        );
    }

    fn compose(&self) {
        let g = &self.gfx;
        let game = game!(self);
        let draft = &self.draft;
        self.panel(&format!(
            "OFFER TO {}",
            game.state.players[draft.to].name.to_uppercase()
        ));
        self.button(
            "TERRITORIES",
            280.0,
            770.0,
            480.0,
            48.0,
            if self.army_page {
                Style::Normal
            } else {
                Style::Selected
            },
            |a| {
                a.army_page = false;
                a.page = 0;
                Ok(())
            },
        );
        self.button(
            "ARMIES + OPEN BORDERS",
            780.0,
            770.0,
            470.0,
            48.0,
            if self.army_page {
                Style::Selected
            } else {
                Style::Normal
            },
            |a| {
                a.army_page = true;
                a.page = 0;
                Ok(())
            },
        );
        if !self.army_page {
            let (own, theirs) = (self.owned(draft.from), self.owned(draft.to));
            g.text(SMALL, "YOU GIVE", 280.0, 742.0, GOLD);
            g.text(SMALL, "YOU REQUEST", 780.0, 742.0, GOLD);
            let rows = own.len().max(theirs.len());
            for i in (self.page * 6)..rows.min(self.page * 6 + 6) {
                let y = 664.0 - (i - self.page * 6) as f32 * 62.0;
                if let Some(&r) = own.get(i) {
                    self.btn_sel(
                        &format!("{}  ({})", game.name(r), game.state.armies[r]),
                        280.0,
                        y,
                        470.0,
                        draft.give_territories.contains(&r),
                        move |a| {
                            toggle(&mut a.draft.give_territories, r);
                            Ok(())
                        },
                    );
                }
                if let Some(&r) = theirs.get(i) {
                    self.btn_sel(
                        &format!("{}  ({})", game.name(r), game.state.armies[r]),
                        780.0,
                        y,
                        470.0,
                        draft.want_territories.contains(&r),
                        move |a| {
                            toggle(&mut a.draft.want_territories, r);
                            Ok(())
                        },
                    );
                }
            }
            self.paging(rows, 6);
        } else {
            self.gift_editor(true, 662.0);
            self.gift_editor(false, 450.0);
            self.btn_sel(
                if draft.open_borders {
                    "MUTUAL OPEN BORDERS INCLUDED"
                } else {
                    "ADD MUTUAL OPEN BORDERS"
                },
                280.0,
                200.0,
                970.0,
                draft.open_borders,
                |a| {
                    a.draft.open_borders = !a.draft.open_borders;
                    Ok(())
                },
            );
        }
        self.button(
            "SEND OFFER",
            1050.0,
            106.0,
            300.0,
            56.0,
            Style::Primary,
            |a| {
                let draft = a.draft.clone();
                let counter = a.counter_id;
                if counter < 0 {
                    a.gm().send_offer(&draft)?;
                } else {
                    a.gm().counter(counter, &draft)?;
                }
                a.modal = Modal::Diplomacy;
                a.page = 0;
                a.say("Offer sent. The response arrives on your next turn.", false);
                Ok(())
            },
        );
    }

    fn gift_editor(&self, give: bool, y: f32) {
        let game = game!(self);
        let d = &self.draft;
        let (source, target, n) = if give {
            (d.give_source, d.give_target, d.give_armies)
        } else {
            (d.want_source, d.want_target, d.want_armies)
        };
        let (owner, receiver) = if give { (d.from, d.to) } else { (d.to, d.from) };
        self.txt(
            &format!(
                "{}{n}",
                if give {
                    "SEND ARMIES: "
                } else {
                    "REQUEST ARMIES: "
                }
            ),
            280.0,
            y + 45.0,
            GOLD,
        );
        let source_label = if source < 0 {
            "CHOOSE SOURCE".to_string()
        } else {
            format!("FROM {}", game.name(source as usize))
        };
        self.btn(&source_label, 280.0, y - 20.0, 470.0, move |a| {
            let options = a.owned(owner);
            a.open_picker(
                "ARMY SOURCE",
                options,
                Rc::new(move |a: &mut App, r: usize| {
                    let d = &mut a.draft;
                    if give {
                        d.give_source = r as i32;
                        d.give_target = -1;
                        d.give_armies = 0;
                    } else {
                        d.want_source = r as i32;
                        d.want_target = -1;
                        d.want_armies = 0;
                    }
                }),
            );
            Ok(())
        });
        let target_label = if target < 0 {
            "CHOOSE ADJACENT DESTINATION".to_string()
        } else {
            format!("TO {}", game.name(target as usize))
        };
        self.btn(&target_label, 780.0, y - 20.0, 470.0, move |a| {
            if source < 0 {
                return refuse("Choose a source first.");
            }
            let game = a.g();
            let targets: Vec<usize> = game
                .neighbours(source as usize)
                .iter()
                .copied()
                .filter(|&r| game.state.owners[r] == receiver)
                .collect();
            a.open_picker(
                "ADJACENT DESTINATION",
                targets,
                Rc::new(move |a: &mut App, r: usize| {
                    if give {
                        a.draft.give_target = r as i32;
                    } else {
                        a.draft.want_target = r as i32;
                    }
                }),
            );
            Ok(())
        });
        self.btn("−", 280.0, y - 82.0, 150.0, move |a| {
            a.set_gift(give, (n - 1).max(0))
        });
        self.btn("+", 445.0, y - 82.0, 150.0, move |a| {
            a.set_gift(give, n + 1)
        });
        self.btn("+10", 610.0, y - 82.0, 150.0, move |a| {
            a.set_gift(give, n + 10)
        });
        self.btn("MAX", 780.0, y - 82.0, 470.0, move |a| {
            let max = if source < 0 {
                0
            } else {
                a.g().state.armies[source as usize] - 1
            };
            a.set_gift(give, max)
        });
    }

    fn set_gift(&mut self, give: bool, n: i64) -> Res {
        let source = if give {
            self.draft.give_source
        } else {
            self.draft.want_source
        };
        if source < 0 {
            return refuse("Choose a source first.");
        }
        let n = n.min(game!(self).state.armies[source as usize] - 1);
        if give {
            self.draft.give_armies = n;
        } else {
            self.draft.want_armies = n;
        }
        Ok(())
    }

    fn owned(&self, p: usize) -> Vec<usize> {
        let s = &game!(self).state;
        (0..s.owners.len()).filter(|&r| s.owners[r] == p).collect()
    }

    fn open_picker(&mut self, title: &str, options: Vec<usize>, callback: PickAction) {
        self.return_modal = self.modal;
        self.pick_title = title.to_string();
        self.picks = options;
        self.pick_action = Some(callback);
        self.pick_page = 0;
        self.modal = Modal::Pick;
    }

    fn picker(&self) {
        let game = game!(self);
        self.panel(&self.pick_title);
        if self.picks.is_empty() {
            self.txt("No eligible territories.", 280.0, 740.0, MUTED);
        }
        for (i, &r) in self
            .picks
            .iter()
            .enumerate()
            .skip(self.pick_page * 18)
            .take(18)
        {
            let k = i - self.pick_page * 18;
            self.btn(
                &format!("{}  ({})", game.name(r), game.state.armies[r]),
                280.0 + (k % 3) as f32 * 340.0,
                720.0 - (k / 3) as f32 * 76.0,
                325.0,
                move |a| {
                    if let Some(action) = a.pick_action.clone() {
                        action(a, r);
                    }
                    a.modal = a.return_modal;
                    Ok(())
                },
            );
        }
        self.btn("PREVIOUS", 280.0, 106.0, 220.0, |a| {
            a.pick_page = a.pick_page.saturating_sub(1);
            Ok(())
        });
        self.btn("NEXT", 515.0, 106.0, 220.0, |a| {
            if (a.pick_page + 1) * 18 < a.picks.len() {
                a.pick_page += 1;
            }
            Ok(())
        });
        self.btn("BACK", 1050.0, 106.0, 300.0, |a| {
            a.modal = a.return_modal;
            Ok(())
        });
    }

    fn paging(&self, count: usize, per_page: usize) {
        let pages = count.div_ceil(per_page).max(1);
        if pages <= 1 {
            return;
        }
        self.button("PREVIOUS", 260.0, 106.0, 200.0, 56.0, Style::Normal, |a| {
            a.page = a.page.saturating_sub(1);
            Ok(())
        });
        self.button("NEXT", 470.0, 106.0, 200.0, 56.0, Style::Normal, move |a| {
            if (a.page + 1) * per_page < count {
                a.page += 1;
            }
            Ok(())
        });
        self.gfx.text(
            BODY,
            &format!("PAGE {} / {pages}", self.page + 1),
            690.0,
            140.0,
            MUTED,
        );
    }

    fn settings(&self) {
        self.panel("PAUSED");
        self.button("RESUME", 480.0, 650.0, 640.0, 64.0, Style::Primary, |a| {
            a.close_modal();
            Ok(())
        });
        self.button(
            &format!("SOUND: {}", if self.sound_enabled { "ON" } else { "OFF" }),
            480.0,
            566.0,
            640.0,
            64.0,
            Style::Normal,
            |a| {
                a.sound_enabled = !a.sound_enabled;
                a.store_preferences();
                Ok(())
            },
        );
        self.button(
            "HOW TO PLAY",
            480.0,
            482.0,
            640.0,
            64.0,
            Style::Normal,
            |a| {
                a.rules(Modal::Settings);
                Ok(())
            },
        );
        self.button(
            "SAVE AND EXIT TO TITLE",
            480.0,
            398.0,
            640.0,
            64.0,
            Style::Normal,
            |a| {
                if let Some(game) = &a.game {
                    a.saves.save(game)?;
                }
                a.confirm_new = false;
                a.modal = Modal::Menu;
                Ok(())
            },
        );
        self.gfx.wrap(
            BODY,
            "The game saves after every action and whenever the app is paused.\n\
             Map: drag to pan, pinch, scroll or the + and - buttons to zoom, FIT to see the whole world.\n\
             Keyboard: Enter = next step, R = roll, B = blitz, Esc = back.",
            480.0,
            320.0,
            700.0,
            MUTED,
        );
    }

    fn rules(&mut self, back: Modal) {
        self.rules_return = back;
        self.page = 0;
        self.modal = Modal::Rules;
    }

    fn rules_modal(&self) {
        let pages = [
            format!(
                "GOAL\nConquer all {} territories. Five commanders share the world; territories are dealt at \
                 random and everyone places 55 starting armies.\n\n\
                 YOUR TURN\n1  REINFORCE — tap your territories to place new armies.\n2  ATTACK — \
                 tap your territory, then an enemy neighbour, then ROLL or BLITZ.\n3  FORTIFY — \
                 move armies between your connected territories, then END TURN.",
                self.board.regions.len()
            ),
            "REINFORCEMENTS\nmax(3, lands ÷ 3) + continent bonuses + your total armies ÷ 5.\n\n\
             CONTINENT BONUSES\nNorth America 9 · Asia 8 · Europe 6 · Africa 5 · Australia 4 · \
             South America 3 · Middle-East 3. The bonus panel on the map shows who holds each continent."
                .into(),
            "BATTLES\nChoose 1–3 attack dice (you need one more army than dice). The defender rolls up to \
             two. Highest dice are compared in pairs; ties go to the defender. BLITZ keeps rolling \
             with maximum dice until you capture the territory or run out of attackers.\n\n\
             After a capture, move at least as many armies as dice rolled; one stays behind."
                .into(),
            "CARDS\nCapture at least one territory to draw a card at the end of your turn. Trade three of \
             a kind or one infantry, cavalry and artillery (wilds match anything) for 4, 6, 8, 10, \
             12, 15, then +5 armies. A card showing land you own gives +2 armies there. With five \
             or more cards you must trade.\n\n\
             DIPLOMACY\nOffer territories, armies or open borders. Open borders let armies pass \
             through allied land while fortifying; it is not a ceasefire."
                .into(),
        ];
        let n = pages.len();
        self.panel(&format!("HOW TO PLAY  ·  {} / {n}", self.page % n + 1));
        self.gfx
            .wrap(GAME, &pages[self.page % n], 280.0, 800.0, 1040.0, PAPER);
        self.button(
            "PREVIOUS",
            260.0,
            106.0,
            220.0,
            56.0,
            Style::Normal,
            move |a| {
                a.page = (a.page + n - 1) % n;
                Ok(())
            },
        );
        self.button("NEXT", 490.0, 106.0, 220.0, 56.0, Style::Normal, move |a| {
            a.page = (a.page + 1) % n;
            Ok(())
        });
        self.button("BACK", 1130.0, 106.0, 220.0, 56.0, Style::Primary, |a| {
            a.close_modal();
            Ok(())
        });
    }

    /// The app is leaving the foreground: save, and hide private cards in a hotseat match.
    pub(super) fn pause(&mut self) {
        if let Some(game) = &self.game {
            if let Err(e) = self.saves.save(game) {
                self.say(&e, true);
            }
            if self.human_turn() && self.hotseat() && self.modal != Modal::Menu {
                self.modal = Modal::Handover;
            }
        }
    }

    pub(super) fn back(&mut self) {
        if self.modal == Modal::Handover {
            return;
        }
        if self.modal == Modal::None {
            let occupying = self
                .game
                .as_ref()
                .is_some_and(|g| g.state.phase == Phase::Occupy);
            if self.human_turn() && (self.from >= 0 || self.to >= 0) && !occupying {
                self.from = -1;
                self.to = -1;
            } else {
                self.modal = Modal::Settings;
            }
        } else if self.modal != Modal::Menu {
            self.close_modal();
        }
        self.cards.clear();
    }
}

fn toggle(values: &mut Vec<usize>, r: usize) {
    if let Some(pos) = values.iter().position(|&v| v == r) {
        values.remove(pos);
    } else {
        values.push(r);
    }
}
