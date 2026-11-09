package com.supremacy.board;

import com.badlogic.gdx.math.EarClippingTriangulator;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import java.io.Reader;
import java.util.*;

/** Geography and bidirectional adjacency, independent of game rules. */
public final class Board {
    public enum Kind { LAND, SEA }
    public record Continent(String name, String color) {}
    public static final class Region {
        public final String id, name, label, includes;
        public final Continent continent;
        public final Vector2 anchor;
        public final List<float[]> polygons;
        public final List<short[]> triangles;
        Region(JsonValue data, Continent continent) {
            id = data.getString("id"); name = data.getString("name");
            label = data.getString("label"); includes = data.getString("includes");
            this.continent = Objects.requireNonNull(continent, "Missing continent for " + name);
            float[] a = data.get("anchor").asFloatArray();
            anchor = new Vector2(a[0], a[1]);
            List<float[]> rings = new ArrayList<>(); List<short[]> indices = new ArrayList<>();
            var triangulator = new EarClippingTriangulator();
            for (JsonValue polygon : data.get("polygons")) {
                float[] vertices = polygon.asFloatArray();
                if (vertices.length < 6 || vertices.length % 2 != 0) throw new IllegalArgumentException("Invalid polygon: " + name);
                for (float v : vertices) if (!Float.isFinite(v)) throw new IllegalArgumentException("Nonfinite geometry: " + name);
                rings.add(vertices); indices.add(triangulator.computeTriangles(vertices).toArray());
            }
            if (rings.isEmpty()) throw new IllegalArgumentException("Empty geometry: " + name);
            polygons = List.copyOf(rings); triangles = List.copyOf(indices);
        }
        public boolean contains(float x, float y) {
            for (float[] ring : polygons) if (Intersector.isPointInPolygon(ring, 0, ring.length, x, y)) return true;
            return false;
        }
    }
    public record Connection(Region a, Region b, Kind kind, List<Vector2> via) {
        public Region other(Region region) {
            if (a == region) return b;
            if (b == region) return a;
            throw new IllegalArgumentException("Region is not an endpoint");
        }
        public boolean touches(Region region) { return a == region || b == region; }
    }
    public final float width, height;
    public final List<Continent> continents;
    public final List<Region> regions;
    public final List<Connection> connections;
    private final Map<String, Region> byName;
    private final Map<Region, List<Connection>> adjacency;

    public Board(Reader reader) {
        JsonValue data = new JsonReader().parse(reader);
        width = data.getFloat("width"); height = data.getFloat("height");
        Map<String, Continent> cs = new LinkedHashMap<>();
        for (JsonValue c : data.get("continents")) {
            var continent = new Continent(c.getString("name"), c.getString("color"));
            if (cs.put(continent.name(), continent) != null) throw new IllegalArgumentException("Duplicate continent");
        }
        continents = List.copyOf(cs.values()); byName = new LinkedHashMap<>();
        Set<String> ids = new HashSet<>();
        for (JsonValue t : data.get("territories")) {
            Region r = new Region(t, cs.get(t.getString("continent")));
            if (!ids.add(r.id) || byName.put(r.name, r) != null) throw new IllegalArgumentException("Duplicate region: " + r.name);
        }
        regions = List.copyOf(byName.values());
        List<Connection> edges = new ArrayList<>(); Set<String> pairs = new HashSet<>();
        adjacency = new HashMap<>();
        for (Region r : regions) adjacency.put(r, new ArrayList<>());
        for (JsonValue e : data.get("connections")) {
            Region a = region(e.getString("a")), b = region(e.getString("b"));
            if (a == b) throw new IllegalArgumentException("Self link: " + a.name);
            String pair = a.name.compareTo(b.name) < 0 ? a.name + "|" + b.name : b.name + "|" + a.name;
            if (!pairs.add(pair)) throw new IllegalArgumentException("Duplicate connection: " + pair);
            List<Vector2> via = new ArrayList<>();
            for (JsonValue v : e.get("via")) { float[] p = v.asFloatArray(); via.add(new Vector2(p[0], p[1])); }
            Connection edge = new Connection(a, b, Kind.valueOf(e.getString("kind")), List.copyOf(via));
            edges.add(edge); adjacency.get(a).add(edge); adjacency.get(b).add(edge);
        }
        connections = List.copyOf(edges);
        adjacency.replaceAll((r, links) -> List.copyOf(links));
        Set<Region> reached = new HashSet<>(); ArrayDeque<Region> queue = new ArrayDeque<>();
        if (regions.isEmpty()) throw new IllegalArgumentException("Empty board");
        queue.add(regions.get(0));
        while (!queue.isEmpty()) { Region r = queue.remove(); if (reached.add(r)) for (Connection e : links(r)) queue.add(e.other(r)); }
        if (reached.size() != regions.size()) throw new IllegalArgumentException("Disconnected board: " + regions.stream().filter(r -> !reached.contains(r)).map(r -> r.name).toList());
    }
    public Region region(String name) { return Objects.requireNonNull(byName.get(name), "Unknown region: " + name); }
    public List<Connection> links(Region region) { return adjacency.get(region); }
    public long seaCount() { return connections.stream().filter(e -> e.kind() == Kind.SEA).count(); }
}
