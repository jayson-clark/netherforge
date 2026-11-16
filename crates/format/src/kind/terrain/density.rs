//! The 3D terrain of a generator whose file has a `terrain.density`, bound to
//! a world's seed and heights (format's `DensityField`, `CornerColumn` and
//! `DensityColumn`).
//!
//! A block's density, in blocks, is the ground's, `height + 0.5 - y +
//! ground(x, y, z)`, or the islands' where that's higher, plus what the
//! script's `density` stage changes; it's solid where that's above 0. `height`
//! is the column's own; everything else is read at the points of a grid
//! `CELL_XZ` blocks apart across and `CELL_Y` up (world-aligned, so a point is
//! the same for every chunk that reads it) and interpolated trilinearly
//! between them. Every 3D noise is kept within -1 to 1, so the ground's offset
//! is never more than `reach` blocks either way: further below its column's
//! height a block is solid, further above air, and nothing there is read
//! (unless the script's stage can change it).

use super::compiled::{CompiledArea, CompiledDensity, CompiledIslands, CompiledTerrain};
use crate::noise::fast_noise_lite::FastNoiseLite;
use crate::noise::seeds;

pub const CELL_XZ: i32 = 4;
pub const CELL_Y: i32 = 8;

/// Which of a grid point's three values: the ground's offset, the islands'
/// density, or the script's change.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Part {
    Ground,
    Islands,
    Delta,
}

pub struct DensityField {
    areas: Vec<CompiledArea>,
    pub density: CompiledDensity,
    pub min_y: i32,
    pub max_y: i32,
    file_noises: Vec<FastNoiseLite>,
    area_noises: Vec<Vec<FastNoiseLite>>,
    pub islands: Option<CompiledIslands>,
    islands_noise: Option<FastNoiseLite>,
    islands_mask: Option<Vec<bool>>,
    half: f64,
    /// Every area's 3D noises are the same: a point's ground doesn't depend on its area.
    pub one_density: bool,
    /// Whether a grid column needs the areas' weights: when the ground differs between areas, or islands keep to some.
    pub weighed: bool,
    /// The most the ground's 3D noises move it, in blocks, anywhere.
    pub reach: f64,
    /// The grid's lowest level (its y over `CELL_Y`), and how many there are: one past the world's top.
    pub lowest_level: i32,
    pub levels: i32,
    /// The highest block an island can be in, or below the world when there are none.
    pub islands_top: i32,
}

impl DensityField {
    pub fn new(
        terrain: &CompiledTerrain,
        density: &CompiledDensity,
        seed: i64,
        min_y: i32,
        max_y: i32,
    ) -> DensityField {
        let areas = terrain.areas.clone();
        let file_noises = density
            .noises
            .iter()
            .map(|n| {
                n.noise
                    .build(seeds::for_role(seed, &format!("density:{}", n.name)))
            })
            .collect();
        let area_noises = areas
            .iter()
            .map(|area| {
                area.terrain
                    .density_noises
                    .iter()
                    .map(|n| {
                        n.noise.build(seeds::for_role(
                            seed,
                            &format!("area:{}:density:{}", area.name, n.name),
                        ))
                    })
                    .collect()
            })
            .collect();
        let islands = density.islands.clone();
        let islands_noise = islands
            .as_ref()
            .map(|i| i.noise.build(seeds::for_role(seed, "islands")));
        let islands_mask = islands
            .as_ref()
            .filter(|i| !i.areas.is_empty())
            .map(|i| (0..areas.len()).map(|a| i.areas.contains(&a)).collect());
        let half = f64::from(islands.as_ref().map_or(0, |i| i.thickness)) / 2.0;
        let first = &areas[0].terrain;
        let one_density = areas.iter().all(|a| {
            a.terrain.density_scale == first.density_scale
                && a.terrain.density_noises == first.density_noises
        });
        let weighed = areas.len() > 1 && (!one_density || islands_mask.is_some());
        let file = density
            .noises
            .iter()
            .fold(0.0, |sum, n| sum + n.amplitude.abs());
        let reach = areas
            .iter()
            .map(|a| {
                a.terrain.density_scale.abs() * file
                    + a.terrain
                        .density_noises
                        .iter()
                        .fold(0.0, |sum, n| sum + n.amplitude.abs())
            })
            .fold(f64::NEG_INFINITY, f64::max);
        let lowest_level = min_y.div_euclid(CELL_Y);
        let levels = (max_y - 1).div_euclid(CELL_Y) + 2 - lowest_level;
        let mut field = DensityField {
            areas,
            density: density.clone(),
            min_y,
            max_y,
            file_noises,
            area_noises,
            islands,
            islands_noise,
            islands_mask,
            half,
            one_density,
            weighed,
            reach,
            lowest_level,
            levels,
            islands_top: min_y - 1,
        };
        for i in 0..levels {
            if field.in_band(i) || (i + 1 < levels && field.in_band(i + 1)) {
                field.islands_top = (lowest_level + i) * CELL_Y + CELL_Y - 1;
            }
        }
        field
    }

