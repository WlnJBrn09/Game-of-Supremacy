//! The macroquad front end: an original nautical atlas on top and a command console underneath.
//! Desktop and Android run exactly the same code.

pub mod atlas;
pub mod camera;
pub mod gfx;
pub mod hud;

use crate::board::{Board, Point};
use crate::game::{BotStrategy, GameEngine, Offer, SaveStore};
use camera::AtlasCamera;
use gfx::{hex, Area, Gfx};
use hud::{Floater, Modal};
use macroquad::audio::{load_sound_from_bytes, play_sound, PlaySoundParams, Sound};
use macroquad::input::utils::{register_input_subscriber, repeat_all_miniquad_input};
use macroquad::miniquad::{self, KeyCode, KeyMods, MouseButton, TouchPhase};
use macroquad::prelude::*;
use serde::{Deserialize, Serialize};
use std::cell::RefCell;
use std::collections::HashMap;
use std::path::PathBuf;
use std::rc::Rc;
use std::sync::Arc;

/// An opaque colour from a hex `0xRRGGBB` literal.
pub const fn rgb(v: u32) -> Color {
    Color::new(
        ((v >> 16) & 255) as f32 / 255.0,
        ((v >> 8) & 255) as f32 / 255.0,
        (v & 255) as f32 / 255.0,
        1.0,
    )
}

pub const INK: Color = rgb(0x0D1C29);
pub const PANEL: Color = rgb(0x132736);
pub const OCEAN: Color = rgb(0x162E3C);
/// The warm off-white used for text and highlights.
pub const PAPER: Color = rgb(0xE8E6DB);
pub const MUTED: Color = rgb(0x89A3AF);
pub const GOLD: Color = rgb(0xE4C484);
pub const GRID: Color = rgb(0x213E4C);

/// Launch options. Desktop reads them from the command line; Android uses the defaults.
#[derive(Clone, Debug, Default)]
pub struct Options {
    /// Where the autosave and remembered setup live.
    pub data_dir: PathBuf,
    pub no_audio: bool,
    /// Fixed match seed for reproducible testing.
    pub seed: Option<u64>,
    /// Writes a PNG of the eighth frame and exits.
    pub screenshot: Option<PathBuf>,
    /// Scripted input for automated interaction checks (see `--script`).
    pub script: Vec<ScriptStep>,
}

/// One scripted interaction, performed on a given frame.
#[derive(Clone, Debug)]
pub enum ScriptStep {
    /// Tap a virtual-console position (y up).
    Tap(f32, f32),
    Key(KeyCode),
    Text(String),
    /// Wait for this many frames.
    Wait(u32),
    /// Save a screenshot.
    Shot(PathBuf),
    /// Save and close the window.
    Quit,
}

/// Remembered setup and settings.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct Preferences {
    pub humans: usize,
    pub difficulty: usize,
    pub sound: bool,
    pub names: Vec<String>,
    pub colors: Vec<i32>,
}

impl Default for Preferences {
    fn default() -> Self {
        Preferences {
            humans: 1,
            difficulty: 2,
            sound: true,
            names: vec![String::new(); 5],
            colors: (0..5).collect(),
        }
    }
}

pub type Res = Result<(), String>;
pub type Action = Rc<dyn Fn(&mut App) -> Res>;
/// Called with the chosen territory when a picker closes.
pub type PickAction = Rc<dyn Fn(&mut App, usize)>;
pub type SlideAction = Rc<dyn Fn(&mut App, f32)>;

pub struct Hit {
    pub area: Area,
    pub action: Action,
}

/// A horizontal control that reports the touch position as a 0..1 fraction while dragged.
#[derive(Clone)]
pub struct Slider {
    pub area: Area,
    pub on_change: SlideAction,
}

impl Slider {
    fn update(&self, app: &mut App, x: f32) {
        (self.on_change)(app, ((x - self.area.x) / self.area.w).clamp(0.0, 1.0));
    }
}

