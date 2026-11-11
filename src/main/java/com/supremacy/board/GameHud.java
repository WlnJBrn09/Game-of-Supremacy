package com.supremacy.board;

import static com.supremacy.game.GameState.Phase.*;

import com.badlogic.gdx.*;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.math.MathUtils;
import com.supremacy.game.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Pass-and-play interaction layer. On phones the map fills the screen beside a command dock; on
 * tablets and desktops a console sits under the map. Every action delegates to the shared rules
 * engine; computer turns are played back on the map at a chosen speed.
 */
final class GameHud {
  enum Modal {
    NONE,
    MENU,
    PLAYER,
    HANDOVER,
    LOG,
    CARDS,
    DIPLOMACY,
    OFFER,
    COMPOSE,
    PICK,
    SETTINGS,
    RULES,
    ROSTER,
    RESULT,
    TUTORIAL
  }

  /** How a territory is emphasised on the map this frame. */
  enum Role {
    NORMAL,
    DIM,
    HINT,
    OPTION,
    SOURCE,
    TARGET
  }

  /** A rising +/- number over a territory; repeated changes within a moment add up. */
  static final class Floater {
    final int region;
    final Color color;
    final String sign;
    float born;
    long amount;

    Floater(int region, String sign, long amount, Color color, float born) {
      this.region = region;
      this.sign = sign;
      this.amount = amount;
      this.color = color;
      this.born = born;
    }

    String text() {
      return sign + amount;
    }
  }

  static final Color RED = Color.valueOf("E0625A"),
      GREEN = Color.valueOf("7DC383"),
      ERROR_BG = Color.valueOf("3A1B1E"),
      ERROR_TEXT = Color.valueOf("F2A39D");
  static final int[] PLACE_STEPS = {1, 3, 5, 0};
  static final String[] SKILL_NOTES = {
    "Learning the ropes", "Casual", "Balanced", "Plans ahead", "Ruthless", "Relentless"
  };

  final AtlasApp app;
  final Draw d;
  final Screens screens;
  final SaveStore saves;
  GameEngine game;
  BotStrategy bots;
  Modal modal = Modal.MENU;

  // Turn interaction.
  int from = -1, to = -1, dice = 3, lastActive = -1, placeStep = 0;
  long amount = 1;
  float animation, clock, toastAt = -99;
  String toast = "", rollSummary = "";
  boolean toastError;
  int[] shownAttack = new int[0], shownDefend = new int[0];
  long[] income;
  final List<Integer> cards = new ArrayList<>();
  private final List<Floater> floaters = new ArrayList<>();

  // Recap of what changed since the active human's last turn, and the territory being inspected.
  Set<Integer> recapLost = Set.of(), recapGained = Set.of();
  boolean recapOpen;
  int recapEvents, inspect = -1;
  Board.Continent spotlight;
  boolean legendOpen;

  // Computer-turn playback.
  private float botTimer, nextDelay;
  boolean skipping, resultShown, watching;
  int arrowA = -1, arrowB = -1;
  private Color arrowTint = RED;
  private float arrowAt = -99, diceSoundAt = -99;
  String botAction = "";

