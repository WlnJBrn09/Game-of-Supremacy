package com.supremacy.board;

import static com.supremacy.game.GameState.Phase.*;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.math.MathUtils;
import com.supremacy.board.Draw.Style;
import com.supremacy.board.GameHud.Modal;
import com.supremacy.game.*;
import java.util.*;

/** Full-screen dialogs. Every layout is computed from the space available, down to 640×340 units. */
final class Screens {
  private static final String[] CARD_TYPES = {"INFANTRY", "CAVALRY", "ARTILLERY", "WILD"};
  private static final String[] TEXT_SIZE_NAMES = {"SMALL", "NORMAL", "LARGE", "LARGEST"};

  private final GameHud hud;
  private final AtlasApp app;
  private final Draw d;
  int page;

  // Title-screen seat setup: the first `humans` seats are human players.
  int humans = 1, difficulty = 2, armiesOption = 2;
  boolean populationBonus = true, confirmNew;
  final String[] seatName = {"Player 1", "Player 2", "Player 3", "Player 4", "Player 5"};
  final int[] seatColor = {0, 1, 2, 3, 4};
  final boolean[] named = new boolean[5];
  private int editing = -1;
  private String editText = "";
  private boolean editFlow;
  private Modal rulesReturn = Modal.MENU, returnModal = Modal.NONE;

  // Log, diplomacy and offers.
  private List<String> logLines = List.of();
  private int logRecent;
  int recipient = 1;
  private int counterId = -1, pickPage;
  private GameState.Offer draft, viewOffer;
  private boolean armyPage;
  private String pickTitle = "";
  private List<Integer> picks = List.of();
  private java.util.function.IntConsumer pickAction;

  // Frame of the current dialog.
  private float fx, fy, fw, fh;

  Screens(GameHud hud) {
    this.hud = hud;
    this.app = hud.app;
    this.d = hud.d;
  }

  private GameEngine game() {
    return hud.game;
  }

  // ---------------------------------------------------------------- setup persistence

  void loadSetup() {
    try {
      Preferences prefs = GameHud.prefs();
      humans = MathUtils.clamp(prefs.getInteger("humans", 1), 1, 5);
      difficulty = MathUtils.clamp(prefs.getInteger("difficulty", 2), 0, 5);
      armiesOption = MathUtils.clamp(prefs.getInteger("armiesOption", 2), 0, GameState.STARTING_ARMY_OPTIONS.length - 1);
      populationBonus = prefs.getBoolean("populationBonus", true);
      int[] colors = new int[5];
      Set<Integer> used = new HashSet<>();
      for (int i = 0; i < 5; i++) {
        colors[i] = prefs.getInteger("color" + i, i);
        if (colors[i] < 0 || colors[i] >= GameState.COLORS.length || !used.add(colors[i])) return;
      }
      System.arraycopy(colors, 0, seatColor, 0, 5);
      for (int i = 0; i < 5; i++) {
        String saved = prefs.getString("name" + i, "");
        if (saved.isEmpty()) continue;
        seatName[i] = GameEngine.cleanName(saved);
        named[i] = true;
      }
    } catch (RuntimeException e) {
      // Unreadable preferences just mean default names and colours.
    }
  }

  void saveSetup() {
    try {
      Preferences prefs = GameHud.prefs();
      prefs.putInteger("humans", humans).putInteger("difficulty", difficulty);
      prefs.putInteger("armiesOption", armiesOption).putBoolean("populationBonus", populationBonus);
      for (int i = 0; i < 5; i++) {
        prefs.putString("name" + i, named[i] ? seatName[i] : "");
        prefs.putInteger("color" + i, seatColor[i]);
      }
      prefs.flush();
    } catch (RuntimeException e) {
      // Remembering setup is a convenience; never block the game on it.
    }
  }

  private int[] previewColors() {
    return GameEngine.assignColors(humans, seatColor);
  }

  private int nextUnnamed() {
    for (int i = 0; i < humans; i++) if (!named[i]) return i;
    return -1;
  }

  private String validatedName(int seat, String raw) {
    String name = GameEngine.cleanName(raw);
    for (int i = 0; i < humans; i++)
      if (i != seat && named[i] && seatName[i].equalsIgnoreCase(name))
        throw new IllegalStateException("Another player is already called " + name + ".");
    for (int k = 0; k < 5 - humans; k++)
      if (GameState.BOT_NAMES[k].equalsIgnoreCase(name))
        throw new IllegalStateException(name + " is a computer player's name. Pick another.");
    return name;
  }

  private boolean android() {
    return Gdx.app.getType() == Application.ApplicationType.Android;
  }

  private void openEditor(int seat, boolean flow) {
    editing = seat;
    editFlow = flow;
    editText = named[seat] ? seatName[seat] : "";
    hud.modal = Modal.PLAYER;
    if (android() && editText.isEmpty()) askName();
  }

  /** Android: the system keyboard in a native dialog, so typing, autocorrect and paste all work. */
  private void askName() {
    Gdx.input.getTextInput(
        new Input.TextInputListener() {
          @Override
          public void input(String text) {
            editText = text.length() > GameState.MAX_NAME ? text.substring(0, GameState.MAX_NAME) : text;
            Gdx.graphics.requestRendering();
          }

          @Override
          public void canceled() {}
        },
        "Name for player " + (editing + 1),
        editText,
        "Up to " + GameState.MAX_NAME + " characters");
  }

  private void pickColor(int seat, int color) {
    for (int j = 0; j < 5; j++)
      if (j != seat && seatColor[j] == color) {
        seatColor[j] = seatColor[seat]; // taking another seat's colour swaps the two
        break;
      }
    seatColor[seat] = color;
    saveSetup();
  }

  boolean typed(char c) {
    if (hud.modal != Modal.PLAYER) return false;
    if ((Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '\'' || c == '.')
        && editText.length() < GameState.MAX_NAME) {
      editText += c;
      Gdx.graphics.requestRendering();
    }
    return true;
  }

  boolean editKey(int key) {
    if (hud.modal != Modal.PLAYER) return false;
    if (key == Input.Keys.BACKSPACE && !editText.isEmpty()) editText = editText.substring(0, editText.length() - 1);
    else if (key == Input.Keys.ENTER || key == Input.Keys.NUMPAD_ENTER) hud.action(this::finishEdit);
    else if (key == Input.Keys.ESCAPE || key == Input.Keys.BACK) close();
    Gdx.graphics.requestRendering();
    return true;
  }

  private void finishEdit() {
    String name = validatedName(editing, editText);
    seatName[editing] = name;
    named[editing] = true;
    saveSetup();
    if (editFlow) {
      int next = nextUnnamed();
      if (next >= 0) {
        openEditor(next, true);
        return;
      }
      hud.modal = Modal.MENU;
      startPressed();
    } else hud.modal = Modal.MENU;
  }

  private void startPressed() {
    int unnamed = nextUnnamed();
    if (unnamed >= 0) {
      openEditor(unnamed, true);
      hud.say("Enter a name for " + seatName[unnamed] + " to begin.", false);
      return;
    }
    for (int i = 0; i < humans; i++) seatName[i] = validatedName(i, seatName[i]);
    boolean over = game() != null && (game().state.phase == FINISHED || !hud.anyHumanAlive());
    if (!over && (game() != null || hud.saves.exists()) && !confirmNew) {
      confirmNew = true;
      hud.say("Starting a new game replaces your saved game. Tap again to confirm.", false);
      return;
    }
    confirmNew = false;
    newMatch();
  }

  private void newMatch() {
    saveSetup();
    hud.waitForSaves();
    hud.attach(
        new GameEngine(
            app.board,
            humans,
            GameState.Difficulty.values()[difficulty],
            Long.getLong("supremacy.seed", System.nanoTime()),
            List.of(seatName).subList(0, humans),
            Arrays.copyOf(seatColor, humans),
            new GameEngine.Options(GameState.STARTING_ARMY_OPTIONS[armiesOption], populationBonus)));
  }

