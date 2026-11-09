//! Desktop launcher. Android starts the same `main` through macroquad's activity glue.

use std::path::PathBuf;
use supremacy::ui::{default_data_dir, window_conf, App, Options, ScriptStep};

const USAGE: &str = "Usage: supremacy [--save-dir DIR] [--seed N] [--no-audio] [--screenshot FILE.png] [--script STEPS]

  --save-dir DIR       Where the autosave and remembered setup live (default ~/.supremacy).
  --seed N             Start new matches from a fixed seed (reproducible testing).
  --no-audio           Do not load sound effects.
  --screenshot FILE    Render eight frames, save a PNG and exit.
  --script STEPS       Scripted input for automated checks, separated by ';':
                         tap X Y | key NAME | text WORDS | wait FRAMES | shot FILE.png | quit
                       X and Y are positions on the 1600x1000 console with y pointing up.

Environment variables SUPREMACY_SAVE_DIR, SUPREMACY_SEED and SUPREMACY_NO_AUDIO=1 work too.";

fn key(name: &str) -> Option<macroquad::miniquad::KeyCode> {
    use macroquad::miniquad::KeyCode::*;
    Some(match name.to_ascii_lowercase().as_str() {
        "enter" => Enter,
        "escape" | "esc" => Escape,
        "back" => Back,
        "space" => Space,
        "backspace" => Backspace,
        "home" => Home,
        "plus" | "equal" => Equal,
        "minus" => Minus,
        "b" => B,
        "r" => R,
        "l" => L,
        "s" => S,
        _ => return None,
    })
}

fn script(steps: &str) -> Result<Vec<ScriptStep>, String> {
    let mut out = Vec::new();
    for step in steps.split(';').map(str::trim).filter(|s| !s.is_empty()) {
        let (verb, rest) = step.split_once(' ').unwrap_or((step, ""));
        let rest = rest.trim();
        out.push(match verb {
            "tap" => {
                let v: Vec<f32> = rest
                    .split_whitespace()
                    .filter_map(|n| n.parse().ok())
                    .collect();
                match v[..] {
                    [x, y] => ScriptStep::Tap(x, y),
                    _ => return Err(format!("tap needs X Y: {step}")),
                }
            }
            "key" => ScriptStep::Key(key(rest).ok_or(format!("unknown key: {rest}"))?),
            "text" => ScriptStep::Text(rest.to_string()),
            "wait" => ScriptStep::Wait(
                rest.parse()
                    .map_err(|_| format!("wait needs frames: {step}"))?,
            ),
            "shot" => ScriptStep::Shot(PathBuf::from(rest)),
            "quit" => ScriptStep::Quit,
            _ => return Err(format!("unknown script step: {step}")),
        });
    }
    Ok(out)
}

fn options() -> Result<Options, String> {
    let env = |k: &str| std::env::var(k).ok().filter(|v| !v.is_empty());
    let mut o = Options {
        data_dir: env("SUPREMACY_SAVE_DIR")
            .map(PathBuf::from)
            .unwrap_or_else(default_data_dir),
        no_audio: env("SUPREMACY_NO_AUDIO").is_some_and(|v| v == "1" || v == "true"),
        seed: env("SUPREMACY_SEED").and_then(|v| v.parse().ok()),
        ..Options::default()
    };
    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        let mut value = || args.next().ok_or(format!("{arg} needs a value"));
        match arg.as_str() {
            "--save-dir" => o.data_dir = PathBuf::from(value()?),
            "--seed" => o.seed = Some(value()?.parse().map_err(|_| "--seed needs a number")?),
            "--no-audio" => o.no_audio = true,
            "--screenshot" => o.screenshot = Some(PathBuf::from(value()?)),
            "--script" => o.script = script(&value()?)?,
            "-h" | "--help" => return Err(String::new()),
            other => return Err(format!("unknown option {other}")),
        }
    }
    Ok(o)
}

#[macroquad::main(window_conf)]
async fn main() {
    let options = match options() {
        Ok(o) => o,
        Err(e) => {
            if !e.is_empty() {
                eprintln!("{e}\n");
            }
            eprintln!("{USAGE}");
            std::process::exit(if e.is_empty() { 0 } else { 2 });
        }
    };
    App::new(options).await.run().await;
}
