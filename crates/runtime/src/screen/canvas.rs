//! A screen's pixels and the shapes scripts draw on them (Kotlin's `Canvas`, `Rect` and `Raster`), line for line.

use netherforge_format::kind::screen::TILE;

/// In a list of colours: leave this pixel as it is.
pub const SKIP: i32 = -1;
const CLEAN: i32 = i32::MIN;

/// A rectangle of a map's pixels, `left` to `right`, `top` to `bottom`, all included.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Rect {
    pub left: i32,
    pub top: i32,
    pub right: i32,
    pub bottom: i32,
}

impl Rect {
    /// A whole map.
    pub const WHOLE: Rect = Rect {
        left: 0,
        top: 0,
        right: TILE - 1,
        bottom: TILE - 1,
    };

    pub fn width(&self) -> i32 {
        self.right - self.left + 1
    }

    pub fn height(&self) -> i32 {
        self.bottom - self.top + 1
    }

    pub fn area(&self) -> i32 {
        self.width() * self.height()
    }

    pub fn union(self, other: Option<Rect>) -> Rect {
        match other {
            None => self,
            Some(o) => Rect {
                left: self.left.min(o.left),
                top: self.top.min(o.top),
                right: self.right.max(o.right),
                bottom: self.bottom.max(o.bottom),
            },
        }
    }
}

/// A screen's pixels: a map colour a pixel, `columns` by `rows` maps of `TILE` pixels a side, and for each map the
/// rectangle that changed since it was last taken. A pixel set to the colour it already has changes nothing.
#[derive(Clone)]
pub struct Canvas {
    pub columns: i32,
    pub rows: i32,
    pub width: i32,
    pub height: i32,
    /// Row by row across the whole screen.
    pub pixels: Vec<u8>,
    /// Per map: the changed rectangle's left, top, right and bottom, or `CLEAN` for none.
    dirty: Vec<i32>,
    /// Whether anything changed since the last take of every map.
    pub changed: bool,
}

impl Canvas {
    pub fn new(columns: i32, rows: i32) -> Canvas {
        let (width, height) = (columns * TILE, rows * TILE);
        let mut dirty = vec![0; (columns * rows * 4) as usize];
        reset(&mut dirty);
        Canvas {
            columns,
            rows,
            width,
            height,
            pixels: vec![0; (width * height) as usize],
            dirty,
            changed: false,
        }
    }

    pub fn inside(&self, x: i32, y: i32) -> bool {
        (0..self.width).contains(&x) && (0..self.height).contains(&y)
    }

    pub fn get(&self, x: i32, y: i32) -> u8 {
        self.pixels[(y * self.width + x) as usize]
    }

    /// Sets a pixel; one off the screen is left alone.
    pub fn set(&mut self, x: i32, y: i32, color: i32) {
        if !self.inside(x, y) {
            return;
        }
        let at = (y * self.width + x) as usize;
        let b = color as u8;
        if self.pixels[at] == b {
            return;
        }
        self.pixels[at] = b;
        self.mark(x, y, x, y);
    }

    /// Sets `count` pixels of row `y` from `x` to `colors` from `from` (`SKIP` leaves one alone).
    pub fn write(&mut self, x: i32, y: i32, colors: &[i32], from: usize, count: usize) {
        if y < 0 || y >= self.height {
            return;
        }
        let (mut left, mut right) = (i32::MAX, -1);
        let row = (y * self.width) as usize;
        for i in 0..count {
            let px = x + i as i32;
            if px < 0 || px >= self.width {
                continue;
            }
            let c = colors[from + i];
            if c == SKIP {
                continue;
            }
            let b = c as u8;
            let at = row + px as usize;
            if self.pixels[at] == b {
                continue;
            }
            self.pixels[at] = b;
            left = left.min(px);
            right = px;
        }
        if right >= 0 {
            self.mark(left, y, right, y);
        }
    }

    /// Fills a rectangle (clipped to the screen) with one colour.
    pub fn fill(&mut self, x: i32, y: i32, width: i32, height: i32, color: i32) {
        let (x0, y0) = (x.max(0), y.max(0));
        let x1 = (self.width.min(x.saturating_add(width))) - 1;
        let y1 = (self.height.min(y.saturating_add(height))) - 1;
        if x0 > x1 || y0 > y1 {
            return;
        }
        let b = color as u8;
        for py in y0..=y1 {
            let row = (py * self.width) as usize;
            let (mut left, mut right) = (-1, -1);
            for px in x0..=x1 {
                let at = row + px as usize;
                if self.pixels[at] == b {
                    continue;
                }
                self.pixels[at] = b;
                if left < 0 {
                    left = px;
                }
                right = px;
            }
            if left >= 0 {
                self.mark(left, py, right, py);
            }
        }
    }

