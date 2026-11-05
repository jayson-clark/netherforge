//! The clips playing on one instance, and what they do to its pose (Kotlin's `AnimationPlayer`).
//!
//! A clip owns the channels its tracks drive while it plays; everything else stays at the rest pose (the
//! authored transform, or what a script last set). Clips are applied in the order they were started, so the
//! newest wins where two drive the same channel. Additive clips come after all of those, each changing the
//! pose they leave (translation and rotation added, scale multiplied), so a squash plays on top of a walk.
//! When a `once` clip ends its channels fall back to rest; a `hold` clip keeps its last pose until stopped;
//! a `loop` clip never ends.
//!
//! Each play can run at its own speed, start part way in, override the clip's loop mode, and blend in: for
//! its first ticks its channels ease from the pose shown when it started into the clip, instead of jumping.

use std::rc::Rc;

use indexmap::IndexMap;
use netherforge_format::kind::centity::compiled::{CompiledAnimation, CompiledCentity};
use netherforge_format::kind::centity::{AnimationBlend, Channel, LoopMode, Transform};
use netherforge_format::math::Vec3;

struct Playing {
    /// The clip: its centity's animation at this index.
    centity: Rc<CompiledCentity>,
    index: usize,
    looped: LoopMode,
    speed: f64,
    blend: Option<Blend>,
    /// Seconds into the clip, as authored (speed already applied).
    time: f64,
    ended: bool,
    paused: bool,
}

impl Playing {
    fn clip(&self) -> &CompiledAnimation {
        &self.centity.animations[self.index]
    }
}

/// Easing in from `from` (the shown pose when the clip started) over `seconds`.
struct Blend {
    from: Vec<Transform>,
    seconds: f64,
    elapsed: f64,
}

/// How a play starts: Kotlin's `play`'s defaults are `Play::default()`.
#[derive(Clone, Debug)]
pub struct Play {
    /// Times its authored rate.
    pub speed: f64,
    /// Seconds in.
    pub from: f64,
    /// Its own loop mode when none.
    pub looped: Option<LoopMode>,
    pub blend_seconds: f64,
}

impl Default for Play {
    fn default() -> Play {
        Play {
            speed: 1.0,
            from: 0.0,
            looped: None,
            blend_seconds: 0.0,
        }
    }
}

#[derive(Default)]
pub struct AnimationPlayer {
    playing: IndexMap<String, Playing>,
    version: u64,
}

impl AnimationPlayer {
    pub fn active(&self) -> bool {
        !self.playing.is_empty()
    }

    /// Counts every change a script makes to the clips (started, stopped, paused, moved, sped up): what was
    /// worked out ahead from them no longer holds once it moves. Advancing doesn't count.
    pub fn version(&self) -> u64 {
        self.version
    }

    /// Starts `centity`'s animation `index`, restarting it if it was already playing, easing from `pose`
    /// (the pose shown now) when `play` blends.
    pub fn play(
        &mut self,
        centity: &Rc<CompiledCentity>,
        index: usize,
        play: Play,
        pose: Option<&[Transform]>,
    ) {
        let clip = &centity.animations[index];
        self.version += 1;
        self.playing.shift_remove(&clip.name);
        let blend = match pose {
            Some(pose) if play.blend_seconds > 0.0 => Some(Blend {
                from: pose.to_vec(),
                seconds: play.blend_seconds,
                elapsed: 0.0,
            }),
            _ => None,
        };
        let looped = play.looped.unwrap_or(clip.looped);
        self.playing.insert(
            clip.name.clone(),
            Playing {
                centity: centity.clone(),
                index,
                looped,
                speed: play.speed,
                blend,
                time: play.from,
                ended: false,
                paused: false,
            },
        );
    }

    pub fn stop(&mut self, name: &str) -> bool {
        let stopped = self.playing.shift_remove(name).is_some();
        if stopped {
            self.version += 1;
        }
        stopped
    }

    pub fn stop_all(&mut self) {
        if !self.playing.is_empty() {
            self.version += 1;
        }
        self.playing.clear();
    }

    pub fn is_playing(&self, name: &str) -> bool {
        self.playing.contains_key(name)
    }

    pub fn names(&self) -> Vec<String> {
        self.playing.keys().cloned().collect()
    }

    fn change(&mut self, name: &str, f: impl FnOnce(&mut Playing)) -> bool {
        let Some(entry) = self.playing.get_mut(name) else {
            return false;
        };
        f(entry);
        self.version += 1;
        true
    }

    /// Holds a clip where it is. False when it isn't playing.
    pub fn pause(&mut self, name: &str) -> bool {
        self.change(name, |e| e.paused = true)
    }

    pub fn resume(&mut self, name: &str) -> bool {
        self.change(name, |e| e.paused = false)
    }

