//! A centity's `body` as it runs (Kotlin's `Mover` and `Movers`): drag, gravity and walking change its
//! velocity, then it moves by it along each axis against the blocks' collision shapes, stepping up what it
//! can, as Minecraft moves an entity. The world's blocks come from one `RegionShapes` read a tick. This is the
//! vanilla-style movement the roadmap keeps custom; physics bodies are Rapier's.

use netherforge_format::kind::centity::compiled::ResolvedBody;
use netherforge_format::math::{BoundingBox, Vec3};
use netherforge_platform::Platform;

use super::Instance;
use super::world_shapes::{BlockShapes, RegionShapes};

/// A tick, in seconds.
pub const SECONDS: f64 = 1.0 / 20.0;

/// What two faces may be apart and still touch.
const EPSILON: f64 = 1e-7;

/// How fast a body inside blocks rises out of them, blocks per second.
const RISE: f64 = 2.0;

/// A bounce off the ground slower than this, blocks per second, stops instead.
const MIN_BOUNCE: f64 = 1.0;

/// No jump rises for longer than this many ticks.
const MAX_RISE_TICKS: usize = 2000;
const MAX_JUMP_SPEED: f64 = 1e4;

#[derive(Clone, Debug, PartialEq)]
pub struct Mover {
    pub settings: ResolvedBody,
    /// Blocks per second, along the world's axes.
    pub velocity: Vec3,
    /// The velocity it walks at (`set_walk_velocity`), or none when it isn't walking.
    pub walk: Option<Vec3>,
    pub on_ground: bool,
    pub in_liquid: bool,
    /// The face that stopped it sideways on its last move, pointing back at it.
    pub wall: Option<Vec3>,
}

/// What one tick's move did that scripts hear: how fast it landed and what it ran into.
#[derive(Clone, Debug, PartialEq, Default)]
pub struct Moved {
    pub landed: Option<f64>,
    pub bumps: Vec<Bump>,
}

/// It ran into a face whose `normal` points back at it, going `speed` blocks per second into it.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Bump {
    pub normal: Vec3,
    pub speed: f64,
}

impl Mover {
    pub fn new(settings: ResolvedBody) -> Mover {
        Mover {
            settings,
            velocity: Vec3::ZERO,
            walk: None,
            on_ground: false,
            in_liquid: false,
            wall: None,
        }
    }

