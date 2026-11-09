//! Geography and bidirectional adjacency, independent of game rules.

use serde::Deserialize;
use std::collections::{HashMap, HashSet, VecDeque};
use std::fmt;

/// The board bundled with the game.
pub const BUNDLED_BOARD: &str = include_str!("../assets/board.json");

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    Land,
    Sea,
}

/// A point in board space: five units per degree, zero at (-180, -60), y pointing north.
#[derive(Clone, Copy, Debug, PartialEq, Default)]
pub struct Point {
    pub x: f32,
    pub y: f32,
}

impl Point {
    pub const fn new(x: f32, y: f32) -> Self {
        Point { x, y }
    }
    pub fn dst(self, o: Point) -> f32 {
        ((self.x - o.x).powi(2) + (self.y - o.y).powi(2)).sqrt()
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Continent {
    pub name: String,
    /// Hex RGB colour.
    pub color: String,
}

#[derive(Clone, Debug)]
pub struct Region {
    pub id: String,
    pub name: String,
    pub label: String,
    pub includes: String,
    /// Index into [`Board::continents`].
    pub continent: usize,
    pub anchor: Point,
    /// Flat `[x0, y0, x1, y1, ...]` rings.
    pub polygons: Vec<Vec<f32>>,
    /// Triangle vertex indices for each ring.
    pub triangles: Vec<Vec<u16>>,
}

impl Region {
    pub fn contains(&self, x: f32, y: f32) -> bool {
        self.polygons
            .iter()
            .any(|ring| point_in_polygon(ring, x, y))
    }
}

#[derive(Clone, Debug)]
pub struct Connection {
    pub a: usize,
    pub b: usize,
    pub kind: Kind,
    pub via: Vec<Point>,
}

impl Connection {
    pub fn other(&self, region: usize) -> usize {
        if self.a == region {
            self.b
        } else if self.b == region {
            self.a
        } else {
            panic!("Region is not an endpoint")
        }
    }
    pub fn touches(&self, region: usize) -> bool {
        self.a == region || self.b == region
    }
}

#[derive(Debug, Clone)]
pub struct BoardError(pub String);

impl fmt::Display for BoardError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for BoardError {}

#[derive(Debug)]
pub struct Board {
    pub width: f32,
    pub height: f32,
    pub continents: Vec<Continent>,
    pub regions: Vec<Region>,
    pub connections: Vec<Connection>,
    by_name: HashMap<String, usize>,
    adjacency: Vec<Vec<usize>>,
}

#[derive(Deserialize)]
struct RawBoard {
    width: f32,
    height: f32,
    continents: Vec<RawContinent>,
    territories: Vec<RawRegion>,
    connections: Vec<RawConnection>,
}

#[derive(Deserialize)]
struct RawContinent {
    name: String,
    color: String,
}

#[derive(Deserialize)]
struct RawRegion {
    id: String,
    name: String,
    label: String,
    includes: String,
    continent: String,
    anchor: [f32; 2],
    polygons: Vec<Vec<f32>>,
}

#[derive(Deserialize)]
struct RawConnection {
    a: String,
    b: String,
    kind: String,
    via: Vec<[f32; 2]>,
}

fn fail<T>(message: impl Into<String>) -> Result<T, BoardError> {
    Err(BoardError(message.into()))
}

impl Board {
    /// The 72-territory board shipped with the game.
    pub fn bundled() -> Board {
        Board::parse(BUNDLED_BOARD).expect("bundled board is valid")
    }

    pub fn parse(json: &str) -> Result<Board, BoardError> {
        let raw: RawBoard =
            serde_json::from_str(json).map_err(|e| BoardError(format!("Invalid board: {e}")))?;
        let mut continents: Vec<Continent> = Vec::new();
        for c in raw.continents {
            if continents.iter().any(|x| x.name == c.name) {
                return fail("Duplicate continent");
            }
            continents.push(Continent {
                name: c.name,
                color: c.color,
            });
        }
        let mut regions = Vec::new();
        let mut by_name = HashMap::new();
        let mut ids = HashSet::new();
        for t in raw.territories {
            let Some(continent) = continents.iter().position(|c| c.name == t.continent) else {
                return fail(format!("Missing continent for {}", t.name));
            };
            let mut polygons = Vec::new();
            let mut triangles = Vec::new();
            for ring in t.polygons {
                if ring.len() < 6 || ring.len() % 2 != 0 {
                    return fail(format!("Invalid polygon: {}", t.name));
                }
                if ring.iter().any(|v| !v.is_finite()) {
                    return fail(format!("Nonfinite geometry: {}", t.name));
                }
                triangles.push(triangulate(&ring));
                polygons.push(ring);
            }
            if polygons.is_empty() {
                return fail(format!("Empty geometry: {}", t.name));
            }
            if !ids.insert(t.id.clone()) || by_name.contains_key(&t.name) {
                return fail(format!("Duplicate region: {}", t.name));
            }
            by_name.insert(t.name.clone(), regions.len());
            regions.push(Region {
                id: t.id,
                name: t.name,
                label: t.label,
                includes: t.includes,
                continent,
                anchor: Point::new(t.anchor[0], t.anchor[1]),
                polygons,
                triangles,
            });
        }
        if regions.is_empty() {
            return fail("Empty board");
        }
        let mut connections = Vec::new();
        let mut adjacency = vec![Vec::new(); regions.len()];
        let mut pairs = HashSet::new();
        for e in raw.connections {
            let lookup = |name: &str| {
                by_name
                    .get(name)
                    .copied()
                    .ok_or_else(|| BoardError(format!("Unknown region: {name}")))
            };
            let (a, b) = (lookup(&e.a)?, lookup(&e.b)?);
            if a == b {
                return fail(format!("Self link: {}", regions[a].name));
            }
            let (na, nb) = (&regions[a].name, &regions[b].name);
            let pair = if na < nb {
                format!("{na}|{nb}")
            } else {
                format!("{nb}|{na}")
            };
            if !pairs.insert(pair.clone()) {
                return fail(format!("Duplicate connection: {pair}"));
            }
            let kind = match e.kind.as_str() {
                "LAND" => Kind::Land,
                "SEA" => Kind::Sea,
                other => return fail(format!("Unknown connection kind: {other}")),
            };
            let via = e.via.iter().map(|p| Point::new(p[0], p[1])).collect();
            adjacency[a].push(connections.len());
            adjacency[b].push(connections.len());
            connections.push(Connection { a, b, kind, via });
        }
        let board = Board {
            width: raw.width,
            height: raw.height,
            continents,
            regions,
            connections,
            by_name,
            adjacency,
        };
        let mut reached = vec![false; board.regions.len()];
        let mut queue = VecDeque::from([0usize]);
        while let Some(r) = queue.pop_front() {
            if !reached[r] {
                reached[r] = true;
                for e in board.links(r) {
                    queue.push_back(e.other(r));
                }
            }
        }
        if reached.iter().any(|r| !r) {
            let missing: Vec<&str> = (0..board.regions.len())
                .filter(|&i| !reached[i])
                .map(|i| board.regions[i].name.as_str())
                .collect();
            return fail(format!("Disconnected board: {missing:?}"));
        }
        Ok(board)
    }

    /// Index of a region by name, if it exists.
    pub fn find(&self, name: &str) -> Option<usize> {
        self.by_name.get(name).copied()
    }

    /// Index of a region by name. Panics for unknown names.
    pub fn index(&self, name: &str) -> usize {
        self.find(name)
            .unwrap_or_else(|| panic!("Unknown region: {name}"))
    }

    /// A region by name. Panics for unknown names.
    pub fn region(&self, name: &str) -> &Region {
        &self.regions[self.index(name)]
    }

    pub fn continent_of(&self, region: usize) -> &Continent {
        &self.continents[self.regions[region].continent]
    }

    /// Connections touching a region, in board order.
    pub fn links(&self, region: usize) -> impl Iterator<Item = &Connection> + '_ {
        self.adjacency[region]
            .iter()
            .map(move |&i| &self.connections[i])
    }

    pub fn sea_count(&self) -> usize {
        self.connections
            .iter()
            .filter(|e| e.kind == Kind::Sea)
            .count()
    }
}

/// Even-odd point-in-polygon test for a flat `[x, y, ...]` ring.
pub fn point_in_polygon(polygon: &[f32], x: f32, y: f32) -> bool {
    let mut odd = false;
    let (sx, sy) = (polygon[0], polygon[1]);
    let mut y1 = sy;
    let mut yi = 3;
    while yi < polygon.len() {
        let y2 = polygon[yi];
        if (y2 < y && y1 >= y) || (y1 < y && y2 >= y) {
            let x2 = polygon[yi - 1];
            if x2 + (y - y2) / (y1 - y2) * (polygon[yi - 3] - x2) < x {
                odd = !odd;
            }
        }
        y1 = y2;
        yi += 2;
    }
    if ((sy < y && y1 >= y) || (y1 < y && sy >= y))
        && sx + (y - sy) / (y1 - sy) * (polygon[yi - 3] - sx) < x
    {
        odd = !odd;
    }
    odd
}

const CONCAVE: i32 = -1;
const CONVEX: i32 = 1;

fn area_sign(p1x: f32, p1y: f32, p2x: f32, p2y: f32, p3x: f32, p3y: f32) -> i32 {
    let mut area = p1x * (p3y - p2y);
    area += p2x * (p1y - p3y);
    area += p3x * (p2y - p1y);
    if area > 0.0 {
        1
    } else if area < 0.0 {
        -1
    } else {
        0
    }
}

/// Ear-clipping triangulation of a simple polygon. Always yields `n - 2` triangles, also for
/// degenerate (nearly collinear) input.
pub fn triangulate(vertices: &[f32]) -> Vec<u16> {
    let count = vertices.len() / 2;
    let clockwise = {
        let mut area = 0.0f32;
        let mut i = 0;
        while i + 3 < vertices.len() {
            area += vertices[i] * vertices[i + 3] - vertices[i + 2] * vertices[i + 1];
            i += 2;
        }
        let n = vertices.len();
        count > 1 && area + vertices[n - 2] * vertices[1] - vertices[0] * vertices[n - 1] < 0.0
    };
    let mut indices: Vec<u16> = if clockwise {
        (0..count as u16).collect()
    } else {
        (0..count as u16).rev().collect()
    };
    let mut triangles = Vec::with_capacity(count.saturating_sub(2) * 3);
    let classify = |indices: &[u16], i: usize| -> i32 {
        let n = indices.len();
        let p = indices[if i == 0 { n } else { i } - 1] as usize * 2;
        let c = indices[i] as usize * 2;
        let x = indices[(i + 1) % n] as usize * 2;
        area_sign(
            vertices[p],
            vertices[p + 1],
            vertices[c],
            vertices[c + 1],
            vertices[x],
            vertices[x + 1],
        )
    };
    let mut types: Vec<i32> = (0..count).map(|i| classify(&indices, i)).collect();
    while indices.len() > 3 {
        let n = indices.len();
        let prev = |i: usize| if i == 0 { n } else { i } - 1;
        let next = |i: usize| (i + 1) % n;
        let is_ear = |tip: usize| -> bool {
            if types[tip] == CONCAVE {
                return false;
            }
            let (pi, ni) = (prev(tip), next(tip));
            let (p1, p2, p3) = (
                indices[pi] as usize * 2,
                indices[tip] as usize * 2,
                indices[ni] as usize * 2,
            );
            let (p1x, p1y) = (vertices[p1], vertices[p1 + 1]);
            let (p2x, p2y) = (vertices[p2], vertices[p2 + 1]);
            let (p3x, p3y) = (vertices[p3], vertices[p3 + 1]);
            let mut i = next(ni);
            while i != pi {
                if types[i] != CONVEX {
                    let v = indices[i] as usize * 2;
                    let (vx, vy) = (vertices[v], vertices[v + 1]);
                    if area_sign(p3x, p3y, p1x, p1y, vx, vy) >= 0
                        && area_sign(p1x, p1y, p2x, p2y, vx, vy) >= 0
                        && area_sign(p2x, p2y, p3x, p3y, vx, vy) >= 0
                    {
                        return false;
                    }
                }
                i = next(i);
            }
            true
        };
        let tip = (0..n)
            .find(|&i| is_ear(i))
            // Degenerate polygon: clip a convex or tangential vertex, else the first one.
            .or_else(|| (0..n).find(|&i| types[i] != CONCAVE))
            .unwrap_or(0);
        triangles.extend([indices[prev(tip)], indices[tip], indices[next(tip)]]);
        indices.remove(tip);
        types.remove(tip);
        let n = indices.len();
        let previous = if tip == 0 { n } else { tip } - 1;
        let following = if tip == n { 0 } else { tip };
        types[previous] = classify(&indices, previous);
        types[following] = classify(&indices, following);
    }
    if indices.len() == 3 {
        triangles.extend_from_slice(&indices);
    }
    triangles
}