/// Input gathered from macroquad's event stream, replayed in order each frame.
enum Event {
    Down(usize, f32, f32),
    Drag(usize, f32, f32),
    Up(usize, f32, f32),
    Move(f32, f32),
    Scroll(f32),
    Key(KeyCode),
    Char(char),
    Pause,
}

#[derive(Default)]
struct Collector {
    events: Vec<Event>,
    touch_ids: [Option<u64>; 2],
    mouse_down: bool,
    dpi: f32,
}

impl Collector {
    fn slot(&mut self, id: u64, start: bool) -> usize {
        if let Some(i) = self.touch_ids.iter().position(|t| *t == Some(id)) {
            return i;
        }
        if start {
            if let Some(i) = self.touch_ids.iter().position(|t| t.is_none()) {
                self.touch_ids[i] = Some(id);
                return i;
            }
        }
        2 + (id as usize % 8)
    }
}

impl miniquad::EventHandler for Collector {
    fn update(&mut self) {}
    fn draw(&mut self) {}
    fn mouse_motion_event(&mut self, x: f32, y: f32) {
        let (x, y) = (x / self.dpi, y / self.dpi);
        self.events.push(if self.mouse_down {
            Event::Drag(0, x, y)
        } else {
            Event::Move(x, y)
        });
    }
    fn mouse_wheel_event(&mut self, _x: f32, y: f32) {
        self.events.push(Event::Scroll(y));
    }
    fn mouse_button_down_event(&mut self, button: MouseButton, x: f32, y: f32) {
        if button == MouseButton::Left {
            self.mouse_down = true;
            self.events.push(Event::Down(0, x / self.dpi, y / self.dpi));
        }
    }
    fn mouse_button_up_event(&mut self, button: MouseButton, x: f32, y: f32) {
        if button == MouseButton::Left && self.mouse_down {
            self.mouse_down = false;
            self.events.push(Event::Up(0, x / self.dpi, y / self.dpi));
        }
    }
    fn touch_event(&mut self, phase: TouchPhase, id: u64, x: f32, y: f32) {
        let (x, y) = (x / self.dpi, y / self.dpi);
        let slot = self.slot(id, phase == TouchPhase::Started);
        self.events.push(match phase {
            TouchPhase::Started => Event::Down(slot, x, y),
            TouchPhase::Moved => Event::Drag(slot, x, y),
            TouchPhase::Ended | TouchPhase::Cancelled => {
                if slot < 2 {
                    self.touch_ids[slot] = None;
                }
                Event::Up(slot, x, y)
            }
        });
    }
    fn char_event(&mut self, character: char, _mods: KeyMods, _repeat: bool) {
        self.events.push(Event::Char(character));
    }
    fn key_down_event(&mut self, keycode: KeyCode, _mods: KeyMods, _repeat: bool) {
        self.events.push(Event::Key(keycode));
    }
    fn window_minimized_event(&mut self) {
        self.events.push(Event::Pause);
    }
}

#[derive(Default)]
struct Pointers {
    fingers: [(f32, f32); 2],
    down: [bool; 2],
    zoom_gesture: bool,
    pinch_distance: f32,
    down_x: f32,
    down_y: f32,
    last_x: f32,
    last_y: f32,
    drag_distance: f32,
}

