//! What an effect spawns on each tick of its timeline (format's
//! `EffectSampler`): the one place that decides, shared by the server (which
//! sends the spawns) and the editor's preview (which draws them).
//!
//! Randomness is Kotlin's `Random(seed)` (`crate::random`), so a seed gives the
//! same points as format's Kotlin did. The server draws a fresh seed per play;
//! the editor a fixed one, so its preview doesn't shimmer between edits.
//!
//! Everything is in effect space: +Z forward, +Y up (Minecraft's yaw 0,
//! south). An `EffectFrame` puts it in the world.

use std::collections::HashMap;
use std::f64::consts::PI;

use serde::Serialize;

use super::compiled::{CompiledEffect, CompiledEmitter};
use super::{Distribution, EmitterShape, Motion};
use crate::game::ParticleData;
use crate::item::ItemDef;
use crate::math::{Matrix4, Vec3, degrees_to_radians};
use crate::random::Random;

const EPSILON: f64 = 1e-9;
const UP: Vec3 = Vec3(0.0, 1.0, 0.0);

/// One of the game's particle spawns: what its spawn packet carries. With
/// `count` above 0 the game scatters that many around `position` with
/// Gaussian `offset` (the spread) and random speed `speed`; with 0 it spawns
/// one moving along `offset` at `speed`.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct EffectSpawn {
    pub emitter: String,
    pub particle: String,
    pub position: Vec3,
    pub count: i32,
    /// The spread when `count` is above 0, the direction when it's 0.
    pub offset: Vec3,
    pub speed: f64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub data: Option<SpawnData>,
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub force: bool,
}

/// The options a spawn carries, by its particle's data kind; colours are `0xRRGGBB`.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(
    tag = "type",
    rename_all = "snake_case",
    rename_all_fields = "camelCase"
)]
pub enum SpawnData {
    Dust {
        color: i32,
        size: f64,
    },
    DustTransition {
        color: i32,
        to_color: i32,
        size: f64,
    },
    Color {
        color: i32,
    },
    /// A canonical block state.
    Block {
        state: String,
    },
    Item {
        item: Box<ItemDef>,
    },
}

pub struct EffectSampler {
    effect: CompiledEffect,
    random: Random,
    /// Each rate emitter's fraction of a point carried to the next tick, by its name.
    accumulators: HashMap<String, f64>,
    tick: i32,
    finished: bool,
}

impl EffectSampler {
    pub fn new(effect: CompiledEffect, seed: i64) -> EffectSampler {
        EffectSampler {
            effect,
            random: Random::new(seed),
            accumulators: HashMap::new(),
            tick: 0,
            finished: false,
        }
    }

    pub fn effect(&self) -> &CompiledEffect {
        &self.effect
    }

    /// The timeline tick the next `step` emits, from 0; it wraps when looping.
    pub fn tick(&self) -> i32 {
        self.tick
    }

    /// Whether an effect that doesn't loop has emitted its last tick.
    pub fn finished(&self) -> bool {
        self.finished
    }

    /// The spawns for the current tick, in effect space, then on to the next.
    /// Looping, the tick after the last is tick 0 again and rate emitters start
    /// from nothing; otherwise the sampler is finished and spawns nothing more.
    pub fn step(&mut self, looped: bool) -> Vec<EffectSpawn> {
        if self.finished {
            return Vec::new();
        }
        let duration = self.effect.duration;
        if self.tick >= duration {
            // Only after `redefine` shortened the timeline under a playing effect.
            if !looped {
                self.finished = true;
                return Vec::new();
            }
            self.tick %= duration;
            self.accumulators.clear();
        }
        let mut spawns = Vec::new();
        let emitters = std::mem::take(&mut self.effect.emitters);
        for emitter in &emitters {
            self.emit(emitter, self.tick, &mut spawns);
        }
        self.effect.emitters = emitters;
        self.tick += 1;
        if self.tick >= duration {
            if looped {
                self.tick = 0;
                self.accumulators.clear();
            } else {
                self.finished = true;
            }
        }
        spawns
    }

    /// Swaps the definition on reload. The tick carries over (one that doesn't
    /// loop and is now past its duration finishes at its next step, a looping
    /// one wraps), and emitters keep their rate's carry by name.
    pub fn redefine(&mut self, effect: CompiledEffect) {
        self.accumulators
            .retain(|name, _| effect.emitters.iter().any(|e| &e.name == name));
        self.effect = effect;
    }