  /**
   * Smoke-test mode for devices and CI: a seeded match starts immediately. With "autoplay" every
   * seat is a computer player, so a whole match plays out unattended.
   */
  void startDemo() {
    boolean autoplay = "autoplay".equals(System.getProperty("supremacy.demo"));
    GameEngine engine =
        new GameEngine(app.board, 1, GameState.Difficulty.Medium, Long.getLong("supremacy.seed", 42L), List.of("Player 1"), new int[] {0});
    if (autoplay) for (GameState.Player p : engine.state.players) p.human = false;
    hud.tutorialSeen = true;
    hud.attach(engine);
    if (autoplay) hud.fastBots = true;
  }

  private void resume() {
    GameEngine restored;
    hud.waitForSaves();
    try {
      restored = hud.saves.load(app.board);
    } catch (IllegalStateException e) {
      if (String.valueOf(e.getMessage()).contains("different board"))
        GameHud.refuse("This saved game uses an older version of the map. Start a new game.");
      throw e;
    }
    String warning = hud.saves.warning();
    hud.attach(restored);
    if (warning != null && !warning.isEmpty()) hud.say(warning, true);
  }

  // ---------------------------------------------------------------- frame

  void draw() {
    switch (hud.modal) {
      case MENU -> menu();
      case PLAYER -> editor();
      case HANDOVER -> handover();
      case LOG -> log();
      case CARDS -> cards();
      case DIPLOMACY -> diplomacy();
      case OFFER -> offer();
      case COMPOSE -> compose();
      case PICK -> picker();
      case SETTINGS -> settings();
      case RULES -> rules();
      case ROSTER -> roster();
      case RESULT -> result();
      case TUTORIAL -> tutorial();
      default -> {}
    }
  }

  void close() {
    switch (hud.modal) {
      case PICK -> hud.modal = returnModal;
      case PLAYER -> hud.modal = Modal.MENU;
      case OFFER, COMPOSE -> hud.modal = Modal.DIPLOMACY;
      case RULES -> hud.modal = rulesReturn;
      case MENU -> {
        if (game() != null && game().state.phase != FINISHED) returnToGame();
      }
      case TUTORIAL -> finishTutorial();
      default -> hud.modal = game() == null ? Modal.MENU : Modal.NONE;
    }
    if (hud.modal == Modal.SETTINGS && game() == null) hud.modal = Modal.MENU;
  }

  private void returnToGame() {
    hud.modal = Modal.NONE;
    hud.lastActive = -1;
    hud.checkTurn();
  }

  private boolean compact() {
    return app.H < 560 || app.W > app.H * 1.95f;
  }

  /** Dims the game and draws a dialog card; returns the content top. */
  private float panel(String title, boolean closable) {
    float W = app.W, H = app.H, m = compact() ? 8 : 24;
    fw = Math.min(W - 2 * m, 1120);
    fh = Math.min(H - 2 * m, 760);
    fx = (W - fw) / 2;
    fy = (H - fh) / 2;
    d.box(0, 0, W, H, new Color(.02f, .045f, .07f, .9f));
    d.round(fx, fy, fw, fh, 14, AtlasApp.PANEL);
    d.round(fx, fy + fh - 6, fw, 6, 3, AtlasApp.GOLD);
    float top = fy + fh - 10;
    float th = compact() ? 40 : 52;
    if (!title.isEmpty()) d.fit(compact() ? app.heading : app.title, title, fx + 18, GameHud.mid(compact() ? app.heading : app.title, top - th, th), fw - 160, AtlasApp.GOLD);
    if (closable) d.button("CLOSE", fx + fw - 112, top - th + 2, 100, th - 6, Style.NORMAL, this::close);
    return top - th - 6;
  }

  private float row() {
    return compact() ? 40 : 48;
  }

  private void paging(int count, int perPage, float x, float y) {
    int pages = Math.max(1, (count + perPage - 1) / perPage);
    page = Math.min(page, pages - 1);
    if (pages <= 1) return;
    float h = row();
    d.button("‹ PREV", x, y, 110, h, Style.NORMAL, () -> page = Math.max(0, page - 1));
    d.button("NEXT ›", x + 118, y, 110, h, Style.NORMAL, () -> page = Math.min(pages - 1, page + 1));
    d.text(app.small, (page + 1) + " / " + pages, x + 238, GameHud.mid(app.small, y, h), AtlasApp.MUTED);
  }

  // ---------------------------------------------------------------- title screen

