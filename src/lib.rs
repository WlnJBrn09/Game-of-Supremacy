//! Game of Supremacy: an offline five-player conquest board game.
//!
//! [`board`] holds geography, [`game`] the rules, bots and saves, and [`ui`] the macroquad
//! front end shared by the desktop and Android builds.

// Index loops and wide drawing signatures mirror the board-game geometry they implement.
#![allow(clippy::needless_range_loop, clippy::too_many_arguments)]

pub mod board;
pub mod game;
pub mod ui;

/// Android entry point, called by miniquad's activity once the native library loads.
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "C" fn quad_main() {
    macroquad::Window::from_config(ui::window_conf(), async {
        let options = ui::Options {
            data_dir: ui::default_data_dir(),
            ..Default::default()
        };
        ui::App::new(options).await.run().await;
    });
}
