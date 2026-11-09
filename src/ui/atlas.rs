//! An original nautical atlas, drawn from the board's geographic polygons.

use super::camera::AtlasCamera;
use super::gfx::{alpha, hex, lerp, mul, Area, COUNTER, SMALL};
use super::hud::Role;
use super::{App, GOLD, GRID, INK, MUTED, OCEAN, PAPER};
use crate::board::{Board, Connection, Kind, Point};
use macroquad::prelude::*;

const OVERVIEW_LABELS: [(&str, &str); 15] = [
    ("British Columbia", "B.C."),
    ("Alberta", "AB"),
    ("Saskatchewan", "SK"),
    ("Manitoba", "MB"),
    ("Ontario", "ON"),
    ("Quebec", "QC"),
    ("Northwest Territories", "N.W.T."),
    ("Newfoundland and Labrador", "N.L."),
    ("Western Australia", "W.A."),
    ("Northern Territory", "N.T."),
    ("South Australia", "S.A."),
    ("Queensland", "QLD"),
    ("New South Wales", "N.S.W."),
    ("Victoria", "VIC"),
    ("Tasmania", "TAS"),
];

/// `java.util.Random`, so the hand-drawn wave marks land where they always have.
struct JavaRandom(u64);

impl JavaRandom {
    fn new(seed: u64) -> Self {
        JavaRandom((seed ^ 0x5DEECE66D) & ((1 << 48) - 1))
    }
    fn next_float(&mut self) -> f32 {
        self.0 = (self.0.wrapping_mul(0x5DEECE66D).wrapping_add(0xB)) & ((1 << 48) - 1);
        (self.0 >> (48 - 24)) as f32 / (1u32 << 24) as f32
    }
}

fn wrap_x(x: f32) -> f32 {
    (x % 1800.0 + 1800.0) % 1800.0
}

/// A Catmull-Rom sea lane through its waypoints, unwrapped across the Pacific.
pub fn curve(board: &Board, edge: &Connection) -> Vec<Point> {
    let mut controls = vec![board.regions[edge.a].anchor];
    controls.extend(edge.via.iter().copied());
    controls.push(board.regions[edge.b].anchor);
    for i in 1..controls.len() {
        let a = controls[i - 1];
        let b = &mut controls[i];
        while b.x - a.x > 900.0 {
            b.x -= 1800.0;
        }
        while b.x - a.x < -900.0 {
            b.x += 1800.0;
        }
    }
    let mut result = Vec::new();
    let last = controls.len() - 1;
    for i in 0..last {
        let (p0, p1, p2, p3) = (
            controls[i.saturating_sub(1)],
            controls[i],
            controls[i + 1],
            controls[(i + 2).min(last)],
        );
        let steps = 8.max((p1.dst(p2) / 3.0) as i32);
        for j in 0..steps {
            let t = j as f32 / steps as f32;
            let (t2, t3) = (t * t, t * t * t);
            let f = |a: f32, b: f32, c: f32, d: f32| {
                0.5 * ((2.0 * b)
                    + (-a + c) * t
                    + (2.0 * a - 5.0 * b + 4.0 * c - d) * t2
                    + (-a + 3.0 * b - 3.0 * c + d) * t3)
            };
            result.push(Point::new(
                f(p0.x, p1.x, p2.x, p3.x),
                f(p0.y, p1.y, p2.y, p3.y),
            ));
        }
    }
    result.push(controls[last]);
    result
}

fn army_text(n: i64) -> String {
    if n >= 100_000 {
        format!("{}k", n / 1000)
    } else {
        n.to_string()
    }
}

impl App {
    fn cx(&self, x: f32) -> f32 {
        self.camera.screen_x(x)
    }
    fn cy(&self, y: f32) -> f32 {
        self.camera.screen_y(y)
    }

    /// Token radius grows gently with zoom so pieces stay legible without hiding the map.
    pub fn token_radius(&self) -> f32 {
        23f32.min(13.0 + 3.2 * (self.camera.zoom - 1.0))
    }