pub struct App {
    pub board: Arc<Board>,
    pub gfx: Gfx,
    pub camera: AtlasCamera,
    pub options: Options,
    pub prefs: Preferences,
    hits: RefCell<Vec<Hit>>,
    sliders: RefCell<Vec<Slider>>,
    dragging: Option<Slider>,
    lanes: HashMap<usize, Vec<Point>>,
    colors: Vec<Color>,
    pub mouse: (f32, f32),
    pub selected: Option<usize>,
    pub hovered: Option<usize>,
    show_routes: bool,
    show_labels: bool,
    pointers: Pointers,
    has_mouse: bool,
    frames: u32,
    dice_sound: Option<Sound>,
    win_sound: Option<Sound>,
    // ------------------------------------------------------------ HUD state
    pub saves: SaveStore,
    pub game: Option<GameEngine>,
    saved_revision: u64,
    bots: BotStrategy,
    pub modal: Modal,
    return_modal: Modal,
    humans: usize,
    difficulty: usize,
    from: i32,
    to: i32,
    page: usize,
    recipient: usize,
    counter_id: i32,
    pick_page: usize,
    amount: i64,
    dice: i64,
    last_active: i32,
    animation: f32,
    clock: f32,
    toast_at: f32,
    toast: String,
    pick_title: String,
    roll_summary: String,
    toast_error: bool,
    legend_open: bool,
    confirm_new: bool,
    shown_attack: Vec<i32>,
    shown_defend: Vec<i32>,
    income: Option<[i64; 3]>,
    report: Vec<String>,
    picks: Vec<usize>,
    pick_action: Option<PickAction>,
    cards: Vec<usize>,
    floaters: Vec<Floater>,
    draft: Offer,
    view_offer: i32,
    army_page: bool,
    sound_enabled: bool,
    log_mode: bool,
    seat_name: [String; 5],
    seat_color: [i32; 5],
    named: [bool; 5],
    editing: usize,
    edit_text: String,
    edit_flow: bool,
    edit_fresh: bool,
    shift: bool,
    rules_return: Modal,
}

impl App {
    pub async fn new(options: Options) -> App {
        let board = Arc::new(Board::bundled());
        let sans =
            load_ttf_font_from_bytes(include_bytes!("../../assets/fonts/NotoSans-Regular.ttf"))
                .expect("bundled font");
        let serif =
            load_ttf_font_from_bytes(include_bytes!("../../assets/fonts/NotoSerif-Regular.ttf"))
                .expect("bundled font");
        let (dice_sound, win_sound) = if options.no_audio {
            (None, None)
        } else {
            (
                load_sound_from_bytes(include_bytes!("../../assets/audio/dice.wav"))
                    .await
                    .ok(),
                load_sound_from_bytes(include_bytes!("../../assets/audio/conquest.wav"))
                    .await
                    .ok(),
            )
        };
        let white = PAPER;
        let colors = board
            .regions
            .iter()
            .map(|r| {
                let base = hex(&board.continents[r.continent].color);
                gfx::lerp(base, white, java_hash(&r.id).rem_euclid(4) as f32 * 0.035)
            })
            .collect();
        let mut lanes = HashMap::new();
        for (i, e) in board.connections.iter().enumerate() {
            if e.kind == crate::board::Kind::Sea {
                lanes.insert(i, atlas::curve(&board, e));
            }
        }
        let prefs = load_preferences(&options.data_dir);
        let saves = SaveStore::new(options.data_dir.join("autosave.json"));
        let mut app = App {
            board,
            gfx: Gfx::new(sans, serif),
            camera: AtlasCamera::new(),
            prefs: prefs.clone(),
            options,
            hits: RefCell::new(Vec::new()),
            sliders: RefCell::new(Vec::new()),
            dragging: None,
            lanes,
            colors,
            mouse: (-1.0, -1.0),
            selected: None,
            hovered: None,
            show_routes: true,
            show_labels: true,
            pointers: Pointers::default(),
            has_mouse: !cfg!(target_os = "android"),
            frames: 0,
            dice_sound,
            win_sound,
            saves,
            game: None,
            saved_revision: 0,
            bots: BotStrategy::new(),
            modal: Modal::Menu,
            return_modal: Modal::None,
            humans: 1,
            difficulty: 2,
            from: -1,
            to: -1,
            page: 0,
            recipient: 1,
            counter_id: -1,
            pick_page: 0,
            amount: 1,
            dice: 3,
            last_active: -1,
            animation: 0.0,
            clock: 0.0,
            toast_at: -99.0,
            toast: String::new(),
            pick_title: String::new(),
            roll_summary: String::new(),
            toast_error: false,
            legend_open: true,
            confirm_new: false,
            shown_attack: Vec::new(),
            shown_defend: Vec::new(),
            income: None,
            report: Vec::new(),
            picks: Vec::new(),
            pick_action: None,
            cards: Vec::new(),
            floaters: Vec::new(),
            draft: Offer::default(),
            view_offer: -1,
            army_page: false,
            sound_enabled: prefs.sound,
            log_mode: false,
            seat_name: std::array::from_fn(|i| format!("Player {}", i + 1)),
            seat_color: [0, 1, 2, 3, 4],
            named: [false; 5],
            editing: 0,
            edit_text: String::new(),
            edit_flow: false,
            edit_fresh: false,
            shift: false,
            rules_return: Modal::Menu,
        };
        app.load_setup();
        app
    }

