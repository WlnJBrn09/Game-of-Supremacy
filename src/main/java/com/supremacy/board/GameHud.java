package com.supremacy.board;

import static com.supremacy.game.GameState.Phase.*;

import com.badlogic.gdx.*;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Rectangle;
import com.supremacy.game.*;
import java.util.*;

/**
 * Pass-and-play interaction layer, laid out like a classic conquest board game: the world map on
 * top, a command console underneath with the commanders, the current phase and the next step.
 * Every action delegates to the shared rules engine.
 */
final class GameHud {
  enum Modal {
    NONE,
    MENU,
    HANDOVER,
    REPORT,
    CARDS,
    DIPLOMACY,
    OFFER,
    COMPOSE,
    PICK,
    SETTINGS,
    RULES
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

  enum Style {
    NORMAL,
    PRIMARY,
    SELECTED,
    DANGER,
    DISABLED
  }

  record Floater(int region, String text, Color color, float born) {}

  static final Color CONSOLE = Color.valueOf("0A1621"),
      RAISED = Color.valueOf("1D3748"),
      RAISED_HI = Color.valueOf("29495E"),
      LINE = Color.valueOf("2A4A5C"),
      RED = Color.valueOf("E0625A"),
      GREEN = Color.valueOf("7DC383"),
      PARCHMENT = Color.valueOf("EFE6CF"),
      OFF = Color.valueOf("152837"),
      GOLD_DARK = Color.valueOf("A88A4E");
  private static final String[] CARD_TYPES = {"INFANTRY", "CAVALRY", "ARTILLERY", "WILD"};
  private static final String[] SKILL_NOTES = {
    "Learning the ropes", "Casual", "Balanced", "Plans ahead", "Ruthless", "Relentless"
  };

  private final AtlasApp app;
  final SaveStore saves;
  GameEngine game;
  private BotStrategy bots;
  private Modal modal = Modal.MENU, returnModal = Modal.NONE;
  private int humans = 1,
      difficulty = 2,
      from = -1,
      to = -1,
      page,
      recipient = 1,
      counterId = -1,
      pickPage;
  private long amount = 1;
  private int dice = 3, lastActive = -1;
  private float animation, clock, toastAt = -99;
  private String toast = "", pickTitle = "", rollSummary = "";
  private boolean toastError, legendOpen = true, confirmNew;
  private int[] shownAttack = new int[0], shownDefend = new int[0];
  private long[] income;
  private List<String> report = List.of();
  private List<Integer> picks = List.of();
  private java.util.function.IntConsumer pickAction;
  private final List<Integer> cards = new ArrayList<>();
  private final List<Floater> floaters = new ArrayList<>();
  private GameState.Offer draft, viewOffer;
  private boolean armyPage, soundEnabled, logMode;
  private Modal rulesReturn = Modal.MENU;
  private Sound diceSound, winSound;

  GameHud(AtlasApp app) {
    this.app = app;
    String dir =
        System.getProperty("supremacy.saveDir", Gdx.files.getLocalStoragePath() + "supremacy");
    saves = new SaveStore(java.nio.file.Paths.get(dir, "autosave.json"));
    soundEnabled = Gdx.app.getPreferences("supremacy-options").getBoolean("sound", true);
    try {
      diceSound = Gdx.audio.newSound(Gdx.files.internal("audio/dice.wav"));
      winSound = Gdx.audio.newSound(Gdx.files.internal("audio/conquest.wav"));
    } catch (RuntimeException e) {
      diceSound = winSound = null;
    }
  }

  void attach(GameEngine engine) {
    game = engine;
    bots = new BotStrategy(game);
    game.onChange(
        () -> {
          saves.save(game);
          Gdx.graphics.requestRendering();
        });
    saves.save(game);
    from = to = -1;
    cards.clear();
    floaters.clear();
    lastActive = -1;
    modal = Modal.NONE;
    app.camera.reset();
    checkTurn();
  }

  // ---------------------------------------------------------------- state helpers

  private void action(Runnable command) {
    try {
      command.run();
      checkTurn();
    } catch (IllegalArgumentException | IllegalStateException | ArithmeticException e) {
      say(e.getMessage(), true);
    }
    Gdx.graphics.requestRendering();
  }

  private void say(String message, boolean error) {
    toast = message == null ? "" : message;
    toastError = error;
    toastAt = clock;
  }

  private static void refuse(String reason) {
    throw new IllegalStateException(reason);
  }

  private boolean hotseat() {
    int n = 0;
    for (GameState.Player p : game.state.players) if (p.human) n++;
    return n > 1;
  }

  private void checkTurn() {
    if (game == null) return;
    GameState s = game.state;
    if (s.active != lastActive) {
      lastActive = s.active;
      from = to = -1;
      amount = 1;
      cards.clear();
      page = 0;
      shownAttack = shownDefend = new int[0];
      rollSummary = "";
      income = null;
      if (s.phase == REINFORCE && s.reserve == game.income(s.active)) {
        long lands = Math.max(3, game.count(s.active) / 3), continents = game.continentIncome(s.active);
        income = new long[] {lands, continents, s.reserve - lands - continents};
      }
      if (s.players.get(lastActive).human) {
        report = game.report(s.active);
        if (hotseat()) modal = Modal.HANDOVER;
        else showReport();
      } else modal = Modal.NONE;
    }
  }

  /** Shows what happened since this player's last turn, once, then marks it read. */
  private void showReport() {
    logMode = false;
    page = 0;
    report = game.report(game.state.active);
    modal = game.state.phase == SETUP || visibleReport().isEmpty() ? Modal.NONE : Modal.REPORT;
    if (game.state.lastReadTurn[game.state.active] < game.state.turn) game.markRead();
  }