    fn emit(&mut self, emitter: &CompiledEmitter, t: i32, out: &mut Vec<EffectSpawn>) {
        if t < emitter.start || t >= emitter.end {
            return;
        }
        let time = f64::from(t);
        let n = match emitter.burst {
            Some(burst) => {
                let pulse = t == emitter.start
                    || emitter
                        .every
                        .is_some_and(|every| (t - emitter.start) % every == 0);
                if pulse { burst } else { 0 }
            }
            None => {
                let rate = match &emitter.rate_curve {
                    Some(curve) => curve.at(time),
                    None => emitter.rate.unwrap_or(0.0),
                };
                let carried = self.accumulators.get(&emitter.name).copied().unwrap_or(0.0);
                let total = carried + rate;
                let whole = total.floor();
                self.accumulators
                    .insert(emitter.name.clone(), total - whole);
                whole as i32
            }
        };
        if n <= 0 {
            return;
        }
        let radius = emitter.radius_curve.as_ref().map_or(0.0, |c| c.at(time));
        let speed = emitter.speed_curve.at(time);
        let data = data_of(emitter, time);
        let spin = (emitter.spin != 0.0).then(|| {
            let mut m = Matrix4::IDENTITY;
            m.rotate_y(degrees_to_radians(emitter.spin * f64::from(t)));
            m
        });
        for i in 0..n {
            let mut local = self.place(emitter, i, n, radius);
            if let Some(spin) = &spin {
                local = spin.transform_direction(local);
            }
            let turned = emitter.rotation.transform_direction(local);
            let (count, offset) = match emitter.motion {
                Motion::Random => (emitter.count, emitter.spread),
                Motion::Outward => (0, away(emitter, turned)),
                Motion::Inward => (0, away(emitter, turned) * -1.0),
                Motion::Direction => (
                    0,
                    emitter.rotation.transform_direction(
                        emitter.direction.expect("a direction emitter's direction"),
                    ),
                ),
            };
            out.push(EffectSpawn {
                emitter: emitter.name.clone(),
                particle: emitter.particle.clone(),
                position: emitter.offset + turned,
                count,
                offset,
                speed,
                data: data.clone(),
                force: emitter.force,
            });
        }
    }

    /// Point `i` of `n` on the emitter's shape, in shape space (before spin and rotation).
    fn place(&mut self, emitter: &CompiledEmitter, i: i32, n: i32, radius: f64) -> Vec3 {
        let even = emitter.distribution == Distribution::Even;
        let r = &mut self.random;
        match &emitter.shape {
            EmitterShape::Point => Vec3::ZERO,
            EmitterShape::Line { to } => {
                *to * match (even, n) {
                    (false, _) => r.next_double(),
                    (true, 1) => 0.5,
                    (true, _) => f64::from(i) / f64::from(n - 1),
                }
            }
            EmitterShape::Ring { .. } => {
                let angle = match even {
                    true => 2.0 * PI * f64::from(i) / f64::from(n),
                    false => r.next_double() * 2.0 * PI,
                };
                Vec3(radius * angle.cos(), 0.0, radius * angle.sin())
            }
            EmitterShape::Disc { .. } => {
                let angle = r.next_double() * 2.0 * PI;
                let distance = radius * r.next_double().sqrt();
                Vec3(distance * angle.cos(), 0.0, distance * angle.sin())
            }
            EmitterShape::Sphere { surface, .. } => {
                if even {
                    fibonacci(i, n) * radius
                } else if *surface == Some(true) {
                    unit_sphere(r) * radius
                } else {
                    let direction = unit_sphere(r);
                    direction * (radius * r.next_double().cbrt())
                }
            }
            EmitterShape::Box { size, surface } => match surface {
                Some(true) => box_surface(r, *size),
                _ => Vec3(
                    (r.next_double() - 0.5) * size.0,
                    (r.next_double() - 0.5) * size.1,
                    (r.next_double() - 0.5) * size.2,
                ),
            },
        }
    }
}

/// The unit vector from the shape's centre to a point, or the shape's up when it's the centre.
fn away(emitter: &CompiledEmitter, turned: Vec3) -> Vec3 {
    let length = (turned.0 * turned.0 + turned.1 * turned.1 + turned.2 * turned.2).sqrt();
    if length < EPSILON {
        return emitter.rotation.transform_direction(UP);
    }
    turned * (1.0 / length)
}