    /// Where a playing clip is, in seconds within its length; none when it isn't playing.
    pub fn position(&self, name: &str) -> Option<f64> {
        self.playing
            .get(name)
            .map(|e| e.clip().resolve(e.time, e.looped).time)
    }

    /// Moves a playing clip to `seconds` in. A hold clip that had ended plays on from there.
    pub fn seek(&mut self, name: &str, seconds: f64) -> bool {
        self.change(name, |e| {
            e.time = seconds;
            e.ended = false;
        })
    }

    pub fn speed(&self, name: &str) -> Option<f64> {
        self.playing.get(name).map(|e| e.speed)
    }

    pub fn set_speed(&mut self, name: &str, speed: f64) -> bool {
        self.change(name, |e| e.speed = speed)
    }

    /// Moves every clip on by `seconds`: the ones that reached their end on this step. `once` clips are
    /// dropped as they end. A blend's easing counts in real time, whatever the clip's speed or whether it's
    /// paused.
    pub fn advance(&mut self, seconds: f64) -> Vec<String> {
        let mut ended = Vec::new();
        self.playing.retain(|_, entry| {
            if let Some(blend) = &mut entry.blend {
                blend.elapsed += seconds;
            }
            if entry.ended || entry.paused {
                return true;
            }
            entry.time += seconds * entry.speed;
            if entry.clip().resolve(entry.time, entry.looped).ended {
                ended.push(entry.clip().name.clone());
                if entry.looped == LoopMode::Once {
                    return false;
                }
                entry.ended = true;
            }
            true
        });
        ended
    }

    /// Writes every playing clip's channels over `pose`, which starts as a copy of the rest pose.
    pub fn apply(&self, pose: &mut [Transform]) {
        self.apply_at(pose, 0.0);
    }

    /// What `apply` will write after `advance` by `seconds`, without moving anything: each clip that far on
    /// (a `once` clip that will have ended no longer writes), each blend that much further in.
    pub fn apply_ahead(&self, pose: &mut [Transform], seconds: f64) {
        self.apply_at(pose, seconds);
    }

    fn apply_at(&self, pose: &mut [Transform], ahead: f64) {
        for blend in [AnimationBlend::Replace, AnimationBlend::Additive] {
            for entry in self.playing.values().filter(|e| e.clip().blend == blend) {
                apply_one(pose, ahead, entry);
            }
        }
    }
}

fn apply_one(pose: &mut [Transform], ahead: f64, entry: &Playing) {
    let time = if entry.ended || entry.paused {
        entry.time
    } else {
        entry.time + ahead * entry.speed
    };
    let position = entry.clip().resolve(time, entry.looped);
    if position.ended && entry.looped == LoopMode::Once && ahead > 0.0 {
        return;
    }
    let weight = entry.blend.as_ref().map_or(1.0, |b| {
        if b.seconds <= 0.0 {
            1.0
        } else {
            ((b.elapsed + ahead) / b.seconds).clamp(0.0, 1.0)
        }
    });
    let additive = entry.clip().blend == AnimationBlend::Additive;
    for track in &entry.clip().tracks {
        let Some(at) = pose.get(track.node_index) else {
            continue;
        };
        let mut value = track.sample(position.time);
        if weight < 1.0 {
            // An additive clip eases in from no change at all; a replacing one from the pose shown when it
            // started.
            let from = if additive {
                identity(track.channel)
            } else {
                entry
                    .blend
                    .as_ref()
                    .and_then(|b| b.from.get(track.node_index))
                    .map_or(identity(track.channel), |t| channel_of(t, track.channel))
            };
            value = blend(track.channel, from, value, weight);
        }
        pose[track.node_index] = at.posed(track.channel, value, entry.clip().blend);
    }
}

fn identity(channel: Channel) -> Vec3 {
    match channel {
        Channel::Scale => Vec3::ONE,
        _ => Vec3::ZERO,
    }
}

fn channel_of(transform: &Transform, channel: Channel) -> Vec3 {
    match channel {
        Channel::Translation => transform.translation_or_default(),
        Channel::Rotation => transform.rotation_or_default(),
        Channel::Scale => transform.scale_or_default(),
    }
}

/// From `from` toward `to` by `t`; rotations the short way round each axis, as clips interpolate them.
fn blend(channel: Channel, from: Vec3, to: Vec3, t: f64) -> Vec3 {
    let step = |a: f64, b: f64| {
        if channel != Channel::Rotation {
            return a + (b - a) * t;
        }
        // Kotlin's `%` keeps the dividend's sign, as Rust's does.
        let mut delta = (b - a) % 360.0;
        if delta > 180.0 {
            delta -= 360.0;
        }
        if delta <= -180.0 {
            delta += 360.0;
        }
        a + delta * t
    };
    Vec3(step(from.0, to.0), step(from.1, to.1), step(from.2, to.2))
}