  private void menu() {
    float W = app.W, H = app.H, m = compact() ? 10 : 28;
    d.box(0, 0, W, H, new Color(.02f, .045f, .07f, .82f));
    fx = m;
    fy = m;
    fw = W - 2 * m;
    fh = H - 2 * m;
    if (!compact()) {
      fw = Math.min(fw, 1180);
      fh = Math.min(fh, 720);
      fx = (W - fw) / 2;
      fy = (H - fh) / 2;
    }
    d.round(fx, fy, fw, fh, 16, AtlasApp.alpha(AtlasApp.PANEL, .97f));
    d.round(fx, fy + fh - 6, fw, 6, 3, AtlasApp.GOLD);
    float pad = compact() ? 12 : 26, top = fy + fh - 10;
    float titleH = compact() ? 38 : 58;
    float tw = d.fit(app.title, "GAME OF SUPREMACY", fx + pad, GameHud.mid(app.title, top - titleH, titleH), fw * .6f, AtlasApp.WHITE);
    d.fit(app.body, "Five commanders. Seven continents. One world.", fx + pad + tw + 16, GameHud.mid(app.body, top - titleH, titleH), fw - tw - 2 * pad - 16, AtlasApp.MUTED);
    top -= titleH + (compact() ? 2 : 10);
    float bottomH = compact() ? 44 : 56, bottom = fy + pad * .7f;
    float colGap = compact() ? 14 : 30, leftW = (fw - 2 * pad - colGap) * .48f, rightX = fx + pad + leftW + colGap, rightW = fw - 2 * pad - leftW - colGap;
    float avail = top - (bottom + bottomH + 10) - 18;
    float seatH = Math.min(compact() ? 42 : 54, avail / 5 - 4);
    // Seats.
    d.text(app.small, "COMMANDERS · TAP A NAME TO EDIT", fx + pad, top - 2, AtlasApp.GOLD);
    int[] preview = previewColors();
    float sy = top - 18;
    for (int i = 0; i < 5; i++) {
      int seat = i;
      float y = sy - (i + 1) * (seatH + 4);
      boolean human = i < humans;
      d.round(fx + pad, y, leftW, seatH, 8, Draw.RAISED);
      d.token(fx + pad + 18, y + seatH / 2, 10, Color.valueOf(GameState.COLORS[preview[i]]));
      float bw = Math.min(110, leftW * .34f);
      if (human) {
        d.fit(app.gameFont, seatName[i], fx + pad + 36, y + seatH / 2 + (seatH > 46 ? 14 : 6), leftW - bw - 52, named[i] ? AtlasApp.WHITE : AtlasApp.MUTED);
        if (seatH > 46)
          d.text(app.small, named[i] ? "Tap to edit name and colour" : "Tap to enter your name", fx + pad + 36, y + 16, named[i] ? AtlasApp.MUTED : AtlasApp.GOLD);
        d.hit(fx + pad, y, leftW - bw - 12, seatH, "seat " + (i + 1), () -> openEditor(seat, false));
      } else d.fit(app.gameFont, GameState.BOT_NAMES[i - humans], fx + pad + 36, GameHud.mid(app.gameFont, y, seatH), leftW - bw - 52, AtlasApp.WHITE);
      d.button(
          human ? "HUMAN" : "COMPUTER",
          fx + pad + leftW - bw - 3,
          y + 3,
          bw,
          seatH - 6,
          human ? Style.SELECTED : Style.NORMAL,
          () -> {
            confirmNew = false;
            if (seat == 0) GameHud.refuse("Seat 1 is always a human player.");
            humans = seat < humans ? seat : seat + 1;
            saveSetup();
          });
    }
    // Computer skill and match options.
    d.text(app.small, "COMPUTER SKILL", rightX, top - 2, AtlasApp.GOLD);
    GameState.Difficulty[] levels = GameState.Difficulty.values();
    float optH = compact() ? 36 : 44;
    // Options share one row, so on a phone the skill buttons keep a comfortable height.
    float skillH = Math.min(compact() ? 40 : 58, (avail - (optH + 26)) / 3 - 6);
    float sw = (rightW - 8) / 2;
    for (int i = 0; i < levels.length; i++) {
      int level = i;
      float bx = rightX + (i % 2) * (sw + 8), by = sy - (i / 2 + 1) * (skillH + 6);
      boolean chosen = i == difficulty;
      if (chosen) d.round(bx - 2, by - 2, sw + 4, skillH + 4, 9, AtlasApp.GOLD);
      d.round(bx, by, sw, skillH, 8, chosen ? Draw.RAISED_HI : Draw.RAISED);
      if (skillH >= 50) {
        d.text(app.gameFont, levels[i].name(), bx + 14, by + skillH - 10, chosen ? AtlasApp.GOLD : AtlasApp.WHITE);
        d.fit(app.small, GameHud.SKILL_NOTES[i], bx + 14, by + 20, sw - 20, AtlasApp.MUTED);
      } else {
        float nw = d.fit(app.body, levels[i].name(), bx + 10, GameHud.mid(app.body, by, skillH), sw * .55f, chosen ? AtlasApp.GOLD : AtlasApp.WHITE);
        d.fit(app.small, GameHud.SKILL_NOTES[i], bx + 18 + nw, GameHud.mid(app.small, by, skillH), sw - nw - 26, AtlasApp.MUTED);
      }
      d.hit(bx, by, sw, skillH, "skill " + levels[i].name(), () -> {
        difficulty = level;
        confirmNew = false;
        saveSetup();
      });
    }
    float oy = sy - 3 * (skillH + 6) - 8;
    d.text(app.small, "STARTING ARMIES", rightX, oy - 2, AtlasApp.GOLD);
    oy -= 16 + optH;
    int[] options = GameState.STARTING_ARMY_OPTIONS;
    float ow = Math.min(compact() ? 56 : 84, (rightW - 3 * 6) / 4);
    for (int i = 0; i < options.length; i++) {
      int k = i;
      d.button(Integer.toString(options[i]), rightX + i * (ow + 6), oy, ow, optH, armiesOption == i ? Style.SELECTED : Style.NORMAL, () -> {
        armiesOption = k;
        confirmNew = false;
        saveSetup();
      });
    }
    float px = rightX + 4 * (ow + 6) + 6, pw = rightX + rightW - px;
    if (pw >= 110)
      d.button(populationBonus ? "POP. BONUS: ON" : "POP. BONUS: OFF", px, oy, pw, optH, populationBonus ? Style.SELECTED : Style.NORMAL, this::togglePopulation);
    else {
      oy -= optH + 6;
      d.button(populationBonus ? "POPULATION BONUS: ON" : "POPULATION BONUS: OFF", rightX, oy, rightW, optH, populationBonus ? Style.SELECTED : Style.NORMAL, this::togglePopulation);
    }
    if (oy - 10 - (bottom + bottomH) > 40)
      d.wrap(
          app.small,
          (5 - humans == 0 ? "All five seats are human." : (5 - humans) + " computer opponents share this skill.")
              + " Territories are dealt at random."
              + (populationBonus ? " Population bonus: +1 army per 5 you have on the board." : "")
              + (humans > 1 ? " Pass the device when prompted; cards stay private." : ""),
          rightX,
          oy - 8,
          rightW,
          AtlasApp.MUTED);
    // Actions.
    float bx = fx + pad, bw = fw - 2 * pad;
    float third = (bw - 16) / 3;
    d.button("HOW TO PLAY", bx, bottom, third * .8f, bottomH, Style.NORMAL, () -> rules(Modal.MENU));
    float mx = bx + third * .8f + 8, mw = third * 1.1f;
    if (game() != null && game().state.phase != FINISHED) d.button("RETURN TO GAME", mx, bottom, mw, bottomH, Style.NORMAL, this::returnToGame);
    else if (hud.saves.exists()) d.button("RESUME SAVED GAME", mx, bottom, mw, bottomH, Style.NORMAL, this::resume);
    float sx = mx + mw + 8;
    d.button(confirmNew ? "TAP AGAIN TO REPLACE SAVE" : "START NEW GAME", sx, bottom, bx + bw - sx, bottomH, confirmNew ? Style.DANGER : Style.PRIMARY, this::startPressed);
  }

  private void togglePopulation() {
    populationBonus = !populationBonus;
    confirmNew = false;
    saveSetup();
  }

  // ---------------------------------------------------------------- player editor

  private void editor() {
    float top = panel("PLAYER " + (editing + 1), true);
    float x = fx + 18, w = fw - 36, fieldH = compact() ? 48 : 60;
    d.text(app.small, android() ? "NAME · TAP TO TYPE" : "NAME · TYPE ON YOUR KEYBOARD", x, top - 2, AtlasApp.GOLD);
    top -= 18;
    float fieldW = Math.min(w, 520);
    d.round(x, top - fieldH, fieldW, fieldH, 10, AtlasApp.GOLD);
    d.round(x + 2.5f, top - fieldH + 2.5f, fieldW - 5, fieldH - 5, 8, AtlasApp.INK);
    BitmapFont f = compact() ? app.gameFont : app.heading;
    if (editText.isEmpty()) d.text(f, android() ? "Tap here to enter a name" : "Type your name", x + 16, GameHud.mid(f, top - fieldH, fieldH), AtlasApp.MUTED);
    else d.text(f, editText, x + 16, GameHud.mid(f, top - fieldH, fieldH), AtlasApp.WHITE);
    if (!android() && ((int) (hud.clock * 2)) % 2 == 0) {
      float cx = x + 16 + (editText.isEmpty() ? 0 : app.width(f, editText)) + 2;
      d.box(cx, top - fieldH + 10, 2.5f, fieldH - 20, AtlasApp.GOLD);
    }
    d.right(app.small, editText.length() + " / " + GameState.MAX_NAME, x + fieldW - 12, top - fieldH + 16, AtlasApp.MUTED);
    if (android()) d.hit(x, top - fieldH, fieldW, fieldH, "name field", this::askName);
    if (!editText.isEmpty() && fieldW + 110 < w)
      d.button("CLEAR", x + fieldW + 10, top - fieldH, 100, fieldH, Style.NORMAL, () -> editText = "");
    top -= fieldH + 14;
    d.text(app.small, "COLOUR · A NUMBER MARKS ANOTHER HUMAN'S COLOUR; PICKING IT SWAPS THEM", x, top - 2, AtlasApp.GOLD);
    top -= 18;
    float sw = Math.min(compact() ? 46 : 60, (w - 7 * 8) / 8);
    for (int k = 0; k < GameState.COLORS.length; k++) {
      int color = k;
      float cx = x + k * (sw + 8) + sw / 2, cy = top - sw / 2;
      boolean chosen = seatColor[editing] == k;
      int holder = -1;
      for (int j = 0; j < humans; j++) if (j != editing && seatColor[j] == k) holder = j;
      if (chosen) d.token(cx, cy, sw / 2, AtlasApp.GOLD);
      d.token(cx, cy, sw / 2 - 5, Color.valueOf(GameState.COLORS[k]));
      if (holder >= 0) d.center(app.body, Integer.toString(holder + 1), cx, cy + 6, AtlasApp.INK);
      d.hit(cx - sw / 2, cy - sw / 2, sw, sw, "colour " + GameState.COLOR_NAMES[k], () -> pickColor(editing, color));
    }
    float lx = x + 8 * (sw + 8) + 6;
    if (lx + 90 < x + w) d.text(app.gameFont, GameState.COLOR_NAMES[seatColor[editing]], lx, top - sw / 2 + 8, Color.valueOf(GameState.COLORS[seatColor[editing]]));
    String next = "DONE";
    if (editFlow) {
      boolean more = false;
      for (int i = editing + 1; i < humans; i++) if (!named[i]) more = true;
      next = more ? "NEXT PLAYER" : "START GAME";
    }
    float bh = row();
    d.button(next, fx + fw - 18 - Math.min(300, fw * .4f), fy + 14, Math.min(300, fw * .4f), bh, Style.PRIMARY, this::finishEdit);
  }

