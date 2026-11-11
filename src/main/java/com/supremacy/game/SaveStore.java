package com.supremacy.game;

import com.badlogic.gdx.utils.Json;
import com.supremacy.board.Board;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Atomic autosave with a previous known-good backup; random state is part of the save. */
public final class SaveStore {
  private final Path path;
  private volatile String warning = "";

  public SaveStore(Path path) {
    this.path = path;
  }

  public boolean exists() {
    return Files.exists(path) || Files.exists(backup());
  }

  public String warning() {
    return warning;
  }

  private Path backup() {
    return path.resolveSibling(path.getFileName() + ".bak");
  }

  public void save(GameEngine game) {
    write(game.board, snapshot(game));
  }

  /**
   * Validates and serialises the match. Cheap; call on the thread that owns the engine. The result
   * can be written later, on another thread, with {@link #write}.
   */
  public String snapshot(GameEngine game) {
    try {
      game.validate();
      return new Json().toJson(game.state);
    } catch (RuntimeException e) {
      warning = "Autosave failed: " + e.getMessage();
      throw new IllegalStateException(warning, e);
    }
  }

  /** Writes a snapshot atomically, keeping the previous valid save as a backup. Thread-safe. */
  public synchronized void write(Board board, String data) {
    try {
      Path parent = path.toAbsolutePath().getParent();
      Files.createDirectories(parent);
      Path temp = path.resolveSibling(path.getFileName() + ".tmp");
      Files.write(
          temp,
          data.getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);
      // Verify the bytes before replacing a valid save or its backup.
      new GameEngine(
          board,
          new Json()
              .fromJson(
                  GameState.class, new String(Files.readAllBytes(temp), StandardCharsets.UTF_8)));
      if (Files.exists(path)) {
        try {
          new GameEngine(
              board,
              new Json()
                  .fromJson(
                      GameState.class,
                      new String(Files.readAllBytes(path), StandardCharsets.UTF_8)));
          Files.copy(path, backup(), StandardCopyOption.REPLACE_EXISTING);
        } catch (RuntimeException ignored) {
          /* Keep the last known-good backup. */
        }
      }
      try {
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
      }
      warning = "";
    } catch (IOException | RuntimeException e) {
      warning = "Autosave failed: " + e.getMessage();
      throw new IllegalStateException(warning, e);
    }
  }

  public GameEngine load(Board board) {
    RuntimeException failure = null;
    for (Path candidate : new Path[] {path, backup()}) {
      if (!Files.exists(candidate)) continue;
      try {
        GameEngine engine =
            new GameEngine(
                board,
                new Json()
                    .fromJson(
                        GameState.class,
                        new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8)));
        warning =
            candidate.equals(path) ? "" : "Recovered the previous autosave after a damaged save.";
        return engine;
      } catch (IOException | RuntimeException e) {
        failure = new IllegalStateException("Cannot read autosave: " + e.getMessage(), e);
      }
    }
    throw failure == null ? new IllegalStateException("No saved match") : failure;
  }
}