    pub fn y_of(&self, level: i32) -> i32 {
        (self.lowest_level + level) * CELL_Y
    }

    /// Whether the points of grid level `level` (an index from the lowest) are inside the islands' band.
    pub fn in_band(&self, level: i32) -> bool {
        self.islands
            .as_ref()
            .is_some_and(|band| f64::from(self.y_of(level) - band.y).abs() < self.half)
    }

    /// The ground's offset at a point: each area's 3D noises, weighed by `weights` (none: there's one ground).
    pub fn ground(&self, weights: Option<&[f64]>, x: f64, y: f64, z: f64) -> f64 {
        let mut file = 0.0;
        for (i, n) in self.density.noises.iter().enumerate() {
            file += unit(self.file_noises[i].get_noise_3d(x, y * n.squash, z)) * n.amplitude;
        }
        let Some(weights) = weights.filter(|_| !self.one_density) else {
            return self.area_ground(0, file, x, y, z);
        };
        let mut sum = 0.0;
        for (a, w) in weights.iter().enumerate() {
            if *w > 0.0 {
                sum += w * self.area_ground(a, file, x, y, z);
            }
        }
        sum
    }

    fn area_ground(&self, area: usize, file: f64, x: f64, y: f64, z: f64) -> f64 {
        let terrain = &self.areas[area].terrain;
        let mut value = terrain.density_scale * file;
        for (i, noise) in self.area_noises[area].iter().enumerate() {
            let n = &terrain.density_noises[i];
            value += unit(noise.get_noise_3d(x, y * n.squash, z)) * n.amplitude;
        }
        value
    }

    /// How much of a point is in the islands' areas, 0 to 1, from its areas' `weights` (none: all in `area`).
    pub fn islands_weight(&self, weights: Option<&[f64]>, area: usize) -> f64 {
        let Some(mask) = &self.islands_mask else {
            return 1.0;
        };
        match weights {
            None => {
                if mask[area] {
                    1.0
                } else {
                    0.0
                }
            }
            Some(weights) => {
                let mut sum = 0.0;
                for (a, w) in weights.iter().enumerate() {
                    if mask[a] {
                        sum += w;
                    }
                }
                sum
            }
        }
    }

    /// The islands' density at a point, in blocks: above 0 where the noise is above the threshold, by more the
    /// nearer the band's middle. Out of the islands' areas (`weight` 0) the threshold rises to 1, so they fade.
    pub fn islands_at(&self, weight: f64, x: f64, y: f64, z: f64) -> f64 {
        let band = self.islands.as_ref().expect("a field with islands");
        let threshold = band.threshold + (1.0 - weight) * (1.0 - band.threshold);
        let n = unit(
            self.islands_noise
                .as_ref()
                .expect("islands' noise")
                .get_noise_3d(x, y, z),
        );
        (n - threshold) / (1.0 - band.threshold) * self.half - (y - f64::from(band.y)).abs()
    }
}

fn unit(value: f64) -> f64 {
    value.clamp(-1.0, 1.0)
}

/// One column of the grid's points: the ground's offset, the islands' density
/// and the script's change at each level, each worked out the first time it's read.
pub struct CornerColumn {
    pub x: i32,
    pub z: i32,
    weights: Option<Vec<f64>>,
    islands_weight: f64,
    ground: Vec<f64>,
    islands: Option<Vec<f64>>,
    delta: Option<Vec<f64>>,
    height: Option<i32>,
}