  // ---------------------------------------------------------------- handover

  private void handover() {
    GameState s = game().state;
    String name = s.players.get(s.active).name;
    Color c = hud.playerColor(s.active);
    float W = app.W, H = app.H;
    d.box(0, 0, W, H, new Color(.02f, .045f, .07f, .97f));
    float w = Math.min(W - 24, 720), h = Math.min(H - 24, 440), x = (W - w) / 2, y = (H - h) / 2;
    d.round(x, y, w, h, 18, AtlasApp.PANEL);
    d.round(x, y + h - 14, w, 14, 7, c);
    float top = y + h - 30;
    d.token(W / 2, top - 26, 24, c);
    top -= 64;
    d.fitCenter(app.title, name.toUpperCase(Locale.ROOT), W / 2, top, w - 40, AtlasApp.WHITE);
    top -= 46;
    d.center(app.gameFont, s.phase == SETUP ? "Place your starting armies." : "Turn " + s.turn + " is ready.", W / 2, top, AtlasApp.GOLD);
    top -= 32;
    d.fitCenter(app.body, "Pass the device to " + name + ". Everyone else, look away: cards stay private.", W / 2, top, w - 40, AtlasApp.MUTED);
    float bh = compact() ? 52 : 64;
    d.button("I'M " + name.toUpperCase(Locale.ROOT) + " · START", x + 40, y + 20, w - 80, bh, Style.PRIMARY, hud::startHumanTurn);
  }

  // ---------------------------------------------------------------- log

  List<String> reportLines() {
    GameState s = game().state;
    String self = s.players.get(s.active).name + " ";
    List<String> lines = new ArrayList<>();
    for (String l : game().report(s.active))
      if (!l.contains("; losses") && !l.startsWith(self) && !l.startsWith("Random territories")) lines.add(l);
    return lines;
  }

  void openLog() {
    GameState s = game().state;
    List<String> lines = new ArrayList<>();
    boolean human = hud.humanTurn();
    List<String> recent = human ? reportLines() : List.of();
    for (String l : recent) if (l.startsWith("Offer #")) lines.add(l);
    for (int i = s.history.size() - 1; i >= 0 && lines.size() < 200; i--) lines.add(s.history.get(i).message);
    logLines = lines;
    logRecent = recent.size();
    page = 0;
    hud.modal = Modal.LOG;
  }

  private void log() {
    float top = panel("BATTLE LOG · NEWEST FIRST", true);
    GameState s = game().state;
    String me = hud.humanTurn() ? s.players.get(s.active).name : "";
    float x = fx + 18, w = fw - 36, lineH = compact() ? 26 : 30, bottom = fy + 14 + row() + 8;
    if (logRecent > 0) {
      d.fit(app.small, logRecent + " " + (logRecent == 1 ? "entry" : "entries") + " since your last turn are marked in gold.", x, top - 2, w, AtlasApp.GOLD);
      top -= 20;
    }
    int per = Math.max(1, (int) ((top - bottom) / lineH));
    page = Math.min(page, Math.max(0, (logLines.size() - 1) / per));
    for (int i = page * per; i < Math.min(logLines.size(), page * per + per); i++) {
      float y = top - (i - page * per) * lineH;
      String line = logLines.get(i);
      boolean hurt = !me.isEmpty() && (line.contains("attacked " + me) || line.contains(me + " was eliminated"));
      Color dot = hurt ? GameHud.RED : i < logRecent ? AtlasApp.GOLD : AtlasApp.alpha(AtlasApp.MUTED, .6f);
      d.token(x + 6, y - lineH / 2, 4, dot);
      d.fit(app.body, line, x + 20, GameHud.mid(app.body, y - lineH, lineH), w - 20, hurt ? GameHud.ERROR_TEXT : AtlasApp.WHITE);
    }
    if (logLines.isEmpty()) d.text(app.gameFont, "Nothing has happened yet.", x, top - 10, AtlasApp.MUTED);
    paging(logLines.size(), per, x, fy + 14);
  }

  // ---------------------------------------------------------------- cards

  private void cards() {
    GameState s = game().state;
    float top = panel("YOUR CARDS", true);
    List<Integer> hand = s.players.get(s.active).cards;
    float x = fx + 18, w = fw - 36;
    d.fit(app.body, "Trade three of a kind or one of each; wilds match anything. Next set: +" + game().nextSetValue() + " armies.", x, top - 2, w, AtlasApp.MUTED);
    top -= 26;
    if (hand.isEmpty()) d.wrap(app.gameFont, "No cards yet. Capture at least one territory during your turn to draw a card.", x, top - 10, w, AtlasApp.MUTED);
    float bottom = fy + 14 + row() + 10, availH = top - bottom - 16;
    float ch = Math.min(220, availH), cw = ch * .72f;
    int rows = availH >= 2 * 160 + 12 ? 2 : 1;
    if (rows == 2) {
      ch = Math.min(220, (availH - 12) / 2);
      cw = ch * .72f;
    }
    int cols = Math.max(1, (int) ((w + 12) / (cw + 12)));
    int per = cols * rows;
    page = Math.min(page, Math.max(0, (hand.size() - 1) / per));
    for (int i = page * per; i < Math.min(hand.size(), page * per + per); i++) {
      int card = hand.get(i), k = i - page * per;
      boolean chosen = hud.cards.contains(card);
      float cx = x + (k % cols) * (cw + 12), cy = top - 6 - (k / cols + 1) * (ch + 12) + 12 + (chosen ? 10 : 0);
      drawCard(card, cx, cy, cw, ch, chosen);
      d.hit(cx, cy, cw, ch, "card " + i, () -> {
        if (!hud.cards.remove(Integer.valueOf(card)) && hud.cards.size() < 3) hud.cards.add(card);
      });
    }
    boolean valid = hud.cards.size() == 3 && game().validSet(hud.cards);
    boolean phaseOk = s.phase == REINFORCE;
    float bh = row(), by = fy + 14;
    if (hand.size() > per) paging(hand.size(), per, x, by);
    float bw = Math.min(260, w * .3f);
    d.button("PICK A SET", x + w - 2 * bw - 10, by, bw, bh, Style.NORMAL, () -> {
      hud.cards.clear();
      hud.cards.addAll(game().findSet(s.active));
      if (hud.cards.isEmpty()) GameHud.refuse("No matching set in your hand yet.");
    });
    d.button("TRADE IN +" + game().nextSetValue(), x + w - bw, by, bw, bh, valid && phaseOk ? Style.PRIMARY : Style.DISABLED, () -> {
      if (!phaseOk) GameHud.refuse("Cards can be traded during the reinforce step of your turn.");
      if (!valid) GameHud.refuse("Select three cards: three of a kind or one of each.");
      hud.trade();
      hud.modal = Modal.NONE;
    });
  }