    pub fn store_preferences(&mut self) {
        self.prefs.sound = self.sound_enabled;
        // Remembering setup is a convenience; never block the game on it.
        let _ = std::fs::create_dir_all(&self.options.data_dir);
        if let Ok(json) = serde_json::to_string_pretty(&self.prefs) {
            let _ = std::fs::write(self.options.data_dir.join("options.json"), json);
        }
    }

    pub fn play(&self, dice: bool, volume: f32) {
        if !self.sound_enabled {
            return;
        }
        if let Some(sound) = if dice {
            &self.dice_sound
        } else {
            &self.win_sound
        } {
            play_sound(
                sound,
                PlaySoundParams {
                    looped: false,
                    volume,
                },
            );
        }
    }

    pub fn add_hit(&self, area: Area, action: impl Fn(&mut App) -> Res + 'static) {
        self.hits.borrow_mut().push(Hit {
            area,
            action: Rc::new(action),
        });
    }

    pub fn add_slider(&self, area: Area, on_change: impl Fn(&mut App, f32) + 'static) {
        self.sliders.borrow_mut().push(Slider {
            area,
            on_change: Rc::new(on_change),
        });
    }

    pub fn clear_hits(&self) {
        self.hits.borrow_mut().clear();
        self.sliders.borrow_mut().clear();
    }

    /// Runs the game until the window closes.
    pub async fn run(mut self) {
        #[cfg(not(target_os = "android"))]
        miniquad::window::set_window_size(1600, 1000);
        simulate_mouse_with_touch(false);
        prevent_quit();
        let subscriber = register_input_subscriber();
        let mut collector = Collector::default();
        let mut script = std::mem::take(&mut self.options.script).into_iter();
        let mut wait = 0u32;
        let (mut shot, mut quit) = (None, false);
        loop {
            collector.dpi = screen_dpi_scale();
            repeat_all_miniquad_input(&mut collector, subscriber);
            self.gfx.fit();
            for event in std::mem::take(&mut collector.events) {
                self.handle(event);
            }
            if wait > 0 {
                wait -= 1;
            } else if let Some(step) = script.next() {
                match step {
                    ScriptStep::Tap(x, y) => {
                        self.touch_down(0, x, y);
                        self.touch_up(0, x, y);
                    }
                    ScriptStep::Key(k) => self.key_down(k),
                    ScriptStep::Text(t) => t.chars().for_each(|c| {
                        self.typed(c);
                    }),
                    ScriptStep::Wait(n) => wait = n,
                    ScriptStep::Shot(path) => shot = Some(path),
                    ScriptStep::Quit => quit = true,
                }
            }
            if is_quit_requested() || quit {
                self.pause();
                break;
            }
            self.render();
            self.frames += 1;
            if let Some(path) = shot.take() {
                save_screenshot(&path);
            }
            if let Some(path) = self.options.screenshot.clone() {
                if self.frames == 8 {
                    save_screenshot(&path);
                    break;
                }
            }
            next_frame().await;
        }
    }

