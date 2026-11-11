package com.supremacy.board;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Rectangle;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/** Immediate-mode UI primitives in UI units (about 1 dp). Every control registers a labelled hit. */
final class Draw {
  enum Style {
    NORMAL,
    PRIMARY,
    SELECTED,
    DANGER,
    DISABLED,
    GHOST
  }

  static final Color CONSOLE = Color.valueOf("0A1621"),
      RAISED = Color.valueOf("1D3748"),
      RAISED_HI = Color.valueOf("29495E"),
      LINE = Color.valueOf("2A4A5C"),
      OFF = Color.valueOf("152837"),
      GOLD_DARK = Color.valueOf("A88A4E"),
      PARCHMENT = Color.valueOf("EFE6CF");

  final AtlasApp app;
  /** Runs a control's action through the HUD, which reports refused moves. */
  private final Consumer<Runnable> act;

  Draw(AtlasApp app, Consumer<Runnable> act) {
    this.app = app;
    this.act = act;
  }

  void box(float x, float y, float w, float h, Color c) {
    app.begin();
    app.rect(x, y, w, h, c);
    app.shapes.end();
  }

  void round(float x, float y, float w, float h, float r, Color c) {
    app.begin();
    app.round(x, y, w, h, r, c);
    app.shapes.end();
  }

  void text(BitmapFont f, String s, float x, float y, Color c) {
    app.batch.begin();
    app.text(f, s, x, y, c);
    app.batch.end();
  }

  void center(BitmapFont f, String s, float x, float y, Color c) {
    app.batch.begin();
    app.centered(f, s, x, y, c);
    app.batch.end();
  }

  void right(BitmapFont f, String s, float x, float y, Color c) {
    text(f, s, x - app.width(f, s), y, c);
  }

  /** Draws text on one line, shrinking it to fit a width. Returns the drawn width. */
  float fit(BitmapFont f, String s, float x, float y, float w, Color c) {
    float measured = app.width(f, s);
    float scale = Math.min(1, w / Math.max(1, measured));
    app.scaleFont(f, scale);
    text(f, s, x, y, c);
    app.scaleFont(f, 1);
    return measured * scale;
  }

  void fitCenter(BitmapFont f, String s, float cx, float y, float w, Color c) {
    float measured = app.width(f, s);
    float scale = Math.min(1, w / Math.max(1, measured));
    app.scaleFont(f, scale);
    center(f, s, cx, y, c);
    app.scaleFont(f, 1);
  }

  /** Draws wrapped text whose first line's top is at {@code y}; returns the height used. */
  float wrap(BitmapFont f, String s, float x, float y, float w, Color c) {
    app.batch.begin();
    app.wrap(f, s, x, y, w, c);
    app.batch.end();
    return app.wrappedHeight(f, s, w);
  }

  float wrapHeight(BitmapFont f, String s, float w) {
    return app.wrappedHeight(f, s, w);
  }

  void token(float x, float y, float r, Color c) {
    app.begin();
    app.shapes.setColor(AtlasApp.INK);
    app.shapes.circle(x, y, r + 1.5f, 24);
    app.shapes.setColor(new Color(c).mul(.68f, .68f, .68f, 1));
    app.shapes.circle(x, y, r, 24);
    app.shapes.setColor(new Color(c).lerp(AtlasApp.WHITE, .1f));
    app.shapes.circle(x, y, r * .83f, 24);
    app.shapes.end();
  }

  void hit(float x, float y, float w, float h, String label, Runnable run) {
    app.hits.add(new AtlasApp.Hit(new Rectangle(x, y, w, h), () -> act.accept(run), label));
  }

