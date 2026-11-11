package com.supremacy.board;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Disposable;

/**
 * Territory fills and outlines baked once into GPU meshes in world coordinates. The camera
 * transform is a shader uniform, so panning and zooming upload nothing. Outline vertices carry
 * a miter normal and are pushed out in the shader, keeping borders a constant on-screen width.
 */
final class MapMesh implements Disposable {
    private static final String VERTEX = """
        attribute vec2 a_position;
        attribute vec2 a_normal;
        uniform mat4 u_projTrans;
        uniform vec4 u_xf;
        void main() {
            vec2 p = a_position + a_normal * u_xf.w;
            gl_Position = u_projTrans * vec4(p * u_xf.x + u_xf.yz, 0.0, 1.0);
        }
        """;
    private static final String FRAGMENT = """
        #ifdef GL_ES
        precision mediump float;
        #endif
        uniform vec4 u_color;
        void main() { gl_FragColor = u_color; }
        """;
    private final Mesh fills, outlines;
    private final int[] fillOffset, fillCount, lineOffset, lineCount;
    private final ShaderProgram shader;
    private AtlasCamera camera;

    MapMesh(Board board) {
        int n = board.regions.size();
        fillOffset = new int[n]; fillCount = new int[n]; lineOffset = new int[n]; lineCount = new int[n];
        int fillVerts = 0, fillIdx = 0, lineVerts = 0, lineIdx = 0;
        for (Board.Region r : board.regions)
            for (int k = 0; k < r.polygons.size(); k++) {
                int points = r.polygons.get(k).length / 2;
                fillVerts += points; fillIdx += r.triangles.get(k).length;
                lineVerts += points * 2; lineIdx += points * 6;
            }
        float[] fv = new float[fillVerts * 4], lv = new float[lineVerts * 4];
        short[] fi = new short[fillIdx], li = new short[lineIdx];
        int fvN = 0, fiN = 0, lvN = 0, liN = 0;
        for (int i = 0; i < n; i++) {
            Board.Region r = board.regions.get(i);
            fillOffset[i] = fiN; lineOffset[i] = liN;
            for (int k = 0; k < r.polygons.size(); k++) {
                float[] p = r.polygons.get(k); short[] tris = r.triangles.get(k);
                int base = fvN, points = p.length / 2;
                for (int v = 0; v < points; v++) { fv[fvN * 4] = p[v * 2]; fv[fvN * 4 + 1] = p[v * 2 + 1]; fvN++; }
                for (short t : tris) fi[fiN++] = (short) (base + t);
                int lineBase = lvN;
                for (int v = 0; v < points; v++) {
                    int prev = (v + points - 1) % points, next = (v + 1) % points;
                    float[] miter = miter(p, prev, v, next);
                    for (int side = -1; side <= 1; side += 2) {
                        lv[lvN * 4] = p[v * 2]; lv[lvN * 4 + 1] = p[v * 2 + 1];
                        lv[lvN * 4 + 2] = miter[0] * side; lv[lvN * 4 + 3] = miter[1] * side; lvN++;
                    }
                }
                for (int v = 0; v < points; v++) {
                    int a = lineBase + v * 2, b = lineBase + ((v + 1) % points) * 2;
                    li[liN++] = (short) a; li[liN++] = (short) (a + 1); li[liN++] = (short) b;
                    li[liN++] = (short) (a + 1); li[liN++] = (short) (b + 1); li[liN++] = (short) b;
                }
            }
            fillCount[i] = fiN - fillOffset[i]; lineCount[i] = liN - lineOffset[i];
        }
        VertexAttribute[] attributes = {new VertexAttribute(VertexAttributes.Usage.Position, 2, "a_position"),
            new VertexAttribute(VertexAttributes.Usage.Normal, 2, "a_normal")};
        fills = new Mesh(true, fillVerts, fillIdx, attributes); fills.setVertices(fv); fills.setIndices(fi);
        outlines = new Mesh(true, lineVerts, lineIdx, attributes); outlines.setVertices(lv); outlines.setIndices(li);
        ShaderProgram.pedantic = false;
        shader = new ShaderProgram(VERTEX, FRAGMENT);
        if (!shader.isCompiled()) throw new IllegalStateException("Map shader: " + shader.getLog());
    }

    /** Unit normal of the corner at {@code v}, lengthened so both adjoining edges keep their width. */
    private static float[] miter(float[] p, int prev, int v, int next) {
        float[] n1 = normal(p, prev, v), n2 = normal(p, v, next);
        float mx = n1[0] + n2[0], my = n1[1] + n2[1], len = (float) Math.sqrt(mx * mx + my * my);
        if (len < 1e-4f) return n1;
        mx /= len; my /= len;
        float dot = mx * n1[0] + my * n1[1];
        float scale = Math.min(2.5f, 1 / Math.max(.4f, dot));
        return new float[] {mx * scale, my * scale};
    }
    private static float[] normal(float[] p, int a, int b) {
        float dx = p[b * 2] - p[a * 2], dy = p[b * 2 + 1] - p[a * 2 + 1], len = (float) Math.sqrt(dx * dx + dy * dy);
        return len < 1e-6f ? new float[] {0, 0} : new float[] {-dy / len, dx / len};
    }

    void begin(Matrix4 projection, AtlasCamera camera) {
        this.camera = camera;
        Gdx.gl.glEnable(GL20.GL_BLEND); Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shader.bind();
        shader.setUniformMatrix("u_projTrans", projection);
    }
    private void transform(float dx, float dy, float halfWidth) {
        float s = camera.scale();
        shader.setUniformf("u_xf", s, camera.screenX(0) + dx, camera.screenY(0) + dy, halfWidth / s);
    }
    void fill(int region, Color c, float dx, float dy) {
        if (fillCount[region] == 0) return;
        transform(dx, dy, 0);
        shader.setUniformf("u_color", c);
        fills.render(shader, GL20.GL_TRIANGLES, fillOffset[region], fillCount[region]);
    }
    /** Draws a region border {@code width} UI units wide, centred on the coastline. */
    void outline(int region, Color c, float width) {
        transform(0, 0, width / 2);
        shader.setUniformf("u_color", c);
        outlines.render(shader, GL20.GL_TRIANGLES, lineOffset[region], lineCount[region]);
    }
    @Override public void dispose() { fills.dispose(); outlines.dispose(); shader.dispose(); }
}