    pub fn draw_map(&self) {
        let g = &self.gfx;
        let cam = &self.camera;
        g.clip(Some(Area::new(
            AtlasCamera::X,
            AtlasCamera::Y,
            AtlasCamera::WIDTH,
            AtlasCamera::HEIGHT,
        )));
        g.rect(
            AtlasCamera::X,
            AtlasCamera::Y,
            AtlasCamera::WIDTH,
            AtlasCamera::HEIGHT,
            OCEAN,
        );
        // Subtle atlas graticule and hand-drawn wave marks stay attached to the board.
        for x in (0..=1800).step_by(150) {
            let x = x as f32;
            g.line(
                self.cx(x),
                self.cy(0.0),
                self.cx(x),
                self.cy(750.0),
                0.65,
                GRID,
            );
        }
        for y in (0..=750).step_by(100) {
            let y = y as f32;
            g.line(
                self.cx(0.0),
                self.cy(y),
                self.cx(1800.0),
                self.cy(y),
                0.65,
                GRID,
            );
        }
        let mut rng = JavaRandom::new(2904);
        let wave = alpha(MUTED, 0.13);
        for _ in 0..360 {
            let x = self.cx(rng.next_float() * 1800.0);
            let y = self.cy(rng.next_float() * 750.0);
            let s = cam.scale();
            g.line(x, y, x + 3.0 * s, y - s, 0.65, wave);
            g.line(x + 3.0 * s, y - s, x + 6.0 * s, y, 0.65, wave);
        }
        if self.show_routes {
            for (i, e) in self.board.connections.iter().enumerate() {
                if e.kind == Kind::Sea {
                    self.draw_lane(i, false);
                }
            }
        }
        // Shadow gives coastlines depth while keeping the camera strictly overhead.
        for r in 0..self.board.regions.len() {
            self.fill(r, alpha(BLACK, 0.3), 2.0, -3.0);
        }
        for r in 0..self.board.regions.len() {
            let mut c = self.colors[r];
            match self.role(r) {
                Role::Dim => c = lerp(c, OCEAN, self.dim_strength()),
                Role::Source => c = lerp(c, PAPER, 0.3),
                Role::Target => c = lerp(c, PAPER, 0.2),
                _ if Some(r) == self.hovered => c = lerp(c, PAPER, 0.16),
                _ => {}
            }
            self.fill(r, c, 0.0, 0.0);
        }
        // Outlines are drawn after every fill so a highlighted border is never covered by a neighbour.
        for r in 0..self.board.regions.len() {
            if !matches!(self.role(r), Role::Source | Role::Target) {
                self.outline(r, hex("263D45"), 1.0);
            }
        }
        for r in 0..self.board.regions.len() {
            match self.role(r) {
                Role::Source => self.outline(r, PAPER, 2.5),
                Role::Target => self.outline(r, self.action_color(), 2.5),
                _ => {}
            }
        }
        // Highlighted lanes are drawn over land to identify their exact endpoints.
        if let (true, Some(sel)) = (self.show_routes, self.selected) {
            for (i, e) in self.board.connections.iter().enumerate() {
                if e.kind == Kind::Sea && e.touches(sel) {
                    self.draw_lane(i, true);
                }
            }
        }
        if self.game.is_none() {
            for r in &self.board.regions {
                if r.name == "Prince Edward Island" || r.name == "Hawaii" {
                    g.ring(self.cx(r.anchor.x), self.cy(r.anchor.y), 4.0, 1.0, GOLD);
                }
            }
        }
        self.compass(self.cx(230.0), self.cy(150.0), 42.0);
        self.ship(self.cx(555.0), self.cy(425.0), 0.9);
        self.ship(self.cx(1240.0), self.cy(175.0), 1.0);
        self.ocean_label("P A C I F I C   O C E A N", 190.0, 350.0);
        self.ocean_label("A T L A N T I C", 700.0, 440.0);
        self.ocean_label("O C E A N", 700.0, 407.0);
        self.ocean_label("I N D I A N   O C E A N", 1300.0, 165.0);
        self.ocean_label("S O U T H   P A C I F I C", 420.0, 120.0);
        self.ocean_label("A R C T I C   O C E A N", 900.0, 735.0);
        if self.show_labels {
            self.draw_labels();
        }
        self.draw_counters();
        if let (true, Some(sel)) = (self.show_routes, self.selected) {
            let mut i = 0;
            for (k, e) in self.board.connections.iter().enumerate() {
                if e.kind == Kind::Sea && e.touches(sel) {
                    i += 1;
                    let p = &self.lanes[&k];
                    let mid = p[p.len() / 2];
                    g.centered(
                        SMALL,
                        &format!("{i:02}"),
                        self.cx(wrap_x(mid.x)),
                        self.cy(mid.y) + 6.0,
                        GOLD,
                    );
                }
            }
        }
        g.clip(None);
    }