/// What a corner asks of the sampler that made it: its column's height, and
/// the script's `density` stage at a point (none where it failed).
pub trait CornerSource {
    fn base_height(&self, x: i32, z: i32) -> i32;
    fn script_density(&self, x: i32, y: i32, z: i32, value: f64) -> Option<f64>;
}

impl CornerColumn {
    pub fn new(
        field: &DensityField,
        x: i32,
        z: i32,
        weights: Option<Vec<f64>>,
        islands_weight: f64,
        scripted: bool,
    ) -> CornerColumn {
        let levels = field.levels as usize;
        CornerColumn {
            x,
            z,
            weights,
            islands_weight,
            ground: vec![f64::NAN; levels],
            islands: field.islands.as_ref().map(|_| vec![f64::NAN; levels]),
            delta: scripted.then(|| vec![f64::NAN; levels]),
            height: None,
        }
    }

    pub fn value(
        &mut self,
        field: &DensityField,
        source: &dyn CornerSource,
        part: Part,
        level: i32,
    ) -> f64 {
        match part {
            Part::Ground => self.ground(field, level),
            Part::Islands => self.islands(field, level),
            Part::Delta => self.delta(field, source, level),
        }
    }

    fn ground(&mut self, field: &DensityField, level: i32) -> f64 {
        let known = self.ground[level as usize];
        if !known.is_nan() {
            return known;
        }
        let value = field.ground(
            self.weights.as_deref(),
            f64::from(self.x),
            f64::from(field.y_of(level)),
            f64::from(self.z),
        );
        self.ground[level as usize] = value;
        value
    }

    fn islands(&mut self, field: &DensityField, level: i32) -> f64 {
        let known = self.islands.as_ref().expect("a field with islands")[level as usize];
        if !known.is_nan() {
            return known;
        }
        let value = field.islands_at(
            self.islands_weight,
            f64::from(self.x),
            f64::from(field.y_of(level)),
            f64::from(self.z),
        );
        self.islands.as_mut().expect("a field with islands")[level as usize] = value;
        value
    }

    /// What the script's stage changed of the file's density at a point: 0 where it failed.
    fn delta(&mut self, field: &DensityField, source: &dyn CornerSource, level: i32) -> f64 {
        let known = self.delta.as_ref().expect("a scripted field")[level as usize];
        if !known.is_nan() {
            return known;
        }
        let y = field.y_of(level);
        let height = match self.height {
            Some(h) => h,
            None => {
                let h = source.base_height(self.x, self.z);
                self.height = Some(h);
                h
            }
        };
        let ground = f64::from(height) + 0.5 - f64::from(y) + self.ground(field, level);
        let value = match self.islands {
            Some(_) => ground.max(self.islands(field, level)),
            None => ground,
        };
        let changed = source
            .script_density(self.x, y, self.z, value)
            .map(|v| v - value)
            .filter(|d| d.is_finite())
            .unwrap_or(0.0);
        self.delta.as_mut().expect("a scripted field")[level as usize] = changed;
        changed
    }
}

pub type Corner = std::rc::Rc<std::cell::RefCell<CornerColumn>>;

/// A column of blocks' density: its own height and the four grid columns
/// round it, whose points are interpolated. `solid` is the one answer every
/// part asks (the chunk's blocks, the column's surfaces, the map).
pub struct DensityColumn<'a> {
    field: &'a DensityField,
    /// Whether the script has a `density` stage, which can change any point: then every block is read.
    scripted: bool,
    source: &'a dyn CornerSource,
    pub height: i32,
    fx: f64,
    fz: f64,
    c00: Corner,
    c10: Corner,
    c01: Corner,
    c11: Corner,
}