/// A direction, uniformly at random.
fn unit_sphere(r: &mut Random) -> Vec3 {
    let y = 2.0 * r.next_double() - 1.0;
    let angle = r.next_double() * 2.0 * PI;
    let across = (1.0 - y * y).sqrt();
    Vec3(across * angle.cos(), y, across * angle.sin())
}

/// On the box's faces, uniform by area: a pair of faces by their area, a side, then a point on it.
fn box_surface(r: &mut Random, size: Vec3) -> Vec3 {
    let (x, y, z) = (size.1 * size.2, size.0 * size.2, size.0 * size.1);
    let pick = r.next_double() * (x + y + z);
    let side = if r.next_double() < 0.5 { -0.5 } else { 0.5 };
    let u = r.next_double() - 0.5;
    let v = r.next_double() - 0.5;
    if pick < x {
        Vec3(side * size.0, u * size.1, v * size.2)
    } else if pick < x + y {
        Vec3(u * size.0, side * size.1, v * size.2)
    } else {
        Vec3(u * size.0, v * size.1, side * size.2)
    }
}

fn data_of(emitter: &CompiledEmitter, time: f64) -> Option<SpawnData> {
    Some(match emitter.kind {
        ParticleData::None | ParticleData::Other => return None,
        ParticleData::Dust => SpawnData::Dust {
            color: emitter.color_curve.at(time),
            size: emitter.size_curve.at(time),
        },
        ParticleData::DustTransition => SpawnData::DustTransition {
            color: emitter.color_curve.at(time),
            to_color: emitter.to_color,
            size: emitter.size_curve.at(time),
        },
        ParticleData::Color => SpawnData::Color {
            color: emitter.color_curve.at(time),
        },
        ParticleData::Block => SpawnData::Block {
            state: emitter
                .block_state
                .as_ref()
                .expect("a block emitter's state")
                .to_string(),
        },
        ParticleData::Item => SpawnData::Item {
            item: Box::new(emitter.item.clone().expect("an item emitter's item")),
        },
    })
}

/// Point `i` of a Fibonacci lattice of `n` on the unit sphere: near-even spacing for any `n`.
pub fn fibonacci(i: i32, n: i32) -> Vec3 {
    // The golden angle, in radians: each point turns by it from the last.
    let golden_angle = PI * (3.0 - 5.0_f64.sqrt());
    let y = 1.0 - 2.0 * (f64::from(i) + 0.5) / f64::from(n);
    let across = (1.0 - y * y).sqrt();
    let angle = golden_angle * f64::from(i);
    Vec3(across * angle.cos(), y, across * angle.sin())
}

/// Where an effect is in the world: effect space to world is
/// `T(origin) · Ry(-yaw) · Rx(pitch) · S(scale)`; directions skip `T` and `S`.
/// `yaw` and `pitch` are Minecraft's degrees (yaw 0 faces south, +Z; 90 west;
/// positive pitch looks down). `scale` multiplies distances (positions,
/// spreads, speeds) but not a dust's size, which is the particle's own.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct EffectFrame {
    pub origin: Vec3,
    pub yaw: f64,
    pub pitch: f64,
    pub scale: f64,
}

impl EffectFrame {
    pub fn at(origin: Vec3) -> EffectFrame {
        EffectFrame {
            origin,
            yaw: 0.0,
            pitch: 0.0,
            scale: 1.0,
        }
    }

    fn turn(&self) -> Matrix4 {
        let mut m = Matrix4::IDENTITY;
        m.rotate_y(degrees_to_radians(-self.yaw))
            .rotate_x(degrees_to_radians(self.pitch));
        m
    }

    pub fn position(&self, p: Vec3) -> Vec3 {
        self.origin + self.turn().transform_direction(p * self.scale)
    }

    pub fn direction(&self, d: Vec3) -> Vec3 {
        self.turn().transform_direction(d)
    }

    /// `spawn` in world space. A spread stays on the world's axes (the game's
    /// Gaussian is per axis, so it can't turn) and scales; a direction turns.
    pub fn to_world(&self, spawn: &EffectSpawn) -> EffectSpawn {
        EffectSpawn {
            position: self.position(spawn.position),
            offset: match spawn.count > 0 {
                true => spawn.offset * self.scale,
                false => self.direction(spawn.offset),
            },
            speed: spawn.speed * self.scale,
            ..spawn.clone()
        }
    }
}
