//! Drawing primitives in the 1600 × 1000 virtual console space with y pointing up, letterboxed
//! into the real window like a fit viewport.

use macroquad::prelude::*;

pub const VIRTUAL_W: f32 = 1600.0;
pub const VIRTUAL_H: f32 = 1000.0;

/// Parses a hex `RRGGBB` (or `RRGGBBAA`) colour.
pub fn hex(value: &str) -> Color {
    let v = u32::from_str_radix(value, 16).unwrap_or(0xFF00FF);
    if value.len() > 6 {
        Color::from_rgba((v >> 24) as u8, (v >> 16) as u8, (v >> 8) as u8, v as u8)
    } else {
        Color::from_rgba((v >> 16) as u8, (v >> 8) as u8, v as u8, 255)
    }
}

pub fn alpha(c: Color, a: f32) -> Color {
    Color::new(c.r, c.g, c.b, a)
}

/// Linear interpolation of every channel, alpha included.
pub fn lerp(c: Color, target: Color, t: f32) -> Color {
    Color::new(
        c.r + t * (target.r - c.r),
        c.g + t * (target.g - c.g),
        c.b + t * (target.b - c.b),
        c.a + t * (target.a - c.a),
    )
}

pub fn mul(c: Color, f: f32) -> Color {
    Color::new(c.r * f, c.g * f, c.b * f, c.a)
}

/// An axis-aligned rectangle with its origin at the bottom-left corner.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Area {
    pub x: f32,
    pub y: f32,
    pub w: f32,
    pub h: f32,
}

impl Area {
    pub const fn new(x: f32, y: f32, w: f32, h: f32) -> Self {
        Area { x, y, w, h }
    }
    pub fn contains(&self, px: f32, py: f32) -> bool {
        self.x <= px && self.x + self.w >= px && self.y <= py && self.y + self.h >= py
    }
    pub fn overlaps(&self, o: &Area) -> bool {
        self.x < o.x + o.w && self.x + self.w > o.x && self.y < o.y + o.h && self.y + self.h > o.y
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Face {
    Sans,
    Serif,
}

/// The game's type scale.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct TextStyle {
    pub face: Face,
    pub size: f32,
    pub outline: bool,
}

pub const SMALL: TextStyle = TextStyle {
    face: Face::Sans,
    size: 13.0,
    outline: false,
};
pub const BODY: TextStyle = TextStyle {
    face: Face::Sans,
    size: 16.0,
    outline: false,
};
pub const TITLE: TextStyle = TextStyle {
    face: Face::Serif,
    size: 35.0,
    outline: false,
};
pub const HEADING: TextStyle = TextStyle {
    face: Face::Serif,
    size: 25.0,
    outline: false,
};
pub const GAME: TextStyle = TextStyle {
    face: Face::Sans,
    size: 21.0,
    outline: false,
};
pub const BIG: TextStyle = TextStyle {
    face: Face::Sans,
    size: 40.0,
    outline: false,
};
/// Army numbers carry a dark outline so they read on every player colour and zoom level.
pub const COUNTER: TextStyle = TextStyle {
    face: Face::Sans,
    size: 17.0,
    outline: true,
};

impl TextStyle {
    pub fn scaled(self, factor: f32) -> TextStyle {
        TextStyle {
            size: self.size * factor,
            ..self
        }
    }
    /// Height of capital letters above the baseline.
    pub fn cap_height(self) -> f32 {
        self.size * 0.714
    }
    pub fn line_height(self) -> f32 {
        self.size * 1.362
    }
}

pub struct Gfx {
    sans: Font,
    serif: Font,
    /// Logical pixels per virtual unit.
    pub scale: f32,
    pub offset_x: f32,
    pub offset_y: f32,
    dpi: f32,
}

impl Gfx {
    pub fn new(sans: Font, serif: Font) -> Self {
        let mut g = Gfx {
            sans,
            serif,
            scale: 1.0,
            offset_x: 0.0,
            offset_y: 0.0,
            dpi: 1.0,
        };
        g.fit();
        g
    }