    fn draw_counters(&self) {
        let Some(game) = &self.game else { return };
        let g = &self.gfx;
        let state = &game.state;
        let r = self.token_radius();
        let pulse = 0.5 + 0.5 * (self.clock * 5.5).sin();
        let counter = COUNTER.scaled(r / 13.0);
        if let (Ok(a), Ok(b)) = (
            usize::try_from(self.arrow_from()),
            usize::try_from(self.arrow_to()),
        ) {
            self.arrow(a, b, r, self.action_color());
        }
        let positions: Vec<(usize, f32, f32)> = (0..self.board.regions.len())
            .map(|i| {
                let anchor = self.board.regions[i].anchor;
                (i, self.cx(anchor.x), self.cy(anchor.y))
            })
            .filter(|&(_, x, y)| self.camera.inside(x, y))
            .collect();
        for &(i, x, y) in &positions {
            let owner = hex(state.color(state.owners[i]));
            let w = (2.0 * r).max(g.width(counter, &army_text(state.armies[i])) + r * 0.9);
            let role = self.role(i);
            let action = self.action_color();
            match role {
                Role::Source => {
                    g.round(
                        x - w / 2.0 - 7.0 - 2.0 * pulse,
                        y - r - 7.0 - 2.0 * pulse,
                        w + 14.0 + 4.0 * pulse,
                        2.0 * r + 14.0 + 4.0 * pulse,
                        r + 7.0 + 2.0 * pulse,
                        alpha(PAPER, 0.25),
                    );
                    g.round(
                        x - w / 2.0 - 4.0,
                        y - r - 4.0,
                        w + 8.0,
                        2.0 * r + 8.0,
                        r + 4.0,
                        PAPER,
                    );
                }
                Role::Target => g.round(
                    x - w / 2.0 - 4.0,
                    y - r - 4.0,
                    w + 8.0,
                    2.0 * r + 8.0,
                    r + 4.0,
                    action,
                ),
                Role::Option => g.round(
                    x - w / 2.0 - 4.0 - 3.0 * pulse,
                    y - r - 4.0 - 3.0 * pulse,
                    w + 8.0 + 6.0 * pulse,
                    2.0 * r + 8.0 + 6.0 * pulse,
                    r + 4.0 + 3.0 * pulse,
                    alpha(action, 0.45 + 0.45 * pulse),
                ),
                Role::Hint => g.round(
                    x - w / 2.0 - 3.0,
                    y - r - 3.0,
                    w + 6.0,
                    2.0 * r + 6.0,
                    r + 3.0,
                    alpha(GOLD, 0.7),
                ),
                _ => {}
            }
            // A raised game piece: drop shadow, dark rim, bevel in the owner's colour and a lighter face.
            g.round(
                x - w / 2.0 + 1.0,
                y - r - 3.0,
                w,
                2.0 * r,
                r,
                alpha(BLACK, 0.45),
            );
            g.round(
                x - w / 2.0 - 1.5,
                y - r - 1.5,
                w + 3.0,
                2.0 * r + 3.0,
                r + 1.5,
                if role == Role::Dim {
                    alpha(INK, 0.75)
                } else {
                    INK
                },
            );
            let mut bevel = mul(owner, 0.68);
            let mut face = lerp(owner, PAPER, 0.1);
            if role == Role::Dim {
                bevel = lerp(bevel, OCEAN, self.dim_strength() * 0.9);
                face = lerp(face, OCEAN, self.dim_strength() * 0.9);
            }
            g.round(x - w / 2.0, y - r, w, 2.0 * r, r, bevel);
            g.round(
                x - w / 2.0 + r * 0.17,
                y - r + r * 0.17,
                w - r * 0.34,
                2.0 * r - r * 0.34,
                r * 0.83,
                face,
            );
        }
        for &(i, x, y) in &positions {
            let color = if self.role(i) == Role::Dim {
                alpha(PAPER, 0.6)
            } else {
                WHITE
            };
            g.centered(
                counter,
                &army_text(state.armies[i]),
                x,
                y + counter.cap_height() / 2.0,
                color,
            );
        }
        // Floating +/- numbers confirm deployments, losses and moves where they happen.
        let floater = counter.scaled(1.15);
        for f in &self.floaters {
            let anchor = self.board.regions[f.region].anchor;
            let age = self.clock - f.born;
            let (x, y) = (self.cx(anchor.x), self.cy(anchor.y) + r + 10.0 + age * 34.0);
            g.centered(
                floater,
                &f.text,
                x,
                y,
                alpha(f.color, (1.6 - age * 1.2).clamp(0.0, 1.0)),
            );
        }
    }