  private void drawCard(int card, float x, float y, float w, float h, boolean selected) {
    int symbol = game().symbol(card);
    boolean owned = card < game().state.owners.length && game().state.owners[card] == game().state.active;
    float k = h / 220;
    app.begin();
    if (selected) app.round(x - 4, y - 4, w + 8, h + 8, 12, AtlasApp.GOLD);
    app.round(x + 2, y - 4, w, h, 10, AtlasApp.alpha(Color.BLACK, .45f));
    app.round(x, y, w, h, 10, Draw.PARCHMENT);
    app.round(x + 7 * k, y + 7 * k, w - 14 * k, h - 14 * k, 6, Color.valueOf("E3D7BA"));
    if (symbol < 3) app.rect(x + 7 * k, y + h - 48 * k, w - 14 * k, 6 * k, Color.valueOf(app.board.regions.get(card).continent.color()));
    float cx = x + w / 2, cy = y + h * .47f;
    Color ink = Color.valueOf("3A3324");
    switch (symbol) {
      case 0 -> infantry(cx, cy, k, ink);
      case 1 -> cavalry(cx, cy, k, ink);
      case 2 -> artillery(cx, cy, k, ink);
      default -> star(cx, cy + 6 * k, 34 * k, Color.valueOf("9C7A2E"));
    }
    app.shapes.end();
    String name = symbol == 3 ? "WILD CARD" : game().name(card);
    d.fitCenter(app.small, name, x + w / 2, y + h - 16 * k, w - 16, ink);
    d.fitCenter(app.small, CARD_TYPES[symbol], x + w / 2, y + 30 * k + 8, w - 16, Color.valueOf("6E6247"));
    if (owned) {
      d.round(x + w - 48, y + 10, 40, 20, 10, AtlasApp.GOLD);
      d.center(app.small, "+2", x + w - 28, y + 25, AtlasApp.INK);
    }
  }

  private void infantry(float x, float y, float k, Color c) {
    app.shapes.setColor(c);
    app.shapes.circle(x, y + 30 * k, 11 * k, 20);
    app.shapes.triangle(x - 20 * k, y - 22 * k, x + 20 * k, y - 22 * k, x, y + 22 * k);
    app.shapes.rect(x - 24 * k, y - 32 * k, 48 * k, 10 * k);
  }

  private void cavalry(float x, float y, float k, Color c) {
    for (int i = 0; i < 18; i++) {
      float a = MathUtils.degreesToRadians * (-50 + i * 280f / 18), b = MathUtils.degreesToRadians * (-50 + (i + 1) * 280f / 18);
      app.line(x + MathUtils.cos(a) * 26 * k, y + MathUtils.sin(a) * 26 * k, x + MathUtils.cos(b) * 26 * k, y + MathUtils.sin(b) * 26 * k, 11 * k, c);
    }
    app.shapes.setColor(Color.valueOf("E3D7BA"));
    for (int i = 0; i < 6; i++) {
      float a = MathUtils.degreesToRadians * (-20 + i * 44);
      app.shapes.circle(x + MathUtils.cos(a) * 26 * k, y + MathUtils.sin(a) * 26 * k, 2.2f * k, 8);
    }
  }

  private void artillery(float x, float y, float k, Color c) {
    app.line(x - 14 * k, y - 8 * k, x + 30 * k, y + 22 * k, 13 * k, c);
    app.line(x - 14 * k, y - 8 * k, x - 34 * k, y - 26 * k, 5 * k, c);
    app.ring(x - 8 * k, y - 16 * k, 14 * k, 4 * k, c);
    app.shapes.setColor(c);
    app.shapes.circle(x - 8 * k, y - 16 * k, 4 * k, 10);
    app.shapes.rect(x - 40 * k, y - 34 * k, 70 * k, 4 * k);
  }

  private void star(float x, float y, float r, Color c) {
    app.shapes.setColor(c);
    for (int q = 0; q < 5; q++) {
      float a = MathUtils.PI / 2 + q * MathUtils.PI2 / 5;
      float l = a - MathUtils.PI / 5, rr = a + MathUtils.PI / 5, in = r * .42f;
      float ox = x + MathUtils.cos(a) * r, oy = y + MathUtils.sin(a) * r;
      app.shapes.triangle(x, y, x + MathUtils.cos(l) * in, y + MathUtils.sin(l) * in, ox, oy);
      app.shapes.triangle(x, y, ox, oy, x + MathUtils.cos(rr) * in, y + MathUtils.sin(rr) * in);
    }
  }

  // ---------------------------------------------------------------- roster (phones)

  private void roster() {
    float top = panel("COMMANDERS", true);
    GameState s = game().state;
    float x = fx + 18, w = fw - 36, h = Math.min(52, (top - fy - 30) / 5.6f);
    float cL = x + w - 230, cA = x + w - 150, cC = x + w - 80;
    d.right(app.small, "LANDS", cL, top - 2, AtlasApp.MUTED);
    d.right(app.small, "ARMIES", cA, top - 2, AtlasApp.MUTED);
    d.right(app.small, "CARDS", cC, top - 2, AtlasApp.MUTED);
    top -= 18;
    for (int p = 0; p < 5; p++) {
      int player = p;
      float y = top - (p + 1) * (h + 4);
      boolean alive = game().alive(p);
      d.round(x, y, w, h, 8, p == s.active ? Draw.RAISED_HI : Draw.RAISED);
      d.token(x + 18, y + h / 2, 9, alive ? hud.playerColor(p) : AtlasApp.alpha(AtlasApp.MUTED, .5f));
      GameState.Player pl = s.players.get(p);
      float nw = d.fit(app.gameFont, pl.name, x + 36, GameHud.mid(app.gameFont, y, h), cL - x - 140, alive ? AtlasApp.WHITE : AtlasApp.MUTED);
      d.text(app.small, pl.human ? "HUMAN" : "CPU", x + 44 + nw, GameHud.mid(app.small, y, h), AtlasApp.MUTED);
      if (alive) {
        d.right(app.gameFont, Integer.toString(game().count(p)), cL, GameHud.mid(app.gameFont, y, h), AtlasApp.WHITE);
        d.right(app.gameFont, Long.toString(game().total(p)), cA, GameHud.mid(app.gameFont, y, h), AtlasApp.WHITE);
        d.right(app.gameFont, Integer.toString(pl.cards.size()), cC, GameHud.mid(app.gameFont, y, h), AtlasApp.WHITE);
      } else d.right(app.body, "ELIMINATED", cC, GameHud.mid(app.body, y, h), AtlasApp.MUTED);
      if (alive && p != s.active && hud.humanTurn())
        d.button("DEAL", x + w - 64, y + 5, 58, h - 10, Style.NORMAL, () -> openDiplomacyWith(player));
    }
  }

  void openDiplomacyWith(int player) {
    if (!hud.interactive() || player == game().state.active || !game().alive(player)) return;
    recipient = player;
    hud.open(Modal.DIPLOMACY);
  }

  // ---------------------------------------------------------------- diplomacy