  // Background autosave.
  private final ExecutorService saver =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "autosave");
            t.setDaemon(true);
            return t;
          });
  private Future<?> pendingSave;
  private boolean dirty, acted;
  private float lastSaveAt;
  private int savedActive = -1;
  private GameState.Phase savedPhase;
  private volatile String saveError;

  // Options.
  boolean soundEnabled, hapticsEnabled, fastBots, continentView, tutorialSeen;
  private Sound diceSound, winSound, placeSound, victorySound, defeatSound;

  // Layout, recomputed every frame from the screen size.
  boolean compact;
  float dockW, topH, consoleH, mapX, mapY, mapW, mapH;

  GameHud(AtlasApp app) {
    this.app = app;
    d = new Draw(app, this::action);
    String dir =
        System.getProperty("supremacy.saveDir", Gdx.files.getLocalStoragePath() + "supremacy");
    saves = new SaveStore(java.nio.file.Paths.get(dir, "autosave.json"));
    Preferences prefs = prefs();
    soundEnabled = prefs.getBoolean("sound", true);
    hapticsEnabled = prefs.getBoolean("haptics", true);
    fastBots = prefs.getBoolean("fastBots", false);
    continentView = prefs.getBoolean("continentView", false);
    tutorialSeen = prefs.getBoolean("tutorialSeen", false);
    screens = new Screens(this);
    screens.loadSetup();
    diceSound = sound("audio/dice.wav");
    winSound = sound("audio/conquest.wav");
    placeSound = sound("audio/place.wav");
    victorySound = sound("audio/victory.wav");
    defeatSound = sound("audio/defeat.wav");
    if (System.getProperty("supremacy.demo") != null) screens.startDemo();
  }

  static Preferences prefs() {
    return Gdx.app.getPreferences("supremacy-options");
  }

  private static Sound sound(String path) {
    try {
      return Gdx.files.internal(path).exists() ? Gdx.audio.newSound(Gdx.files.internal(path)) : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  void play(Sound s, float volume) {
    if (soundEnabled && s != null) s.play(volume);
  }

  void haptic(int ms) {
    if (hapticsEnabled && Gdx.app.getType() == Application.ApplicationType.Android)
      Gdx.input.vibrate(ms);
  }

  void attach(GameEngine engine) {
    game = engine;
    bots = new BotStrategy(game);
    game.onChange(
        () -> {
          dirty = true;
          Gdx.graphics.requestRendering();
        });
    game.onEvent(this::onEvent);
    saveNow();
    from = to = -1;
    cards.clear();
    floaters.clear();
    lastActive = -1;
    resultShown = game.state.phase == FINISHED;
    watching = skipping = false;
    recapLost = recapGained = Set.of();
    recapOpen = false;
    inspect = -1;
    spotlight = null;
    modal = Modal.NONE;
    legendOpen = !compactNow() && prefs().getBoolean("legendOpen", true);
    app.camera.reset();
    checkTurn();
  }

  private boolean compactNow() {
    return app.H < 560 || app.W > app.H * 1.95f;
  }

  // ---------------------------------------------------------------- state helpers

  void action(Runnable command) {
    try {
      command.run();
      acted = true;
      checkTurn();
    } catch (IllegalArgumentException | IllegalStateException | ArithmeticException e) {
      say(e.getMessage(), true);
    }
    Gdx.graphics.requestRendering();
  }

  void say(String message, boolean error) {
    toast = message == null ? "" : message;
    toastError = error;
    toastAt = clock;
    if (error) haptic(25);
  }

  static void refuse(String reason) {
    throw new IllegalStateException(reason);
  }

  boolean hotseat() {
    int n = 0;
    for (GameState.Player p : game.state.players) if (p.human) n++;
    return n > 1;
  }

  boolean anyHumanAlive() {
    for (int p = 0; p < 5; p++) if (game.state.players.get(p).human && game.alive(p)) return true;
    return false;
  }

  void checkTurn() {
    if (game == null) return;
    GameState s = game.state;
    if (!resultShown && (s.phase == FINISHED || !anyHumanAlive())) {
      resultShown = true;
      from = to = -1;
      recapOpen = false;
      if (modal == Modal.NONE || modal == Modal.HANDOVER) modal = Modal.RESULT;
      toast = "";
      boolean humanWon = s.phase == FINISHED && s.players.get(s.winner).human;
      play(humanWon ? victorySound : defeatSound, .6f);
      haptic(humanWon ? 60 : 120);
    }
    if (s.active != lastActive) {
      lastActive = s.active;
      from = to = -1;
      amount = 1;
      cards.clear();
      inspect = -1;
      shownAttack = shownDefend = new int[0];
      rollSummary = "";
      income = null;
      if (s.phase == REINFORCE && s.reserve == game.income(s.active)) {
        long lands = Math.max(3, game.count(s.active) / 3), continents = game.continentIncome(s.active);
        income = new long[] {lands, continents, s.reserve - lands - continents};
      }
      if (s.players.get(s.active).human && s.phase != FINISHED) {
        skipping = false;
        if (hotseat()) {
          modal = Modal.HANDOVER;
          toast = ""; // the previous player's messages are not for the next one
        } else startHumanTurn();
      }
    }
  }

  /** Called when a human takes the device: recap what changed and frame the losses. */
  void startHumanTurn() {
    modal = Modal.NONE;
    GameState s = game.state;
    List<List<Integer>> changes = game.changesSinceLastSeen(s.active);
    recapLost = new LinkedHashSet<>(changes.get(0));
    recapGained = new LinkedHashSet<>(changes.get(1));
    recapEvents = screens.reportLines().size();
    recapOpen = s.phase != SETUP && (!recapLost.isEmpty() || !recapGained.isEmpty() || recapEvents > 0);
    if (s.lastReadTurn[s.active] < s.turn) game.markRead();
    if (!recapLost.isEmpty()) frame(recapLost);
    else app.camera.animateReset();
    if (!tutorialSeen && (s.phase == SETUP || s.phase == REINFORCE)) modal = Modal.TUTORIAL;
  }

  /** Hides the recap card and its red/green outlines once the player has seen them or moved. */
  void dismissRecap() {
    recapOpen = false;
    recapLost = recapGained = Set.of();
  }

  void frame(Collection<Integer> regions) {
    float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
    for (int r : regions) {
      var a = app.board.regions.get(r).anchor;
      minX = Math.min(minX, a.x);
      maxX = Math.max(maxX, a.x);
      minY = Math.min(minY, a.y);
      maxY = Math.max(maxY, a.y);
    }
    if (maxX - minX > 900) app.camera.animateReset(); // spans the Pacific wrap: show everything
    else app.camera.frame(minX, minY, maxX, maxY);
  }

  boolean humanTurn() {
    return game != null
        && game.state.players.get(game.state.active).human
        && game.state.phase != FINISHED;
  }

  boolean interactive() {
    return humanTurn() && modal == Modal.NONE && animation <= 0;
  }

  boolean blocksBoard() {
    return modal != Modal.NONE || game == null;
  }

  float time() {
    return clock;
  }

  List<Floater> floaters() {
    return floaters;
  }

  boolean continentMode() {
    return continentView;
  }

  Board.Continent highlightContinent() {
    return spotlight;
  }

  Color recapColor(int r) {
    if (!humanTurn() || game.state.active != lastActive) return null;
    if (recapLost.contains(r)) return RED;
    if (recapGained.contains(r)) return GREEN;
    return null;
  }

  boolean animating() {
    return animation > 0
        || modal == Modal.PLAYER
        || !floaters.isEmpty()
        || clock - toastAt < 3.6f
        || clock - arrowAt < 1.2f
        || pendingSave != null && !pendingSave.isDone()
        || (game != null && !humanTurn() && modal == Modal.NONE && game.state.phase != FINISHED)
        || (interactive()
            && (from >= 0 || game.state.phase == OCCUPY || !recapLost.isEmpty() || !recapGained.isEmpty()));
  }

  /** The region whose sea lanes the map highlights. */
  int focus() {
    if (game == null) return -1;
    if (game.state.phase == OCCUPY && humanTurn()) return game.state.occupationTo;
    if (inspect >= 0) return inspect;
    return humanTurn() ? from : -1;
  }

  int arrowFrom() {
    if (game == null) return -1;
    if (humanTurn())
      return game.state.phase == OCCUPY ? game.state.occupationFrom : to >= 0 ? from : -1;
    return clock - arrowAt < 1.2f ? arrowA : -1;
  }

  int arrowTo() {
    if (game == null) return -1;
    if (humanTurn()) return game.state.phase == OCCUPY ? game.state.occupationTo : to;
    return clock - arrowAt < 1.2f ? arrowB : -1;
  }

  Color arrowColor() {
    return humanTurn() ? actionColor() : arrowTint;
  }

  /** Deployment only fades enemy land a little; targeting fades everything irrelevant. */
  float dimStrength() {
    return game != null && (game.state.phase == SETUP || game.state.phase == REINFORCE) ? .3f : .5f;
  }

  Color actionColor() {
    return game != null && (game.state.phase == ATTACK || game.state.phase == OCCUPY)
        ? RED
        : AtlasApp.GOLD;
  }

  boolean canAttackFrom(int r) {
    GameState s = game.state;
    if (s.owners[r] != s.active || s.armies[r] < 2) return false;
    for (int n : game.neighbours(r)) if (s.owners[n] != s.active) return true;
    return false;
  }

  /** An enemy neighbour clearly outnumbers this territory of the player whose turn it is. */
  boolean threatened(int r) {
    if (!humanTurn() || modal != Modal.NONE) return false;
    GameState s = game.state;
    if (s.owners[r] != s.active || s.phase == OCCUPY || (s.phase == ATTACK && from >= 0)) return false;
    if (s.phase == FORTIFY && from >= 0) return false;
    long strongest = 0;
    for (int n : game.neighbours(r)) if (s.owners[n] != s.active) strongest = Math.max(strongest, s.armies[n]);
    return strongest >= s.armies[r] + 3;
  }

  int threatCount() {
    int n = 0;
    for (int r = 0; r < game.state.owners.length; r++) if (threatened(r)) n++;
    return n;
  }

  Role role(int r) {
    if (game == null || !humanTurn() || modal != Modal.NONE) return Role.NORMAL;
    GameState s = game.state;
    boolean own = s.owners[r] == s.active;
    switch (s.phase) {
      case SETUP, REINFORCE -> {
        if (r == from) return Role.SOURCE;
        if (!own) return Role.DIM;
        return s.reserve > 0 ? Role.HINT : Role.NORMAL;
      }
      case ATTACK -> {
        if (from < 0) return canAttackFrom(r) ? Role.HINT : Role.NORMAL;
        if (r == from) return Role.SOURCE;
        if (r == to) return Role.TARGET;
        return game.canAttack(from, r) ? Role.OPTION : Role.DIM;
      }
      case OCCUPY -> {
        if (r == s.occupationFrom) return Role.SOURCE;
        return r == s.occupationTo ? Role.TARGET : Role.DIM;
      }
      case FORTIFY -> {
        if (from < 0) return own && game.movable(r) > 0 ? Role.HINT : Role.NORMAL;
        if (r == from) return Role.SOURCE;
        if (r == to) return Role.TARGET;
        return own && game.connected(from, r, s.active) ? Role.OPTION : Role.DIM;
      }
      default -> {
        return Role.NORMAL;
      }
    }
  }

  // ---------------------------------------------------------------- events and playback

  private void pop(int region, String sign, long n, Color color) {
    if (region < 0 || n == 0 || skipping) return;
    for (Floater f : floaters)
      if (f.region == region && f.sign.equals(sign) && f.color.equals(color) && clock - f.born < .45f) {
        f.amount += n;
        f.born = clock;
        return;
      }
    floaters.add(new Floater(region, sign, n, color, clock));
  }

  private void onEvent(GameEngine.Event e) {
    GameState s = game.state;
    boolean human = s.players.get(s.active).human;
    float delay = .1f;
    switch (e.type()) {
      case DEPLOY -> {
        pop(e.to(), "+", e.amount(), AtlasApp.GOLD);
        if (human) play(placeSound, .5f);
        else botAction = s.players.get(s.active).name + " reinforces " + game.name(e.to()) + ".";
        delay = .22f;
        follow(e.to());
      }
      case ATTACK -> {
        pop(e.from(), "-", e.attackerLoss(), RED);
        pop(e.to(), "-", e.defenderLoss(), RED);
        boolean newFront = e.from() != arrowA || e.to() != arrowB || clock - arrowAt > 1.5f;
        arrowA = e.from();
        arrowB = e.to();
        arrowAt = clock;
        arrowTint = Color.valueOf(s.color(s.active));
        if (clock - diceSoundAt > .14f && !skipping) {
          play(diceSound, human ? .45f : .25f);
          diceSoundAt = clock;
        }
        if (!human)
          botAction =
              s.players.get(s.active).name
                  + " attacks "
                  + game.name(e.to())
                  + " ("
                  + s.players.get(s.owners[e.to()]).name
                  + ") from "
                  + game.name(e.from())
                  + ".";
        delay = newFront ? .5f : .13f;
        follow(e.to());
      }
      case CAPTURE -> {
        play(winSound, human ? .5f : .3f);
        int victim = (int) e.amount();
        if (victim >= 0 && s.players.get(victim).human) haptic(40);
        if (human) haptic(30);
        if (!human)
          botAction =
              s.players.get(s.active).name
                  + " captures "
                  + game.name(e.to())
                  + " from "
                  + s.players.get(victim).name
                  + ".";
        delay = .45f;
      }
      case OCCUPY -> {
        pop(e.to(), "+", e.amount(), AtlasApp.GOLD);
        delay = .3f;
      }
      case FORTIFY -> {
        pop(e.from(), "-", e.amount(), AtlasApp.MUTED);
        pop(e.to(), "+", e.amount(), AtlasApp.GOLD);
        if (!human)
          botAction =
              s.players.get(s.active).name
                  + " moves "
                  + e.amount()
                  + " from "
                  + game.name(e.from())
                  + " to "
                  + game.name(e.to())
                  + ".";
        arrowA = e.from();
        arrowB = e.to();
        arrowAt = clock;
        arrowTint = AtlasApp.GOLD;
        delay = .45f;
        follow(e.to());
      }
      case TRADE -> {
        if (e.to() >= 0) pop(e.to(), "+", 2, AtlasApp.GOLD);
        if (!human) botAction = s.players.get(s.active).name + " trades cards for " + e.amount() + " armies.";
        delay = .6f;
      }
      case ELIMINATED -> {
        String loser = s.players.get(e.player()).name, winner = s.players.get(e.from()).name;
        say(loser + " was eliminated by " + winner + ".", false);
        delay = 1.2f;
      }
      case UNDO -> pop(e.to(), e.amount() > 0 ? "+" : "-", Math.abs(e.amount()), AtlasApp.MUTED);
      case TURN -> delay = .5f;
      default -> {}
    }
    nextDelay = Math.max(nextDelay, delay);
  }

  /** Keeps computer moves on screen without fighting a player who is looking elsewhere. */
  private void follow(int region) {
    if (humanTurn() || skipping) return;
    var a = app.board.regions.get(region).anchor;
    app.camera.reveal(a.x, a.y, 40);
  }

  private void playBots(float dt) {
    GameState s = game.state;
    if (s.phase == FINISHED || modal != Modal.NONE || s.players.get(s.active).human) return;
    if (skipping) {
      long start = System.nanoTime();
      while (!humanTurn() && s.phase != FINISHED && modal == Modal.NONE
          && System.nanoTime() - start < 18_000_000L) stepBot();
      floaters.clear();
      arrowA = arrowB = -1;
      return;
    }
    botTimer -= dt;
    if (botTimer <= 0) stepBot();
  }

  private void stepBot() {
    nextDelay = .06f;
    try {
      bots.step();
    } catch (RuntimeException e) {
      say("Computer player paused: " + e.getMessage(), true);
      modal = Modal.SETTINGS;
    }
    botTimer = nextDelay * (fastBots ? .3f : 1f);
    checkTurn();
  }

  // ---------------------------------------------------------------- saving

  /** Saves at meaningful moments rather than after every dice roll, off the render thread. */
  private void maybeSave() {
    if (!dirty || game == null) return;
    GameState s = game.state;
    boolean boundary = s.active != savedActive || s.phase != savedPhase || s.phase == OCCUPY;
    if (!(acted || boundary || clock - lastSaveAt > 4)) return;
    if (pendingSave != null && !pendingSave.isDone() && !boundary) return;
    saveAsync();
  }

  private void saveAsync() {
    try {
      String data = saves.snapshot(game);
      Board board = app.board;
      pendingSave =
          saver.submit(
              () -> {
                try {
                  saves.write(board, data);
                } catch (RuntimeException e) {
                  saveError = e.getMessage();
                }
              });
      markSaved();
    } catch (RuntimeException e) {
      say(e.getMessage(), true);
      dirty = false;
    }
  }

  private void markSaved() {
    dirty = acted = false;
    lastSaveAt = clock;
    savedActive = game.state.active;
    savedPhase = game.state.phase;
  }

  /** Saves and waits for the disk, e.g. when the app goes to the background. */
  void saveNow() {
    if (game == null) return;
    try {
      String data = saves.snapshot(game);
      Board board = app.board;
      saver.submit(() -> saves.write(board, data)).get(5, TimeUnit.SECONDS);
      markSaved();
    } catch (ExecutionException e) {
      say(e.getCause().getMessage(), true);
    } catch (InterruptedException | TimeoutException | RuntimeException e) {
      say("Autosave failed: " + e.getMessage(), true);
    }
  }

  void waitForSaves() {
    try {
      if (pendingSave != null) pendingSave.get(5, TimeUnit.SECONDS);
    } catch (InterruptedException | ExecutionException | TimeoutException ignored) {
      // A failed background save already reported itself.
    }
  }

  // ---------------------------------------------------------------- map input

  /** A tap on a territory. Returns true when it was a game move (not just a look). */
  boolean tap(int r) {
    if (game == null) return false;
    if (!interactive()) {
      inspect(r);
      return false;
    }
    try {
      boolean move = choose(r);
      if (move) {
        inspect = -1;
        acted = true;
        checkTurn();
      }
      return move;
    } catch (IllegalArgumentException | IllegalStateException e) {
      say(e.getMessage(), true);
      return false;
    } finally {
      Gdx.graphics.requestRendering();
    }
  }

  void tapOcean() {
    inspect = -1;
    spotlight = null;
    if (interactive() && game.state.phase != OCCUPY) from = to = -1;
  }

  void inspect(int r) {
    inspect = inspect == r ? -1 : r;
  }

  private boolean choose(int r) {
    GameState s = game.state;
    boolean own = s.owners[r] == s.active;
    switch (s.phase) {
      case SETUP, REINFORCE -> {
        if (!own) {
          inspect(r);
          return false;
        }
        if (s.reserve == 0) {
          inspect(r);
          say(
              s.phase == SETUP
                  ? "All armies placed. Tap FINISH PLACEMENT."
                  : "No armies left to place. Trade cards or start attacking.",
              false);
          return false;
        }
        place(r);
        return true;
      }
      case ATTACK -> {
        if (own) {
          if (r == from) {
            from = to = -1;
            return true;
          }
          if (!canAttackFrom(r)) {
            inspect(r);
            say(
                s.armies[r] < 2
                    ? game.name(r) + " needs at least 2 armies to attack."
                    : game.name(r) + " has no enemy neighbours.",
                false);
            return false;
          }
          from = r;
          to = -1;
        } else if (from >= 0 && game.canAttack(from, r)) {
          to = to == r ? -1 : r;
        } else {
          // Tapping an enemy first picks your strongest bordering territory for you.
          int best = -1;
          for (int n : game.neighbours(r))
            if (s.owners[n] == s.active && s.armies[n] > 1 && (best < 0 || s.armies[n] > s.armies[best]))
              best = n;
          if (best < 0) {
            inspect(r);
            say("None of your territories with 2+ armies borders " + game.name(r) + ".", false);
            return false;
          }
          from = best;
          to = r;
        }
        dice = (int) Math.min(3, s.armies[from] - 1);
        closeIn(from);
        return true;
      }
      case OCCUPY -> {
        say("First choose how many armies move into " + game.name(s.occupationTo) + ".", false);
        return false;
      }
      case FORTIFY -> {
        if (!own) {
          inspect(r);
          return false;
        }
        if (r == from) {
          from = to = -1;
        } else if (from >= 0 && game.connected(from, r, s.active)) {
          to = r;
          amount = game.movable(from);
        } else {
          if (game.movable(r) <= 0) {
            inspect(r);
            say(
                s.armies[r] < 2
                    ? game.name(r) + " has only one army; it must stay."
                    : "Armies in " + game.name(r) + " already moved this turn.",
                false);
            return false;
          }
          from = r;
          to = -1;
          closeIn(from);
        }
        return true;
      }
      default -> {
        return false;
      }
    }
  }

  /** On small screens, zooms in on a chosen territory and its neighbours if they are too small to tap. */
  private void closeIn(int r) {
    if (!compact || app.camera.zoom >= 2.2f) return;
    List<Integer> area = new ArrayList<>();
    area.add(r);
    for (int n : game.neighbours(r))
      if (Math.abs(app.board.regions.get(n).anchor.x - app.board.regions.get(r).anchor.x) < 400) area.add(n);
    frame(area);
  }

  boolean key(int key) {
    if (!interactive()) {
      if (game != null && !humanTurn() && key == Input.Keys.SPACE) {
        skipping = true;
        return true;
      }
      return false;
    }
    GameState s = game.state;
    switch (key) {
      case Input.Keys.ENTER, Input.Keys.SPACE -> {
        if (s.phase == FORTIFY && from >= 0 && to >= 0) action(this::fortify);
        else action(this::primary);
        return true;
      }
      case Input.Keys.B -> {
        if (s.phase == ATTACK && from >= 0 && to >= 0) action(() -> roll(true));
        return true;
      }
      case Input.Keys.R -> {
        if (s.phase == ATTACK && from >= 0 && to >= 0) action(() -> roll(false));
        return to >= 0;
      }
      case Input.Keys.U, Input.Keys.Z -> {
        if (game.canUndo()) action(this::undo);
        return true;
      }
      case Input.Keys.A -> {
        if ((s.phase == SETUP || s.phase == REINFORCE) && s.reserve > 0) action(this::autoPlace);
        return true;
      }
      default -> {
        return false;
      }
    }
  }

  boolean modalKey(int key) {
    if (key == Input.Keys.ENTER && modal == Modal.HANDOVER) {
      startHumanTurn();
      return true;
    }
    return false;
  }

  boolean typed(char c) {
    return screens.typed(c);
  }

  boolean editKey(int key) {
    return screens.editKey(key);
  }

  // ---------------------------------------------------------------- commands

  int placeAmount() {
    long reserve = game.state.reserve;
    int step = PLACE_STEPS[placeStep];
    return (int) Math.min(reserve, step == 0 ? reserve : step);
  }

  private void place(int r) {
    GameState s = game.state;
    long n = Math.max(1, Math.min(s.reserve, PLACE_STEPS[placeStep] == 0 ? s.reserve : PLACE_STEPS[placeStep]));
    game.deploy(r, n);
    dismissRecap();
    from = r;
    afterPlacing();
  }

  private void afterPlacing() {
    GameState s = game.state;
    if (s.reserve == 0) {
      from = -1;
      if (s.phase == REINFORCE
          && game.findSet(s.active).isEmpty()
          && s.players.get(s.active).cards.size() < 5) {
        game.finishDeployment();
        say("All armies placed. Attack! (UNDO takes back the last placement.)", false);
      }
    }
  }

  void autoPlace() {
    GameState s = game.state;
    if (s.reserve <= 0) refuse("No armies left to place.");
    BotStrategy.Placement plan = bots.suggestDeployment();
    if (plan.regions().length == 0) refuse("No placement to suggest.");
    game.deployPlan(plan.regions(), plan.amounts());
    dismissRecap();
    from = -1;
    say(
        "Placed on "
            + plan.regions().length
            + (plan.regions().length == 1 ? " territory" : " territories")
            + ". UNDO puts them back.",
        false);
    afterPlacing();
  }

  void undo() {
    game.undo();
    from = to = -1;
    say("Undone.", false);
  }

  void roll(boolean blitz) {
    GameState s = game.state;
    if (from < 0 || to < 0) refuse("Pick your territory, then an enemy neighbour.");
    int f = from, t = to, rolls = 0;
    long a0 = s.armies[f], d0 = s.armies[t];
    String target = game.name(t);
    dismissRecap();
    do {
      int n = (int) Math.min(blitz ? 3 : dice, s.armies[f] - 1);
      game.attack(f, t, n);
      rolls++;
    } while (blitz && s.phase == ATTACK && s.armies[f] > 1);
    shownAttack = s.attackDice.clone();
    shownDefend = s.defendDice.clone();
    long lostA = a0 - s.armies[f], lostD = d0 - s.armies[t];
    animation = .35f;
    boolean captured = s.phase == OCCUPY || s.phase == FINISHED || s.owners[t] == s.active;
    rollSummary =
        (blitz ? "Blitz · " + rolls + (rolls == 1 ? " roll · " : " rolls · ") : "")
            + "You lost "
            + lostA
            + " · They lost "
            + lostD;
    if (captured) {
      amount = s.phase == OCCUPY ? s.armies[f] - 1 : 0;
      if (s.phase != FINISHED) say(target + " captured!", false);
    } else if (s.armies[f] < 2) {
      say(game.name(f) + " has no armies left to attack with.", false);
      from = to = -1;
    } else dice = (int) Math.min(dice, s.armies[f] - 1);
  }

  void occupy() {
    GameState s = game.state;
    int f = s.occupationFrom, t = s.occupationTo;
    long n = Math.max(s.occupationMinimum, Math.min(amount, s.armies[f] - 1));
    game.occupy(n);
    to = -1;
    // Keep the push going from the newly captured territory when it can attack again.
    if (s.phase == ATTACK) {
      from = canAttackFrom(t) ? t : canAttackFrom(f) ? f : -1;
      if (from >= 0) dice = (int) Math.min(3, s.armies[from] - 1);
    } else from = -1;
  }

  void fortify() {
    if (from < 0 || to < 0) refuse("Pick a territory to move from, then a destination.");
    long n = Math.max(1, Math.min(amount, game.movable(from)));
    game.fortify(from, to, n);
    say("Moved " + n + " from " + game.name(from) + " to " + game.name(to) + ".", false);
    from = to = -1;
  }

  void trade() {
    GameState s = game.state;
    List<Integer> set = cards.size() == 3 ? new ArrayList<>(cards) : game.findSet(s.active);
    if (set.isEmpty()) refuse("You do not have a matching set yet.");
    long before = s.reserve;
    game.tradeCards(new ArrayList<>(set));
    cards.clear();
    say("Cards traded for " + (s.reserve - before) + " armies.", false);
  }

  void primary() {
    GameState s = game.state;
    dismissRecap();
    switch (s.phase) {
      case SETUP -> {
        if (s.reserve > 0) refuse("Place all " + s.reserve + " remaining armies first (or tap AUTO).");
        from = -1;
        game.finishDeployment();
      }
      case REINFORCE -> {
        if (s.players.get(s.active).cards.size() >= 5)
          refuse("You hold five or more cards. Trade a set first.");
        if (s.reserve > 0) refuse("Place all " + s.reserve + " remaining armies first (or tap AUTO).");
        from = -1;
        game.finishDeployment();
      }
      case ATTACK -> {
        from = to = -1;
        game.finishAttacks();
      }
      case OCCUPY -> occupy();
      case FORTIFY -> {
        from = to = -1;
        game.endTurn();
      }
      default -> {}
    }
  }

  // ---------------------------------------------------------------- frame

  /** Sizes the map for this frame: beside a dock on phones, above a console elsewhere. */
  void layoutMap() {
    float W = app.W, H = app.H;
    compact = compactNow();
    if (compact) {
      dockW = Draw.clamp(W * .33f, 264, 340);
      mapX = 0;
      mapY = 0;
      mapW = W - dockW;
      mapH = H;
      app.camera.setRect(0, 0, mapW, H);
      app.camera.setInsets(0, 0, game != null ? 42 : 0, 0);
    } else {
      topH = 52;
      consoleH = Draw.clamp(H * .26f, 200, 250);
      mapX = 0;
      mapY = consoleH;
      mapW = W;
      mapH = H - consoleH - topH;
      app.camera.setRect(0, consoleH, W, mapH);
      app.camera.setInsets(0, 0, 0, 0);
    }
  }

  void render() {
    float dt = Math.min(.1f, Gdx.graphics.getDeltaTime());
    clock += dt;
    if (game != null) {
      checkTurn();
      playBots(dt);
      if (animation > 0) animation = Math.max(0, animation - dt);
      floaters.removeIf(f -> clock - f.born > 1.4f);
      maybeSave();
      if (saveError != null) {
        say(saveError, true);
        saveError = null;
      }
    }
    mapOverlay();
    if (compact) dock();
    else {
      topBar();
      console();
    }
    if (modal != Modal.NONE) {
      app.hits.clear();
      app.sliders.clear();
      screens.draw();
    }
    drawToast();
  }

  // ---------------------------------------------------------------- shared pieces

  String phaseName(GameState.Phase p) {
    return switch (p) {
      case SETUP -> "PLACE ARMIES";
      case REINFORCE -> "REINFORCE";
      case ATTACK -> "ATTACK";
      case OCCUPY -> "OCCUPY";
      case FORTIFY -> "FORTIFY";
      case FINISHED -> "GAME OVER";
    };
  }

  Color playerColor(int p) {
    return Color.valueOf(game.state.color(p));
  }

  static float mid(BitmapFont f, float y, float h) {
    return y + h / 2 + f.getCapHeight() / 2;
  }

  private void headerButtons(float x, float y, float w, float h) {
    boolean human = humanTurn();
    int hand = human ? game.state.players.get(game.state.active).cards.size() : 0;
    boolean set = human && !game.findSet(game.state.active).isEmpty();
    int inbox = human ? game.inbox().size() : 0;
    float gap = 6, bw = (w - 3 * gap) / 4;
    d.button(human ? "CARDS " + hand : "CARDS", x, y, bw, h, set ? Draw.Style.SELECTED : Draw.Style.NORMAL, () -> open(Modal.CARDS));
    d.button(
        inbox > 0 ? (compact ? "DEALS " : "DIPLOMACY ") + inbox : compact ? "DEALS" : "DIPLOMACY",
        x + bw + gap,
        y,
        bw,
        h,
        inbox > 0 ? Draw.Style.SELECTED : Draw.Style.NORMAL,
        () -> open(Modal.DIPLOMACY));
    d.button("LOG", x + 2 * (bw + gap), y, bw, h, Draw.Style.NORMAL, () -> open(Modal.LOG));
    d.button("MENU", x + 3 * (bw + gap), y, bw, h, Draw.Style.NORMAL, () -> open(Modal.SETTINGS));
  }

  /** Three-step phase track. */
  private void stepper(float x, float y, float w, float h) {
    GameState s = game.state;
    if (s.phase == SETUP) {
      d.round(x, y, w, h, h / 2, AtlasApp.GOLD);
      d.fitCenter(app.small, "INITIAL PLACEMENT · " + game.startingArmies() + " ARMIES EACH", x + w / 2, mid(app.small, y, h), w - 16, AtlasApp.INK);
      return;
    }
    String[] steps = compact ? new String[] {"PLACE", "ATTACK", "MOVE"} : new String[] {"1  REINFORCE", "2  ATTACK", "3  FORTIFY"};
    int current =
        switch (s.phase) {
          case REINFORCE -> 0;
          case ATTACK, OCCUPY -> 1;
          case FORTIFY -> 2;
          default -> 3;
        };
    float gap = 8, sw = (w - 2 * gap) / 3;
    for (int i = 0; i < 3; i++) {
      float sx = x + i * (sw + gap);
      Color fill = i == current ? AtlasApp.GOLD : i < current ? Draw.RAISED : Draw.OFF;
      Color ink = i == current ? AtlasApp.INK : i < current ? AtlasApp.MUTED : AtlasApp.alpha(AtlasApp.MUTED, .7f);
      d.round(sx, y, sw, h, h / 2, fill);
      d.fitCenter(compact ? app.small : app.body, steps[i], sx + sw / 2, mid(compact ? app.small : app.body, y, h), sw - 10, ink);
    }
  }

  /** The main button and, when possible, UNDO beside it. */
  private void primaryRow(float x, float y, float w, float h) {
    GameState s = game.state;
    if (s.phase == FINISHED) {
      d.button("RESULTS", x, y, w * .42f, h, Draw.Style.NORMAL, () -> modal = Modal.RESULT);
      d.button("NEW GAME", x + w * .42f + 8, y, w * .58f - 8, h, Draw.Style.PRIMARY, () -> modal = Modal.MENU);
      return;
    }
    if (!humanTurn()) {
      if (modal != Modal.NONE) return;
      d.button(fastBots ? "SPEED: FAST" : "SPEED: NORMAL", x, y, w * .46f, h, Draw.Style.NORMAL, this::toggleSpeed);
      d.button(
          anyHumanAlive() ? "SKIP TO MY TURN" : "SKIP TO END",
          x + w * .46f + 8,
          y,
          w * .54f - 8,
          h,
          skipping ? Draw.Style.SELECTED : Draw.Style.PRIMARY,
          () -> skipping = true);
      return;
    }
    int hand = s.players.get(s.active).cards.size();
    String label;
    Draw.Style style = Draw.Style.PRIMARY;
    switch (s.phase) {
      case SETUP -> {
        label = s.reserve > 0 ? "PLACE " + s.reserve + " MORE" : "FINISH PLACEMENT";
        if (s.reserve > 0) style = Draw.Style.DISABLED;
      }
      case REINFORCE -> {
        if (hand >= 5) {
          label = "TRADE CARDS FIRST";
          style = Draw.Style.DISABLED;
        } else if (s.reserve > 0) {
          label = "PLACE " + s.reserve + " MORE";
          style = Draw.Style.DISABLED;
        } else label = "START ATTACKING";
      }
      case ATTACK -> label = "END ATTACKS";
      case OCCUPY -> label = "MOVE " + Math.max(s.occupationMinimum, Math.min(amount, s.armies[s.occupationFrom] - 1)) + " IN";
      case FORTIFY -> label = "END TURN";
      default -> label = "";
    }
    float uw = game.canUndo() ? Math.min(96, w * .3f) : 0;
    if (uw > 0) d.button("UNDO", x, y, uw, h, Draw.Style.NORMAL, this::undo);
    d.button(label, x + (uw > 0 ? uw + 8 : 0), y, w - (uw > 0 ? uw + 8 : 0), h, style, this::primary);
  }

  void toggleSpeed() {
    fastBots = !fastBots;
    prefs().putBoolean("fastBots", fastBots).flush();
  }

  /** Big reserve count, income or card status. Returns the height used from {@code top}. */
  private float status(float x, float top, float w) {
    GameState s = game.state;
    if (!humanTurn()) return 0;
    switch (s.phase) {
      case SETUP, REINFORCE -> {
        float bigH = compact ? 30 : 38;
        BitmapFont f = compact ? app.heading : app.big;
        String n = Long.toString(s.reserve);
        d.text(f, n, x, top - (bigH - f.getCapHeight()) / 2, AtlasApp.GOLD);
        float nx = x + app.width(f, n) + 10;
        d.fit(app.body, s.reserve == 1 ? "ARMY TO PLACE" : "ARMIES TO PLACE", nx, mid(app.body, top - bigH, bigH), w - (nx - x), AtlasApp.WHITE);
        float used = bigH;
        if (income != null && s.phase == REINFORCE) {
          d.fit(
              app.small,
              "Lands +" + income[0] + " · Continents +" + income[1] + " · Population +" + income[2],
              x,
              top - used - 2,
              w,
              AtlasApp.MUTED);
          used += 18;
        }
        return used;
      }
      case ATTACK, OCCUPY -> {
        String note =
            s.conquered
                ? "You captured land: you will draw a card."
                : "Capture a territory this turn to draw a card.";
        return d.wrap(app.small, note, x, top, w, s.conquered ? GREEN : AtlasApp.MUTED) + 4;
      }
      case FORTIFY -> {
        String note = "Optional: move armies, then END TURN." + (s.conquered ? " You will draw a card." : "");
        return d.wrap(app.small, note, x, top, w, AtlasApp.MUTED) + 4;
      }
      default -> {
        return 0;
      }
    }
  }

  // ---------------------------------------------------------------- phone dock

  private void dock() {
    float W = app.W, H = app.H, x = W - dockW, pad = 8, ix = x + pad, iw = dockW - 2 * pad;
    d.box(x, 0, dockW, H, Draw.CONSOLE);
    d.box(x, 0, 1.5f, H, Draw.LINE);
    float y = H - pad - 40;
    if (game == null) {
      d.wrap(app.body, "Open the menu to start or resume a game.", ix, H / 2, iw, AtlasApp.MUTED);
      d.button("MENU", ix, pad, iw, 48, Draw.Style.PRIMARY, () -> modal = Modal.MENU);
      return;
    }
    headerButtons(ix, y, iw, 40);
    GameState s = game.state;
    y -= 8 + 32;
    int shown = s.phase == FINISHED ? s.winner : s.active;
    d.token(ix + 11, y + 16, 9, playerColor(shown));
    float nameW = d.fit(app.heading, s.players.get(shown).name, ix + 28, mid(app.heading, y, 32), iw - 110, playerColor(shown));
    d.text(app.small, s.phase == FINISHED ? "WINNER" : "TURN " + Math.max(1, s.turn), ix + 36 + nameW, mid(app.small, y, 32), AtlasApp.MUTED);
    String who = s.players.get(shown).human ? "HUMAN" : "CPU · " + s.difficulty.name().toUpperCase(Locale.ROOT);
    d.right(app.small, who, ix + iw, mid(app.small, y, 32), AtlasApp.MUTED);
    y -= 6 + 22;
    if (s.phase != FINISHED) stepper(ix, y, iw, 22);
    float bottom = pad, primH = 48;
    primaryRow(ix, bottom, iw, primH);
    float top = y - 10, base = bottom + primH + 8;
    float used = status(ix, top, iw);
    if (used > 0) top -= used + 6;
    actionArea(ix, base, iw, top - base);
  }

  // ---------------------------------------------------------------- tablet and desktop console

  private void topBar() {
    float W = app.W, H = app.H, y = H - topH;
    d.box(0, y, W, topH, Draw.CONSOLE);
    d.box(0, y, W, 2, Draw.LINE);
    d.text(app.heading, "SUPREMACY", 16, mid(app.heading, y, topH), AtlasApp.WHITE);
    float bw = Math.min(520, W * .42f);
    if (game != null) {
      GameState s = game.state;
      float tx = 30 + app.width(app.heading, "SUPREMACY");
      d.text(app.body, "TURN " + Math.max(1, s.turn), tx, mid(app.body, y, topH), AtlasApp.MUTED);
      if (s.phase != FINISHED) {
        float px = tx + app.width(app.body, "TURN 000") + 16;
        d.token(px + 8, y + topH / 2, 8, playerColor(s.active));
        d.fit(app.gameFont, s.players.get(s.active).name + "  ·  " + phaseName(s.phase), px + 24, mid(app.gameFont, y, topH), W - bw - px - 40, AtlasApp.WHITE);
      }
      headerButtons(W - bw - 12, y + 6, bw, topH - 12);
    } else d.button("MENU", W - 140, y + 6, 128, topH - 12, Draw.Style.NORMAL, () -> modal = Modal.MENU);
  }

  private void console() {
    float W = app.W, C = consoleH;
    d.box(0, 0, W, C, Draw.CONSOLE);
    d.box(0, C - 2, W, 2, Draw.LINE);
    float rw = Draw.clamp(W * .25f, 250, 400), cw = Draw.clamp(W * .23f, 240, 360);
    float ax = 24 + rw, aw = W - rw - cw - 48;
    d.round(12, 12, rw, C - 24, 12, AtlasApp.PANEL);
    d.round(ax, 12, aw, C - 24, 12, AtlasApp.PANEL);
    d.round(W - 12 - cw, 12, cw, C - 24, 12, AtlasApp.PANEL);
    if (game == null) {
      d.center(app.gameFont, "Open the menu to start or resume a game.", W / 2, C / 2 + 8, AtlasApp.MUTED);
      return;
    }
    roster(24, 16, rw - 24, C - 32);
    GameState s = game.state;
    float top = C - 24;
    // The phase track needs height; on shorter screens the top bar's phase name does the job.
    if (s.phase != FINISHED && C >= 230) {
      stepper(ax + 14, top - 28, aw - 28, 28);
      top -= 40;
    }
    actionArea(ax + 16, 22, aw - 32, top - 22);
    // Command column: who is playing, the big number, and the main button.
    float cx = W - cw, ccw = cw - 24, ct = C - 24;
    int shown = s.phase == FINISHED ? s.winner : s.active;
    d.fit(app.heading, s.players.get(shown).name, cx, ct - 4, ccw, playerColor(shown));
    d.text(
        app.small,
        s.phase == FINISHED ? "WINNER" : s.players.get(shown).human ? "HUMAN COMMANDER" : "COMPUTER · " + s.difficulty.name().toUpperCase(Locale.ROOT),
        cx,
        ct - 34,
        AtlasApp.MUTED);
    status(cx, ct - 50, ccw);
    primaryRow(cx - 2, 24, ccw + 4, 56);
  }

  private void roster(float x, float y, float w, float h) {
    GameState s = game.state;
    float row = Math.min(36, (h - 18) / 5);
    float top = y + h;
    d.text(app.small, "COMMANDERS", x + 4, top - 2, AtlasApp.MUTED);
    float cLands = x + w - 112, cArmies = x + w - 52, cCards = x + w - 6;
    d.right(app.small, "LANDS", cLands, top - 2, AtlasApp.MUTED);
    d.right(app.small, "ARMIES", cArmies, top - 2, AtlasApp.MUTED);
    d.right(app.small, "CARDS", cCards, top - 2, AtlasApp.MUTED);
    for (int p = 0; p < 5; p++) {
      int player = p;
      float ry = top - 18 - (p + 1) * row;
      boolean alive = game.alive(p), active = p == s.active && s.phase != FINISHED;
      if (active) {
        d.round(x - 4, ry + 1, w + 8, row - 2, 7, Draw.RAISED_HI);
        d.round(x - 4, ry + 1, 5, row - 2, 2.5f, playerColor(p));
      }
      d.token(x + 14, ry + row / 2, 8, alive ? playerColor(p) : AtlasApp.alpha(AtlasApp.MUTED, .5f));
      GameState.Player pl = s.players.get(p);
      float nw = d.fit(app.gameFont, pl.name, x + 30, mid(app.gameFont, ry, row), cLands - x - 110, alive ? AtlasApp.WHITE : AtlasApp.MUTED);
      d.text(app.small, pl.human ? "YOU" : "CPU", x + 36 + nw, mid(app.small, ry, row), AtlasApp.MUTED);
      if (!alive) {
        d.right(app.body, "OUT", cCards, mid(app.body, ry, row), AtlasApp.MUTED);
        continue;
      }
      d.right(app.body, Integer.toString(game.count(p)), cLands, mid(app.body, ry, row), AtlasApp.WHITE);
      d.right(app.body, Long.toString(game.total(p)), cArmies, mid(app.body, ry, row), AtlasApp.WHITE);
      d.right(app.body, Integer.toString(pl.cards.size()), cCards, mid(app.body, ry, row), AtlasApp.WHITE);
      d.hit(x - 4, ry, w + 8, row, "player " + pl.name, () -> screens.openDiplomacyWith(player));
    }
  }

  // ---------------------------------------------------------------- action area (both layouts)

  private void actionArea(float x, float y, float w, float h) {
    GameState s = game.state;
    float top = y + h;
    if (s.phase == FINISHED) {
      d.wrap(compact ? app.body : app.gameFont, s.players.get(s.winner).name + " conquered the world in " + s.turn + " turns.", x, top, w, AtlasApp.GOLD);
      return;
    }
    if (!humanTurn()) {
      String who = s.players.get(s.active).name;
      float used = d.wrap(compact ? app.body : app.gameFont, watching || !anyHumanAlive() ? "Watching " + who + "…" : who + " is taking their turn…", x, top, w, AtlasApp.WHITE);
      if (!botAction.isEmpty() && !skipping)
        used += 6 + d.wrap(app.small, botAction, x, top - used - 6, w, AtlasApp.MUTED);
      if (!compact)
        d.wrap(app.small, "Moves play out on the map. Change the speed, or skip ahead.", x, top - used - 12, w, AtlasApp.alpha(AtlasApp.MUTED, .8f));
      return;
    }
    switch (s.phase) {
      case SETUP, REINFORCE -> deployArea(x, y, w, top);
      case ATTACK -> attackArea(x, y, w, top);
      case OCCUPY -> occupyArea(x, y, w, top);
      case FORTIFY -> fortifyArea(x, y, w, top);
      default -> {}
    }
  }

  private float instruction(String message, float x, float top, float w, Color c) {
    return d.wrap(compact ? app.body : app.gameFont, message, x, top, w, c) + 8;
  }

  private void deployArea(float x, float y, float w, float top) {
    GameState s = game.state;
    int hand = s.players.get(s.active).cards.size();
    boolean canTrade = s.phase == REINFORCE && !game.findSet(s.active).isEmpty();
    float row = compact ? 40 : 44, gap = 6;
    if (s.reserve == 0) {
      String m =
          s.phase == SETUP
              ? "All armies placed. Tap FINISH PLACEMENT."
              : hand >= 5 ? "You hold " + hand + " cards: trade a set before attacking." : "All armies placed. Trade cards for more, or start attacking.";
      top -= instruction(m, x, top, w, AtlasApp.GOLD);
      if (canTrade) d.button("TRADE CARDS +" + game.nextSetValue(), x, top - row, Math.min(w, 300), row, Draw.Style.SELECTED, this::trade);
      return;
    }
    String m = from >= 0 ? "Placed on " + game.name(from) + ". Each tap places:" : "Tap your gold-ringed land. Each tap places:";
    BitmapFont f = compact ? app.body : app.gameFont;
    d.fit(f, m, x, top, w, AtlasApp.WHITE);
    top -= f.getLineHeight() + 6;
    // Amount chips, with AUTO beside them when the panel is wide enough, otherwise underneath.
    float cw = Draw.clamp((w - 104 - 4 * gap) / 4, 46, 64);
    boolean sameRow = w - 4 * (cw + gap) >= 96;
    if (!sameRow) cw = Math.min(64, (w - 3 * gap) / 4);
    String[] labels = {"1", "3", "5", "ALL"};
    for (int i = 0; i < 4; i++) {
      int step = i;
      d.button(labels[i], x + i * (cw + gap), top - row, cw, row, placeStep == i ? Draw.Style.SELECTED : Draw.Style.NORMAL, () -> placeStep = step);
    }
    String auto = s.phase == SETUP ? "AUTO-PLACE" : "AUTO";
    if (sameRow) {
      float ax = x + 4 * (cw + gap) + 4;
      d.button(auto, ax, top - row, Math.min(220, x + w - ax), row, Draw.Style.NORMAL, this::autoPlace);
      top -= row + 8;
      if (canTrade && top - row >= y) {
        d.button("TRADE CARDS +" + game.nextSetValue(), x, top - row, Math.min(w, 300), row, hand >= 5 ? Draw.Style.PRIMARY : Draw.Style.SELECTED, this::trade);
        top -= row + 8;
      }
    } else {
      top -= row + 8;
      float half = (w - 8) / 2;
      d.button(auto, x, top - row, canTrade ? half : Math.min(w, 220), row, Draw.Style.NORMAL, this::autoPlace);
      if (canTrade)
        d.button("TRADE +" + game.nextSetValue(), x + half + 8, top - row, half, row, hand >= 5 ? Draw.Style.PRIMARY : Draw.Style.SELECTED, this::trade);
      top -= row + 8;
    }
    int threats = threatCount();
    if (threats > 0 && top - 14 >= y - 4)
      d.fit(app.small, "! " + threats + (threats == 1 ? " border is" : " borders are") + " clearly outnumbered (red marks).", x, top - 2, w, ERROR_TEXT);
  }

  private void attackArea(float x, float y, float w, float top) {
    GameState s = game.state;
    if (from >= 0 && (s.owners[from] != s.active || s.armies[from] < 2)) from = to = -1;
    if (to >= 0 && (from < 0 || !game.canAttack(from, to))) to = -1;
    float row = compact ? 40 : 44;
    if (from < 0) {
      top -= instruction("Tap a gold-ringed territory to attack from, or tap an enemy to attack it.", x, top, w, AtlasApp.WHITE);
      if (!compact)
        d.wrap(app.small, "Done attacking? END ATTACKS moves on to fortifying.", x, top, w, AtlasApp.MUTED);
      lastRoll(x, y, w);
      return;
    }
    if (to < 0) {
      top -= instruction("Attack from " + game.name(from) + " (" + s.armies[from] + "). Pick a target:", x, top, w, AtlasApp.WHITE);
      List<Integer> targets = new ArrayList<>();
      for (int n : game.neighbours(from)) if (game.canAttack(from, n)) targets.add(n);
      targets.sort(Comparator.comparingLong(n -> s.armies[n]));
      int cols = Math.max(2, Math.min(4, (int) ((w + 6) / 150)));
      float gap = 6, bw = (w - (cols - 1) * gap) / cols;
      int rows = Math.max(1, (int) ((top - y + gap) / (row + gap)));
      int shown = Math.min(targets.size(), cols * rows - 1);
      for (int i = 0; i <= shown; i++) {
        float bx = x + (i % cols) * (bw + gap), by = top - row - (i / cols) * (row + gap);
        if (i == shown) {
          d.button("CANCEL", bx, by, bw, row, Draw.Style.NORMAL, () -> from = to = -1);
          break;
        }
        int t = targets.get(i);
        d.button(game.name(t) + " " + s.armies[t], bx, by, bw, row, Draw.Style.NORMAL, () -> to = t);
        d.token(bx + 9, by + row - 9, 4.5f, playerColor(s.owners[t]));
      }
      return;
    }
    // Battle: who fights whom, the odds, dice and the roll buttons.
    BitmapFont f = compact ? app.body : app.gameFont;
    float lh = compact ? 20 : 26;
    String left = game.name(from) + " " + s.armies[from], right = game.name(to) + " " + s.armies[to];
    d.token(x + 7, top - lh / 2, 6, playerColor(s.active));
    float lw = d.fit(f, left, x + 18, mid(f, top - lh, lh), w * .44f, AtlasApp.WHITE);
    d.text(app.small, "vs", x + 24 + lw, mid(app.small, top - lh, lh), AtlasApp.MUTED);
    float rx = x + 46 + lw;
    d.token(rx + 7, top - lh / 2, 6, playerColor(s.owners[to]));
    d.fit(f, right, rx + 18, mid(f, top - lh, lh), x + w - rx - 18, AtlasApp.WHITE);
    top -= lh + 2;
    double odds = game.conquestChance(from, to);
    Color oc = odds >= .7 ? GREEN : odds >= .4 ? AtlasApp.GOLD : RED;
    d.text(app.small, "CHANCE TO CAPTURE WITH BLITZ  ", x, mid(app.small, top - 18, 18), AtlasApp.MUTED);
    d.text(app.body, Math.round(odds * 100) + "%", x + app.width(app.small, "CHANCE TO CAPTURE WITH BLITZ  "), mid(app.body, top - 18, 18), oc);
    top -= 24;
    int maxDice = (int) Math.min(3, s.armies[from] - 1);
    dice = Math.max(1, Math.min(dice, maxDice));
    float ds = row - 4;
    for (int k = 1; k <= 3; k++) {
      int n = k;
      float bx = x + (k - 1) * (ds + 6);
      boolean ok = k <= maxDice;
      if (k == dice) d.round(bx - 2.5f, top - row - .5f, ds + 5, ds + 5, 8, AtlasApp.GOLD);
      d.die(bx, top - row + 2, ds, k, ok ? (k == dice ? Draw.PARCHMENT : Color.valueOf("B9B3A3")) : Draw.OFF, ok ? AtlasApp.INK : AtlasApp.MUTED);
      d.hit(bx, top - row, ds, row, "dice " + k, () -> {
        if (n > maxDice) refuse("Attacking with " + n + " dice needs " + (n + 1) + " armies.");
        dice = n;
      });
    }
    float bx = x + 3 * (ds + 6) + 4, rest = x + w - bx;
    if (compact) {
      d.button("ROLL", bx, top - row, rest, row, Draw.Style.PRIMARY, () -> roll(false));
      top -= row + 8;
      float half = (w - 8) / 2;
      d.button("BLITZ", x, top - row, half, row, Draw.Style.DANGER, () -> roll(true));
      d.button("CANCEL", x + half + 8, top - row, half, row, Draw.Style.NORMAL, () -> to = -1);
      top -= row + 8;
    } else {
      float third = (rest - 16) / 3;
      d.button("ROLL", bx, top - row, third, row, Draw.Style.PRIMARY, () -> roll(false));
      d.button("BLITZ", bx + third + 8, top - row, third, row, Draw.Style.DANGER, () -> roll(true));
      d.button("CANCEL", bx + 2 * (third + 8), top - row, third, row, Draw.Style.NORMAL, () -> to = -1);
      top -= row + 8;
    }
    float rh = rollHeight(w);
    if (top - rh - 6 >= y - 2) lastRoll(x, Math.max(y, top - rh - 6), w);
  }

  private float diceSize() {
    return compact ? 24 : 28;
  }

  /** Height of the last-roll block: the summary drops below the dice when the panel is narrow. */
  private float rollHeight(float w) {
    float step = diceSize() + 5;
    boolean narrow = w - (5 * step + 16) < app.width(app.small, rollSummary) * .85f;
    return shownAttack.length == 0 ? 0 : narrow ? diceSize() + 18 : diceSize();
  }

  /** The last roll's dice, with its block's bottom at {@code y}; the loser of each pair is faded. */
  private void lastRoll(float x, float y, float w) {
    if (shownAttack.length == 0) return;
    boolean below = rollHeight(w) > diceSize();
    float textY = y + 13;
    if (below) y += 18;
    boolean rolling = animation > 0;
    int tick = (int) (clock * 24);
    int[] a = shownAttack.clone(), def = shownDefend.clone();
    Arrays.sort(a);
    Arrays.sort(def);
    int pairs = Math.min(a.length, def.length);
    float size = diceSize(), step = size + 5;
    for (int i = 0; i < a.length; i++) {
      int v = rolling ? 1 + (tick + i * 3) % 6 : a[a.length - 1 - i];
      boolean lost = !rolling && i < pairs && a[a.length - 1 - i] <= def[def.length - 1 - i];
      d.die(x + i * step, y, size, v, lost ? AtlasApp.alpha(RED, .35f) : RED, AtlasApp.WHITE);
    }
    float dx = x + 3 * step + 8;
    for (int i = 0; i < def.length; i++) {
      int v = rolling ? 1 + (tick + i * 2) % 6 : def[def.length - 1 - i];
      boolean lost = !rolling && i < pairs && a[a.length - 1 - i] > def[def.length - 1 - i];
      d.die(dx + i * step, y, size, v, lost ? AtlasApp.alpha(Draw.PARCHMENT, .3f) : Draw.PARCHMENT, AtlasApp.INK);
    }
    float sx = dx + 2 * step + 8;
    if (rolling) return;
    if (!below) d.fit(app.small, rollSummary, sx, mid(app.small, y, size), x + w - sx, AtlasApp.WHITE);
    else d.fit(app.small, rollSummary, x, textY, w, AtlasApp.WHITE);
  }

  private void occupyArea(float x, float y, float w, float top) {
    GameState s = game.state;
    int f = s.occupationFrom, t = s.occupationTo;
    long max = s.armies[f] - 1, min = s.occupationMinimum;
    amount = Math.max(min, Math.min(amount, max));
    float row = compact ? 40 : 44;
    top -= instruction(game.name(t) + " captured! How many armies move in from " + game.name(f) + "?", x, top, w, AtlasApp.GOLD);
    d.text(app.small, game.name(f) + " keeps " + (s.armies[f] - amount) + " · " + game.name(t) + " gets " + amount, x, top - 2, AtlasApp.MUTED);
    top -= 18;
    d.amount(x, top - row, w, row, amount, min, max, v -> amount = v);
    top -= row + 8;
    if (top - row >= y) {
      float third = (w - 16) / 3;
      d.button("MIN " + min, x, top - row, third, row, Draw.Style.NORMAL, () -> amount = min);
      d.button("HALF", x + third + 8, top - row, third, row, Draw.Style.NORMAL, () -> amount = Math.max(min, (max + 1) / 2));
      d.button("ALL " + max, x + 2 * (third + 8), top - row, third, row, Draw.Style.NORMAL, () -> amount = max);
      top -= row + 8;
    }
    float rh = rollHeight(w);
    if (top - rh - 6 >= y) lastRoll(x, top - rh - 6, w);
  }

  private void fortifyArea(float x, float y, float w, float top) {
    GameState s = game.state;
    if (from >= 0 && (s.owners[from] != s.active || game.movable(from) <= 0)) from = to = -1;
    float row = compact ? 40 : 44;
    if (from < 0) {
      top -= instruction("Tap a territory to move armies from, or END TURN.", x, top, w, AtlasApp.WHITE);
      d.wrap(app.small, "Armies travel through your connected land (and allies with open borders). Each army moves once per turn.", x, top, w, AtlasApp.MUTED);
      return;
    }
    if (to < 0) {
      top -= instruction("Moving from " + game.name(from) + " (" + game.movable(from) + " can move). Tap a highlighted destination.", x, top, w, AtlasApp.WHITE);
      d.button("CANCEL", x, top - row, Math.min(w, 180), row, Draw.Style.NORMAL, () -> from = to = -1);
      return;
    }
    long max = game.movable(from);
    amount = Math.max(1, Math.min(amount, max));
    top -= instruction("From " + game.name(from) + " to " + game.name(to), x, top, w, AtlasApp.WHITE);
    d.text(app.small, game.name(from) + " keeps " + (s.armies[from] - amount) + " · " + game.name(to) + " will have " + (s.armies[to] + amount), x, top - 2, AtlasApp.MUTED);
    top -= 18;
    d.amount(x, top - row, w, row, amount, 1, max, v -> amount = v);
    top -= row + 8;
    float half = (w - 8) / 2;
    d.button("MOVE " + amount, x, top - row, half, row, Draw.Style.SELECTED, this::fortify);
    d.button("CANCEL", x + half + 8, top - row, half, row, Draw.Style.NORMAL, () -> from = to = -1);
  }

  // ---------------------------------------------------------------- map overlays

  private void mapOverlay() {
    float right = mapX + mapW, top = mapY + mapH;
    float zb = compact ? 40 : 44;
    if (Gdx.app.getType() != Application.ApplicationType.Android) {
      d.button("+", right - zb - 8, mapY + 8 + 2 * (zb + 6), zb, zb, Draw.Style.GHOST, () -> app.camera.zoomAt(1.4f, app.camera.x + app.camera.width / 2, app.camera.y + app.camera.height / 2));
      d.button("−", right - zb - 8, mapY + 8 + zb + 6, zb, zb, Draw.Style.GHOST, () -> app.camera.zoomAt(1 / 1.4f, app.camera.x + app.camera.width / 2, app.camera.y + app.camera.height / 2));
    }
    d.button("FIT", right - zb - 8, mapY + 8, zb, zb, Draw.Style.GHOST, () -> app.camera.animateReset());
    if (game == null) return;
    if (compact) chips(mapX + 8, top - 40, mapW - 16 - (zb + 8), 34);
    legend(mapX + 8, mapY + 8);
    if (!compact && !humanTurn() && game.state.phase != FINISHED) {
      String m = game.state.players.get(game.state.active).name + " is taking their turn…";
      float w = app.width(app.gameFont, m) + 40;
      d.round(mapX + mapW / 2 - w / 2, top - 52, w, 40, 20, AtlasApp.alpha(AtlasApp.INK, .9f));
      d.center(app.gameFont, m, mapX + mapW / 2, mid(app.gameFont, top - 52, 40), AtlasApp.WHITE);
    }
    if (recapOpen && humanTurn() && modal == Modal.NONE) recap(top - (compact ? 46 : 8));
    else if (inspect >= 0) info(inspect, mapX + 8, top - (compact ? 46 : 8));
    tooltip();
  }

  /** Phone roster: one chip per commander along the top of the map. */
  private void chips(float x, float y, float w, float h) {
    GameState s = game.state;
    float gap = 5, cw = Math.min(118, (w - 4 * gap) / 5);
    for (int p = 0; p < 5; p++) {
      float cx = x + p * (cw + gap);
      boolean alive = game.alive(p), active = p == s.active && s.phase != FINISHED;
      if (active) d.round(cx - 2, y - 2, cw + 4, h + 4, 9, playerColor(p));
      d.round(cx, y, cw, h, 8, AtlasApp.alpha(AtlasApp.INK, .9f));
      d.token(cx + 12, y + h / 2, 6.5f, alive ? playerColor(p) : AtlasApp.alpha(AtlasApp.MUTED, .5f));
      String count = alive ? Integer.toString(game.count(p)) : "OUT";
      float countW = app.width(app.small, count);
      d.fit(app.small, s.players.get(p).name, cx + 23, mid(app.small, y, h), cw - 32 - countW, alive ? AtlasApp.WHITE : AtlasApp.MUTED);
      d.right(app.small, count, cx + cw - 6, mid(app.small, y, h), alive ? AtlasApp.GOLD : AtlasApp.MUTED);
    }
    d.hit(x, y, 5 * (cw + gap), h, "commanders", () -> modal = Modal.ROSTER);
  }

  /** Continent bonuses: tap a row to spotlight that continent on the map. */
  private void legend(float x, float y) {
    List<Board.Continent> list = app.board.continents;
    float w = Math.min(250, mapW - 80), rowH = compact ? Math.min(30, (mapH - 100) / 8) : 28;
    if (!legendOpen) {
      d.button("BONUSES", x, y, 110, compact ? 36 : 40, Draw.Style.GHOST, () -> setLegend(true));
      return;
    }
    float h = 34 + list.size() * rowH + 6;
    d.round(x, y, w, h, 10, AtlasApp.alpha(AtlasApp.INK, .9f));
    d.text(app.small, "CONTINENT BONUSES", x + 12, y + h - 12, AtlasApp.GOLD);
    d.right(app.small, "HIDE", x + w - 10, y + h - 12, AtlasApp.MUTED);
    d.hit(x, y + h - 32, w, 32, "hide bonuses", () -> setLegend(false));
    GameState s = game.state;
    int viewer = s.phase == FINISHED ? s.winner : s.active;
    for (int i = 0; i < list.size(); i++) {
      Board.Continent c = list.get(i);
      float ry = y + h - 34 - (i + 1) * rowH;
      int total = 0, mine = 0, holder = -2;
      for (int r = 0; r < s.owners.length; r++)
        if (app.board.regions.get(r).continent == c) {
          total++;
          if (s.owners[r] == viewer) mine++;
          holder = holder == -2 ? s.owners[r] : holder == s.owners[r] ? holder : -1;
        }
      if (spotlight == c) d.round(x + 4, ry, w - 8, rowH, 5, Draw.RAISED_HI);
      d.box(x + 12, ry + rowH / 2 - 5, 10, 10, Color.valueOf(c.color()));
      d.fit(app.body, c.name(), x + 30, mid(app.body, ry, rowH), w - 120, AtlasApp.WHITE);
      d.right(app.body, "+" + GameEngine.BONUSES.get(c.name()), x + w - 54, mid(app.body, ry, rowH), AtlasApp.GOLD);
      if (holder >= 0) d.token(x + w - 22, ry + rowH / 2, 6, playerColor(holder));
      else if (humanTurn()) d.right(app.small, mine + "/" + total, x + w - 10, mid(app.small, ry, rowH), AtlasApp.MUTED);
      d.hit(x, ry, w, rowH, "continent " + c.name(), () -> spotlight = spotlight == c ? null : c);
    }
  }

  private void setLegend(boolean open) {
    legendOpen = open;
    if (!open) spotlight = null;
    if (!compact) prefs().putBoolean("legendOpen", open).flush();
  }

  /** What changed while this player was away, with the map outlining losses and gains. */
  private void recap(float top) {
    GameState s = game.state;
    float w = Math.min(mapW - 16 - 60, 540), x = mapX + 8;
    List<String> lost = new ArrayList<>(), gained = new ArrayList<>();
    for (int r : recapLost) lost.add(game.name(r));
    for (int r : recapGained) gained.add(game.name(r));
    String headline =
        lost.isEmpty()
            ? "Your territories held while you were away."
            : "You lost " + lost.size() + (lost.size() == 1 ? " territory" : " territories") + " while you were away.";
    String detail = (lost.isEmpty() ? "" : "Lost: " + list(lost) + ". ") + (gained.isEmpty() ? "" : "Gained: " + list(gained) + ".");
    float tw = w - 24, hh = d.wrapHeight(app.body, headline, tw), dh = detail.isEmpty() ? 0 : d.wrapHeight(app.small, detail, tw) + 6;
    float h = 14 + hh + dh + 10 + 40 + 10;
    d.round(x, top - h, w, h, 10, AtlasApp.alpha(lost.isEmpty() ? AtlasApp.INK : ERROR_BG, .95f));
    d.round(x, top - h, 5, h, 2.5f, lost.isEmpty() ? GREEN : RED);
    float y = top - 12;
    d.wrap(app.body, headline, x + 14, y, tw, lost.isEmpty() ? GREEN : ERROR_TEXT);
    y -= hh + 6;
    if (!detail.isEmpty()) d.wrap(app.small, detail, x + 14, y, tw, AtlasApp.WHITE);
    float bw = (w - 24 - 16) / 3, by = top - h + 10;
    d.button("SHOW", x + 12, by, bw, 40, Draw.Style.NORMAL, () -> {
      if (!recapLost.isEmpty()) frame(recapLost);
      else if (!recapGained.isEmpty()) frame(recapGained);
    });
    d.button(recapEvents > 0 ? "LOG (" + recapEvents + ")" : "LOG", x + 12 + bw + 8, by, bw, 40, Draw.Style.NORMAL, () -> open(Modal.LOG));
    d.button("OK", x + 12 + 2 * (bw + 8), by, bw, 40, Draw.Style.PRIMARY, this::dismissRecap);
    if (s.phase == SETUP) recapOpen = false;
  }

  private static String list(List<String> names) {
    if (names.size() <= 4) return String.join(", ", names);
    return String.join(", ", names.subList(0, 4)) + " and " + (names.size() - 4) + " more";
  }

  /** Territory details card (long-press, or a tap that is not a move). */
  private void info(int r, float x, float top) {
    GameState s = game.state;
    Board.Region region = app.board.regions.get(r);
    float w = Math.min(300, mapW - 16 - 60);
    int owner = s.owners[r];
    List<Integer> next = new ArrayList<>();
    for (int n : game.neighbours(r)) next.add(n);
    int shown = Math.min(next.size(), compact ? 4 : 6);
    float h = 30 + 22 + 20 + 18 + shown * 19 + (next.size() > shown ? 18 : 0) + 10;
    d.round(x, top - h, w, h, 10, AtlasApp.alpha(AtlasApp.INK, .95f));
    d.round(x, top - h, 5, h, 2.5f, playerColor(owner));
    float y = top - 8;
    d.fit(app.gameFont, region.name, x + 14, y - 2, w - 60, AtlasApp.WHITE);
    d.button("CLOSE", x + w - 70, top - 40, 64, 34, Draw.Style.NORMAL, () -> inspect = -1);
    y -= 30;
    d.token(x + 20, y - 7, 6, playerColor(owner));
    d.fit(app.body, s.players.get(owner).name + " · " + s.armies[r] + (s.armies[r] == 1 ? " army" : " armies"), x + 32, y, w - 46, playerColor(owner));
    y -= 22;
    int holder = -2, mine = 0, total = 0;
    for (int i = 0; i < s.owners.length; i++)
      if (app.board.regions.get(i).continent == region.continent) {
        total++;
        if (s.owners[i] == owner) mine++;
        holder = holder == -2 ? s.owners[i] : holder == s.owners[i] ? holder : -1;
      }
    String continent = region.continent.name() + " +" + GameEngine.BONUSES.get(region.continent.name())
        + (holder >= 0 ? " · held by " + s.players.get(holder).name : " · owner has " + mine + "/" + total);
    d.fit(app.small, continent, x + 14, y, w - 28, AtlasApp.MUTED);
    y -= 20;
    d.text(app.small, "BORDERS", x + 14, y, AtlasApp.GOLD);
    y -= 18;
    next.sort(Comparator.comparingLong(n -> -s.armies[n]));
    for (int i = 0; i < shown; i++) {
      int n = next.get(i);
      d.token(x + 20, y - 6, 4.5f, playerColor(s.owners[n]));
      d.fit(app.small, game.name(n) + " — " + s.players.get(s.owners[n]).name + " (" + s.armies[n] + ")", x + 30, y, w - 44, AtlasApp.WHITE);
      y -= 19;
    }
    if (next.size() > shown) d.text(app.small, "+" + (next.size() - shown) + " more", x + 30, y, AtlasApp.MUTED);
  }

  private void tooltip() {
    if (game == null
        || app.hovered == null
        || modal != Modal.NONE
        || !app.pointerMoved
        || Gdx.app.getType() != Application.ApplicationType.Desktop) return;
    GameState s = game.state;
    int r = app.board.regions.indexOf(app.hovered);
    String line1 = app.hovered.name,
        line2 = s.players.get(s.owners[r]).name + " · " + s.armies[r] + (s.armies[r] == 1 ? " army" : " armies"),
        line3 = app.hovered.continent.name() + " (+" + GameEngine.BONUSES.get(app.hovered.continent.name()) + ")";
    float w = Math.max(app.width(app.body, line1), Math.max(app.width(app.body, line2), app.width(app.small, line3))) + 28;
    float x = Math.min(app.mouse.x + 18, mapX + mapW - w - 4), y = Math.max(app.mouse.y - 14, mapY + 76);
    d.round(x, y - 70, w, 70, 8, AtlasApp.alpha(AtlasApp.INK, .94f));
    d.box(x, y - 70, 4, 70, playerColor(s.owners[r]));
    d.text(app.body, line1, x + 14, y - 9, AtlasApp.WHITE);
    d.text(app.body, line2, x + 14, y - 30, playerColor(s.owners[r]));
    d.text(app.small, line3, x + 14, y - 51, AtlasApp.MUTED);
  }

  private void drawToast() {
    float age = clock - toastAt;
    if (toast.isEmpty() || age > 3.5f) return;
    float a = MathUtils.clamp(3.5f - age, 0, 1);
    float maxW = (modal == Modal.NONE ? mapW : app.W) - 32;
    float w = Math.min(maxW, app.width(app.body, toast) + 44);
    float cx = modal == Modal.NONE ? mapX + mapW / 2 : app.W / 2;
    float y = modal == Modal.NONE ? (compact ? mapY + 56 : mapY + 14) : app.H - 58;
    d.round(cx - w / 2, y, w, 40, 20, AtlasApp.alpha(toastError ? ERROR_BG : AtlasApp.INK, .95f * a));
    d.round(cx - w / 2, y, 6, 40, 3, AtlasApp.alpha(toastError ? RED : AtlasApp.GOLD, a));
    d.fitCenter(app.body, toast, cx, mid(app.body, y, 40), w - 32, AtlasApp.alpha(toastError ? ERROR_TEXT : AtlasApp.WHITE, a));
  }

  // ---------------------------------------------------------------- modals and lifecycle

  void open(Modal next) {
    if (next == Modal.SETTINGS || next == Modal.MENU) {
      modal = next;
      return;
    }
    if (game == null) return;
    if (next == Modal.LOG) {
      screens.openLog();
      return;
    }
    if (!humanTurn()) {
      if (next == Modal.CARDS || next == Modal.DIPLOMACY) say("Wait for your turn: cards and offers are private.", false);
      return;
    }
    if (animation > 0) return;
    modal = next;
    screens.page = 0;
    cards.clear();
  }

  void pause() {
    if (game != null) {
      saveNow();
      if (humanTurn() && hotseat() && modal != Modal.MENU && modal != Modal.PLAYER) modal = Modal.HANDOVER;
    }
  }

  void back() {
    if (modal == Modal.HANDOVER) return;
    if (modal == Modal.NONE) {
      if (inspect >= 0 || spotlight != null) {
        inspect = -1;
        spotlight = null;
      } else if (humanTurn() && (from >= 0 || to >= 0) && game.state.phase != OCCUPY) from = to = -1;
      else modal = Modal.SETTINGS;
    } else screens.close();
    cards.clear();
  }

  void dispose() {
    waitForSaves();
    saver.shutdown();
    for (Sound s : new Sound[] {diceSound, winSound, placeSound, victorySound, defeatSound}) if (s != null) s.dispose();
  }
}