    /// Fills the span of row `y` from `x0` to `x1`, both included and clipped.
    pub fn span(&mut self, y: i32, x0: i32, x1: i32, color: i32) {
        if y < 0 || y >= self.height {
            return;
        }
        self.fill(x0, y, x1 - x0 + 1, 1, color);
    }

    /// Copies a rectangle to (`to_x`, `to_y`), as if through a copy of it: overlapping is fine.
    pub fn copy(&mut self, x: i32, y: i32, width: i32, height: i32, to_x: i32, to_y: i32) {
        if width <= 0 || height <= 0 {
            return;
        }
        let block: Vec<i32> = (0..width * height)
            .map(|i| {
                let (px, py) = (x + i % width, y + i / width);
                if self.inside(px, py) {
                    i32::from(self.get(px, py))
                } else {
                    SKIP
                }
            })
            .collect();
        for row in 0..height {
            self.write(
                to_x,
                to_y + row,
                &block,
                (row * width) as usize,
                width as usize,
            );
        }
    }

    /// Every pixel of `other` (as big as this) where it differs: what a player's own view starts from.
    pub fn copy_from(&mut self, other: &Canvas) {
        for y in 0..self.height {
            let row = (y * self.width) as usize;
            let (mut left, mut right) = (-1, -1);
            for x in 0..self.width {
                let at = row + x as usize;
                let b = other.pixels[at];
                if self.pixels[at] == b {
                    continue;
                }
                self.pixels[at] = b;
                if left < 0 {
                    left = x;
                }
                right = x;
            }
            if left >= 0 {
                self.mark(left, y, right, y);
            }
        }
    }

    fn mark(&mut self, x0: i32, y0: i32, x1: i32, y1: i32) {
        self.changed = true;
        for row in y0 / TILE..=y1 / TILE {
            for column in x0 / TILE..=x1 / TILE {
                let at = ((row * self.columns + column) * 4) as usize;
                let left = x0.max(column * TILE) - column * TILE;
                let top = y0.max(row * TILE) - row * TILE;
                let right = x1.min(column * TILE + TILE - 1) - column * TILE;
                let bottom = y1.min(row * TILE + TILE - 1) - row * TILE;
                let d = &mut self.dirty[at..at + 4];
                if d[0] == CLEAN {
                    d.copy_from_slice(&[left, top, right, bottom]);
                } else {
                    d[0] = d[0].min(left);
                    d[1] = d[1].min(top);
                    d[2] = d[2].max(right);
                    d[3] = d[3].max(bottom);
                }
            }
        }
    }

    /// Map `tile`'s changed rectangle since the last take, or none; forgets it.
    pub fn take(&mut self, tile: usize) -> Option<Rect> {
        let at = tile * 4;
        if self.dirty[at] == CLEAN {
            return None;
        }
        let d = &self.dirty[at..at + 4];
        let rect = Rect {
            left: d[0],
            top: d[1],
            right: d[2],
            bottom: d[3],
        };
        self.dirty[at] = CLEAN;
        Some(rect)
    }

    /// Forgets every change: they've all been taken (or nobody needs them).
    pub fn settle(&mut self) {
        reset(&mut self.dirty);
        self.changed = false;
    }

    /// The pixels of map `tile` in `rect`, row by row: what a map packet carries.
    pub fn region(&self, tile: usize, rect: Rect) -> Vec<u8> {
        let left = (tile as i32 % self.columns) * TILE + rect.left;
        let top = (tile as i32 / self.columns) * TILE + rect.top;
        let w = rect.width() as usize;
        let mut out = Vec::with_capacity(w * rect.height() as usize);
        for row in 0..rect.height() {
            let start = ((top + row) * self.width + left) as usize;
            out.extend_from_slice(&self.pixels[start..start + w]);
        }
        out
    }
}

fn reset(dirty: &mut [i32]) {
    for i in (0..dirty.len()).step_by(4) {
        dirty[i] = CLEAN;
    }
}

