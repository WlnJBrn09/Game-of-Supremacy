//! Flat, top-down map transform. Rotation and perspective are intentionally absent.
//!
//! Screen coordinates are the 1600 × 1000 virtual console with y pointing up.

use crate::board::Point;

#[derive(Clone, Copy, Debug)]
pub struct AtlasCamera {
    pub center_x: f32,
    pub center_y: f32,
    pub zoom: f32,
}

impl Default for AtlasCamera {
    fn default() -> Self {
        AtlasCamera {
            center_x: 900.0,
            center_y: 375.0,
            zoom: 1.0,
        }
    }
}

impl AtlasCamera {
    pub const X: f32 = 0.0;
    pub const Y: f32 = 268.0;
    pub const WIDTH: f32 = 1600.0;
    pub const HEIGHT: f32 = 676.0;
    pub const BASE_SCALE: f32 = Self::WIDTH / 1800.0;

    pub fn new() -> Self {
        Self::default()
    }
    pub fn scale(&self) -> f32 {
        Self::BASE_SCALE * self.zoom
    }
    pub fn screen_x(&self, x: f32) -> f32 {
        Self::X + Self::WIDTH / 2.0 + (x - self.center_x) * self.scale()
    }
    pub fn screen_y(&self, y: f32) -> f32 {
        Self::Y + Self::HEIGHT / 2.0 + (y - self.center_y) * self.scale()
    }
    pub fn world(&self, x: f32, y: f32) -> Point {
        Point::new(
            (x - Self::X - Self::WIDTH / 2.0) / self.scale() + self.center_x,
            (y - Self::Y - Self::HEIGHT / 2.0) / self.scale() + self.center_y,
        )
    }
    pub fn inside(&self, x: f32, y: f32) -> bool {
        (Self::X..=Self::X + Self::WIDTH).contains(&x)
            && (Self::Y..=Self::Y + Self::HEIGHT).contains(&y)
    }
    pub fn zoom_at(&mut self, factor: f32, x: f32, y: f32) {
        let before = self.world(x, y);
        self.zoom = (self.zoom * factor).clamp(1.0, 6.0);
        let after = self.world(x, y);
        self.center_x += before.x - after.x;
        self.center_y += before.y - after.y;
        self.clamp();
    }
    pub fn pan(&mut self, dx: f32, dy: f32) {
        self.center_x -= dx / self.scale();
        self.center_y -= dy / self.scale();
        self.clamp();
    }
    pub fn focus(&mut self, anchor: Point) {
        self.zoom = 3.0;
        self.center_x = anchor.x;
        self.center_y = anchor.y;
        self.clamp();
    }
    pub fn reset(&mut self) {
        *self = Self::default();
    }
    fn clamp(&mut self) {
        let half_x = Self::WIDTH / self.scale() / 2.0;
        let half_y = Self::HEIGHT / self.scale() / 2.0;
        self.center_x = if half_x >= 900.0 {
            900.0
        } else {
            self.center_x.clamp(half_x, 1800.0 - half_x)
        };
        self.center_y = if half_y >= 375.0 {
            375.0
        } else {
            self.center_y.clamp(half_y, 750.0 - half_y)
        };
    }
}