    fn render(&mut self) {
        clear_background(INK);
        let (mx, my) = self.mouse;
        self.hovered = if self.blocks_board() || !self.has_mouse {
            None
        } else {
            self.pick(mx, my)
        };
        self.clear_hits();
        self.hud_update();
        self.selected = usize::try_from(self.focus()).ok();
        self.draw_map();
        self.hud_render();
    }

    fn virtual_point(&self, x: f32, y: f32) -> (f32, f32) {
        self.gfx.unproject(x, y)
    }

    fn handle(&mut self, event: Event) {
        match event {
            Event::Down(p, x, y) => {
                let (x, y) = self.virtual_point(x, y);
                self.mouse = (x, y);
                self.touch_down(p, x, y)
            }
            Event::Drag(p, x, y) => {
                let (x, y) = self.virtual_point(x, y);
                self.mouse = (x, y);
                self.touch_dragged(p, x, y)
            }
            Event::Up(p, x, y) => {
                let (x, y) = self.virtual_point(x, y);
                self.touch_up(p, x, y)
            }
            Event::Move(x, y) => self.mouse = self.virtual_point(x, y),
            Event::Scroll(dy) => {
                let (mx, my) = self.mouse;
                if !self.blocks_board() && self.camera.inside(mx, my) && dy != 0.0 {
                    self.camera.zoom_at(1.18f32.powf(dy.signum()), mx, my);
                }
            }
            Event::Key(k) => self.key_down(k),
            Event::Char(c) => {
                self.typed(c);
            }
            Event::Pause => self.pause(),
        }
    }

    fn touch_down(&mut self, pointer: usize, x: f32, y: f32) {
        let p = &mut self.pointers;
        if pointer < 2 {
            p.fingers[pointer] = (x, y);
            p.down[pointer] = true;
            if p.down[0] && p.down[1] {
                p.zoom_gesture = true;
                p.pinch_distance = dist(p.fingers[0], p.fingers[1]);
                return;
            }
        }
        p.down_x = x;
        p.last_x = x;
        p.down_y = y;
        p.last_y = y;
        p.drag_distance = 0.0;
        self.dragging = self
            .sliders
            .borrow()
            .iter()
            .rev()
            .find(|s| s.area.contains(x, y))
            .cloned();
        if let Some(slider) = self.dragging.clone() {
            slider.update(self, x);
        }
    }

    fn touch_dragged(&mut self, pointer: usize, x: f32, y: f32) {
        if pointer < 2 {
            self.pointers.fingers[pointer] = (x, y);
        }
        let p = &mut self.pointers;
        if p.down[0] && p.down[1] {
            let distance = dist(p.fingers[0], p.fingers[1]);
            let (cx, cy) = (
                (p.fingers[0].0 + p.fingers[1].0) / 2.0,
                (p.fingers[0].1 + p.fingers[1].1) / 2.0,
            );
            let ratio = if p.pinch_distance > 1.0 {
                Some(distance / p.pinch_distance)
            } else {
                None
            };
            p.pinch_distance = distance;
            if let (Some(ratio), false) = (ratio, self.blocks_board()) {
                self.camera.zoom_at(ratio, cx, cy);
            }
            return;
        }
        if p.zoom_gesture {
            return;
        }
        if let Some(slider) = self.dragging.clone() {
            slider.update(self, x);
            return;
        }
        let p = &mut self.pointers;
        p.drag_distance = p.drag_distance.max(dist((p.down_x, p.down_y), (x, y)));
        let (dx, dy) = (x - p.last_x, y - p.last_y);
        let (down_x, down_y, far) = (p.down_x, p.down_y, p.drag_distance > 4.0);
        p.last_x = x;
        p.last_y = y;
        if !self.blocks_board() && self.camera.inside(down_x, down_y) && far {
            self.camera.pan(dx, dy);
        }
    }