  private void diplomacy() {
    float top = panel("DIPLOMACY", true);
    GameState s = game().state;
    float x = fx + 18, w = fw - 36, rh = row();
    d.fit(app.body, "Offers arrive on the recipient's turn; replies arrive on your next turn.", x, top - 2, w, AtlasApp.MUTED);
    top -= 26;
    List<GameState.Offer> inbox = game().inbox();
    d.text(app.small, "INCOMING OFFERS", x, top - 2, AtlasApp.GOLD);
    top -= 18;
    float bottomBlock = fy + 14 + 2 * (rh + 8) + 22;
    int per = Math.max(1, (int) ((top - bottomBlock) / (rh + 6)));
    for (int i = page * per; i < Math.min(inbox.size(), page * per + per); i++) {
      GameState.Offer o = inbox.get(i);
      d.button("#" + o.id + " FROM " + s.players.get(o.from).name.toUpperCase(Locale.ROOT) + " · REVIEW", x, top - (i - page * per + 1) * (rh + 6), w, rh, Style.NORMAL, () -> {
        viewOffer = o;
        page = 0;
        hud.modal = Modal.OFFER;
      });
    }
    if (inbox.isEmpty()) d.text(app.gameFont, "No pending offers.", x, top - 8, AtlasApp.MUTED);
    if (recipient == s.active || !game().alive(recipient)) cycleRecipient();
    float by = fy + 14 + rh + 8;
    d.text(app.small, "NEW OFFER", x, by + rh + 18, AtlasApp.GOLD);
    float half = (w - 10) / 2;
    d.button("TO: " + s.players.get(recipient).name + " (change)", x, by, half, rh, Style.NORMAL, this::cycleRecipient);
    d.token(x + 14, by + rh / 2, 6, hud.playerColor(recipient));
    d.button("COMPOSE OFFER", x + half + 10, by, half, rh, Style.PRIMARY, () -> {
      draft = new GameState.Offer();
      draft.from = s.active;
      draft.to = recipient;
      counterId = -1;
      armyPage = false;
      page = 0;
      hud.modal = Modal.COMPOSE;
    });
    boolean borders = s.borders[s.active][recipient];
    String other = s.players.get(recipient).name.toUpperCase(Locale.ROOT);
    d.button(borders ? "REVOKE OPEN BORDERS WITH " + other : "NO OPEN BORDERS WITH " + other, x, fy + 14, w, rh, borders ? Style.DANGER : Style.DISABLED, () -> {
      if (borders) {
        game().revokeBorders(recipient);
        hud.say("Open borders revoked.", false);
      } else GameHud.refuse("Open borders are agreed through an accepted offer.");
    });
  }

  private void cycleRecipient() {
    do recipient = (recipient + 1) % 5;
    while (recipient == game().state.active || !game().alive(recipient));
  }

  private String gift(long n, int source, int target) {
    return n == 0 ? "" : " " + n + " armies from " + game().name(source) + " to " + game().name(target);
  }

  private void offer() {
    GameState s = game().state;
    float top = panel("OFFER #" + viewOffer.id + " FROM " + s.players.get(viewOffer.from).name.toUpperCase(Locale.ROOT), true);
    List<String> lines = new ArrayList<>();
    for (int r : viewOffer.giveTerritories) lines.add("You receive territory: " + game().name(r));
    for (int r : viewOffer.wantTerritories) lines.add("You give territory: " + game().name(r));
    if (viewOffer.giveArmies > 0) lines.add("You receive" + gift(viewOffer.giveArmies, viewOffer.giveSource, viewOffer.giveTarget));
    if (viewOffer.wantArmies > 0) lines.add("You send" + gift(viewOffer.wantArmies, viewOffer.wantSource, viewOffer.wantTarget));
    lines.add("Mutual open borders: " + (viewOffer.openBorders ? "included" : "not included"));
    lines.add("Garrisons return automatically. Terms are rechecked when accepted.");
    float x = fx + 18, w = fw - 36, lh = compact() ? 26 : 32, rh = row();
    int per = Math.max(1, (int) ((top - fy - 14 - 2 * rh - 16) / lh));
    for (int i = page * per; i < Math.min(lines.size(), page * per + per); i++)
      d.fit(app.gameFont, lines.get(i), x, GameHud.mid(app.gameFont, top - (i - page * per + 1) * lh, lh), w, i == lines.size() - 1 ? AtlasApp.MUTED : AtlasApp.WHITE);
    if (lines.size() > per) paging(lines.size(), per, x, fy + 14 + rh + 8);
    float third = (w - 20) / 3;
    d.button("ACCEPT", x, fy + 14, third, rh, Style.PRIMARY, () -> {
      boolean accepted = game().answer(viewOffer.id, true);
      hud.say(accepted ? "Exchange completed." : "Offer expired because its terms changed.", !accepted);
      hud.modal = Modal.DIPLOMACY;
    });
    d.button("REJECT", x + third + 10, fy + 14, third, rh, Style.DANGER, () -> {
      game().answer(viewOffer.id, false);
      hud.modal = Modal.DIPLOMACY;
    });
    d.button("COUNTER", x + 2 * (third + 10), fy + 14, third, rh, Style.NORMAL, () -> {
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
      hud.modal = Modal.COMPOSE;
    });
  }

  private void compose() {
    float top = panel("OFFER TO " + game().state.players.get(draft.to).name.toUpperCase(Locale.ROOT), true);
    float x = fx + 18, w = fw - 36, rh = row(), half = (w - 10) / 2;
    d.button("TERRITORIES", x, top - rh, half, rh, armyPage ? Style.NORMAL : Style.SELECTED, () -> {
      armyPage = false;
      page = 0;
    });
    d.button("ARMIES", x + half + 10, top - rh, half, rh, armyPage ? Style.SELECTED : Style.NORMAL, () -> {
      armyPage = true;
      page = 0;
    });
    top -= rh + 10;
    float bottom = fy + 14 + rh + 10;
    if (!armyPage) {
      List<Integer> own = owned(draft.from), theirs = owned(draft.to);
      d.text(app.small, "YOU GIVE", x, top - 2, AtlasApp.GOLD);
      d.text(app.small, "YOU REQUEST", x + half + 10, top - 2, AtlasApp.GOLD);
      top -= 18;
      float bh = compact() ? 36 : 44;
      int per = Math.max(1, (int) ((top - bottom - rh - 8) / (bh + 6)));
      int count = Math.max(own.size(), theirs.size());
      page = Math.min(page, Math.max(0, (count - 1) / per));
      for (int i = page * per; i < Math.min(count, page * per + per); i++) {
        float y = top - (i - page * per + 1) * (bh + 6);
        if (i < own.size()) {
          int r = own.get(i);
          d.button(game().name(r) + " (" + game().state.armies[r] + ")", x, y, half, bh, draft.giveTerritories.contains(r) ? Style.SELECTED : Style.NORMAL, () -> toggle(draft.giveTerritories, r));
        }
        if (i < theirs.size()) {
          int r = theirs.get(i);
          d.button(game().name(r) + " (" + game().state.armies[r] + ")", x + half + 10, y, half, bh, draft.wantTerritories.contains(r) ? Style.SELECTED : Style.NORMAL, () -> toggle(draft.wantTerritories, r));
        }
      }
      paging(count, per, x, bottom);
    } else {
      giftEditor(true, x, top, half);
      giftEditor(false, x + half + 10, top, half);
    }
    float bw = Math.min(260, half);
    d.button(draft.openBorders ? "OPEN BORDERS: YES" : "OPEN BORDERS: NO", x, fy + 14, bw, rh, draft.openBorders ? Style.SELECTED : Style.NORMAL, () -> draft.openBorders = !draft.openBorders);
    d.button("SEND OFFER", x + w - bw, fy + 14, bw, rh, Style.PRIMARY, () -> {
      if (counterId < 0) game().sendOffer(draft);
      else game().counter(counterId, draft);
      hud.modal = Modal.DIPLOMACY;
      page = 0;
      hud.say("Offer sent. The reply arrives on your next turn.", false);
    });
  }

  private void toggle(List<Integer> values, int r) {
    if (!values.remove(Integer.valueOf(r))) values.add(r);
  }

