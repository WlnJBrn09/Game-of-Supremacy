package com.supremacy.board;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;

/**
 * Flat, top-down map transform inside a screen rectangle. Rotation and perspective are intentionally
 * absent. Overlays may cover the edges of the rectangle; the camera fits and clamps the world to the
 * uncovered ("safe") part so nothing is stranded under a panel.
 */
public final class AtlasCamera {
    public static final float WORLD_W = 1800, WORLD_H = 750, MAX_ZOOM = 8;
    /** Map rectangle on screen, in UI units. */
    public float x = 0, y = 268, width = 1600, height = 676;
    /** Parts of the map rectangle covered by overlays. */
    public float insetLeft, insetRight, insetTop, insetBottom;
    public float centerX = WORLD_W / 2, centerY = WORLD_H / 2, zoom = 1;
    // Animation target and fling velocity (world units per second).
    private float targetX = Float.NaN, targetY, targetZoom;
    private float flingX, flingY;

    public void setRect(float x, float y, float w, float h) {
        this.x = x; this.y = y; width = w; height = h; clamp();
    }
    public void setInsets(float left, float right, float top, float bottom) {
        insetLeft = left; insetRight = right; insetTop = top; insetBottom = bottom; clamp();
    }
    float safeW() { return Math.max(1, width - insetLeft - insetRight); }
    float safeH() { return Math.max(1, height - insetTop - insetBottom); }
    float safeX() { return x + insetLeft + safeW() / 2; }
    float safeY() { return y + insetBottom + safeH() / 2; }
    /** Scale at zoom 1: the whole world fits the safe area. */
    public float fitScale() { return Math.min(safeW() / WORLD_W, safeH() / WORLD_H); }
    public float scale() { return fitScale() * zoom; }
    public float screenX(float wx) { return safeX() + (wx - centerX) * scale(); }
    public float screenY(float wy) { return safeY() + (wy - centerY) * scale(); }
    public Vector2 world(float sx, float sy) { return new Vector2((sx - safeX()) / scale() + centerX, (sy - safeY()) / scale() + centerY); }
    public boolean inside(float sx, float sy) { return sx >= x && sx <= x + width && sy >= y && sy <= y + height; }
    /** True when a screen point is inside the uncovered part of the map. */
    public boolean visible(float sx, float sy, float margin) {
        return sx >= x + insetLeft + margin && sx <= x + width - insetRight - margin
            && sy >= y + insetBottom + margin && sy <= y + height - insetTop - margin;
    }
    public void zoomAt(float factor, float sx, float sy) {
        stop();
        Vector2 before = world(sx, sy);
        zoom = MathUtils.clamp(zoom * factor, 1, MAX_ZOOM);
        Vector2 after = world(sx, sy);
        centerX += before.x - after.x; centerY += before.y - after.y; clamp();
    }
    public void pan(float dx, float dy) { stop(); centerX -= dx / scale(); centerY -= dy / scale(); clamp(); }
    /** Continues a pan with the finger's release velocity (screen units per second). */
    public void fling(float vx, float vy) { targetX = Float.NaN; flingX = -vx / scale(); flingY = -vy / scale(); }
    public void stop() { targetX = Float.NaN; flingX = flingY = 0; }
    /** Animates to a world point at the given zoom. */
    public void flyTo(float wx, float wy, float z) {
        flingX = flingY = 0; targetX = wx; targetY = wy; targetZoom = MathUtils.clamp(z, 1, MAX_ZOOM);
    }
    /** Animates just enough to bring a world point into the safe area, keeping the zoom. */
    public void reveal(float wx, float wy, float margin) {
        if (visible(screenX(wx), screenY(wy), margin)) return;
        flyTo(wx, wy, zoom);
    }
    /** Animates to show a world-space box with some padding. */
    public void frame(float minX, float minY, float maxX, float maxY) {
        float w = Math.max(80, maxX - minX) * 1.5f, h = Math.max(60, maxY - minY) * 1.5f;
        float z = Math.min(safeW() / (w * fitScale()), safeH() / (h * fitScale()));
        flyTo((minX + maxX) / 2, (minY + maxY) / 2, Math.min(z, 4));
    }
    public void focus(Board.Region r) { flyTo(r.anchor.x, r.anchor.y, Math.max(zoom, 3)); }
    public void reset() { stop(); centerX = WORLD_W / 2; centerY = WORLD_H / 2; zoom = 1; }
    public void animateReset() { flyTo(WORLD_W / 2, WORLD_H / 2, 1); }
    public boolean moving() { return !Float.isNaN(targetX) || Math.abs(flingX) + Math.abs(flingY) > 1; }
    /** Advances animation and fling; returns true while still moving. */
    public boolean update(float dt) {
        if (!Float.isNaN(targetX)) {
            float k = 1 - (float) Math.exp(-dt * 9), bx = centerX, by = centerY, bz = zoom;
            centerX += (targetX - centerX) * k; centerY += (targetY - centerY) * k;
            zoom += (targetZoom - zoom) * k;
            if (Math.abs(targetX - centerX) * scale() < .5f && Math.abs(targetY - centerY) * scale() < .5f && Math.abs(targetZoom - zoom) < .005f) {
                centerX = targetX; centerY = targetY; zoom = targetZoom; targetX = Float.NaN;
            }
            clamp();
            // A target beyond the map's edge is reached when the clamped camera stops moving.
            if (Math.abs(centerX - bx) * scale() < .05f && Math.abs(centerY - by) * scale() < .05f && Math.abs(zoom - bz) < .001f) { zoom = targetZoom; targetX = Float.NaN; clamp(); }
            return true;
        }
        if (Math.abs(flingX) + Math.abs(flingY) > 1) {
            centerX += flingX * dt; centerY += flingY * dt;
            float decay = (float) Math.exp(-dt * 4.5f);
            flingX *= decay; flingY *= decay;
            float beforeX = centerX, beforeY = centerY;
            clamp();
            if (beforeX != centerX) flingX = 0;
            if (beforeY != centerY) flingY = 0;
            return true;
        }
        flingX = flingY = 0;
        return false;
    }
    private void clamp() {
        float halfX = safeW() / scale() / 2, halfY = safeH() / scale() / 2;
        centerX = halfX >= WORLD_W / 2 ? WORLD_W / 2 : MathUtils.clamp(centerX, halfX, WORLD_W - halfX);
        centerY = halfY >= WORLD_H / 2 ? WORLD_H / 2 : MathUtils.clamp(centerY, halfY, WORLD_H - halfY);
    }
}