    fn arrow(&self, from: usize, to: usize, r: f32, c: Color) {
        let (a, b) = (
            self.board.regions[from].anchor,
            self.board.regions[to].anchor,
        );
        if (a.x - b.x).abs() > 900.0 {
            return; // Pacific wrap: the sea lane already shows the route.
        }
        let g = &self.gfx;
        let (x1, y1, x2, y2) = (self.cx(a.x), self.cy(a.y), self.cx(b.x), self.cy(b.y));
        let (dx, dy) = (x2 - x1, y2 - y1);
        let len = (dx * dx + dy * dy).sqrt();
        if len < 2.0 * r + 14.0 {
            return;
        }
        let (ux, uy) = (dx / len, dy / len);
        let (sx, sy) = (x1 + ux * (r + 4.0), y1 + uy * (r + 4.0));
        let (ex, ey) = (x2 - ux * (r + 6.0), y2 - uy * (r + 6.0));
        let head = 18f32.min(len / 3.0);
        let (bx, by) = (ex - ux * head, ey - uy * head);
        let (px, py) = (-uy * head * 0.6, ux * head * 0.6);
        let shade = alpha(INK, 0.85);
        g.line(sx, sy, bx, by, 7.5, shade);
        g.line(sx, sy, bx, by, 4.5, c);
        g.triangle(
            ex + ux * 2.5,
            ey + uy * 2.5,
            bx + px * 1.3 - ux * 2.0,
            by + py * 1.3 - uy * 2.0,
            bx - px * 1.3 - ux * 2.0,
            by - py * 1.3 - uy * 2.0,
            shade,
        );
        g.triangle(ex, ey, bx + px, by + py, bx - px, by - py, c);
    }

    fn ocean_label(&self, name: &str, x: f32, y: f32) {
        self.gfx
            .centered(SMALL, name, self.cx(x), self.cy(y), alpha(MUTED, 0.42));
    }

    fn fill(&self, r: usize, color: Color, dx: f32, dy: f32) {
        let region = &self.board.regions[r];
        for (p, triangles) in region.polygons.iter().zip(&region.triangles) {
            for t in triangles.chunks_exact(3) {
                let (a, b, c) = (t[0] as usize * 2, t[1] as usize * 2, t[2] as usize * 2);
                self.gfx.triangle(
                    self.cx(p[a]) + dx,
                    self.cy(p[a + 1]) + dy,
                    self.cx(p[b]) + dx,
                    self.cy(p[b + 1]) + dy,
                    self.cx(p[c]) + dx,
                    self.cy(p[c + 1]) + dy,
                    color,
                );
            }
        }
    }

    fn outline(&self, r: usize, color: Color, width: f32) {
        for p in &self.board.regions[r].polygons {
            for i in (0..p.len()).step_by(2) {
                let j = (i + 2) % p.len();
                self.gfx.line(
                    self.cx(p[i]),
                    self.cy(p[i + 1]),
                    self.cx(p[j]),
                    self.cy(p[j + 1]),
                    width,
                    color,
                );
            }
        }
    }

    fn draw_labels(&self) {
        let g = &self.gfx;
        let mut occupied: Vec<Area> = Vec::new();
        let mut order: Vec<usize> = (0..self.board.regions.len()).collect();
        order.sort_by_key(|&r| {
            if Some(r) == self.selected {
                -2
            } else if Some(r) == self.hovered {
                -1
            } else {
                0
            }
        });
        let in_game = self.game.is_some();
        for r in order {
            let region = &self.board.regions[r];
            let x = self.cx(region.anchor.x);
            let y = self.cy(region.anchor.y)
                + if in_game {
                    self.token_radius() + 15.0
                } else {
                    6.0
                };
            if !self.camera.inside(x, y) {
                continue;
            }
            let style = SMALL.scaled(if self.camera.zoom > 2.0 { 1.0 } else { 0.83 });
            let label = if self.camera.zoom < 1.8 {
                OVERVIEW_LABELS
                    .iter()
                    .find(|(name, _)| *name == region.name)
                    .map(|(_, short)| *short)
                    .unwrap_or(&region.label)
            } else {
                &region.label
            };
            let (w, h) = (g.block_width(style, label), g.block_height(style, label));
            let bounds = Area::new(x - w / 2.0 - 3.0, y - h - 3.0, w + 6.0, h + 7.0);
            let collision = occupied.iter().any(|b| b.overlaps(&bounds));
            let focused = Some(r) == self.selected || Some(r) == self.hovered;
            if collision && !focused {
                continue;
            }
            occupied.push(bounds);
            let left = self.camera.world(x - w / 2.0, y - h / 2.0);
            let right = self.camera.world(x + w / 2.0, y - h / 2.0);
            let coastal = !region.contains(region.anchor.x, region.anchor.y)
                || !region.contains(left.x, left.y)
                || !region.contains(right.x, right.y);
            if coastal {
                g.rect(bounds.x, bounds.y, bounds.w, bounds.h, alpha(INK, 0.80));
            }
            let dim = self.role(r) == Role::Dim;
            let color = match (coastal, dim) {
                (true, true) => MUTED,
                (true, false) => PAPER,
                (false, true) => alpha(INK, 0.55),
                (false, false) => INK,
            };
            g.centered(style, label, x, y, color);
        }
    }