  private void giftEditor(boolean give, float x, float top, float w) {
    int source = give ? draft.giveSource : draft.wantSource, target = give ? draft.giveTarget : draft.wantTarget;
    long n = give ? draft.giveArmies : draft.wantArmies;
    int owner = give ? draft.from : draft.to, receiver = give ? draft.to : draft.from;
    float rh = compact() ? 38 : 46;
    d.text(app.body, (give ? "SEND ARMIES: " : "REQUEST ARMIES: ") + n, x, top - 4, AtlasApp.GOLD);
    top -= 24;
    d.button(source < 0 ? "CHOOSE SOURCE" : "FROM " + game().name(source), x, top - rh, w, rh, Style.NORMAL, () -> pick("ARMY SOURCE", owned(owner), r -> {
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
    top -= rh + 6;
    d.button(target < 0 ? "CHOOSE NEIGHBOUR" : "TO " + game().name(target), x, top - rh, w, rh, Style.NORMAL, () -> {
      if (source < 0) GameHud.refuse("Choose a source first.");
      List<Integer> targets = new ArrayList<>();
      for (int r : game().neighbours(source)) if (game().state.owners[r] == receiver) targets.add(r);
      pick("ADJACENT DESTINATION", targets, r -> {
        if (give) draft.giveTarget = r;
        else draft.wantTarget = r;
      });
    });
    top -= rh + 6;
    float q = (w - 18) / 4;
    d.button("−", x, top - rh, q, rh, Style.NORMAL, () -> setGift(give, Math.max(0, n - 1)));
    d.button("+", x + q + 6, top - rh, q, rh, Style.NORMAL, () -> setGift(give, n + 1));
    d.button("+10", x + 2 * (q + 6), top - rh, q, rh, Style.NORMAL, () -> setGift(give, n + 10));
    d.button("MAX", x + 3 * (q + 6), top - rh, q, rh, Style.NORMAL, () -> setGift(give, source < 0 ? 0 : game().state.armies[source] - 1));
  }

  private void setGift(boolean give, long n) {
    int source = give ? draft.giveSource : draft.wantSource;
    if (source < 0) GameHud.refuse("Choose a source first.");
    n = Math.min(n, game().state.armies[source] - 1);
    if (give) draft.giveArmies = n;
    else draft.wantArmies = n;
  }

  private List<Integer> owned(int p) {
    List<Integer> list = new ArrayList<>();
    for (int r = 0; r < game().state.owners.length; r++) if (game().state.owners[r] == p) list.add(r);
    return list;
  }

  private void pick(String title, List<Integer> options, java.util.function.IntConsumer callback) {
    returnModal = hud.modal;
    pickTitle = title;
    picks = options;
    pickAction = callback;
    pickPage = 0;
    hud.modal = Modal.PICK;
  }

  private void picker() {
    float top = panel(pickTitle, true);
    float x = fx + 18, w = fw - 36, bh = compact() ? 38 : 46, bottom = fy + 14 + row() + 8;
    if (picks.isEmpty()) d.text(app.gameFont, "No eligible territories.", x, top - 8, AtlasApp.MUTED);
    int cols = Math.max(1, (int) (w / 220)), rows = Math.max(1, (int) ((top - bottom) / (bh + 6))), per = cols * rows;
    float cw = (w - (cols - 1) * 8) / cols;
    pickPage = Math.min(pickPage, Math.max(0, (picks.size() - 1) / per));
    for (int i = pickPage * per; i < Math.min(picks.size(), pickPage * per + per); i++) {
      int r = picks.get(i), k = i - pickPage * per;
      d.button(game().name(r) + " (" + game().state.armies[r] + ")", x + (k % cols) * (cw + 8), top - (k / cols + 1) * (bh + 6), cw, bh, Style.NORMAL, () -> {
        pickAction.accept(r);
        hud.modal = returnModal;
      });
    }
    int pages = Math.max(1, (picks.size() + per - 1) / per);
    if (pages > 1) {
      d.button("‹ PREV", x, fy + 14, 110, row(), Style.NORMAL, () -> pickPage = Math.max(0, pickPage - 1));
      d.button("NEXT ›", x + 118, fy + 14, 110, row(), Style.NORMAL, () -> pickPage = Math.min(pages - 1, pickPage + 1));
    }
    d.button("BACK", x + w - 160, fy + 14, 160, row(), Style.NORMAL, () -> hud.modal = returnModal);
  }

  // ---------------------------------------------------------------- settings

  private void settings() {
    float top = panel(game() == null ? "SETTINGS" : "PAUSED", true);
    float x = fx + 18, w = fw - 36, rh = compact() ? 42 : 52, gap = 8;
    boolean two = compact() || w > 700;
    float cw = two ? (w - 12) / 2 : w;
    List<Object[]> items = new ArrayList<>();
    items.add(new Object[] {"RESUME", Style.PRIMARY, (Runnable) this::close});
    items.add(new Object[] {"SOUND: " + (hud.soundEnabled ? "ON" : "OFF"), Style.NORMAL, (Runnable) () -> {
      hud.soundEnabled = !hud.soundEnabled;
      GameHud.prefs().putBoolean("sound", hud.soundEnabled).flush();
    }});
    if (android())
      items.add(new Object[] {"VIBRATION: " + (hud.hapticsEnabled ? "ON" : "OFF"), Style.NORMAL, (Runnable) () -> {
        hud.hapticsEnabled = !hud.hapticsEnabled;
        GameHud.prefs().putBoolean("haptics", hud.hapticsEnabled).flush();
      }});
    items.add(new Object[] {"COMPUTER SPEED: " + (hud.fastBots ? "FAST" : "NORMAL"), Style.NORMAL, (Runnable) hud::toggleSpeed});
    items.add(new Object[] {"TEXT SIZE: " + TEXT_SIZE_NAMES[app.textSize()], Style.NORMAL, (Runnable) () -> {
      GameHud.prefs().putInteger("textSize", (app.textSize() + 1) % TEXT_SIZE_NAMES.length).flush();
      app.applyTextSize();
    }});
    items.add(new Object[] {"MAP COLOURS: " + (hud.continentView ? "CONTINENTS" : "OWNERS"), Style.NORMAL, (Runnable) () -> {
      hud.continentView = !hud.continentView;
      GameHud.prefs().putBoolean("continentView", hud.continentView).flush();
    }});
    items.add(new Object[] {"HOW TO PLAY", Style.NORMAL, (Runnable) () -> rules(Modal.SETTINGS)});
    items.add(new Object[] {"SAVE AND EXIT TO TITLE", Style.NORMAL, (Runnable) () -> {
      hud.saveNow();
      confirmNew = false;
      hud.modal = Modal.MENU;
    }});
    int perCol = two ? (items.size() + 1) / 2 : items.size();
    for (int i = 0; i < items.size(); i++) {
      Object[] it = items.get(i);
      int col = two ? i / perCol : 0, r = two ? i % perCol : i;
      d.button((String) it[0], x + col * (cw + 12), top - (r + 1) * (rh + gap), cw, rh, (Style) it[1], (Runnable) it[2]);
    }
    float textTop = top - perCol * (rh + gap) - 12;
    if (textTop - fy > 50)
      d.wrap(
          app.small,
          "The game saves automatically, and whenever the app is paused. Map: drag to pan, pinch or double-tap to zoom, hold a territory for details, FIT to see the world."
              + (android() ? "" : " Keyboard: Enter = next step, R = roll, B = blitz, A = auto-place, U = undo, Space = skip computer turns, Esc = back."),
          x,
          textTop,
          w,
          AtlasApp.MUTED);
  }

  // ---------------------------------------------------------------- rules and tutorial

  private void rules(Modal back) {
    rulesReturn = back;
    page = 0;
    hud.modal = Modal.RULES;
  }

  private static final String[] RULES = {
    "GOAL\nConquer every territory. Five commanders share the world; territories are dealt at random and everyone places their starting armies.",
    "YOUR TURN\n1 PLACE: tap your gold-ringed territories. Choose how many each tap places, or AUTO.\n2 ATTACK: tap your territory, then an enemy neighbour, then ROLL or BLITZ.\n3 MOVE: shift armies between your connected territories, then END TURN.",
    "REINFORCEMENTS\nmax(3, territories ÷ 3) + continent bonuses + population bonus (one army per five you have on the board, unless switched off).\n\nCONTINENT BONUSES\nNorth America 9 · Asia 8 · Europe 6 · Africa 5 · Australia 4 · South America 3 · Middle-East 3.",
    "BATTLES\nAttack with 1–3 dice (you need one more army than dice). The defender rolls up to two. Highest dice are compared in pairs; ties go to the defender. BLITZ keeps rolling until you capture the territory or run out of attackers. After a capture, move in at least as many armies as dice rolled.",
    "CARDS\nCapture a territory during your turn to draw a card. Trade three of a kind, or one of each type (wilds match anything), for 4, 6, 8, 10, 12, 15, then +5 armies. A card showing land you own adds +2 there. With five or more cards you must trade.",
    "DIPLOMACY\nOffer territories, armies or open borders to another commander. Open borders let armies pass through allied land while moving; it is not a ceasefire.",
    "MAP TIPS\nRed ! marks your borders where an enemy stack clearly outnumbers you. Hold a territory for details. Tap a continent in BONUSES to spotlight it. UNDO takes back placements and moves until you roll dice."
  };

  private void rules() {
    float top = panel("HOW TO PLAY · " + (page % RULES.length + 1) + " / " + RULES.length, true);
    float x = fx + 22, w = Math.min(fw - 44, 900);
    d.wrap(compact() ? app.body : app.gameFont, RULES[page % RULES.length], x, top - 4, w, AtlasApp.WHITE);
    float rh = row();
    d.button("‹ PREV", x, fy + 14, 120, rh, Style.NORMAL, () -> page = (page + RULES.length - 1) % RULES.length);
    d.button("NEXT ›", x + 128, fy + 14, 120, rh, Style.NORMAL, () -> page = (page + 1) % RULES.length);
    d.button("BACK", fx + fw - 18 - 160, fy + 14, 160, rh, Style.PRIMARY, this::close);
  }

  private static final String[] TUTORIAL = {
    "1 · PLACE ARMIES\nTerritories with a gold ring are yours. Tap them to add armies: pick 1, 3, 5 or ALL per tap, or let AUTO place them for you. UNDO takes a placement back.",
    "2 · ATTACK\nTap one of your territories, then an enemy neighbour (or just tap the enemy). ROLL fights one round; BLITZ fights until the territory falls. The win chance is shown before you commit.",
    "3 · MOVE AND END\nAfter attacking, move armies between your connected territories, then END TURN. Capturing at least one territory earns a card; sets of cards trade for extra armies."
  };

  private void tutorial() {
    float W = app.W, H = app.H;
    d.box(0, 0, W, H, new Color(.02f, .045f, .07f, .7f));
    float w = Math.min(W - 24, 640), x = (W - w) / 2;
    BitmapFont f = compact() ? app.body : app.gameFont;
    String text = TUTORIAL[Math.min(page, TUTORIAL.length - 1)];
    float th = d.wrapHeight(f, text, w - 40), rh = row(), h = th + 30 + 26 + rh + 20;
    float y = (H - h) / 2;
    d.round(x, y, w, h, 14, AtlasApp.PANEL);
    d.round(x, y + h - 6, w, 6, 3, AtlasApp.GOLD);
    d.text(app.small, "HOW TO PLAY · " + (page + 1) + " / " + TUTORIAL.length, x + 20, y + h - 16, AtlasApp.GOLD);
    d.wrap(f, text, x + 20, y + h - 40, w - 40, AtlasApp.WHITE);
    d.button("SKIP", x + 20, y + 16, 110, rh, Style.NORMAL, this::finishTutorial);
    boolean last = page >= TUTORIAL.length - 1;
    d.button(last ? "GOT IT" : "NEXT ›", x + w - 20 - 160, y + 16, 160, rh, Style.PRIMARY, () -> {
      if (last) finishTutorial();
      else page++;
    });
  }

  private void finishTutorial() {
    hud.tutorialSeen = true;
    GameHud.prefs().putBoolean("tutorialSeen", true).flush();
    page = 0;
    hud.modal = Modal.NONE;
  }

  // ---------------------------------------------------------------- results

  private void result() {
    GameState s = game().state;
    boolean finished = s.phase == FINISHED;
    int me = -1;
    for (int p = 0; p < 5; p++) if (s.players.get(p).human) me = me < 0 ? p : me;
    boolean humanWon = finished && s.players.get(s.winner).human;
    String title = humanWon ? "VICTORY" : finished ? s.players.get(s.winner).name.toUpperCase(Locale.ROOT) + " WINS" : "DEFEATED";
    float top = panel(title, false);
    float x = fx + 18, w = fw - 36;
    String sub;
    if (finished) sub = s.players.get(s.winner).name + " conquered all " + s.owners.length + " territories in " + s.turn + " turns.";
    else {
      int by = me >= 0 ? s.eliminatedBy[me] : -1;
      sub = "You were eliminated" + (by >= 0 ? " by " + s.players.get(by).name : "") + " on turn " + (me >= 0 ? s.eliminatedTurn[me] : s.turn) + ".";
    }
    d.fit(app.gameFont, sub, x, top - 4, w, humanWon ? AtlasApp.GOLD : AtlasApp.WHITE);
    top -= 34;
    float rh = Math.min(compact() ? 34 : 44, (top - fy - 14 - row() - 30) / 6);
    float c1 = x + w * .42f, c2 = x + w * .58f, c3 = x + w * .74f, c4 = x + w;
    d.right(app.small, "CAPTURED", c1, top - 2, AtlasApp.MUTED);
    d.right(app.small, "DESTROYED", c2, top - 2, AtlasApp.MUTED);
    d.right(app.small, "LOST", c3, top - 2, AtlasApp.MUTED);
    d.right(app.small, "RESULT", c4, top - 2, AtlasApp.MUTED);
    top -= 16;
    Integer[] order = {0, 1, 2, 3, 4};
    Arrays.sort(order, Comparator.comparingInt((Integer p) -> finished && p == s.winner ? -1 : 0)
        .thenComparingInt(p -> s.eliminatedTurn[p] < 0 ? -1000 : -s.eliminatedTurn[p]));
    for (int k = 0; k < 5; k++) {
      int p = order[k];
      float y = top - (k + 1) * rh;
      if (s.players.get(p).human) d.round(x - 6, y + 2, w + 12, rh - 4, 6, Draw.RAISED);
      d.token(x + 10, y + rh / 2, 7, hud.playerColor(p));
      BitmapFont f = compact() ? app.body : app.gameFont;
      d.fit(f, s.players.get(p).name + (s.players.get(p).human ? " (you)" : ""), x + 26, GameHud.mid(f, y, rh), c1 - x - 120, AtlasApp.WHITE);
      d.right(f, Integer.toString(s.captures[p]), c1, GameHud.mid(f, y, rh), AtlasApp.WHITE);
      d.right(f, Long.toString(s.destroyed[p]), c2, GameHud.mid(f, y, rh), AtlasApp.WHITE);
      d.right(f, Long.toString(s.lost[p]), c3, GameHud.mid(f, y, rh), AtlasApp.WHITE);
      String status =
          finished && p == s.winner
              ? "Winner"
              : s.eliminatedTurn[p] >= 0
                  ? "Out, turn " + s.eliminatedTurn[p]
                  : game().count(p) + " lands";
      d.right(app.body, status, c4, GameHud.mid(app.body, y, rh), finished && p == s.winner ? AtlasApp.GOLD : AtlasApp.MUTED);
    }
    float bh = row(), half = (w - 10) / 2;
    if (finished) d.button("VIEW MAP", x, fy + 14, half, bh, Style.NORMAL, () -> hud.modal = Modal.NONE);
    else d.button("WATCH THE REST", x, fy + 14, half, bh, Style.NORMAL, () -> {
      hud.watching = true;
      hud.modal = Modal.NONE;
    });
    d.button("NEW GAME", x + half + 10, fy + 14, half, bh, Style.PRIMARY, () -> hud.modal = Modal.MENU);
  }
}