/// A line from (`x1`, `y1`) to (`x2`, `y2`), both ends drawn, `thickness` pixels wide (a round brush past 2).
pub fn line(canvas: &mut Canvas, x1: i32, y1: i32, x2: i32, y2: i32, color: i32, thickness: i32) {
    if color == SKIP {
        return;
    }
    let brush = brush(thickness);
    let (mut x, mut y) = (x1, y1);
    let (dx, dy) = ((x2 - x1).abs(), -(y2 - y1).abs());
    let (sx, sy) = (if x1 < x2 { 1 } else { -1 }, if y1 < y2 { 1 } else { -1 });
    let mut err = dx + dy;
    loop {
        for (by, from, to) in &brush {
            canvas.span(y + by, x + from, x + to, color);
        }
        if x == x2 && y == y2 {
            break;
        }
        let e2 = 2 * err;
        if e2 >= dy {
            err += dy;
            x += sx;
        }
        if e2 <= dx {
            err += dx;
            y += sy;
        }
    }
}

/// The rows of a round brush `thickness` pixels across, as (dy, from dx, to dx).
fn brush(thickness: i32) -> Vec<(i32, i32, i32)> {
    if thickness <= 1 {
        return vec![(0, 0, 0)];
    }
    let r = f64::from(thickness) / 2.0;
    let low = -(thickness - 1) / 2;
    let high = low + thickness - 1;
    if thickness == 2 {
        return (low..=high).map(|d| (d, low, high)).collect();
    }
    let middle = f64::from(low + high) / 2.0;
    (low..=high)
        .filter_map(|dy| {
            let cy = f64::from(dy) - middle;
            let half = (r * r - cy * cy).max(0.0).sqrt();
            let from = (middle - half - 0.0001).ceil() as i32;
            let to = (middle + half + 0.0001).floor() as i32;
            (from <= to).then_some((dy, from, to))
        })
        .collect()
}

/// A rectangle with corners rounded by `radius` (held to half its shorter side): `fill` inside, `outline`
/// `thickness` pixels wide along its edge inside it. Either may be `SKIP`.
#[allow(clippy::too_many_arguments)]
pub fn rectangle(
    canvas: &mut Canvas,
    x: i32,
    y: i32,
    width: i32,
    height: i32,
    radius: i32,
    fill: i32,
    outline: i32,
    thickness: i32,
) {
    if width <= 0 || height <= 0 {
        return;
    }
    let r = radius.clamp(0, width.min(height) / 2);
    let t = thickness.clamp(1, 1.max(width.min(height) / 2 + 1));
    for row in 0..height {
        let (outer_from, outer_to) = rounded_span(row, width, height, r);
        if outer_from > outer_to {
            continue;
        }
        if outline == SKIP {
            if fill != SKIP {
                canvas.span(y + row, x + outer_from, x + outer_to, fill);
            }
            continue;
        }
        // The inside: the rectangle inset by the outline's thickness, its corners rounded as much less.
        let inside = (0..height - 2 * t).contains(&(row - t)) && width - 2 * t > 0;
        if !inside {
            canvas.span(y + row, x + outer_from, x + outer_to, outline);
            continue;
        }
        let (inner_from, inner_to) =
            rounded_span(row - t, width - 2 * t, height - 2 * t, (r - t).max(0));
        if inner_from > inner_to {
            canvas.span(y + row, x + outer_from, x + outer_to, outline);
            continue;
        }
        canvas.span(y + row, x + outer_from, x + t + inner_from - 1, outline);
        canvas.span(y + row, x + t + inner_to + 1, x + outer_to, outline);
        if fill != SKIP {
            canvas.span(y + row, x + t + inner_from, x + t + inner_to, fill);
        }
    }
}

/// The span of row `row` of a `width` by `height` rectangle with corners of `radius`: (first, last) column.
fn rounded_span(row: i32, width: i32, height: i32, radius: i32) -> (i32, i32) {
    if radius == 0 {
        return (0, width - 1);
    }
    let from_edge = if row < radius {
        f64::from(radius - row) - 0.5
    } else if row >= height - radius {
        f64::from(row - (height - radius)) + 0.5
    } else {
        return (0, width - 1);
    };
    let r = f64::from(radius);
    let inset = r - (r * r - from_edge * from_edge).max(0.0).sqrt();
    let cut = (inset + 0.5).floor() as i32;
    (cut, width - 1 - cut)
}

