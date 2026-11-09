package com.supremacy.board;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;

/** Flat, top-down map transform. Rotation and perspective are intentionally absent. */
public final class AtlasCamera {
    public static final float X = 28, Y = 134, WIDTH = 1216, HEIGHT = 730;
    public static final float BASE_SCALE = WIDTH / 1800f;
    public float centerX = 900, centerY = 375, zoom = 1;
    public float scale() { return BASE_SCALE * zoom; }
    public float screenX(float x) { return X + WIDTH / 2 + (x - centerX) * scale(); }
    public float screenY(float y) { return Y + HEIGHT / 2 + (y - centerY) * scale(); }
    public Vector2 world(float x, float y) { return new Vector2((x-X-WIDTH/2)/scale()+centerX,(y-Y-HEIGHT/2)/scale()+centerY); }
    public boolean inside(float x, float y) { return x>=X && x<=X+WIDTH && y>=Y && y<=Y+HEIGHT; }
    public void zoomAt(float factor, float x, float y) {
        Vector2 before = world(x,y);
        zoom = MathUtils.clamp(zoom*factor,1,6);
        Vector2 after = world(x,y);
        centerX += before.x-after.x; centerY += before.y-after.y; clamp();
    }
    public void pan(float dx,float dy) { centerX-=dx/scale(); centerY-=dy/scale(); clamp(); }
    public void focus(Board.Region r) { zoom=3; centerX=r.anchor.x; centerY=r.anchor.y; clamp(); }
    public void reset() { centerX=900; centerY=375; zoom=1; }
    private void clamp() {
        float halfX=WIDTH/scale()/2, halfY=HEIGHT/scale()/2;
        centerX=halfX>=900 ? 900 : MathUtils.clamp(centerX,halfX,1800-halfX);
        centerY=halfY>=375 ? 375 : MathUtils.clamp(centerY,halfY,750-halfY);
    }
}