    /// Recomputes the letterboxed viewport for the current window size.
    pub fn fit(&mut self) {
        let (w, h) = (screen_width(), screen_height());
        self.scale = (w / VIRTUAL_W).min(h / VIRTUAL_H).max(0.01);
        self.offset_x = (w - VIRTUAL_W * self.scale) / 2.0;
        self.offset_y = (h - VIRTUAL_H * self.scale) / 2.0;
        self.dpi = screen_dpi_scale();
    }

    /// Converts a window position in logical pixels to virtual coordinates.
    pub fn unproject(&self, x: f32, y: f32) -> (f32, f32) {
        (
            (x - self.offset_x) / self.scale,
            VIRTUAL_H - (y - self.offset_y) / self.scale,
        )
    }

    pub fn sx(&self, x: f32) -> f32 {
        self.offset_x + x * self.scale
    }
    pub fn sy(&self, y: f32) -> f32 {
        self.offset_y + (VIRTUAL_H - y) * self.scale
    }

    /// Restricts drawing to a virtual rectangle (None clears the clip). The batcher starts a new
    /// draw call whenever the clip changes, so no flush is needed (a mid-frame flush would also
    /// invalidate glyphs batched against a font atlas that grows later in the frame).
    pub fn clip(&self, area: Option<Area>) {
        let gl = unsafe { get_internal_gl() };
        gl.quad_gl.scissor(area.map(|a| {
            let d = self.dpi;
            (
                (self.sx(a.x) * d).round() as i32,
                (self.sy(a.y + a.h) * d).round() as i32,
                (a.w * self.scale * d).round() as i32,
                (a.h * self.scale * d).round() as i32,
            )
        }));
    }

    pub fn rect(&self, x: f32, y: f32, w: f32, h: f32, c: Color) {
        draw_rectangle(
            self.sx(x),
            self.sy(y + h),
            w * self.scale,
            h * self.scale,
            c,
        );
    }

    pub fn line(&self, x: f32, y: f32, xx: f32, yy: f32, width: f32, c: Color) {
        draw_line(
            self.sx(x),
            self.sy(y),
            self.sx(xx),
            self.sy(yy),
            width * self.scale,
            c,
        );
    }

    pub fn triangle(&self, x1: f32, y1: f32, x2: f32, y2: f32, x3: f32, y3: f32, c: Color) {
        draw_triangle(
            vec2(self.sx(x1), self.sy(y1)),
            vec2(self.sx(x2), self.sy(y2)),
            vec2(self.sx(x3), self.sy(y3)),
            c,
        );
    }

    pub fn circle(&self, x: f32, y: f32, r: f32, segments: u8, c: Color) {
        draw_poly(
            self.sx(x),
            self.sy(y),
            segments.max(3),
            r * self.scale,
            0.0,
            c,
        );
    }

    /// A filled pie slice; angles in degrees counter-clockwise from +x.
    pub fn arc(&self, x: f32, y: f32, r: f32, start: f32, degrees: f32, segments: u32, c: Color) {
        let step = degrees.to_radians() / segments as f32;
        let a0 = start.to_radians();
        for i in 0..segments {
            let (a, b) = (a0 + i as f32 * step, a0 + (i + 1) as f32 * step);
            self.triangle(
                x,
                y,
                x + a.cos() * r,
                y + a.sin() * r,
                x + b.cos() * r,
                y + b.sin() * r,
                c,
            );
        }
    }

    /// Filled rounded rectangle; corners are pie slices so translucent colours do not double-blend.
    pub fn round(&self, x: f32, y: f32, w: f32, h: f32, r: f32, c: Color) {
        let r = r.min(w.min(h) / 2.0).max(0.0);
        self.rect(x + r, y, w - 2.0 * r, h, c);
        self.rect(x, y + r, r, h - 2.0 * r, c);
        self.rect(x + w - r, y + r, r, h - 2.0 * r, c);
        if r > 0.0 {
            self.arc(x + r, y + r, r, 180.0, 90.0, 8, c);
            self.arc(x + w - r, y + r, r, 270.0, 90.0, 8, c);
            self.arc(x + w - r, y + h - r, r, 0.0, 90.0, 8, c);
            self.arc(x + r, y + h - r, r, 90.0, 90.0, 8, c);
        }
    }