  private boolean humanTurn() {
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

  boolean animating() {
    return animation > 0
        || !floaters.isEmpty()
        || clock - toastAt < 3.6f
        || (interactive() && (from >= 0 || game.state.phase == OCCUPY));
  }

  /** The region whose sea lanes the map highlights. */
  int focus() {
    if (game == null) return -1;
    if (game.state.phase == OCCUPY) return game.state.occupationTo;
    return humanTurn() ? from : -1;
  }

  int arrowFrom() {
    if (game == null || !humanTurn()) return -1;
    return game.state.phase == OCCUPY ? game.state.occupationFrom : to >= 0 ? from : -1;
  }

  int arrowTo() {
    if (game == null || !humanTurn()) return -1;
    return game.state.phase == OCCUPY ? game.state.occupationTo : to;
  }

  /** Deployment only fades enemy land a little; targeting fades everything irrelevant. */
  float dimStrength() {
    return game != null && (game.state.phase == SETUP || game.state.phase == REINFORCE) ? .32f : .5f;
  }

  Color actionColor() {
    return game != null && (game.state.phase == ATTACK || game.state.phase == OCCUPY)
        ? RED
        : AtlasApp.GOLD;
  }

  private boolean canAttackFrom(int r) {
    GameState s = game.state;
    if (s.owners[r] != s.active || s.armies[r] < 2) return false;
    for (int n : game.neighbours(r)) if (s.owners[n] != s.active) return true;
    return false;
  }

  Role role(int r) {
    if (game == null || !humanTurn() || modal != Modal.NONE) return Role.NORMAL;
    GameState s = game.state;
    boolean own = s.owners[r] == s.active;
    switch (s.phase) {
      case SETUP, REINFORCE -> {
        if (r == from) return Role.SOURCE;
        if (!own) return Role.DIM;
        return s.reserve > 0 && from < 0 ? Role.HINT : Role.NORMAL;
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

  // ---------------------------------------------------------------- map input

  void tap(int r) {
    if (!interactive()) return;
    action(() -> choose(r));
  }

  void tapOcean() {
    if (interactive() && game.state.phase != OCCUPY) from = to = -1;
  }

  private void choose(int r) {
    GameState s = game.state;
    boolean own = s.owners[r] == s.active;
    String owner = s.players.get(s.owners[r]).name;
    switch (s.phase) {
      case SETUP, REINFORCE -> {
        if (!own) refuse(game.name(r) + " belongs to " + owner + ". Place armies on your own land.");
        if (s.reserve == 0)
          refuse(
              s.phase == SETUP
                  ? "All armies placed. Tap FINISH PLACEMENT."
                  : "No armies left to place. Trade cards or start attacking.");
        from = from == r ? -1 : r;
        to = -1;
        amount = s.reserve;
      }
      case ATTACK -> {
        if (own) {
          if (r == from) {
            from = to = -1;
            return;
          }
          if (s.armies[r] < 2) refuse(game.name(r) + " needs at least 2 armies to attack.");
          if (!canAttackFrom(r)) refuse(game.name(r) + " has no enemy neighbours.");
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
          if (best < 0) refuse("None of your territories with 2+ armies borders " + game.name(r) + ".");
          from = best;
          to = r;
        }
        dice = (int) Math.min(3, s.armies[from] - 1);
      }
      case OCCUPY -> refuse("Choose how many armies move into " + game.name(s.occupationTo) + ".");
      case FORTIFY -> {
        if (!own) refuse("You can only move armies between your own territories.");
        if (r == from) {
          from = to = -1;
        } else if (from >= 0 && game.connected(from, r, s.active)) {
          to = r;
          amount = game.movable(from);
        } else {
          if (game.movable(r) <= 0)
            refuse(
                s.armies[r] < 2
                    ? game.name(r) + " has only one army; it must stay."
                    : "Armies in " + game.name(r) + " already moved this turn.");
          from = r;
          to = -1;
        }
      }
      default -> {}
    }
  }

  boolean key(int key) {
    if (!interactive()) return false;
    switch (key) {
      case Input.Keys.ENTER, Input.Keys.SPACE -> {
        GameState s = game.state;
        if ((s.phase == SETUP || s.phase == REINFORCE) && from >= 0 && s.reserve > 0)
          action(this::deploy);
        else if (s.phase == FORTIFY && from >= 0 && to >= 0) action(this::fortify);
        else action(this::primary);
        return true;
      }
      case Input.Keys.B -> {
        if (game.state.phase == ATTACK && from >= 0 && to >= 0) action(() -> roll(true));
        return true;
      }
      case Input.Keys.R -> {
        if (game.state.phase == ATTACK && from >= 0 && to >= 0) action(() -> roll(false));
        return to >= 0;
      }
      default -> {
        return false;
      }
    }
  }

  // ---------------------------------------------------------------- commands

  private void pop(int region, String text, Color color) {
    floaters.add(new Floater(region, text, color, clock));
  }

  private void deploy() {
    GameState s = game.state;
    if (from < 0) refuse("Tap one of your territories first.");
    long n = Math.max(1, Math.min(amount, s.reserve));
    game.deploy(from, n);
    pop(from, "+" + n, AtlasApp.GOLD);
    if (s.reserve == 0) {
      from = -1;
      if (s.phase == REINFORCE
          && game.findSet(s.active).isEmpty()
          && s.players.get(s.active).cards.size() < 5) {
        game.finishDeployment();
        say("All armies placed. Attack!", false);
      }
    } else amount = s.reserve;
  }

  private void roll(boolean blitz) {
    GameState s = game.state;
    if (from < 0 || to < 0) refuse("Pick your territory, then an enemy neighbour.");
    int f = from, t = to, rolls = 0;
    long a0 = s.armies[f], d0 = s.armies[t];
    String target = game.name(t);
    do {
      int n = (int) Math.min(blitz ? 3 : dice, s.armies[f] - 1);
      game.attack(f, t, n);
      rolls++;
    } while (blitz && s.phase == ATTACK && s.armies[f] > 1);
    shownAttack = s.attackDice.clone();
    shownDefend = s.defendDice.clone();
    long lostA = a0 - s.armies[f], lostD = d0 - s.armies[t];
    if (lostA > 0) pop(f, "-" + lostA, RED);
    if (lostD > 0) pop(t, "-" + lostD, RED);
    animation = .5f;
    if (soundEnabled && diceSound != null) diceSound.play(.45f);
    boolean captured = s.phase == OCCUPY || s.phase == FINISHED || s.owners[t] == s.active;
    rollSummary =
        (blitz ? "Blitz · " + rolls + (rolls == 1 ? " roll · " : " rolls · ") : "")
            + "You lost "
            + lostA
            + " · They lost "
            + lostD;
    if (captured) {
      amount = s.phase == OCCUPY ? s.armies[f] - 1 : 0;
      if (soundEnabled && winSound != null) winSound.play(.5f);
      if (s.phase != FINISHED) say(target + " captured!", false);
    } else if (s.armies[f] < 2) {
      say(game.name(f) + " has no armies left to attack with.", false);
      from = to = -1;
    } else dice = (int) Math.min(dice, s.armies[f] - 1);
  }

  private void occupy() {
    GameState s = game.state;
    int f = s.occupationFrom, t = s.occupationTo;
    long n = Math.max(s.occupationMinimum, Math.min(amount, s.armies[f] - 1));
    game.occupy(n);
    pop(t, "+" + n, AtlasApp.GOLD);
    to = -1;
    // Keep the push going from the newly captured territory when it can attack again.
    if (s.phase == ATTACK) {
      from = canAttackFrom(t) ? t : canAttackFrom(f) ? f : -1;
      if (from >= 0) dice = (int) Math.min(3, s.armies[from] - 1);
    } else from = -1;
  }

  private void fortify() {
    if (from < 0 || to < 0) refuse("Pick a territory to move from, then a destination.");
    long n = Math.max(1, Math.min(amount, game.movable(from)));
    game.fortify(from, to, n);
    pop(from, "-" + n, AtlasApp.MUTED);
    pop(to, "+" + n, AtlasApp.GOLD);
    say("Moved " + n + " from " + game.name(from) + " to " + game.name(to) + ".", false);
    from = to = -1;
  }

  private void trade() {
    GameState s = game.state;
    List<Integer> set = cards.size() == 3 ? new ArrayList<>(cards) : game.findSet(s.active);
    if (set.isEmpty()) refuse("You do not have a matching set yet.");
    long before = s.reserve;
    long[] armies = s.armies.clone();
    game.tradeCards(new ArrayList<>(set));
    cards.clear();
    String bonus = "";
    for (int i = 0; i < armies.length; i++)
      if (s.armies[i] > armies[i]) {
        pop(i, "+" + (s.armies[i] - armies[i]), AtlasApp.GOLD);
        bonus = " (+2 on " + game.name(i) + ")";
      }
    say("Cards traded for " + (s.reserve - before) + " armies" + bonus + ".", false);
    amount = s.reserve;
  }

  private void primary() {
    GameState s = game.state;
    switch (s.phase) {
      case SETUP -> {
        if (s.reserve > 0) refuse("Place all " + s.reserve + " remaining armies first.");
        from = -1;
        game.finishDeployment();
      }
      case REINFORCE -> {
        if (s.players.get(s.active).cards.size() >= 5)
          refuse("You hold five or more cards. Trade a set first.");
        if (s.reserve > 0) refuse("Place all " + s.reserve + " remaining armies first.");
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

  void render() {
    clock += Math.min(.1f, Gdx.graphics.getDeltaTime());
    if (game != null) {
      checkTurn();
      if (!game.state.players.get(game.state.active).human
          && game.state.phase != FINISHED
          && modal == Modal.NONE) {
        try {
          for (int i = 0;
              i < 12
                  && !game.state.players.get(game.state.active).human
                  && game.state.phase != FINISHED;
              i++) bots.step();
          checkTurn();
        } catch (RuntimeException e) {
          say("Bot paused: " + e.getMessage(), true);
          modal = Modal.SETTINGS;
        }
      }
      if (animation > 0) animation = Math.max(0, animation - Gdx.graphics.getDeltaTime());
      if (!game.state.players.get(game.state.active).human && modal == Modal.NONE)
        Gdx.graphics.requestRendering();
      floaters.removeIf(f -> clock - f.born() > 1.4f);
    }
    mapOverlay();
    topBar();
    console();
    if (modal != Modal.NONE) {
      app.hits.clear();
      app.sliders.clear();
      drawModal();
    }
    drawToast();
  }

  // ---------------------------------------------------------------- drawing primitives

  private void box(float x, float y, float w, float h, Color c) {
    app.begin();
    app.rect(x, y, w, h, c);
    app.shapes.end();
  }

  private void round(float x, float y, float w, float h, float r, Color c) {
    app.begin();
    app.round(x, y, w, h, r, c);
    app.shapes.end();
  }

  private void txt(String s, float x, float y, Color c) {
    text(app.gameFont, s, x, y, c);
  }

  private void text(BitmapFont f, String s, float x, float y, Color c) {
    app.batch.begin();
    app.text(f, s, x, y, c);
    app.batch.end();
  }

  private void center(BitmapFont f, String s, float x, float y, Color c) {
    app.batch.begin();
    app.centered(f, s, x, y, c);
    app.batch.end();
  }

  private void right(BitmapFont f, String s, float x, float y, Color c) {
    text(f, s, x - app.width(f, s), y, c);
  }

  /** Draws text shrunk to fit a width. */
  private void fit(BitmapFont f, String s, float x, float y, float w, Color c) {
    float measured = app.width(f, s);
    float scale = Math.min(1, w / Math.max(1, measured));
    f.getData().setScale(scale);
    text(f, s, x, y, c);
    f.getData().setScale(1);
  }

  private void wrap(String s, float x, float y, float w, Color c) {
    wrap(app.gameFont, s, x, y, w, c);
  }

  private void wrap(BitmapFont f, String s, float x, float y, float w, Color c) {
    app.batch.begin();
    app.wrap(f, s, x, y, w, c);
    app.batch.end();
  }

  private Color playerColor(int p) {
    return Color.valueOf(GameState.COLORS[p]);
  }

  private void token(float x, float y, float r, Color c) {
    app.begin();
    app.shapes.setColor(AtlasApp.INK);
    app.shapes.circle(x, y, r + 1.5f, 20);
    app.shapes.setColor(new Color(c).mul(.68f, .68f, .68f, 1));
    app.shapes.circle(x, y, r, 20);
    app.shapes.setColor(new Color(c).lerp(AtlasApp.WHITE, .1f));
    app.shapes.circle(x, y, r * .83f, 20);
    app.shapes.end();
  }

  private void button(String label, float x, float y, float w, Runnable run) {
    button(label, x, y, w, 52, Style.NORMAL, run);
  }

  private void button(String label, float x, float y, float w, boolean active, Runnable run) {
    button(label, x, y, w, 52, active ? Style.SELECTED : Style.NORMAL, run);
  }

  private void button(
      String label, float x, float y, float w, float h, Style style, Runnable run) {
    Color bg =
        switch (style) {
          case PRIMARY -> AtlasApp.GOLD;
          case SELECTED -> RAISED_HI;
          case DANGER -> RED;
          case DISABLED -> OFF;
          default -> RAISED;
        };
    Color fg =
        switch (style) {
          case PRIMARY -> AtlasApp.INK;
          case DISABLED -> AtlasApp.MUTED;
          case SELECTED -> AtlasApp.GOLD;
          default -> AtlasApp.WHITE;
        };
    app.begin();
    if (style == Style.SELECTED) app.round(x - 2, y - 2, w + 4, h + 4, 10, AtlasApp.GOLD);
    if (style == Style.PRIMARY) app.round(x, y - 3, w, h, 9, GOLD_DARK);
    if (style == Style.DANGER) app.round(x, y - 3, w, h, 9, new Color(RED).mul(.7f, .7f, .7f, 1));
    if (style == Style.NORMAL) app.round(x, y - 2, w, h, 9, AtlasApp.INK);
    app.round(x, y, w, h, 8, bg);
    app.shapes.end();
    if (label.equals("−")) {
      box(x + w / 2 - 9, y + h / 2 - 1.5f, 18, 3, fg);
      app.hits.add(new AtlasApp.Hit(new Rectangle(x, y, w, h), () -> action(run)));
      return;
    }
    BitmapFont f = app.gameFont;
    float measured = app.width(f, label), scale = Math.min(1, (w - 20) / Math.max(1, measured));
    f.getData().setScale(scale);
    app.batch.begin();
    app.centered(f, label, x + w / 2, y + h / 2 + f.getCapHeight() / 2, fg);
    app.batch.end();
    f.getData().setScale(1);
    app.hits.add(new AtlasApp.Hit(new Rectangle(x, y, w, h), () -> action(run)));
  }

  private void die(float x, float y, float size, int n, Color face, Color pip) {
    app.begin();
    app.round(x + 1, y - 2, size, size, size * .18f, AtlasApp.alpha(Color.BLACK, .4f));
    app.round(x, y, size, size, size * .18f, face);
    app.shapes.setColor(pip);
    float c = size / 2, o = size * .27f, pr = size * .085f;
    float[][] spots =
        switch (n) {
          case 1 -> new float[][] {{0, 0}};
          case 2 -> new float[][] {{-1, -1}, {1, 1}};
          case 3 -> new float[][] {{-1, -1}, {0, 0}, {1, 1}};
          case 4 -> new float[][] {{-1, -1}, {1, 1}, {-1, 1}, {1, -1}};
          case 5 -> new float[][] {{-1, -1}, {1, 1}, {-1, 1}, {1, -1}, {0, 0}};
          default -> new float[][] {{-1, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, 0}, {1, 0}};
        };
    for (float[] p : spots) app.shapes.circle(x + c + p[0] * o, y + c + p[1] * o, pr, 12);
    app.shapes.end();
  }

  /** A horizontal amount slider with − / + steppers on either side. */
  private void amountControl(float x, float y, float w, long min, long max) {
    amount = Math.max(min, Math.min(amount, max));
    button("−", x, y, 52, 52, Style.NORMAL, () -> amount = Math.max(min, amount - 1));
    button("+", x + w - 52, y, 52, 52, Style.NORMAL, () -> amount = Math.min(max, amount + 1));
    float sx = x + 76, sw = w - 152, cy = y + 26;
    float f = max <= min ? 1 : (amount - min) / (float) (max - min);
    app.begin();
    app.round(sx, cy - 4, sw, 8, 4, AtlasApp.INK);
    app.round(sx, cy - 4, Math.max(8, sw * f), 8, 4, AtlasApp.GOLD);
    app.shapes.setColor(AtlasApp.INK);
    app.shapes.circle(sx + sw * f, cy, 16, 24);
    app.shapes.setColor(AtlasApp.GOLD);
    app.shapes.circle(sx + sw * f, cy, 13, 24);
    app.shapes.end();
    app.small.getData().setScale(1);
    text(app.small, Long.toString(min), sx, y - 2, AtlasApp.MUTED);
    right(app.small, Long.toString(max), sx + sw, y - 2, AtlasApp.MUTED);
    if (max > min)
      app.sliders.add(
          new AtlasApp.Slider(
              new Rectangle(sx - 16, y, sw + 32, 52),
              v -> {
                amount = min + Math.round(v * (max - min));
                Gdx.graphics.requestRendering();
              }));
  }

  // ---------------------------------------------------------------- top bar

  private void topBar() {
    box(0, 944, 1600, 56, CONSOLE);
    box(0, 944, 1600, 2, LINE);
    text(app.heading, "SUPREMACY", 20, 984, AtlasApp.WHITE);
    if (game != null) {
      GameState s = game.state;
      text(app.body, "TURN " + Math.max(1, s.turn), 196, 978, AtlasApp.MUTED);
      if (s.phase != FINISHED) {
        token(300, 972, 9, playerColor(s.active));
        txt(s.players.get(s.active).name + "  ·  " + phaseName(s.phase), 318, 982, AtlasApp.WHITE);
      }
    }
    boolean human = humanTurn();
    int hand = human ? game.state.players.get(game.state.active).cards.size() : 0;
    boolean set = human && !game.findSet(game.state.active).isEmpty();
    button(
        human ? "CARDS  " + hand : "CARDS",
        980,
        952,
        150,
        40,
        set ? Style.SELECTED : Style.NORMAL,
        () -> open(Modal.CARDS));
    int inbox = human ? game.inbox().size() : 0;
    button(
        inbox > 0 ? "DIPLOMACY  " + inbox : "DIPLOMACY",
        1140,
        952,
        190,
        40,
        inbox > 0 ? Style.SELECTED : Style.NORMAL,
        () -> open(Modal.DIPLOMACY));
    button(
        "LOG",
        1340,
        952,
        110,
        40,
        Style.NORMAL,
        () -> {
          if (game == null) return;
          List<String> recent = new ArrayList<>();
          List<GameState.Notice> history = game.state.history;
          for (int i = history.size() - 1; i >= 0 && recent.size() < 54; i--)
            recent.add(history.get(i).message);
          report = recent;
          open(Modal.REPORT);
          if (modal == Modal.REPORT) logMode = true;
        });
    button("MENU", 1460, 952, 128, 40, Style.NORMAL, () -> open(Modal.SETTINGS));
  }

  private String phaseName(GameState.Phase p) {
    return switch (p) {
      case SETUP -> "PLACE ARMIES";
      case REINFORCE -> "REINFORCE";
      case ATTACK -> "ATTACK";
      case OCCUPY -> "OCCUPY";
      case FORTIFY -> "FORTIFY";
      case FINISHED -> "VICTORY";
    };
  }

  // ---------------------------------------------------------------- map overlays

  private void mapOverlay() {
    float y0 = AtlasCamera.Y;
    // Zoom controls.
    button("+", 1540, y0 + 116, 48, 48, Style.NORMAL, () -> app.camera.zoomAt(1.35f, 800, 606));
    button("−", 1540, y0 + 64, 48, 48, Style.NORMAL, () -> app.camera.zoomAt(.74f, 800, 606));
    button("FIT", 1540, y0 + 12, 48, 48, Style.NORMAL, () -> app.camera.reset());
    if (game != null) legend();
    if (game != null && game.state.phase == FINISHED) victoryBanner();
    else if (game != null && !game.state.players.get(game.state.active).human)
      banner(game.state.players.get(game.state.active).name + " is taking their turn…");
    tooltip();
  }

  private void banner(String message) {
    float w = app.width(app.gameFont, message) + 48;
    round(800 - w / 2, 884, w, 46, 23, AtlasApp.alpha(AtlasApp.INK, .9f));
    center(app.gameFont, message, 800, 915, AtlasApp.WHITE);
  }

  private void victoryBanner() {
    GameState s = game.state;
    float y = 500;
    round(450, y, 700, 190, 18, AtlasApp.INK);
    round(450, y + 178, 700, 12, 6, playerColor(s.winner));
    center(app.title, "WORLD CONQUERED", 800, y + 152, AtlasApp.GOLD);
    center(
        app.gameFont,
        s.players.get(s.winner).name + " rules all " + s.owners.length + " territories.",
        800,
        y + 96,
        AtlasApp.WHITE);
    button("NEW GAME", 660, y + 18, 280, 52, Style.PRIMARY, () -> modal = Modal.MENU);
  }

  private void legend() {
    float x = 12, y0 = AtlasCamera.Y + 12, w = 262;
    List<Board.Continent> list = app.board.continents;
    float h = legendOpen ? 42 + list.size() * 25 : 36;
    round(x, y0, w, h, 10, AtlasApp.alpha(AtlasApp.INK, .86f));
    text(app.small, "CONTINENT BONUSES", x + 14, y0 + h - 12, AtlasApp.GOLD);
    text(app.small, legendOpen ? "HIDE" : "SHOW", x + w - 46, y0 + h - 12, AtlasApp.MUTED);
    app.hits.add(
        new AtlasApp.Hit(
            new Rectangle(x, y0 + h - 36, w, 36), () -> legendOpen = !legendOpen));
    if (!legendOpen) return;
    GameState s = game.state;
    int viewer = s.active;
    for (int i = 0; i < list.size(); i++) {
      Board.Continent c = list.get(i);
      float y = y0 + h - 44 - i * 25;
      int total = 0, mine = 0, holder = -2;
      for (int r = 0; r < s.owners.length; r++)
        if (app.board.regions.get(r).continent == c) {
          total++;
          if (s.owners[r] == viewer) mine++;
          holder = holder == -2 ? s.owners[r] : holder == s.owners[r] ? holder : -1;
        }
      box(x + 14, y - 12, 12, 12, Color.valueOf(c.color()));
      text(app.body, c.name(), x + 34, y, AtlasApp.WHITE);
      right(app.body, "+" + GameEngine.BONUSES.get(c.name()), x + w - 58, y, AtlasApp.GOLD);
      if (holder >= 0) token(x + w - 22, y - 6, 7, playerColor(holder));
      else if (humanTurn())
        right(app.small, mine + "/" + total, x + w - 10, y - 1, AtlasApp.MUTED);
    }
  }

  private void tooltip() {
    if (game == null
        || app.hovered == null
        || modal != Modal.NONE
        || Gdx.app.getType() != Application.ApplicationType.Desktop) return;
    GameState s = game.state;
    int r = app.board.regions.indexOf(app.hovered);
    String line1 = app.hovered.name,
        line2 = s.players.get(s.owners[r]).name + " · " + s.armies[r] + (s.armies[r] == 1 ? " army" : " armies"),
        line3 = app.hovered.continent.name() + " (+" + GameEngine.BONUSES.get(app.hovered.continent.name()) + ")";
    float w = Math.max(app.width(app.body, line1), Math.max(app.width(app.body, line2), app.width(app.small, line3))) + 28;
    com.badlogic.gdx.math.Vector2 mouse = app.mouse;
    float x = Math.min(mouse.x + 18, 1590 - w), y = Math.max(mouse.y - 18, AtlasCamera.Y + 84);
    round(x, y - 78, w, 78, 8, AtlasApp.alpha(AtlasApp.INK, .94f));
    box(x, y - 78, 4, 78, playerColor(s.owners[r]));
    text(app.body, line1, x + 14, y - 10, AtlasApp.WHITE);
    text(app.body, line2, x + 14, y - 34, playerColor(s.owners[r]));
    text(app.small, line3, x + 14, y - 58, AtlasApp.MUTED);
  }

  private void drawToast() {
    float age = clock - toastAt;
    if (toast.isEmpty() || age > 3.5f) return;
    float a = MathUtils.clamp(3.5f - age, 0, 1);
    float w = Math.min(1200, app.width(app.gameFont, toast) + 56);
    float y = modal == Modal.NONE ? 818 : 40;
    round(800 - w / 2, y, w, 50, 25, AtlasApp.alpha(toastError ? Color.valueOf("3A1B1E") : AtlasApp.INK, .95f * a));
    round(800 - w / 2, y, 8, 50, 4, AtlasApp.alpha(toastError ? RED : AtlasApp.GOLD, a));
    app.gameFont.getData().setScale(Math.min(1, 1140 / Math.max(1, app.width(app.gameFont, toast))));
    center(app.gameFont, toast, 800, y + 33, AtlasApp.alpha(toastError ? Color.valueOf("F2A39D") : AtlasApp.WHITE, a));
    app.gameFont.getData().setScale(1);
  }

  // ---------------------------------------------------------------- console

  private void console() {
    box(0, 0, 1600, AtlasCamera.Y, CONSOLE);
    box(0, AtlasCamera.Y - 2, 1600, 2, LINE);
    round(12, 12, 420, 244, 12, AtlasApp.PANEL);
    round(444, 12, 752, 244, 12, AtlasApp.PANEL);
    round(1208, 12, 380, 244, 12, AtlasApp.PANEL);
    if (game == null) {
      center(app.gameFont, "Open the menu to start or resume a game.", 820, 140, AtlasApp.MUTED);
      return;
    }
    roster();
    actionPanel();
    commandPanel();
  }

  private void roster() {
    GameState s = game.state;
    text(app.small, "COMMANDERS", 28, 246, AtlasApp.MUTED);
    right(app.small, "LANDS", 288, 246, AtlasApp.MUTED);
    right(app.small, "ARMIES", 360, 246, AtlasApp.MUTED);
    right(app.small, "CARDS", 418, 246, AtlasApp.MUTED);
    for (int p = 0; p < 5; p++) {
      int player = p;
      float y = 186 - p * 42;
      boolean alive = game.alive(p), active = p == s.active && s.phase != FINISHED;
      if (active) {
        round(20, y, 404, 40, 8, RAISED_HI);
        round(20, y, 6, 40, 3, playerColor(p));
      }
      token(46, y + 20, 10, alive ? playerColor(p) : AtlasApp.alpha(AtlasApp.MUTED, .5f));
      GameState.Player pl = s.players.get(p);
      Color name = alive ? AtlasApp.WHITE : AtlasApp.MUTED;
      fit(app.gameFont, pl.name, 66, y + 30, 132, name);
      text(app.small, pl.human ? "HUMAN" : "CPU", 66 + Math.min(132, app.width(app.gameFont, pl.name)) + 8, y + 26, AtlasApp.MUTED);
      if (!alive) {
        right(app.body, "ELIMINATED", 418, y + 28, AtlasApp.MUTED);
        continue;
      }
      right(app.body, Integer.toString(game.count(p)), 288, y + 28, AtlasApp.WHITE);
      right(app.body, Long.toString(game.total(p)), 360, y + 28, AtlasApp.WHITE);
      right(app.body, Integer.toString(pl.cards.size()), 418, y + 28, AtlasApp.WHITE);
      app.hits.add(
          new AtlasApp.Hit(
              new Rectangle(20, y, 404, 40),
              () -> {
                if (interactive() && player != s.active && game.alive(player)) {
                  recipient = player;
                  open(Modal.DIPLOMACY);
                }
              }));
    }
  }

  private void stepper() {
    GameState s = game.state;
    if (s.phase == SETUP) {
      round(460, 214, 720, 34, 17, AtlasApp.GOLD);
      center(app.body, "INITIAL PLACEMENT  ·  EVERY COMMANDER PLACES 55 ARMIES", 820, 237, AtlasApp.INK);
      return;
    }
    String[] steps = {"1  REINFORCE", "2  ATTACK", "3  FORTIFY"};
    int current =
        switch (s.phase) {
          case REINFORCE -> 0;
          case ATTACK, OCCUPY -> 1;
          case FORTIFY -> 2;
          default -> 3;
        };
    for (int i = 0; i < 3; i++) {
      float x = 460 + i * 244;
      Color fill = i == current ? AtlasApp.GOLD : i < current ? RAISED : OFF;
      Color ink = i == current ? AtlasApp.INK : i < current ? AtlasApp.MUTED : AtlasApp.alpha(AtlasApp.MUTED, .7f);
      round(x, 214, 232, 34, 17, fill);
      center(app.body, steps[i], x + 116, 237, ink);
      if (i < 2) {
        app.begin();
        app.shapes.setColor(i < current ? AtlasApp.GOLD : LINE);
        app.shapes.triangle(x + 234, 224, x + 234, 238, x + 242, 231);
        app.shapes.end();
      }
    }
  }

  private void actionPanel() {
    GameState s = game.state;
    stepper();
    float x = 464, w = 712;
    if (s.phase == FINISHED) {
      wrap(s.players.get(s.winner).name + " conquered the world!", x, 192, w, AtlasApp.GOLD);
      button("START A NEW GAME", x, 40, 300, 56, Style.PRIMARY, () -> modal = Modal.MENU);
      return;
    }
    if (!s.players.get(s.active).human) {
      txt(s.players.get(s.active).name + " is taking their turn…", x, 192, AtlasApp.WHITE);
      return;
    }
    switch (s.phase) {
      case SETUP, REINFORCE -> deployPanel(x, w);
      case ATTACK -> attackPanel(x, w);
      case OCCUPY -> occupyPanel(x, w);
      case FORTIFY -> fortifyPanel(x, w);
      default -> {}
    }
  }

  private void instruction(String message, Color c) {
    fit(app.gameFont, message, 464, 192, 712, c);
  }

  /** A small right-pointing arrow aligned with a line of game text whose top is {@code y}. */
  private void pointer(float x, float y, Color c) {
    float mid = y - 8;
    app.begin();
    app.line(x + 2, mid, x + 16, mid, 3, c);
    app.shapes.setColor(c);
    app.shapes.triangle(x + 14, mid + 7, x + 14, mid - 7, x + 24, mid);
    app.shapes.end();
  }

  private void territoryLine(int r, long armies, float x, float y) {
    token(x + 12, y - 8, 11, playerColor(game.state.owners[r]));
    txt(game.name(r), x + 32, y, AtlasApp.WHITE);
    float nx = x + 40 + app.width(app.gameFont, game.name(r));
    txt(Long.toString(armies), nx, y, AtlasApp.GOLD);
  }

  private void deployPanel(float x, float w) {
    GameState s = game.state;
    int hand = s.players.get(s.active).cards.size();
    if (s.reserve == 0) {
      if (s.phase == SETUP) instruction("All armies placed. Tap FINISH PLACEMENT to continue.", AtlasApp.GOLD);
      else if (hand >= 5) instruction("You hold " + hand + " cards: trade a set before attacking.", AtlasApp.GOLD);
      else instruction("All armies placed. Trade cards for more, or start attacking.", AtlasApp.GOLD);
      return;
    }
    if (from < 0 || s.owners[from] != s.active) {
      from = -1;
      instruction(
          "Tap one of your highlighted territories to place " + s.reserve + (s.reserve == 1 ? " army." : " armies."),
          AtlasApp.WHITE);
      wrap(
          app.body,
          "• Reinforce borders that face enemies and continents you want to hold.\n"
              + "• Use the slider to split your armies across several territories.\n"
              + (hand >= 3 ? "• You have " + hand + " cards. Open CARDS to trade a matching set." : "• Capture a territory each turn to earn a card."),
          x,
          140,
          w,
          AtlasApp.MUTED);
      return;
    }
    instruction("Place armies on " + game.name(from) + ".", AtlasApp.WHITE);
    territoryLine(from, s.armies[from], x, 150);
    float nx = x + 54 + app.width(app.gameFont, game.name(from)) + app.width(app.gameFont, "" + s.armies[from]);
    pointer(nx, 150, AtlasApp.GOLD);
    txt(Long.toString(s.armies[from] + amount), nx + 34, 150, AtlasApp.GOLD);
    amountControl(x, 46, 500, 1, s.reserve);
    button("PLACE " + amount, x + 516, 46, 196, 52, Style.PRIMARY, this::deploy);
  }

  private void attackPanel(float x, float w) {
    GameState s = game.state;
    if (from >= 0 && (s.owners[from] != s.active || s.armies[from] < 2)) from = to = -1;
    if (to >= 0 && (from < 0 || !game.canAttack(from, to))) to = -1;
    if (from < 0) {
      instruction("Choose where to attack from, or tap an enemy territory.", AtlasApp.WHITE);
      wrap(
          app.body,
          "• Gold rings mark your territories that can attack (2+ armies next to an enemy).\n"
              + "• Tap an enemy first and your strongest neighbour is picked for you.\n"
              + "• Finished? Tap END ATTACKS to move armies and end your turn.",
          x,
          140,
          w,
          AtlasApp.MUTED);
      lastRoll(x, 30);
      return;
    }
    if (to < 0) {
      instruction("Attack from " + game.name(from) + " (" + s.armies[from] + ") — pick a target.", AtlasApp.WHITE);
      List<Integer> targets = new ArrayList<>();
      for (int n : game.neighbours(from)) if (game.canAttack(from, n)) targets.add(n);
      targets.sort(Comparator.comparingLong(n -> s.armies[n]));
      for (int i = 0; i < Math.min(8, targets.size()); i++) {
        int t = targets.get(i);
        float bx = x + (i % 4) * 180, by = 98 - (i / 4) * 60;
        String label = game.name(t) + "  " + s.armies[t];
        button(label, bx + 0, by, 172, 52, Style.NORMAL, () -> to = t);
        token(bx + 12, by + 40, 6, playerColor(s.owners[t]));
      }
      return;
    }
    instruction("Battle for " + game.name(to) + " (" + s.players.get(s.owners[to]).name + ")", AtlasApp.WHITE);
    territoryLine(from, s.armies[from], x, 152);
    float mx = x + 60 + app.width(app.gameFont, game.name(from)) + app.width(app.gameFont, "" + s.armies[from]);
    txt("vs", mx, 152, AtlasApp.MUTED);
    territoryLine(to, s.armies[to], mx + 36, 152);
    double odds = game.conquestChance(from, to);
    Color oc = odds >= .7 ? GREEN : odds >= .4 ? AtlasApp.GOLD : RED;
    right(app.body, "BLITZ WIN CHANCE", x + w - 70, 147, AtlasApp.MUTED);
    right(app.gameFont, Math.round(odds * 100) + "%", x + w, 152, oc);
    int maxDice = (int) Math.min(3, s.armies[from] - 1);
    dice = Math.max(1, Math.min(dice, maxDice));
    for (int d = 1; d <= 3; d++) {
      int n = d;
      float bx = x + (d - 1) * 60;
      boolean ok = d <= maxDice;
      if (d == dice) round(bx - 3, 69, 58, 58, 10, AtlasApp.GOLD);
      die(bx, 72, 52, d, ok ? (d == dice ? PARCHMENT : Color.valueOf("B9B3A3")) : OFF, ok ? AtlasApp.INK : AtlasApp.MUTED);
      app.hits.add(
          new AtlasApp.Hit(
              new Rectangle(bx, 72, 52, 52),
              () -> action(() -> {
                if (n > maxDice) refuse("Attacking with " + n + " dice needs " + (n + 1) + " armies.");
                dice = n;
              })));
    }
    text(app.small, "ATTACK DICE", x, 64, AtlasApp.MUTED);
    button("ROLL", x + 188, 72, 160, 52, Style.PRIMARY, () -> roll(false));
    button("BLITZ", x + 360, 72, 160, 52, Style.DANGER, () -> roll(true));
    button("CANCEL", x + 532, 72, 180, 52, Style.NORMAL, () -> to = -1);
    lastRoll(x + 188, 30);
  }

  private void lastRoll(float x, float y) {
    if (shownAttack.length == 0) return;
    boolean rolling = animation > 0;
    int tick = (int) (clock * 24);
    int[] a = shownAttack.clone(), d = shownDefend.clone();
    Arrays.sort(a);
    Arrays.sort(d);
    int pairs = Math.min(a.length, d.length);
    for (int i = 0; i < a.length; i++) {
      int v = rolling ? 1 + (tick + i * 3) % 6 : a[a.length - 1 - i];
      // After the roll, the losing die of each compared pair is faded.
      boolean lost = !rolling && i < pairs && a[a.length - 1 - i] <= d[d.length - 1 - i];
      die(x + i * 36, y, 30, v, lost ? AtlasApp.alpha(RED, .35f) : RED, AtlasApp.WHITE);
    }
    float dx = x + 3 * 36 + 18;
    for (int i = 0; i < d.length; i++) {
      int v = rolling ? 1 + (tick + i * 2) % 6 : d[d.length - 1 - i];
      boolean lost = !rolling && i < pairs && a[a.length - 1 - i] > d[d.length - 1 - i];
      die(dx + i * 36, y, 30, v, lost ? AtlasApp.alpha(PARCHMENT, .3f) : PARCHMENT, AtlasApp.INK);
    }
    float sx = dx + 2 * 36 + 16;
    if (!rolling) fit(app.body, rollSummary, sx, y + 22, 1180 - sx, AtlasApp.WHITE);
  }

  private void occupyPanel(float x, float w) {
    GameState s = game.state;
    int f = s.occupationFrom, t = s.occupationTo;
    long max = s.armies[f] - 1, min = s.occupationMinimum;
    instruction(game.name(t) + " captured!  Move armies in from " + game.name(f) + ".", AtlasApp.GOLD);
    amountControl(x, 86, 500, min, max);
    text(app.body, game.name(f) + " keeps " + (s.armies[f] - amount), x + 76, 152, AtlasApp.MUTED);
    button("MOVE " + amount, x + 516, 86, 196, 52, Style.PRIMARY, this::occupy);
    lastRoll(x, 24);
  }

  private void fortifyPanel(float x, float w) {
    GameState s = game.state;
    if (from >= 0 && (s.owners[from] != s.active || game.movable(from) <= 0)) from = to = -1;
    if (from < 0) {
      instruction("Move armies between connected territories, or end your turn.", AtlasApp.WHITE);
      wrap(
          app.body,
          "• Tap a territory to move from, then a highlighted destination.\n"
              + "• Armies travel through your own connected land (and allies with open borders).\n"
              + "• Each army can move once per turn. Moving is optional.",
          x,
          140,
          w,
          AtlasApp.MUTED);
      return;
    }
    if (to < 0) {
      instruction(
          "Move from " + game.name(from) + " (" + game.movable(from) + " can move) — pick a highlighted destination.",
          AtlasApp.WHITE);
      button("CANCEL", x, 46, 180, 52, Style.NORMAL, () -> from = to = -1);
      return;
    }
    instruction("Move armies from " + game.name(from) + " to " + game.name(to) + ".", AtlasApp.WHITE);
    territoryLine(from, s.armies[from] - amount, x, 150);
    float mx = x + 60 + app.width(app.gameFont, game.name(from)) + app.width(app.gameFont, "" + (s.armies[from] - amount));
    pointer(mx, 150, AtlasApp.MUTED);
    territoryLine(to, s.armies[to] + amount, mx + 32, 150);
    amountControl(x, 46, 500, 1, game.movable(from));
    button("MOVE " + amount, x + 516, 46, 196, 52, Style.PRIMARY, this::fortify);
  }

  private void commandPanel() {
    GameState s = game.state;
    float x = 1226;
    if (s.phase == FINISHED) {
      text(app.heading, s.players.get(s.winner).name, x, 244, playerColor(s.winner));
      text(app.small, "WINNER", x, 206, AtlasApp.MUTED);
      return;
    }
    GameState.Player pl = s.players.get(s.active);
    fit(app.heading, pl.name, x, 244, 340, playerColor(s.active));
    text(
        app.small,
        pl.human ? "HUMAN COMMANDER" : "COMPUTER  ·  " + s.difficulty.name().toUpperCase(Locale.ROOT),
        x,
        208,
        AtlasApp.MUTED);
    if (!pl.human) return;
    int hand = pl.cards.size();
    boolean canTrade = s.phase == REINFORCE && !game.findSet(s.active).isEmpty();
    switch (s.phase) {
      case SETUP, REINFORCE -> {
        text(app.big, Long.toString(s.reserve), x, 186, AtlasApp.GOLD);
        text(app.body, s.reserve == 1 ? "ARMY TO PLACE" : "ARMIES TO PLACE", x + 14 + app.width(app.big, "" + s.reserve), 168, AtlasApp.WHITE);
        if (income != null && s.phase == REINFORCE)
          text(
              app.small,
              "Lands +" + income[0] + "   Continents +" + income[1] + "   Army bonus +" + income[2],
              x,
              150,
              AtlasApp.MUTED);
      }
      case ATTACK, OCCUPY ->
          wrap(
              app.body,
              s.conquered
                  ? "You have captured territory this turn and will draw a card."
                  : "Capture at least one territory to draw a card at the end of your turn.",
              x,
              180,
              344,
              s.conquered ? GREEN : AtlasApp.MUTED);
      case FORTIFY ->
          wrap(
              app.body,
              "Optional: reposition your armies, then end your turn."
                  + (s.conquered ? " You will draw a card." : ""),
              x,
              180,
              344,
              AtlasApp.MUTED);
      default -> {}
    }
    if (canTrade)
      button(
          "TRADE CARDS  +" + game.nextSetValue(),
          x - 2,
          98,
          348,
          40,
          hand >= 5 ? Style.PRIMARY : Style.SELECTED,
          this::trade);
    String label;
    Style style = Style.PRIMARY;
    switch (s.phase) {
      case SETUP -> {
        label = s.reserve > 0 ? "PLACE " + s.reserve + " MORE" : "FINISH PLACEMENT";
        if (s.reserve > 0) style = Style.DISABLED;
      }
      case REINFORCE -> {
        if (hand >= 5) {
          label = "TRADE CARDS FIRST";
          style = Style.DISABLED;
        } else if (s.reserve > 0) {
          label = "PLACE " + s.reserve + " MORE";
          style = Style.DISABLED;
        } else label = "START ATTACKING";
      }
      case ATTACK -> label = "END ATTACKS";
      case OCCUPY -> {
        label = "MOVE " + amount + " IN";
      }
      case FORTIFY -> label = "END TURN";
      default -> label = "";
    }
    button(label, x - 2, 26, 348, 62, style, this::primary);
  }

  // ---------------------------------------------------------------- modals

  private void open(Modal next) {
    if (game == null || !humanTurn()) {
      if (next == Modal.SETTINGS) modal = next;
      else if (game != null && next == Modal.REPORT) modal = next;
      return;
    }
    if (animation > 0) return;
    modal = next;
    page = 0;
    cards.clear();
  }

  private void panel(String title) {
    box(0, 0, 1600, 1000, new Color(.02f, .045f, .07f, .9f));
    round(220, 86, 1160, 828, 18, AtlasApp.PANEL);
    round(220, 902, 1160, 12, 6, AtlasApp.GOLD);
    text(app.heading, title, 260, 870, AtlasApp.GOLD);
    if (modal != Modal.MENU && modal != Modal.HANDOVER)
      button("CLOSE", 1210, 828, 140, 48, Style.NORMAL, this::closeModal);
  }

  private void closeModal() {
    if (modal == Modal.PICK) modal = returnModal;
    else if (modal == Modal.OFFER || modal == Modal.COMPOSE) modal = Modal.DIPLOMACY;
    else if (modal == Modal.RULES) modal = rulesReturn;
    else modal = game == null ? Modal.MENU : Modal.NONE;
    if (modal == Modal.SETTINGS && game == null) modal = Modal.MENU;
  }

  private void drawModal() {
    switch (modal) {
      case MENU -> menu();
      case HANDOVER -> handover();
      case REPORT -> report();
      case CARDS -> cards();
      case DIPLOMACY -> diplomacy();
      case OFFER -> offer();
      case COMPOSE -> compose();
      case PICK -> picker();
      case SETTINGS -> settings();
      case RULES -> rules();
      default -> {}
    }
  }

  private void menu() {
    panel("");
    text(app.title, "GAME OF SUPREMACY", 260, 880, AtlasApp.WHITE);
    text(app.body, "Five commanders. Seven continents. One world.", 262, 826, AtlasApp.MUTED);
    // Seats.
    text(app.small, "COMMANDERS  ·  TAP A SEAT TO SWITCH HUMAN / COMPUTER", 260, 780, AtlasApp.GOLD);
    for (int i = 0; i < 5; i++) {
      int seat = i;
      float y = 704 - i * 66;
      boolean human = i < humans;
      round(260, y, 500, 58, 10, RAISED);
      token(292, y + 29, 14, playerColor(i));
      String name = human ? "Player " + (i + 1) : GameState.BOT_NAMES[i - humans];
      txt(name, 320, y + 39, AtlasApp.WHITE);
      button(
          human ? "HUMAN" : "COMPUTER",
          590,
          y + 9,
          156,
          40,
          human ? Style.SELECTED : Style.NORMAL,
          () -> {
            confirmNew = false;
            if (seat == 0) refuse("Seat 1 is always a human player.");
            humans = seat < humans ? seat : seat + 1;
          });
    }
    // Skill.
    text(app.small, "COMPUTER SKILL", 800, 780, AtlasApp.GOLD);
    GameState.Difficulty[] levels = GameState.Difficulty.values();
    for (int i = 0; i < levels.length; i++) {
      int level = i;
      float bx = 800 + (i % 2) * 280, by = 704 - (i / 2) * 76;
      boolean chosen = i == difficulty;
      if (chosen) round(bx - 2, by - 2, 268, 66, 11, AtlasApp.GOLD);
      round(bx, by, 264, 62, 10, chosen ? RAISED_HI : RAISED);
      txt(levels[i].name(), bx + 16, by + 46, chosen ? AtlasApp.GOLD : AtlasApp.WHITE);
      text(app.small, SKILL_NOTES[i], bx + 16, by + 20, AtlasApp.MUTED);
      app.hits.add(
          new AtlasApp.Hit(
              new Rectangle(bx, by, 264, 62),
              () -> {
                difficulty = level;
                confirmNew = false;
              }));
    }
    wrap(
        app.body,
        (5 - humans == 0 ? "All five seats are human." : (5 - humans) + " computer opponents share this skill level.")
            + " Territories are dealt at random and every commander starts with 55 armies."
            + (humans > 1 ? " Pass the device when prompted; cards stay private." : ""),
        800,
        448,
        540,
        AtlasApp.MUTED);
    boolean saved = saves.exists();
    button("HOW TO PLAY", 260, 120, 240, 64, Style.NORMAL, () -> rules(Modal.MENU));
    if (game != null && game.state.phase != FINISHED)
      button(
          "RETURN TO GAME",
          520,
          120,
          330,
          64,
          Style.NORMAL,
          () -> {
            lastActive = -1;
            checkTurn();
          });
    else if (saved)
      button(
          "RESUME SAVED GAME",
          520,
          120,
          330,
          64,
          Style.NORMAL,
          () -> {
            GameEngine restored;
            try {
              restored = saves.load(app.board);
            } catch (IllegalStateException e) {
              if (String.valueOf(e.getMessage()).contains("different board"))
                refuse("This saved game uses an older version of the map. Start a new game.");
              throw e;
            }
            String warning = saves.warning();
            attach(restored);
            if (warning != null && !warning.isEmpty()) say(warning, true);
          });
    button(
        confirmNew ? "TAP AGAIN TO REPLACE SAVE" : "START NEW GAME",
        870,
        120,
        470,
        64,
        confirmNew ? Style.DANGER : Style.PRIMARY,
        () -> {
          if ((game != null || saved) && !confirmNew) {
            confirmNew = true;
            say("Starting a new game replaces your saved game.", false);
          } else {
            confirmNew = false;
            newMatch();
          }
        });
  }

  private void newMatch() {
    attach(
        new GameEngine(
            app.board,
            humans,
            GameState.Difficulty.values()[difficulty],
            Long.getLong("supremacy.seed", System.nanoTime())));
  }

  private List<String> visibleReport() {
    String self = game.state.players.get(game.state.active).name + " ";
    return report.stream()
        .filter(l -> !l.contains("; losses"))
        .filter(l -> logMode || (!l.startsWith(self) && !l.startsWith("Random territories")))
        .collect(java.util.stream.Collectors.toList());
  }

  private void handover() {
    GameState s = game.state;
    String name = s.players.get(s.active).name;
    Color c = playerColor(s.active);
    box(0, 0, 1600, 1000, new Color(.02f, .045f, .07f, .97f));
    round(400, 230, 800, 540, 20, AtlasApp.PANEL);
    round(400, 752, 800, 18, 9, c);
    token(800, 650, 34, c);
    center(app.title, name.toUpperCase(Locale.ROOT), 800, 590, AtlasApp.WHITE);
    center(
        app.gameFont,
        s.phase == SETUP ? "Place your starting armies." : "Your turn " + s.turn + " is ready.",
        800,
        530,
        AtlasApp.GOLD);
    center(app.body, "Pass the device to " + name + ". Everyone else, look away —", 800, 478, AtlasApp.MUTED);
    center(app.body, "cards stay private until this player continues.", 800, 452, AtlasApp.MUTED);
    int events = visibleReport().size();
    if (events > 0 && s.phase != SETUP)
      center(app.body, events + (events == 1 ? " event" : " events") + " since your last turn.", 800, 410, AtlasApp.WHITE);
    button(
        "I'M " + name.toUpperCase(Locale.ROOT) + "  —  START",
        520,
        280,
        560,
        72,
        Style.PRIMARY,
        this::showReport);
  }

  private void report() {
    panel(logMode ? "BATTLE LOG  ·  NEWEST FIRST" : "WHILE YOU WERE AWAY");
    List<String> lines = visibleReport();
    String me = game.state.players.get(game.state.active).name;
    float top = 784;
    if (!logMode) {
      // Lead with what matters most to the returning player: the land they lost.
      String marker = " attacked " + me + " and won ";
      List<String> lost = new ArrayList<>();
      for (String l : lines)
        if (l.contains(marker)) lost.add(l.substring(l.indexOf(marker) + marker.length()).replace(".", ""));
      String summary =
          lost.isEmpty()
              ? "Your territories held. No land was lost."
              : "You lost " + lost.size() + (lost.size() == 1 ? " territory: " : " territories: ") + String.join(", ", lost) + ".";
      round(260, 742, 1080, 58, 10, lost.isEmpty() ? RAISED : Color.valueOf("3A1B1E"));
      fit(app.gameFont, summary, 280, 780, 1040, lost.isEmpty() ? GREEN : Color.valueOf("F2A39D"));
      top = 700;
    }
    if (lines.isEmpty()) txt("Nothing new to report.", 260, top, AtlasApp.MUTED);
    int per = logMode ? 9 : 8;
    for (int i = page * per; i < Math.min(lines.size(), page * per + per); i++) {
      float y = top - (i - page * per) * 66;
      String line = lines.get(i);
      Color dot =
          line.contains("attacked " + me) || line.contains(me + " was eliminated")
              ? RED
              : line.contains(" won ") || line.contains("conquered") ? AtlasApp.GOLD : AtlasApp.MUTED;
      token(272, y - 12, 6, dot);
      wrap(line, 296, y, 1040, line.contains("attacked " + me) ? Color.valueOf("F2A39D") : AtlasApp.WHITE);
    }
    paging(lines.size(), per);
    button("CONTINUE", 1050, 106, 300, 56, Style.PRIMARY, () -> modal = Modal.NONE);
  }

  private void cards() {
    GameState s = game.state;
    panel("YOUR CARDS");
    List<Integer> hand = s.players.get(s.active).cards;
    text(
        app.body,
        "Trade three of a kind, or one of each type. Wilds match anything. Next set: +"
            + game.nextSetValue()
            + " armies.",
        262,
        826,
        AtlasApp.MUTED);
    if (hand.isEmpty())
      wrap("No cards yet. Capture at least one territory during your turn to draw a card.", 260, 700, 1000, AtlasApp.MUTED);
    int per = 12;
    for (int i = page * per; i < Math.min(hand.size(), page * per + per); i++) {
      int card = hand.get(i), k = i - page * per;
      float cx = 270 + (k % 6) * 178, cy = (k < 6 ? 560 : 300) + (cards.contains(card) ? 16 : 0);
      drawCard(card, cx, cy, cards.contains(card));
      app.hits.add(
          new AtlasApp.Hit(
              new Rectangle(cx, cy, 160, 220),
              () -> {
                if (!cards.remove(Integer.valueOf(card)) && cards.size() < 3) cards.add(card);
              }));
    }
    if (hand.size() > per) paging(hand.size(), per);
    boolean valid = cards.size() == 3 && game.validSet(cards);
    boolean phaseOk = s.phase == REINFORCE;
    button(
        "SELECT A SET FOR ME",
        hand.size() > per ? 640 : 260,
        106,
        330,
        56,
        Style.NORMAL,
        () -> {
          cards.clear();
          cards.addAll(game.findSet(s.active));
          if (cards.isEmpty()) refuse("No matching set in your hand yet.");
        });
    button(
        "TRADE IN  +" + game.nextSetValue(),
        1010,
        106,
        340,
        56,
        valid && phaseOk ? Style.PRIMARY : Style.DISABLED,
        () -> {
          if (!phaseOk) refuse("Cards can be traded during the reinforce step of your turn.");
          if (!valid) refuse("Select three cards: three of a kind or one of each.");
          trade();
          modal = Modal.NONE;
        });
  }

  private void drawCard(int card, float x, float y, boolean selected) {
    int symbol = game.symbol(card);
    boolean owned = card < game.state.owners.length && game.state.owners[card] == game.state.active;
    app.begin();
    if (selected) app.round(x - 5, y - 5, 170, 230, 14, AtlasApp.GOLD);
    app.round(x + 2, y - 4, 160, 220, 11, AtlasApp.alpha(Color.BLACK, .45f));
    app.round(x, y, 160, 220, 11, PARCHMENT);
    app.round(x + 8, y + 8, 144, 204, 7, Color.valueOf("E3D7BA"));
    if (symbol < 3) app.rect(x + 8, y + 172, 144, 6, Color.valueOf(app.board.regions.get(card).continent.color()));
    float cx = x + 80, cy = y + 104;
    Color ink = Color.valueOf("3A3324");
    switch (symbol) {
      case 0 -> infantry(cx, cy, ink);
      case 1 -> cavalry(cx, cy, ink);
      case 2 -> artillery(cx, cy, ink);
      default -> star(cx, cy + 6, 34, Color.valueOf("9C7A2E"));
    }
    app.shapes.end();
    String name = symbol == 3 ? "WILD CARD" : game.name(card);
    app.small.getData().setScale(Math.min(1, 136 / Math.max(1, app.width(app.small, name))));
    center(app.small, name, x + 80, y + 202, ink);
    app.small.getData().setScale(1);
    center(app.small, CARD_TYPES[symbol], x + 80, y + 40, Color.valueOf("6E6247"));
    if (owned) {
      round(x + 104, y + 12, 44, 20, 10, AtlasApp.GOLD);
      center(app.small, "+2", x + 126, y + 27, AtlasApp.INK);
    }
  }

  private void infantry(float x, float y, Color c) {
    app.shapes.setColor(c);
    app.shapes.circle(x, y + 30, 11, 20);
    app.shapes.triangle(x - 20, y - 22, x + 20, y - 22, x, y + 22);
    app.shapes.rect(x - 24, y - 32, 48, 10);
  }

  private void cavalry(float x, float y, Color c) {
    for (int i = 0; i < 18; i++) {
      float a = MathUtils.degreesToRadians * (-50 + i * 280f / 18), b = MathUtils.degreesToRadians * (-50 + (i + 1) * 280f / 18);
      app.line(x + MathUtils.cos(a) * 26, y + MathUtils.sin(a) * 26, x + MathUtils.cos(b) * 26, y + MathUtils.sin(b) * 26, 11, c);
    }
    app.shapes.setColor(Color.valueOf("E3D7BA"));
    for (int i = 0; i < 6; i++) {
      float a = MathUtils.degreesToRadians * (-20 + i * 44);
      app.shapes.circle(x + MathUtils.cos(a) * 26, y + MathUtils.sin(a) * 26, 2.2f, 8);
    }
  }

  private void artillery(float x, float y, Color c) {
    app.line(x - 14, y - 8, x + 30, y + 22, 13, c);
    app.line(x - 14, y - 8, x - 34, y - 26, 5, c);
    app.ring(x - 8, y - 16, 14, 4, c);
    app.shapes.setColor(c);
    app.shapes.circle(x - 8, y - 16, 4, 10);
    app.shapes.rect(x - 40, y - 34, 70, 4);
  }

  private void star(float x, float y, float r, Color c) {
    app.shapes.setColor(c);
    for (int k = 0; k < 5; k++) {
      float a = MathUtils.PI / 2 + k * MathUtils.PI2 / 5;
      float l = a - MathUtils.PI / 5, rr = a + MathUtils.PI / 5, in = r * .42f;
      float ox = x + MathUtils.cos(a) * r, oy = y + MathUtils.sin(a) * r;
      app.shapes.triangle(x, y, x + MathUtils.cos(l) * in, y + MathUtils.sin(l) * in, ox, oy);
      app.shapes.triangle(x, y, ox, oy, x + MathUtils.cos(rr) * in, y + MathUtils.sin(rr) * in);
    }
  }

  private void diplomacy() {
    panel("DIPLOMACY");
    text(app.body, "Offers are delivered on the recipient's turn. Responses arrive on your next turn.", 262, 826, AtlasApp.MUTED);
    List<GameState.Offer> inbox = game.inbox();
    text(app.small, "INCOMING OFFERS", 280, 780, AtlasApp.GOLD);
    for (int i = page * 5; i < Math.min(inbox.size(), page * 5 + 5); i++) {
      GameState.Offer o = inbox.get(i);
      button(
          "#" + o.id + "  FROM " + game.state.players.get(o.from).name.toUpperCase(Locale.ROOT) + "  —  REVIEW",
          280,
          700 - (i - page * 5) * 64,
          970,
          () -> {
            viewOffer = o;
            page = 0;
            modal = Modal.OFFER;
          });
    }
    if (inbox.isEmpty()) txt("No pending offers.", 280, 740, AtlasApp.MUTED);
    if (inbox.size() > 5) paging(inbox.size(), 5);
    if (recipient == game.state.active || !game.alive(recipient)) cycleRecipient();
    text(app.small, "NEW OFFER", 280, 368, AtlasApp.GOLD);
    button("TO: " + game.state.players.get(recipient).name + "  (change)", 280, 300, 470, () -> cycleRecipient());
    token(306, 326, 10, playerColor(recipient));
    button(
        "COMPOSE OFFER",
        780,
        300,
        470,
        52,
        Style.PRIMARY,
        () -> {
          draft = new GameState.Offer();
          draft.from = game.state.active;
          draft.to = recipient;
          counterId = -1;
          armyPage = false;
          page = 0;
          modal = Modal.COMPOSE;
        });
    boolean borders = game.state.borders[game.state.active][recipient];
    button(
        borders ? "REVOKE OPEN BORDERS WITH " + game.state.players.get(recipient).name.toUpperCase(Locale.ROOT) : "NO OPEN BORDERS WITH " + game.state.players.get(recipient).name.toUpperCase(Locale.ROOT),
        280,
        230,
        970,
        52,
        borders ? Style.DANGER : Style.DISABLED,
        () -> {
          if (borders) {
            game.revokeBorders(recipient);
            say("Open borders revoked.", false);
          } else refuse("Open borders are agreed through an accepted offer.");
        });
  }

  private void cycleRecipient() {
    do {
      recipient = (recipient + 1) % 5;
    } while (recipient == game.state.active || !game.alive(recipient));
  }

  private String gift(long n, int source, int target) {
    return n == 0
        ? ""
        : "; " + n + " armies from " + game.name(source) + " to " + game.name(target);
  }

  private void offer() {
    panel("OFFER #" + viewOffer.id + "  FROM " + game.state.players.get(viewOffer.from).name.toUpperCase(Locale.ROOT));
    List<String> lines = new ArrayList<>();
    for (int r : viewOffer.giveTerritories) lines.add("YOU RECEIVE TERRITORY: " + game.name(r));
    for (int r : viewOffer.wantTerritories) lines.add("YOU GIVE TERRITORY: " + game.name(r));
    if (viewOffer.giveArmies > 0)
      lines.add("YOU RECEIVE" + gift(viewOffer.giveArmies, viewOffer.giveSource, viewOffer.giveTarget));
    if (viewOffer.wantArmies > 0)
      lines.add("YOU SEND" + gift(viewOffer.wantArmies, viewOffer.wantSource, viewOffer.wantTarget));
    lines.add("MUTUAL OPEN BORDERS: " + (viewOffer.openBorders ? "included" : "not included"));
    lines.add("Garrisons return automatically. Terms are rechecked when accepted.");
    for (int i = page * 7; i < Math.min(lines.size(), page * 7 + 7); i++)
      wrap(lines.get(i), 280, 780 - (i - page * 7) * 62, 1020, i == lines.size() - 1 ? AtlasApp.MUTED : AtlasApp.WHITE);
    if (lines.size() > 7) paging(lines.size(), 7);
    button(
        "ACCEPT",
        280,
        230,
        300,
        56,
        Style.PRIMARY,
        () -> {
          boolean accepted = game.answer(viewOffer.id, true);
          say(accepted ? "Exchange completed." : "Offer expired because its terms changed.", !accepted);
          modal = Modal.DIPLOMACY;
        });
    button(
        "REJECT",
        610,
        230,
        300,
        56,
        Style.DANGER,
        () -> {
          game.answer(viewOffer.id, false);
          modal = Modal.DIPLOMACY;
        });
    button(
        "COUNTER",
        940,
        230,
        300,
        56,
        Style.NORMAL,
        () -> {
          draft = new GameState.Offer();
          draft.from = viewOffer.to;
          draft.to = viewOffer.from;
          draft.giveTerritories.addAll(viewOffer.wantTerritories);
          draft.wantTerritories.addAll(viewOffer.giveTerritories);
          draft.giveSource = viewOffer.wantSource;
          draft.giveTarget = viewOffer.wantTarget;
          draft.giveArmies = viewOffer.wantArmies;
          draft.wantSource = viewOffer.giveSource;
          draft.wantTarget = viewOffer.giveTarget;
          draft.wantArmies = viewOffer.giveArmies;
          draft.openBorders = viewOffer.openBorders;
          counterId = viewOffer.id;
          armyPage = false;
          page = 0;
          modal = Modal.COMPOSE;
        });
  }

  private void compose() {
    panel("OFFER TO " + game.state.players.get(draft.to).name.toUpperCase(Locale.ROOT));
    button("TERRITORIES", 280, 770, 480, 48, armyPage ? Style.NORMAL : Style.SELECTED, () -> {
      armyPage = false;
      page = 0;
    });
    button("ARMIES + OPEN BORDERS", 780, 770, 470, 48, armyPage ? Style.SELECTED : Style.NORMAL, () -> {
      armyPage = true;
      page = 0;
    });
    if (!armyPage) {
      List<Integer> own = owned(draft.from), theirs = owned(draft.to);
      text(app.small, "YOU GIVE", 280, 742, AtlasApp.GOLD);
      text(app.small, "YOU REQUEST", 780, 742, AtlasApp.GOLD);
      for (int i = page * 6; i < Math.min(Math.max(own.size(), theirs.size()), page * 6 + 6); i++) {
        float y = 664 - (i - page * 6) * 62;
        if (i < own.size()) {
          int r = own.get(i);
          button(
              game.name(r) + "  (" + game.state.armies[r] + ")",
              280,
              y,
              470,
              draft.giveTerritories.contains(r),
              () -> toggle(draft.giveTerritories, r));
        }
        if (i < theirs.size()) {
          int r = theirs.get(i);
          button(
              game.name(r) + "  (" + game.state.armies[r] + ")",
              780,
              y,
              470,
              draft.wantTerritories.contains(r),
              () -> toggle(draft.wantTerritories, r));
        }
      }
      paging(Math.max(own.size(), theirs.size()), 6);
    } else {
      giftEditor(true, 662);
      giftEditor(false, 450);
      button(
          draft.openBorders ? "MUTUAL OPEN BORDERS INCLUDED" : "ADD MUTUAL OPEN BORDERS",
          280,
          200,
          970,
          draft.openBorders,
          () -> draft.openBorders = !draft.openBorders);
    }
    button(
        "SEND OFFER",
        1050,
        106,
        300,
        56,
        Style.PRIMARY,
        () -> {
          if (counterId < 0) game.sendOffer(draft);
          else game.counter(counterId, draft);
          modal = Modal.DIPLOMACY;
          page = 0;
          say("Offer sent. The response arrives on your next turn.", false);
        });
  }

  private void toggle(List<Integer> values, int r) {
    if (!values.remove(Integer.valueOf(r))) values.add(r);
  }

  private void giftEditor(boolean give, float y) {
    int source = give ? draft.giveSource : draft.wantSource,
        target = give ? draft.giveTarget : draft.wantTarget;
    long n = give ? draft.giveArmies : draft.wantArmies;
    int owner = give ? draft.from : draft.to, receiver = give ? draft.to : draft.from;
    txt((give ? "SEND ARMIES: " : "REQUEST ARMIES: ") + n, 280, y + 45, AtlasApp.GOLD);
    button(
        source < 0 ? "CHOOSE SOURCE" : "FROM " + game.name(source),
        280,
        y - 20,
        470,
        () ->
            pick(
                "ARMY SOURCE",
                owned(owner),
                r -> {
                  if (give) {
                    draft.giveSource = r;
                    draft.giveTarget = -1;
                    draft.giveArmies = 0;
                  } else {
                    draft.wantSource = r;
                    draft.wantTarget = -1;
                    draft.wantArmies = 0;
                  }
                }));
    button(
        target < 0 ? "CHOOSE ADJACENT DESTINATION" : "TO " + game.name(target),
        780,
        y - 20,
        470,
        () -> {
          if (source < 0) refuse("Choose a source first.");
          List<Integer> targets = new ArrayList<>();
          for (int r : game.neighbours(source))
            if (game.state.owners[r] == receiver) targets.add(r);
          pick(
              "ADJACENT DESTINATION",
              targets,
              r -> {
                if (give) draft.giveTarget = r;
                else draft.wantTarget = r;
              });
        });
    button("−", 280, y - 82, 150, () -> setGift(give, Math.max(0, n - 1)));
    button("+", 445, y - 82, 150, () -> setGift(give, n + 1));
    button("+10", 610, y - 82, 150, () -> setGift(give, n + 10));
    button(
        "MAX",
        780,
        y - 82,
        470,
        () -> setGift(give, source < 0 ? 0 : game.state.armies[source] - 1));
  }

  private void setGift(boolean give, long n) {
    int source = give ? draft.giveSource : draft.wantSource;
    if (source < 0) refuse("Choose a source first.");
    n = Math.min(n, game.state.armies[source] - 1);
    if (give) draft.giveArmies = n;
    else draft.wantArmies = n;
  }

  private List<Integer> owned(int p) {
    List<Integer> list = new ArrayList<>();
    for (int r = 0; r < game.state.owners.length; r++) if (game.state.owners[r] == p) list.add(r);
    return list;
  }

  private void pick(String title, List<Integer> options, java.util.function.IntConsumer callback) {
    returnModal = modal;
    pickTitle = title;
    picks = options;
    pickAction = callback;
    pickPage = 0;
    modal = Modal.PICK;
  }

  private void picker() {
    panel(pickTitle);
    if (picks.isEmpty()) txt("No eligible territories.", 280, 740, AtlasApp.MUTED);
    for (int i = pickPage * 18; i < Math.min(picks.size(), pickPage * 18 + 18); i++) {
      int r = picks.get(i), k = i - pickPage * 18;
      button(
          game.name(r) + "  (" + game.state.armies[r] + ")",
          280 + (k % 3) * 340,
          720 - (k / 3) * 76,
          325,
          () -> {
            pickAction.accept(r);
            modal = returnModal;
          });
    }
    button("PREVIOUS", 280, 106, 220, () -> pickPage = Math.max(0, pickPage - 1));
    button(
        "NEXT",
        515,
        106,
        220,
        () -> {
          if ((pickPage + 1) * 18 < picks.size()) pickPage++;
        });
    button("BACK", 1050, 106, 300, () -> modal = returnModal);
  }

  private void paging(int count, int perPage) {
    int pages = Math.max(1, (count + perPage - 1) / perPage);
    if (pages <= 1) return;
    button("PREVIOUS", 260, 106, 200, 56, Style.NORMAL, () -> page = Math.max(0, page - 1));
    button(
        "NEXT",
        470,
        106,
        200,
        56,
        Style.NORMAL,
        () -> {
          if ((page + 1) * perPage < count) page++;
        });
    text(app.body, "PAGE " + (page + 1) + " / " + pages, 690, 140, AtlasApp.MUTED);
  }

  private void settings() {
    panel("PAUSED");
    button("RESUME", 480, 650, 640, 64, Style.PRIMARY, this::closeModal);
    button(
        "SOUND: " + (soundEnabled ? "ON" : "OFF"),
        480,
        566,
        640,
        64,
        Style.NORMAL,
        () -> {
          soundEnabled = !soundEnabled;
          Gdx.app.getPreferences("supremacy-options").putBoolean("sound", soundEnabled).flush();
        });
    button("HOW TO PLAY", 480, 482, 640, 64, Style.NORMAL, () -> rules(Modal.SETTINGS));
    button(
        "SAVE AND EXIT TO TITLE",
        480,
        398,
        640,
        64,
        Style.NORMAL,
        () -> {
          if (game != null) saves.save(game);
          confirmNew = false;
          modal = Modal.MENU;
        });
    wrap(
        app.body,
        "The game saves after every action and whenever the app is paused.\n"
            + "Map: drag to pan, pinch, scroll or the + and - buttons to zoom, FIT to see the whole world.\n"
            + "Keyboard: Enter = next step, R = roll, B = blitz, Esc = back.",
        480,
        320,
        700,
        AtlasApp.MUTED);
  }

  private void rules(Modal back) {
    rulesReturn = back;
    page = 0;
    modal = Modal.RULES;
  }

  private void rules() {
    panel("HOW TO PLAY  ·  " + (page % 4 + 1) + " / 4");
    String[] pages = {
      "GOAL\nConquer all "
          + app.board.regions.size()
          + " territories. Five commanders share the world; territories are dealt at"
          + " random and everyone places 55 starting armies.\n\n"
          + "YOUR TURN\n1  REINFORCE — tap your territories to place new armies.\n2  ATTACK —"
          + " tap your territory, then an enemy neighbour, then ROLL or BLITZ.\n3  FORTIFY —"
          + " move armies between your connected territories, then END TURN.",
      "REINFORCEMENTS\nmax(3, lands ÷ 3) + continent bonuses + your total armies ÷ 5.\n\n"
          + "CONTINENT BONUSES\nNorth America 9 · Asia 8 · Europe 6 · Africa 5 · Australia 4 ·"
          + " South America 3 · Middle-East 3. The bonus panel on the map shows who holds each"
          + " continent.",
      "BATTLES\nChoose 1–3 attack dice (you need one more army than dice). The defender rolls up to"
          + " two. Highest dice are compared in pairs; ties go to the defender. BLITZ keeps rolling"
          + " with maximum dice until you capture the territory or run out of attackers.\n\n"
          + "After a capture, move at least as many armies as dice rolled; one stays behind.",
      "CARDS\nCapture at least one territory to draw a card at the end of your turn. Trade three of"
          + " a kind or one infantry, cavalry and artillery (wilds match anything) for 4, 6, 8, 10,"
          + " 12, 15, then +5 armies. A card showing land you own gives +2 armies there. With five"
          + " or more cards you must trade.\n\n"
          + "DIPLOMACY\nOffer territories, armies or open borders. Open borders let armies pass"
          + " through allied land while fortifying; it is not a ceasefire."
    };
    wrap(pages[page % pages.length], 280, 800, 1040, AtlasApp.WHITE);
    button("PREVIOUS", 260, 106, 220, 56, Style.NORMAL, () -> page = (page + pages.length - 1) % pages.length);
    button("NEXT", 490, 106, 220, 56, Style.NORMAL, () -> page = (page + 1) % pages.length);
    button("BACK", 1130, 106, 220, 56, Style.PRIMARY, this::closeModal);
  }

  void pause() {
    if (game != null) {
      try {
        saves.save(game);
      } catch (RuntimeException e) {
        say(e.getMessage(), true);
      }
      if (humanTurn() && hotseat() && modal != Modal.MENU) modal = Modal.HANDOVER;
    }
  }

  void back() {
    if (modal == Modal.HANDOVER) return;
    if (modal == Modal.NONE) {
      if (humanTurn() && (from >= 0 || to >= 0) && game.state.phase != OCCUPY) from = to = -1;
      else modal = Modal.SETTINGS;
    } else if (modal != Modal.MENU) closeModal();
    cards.clear();
  }

  void dispose() {
    if (diceSound != null) diceSound.dispose();
    if (winSound != null) winSound.dispose();
  }
}