  void button(String label, float x, float y, float w, float h, Style style, Runnable run) {
    Rectangle rect = new Rectangle(x, y, w, h);
    boolean pressed = style != Style.DISABLED && app.pressing(rect);
    Color bg =
        switch (style) {
          case PRIMARY -> AtlasApp.GOLD;
          case SELECTED -> RAISED_HI;
          case DANGER -> GameHud.RED;
          case DISABLED -> AtlasApp.alpha(RAISED, .5f);
          case GHOST -> AtlasApp.alpha(AtlasApp.INK, .93f);
          default -> RAISED;
        };
    Color fg =
        switch (style) {
          case PRIMARY -> AtlasApp.INK;
          case DISABLED -> AtlasApp.alpha(AtlasApp.MUTED, .85f);
          case SELECTED -> AtlasApp.GOLD;
          default -> AtlasApp.WHITE;
        };
    if (pressed) bg = new Color(bg).mul(.8f, .8f, .8f, 1);
    float lift = pressed ? 1 : 0;
    app.begin();
    if (style == Style.SELECTED) app.round(x - 2, y - 2, w + 4, h + 4, 9, AtlasApp.GOLD);
    if (style == Style.PRIMARY && !pressed) app.round(x, y - 3, w, h, 8, GOLD_DARK);
    if (style == Style.DANGER && !pressed)
      app.round(x, y - 3, w, h, 8, new Color(GameHud.RED).mul(.7f, .7f, .7f, 1));
    if (style == Style.NORMAL && !pressed) app.round(x, y - 2, w, h, 8, AtlasApp.INK);
    app.round(x, y - lift, w, h, 7, bg);
    app.shapes.end();
    if (label.equals("−") || label.equals("+")) {
      // Drawn, not typed: crisp at every size and independent of font glyph metrics.
      float s = Math.min(16, h * .36f);
      box(x + w / 2 - s / 2, y - lift + h / 2 - 1.25f, s, 2.5f, fg);
      if (label.equals("+")) box(x + w / 2 - 1.25f, y - lift + h / 2 - s / 2, 2.5f, s, fg);
    } else {
      BitmapFont f = h >= 44 && w >= 120 ? app.gameFont : app.body;
      fitCenter(f, label, x + w / 2, y - lift + h / 2 + f.getCapHeight() / 2, w - 14, fg);
    }
    hit(x, y, w, h, label, run);
  }

  /** A die face; {@code n} from 1 to 6. */
  void die(float x, float y, float size, int n, Color face, Color pip) {
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

  /**
   * A horizontal amount slider with − / + steppers on either side, {@code h} tall. The value is
   * clamped to [min, max] and reported through {@code set}.
   */
  void amount(float x, float y, float w, float h, long value, long min, long max, LongConsumer set) {
    float b = Math.min(48, h);
    button("−", x, y, b, h, Style.NORMAL, () -> set.accept(Math.max(min, value - 1)));
    button("+", x + w - b, y, b, h, Style.NORMAL, () -> set.accept(Math.min(max, value + 1)));
    float sx = x + b + 18, sw = Math.max(10, w - 2 * b - 36), cy = y + h / 2;
    float f = max <= min ? 1 : (value - min) / (float) (max - min);
    app.begin();
    app.round(sx, cy - 3.5f, sw, 7, 3.5f, AtlasApp.INK);
    app.round(sx, cy - 3.5f, Math.max(7, sw * f), 7, 3.5f, AtlasApp.GOLD);
    app.shapes.setColor(AtlasApp.INK);
    app.shapes.circle(sx + sw * f, cy, 13, 24);
    app.shapes.setColor(AtlasApp.GOLD);
    app.shapes.circle(sx + sw * f, cy, 10.5f, 24);
    app.shapes.end();
    if (max > min)
      app.sliders.add(
          new AtlasApp.Slider(
              new Rectangle(sx - 18, y - 4, sw + 36, h + 8),
              v -> {
                set.accept(min + Math.round(v * (max - min)));
                Gdx.graphics.requestRendering();
              }));
  }

  static float clamp(float v, float lo, float hi) {
    return MathUtils.clamp(v, lo, Math.max(lo, hi));
  }
}