    /// The upward speed that rises `height` blocks under its gravity and drag, as `step` integrates it.
    pub fn jump_speed(&self, height: f64) -> f64 {
        if height <= 0.0 {
            return 0.0;
        }
        let s = &self.settings;
        let gravity = s.gravity
            * if self.in_liquid {
                s.liquid_gravity
            } else {
                1.0
            };
        if s.fly || gravity <= 0.0 {
            return (2.0 * gravity.max(1.0) * height).sqrt();
        }
        let keep = (1.0
            - if self.in_liquid {
                s.liquid_drag
            } else {
                s.drag
            })
        .powf(SECONDS);
        let apex = |v0: f64| {
            let (mut v, mut y) = (v0, 0.0);
            for _ in 0..MAX_RISE_TICKS {
                v = v * keep - gravity * SECONDS;
                if v <= 0.0 {
                    return y;
                }
                y += v * SECONDS;
            }
            y
        };
        let (mut lo, mut hi) = (0.0, 1.0);
        while apex(hi) < height && hi < MAX_JUMP_SPEED {
            hi *= 2.0;
        }
        for _ in 0..48 {
            let mid = (lo + hi) / 2.0;
            if apex(mid) < height {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        hi
    }
}

fn offset(b: &BoundingBox, x: f64, y: f64, z: f64) -> BoundingBox {
    BoundingBox {
        min: b.min + Vec3(x, y, z),
        max: b.max + Vec3(x, y, z),
    }
}

fn shrink(b: &BoundingBox) -> BoundingBox {
    let d = Vec3(1e-4, 1e-4, 1e-4);
    BoundingBox {
        min: b.min + d,
        max: b.max - d,
    }
}

pub fn overlaps(a: &BoundingBox, b: &BoundingBox) -> bool {
    a.min.0 < b.max.0
        && a.max.0 > b.min.0
        && a.min.1 < b.max.1
        && a.max.1 > b.min.1
        && a.min.2 < b.max.2
        && a.max.2 > b.min.2
}

fn flat_distance(from: &BoundingBox, to: &BoundingBox) -> f64 {
    let (dx, dz) = (to.min.0 - from.min.0, to.min.2 - from.min.2);
    dx * dx + dz * dz
}

fn sign(v: f64) -> f64 {
    if v > 0.0 { 1.0 } else { -1.0 }
}

/// `from` moved toward `to` by at most `rate`, on every axis together.
pub fn approach(from: Vec3, to: Vec3, rate: f64) -> Vec3 {
    let d = to - from;
    let length = d.length();
    if length <= rate {
        to
    } else {
        from + d * (rate / length)
    }
}

/// `from`'s sideways part moved toward `to`'s by at most `rate`; its up and down part as it is.
pub fn approach_flat(from: Vec3, to: Vec3, rate: f64) -> Vec3 {
    let (dx, dz) = (to.0 - from.0, to.2 - from.2);
    let length = (dx * dx + dz * dz).sqrt();
    if length <= rate {
        Vec3(to.0, from.1, to.2)
    } else {
        Vec3(
            from.0 + dx * rate / length,
            from.1,
            from.2 + dz * rate / length,
        )
    }
}

/// What a move of a box came to: where it ended and which ways something stopped it.
struct Collision {
    b: BoundingBox,
    hit_x: bool,
    hit_up: bool,
    hit_down: bool,
    hit_z: bool,
}

fn axis(v: Vec3, a: usize) -> f64 {
    v.coordinates()[a]
}

/// How far `b` may move along `a` (0 x, 1 y, 2 z) toward `distance` before a solid stops it.
fn clip(solids: &[BoundingBox], b: &BoundingBox, a: usize, distance: f64) -> f64 {
    if distance == 0.0 {
        return 0.0;
    }
    let mut d = distance;
    for s in solids {
        // Only what it overlaps across the other two axes is in its way.
        let across = (0..3).filter(|o| *o != a).all(|o| {
            !(axis(s.max, o) <= axis(b.min, o) + EPSILON
                || axis(s.min, o) >= axis(b.max, o) - EPSILON)
        });
        if !across {
            continue;
        }
        let (low, high) = (axis(b.min, a), axis(b.max, a));
        let (s_low, s_high) = (axis(s.min, a), axis(s.max, a));
        if d > 0.0 && s_low >= high - EPSILON {
            d = d.min(s_low - high);
        }
        if d < 0.0 && s_high <= low + EPSILON {
            d = d.max(s_high - low);
        }
    }
    d
}

/// Moves `b` by `want` against `solids`: up or down first, then the longer of the two sideways ways first.
fn collide(solids: &[BoundingBox], b: &BoundingBox, want: Vec3) -> Collision {
    let dy = clip(solids, b, 1, want.1);
    let mut moved = offset(b, 0.0, dy, 0.0);
    let (dx, dz);
    if want.0.abs() >= want.2.abs() {
        dx = clip(solids, &moved, 0, want.0);
        moved = offset(&moved, dx, 0.0, 0.0);
        dz = clip(solids, &moved, 2, want.2);
        moved = offset(&moved, 0.0, 0.0, dz);
    } else {
        dz = clip(solids, &moved, 2, want.2);
        moved = offset(&moved, 0.0, 0.0, dz);
        dx = clip(solids, &moved, 0, want.0);
        moved = offset(&moved, dx, 0.0, 0.0);
    }
    Collision {
        b: moved,
        hit_x: (dx - want.0).abs() > EPSILON,
        hit_up: want.1 > 0.0 && (dy - want.1).abs() > EPSILON,
        hit_down: want.1 < 0.0 && (dy - want.1).abs() > EPSILON,
        hit_z: (dz - want.2).abs() > EPSILON,
    }
}

/// Every block box a move of `b` by `want` (and a step `reach` up) could meet; a block in an unloaded chunk
/// is a full cube.
fn solids_around(
    shapes: &dyn BlockShapes,
    b: &BoundingBox,
    want: Vec3,
    reach: f64,
) -> Vec<BoundingBox> {
    let mut solids = Vec::new();
    let x0 = (b.min.0.min(b.min.0 + want.0) - EPSILON).floor() as i32;
    let x1 = (b.max.0.max(b.max.0 + want.0) + EPSILON).floor() as i32;
    let y0 = (b.min.1.min(b.min.1 + want.1) - EPSILON).floor() as i32 - 1;
    let y1 = (b.max.1.max(b.max.1 + want.1) + reach).floor() as i32;
    let z0 = (b.min.2.min(b.min.2 + want.2) - EPSILON).floor() as i32;
    let z1 = (b.max.2.max(b.max.2 + want.2) + EPSILON).floor() as i32;
    for x in x0..=x1 {
        for z in z0..=z1 {
            for y in y0..=y1 {
                match shapes.boxes(x, y, z) {
                    None => solids.push(BoundingBox {
                        min: Vec3(f64::from(x), f64::from(y), f64::from(z)),
                        max: Vec3(f64::from(x + 1), f64::from(y + 1), f64::from(z + 1)),
                    }),
                    Some(boxes) => solids.extend(boxes),
                }
            }
        }
    }
    solids
}

/// Moves `instance`'s body one tick; none when it has none.
pub fn step(platform: &dyn Platform, instance: &mut Instance) -> Option<Moved> {
    let anchor = instance.anchor().clone();
    let mover = instance.mover.as_mut()?;
    let s = mover.settings.clone();
    let world = anchor.world.clone();
    let half = s.width / 2.0;
    let middle = platform.blocks().get(
        world.clone(),
        anchor.x.floor() as i32,
        (anchor.y + s.height / 2.0).floor() as i32,
        anchor.z.floor() as i32,
    );
    mover.in_liquid = middle.is_some_and(|b| b.liquid);

    let mut v = mover.velocity;
    // Drag first, then gravity, then the walk: so a walk reaches exactly the speed it asks for.
    let keep = (1.0
        - if mover.in_liquid {
            s.liquid_drag
        } else {
            s.drag
        })
    .powf(SECONDS);
    v = v * keep;
    if !s.fly {
        let gravity = s.gravity
            * if mover.in_liquid {
                s.liquid_gravity
            } else {
                1.0
            };
        v = Vec3(v.0, (v.1 - gravity * SECONDS).max(-s.max_fall_speed), v.2);
    }
    if let Some(walk) = mover.walk {
        let rate = if s.fly || mover.on_ground {
            s.acceleration
        } else {
            s.air_acceleration
        } * SECONDS;
        v = if s.fly {
            approach(v, walk, rate)
        } else {
            approach_flat(v, walk, rate)
        };
    } else if mover.on_ground && !s.fly {
        v = approach_flat(v, Vec3::ZERO, s.friction * SECONDS);
    }

    let want = v * SECONDS;
    let mut b = BoundingBox {
        min: Vec3(anchor.x - half, anchor.y, anchor.z - half),
        max: Vec3(anchor.x + half, anchor.y + s.height, anchor.z + half),
    };
    let was_on_ground = mover.on_ground;
    let mut moved = Moved::default();
    mover.wall = None;
    if !s.blocks {
        b = offset(&b, want.0, want.1, want.2);
        mover.on_ground = false;
    } else {
        let reach = s.step_height + 1.0;
        let shapes = RegionShapes::around(
            platform.worlds(),
            &world,
            Vec3(
                b.min.0.min(b.min.0 + want.0) - 1.0,
                b.min.1.min(b.min.1 + want.1) - 1.0,
                b.min.2.min(b.min.2 + want.2) - 1.0,
            ),
            Vec3(
                b.max.0.max(b.max.0 + want.0) + 1.0,
                b.max.1.max(b.max.1 + want.1) + reach,
                b.max.2.max(b.max.2 + want.2) + 1.0,
            ),
        );
        let solids = solids_around(&shapes, &b, want, reach);
        if solids.iter().any(|x| overlaps(x, &shrink(&b))) {
            // Inside blocks (spawned there, or the world changed round it): it rises out rather than freezes.
            b = offset(&b, want.0, want.1.max(RISE * SECONDS), want.2);
            v = Vec3(v.0, v.1.max(0.0), v.2);
            mover.on_ground = false;
        } else {
            let mut result = collide(&solids, &b, want);
            // Stepping up: blocked sideways while on the ground, try it from a step higher.
            if s.step_height > 0.0
                && (was_on_ground || result.hit_down)
                && (result.hit_x || result.hit_z)
            {
                let up = clip(&solids, &b, 1, s.step_height);
                let raised = offset(&b, 0.0, up, 0.0);
                let across = collide(&solids, &raised, Vec3(want.0, 0.0, want.2));
                let drop = -(up + (-want.1).max(0.0));
                let down = clip(&solids, &across.b, 1, drop);
                let stepped = offset(&across.b, 0.0, down, 0.0);
                if flat_distance(&b, &stepped) > flat_distance(&b, &result.b) + EPSILON {
                    result = Collision {
                        b: stepped,
                        hit_x: across.hit_x,
                        hit_up: false,
                        hit_down: down > drop + EPSILON,
                        hit_z: false,
                    };
                }
            }
            b = result.b;
            let bounce = s.bounce;
            if result.hit_x {
                let normal = Vec3(-sign(want.0), 0.0, 0.0);
                moved.bumps.push(Bump {
                    normal,
                    speed: v.0.abs(),
                });
                mover.wall = Some(normal);
                v = Vec3(-v.0 * bounce, v.1, v.2);
            }
            if result.hit_z {
                let normal = Vec3(0.0, 0.0, -sign(want.2));
                moved.bumps.push(Bump {
                    normal,
                    speed: v.2.abs(),
                });
                mover.wall = Some(match mover.wall {
                    Some(w) => Vec3(w.0, 0.0, -sign(want.2)),
                    None => normal,
                });
                v = Vec3(v.0, v.1, -v.2 * bounce);
            }
            if result.hit_up {
                moved.bumps.push(Bump {
                    normal: Vec3(0.0, -1.0, 0.0),
                    speed: v.1.abs(),
                });
                v = Vec3(v.0, -v.1 * bounce, v.2);
            }
            if result.hit_down {
                if !was_on_ground {
                    moved.landed = Some(-v.1);
                }
                // A bounce too small to see would never stop.
                let up = -v.1 * bounce;
                v = Vec3(v.0, if up > MIN_BOUNCE { up } else { 0.0 }, v.2);
            }
            mover.on_ground = result.hit_down && v.1 <= 0.0;
        }
    }
    mover.velocity = v;
    let next = netherforge_platform::Location {
        x: (b.min.0 + b.max.0) / 2.0,
        y: b.min.1,
        z: (b.min.2 + b.max.2) / 2.0,
        ..anchor.clone()
    };
    if next.x != anchor.x || next.y != anchor.y || next.z != anchor.z {
        // Where its entities stand: a chunk whose entities aren't loaded would unload it under them.
        if platform.worlds().entities_loaded(next.clone()) {
            instance.set_anchor(next);
        } else if let Some(mover) = &mut instance.mover {
            mover.velocity = Vec3::ZERO;
        }
    }
    Some(moved)
}
