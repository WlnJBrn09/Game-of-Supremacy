package com.supremacy.board;

import static com.supremacy.game.GameState.Phase.*;

import com.badlogic.gdx.*;
import com.badlogic.gdx.backends.lwjgl3.*;
import com.badlogic.gdx.graphics.*;
import com.supremacy.game.*;
import java.util.*;

/**
 * Desktop-only scripted play-through that saves screenshots at a chosen window size and density,
 * so layouts can be checked at phone, tablet and desktop sizes without a device:
 *
 * <pre>java -Dsupremacy.saveDir=/tmp/tour -cp game.jar com.supremacy.board.ScreenshotTour 2400 1080 2.625 out/phone</pre>
 *
 * Controls are pressed by their labels, exactly as a player would tap them. Any expectation that
 * fails is printed with a "TOUR FAILURE" prefix and makes the process exit with status 1.
 */
public final class ScreenshotTour extends ApplicationAdapter {
  private final AtlasApp app = new AtlasApp();
  private final String out;
  private final List<Runnable> script = new ArrayList<>();
  private int frame, step, wait = 20, failures;

  private ScreenshotTour(String out) {
    this.out = out;
  }

  public static void main(String[] a) {
    int w = Integer.parseInt(a[0]), h = Integer.parseInt(a[1]);
    System.setProperty("supremacy.density", a[2]);
    System.setProperty("supremacy.noAudio", "true");
    if (System.getProperty("supremacy.seed") == null) System.setProperty("supremacy.seed", "42");
    new java.io.File(a[3]).mkdirs();
    Lwjgl3ApplicationConfiguration c = new Lwjgl3ApplicationConfiguration();
    c.setPreferencesConfig(System.getProperty("supremacy.saveDir"), Files.FileType.Absolute);
    c.setWindowedMode(w, h);
    c.setResizable(false);
    c.disableAudio(true);
    c.setForegroundFPS(60);
    c.setBackBufferConfig(8, 8, 8, 8, 16, 0, 4);
    ScreenshotTour tour = new ScreenshotTour(a[3]);
    new Lwjgl3Application(tour, c);
    System.exit(tour.failures == 0 ? 0 : 1);
  }

  private GameHud hud() {
    return app.hud;
  }

  private GameState s() {
    return app.hud.game.state;
  }

  private void fail(String why) {
    failures++;
    System.out.println("TOUR FAILURE at step " + step + ": " + why);
    // Later steps depend on earlier ones; stop instead of cascading.
    if (failures >= 4) {
      System.out.println("TOUR ABORTED after " + failures + " failures");
      Gdx.app.exit();
    }
  }

  private void expect(boolean ok, String why) {
    if (!ok) fail(why);
  }

  /** Every control must be on screen and large enough to tap. */
  private void checkLayout(String name) {
    for (AtlasApp.Hit h : app.hits) {
      com.badlogic.gdx.math.Rectangle r = h.rect();
      if (r.x < -1 || r.y < -1 || r.x + r.width > app.W + 1 || r.y + r.height > app.H + 1)
        fail(name + ": '" + h.label() + "' is partly off screen at " + r);
      if (Math.min(r.width, r.height) < 28) fail(name + ": '" + h.label() + "' is only " + r.width + "x" + r.height + " units");
    }
  }

  private void shot(String name) {
    checkLayout(name);
    Pixmap p = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
    PixmapIO.writePNG(Gdx.files.absolute(out + "/" + name + ".png"), p, -1, true);
    p.dispose();
    System.out.println("shot " + name + " (" + app.W + "x" + app.H + " units, " + (hud().compact ? "phone" : "wide") + " layout)");
  }

  private void press(String label) {
    if (!app.press(label)) fail("no control labelled '" + label + "'; visible: " + app.labels());
  }

  private void pressStarting(String prefix) {
    for (String l : app.labels())
      if (l.startsWith(prefix)) {
        press(l);
        return;
      }
    fail("no control starting with '" + prefix + "'; visible: " + app.labels());
  }

  /** The active player's territory with the most armies that borders an enemy. */
  private int frontier() {
    GameState s = s();
    int best = -1;
    for (int r = 0; r < s.owners.length; r++)
      if (s.owners[r] == s.active) {
        boolean border = false;
        for (int n : hud().game.neighbours(r)) if (s.owners[n] != s.active) border = true;
        if (border && (best < 0 || s.armies[r] > s.armies[best])) best = r;
      }
    return best;
  }

  private int weakestEnemy(int r) {
    GameState s = s();
    int best = -1;
    for (int n : hud().game.neighbours(r))
      if (s.owners[n] != s.active && (best < 0 || s.armies[n] < s.armies[best])) best = n;
    return best;
  }

  private void then(Runnable r) {
    script.add(r);
  }