    pub fn ring(&self, x: f32, y: f32, radius: f32, width: f32, c: Color) {
        let tau = std::f32::consts::TAU;
        for i in 0..24 {
            let (a, b) = (i as f32 * tau / 24.0, (i + 1) as f32 * tau / 24.0);
            self.line(
                x + a.cos() * radius,
                y + a.sin() * radius,
                x + b.cos() * radius,
                y + b.sin() * radius,
                width,
                c,
            );
        }
    }

    fn params(&self, style: TextStyle, color: Color) -> TextParams<'_> {
        let logical = style.size * self.scale;
        let px = logical.round().clamp(1.0, 1000.0);
        TextParams {
            font: Some(match style.face {
                Face::Sans => &self.sans,
                Face::Serif => &self.serif,
            }),
            font_size: px as u16,
            font_scale: logical / px,
            font_scale_aspect: 1.0,
            rotation: 0.0,
            color,
        }
    }

    /// Advance width of a single line in virtual units.
    pub fn width(&self, style: TextStyle, value: &str) -> f32 {
        let p = self.params(style, WHITE);
        measure_text(value, p.font, p.font_size, p.font_scale).width / self.scale
    }

    /// Draws one line with the top of its capitals at `y`.
    pub fn text(&self, style: TextStyle, value: &str, x: f32, y: f32, color: Color) {
        if value.is_empty() {
            return;
        }
        let (bx, by) = (self.sx(x), self.sy(y - style.cap_height()));
        if style.outline {
            let border = Color::new(0.03, 0.07, 0.1, 0.9 * color.a);
            let o = 2.0 * style.size / COUNTER.size * self.scale;
            for (dx, dy) in [
                (-1.0, -1.0),
                (0.0, -1.0),
                (1.0, -1.0),
                (-1.0, 0.0),
                (1.0, 0.0),
                (-1.0, 1.0),
                (0.0, 1.0),
                (1.0, 1.0),
            ] {
                draw_text_ex(value, bx + dx * o, by + dy * o, self.params(style, border));
            }
        }
        draw_text_ex(value, bx, by, self.params(style, color));
    }

    /// Centres each line of `value` on `x`; the first line's capitals start at `y`.
    pub fn centered(&self, style: TextStyle, value: &str, x: f32, y: f32, color: Color) {
        for (i, line) in value.split('\n').enumerate() {
            let w = self.width(style, line);
            self.text(
                style,
                line,
                x - w / 2.0,
                y - i as f32 * style.line_height(),
                color,
            );
        }
    }

    /// Width of the widest line of a possibly multi-line block.
    pub fn block_width(&self, style: TextStyle, value: &str) -> f32 {
        value
            .split('\n')
            .map(|l| self.width(style, l))
            .fold(0.0, f32::max)
    }

    /// Height of a block from the first line's capitals to the last baseline.
    pub fn block_height(&self, style: TextStyle, value: &str) -> f32 {
        style.cap_height() + (value.split('\n').count() - 1) as f32 * style.line_height()
    }

    pub fn right(&self, style: TextStyle, value: &str, x: f32, y: f32, color: Color) {
        let w = self.width(style, value);
        self.text(style, value, x - w, y, color);
    }

    /// Draws text shrunk to fit a width.
    pub fn fit_text(&self, style: TextStyle, value: &str, x: f32, y: f32, w: f32, color: Color) {
        let measured = self.width(style, value);
        let factor = (w / measured.max(1.0)).min(1.0);
        self.text(style.scaled(factor), value, x, y, color);
    }

    /// Breaks text into lines no wider than `width`, honouring explicit newlines.
    pub fn wrap_lines(&self, style: TextStyle, value: &str, width: f32) -> Vec<String> {
        let mut lines = Vec::new();
        for paragraph in value.split('\n') {
            let mut line = String::new();
            for word in paragraph.split(' ') {
                let candidate = if line.is_empty() {
                    word.to_string()
                } else {
                    format!("{line} {word}")
                };
                if !line.is_empty() && self.width(style, &candidate) > width {
                    lines.push(std::mem::take(&mut line));
                    line = word.to_string();
                } else {
                    line = candidate;
                }
            }
            lines.push(line);
        }
        lines
    }

    pub fn wrap(&self, style: TextStyle, value: &str, x: f32, y: f32, width: f32, color: Color) {
        for (i, line) in self.wrap_lines(style, value, width).iter().enumerate() {
            self.text(style, line, x, y - i as f32 * style.line_height(), color);
        }
    }
}