    fn touch_up(&mut self, pointer: usize, x: f32, y: f32) {
        let p = &mut self.pointers;
        if pointer < 2 {
            p.down[pointer] = false;
        }
        if p.zoom_gesture {
            if !p.down[0] && !p.down[1] {
                p.zoom_gesture = false;
            }
            return;
        }
        if self.dragging.take().is_some() || p.drag_distance > 4.0 {
            return;
        }
        let action = self
            .hits
            .borrow()
            .iter()
            .rev()
            .find(|h| h.area.contains(x, y))
            .map(|h| h.action.clone());
        if let Some(action) = action {
            self.run_action(move |app| action(app));
            return;
        }
        if !self.blocks_board() && self.camera.inside(x, y) {
            match self.pick(x, y) {
                Some(r) => self.tap(r),
                None => self.tap_ocean(),
            }
        }
    }

    fn key_down(&mut self, key: KeyCode) {
        if matches!(key, KeyCode::Escape | KeyCode::Back) {
            self.back();
            return;
        }
        if self.edit_key(key) || self.blocks_board() || self.key(key) {
            return;
        }
        match key {
            KeyCode::L => self.show_labels = !self.show_labels,
            KeyCode::S => self.show_routes = !self.show_routes,
            KeyCode::Home => self.camera.reset(),
            KeyCode::Equal | KeyCode::KpAdd => self.camera.zoom_at(1.25, 800.0, 606.0),
            KeyCode::Minus | KeyCode::KpSubtract => self.camera.zoom_at(0.8, 800.0, 606.0),
            _ => {}
        }
    }
}

fn dist(a: (f32, f32), b: (f32, f32)) -> f32 {
    ((a.0 - b.0).powi(2) + (a.1 - b.1).powi(2)).sqrt()
}

/// Java's `String.hashCode`, which fixes each territory's subtle tint.
pub fn java_hash(value: &str) -> i32 {
    value
        .encode_utf16()
        .fold(0i32, |h, c| h.wrapping_mul(31).wrapping_add(c as i32))
}

fn load_preferences(dir: &std::path::Path) -> Preferences {
    std::fs::read_to_string(dir.join("options.json"))
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

fn save_screenshot(path: &std::path::Path) {
    let image = get_screen_data();
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    image.export_png(&path.to_string_lossy());
}

/// Where saves live on this platform when no directory is given: the app's private files
/// directory on Android, `~/.supremacy` elsewhere.
pub fn default_data_dir() -> PathBuf {
    #[cfg(target_os = "android")]
    {
        android_files_dir()
            .unwrap_or_else(|| PathBuf::from("/data/data/com.supremacy.game/files"))
            .join("supremacy")
    }
    #[cfg(not(target_os = "android"))]
    {
        std::env::var_os("HOME")
            .or_else(|| std::env::var_os("USERPROFILE"))
            .map(PathBuf::from)
            .unwrap_or_else(|| PathBuf::from("."))
            .join(".supremacy")
    }
}

/// `Context.getFilesDir()` of the running activity.
#[cfg(target_os = "android")]
fn android_files_dir() -> Option<PathBuf> {
    use macroquad::miniquad::native::android::{attach_jni_env, ACTIVITY};
    use macroquad::miniquad::{call_object_method, get_utf_str};
    unsafe {
        let env = attach_jni_env();
        let activity = ACTIVITY;
        if env.is_null() || activity.is_null() {
            return None;
        }
        let dir = call_object_method!(env, activity, "getFilesDir", "()Ljava/io/File;");
        if dir.is_null() {
            return None;
        }
        let path = call_object_method!(env, dir, "getAbsolutePath", "()Ljava/lang/String;");
        if path.is_null() {
            return None;
        }
        Some(PathBuf::from(get_utf_str!(env, path)))
    }
}

pub fn window_conf() -> Conf {
    Conf {
        window_title: "Supremacy | Offline Conquest".to_owned(),
        window_width: 1600,
        window_height: 1000,
        high_dpi: true,
        fullscreen: cfg!(target_os = "android"),
        sample_count: 4,
        window_resizable: true,
        ..Default::default()
    }
}
