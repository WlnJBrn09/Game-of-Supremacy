package com.supremacy.board;

import static com.supremacy.game.GameState.Phase.*;

import com.badlogic.gdx.*;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Rectangle;
import com.supremacy.game.*;
import java.util.*;

/** Pass-and-play interaction layer. Every action delegates to the shared rules engine. */
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
  private float animation;
  private String status = "", pickTitle = "";
  private List<String> report = List.of();
  private List<Integer> picks = List.of();
  private java.util.function.IntConsumer pickAction;
  private final List<Integer> cards = new ArrayList<>();
  private GameState.Offer draft, viewOffer;
  private boolean armyPage, soundEnabled;
  private Sound diceSound, winSound;
  private final String[] phases = {
    "INITIAL DEPLOYMENT", "REINFORCE", "ATTACK", "OCCUPY", "FORTIFY", "VICTORY"
  };

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
      status = "Sound unavailable on this device.";
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
    lastActive = -1;
    modal = Modal.NONE;
    app.camera.reset();
    checkTurn();
  }

  private void action(Runnable command) {
    try {
      status = "";
      command.run();
      checkTurn();
    } catch (IllegalArgumentException | IllegalStateException | ArithmeticException e) {
      status = e.getMessage();
    }
    Gdx.graphics.requestRendering();
  }

  private void checkTurn() {
    if (game == null) return;
    if (game.state.active != lastActive) {
      lastActive = game.state.active;
      from = to = -1;
      amount = 1;
      cards.clear();
      page = 0;
      modal = game.state.players.get(lastActive).human ? Modal.HANDOVER : Modal.NONE;
    }
  }

  boolean interactive() {
    return game != null
        && modal == Modal.NONE
        && game.state.players.get(game.state.active).human
        && game.state.phase != FINISHED
        && animation <= 0;
  }

  boolean blocksBoard() {
    return modal != Modal.NONE || !interactive();
  }

  void select(int r) {
    if (!interactive()) return;
    GameState s = game.state;
    if (s.phase == OCCUPY) {
      from = s.occupationFrom;
      to = s.occupationTo;
      return;
    }
    if (s.phase == SETUP || s.phase == REINFORCE) {
      from = r;
      to = -1;
    } else if (s.owners[r] == s.active) {
      if (s.phase == FORTIFY && from >= 0 && from != r) to = r;
      else {
        from = r;
        to = -1;
      }
    } else if (from >= 0) to = r;
    else {
      from = -1;
      to = r;
    }
    amount = 1;
  }

  void render() {
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
          status = "Bot paused: " + e.getMessage();
          modal = Modal.SETTINGS;
        }
      }
      if (animation > 0) {
        animation = Math.max(0, animation - Gdx.graphics.getDeltaTime());
        Gdx.graphics.requestRendering();
      }
      if (!game.state.players.get(game.state.active).human && modal == Modal.NONE)
        Gdx.graphics.requestRendering();
    }
    chrome();
    sidebar();
    legend();
    if (modal != Modal.NONE) {
      app.hits.clear();
      drawModal();
    }
    if (animation > 0) drawDice();
  }

  private void box(float x, float y, float w, float h, Color c) {
    app.begin();
    app.rect(x, y, w, h, c);
    app.shapes.end();
  }

  private void txt(String s, float x, float y, Color c) {
    app.batch.begin();
    app.text(app.gameFont, s, x, y, c);
    app.batch.end();
  }

  private void wrap(String s, float x, float y, float w, Color c) {
    app.batch.begin();
    app.wrap(app.gameFont, s, x, y, w, c);
    app.batch.end();
  }

  private void button(String label, float x, float y, float w, Runnable run) {
    button(label, x, y, w, false, run);
  }

  private void button(String label, float x, float y, float w, boolean active, Runnable run) {
    box(x, y, w, 52, active ? Color.valueOf("42575C") : Color.valueOf("203846"));
    app.batch.begin();
    com.badlogic.gdx.graphics.g2d.GlyphLayout measured =
        new com.badlogic.gdx.graphics.g2d.GlyphLayout(app.gameFont, label);
    app.gameFont.getData().setScale(Math.min(1, (w - 20) / Math.max(1, measured.width)));
    app.centered(app.gameFont, label, x + w / 2, y + 35, active ? AtlasApp.GOLD : AtlasApp.WHITE);
    app.gameFont.getData().setScale(1);
    app.batch.end();
    app.hits.add(new AtlasApp.Hit(new Rectangle(x, y, w, 52), () -> action(run)));
  }

  private void chrome() {
    box(28, 897, 1544, 1, AtlasApp.GRID);
    app.batch.begin();
    app.text(app.title, "SUPREMACY", 28, 969, AtlasApp.WHITE);
    app.text(app.small, "OFFLINE  /  FIVE PLAYERS  /  WORLD CONQUEST", 30, 918, AtlasApp.MUTED);
    app.batch.end();
    button("CARDS", 650, 926, 150, () -> open(Modal.CARDS));
    button("DIPLOMACY", 812, 926, 200, () -> open(Modal.DIPLOMACY));
    button(
        "REPORT",
        1024,
        926,
        150,
        () -> {
          report = game == null ? List.of() : game.report(game.state.active);
          open(Modal.REPORT);
        });
    button("OPTIONS", 1186, 926, 180, () -> open(Modal.SETTINGS));
    button("RESET MAP", 1378, 926, 194, () -> app.camera.reset());
    if (game != null) {
      GameState s = game.state;
      txt(
          "TURN "
              + s.turn
              + "   /   "
              + s.players.get(s.active).name
              + "   /   "
              + phases[s.phase.ordinal()],
          28,
          889,
          AtlasApp.GOLD);
    }
    button("−", 1090, 148, 55, () -> app.camera.zoomAt(.8f, 636, 499));
    button("+", 1155, 148, 55, () -> app.camera.zoomAt(1.25f, 636, 499));
    wrap(status, 28, 111, 1190, status.startsWith("Autosave") ? Color.SALMON : AtlasApp.GOLD);
  }

  private void sidebar() {
    box(1264, 134, 308, 730, AtlasApp.PANEL);
    if (game == null) return;
    GameState s = game.state;
    if (s.phase == FINISHED) {
      wrap(s.players.get(s.winner).name + " conquered the world!", 1284, 830, 268, AtlasApp.GOLD);
      button("MAIN MENU", 1284, 200, 268, () -> modal = Modal.MENU);
      return;
    }
    txt(s.players.get(s.active).name, 1284, 836, Color.valueOf(GameState.COLORS[s.active]));
    txt(phases[s.phase.ordinal()], 1284, 804, AtlasApp.WHITE);
    if (!s.players.get(s.active).human) {
      wrap(
          "Resolving bot turns…\nYour report will be ready next turn.",
          1284,
          750,
          268,
          AtlasApp.MUTED);
      return;
    }
    if (s.phase == SETUP || s.phase == REINFORCE) {
      txt("RESERVE: " + s.reserve, 1284, 756, AtlasApp.GOLD);
      if (s.phase == REINFORCE)
        wrap("Territories + continents + 20% population", 1284, 724, 268, AtlasApp.MUTED);
    }
    if (s.phase == OCCUPY) {
      from = s.occupationFrom;
      to = s.occupationTo;
    }
    wrap(
        from < 0 ? "Tap your source territory" : game.name(from) + "  (" + s.armies[from] + ")",
        1284,
        656,
        268,
        AtlasApp.WHITE);
    if (to >= 0)
      wrap("→ " + game.name(to) + "  (" + s.armies[to] + ")", 1284, 594, 268, AtlasApp.GOLD);
    button("CHOOSE TERRITORY", 1284, 492, 268, () -> chooseTerritory());
    long max = maximum();
    amount = Math.max(minimum(), Math.min(amount, Math.max(minimum(), max)));
    if (s.phase == ATTACK) {
      if (from >= 0) dice = (int) Math.max(1, Math.min(dice, s.armies[from] - 1));
      txt("ATTACK DICE", 1284, 462, AtlasApp.MUTED);
      for (int d = 1; d <= 3; d++) {
        final int chosen = d;
        button("" + d, 1284 + (d - 1) * 92, 388, 84, dice == d, () -> dice = chosen);
      }
      button(
          "ROLL DICE",
          1284,
          318,
          268,
          () -> {
            if (from < 0 || to < 0)
              throw new IllegalArgumentException("Select your army, then an adjacent enemy");
            game.attack(from, to, dice);
            animation = .65f;
            if (soundEnabled && diceSound != null) diceSound.play(.45f);
            if (game.state.phase == OCCUPY) {
              amount = game.state.occupationMinimum;
              if (soundEnabled && winSound != null) winSound.play(.5f);
            }
          });
    } else {
      txt("ARMIES: " + amount, 1284, 462, AtlasApp.GOLD);
      button("−", 1284, 388, 60, () -> amount = Math.max(minimum(), amount - 1));
      button("+", 1352, 388, 60, () -> amount = Math.min(maximum(), amount + 1));
      button("MAX", 1420, 388, 132, () -> amount = Math.max(minimum(), maximum()));
      button("+10", 1284, 326, 96, () -> amount = Math.min(maximum(), amount + 10));
      button(
          s.phase == OCCUPY ? "OCCUPY" : s.phase == FORTIFY ? "MOVE" : "DEPLOY",
          1390,
          326,
          162,
          () -> {
            if (s.phase == OCCUPY) game.occupy(amount);
            else if (s.phase == FORTIFY) game.fortify(from, to, amount);
            else game.deploy(from, amount);
          });
    }
    String next =
        s.phase == SETUP
            ? "FINISH SETUP"
            : s.phase == REINFORCE
                ? "BEGIN ATTACKS"
                : s.phase == ATTACK
                    ? "FINISH ATTACKS"
                    : s.phase == FORTIFY ? "END TURN" : "COMPLETE OCCUPATION";
    if (s.phase != OCCUPY)
      button(
          next,
          1284,
          200,
          268,
          true,
          () -> {
            if (s.phase == SETUP || s.phase == REINFORCE) game.finishDeployment();
            else if (s.phase == ATTACK) game.finishAttacks();
            else game.endTurn();
          });
    if (s.attackDice.length > 0) {
      txt("Last roll: " + Arrays.toString(s.attackDice), 1284, 294, AtlasApp.WHITE);
      txt("Defence: " + Arrays.toString(s.defendDice), 1284, 264, AtlasApp.MUTED);
    }
  }

  private long minimum() {
    return game != null && game.state.phase == OCCUPY ? game.state.occupationMinimum : 1;
  }

  private long maximum() {
    if (game == null) return 1;
    GameState s = game.state;
    return switch (s.phase) {
      case SETUP, REINFORCE -> s.reserve;
      case OCCUPY -> s.armies[s.occupationFrom] - 1;
      case FORTIFY -> from >= 0 ? game.movable(from) : 1;
      default -> 1;
    };
  }

  private void chooseTerritory() {
    GameState s = game.state;
    List<Integer> list = new ArrayList<>();
    for (int i = 0; i < s.owners.length; i++) if (s.owners[i] == s.active) list.add(i);
    pick(
        "YOUR TERRITORIES",
        list,
        r -> {
          from = r;
          to = -1;
          app.selected = app.board.regions.get(r);
          app.camera.focus(app.selected);
        });
  }

  private void legend() {
    if (game == null) return;
    for (int p = 0; p < 5; p++) {
      int player = p;
      float x = 28 + p * 310;
      box(x, 44, 298, 48, AtlasApp.PANEL);
      box(x + 10, 58, 15, 15, Color.valueOf(GameState.COLORS[p]));
      txt(
          game.state.players.get(p).name + "   " + game.count(p) + " lands",
          x + 35,
          77,
          game.alive(p) ? AtlasApp.WHITE : AtlasApp.MUTED);
      app.hits.add(
          new AtlasApp.Hit(
              new Rectangle(x, 44, 298, 48),
              () -> {
                if (interactive() && player != game.state.active) {
                  recipient = player;
                  modal = Modal.DIPLOMACY;
                }
              }));
    }
  }

  private void open(Modal next) {
    if (game == null || !game.state.players.get(game.state.active).human) {
      if (next == Modal.SETTINGS) modal = next;
      return;
    }
    modal = next;
    page = 0;
    cards.clear();
  }

  private void panel(String title) {
    box(0, 0, 1600, 1000, new Color(.035f, .07f, .10f, .97f));
    box(245, 128, 1110, 744, AtlasApp.PANEL);
    txt(title, 280, 834, AtlasApp.GOLD);
    if (modal != Modal.MENU && modal != Modal.HANDOVER)
      button("CLOSE", 1140, 790, 180, () -> modal = Modal.NONE);
    wrap(status, 280, 170, 1000, Color.SALMON);
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
    panel("GAME OF SUPREMACY");
    wrap("Five players. One world. Pass the device when prompted.", 280, 752, 1000, AtlasApp.WHITE);
    button("HUMANS: " + humans, 280, 626, 470, () -> humans = humans % 5 + 1);
    button(
        "AI: " + GameState.Difficulty.values()[difficulty],
        780,
        626,
        470,
        () -> difficulty = (difficulty + 1) % 6);
    wrap(
        "Remaining seats: "
            + (5 - humans)
            + " bots. Charles, George, Douglas and Dan.\n"
            + "Each player receives 55 starting armies. Territories are dealt randomly.",
        280,
        590,
        970,
        AtlasApp.MUTED);
    button(
        "NEW MATCH",
        280,
        430,
        970,
        true,
        () -> {
          if (game != null || saves.exists()) {
            returnModal = Modal.MENU;
            status = "A new match replaces the autosave. Press CONFIRM NEW MATCH below.";
          } else newMatch();
        });
    if (status.startsWith("A new match"))
      button(
          "CONFIRM NEW MATCH",
          280,
          360,
          970,
          () -> {
            status = "";
            newMatch();
          });
    if (saves.exists())
      button(
          "RESUME AUTOSAVE",
          280,
          280,
          470,
          () -> {
            GameEngine restored = saves.load(app.board);
            String warning = saves.warning();
            attach(restored);
            status = warning;
          });
    button("HOW TO PLAY", 780, 280, 470, () -> modal = Modal.RULES);
    if (game != null)
      button(
          "RETURN TO MATCH",
          280,
          210,
          970,
          () -> {
            modal = game.state.players.get(game.state.active).human ? Modal.HANDOVER : Modal.NONE;
          });
  }

  private void newMatch() {
    attach(
        new GameEngine(
            app.board, humans, GameState.Difficulty.values()[difficulty], System.nanoTime()));
  }

  private void handover() {
    panel("PASS THE DEVICE");
    String name = game.state.players.get(game.state.active).name;
    wrap(
        "It is "
            + name
            + "’s turn.\n\nOther players: pass the device before continuing. Cards stay private.",
        360,
        695,
        880,
        AtlasApp.WHITE);
    button(
        "I AM " + name.toUpperCase(Locale.ROOT),
        360,
        350,
        880,
        true,
        () -> {
          report = game.report(game.state.active);
          modal = game.state.phase == SETUP ? Modal.NONE : Modal.REPORT;
          page = 0;
        });
  }

  private void report() {
    panel("YOUR TURN REPORT");
    if (report.isEmpty()) wrap("No new events.", 280, 740, 1000, AtlasApp.MUTED);
    List<String> lines =
        report.stream()
            .filter(l -> !l.contains("; losses"))
            .collect(java.util.stream.Collectors.toList());
    for (int i = page * 7; i < Math.min(lines.size(), page * 7 + 7); i++)
      wrap(lines.get(i), 280, 746 - (i - page * 7) * 72, 1020, AtlasApp.WHITE);
    paging(lines.size(), 7);
    button(
        "CONTINUE",
        780,
        210,
        470,
        true,
        () -> {
          game.markRead();
          modal = Modal.NONE;
        });
  }

  private void cards() {
    panel("PRIVATE CARDS  /  " + game.state.players.get(game.state.active).name);
    List<Integer> hand = game.state.players.get(game.state.active).cards;
    txt(
        "Next set: " + game.nextSetValue() + " armies. Trade while holding five or more.",
        280,
        748,
        AtlasApp.MUTED);
    for (int i = page * 6; i < Math.min(hand.size(), page * 6 + 6); i++) {
      int card = hand.get(i);
      String label =
          (game.symbol(card) == 3
              ? "WILD"
              : "ABC".charAt(game.symbol(card)) + "  " + game.name(card));
      button(
          label,
          280,
          652 - (i - page * 6) * 61,
          970,
          cards.contains(card),
          () -> {
            if (!cards.remove(Integer.valueOf(card)) && cards.size() < 3) cards.add(card);
          });
    }
    paging(hand.size(), 6);
    button(
        "CHOOSE VALID SET",
        280,
        210,
        470,
        () -> {
          cards.clear();
          cards.addAll(game.findSet(game.state.active));
        });
    button(
        "TRADE SELECTED",
        780,
        210,
        470,
        true,
        () -> {
          game.tradeCards(new ArrayList<>(cards));
          cards.clear();
        });
  }

  private void diplomacy() {
    panel("DIPLOMACY  /  OFFERS ARRIVE ON THE RECIPIENT’S TURN");
    List<GameState.Offer> inbox = game.inbox();
    for (int i = page * 5; i < Math.min(inbox.size(), page * 5 + 5); i++) {
      GameState.Offer o = inbox.get(i);
      button(
          "#" + o.id + "  FROM " + game.state.players.get(o.from).name,
          280,
          665 - (i - page * 5) * 65,
          970,
          () -> {
            viewOffer = o;
            page = 0;
            modal = Modal.OFFER;
          });
    }
    if (inbox.isEmpty()) txt("No pending offers.", 280, 740, AtlasApp.MUTED);
    paging(inbox.size(), 5);
    if (recipient == game.state.active || !game.alive(recipient)) cycleRecipient();
    button("TO: " + game.state.players.get(recipient).name, 280, 280, 470, () -> cycleRecipient());
    button(
        "COMPOSE OFFER",
        780,
        280,
        470,
        true,
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
        borders ? "REVOKE OPEN BORDERS" : "NO OPEN BORDERS WITH RECIPIENT",
        280,
        210,
        970,
        () -> {
          if (borders) game.revokeBorders(recipient);
        });
  }

  private void cycleRecipient() {
    do {
      recipient = (recipient + 1) % 5;
    } while (recipient == game.state.active || !game.alive(recipient));
  }

  private String describe(GameState.Offer o) {
    return game.state.players.get(o.from).name
        + " offers: "
        + territoryList(o.giveTerritories)
        + gift(o.giveArmies, o.giveSource, o.giveTarget)
        + "\n\nRequests: "
        + territoryList(o.wantTerritories)
        + gift(o.wantArmies, o.wantSource, o.wantTarget)
        + "\n\nOpen borders: "
        + (o.openBorders ? "mutual fortification transit" : "not included")
        + ".\nGarrisons return automatically; offered assets stay usable until acceptance.";
  }

  private String gift(long n, int source, int target) {
    return n == 0
        ? ""
        : "; " + n + " armies from " + game.name(source) + " to " + game.name(target);
  }

  private String territoryList(List<Integer> list) {
    return list.isEmpty()
        ? "no territories"
        : String.join(
            ", ", list.stream().map(game::name).collect(java.util.stream.Collectors.toList()));
  }

  private void offer() {
    panel("OFFER #" + viewOffer.id);
    List<String> lines = new ArrayList<>();
    for (int r : viewOffer.giveTerritories) lines.add("GIVES TERRITORY: " + game.name(r));
    for (int r : viewOffer.wantTerritories) lines.add("REQUESTS TERRITORY: " + game.name(r));
    if (viewOffer.giveArmies > 0)
      lines.add("GIVES" + gift(viewOffer.giveArmies, viewOffer.giveSource, viewOffer.giveTarget));
    if (viewOffer.wantArmies > 0)
      lines.add(
          "REQUESTS" + gift(viewOffer.wantArmies, viewOffer.wantSource, viewOffer.wantTarget));
    lines.add("MUTUAL OPEN BORDERS: " + (viewOffer.openBorders ? "included" : "not included"));
    lines.add("Garrisons return automatically. Terms are rechecked when accepted.");
    for (int i = page * 6; i < Math.min(lines.size(), page * 6 + 6); i++)
      wrap(lines.get(i), 280, 744 - (i - page * 6) * 65, 1020, AtlasApp.WHITE);
    paging(lines.size(), 6);
    button(
        "ACCEPT",
        280,
        260,
        300,
        true,
        () -> {
          boolean accepted = game.answer(viewOffer.id, true);
          status = accepted ? "Exchange completed." : "Offer expired because its terms changed.";
          modal = Modal.DIPLOMACY;
        });
    button(
        "REJECT",
        610,
        260,
        300,
        () -> {
          game.answer(viewOffer.id, false);
          modal = Modal.DIPLOMACY;
        });
    button(
        "COUNTER",
        940,
        260,
        300,
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
    panel("OFFER TO " + game.state.players.get(draft.to).name);
    button(
        armyPage ? "SHOW TERRITORIES" : "ARMIES + OPEN BORDERS",
        280,
        714,
        970,
        () -> {
          armyPage = !armyPage;
          page = 0;
        });
    if (!armyPage) {
      List<Integer> own = new ArrayList<>(), theirs = new ArrayList<>();
      for (int r = 0; r < game.state.owners.length; r++) {
        if (game.state.owners[r] == draft.from) own.add(r);
        if (game.state.owners[r] == draft.to) theirs.add(r);
      }
      txt("GIVE TERRITORIES", 280, 688, AtlasApp.MUTED);
      txt("REQUEST TERRITORIES", 780, 688, AtlasApp.MUTED);
      for (int i = page * 5; i < Math.min(Math.max(own.size(), theirs.size()), page * 5 + 5); i++) {
        float y = 610 - (i - page * 5) * 62;
        if (i < own.size()) {
          int r = own.get(i);
          button(
              game.name(r),
              280,
              y,
              470,
              draft.giveTerritories.contains(r),
              () -> toggle(draft.giveTerritories, r));
        }
        if (i < theirs.size()) {
          int r = theirs.get(i);
          button(
              game.name(r),
              780,
              y,
              470,
              draft.wantTerritories.contains(r),
              () -> toggle(draft.wantTerritories, r));
        }
      }
      paging(Math.max(own.size(), theirs.size()), 5);
    } else {
      giftEditor(true, 642);
      giftEditor(false, 430);
      button(
          draft.openBorders ? "OPEN BORDERS INCLUDED" : "ADD MUTUAL OPEN BORDERS",
          280,
          260,
          970,
          draft.openBorders,
          () -> draft.openBorders = !draft.openBorders);
    }
    button(
        "SEND OFFER",
        780,
        210,
        470,
        true,
        () -> {
          if (counterId < 0) game.sendOffer(draft);
          else game.counter(counterId, draft);
          modal = Modal.DIPLOMACY;
          page = 0;
          status = "Offer sent. The response arrives on your next turn.";
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
    txt((give ? "GIVE" : "REQUEST") + " ARMIES: " + n, 280, y + 45, AtlasApp.GOLD);
    button(
        source < 0 ? "CHOOSE SOURCE" : game.name(source),
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
        target < 0 ? "CHOOSE ADJACENT DESTINATION" : game.name(target),
        780,
        y - 20,
        470,
        () -> {
          if (source < 0) throw new IllegalArgumentException("Choose source first");
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
    if (source < 0) throw new IllegalArgumentException("Choose source first");
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
    for (int i = pickPage * 15; i < Math.min(picks.size(), pickPage * 15 + 15); i++) {
      int r = picks.get(i), k = i - pickPage * 15;
      button(
          game.name(r),
          280 + (k % 3) * 340,
          668 - (k / 3) * 84,
          325,
          () -> {
            pickAction.accept(r);
            modal = returnModal;
          });
    }
    button("PREVIOUS", 280, 210, 300, () -> pickPage = Math.max(0, pickPage - 1));
    button(
        "NEXT",
        610,
        210,
        300,
        () -> {
          if ((pickPage + 1) * 15 < picks.size()) pickPage++;
        });
    button("BACK", 940, 210, 300, () -> modal = returnModal);
  }

  private void paging(int count, int perPage) {
    button("PREVIOUS", 280, 210, 220, () -> page = Math.max(0, page - 1));
    button(
        "NEXT",
        515,
        210,
        220,
        () -> {
          if ((page + 1) * perPage < count) page++;
        });
  }

  private void settings() {
    panel("OPTIONS");
    button(
        "SOUND: " + (soundEnabled ? "ON" : "OFF"),
        280,
        650,
        970,
        () -> {
          soundEnabled = !soundEnabled;
          Gdx.app.getPreferences("supremacy-options").putBoolean("sound", soundEnabled).flush();
        });
    button("RULES + CONTROLS", 280, 570, 970, () -> modal = Modal.RULES);
    button(
        "MAIN MENU",
        280,
        490,
        970,
        () -> {
          if (game != null) saves.save(game);
          modal = Modal.MENU;
          status = "";
        });
    wrap(
        "The match saves after every action and when the app pauses.\n"
            + "The board stays directly overhead. Pinch or use + / − to zoom; drag to pan.",
        280,
        420,
        970,
        AtlasApp.MUTED);
  }

  private void rules() {
    panel("RULES + CONTROLS");
    String[] pages = {
      "Random territories; 55 armies each. Place your remaining armies, then finish setup.\n\n"
          + "Each turn: reinforce, attack, fortify. Reinforcements = max(3, lands / 3) + continent"
          + " bonuses + floor(current armies / 5).\n\n"
          + "Bonuses: North America 9; South America 3; Europe 6; Africa 5; Asia 8; Australia 4;"
          + " Middle-East 3.",
      "Tap your territory, then an adjacent enemy. Choose 1–3 attack dice and roll once. Defence"
          + " automatically uses up to two dice. Ties favour defence.\n\n"
          + "After conquest, move at least the number of attack dice and leave one behind."
          + " Capturing the last enemy territory wins. Eliminated players give only their cards.",
      "Conquer at least one territory for one card at turn end. Sets: three of a kind, or one of"
          + " each, with wilds. Rewards: 4, 6, 8, 10, 12, 15, then +5. One matched owned territory"
          + " gets +2.\n\n"
          + "Trade at five cards; six after elimination triggers immediate trading. Fortify any"
          + " number of times through connected friendly land, but each piece moves only once.",
      "Send trade offers on your turn. On their turn, the recipient accepts immediately, counters"
          + " or rejects. You see the response next turn. Pending assets stay usable; impossible"
          + " offers expire.\n\n"
          + "Open borders allows mutual fortification transit, including sea paths. It does not"
          + " permit attacks from another player’s land and is not a ceasefire. Either side may"
          + " revoke it on their turn.\n\n"
          + "Territory garrisons return automatically to bordering friendly land or the nearest"
          + " friendly land by board connections."
    };
    wrap(pages[page % pages.length], 280, 732, 1020, AtlasApp.WHITE);
    button("PREVIOUS", 280, 210, 470, () -> page = Math.max(0, page - 1));
    button("NEXT", 780, 210, 470, () -> page = (page + 1) % pages.length);
  }

  private void drawDice() {
    box(425, 398, 700, 192, AtlasApp.INK);
    txt("ATTACK", 455, 562, Color.SALMON);
    txt("DEFENCE", 805, 562, AtlasApp.WHITE);
    int tick = (int) (animation * 30);
    for (int i = 0; i < game.state.attackDice.length; i++)
      die(455 + i * 92, 440, 1 + ((tick + i * 3) % 6), Color.SALMON);
    for (int i = 0; i < game.state.defendDice.length; i++)
      die(805 + i * 92, 440, 1 + ((tick + i * 2) % 6), AtlasApp.WHITE);
  }

  private void die(float x, float y, int n, Color c) {
    box(x, y, 70, 70, c);
    int[][] dots = {{0, 0}, {-1, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, 0}, {1, 0}};
    List<Integer> use =
        switch (n) {
          case 1 -> List.of(0);
          case 2 -> List.of(1, 2);
          case 3 -> List.of(0, 1, 2);
          case 4 -> List.of(1, 2, 3, 4);
          case 5 -> List.of(0, 1, 2, 3, 4);
          default -> List.of(1, 2, 3, 4, 5, 6);
        };
    app.begin();
    app.shapes.setColor(AtlasApp.INK);
    for (int i : use) app.shapes.circle(x + 35 + dots[i][0] * 20, y + 35 + dots[i][1] * 20, 5, 12);
    app.shapes.end();
  }

  void pause() {
    if (game != null) {
      try {
        saves.save(game);
      } catch (RuntimeException e) {
        status = e.getMessage();
      }
      if (game.state.players.get(game.state.active).human) modal = Modal.HANDOVER;
    }
  }

  void back() {
    if (modal == Modal.HANDOVER) return;
    if (modal == Modal.NONE) modal = Modal.SETTINGS;
    else if (modal == Modal.PICK) modal = returnModal;
    else if (game == null) modal = Modal.MENU;
    else modal = Modal.NONE;
    cards.clear();
  }

  void dispose() {
    if (diceSound != null) diceSound.dispose();
    if (winSound != null) winSound.dispose();
  }
}