  /** Waits (in script steps) until a condition holds, failing after a limit. */
  private void until(String what, java.util.function.BooleanSupplier ok, int limit) {
    int[] tries = {0};
    script.add(new Runnable() {
      @Override public void run() {
        if (ok.getAsBoolean()) return;
        if (++tries[0] > limit) {
          fail("timed out waiting for " + what);
          return;
        }
        script.add(step + 1, this);
      }
    });
  }

  @Override
  public void create() {
    app.create();
    Gdx.graphics.setContinuousRendering(true);
    if ("hotseat".equals(System.getProperty("supremacy.tour"))) hotseat();
    else match();
  }

  /** Two humans sharing one device: every human turn starts behind a handover screen. */
  private void hotseat() {
    then(() -> hud().screens.humans = 2);
    then(() -> press("START NEW GAME"));
    then(() -> {
      for (char ch : "Ann".toCharArray()) Gdx.input.getInputProcessor().keyTyped(ch);
    });
    then(() -> press("NEXT PLAYER"));
    then(() -> {
      for (char ch : "Bob".toCharArray()) Gdx.input.getInputProcessor().keyTyped(ch);
    });
    then(() -> press("START GAME"));
    then(() -> {
      expect(hud().modal == GameHud.Modal.HANDOVER, "first human gets a handover screen");
      shot("h1-handover-ann");
    });
    then(() -> press("I'M ANN · START"));
    then(() -> press("SKIP"));
    then(() -> press("AUTO-PLACE"));
    then(() -> press("FINISH PLACEMENT"));
    then(() -> {
      expect(hud().modal == GameHud.Modal.HANDOVER && s().players.get(s().active).name.equals("Bob"), "second human gets a handover screen");
      expect(app.labels().stream().noneMatch(l -> l.startsWith("CARDS")), "the handover hides the game controls");
      shot("h2-handover-bob");
    });
    then(() -> press("I'M BOB · START"));
    then(() -> press("AUTO-PLACE"));
    then(() -> press("FINISH PLACEMENT"));
    until("Ann's first turn", () -> hud().modal == GameHud.Modal.HANDOVER && s().phase == REINFORCE, 400);
    then(() -> shot("h3-handover-turn1"));
    then(() -> press("I'M ANN · START"));
    then(() -> {
      expect(hud().modal == GameHud.Modal.NONE && hud().humanTurn(), "Ann plays after the handover");
      hud().pause();
    });
    then(() -> {
      expect(hud().modal == GameHud.Modal.HANDOVER, "pausing a pass-and-play game hides the board");
      shot("h4-paused");
    });
    then(() -> Gdx.app.exit());
  }