/// An ellipse round (`cx`, `cy`) with radii `rx` and `ry`: `fill` inside, `outline` `thickness` pixels inside its edge.
#[allow(clippy::too_many_arguments)]
pub fn ellipse(
    canvas: &mut Canvas,
    cx: i32,
    cy: i32,
    rx: i32,
    ry: i32,
    fill: i32,
    outline: i32,
    thickness: i32,
) {
    if rx < 0 || ry < 0 {
        return;
    }
    if rx == 0 || ry == 0 {
        let color = if outline != SKIP { outline } else { fill };
        if color != SKIP {
            canvas.fill(cx - rx, cy - ry, 2 * rx + 1, 2 * ry + 1, color);
        }
        return;
    }
    let t = thickness.max(1);
    let (irx, iry) = (rx - t, ry - t);
    for dy in -ry..=ry {
        let outer = half_width(f64::from(rx) + 0.5, f64::from(ry) + 0.5, dy);
        if outer < 0 {
            continue;
        }
        if outline == SKIP {
            if fill != SKIP {
                canvas.span(cy + dy, cx - outer, cx + outer, fill);
            }
            continue;
        }
        let inner = if irx >= 0 && iry >= 0 && dy.abs() <= iry {
            half_width(f64::from(irx) + 0.5, f64::from(iry) + 0.5, dy)
        } else {
            -1
        };
        if inner < 0 {
            canvas.span(cy + dy, cx - outer, cx + outer, outline);
            continue;
        }
        canvas.span(cy + dy, cx - outer, cx - inner - 1, outline);
        canvas.span(cy + dy, cx + inner + 1, cx + outer, outline);
        if fill != SKIP {
            canvas.span(cy + dy, cx - inner, cx + inner, fill);
        }
    }
}

/// How far right of the middle row `dy` of an ellipse of radii `a` and `b` reaches; -1 when it doesn't reach.
fn half_width(a: f64, b: f64, dy: i32) -> i32 {
    let f = 1.0 - f64::from(dy * dy) / (b * b);
    if f < 0.0 {
        return -1;
    }
    ((a * f.sqrt() - 0.5 + 0.0001).floor() as i32).max(0)
}

/// A polygon through `points` (x, y, x, y, …): `fill` inside (even-odd, sampled at pixel middles), then `outline`
/// `thickness` pixels wide along each edge.
pub fn polygon(canvas: &mut Canvas, points: &[i32], fill: i32, outline: i32, thickness: i32) {
    let n = points.len() / 2;
    if n < 3 {
        return;
    }
    if fill != SKIP {
        let top = (0..n).map(|i| points[i * 2 + 1]).min().unwrap_or(0);
        let bottom = (0..n).map(|i| points[i * 2 + 1]).max().unwrap_or(0);
        let mut crossings: Vec<f64> = Vec::with_capacity(n);
        for y in top.max(0)..=bottom.min(canvas.height - 1) {
            let sy = f64::from(y) + 0.5;
            crossings.clear();
            for i in 0..n {
                let (ax, ay) = (f64::from(points[i * 2]), f64::from(points[i * 2 + 1]));
                let j = (i + 1) % n;
                let (bx, by) = (f64::from(points[j * 2]), f64::from(points[j * 2 + 1]));
                if (ay <= sy && by > sy) || (by <= sy && ay > sy) {
                    crossings.push(ax + (sy - ay) / (by - ay) * (bx - ax));
                }
            }
            crossings.sort_by(f64::total_cmp);
            for pair in crossings.chunks_exact(2) {
                let from = (pair[0] - 0.5).ceil() as i32;
                let to = (pair[1] - 0.5).floor() as i32;
                if from <= to {
                    canvas.span(y, from, to, fill);
                }
            }
        }
    }
    if outline != SKIP {
        for i in 0..n {
            let j = (i + 1) % n;
            line(
                canvas,
                points[i * 2],
                points[i * 2 + 1],
                points[j * 2],
                points[j * 2 + 1],
                outline,
                thickness,
            );
        }
    }
}

/// Pixels `colors` (`width` a row, `SKIP` for none) with their top left at (`x`, `y`), each `scale` by `scale`.
pub fn blit(canvas: &mut Canvas, x: i32, y: i32, colors: &[i32], width: usize, scale: usize) {
    if width == 0 {
        return;
    }
    let height = colors.len() / width;
    if scale == 1 {
        for row in 0..height {
            canvas.write(x, y + row as i32, colors, row * width, width);
        }
        return;
    }
    let mut wide = vec![SKIP; width * scale];
    for row in 0..height {
        for column in 0..width {
            let c = colors[row * width + column];
            for k in 0..scale {
                wide[column * scale + k] = c;
            }
        }
        for k in 0..scale {
            canvas.write(x, y + (row * scale + k) as i32, &wide, 0, wide.len());
        }
    }
}