    fn draw_lane(&self, edge: usize, highlight: bool) {
        let g = &self.gfx;
        let path = &self.lanes[&edge];
        let c = alpha(
            GOLD,
            if highlight {
                0.95
            } else if self.selected.is_none() {
                0.57
            } else {
                0.13
            },
        );
        let mut length = 0.0;
        for pair in path.windows(2) {
            let (a, b) = (pair[0], pair[1]);
            length += a.dst(b) * self.camera.scale();
            let (ax, bx) = (wrap_x(a.x), wrap_x(b.x));
            if (ax - bx).abs() > 900.0 {
                continue;
            }
            if length % 12.0 < 7.0 {
                g.line(
                    self.cx(ax),
                    self.cy(a.y),
                    self.cx(bx),
                    self.cy(b.y),
                    if highlight { 2.0 } else { 1.2 },
                    c,
                );
            }
        }
        if highlight {
            let e = &self.board.connections[edge];
            for r in [e.a, e.b] {
                let anchor = self.board.regions[r].anchor;
                g.ring(self.cx(anchor.x), self.cy(anchor.y), 7.0, 1.2, GOLD);
            }
        }
    }

    fn compass(&self, x: f32, y: f32, r: f32) {
        let g = &self.gfx;
        let c = alpha(MUTED, 0.4);
        g.ring(x, y, r, 0.7, c);
        g.ring(x, y, r - 6.0, 0.6, c);
        for i in 0..8 {
            let a = i as f32 * std::f32::consts::PI / 4.0;
            g.line(x, y, x + a.sin() * r * 1.2, y + a.cos() * r * 1.2, 0.7, c);
        }
        g.triangle(x, y + r * 1.2, x - 5.0, y, x, y - 5.0, c);
        g.triangle(x, y - r * 1.2, x + 5.0, y, x, y + 5.0, c);
    }

    fn ship(&self, x: f32, y: f32, s: f32) {
        let g = &self.gfx;
        let c = alpha(MUTED, 0.27);
        g.line(x - 14.0 * s, y, x + 14.0 * s, y, 0.9, c);
        g.line(x - 14.0 * s, y, x - 7.0 * s, y - 5.0 * s, 0.9, c);
        g.line(x - 7.0 * s, y - 5.0 * s, x + 8.0 * s, y - 5.0 * s, 0.9, c);
        g.line(x + 8.0 * s, y - 5.0 * s, x + 14.0 * s, y, 0.9, c);
        g.line(x, y, x, y + 27.0 * s, 0.8, c);
        g.line(x, y + 27.0 * s, x - 13.0 * s, y + 5.0 * s, 0.8, c);
        g.line(x - 13.0 * s, y + 5.0 * s, x, y + 5.0 * s, 0.8, c);
        g.line(x + 3.0 * s, y + 23.0 * s, x + 13.0 * s, y + 5.0 * s, 0.8, c);
        g.line(x + 13.0 * s, y + 5.0 * s, x + 3.0 * s, y + 5.0 * s, 0.8, c);
    }

    pub fn pick(&self, x: f32, y: f32) -> Option<usize> {
        if !self.camera.inside(x, y) {
            return None;
        }
        let p = self.camera.world(x, y);
        // Small-island anchors receive a screen-space target so PEI and Hawaii
        // remain selectable even at the overview scale.
        let reach = if self.game.is_some() {
            self.token_radius() + 3.0
        } else {
            7.0
        };
        let mut nearest = None;
        let mut distance = reach * reach;
        for (i, r) in self.board.regions.iter().enumerate() {
            let (dx, dy) = (self.cx(r.anchor.x) - x, self.cy(r.anchor.y) - y);
            let d = dx * dx + dy * dy;
            if d < distance {
                nearest = Some(i);
                distance = d;
            }
        }
        nearest.or_else(|| self.board.regions.iter().position(|r| r.contains(p.x, p.y)))
    }
}