  private void match() {
    int[] home = {-1}, target = {-1};
    then(() -> shot("01-title"));
    then(() -> press("START NEW GAME"));
    then(() -> {
      expect(hud().modal == GameHud.Modal.PLAYER, "name editor opens for an unnamed player");
      for (char ch : "Waylon".toCharArray()) Gdx.input.getInputProcessor().keyTyped(ch);
    });
    then(() -> shot("02-name"));
    then(() -> press("START GAME"));
    then(() -> {
      expect(hud().game != null && s().phase == SETUP, "game starts in setup");
      shot("03-tutorial");
    });
    then(() -> press("NEXT ›"));
    then(() -> press("NEXT ›"));
    then(() -> press("GOT IT"));
    then(() -> shot("04-setup"));
    then(() -> {
      home[0] = frontier();
      long before = s().armies[home[0]];
      app.tapRegion(home[0]);
      expect(s().armies[home[0]] == before + 1, "a tap places one army");
    });
    then(() -> press("5"));
    then(() -> app.tapRegion(home[0]));
    then(() -> shot("05-setup-placing"));
    then(() -> press("AUTO-PLACE"));
    then(() -> {
      expect(s().reserve == 0, "auto-place uses the whole reserve");
      shot("06-setup-done");
    });
    then(() -> press("UNDO"));
    then(() -> expect(s().reserve > 0, "undo returns the auto-placed armies"));
    then(() -> press("AUTO-PLACE"));
    then(() -> press("FINISH PLACEMENT"));
    until("the first human turn", () -> hud().humanTurn() && s().phase == REINFORCE && hud().modal == GameHud.Modal.NONE, 400);
    then(() -> shot("07-reinforce"));
    then(() -> {
      home[0] = frontier();
      press("ALL");
    });
    then(() -> app.tapRegion(home[0]));
    then(() -> {
      expect(s().phase == ATTACK, "placing the last army moves on to attacking");
      shot("08-attack");
    });
    then(() -> app.tapRegion(frontier()));
    then(() -> {
      home[0] = hud().from;
      shot("09-attack-source");
    });
    then(() -> {
      target[0] = weakestEnemy(home[0]);
      app.tapRegion(target[0]);
    });
    then(() -> shot("10-battle"));
    then(() -> press("BLITZ"));
    then(() -> shot("11-after-blitz"));
    then(() -> {
      if (s().phase == OCCUPY) {
        shot("12-occupy");
        pressStarting("MOVE ");
      }
    });
    then(() -> hud().inspect(target[0]));
    then(() -> {
      expect(hud().inspect == target[0], "territory details open");
      shot("13-territory-info");
      hud().inspect = -1;
    });
    then(() -> {
      Board.Region r = app.board.regions.get(home[0]);
      app.camera.flyTo(r.anchor.x, r.anchor.y, 3.2f);
    });
    for (int i = 0; i < 3; i++) then(() -> {});
    then(() -> shot("14-zoomed"));
    then(() -> app.camera.animateReset());
    then(() -> {
      if (app.labels().contains("BONUSES")) press("BONUSES");
    });
    then(() -> press("continent Asia"));
    then(() -> shot("15-continent-spotlight"));
    then(() -> hud().spotlight = null);
    then(() -> pressStarting("CARDS"));
    then(() -> shot("16-cards"));
    then(() -> press("CLOSE"));
    then(() -> pressStarting(hud().compact ? "DEALS" : "DIPLOMACY"));
    then(() -> shot("17-diplomacy"));
    then(() -> press("COMPOSE OFFER"));
    then(() -> shot("18-compose"));
    then(() -> press("ARMIES"));
    then(() -> shot("19-compose-armies"));
    then(() -> press("CLOSE"));
    then(() -> press("CLOSE"));
    then(() -> {
      if (hud().compact) {
        press("commanders");
      }
    });
    then(() -> {
      if (hud().compact) {
        shot("20-commanders");
        press("CLOSE");
      }
    });
    then(() -> press("LOG"));
    then(() -> shot("21-log"));
    then(() -> press("CLOSE"));
    then(() -> press("MENU"));
    then(() -> shot("22-pause"));
    then(() -> press("HOW TO PLAY"));
    then(() -> shot("23-rules"));
    then(() -> press("BACK"));
    then(() -> press("RESUME"));
    then(() -> {
      expect(hud().modal == GameHud.Modal.NONE, "dialogs close back to the game");
      if (s().phase == ATTACK) press("END ATTACKS");
    });
    then(() -> {
      expect(s().phase == FORTIFY, "fortify after attacks");
      int from = -1, to = -1;
      for (int a = 0; a < s().owners.length && to < 0; a++)
        if (s().owners[a] == s().active && hud().game.movable(a) > 0)
          for (int b = 0; b < s().owners.length; b++)
            if (b != a && s().owners[b] == s().active && hud().game.connected(a, b, s().active)) {
              from = a;
              to = b;
              break;
            }
      if (from >= 0) {
        app.tapRegion(from);
        app.tapRegion(to);
      }
    });
    then(() -> shot("24-fortify"));
    then(() -> {
      if (app.labels().stream().anyMatch(l -> l.startsWith("MOVE "))) pressStarting("MOVE ");
    });
    then(() -> press("END TURN"));
    for (int i = 0; i < 4; i++) then(() -> {});
    then(() -> {
      expect(!hud().humanTurn(), "computer players take their turns");
      shot("25-computer-turn");
    });
    then(() -> press("SKIP TO MY TURN"));
    until("the second human turn", () -> hud().humanTurn() && hud().modal == GameHud.Modal.NONE, 600);
    for (int i = 0; i < 3; i++) then(() -> {});
    then(() -> shot("26-recap"));
    then(() -> {
      // Forge a near-win to check the victory screen: everything but one weak neighbour is ours.
      GameState s = s();
      int big = home[0] >= 0 ? home[0] : frontier();
      int last = -1;
      for (int n : hud().game.neighbours(big)) last = n;
      for (int r = 0; r < s.owners.length; r++) {
        s.owners[r] = s.active;
        s.armies[r] = Math.max(1, s.armies[r]);
      }
      s.owners[last] = (s.active + 1) % 5;
      s.armies[last] = 1;
      s.armies[big] = 40;
      s.phase = ATTACK;
      s.reserve = 0;
      hud().from = big;
      hud().to = last;
    });
    then(() -> press("BLITZ"));
    then(() -> {
      if (s().phase == OCCUPY) pressStarting("MOVE ");
    });
    for (int i = 0; i < 3; i++) then(() -> {});
    then(() -> {
      expect(s().phase == FINISHED && hud().modal == GameHud.Modal.RESULT, "victory screen after world conquest");
      shot("27-victory");
    });
    then(() -> Gdx.app.exit());
  }

  @Override
  public void resize(int w, int h) {
    app.resize(w, h);
  }

  @Override
  public void render() {
    app.pointerMoved = false; // no desktop hover tooltips in screenshots
    app.render();
    frame++;
    // One step every few frames so animations and camera flights settle before the next step.
    if (frame >= wait && step < script.size()) {
      wait = frame + 24;
      try {
        script.get(step).run();
      } catch (RuntimeException e) {
        fail(e.toString());
        e.printStackTrace(System.out);
      }
      step++;
    }
  }

  @Override
  public void dispose() {
    app.dispose();
  }
}
