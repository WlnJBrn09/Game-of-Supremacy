//! Atomic autosave with a previous known-good backup; random state is part of the save.

use super::engine::GameEngine;
use super::state::GameState;
use crate::board::Board;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Arc;

pub struct SaveStore {
    path: PathBuf,
    warning: String,
}

fn sibling(path: &Path, suffix: &str) -> PathBuf {
    let mut name = path.file_name().unwrap_or_default().to_os_string();
    name.push(suffix);
    path.with_file_name(name)
}

fn read(board: &Arc<Board>, path: &Path) -> Result<GameEngine, String> {
    let data = fs::read_to_string(path).map_err(|e| e.to_string())?;
    let state: GameState = serde_json::from_str(&data).map_err(|e| e.to_string())?;
    GameEngine::restore(board.clone(), state).map_err(|e| e.0)
}

impl SaveStore {
    pub fn new(path: impl Into<PathBuf>) -> Self {
        SaveStore {
            path: path.into(),
            warning: String::new(),
        }
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    pub fn exists(&self) -> bool {
        self.path.exists() || self.backup().exists()
    }

    /// Set when the last load had to fall back to the backup, or the last save failed.
    pub fn warning(&self) -> &str {
        &self.warning
    }

    fn backup(&self) -> PathBuf {
        sibling(&self.path, ".bak")
    }

    pub fn save(&mut self, game: &GameEngine) -> Result<(), String> {
        match self.try_save(game) {
            Ok(()) => {
                self.warning.clear();
                Ok(())
            }
            Err(e) => {
                self.warning = format!("Autosave failed: {e}");
                Err(self.warning.clone())
            }
        }
    }

    fn try_save(&self, game: &GameEngine) -> Result<(), String> {
        game.validate().map_err(|e| e.0)?;
        if let Some(parent) = self.path.parent().filter(|p| !p.as_os_str().is_empty()) {
            fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        let data = serde_json::to_string(&game.state).map_err(|e| e.to_string())?;
        let temp = sibling(&self.path, ".tmp");
        fs::write(&temp, data.as_bytes()).map_err(|e| e.to_string())?;
        // Verify the bytes before replacing a valid save or its backup.
        read(&game.board, &temp)?;
        if self.path.exists() && read(&game.board, &self.path).is_ok() {
            // Keep the last known-good backup if the current save is damaged.
            fs::copy(&self.path, self.backup()).map_err(|e| e.to_string())?;
        }
        fs::rename(&temp, &self.path).map_err(|e| e.to_string())
    }

    pub fn load(&mut self, board: &Arc<Board>) -> Result<GameEngine, String> {
        let mut failure = None;
        for candidate in [self.path.clone(), self.backup()] {
            if !candidate.exists() {
                continue;
            }
            match read(board, &candidate) {
                Ok(engine) => {
                    self.warning = if candidate == self.path {
                        String::new()
                    } else {
                        "Recovered the previous autosave after a damaged save.".into()
                    };
                    return Ok(engine);
                }
                Err(e) => failure = Some(format!("Cannot read autosave: {e}")),
            }
        }
        Err(failure.unwrap_or_else(|| "No saved match".into()))
    }
}