impl<'a> DensityColumn<'a> {
    pub fn new(
        field: &'a DensityField,
        source: &'a dyn CornerSource,
        scripted: bool,
        height: i32,
        x: i32,
        z: i32,
        corner: &dyn Fn(i32, i32) -> Corner,
    ) -> DensityColumn<'a> {
        let gx = x.div_euclid(CELL_XZ);
        let gz = z.div_euclid(CELL_XZ);
        let fx = f64::from(x - gx * CELL_XZ) / f64::from(CELL_XZ);
        let fz = f64::from(z - gz * CELL_XZ) / f64::from(CELL_XZ);
        let c00 = corner(gx, gz);
        let c10 = if fx > 0.0 {
            corner(gx + 1, gz)
        } else {
            c00.clone()
        };
        let c01 = if fz > 0.0 {
            corner(gx, gz + 1)
        } else {
            c00.clone()
        };
        let c11 = if fx > 0.0 && fz > 0.0 {
            corner(gx + 1, gz + 1)
        } else if fx > 0.0 {
            c10.clone()
        } else {
            c01.clone()
        };
        DensityColumn {
            field,
            scripted,
            source,
            height,
            fx,
            fz,
            c00,
            c10,
            c01,
            c11,
        }
    }

    /// Whether the block at `y` is solid; above the world's top, nothing is.
    pub fn solid(&self, y: i32) -> bool {
        let field = self.field;
        if y >= field.max_y {
            return false;
        }
        let ground = f64::from(self.height) + 0.5 - f64::from(y);
        let level = y.div_euclid(CELL_Y);
        let i = level - field.lowest_level;
        let fy = f64::from(y - level * CELL_Y) / f64::from(CELL_Y);
        let islands = field.islands.is_some();
        if self.scripted {
            let mut value = ground + self.interpolate(Part::Ground, i, fy);
            if islands {
                value = value.max(self.interpolate(Part::Islands, i, fy));
            }
            return value + self.interpolate(Part::Delta, i, fy) > 0.0;
        }
        if ground > field.reach {
            return true;
        }
        if islands
            && (field.in_band(i) || field.in_band(i + 1))
            && self.interpolate(Part::Islands, i, fy) > 0.0
        {
            return true;
        }
        if ground < -field.reach {
            return false;
        }
        ground + self.interpolate(Part::Ground, i, fy) > 0.0
    }

    /// The highest block that can be solid: everything above it is air.
    pub fn highest(&self) -> i32 {
        let field = self.field;
        if self.scripted {
            return field.max_y - 1;
        }
        let ground = (f64::from(self.height) + 0.5 + field.reach).floor() as i32;
        (field.max_y - 1).min(ground.max(field.islands_top))
    }

    /// Whether every block from `y` down is solid, so there's nothing more to look for below it.
    pub fn solid_below(&self, y: i32) -> bool {
        !self.scripted && f64::from(self.height) + 0.5 - f64::from(y) > self.field.reach
    }

    /// The y of the topmost solid block, or one below the world's bottom when there's none.
    pub fn top(&self) -> i32 {
        let mut y = self.highest();
        while y >= self.field.min_y {
            if self.solid(y) {
                return y;
            }
            y -= 1;
        }
        self.field.min_y - 1
    }

    /// Every top surface (a solid block with no solid block on it), from the highest down.
    pub fn surfaces(&self) -> Vec<i32> {
        let mut out = Vec::new();
        let mut above = false;
        let mut y = self.highest();
        while y >= self.field.min_y {
            let solid = self.solid(y);
            if solid && !above {
                out.push(y);
            }
            if solid && self.solid_below(y) {
                break;
            }
            above = solid;
            y -= 1;
        }
        out
    }

    fn interpolate(&self, part: Part, level: i32, fy: f64) -> f64 {
        let low = self.across(part, level);
        if fy == 0.0 {
            return low;
        }
        let high = self.across(part, level + 1);
        low + (high - low) * fy
    }

    fn value(&self, corner: &Corner, part: Part, level: i32) -> f64 {
        corner
            .borrow_mut()
            .value(self.field, self.source, part, level)
    }

    fn across(&self, part: Part, level: i32) -> f64 {
        let v00 = self.value(&self.c00, part, level);
        let a = if self.fx > 0.0 {
            v00 + (self.value(&self.c10, part, level) - v00) * self.fx
        } else {
            v00
        };
        if self.fz == 0.0 {
            return a;
        }
        let v01 = self.value(&self.c01, part, level);
        let b = if self.fx > 0.0 {
            v01 + (self.value(&self.c11, part, level) - v01) * self.fx
        } else {
            v01
        };
        a + (b - a) * self.fz
    }
}
