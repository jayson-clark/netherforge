//! `cutscenes/<id>.json`: a camera path a script plays for a player. Format's
//! `CutsceneFile`, `CutsceneKind` and `CutsceneValidator`: the length, pitch,
//! a cue's duration and event name, and how many keys a track holds are
//! their fields' rules; what depends on the length is checked here. It
//! compiles to a `CompiledCutscene` (format's `CutsceneCompiler`).

use std::collections::HashSet;

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use crate::animation::{Easing, Keyframe, by_time};
use crate::document::{Context, Document};
use crate::json::{child_path, format_number};
use crate::problem::{ProblemSink, codes};

/// The longest a cutscene may be, in seconds.
pub const MAX_LENGTH: f64 = 3600.0;
/// Keys on one track, and cues in one cutscene, at most.
pub const MAX_KEYS: usize = 4096;
/// How far from the origin a position may be: the world's own limit.
pub const MAX_COORDINATE: f64 = 3.0e7;

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct CutsceneFile {
    #[serde(rename = "$schema", skip_serializing_if = "Option::is_none")]
    pub schema: Option<String>,
    /// Seconds it lasts. Default: the last key's or cue's time.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(max = 3600), extend("exclusiveMinimum" = 0, "x-problem" = "cutscene.length"))]
    pub length: Option<f64>,
    /// Whether the player may end it early by sneaking. Default false.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub skippable: Option<bool>,
    #[serde(default)]
    pub camera: Camera,
    /// What happens when, in time order.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    #[schemars(length(max = 4096), extend("x-problem" = "cutscene.key-limit"))]
    pub cues: Vec<Cue>,
}

/// The camera's two tracks; each needs a key.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct Camera {
    /// Where it is, in world coordinates (or from the `origin` a script plays it at).
    #[serde(default)]
    #[schemars(length(max = 4096), extend("x-problem" = "cutscene.key-limit"))]
    pub position: Vec<Keyframe>,
    /// Which way it looks, in degrees.
    #[serde(default)]
    #[schemars(length(max = 4096), extend("x-problem" = "cutscene.key-limit"))]
    pub rotation: Vec<RotationKey>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct RotationKey {
    pub time: f64,
    /// 0 faces south and 90 west, as the game's yaw does; it turns the short way round.
    pub yaw: f64,
    /// -90 (straight up) to 90 (straight down).
    #[schemars(range(min = -90, max = 90), extend("x-problem" = "cutscene.pitch"))]
    pub pitch: f64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub easing: Option<Easing>,
}

/// Something that happens at `time`: an event scripts hear, a line of text, or both.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct Cue {
    pub time: f64,
    /// `event.name` of the cutscene's `cue` event.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("pattern" = crate::names::ID_PATTERN, "x-problem" = "cutscene.cue-event", "x-rule" = crate::names::ID_RULE))]
    pub event: Option<String>,
    /// MiniMessage shown as the subtitle while the cue lasts.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(extend("x-minimessage" = true))]
    pub text: Option<String>,
    /// Seconds the text stays. Default 3.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(max = 3600), extend("exclusiveMinimum" = 0, "default" = 3, "x-problem" = "cutscene.cue-duration"))]
    pub duration: Option<f64>,
}

impl CutsceneFile {
    /// Seconds it lasts: its `length`, or the last key's or cue's time.
    pub fn length(&self) -> f64 {
        self.length.unwrap_or_else(|| {
            let times = self.camera.position.iter().map(|k| k.time);
            let times = times.chain(self.camera.rotation.iter().map(|k| k.time));
            times
                .chain(self.cues.iter().map(|c| c.time))
                .fold(0.0, f64::max)
        })
    }
}

fn key_times(
    path: &str,
    times: impl Iterator<Item = f64>,
    length: Option<f64>,
    sink: &mut ProblemSink,
) {
    let mut seen = HashSet::new();
    for (i, time) in times.enumerate() {
        let at = format!("{path}[{i}].time");
        if !(time >= 0.0 && length.is_none_or(|l| time <= l)) {
            let message = match length {
                Some(l) => format!(
                    "A key's time must be from 0 to the cutscene's length ({} seconds), not {}",
                    format_number(l),
                    format_number(time)
                ),
                None => "A key's time can't be before 0".into(),
            };
            sink.report(codes::CUTSCENE_KEY_TIME, message, Some(at));
        } else if !seen.insert(time.to_bits()) {
            sink.report(
                codes::CUTSCENE_KEY_DUPLICATE,
                format!("Another key is already at {} seconds", format_number(time)),
                Some(at),
            );
        }
    }
}

impl Document for CutsceneFile {
    const SCHEMA: &'static str = "../.netherforge/schema/cutscene.schema.json";

    fn set_schema(&mut self, schema: Option<String>) {
        self.schema = schema;
    }

    /// Keys in time order; cues keep a time's order (the order they fire in).
    fn canonical(&self) -> Self {
        CutsceneFile {
            camera: Camera {
                position: by_time(&self.camera.position, |k| k.time),
                rotation: by_time(&self.camera.rotation, |k| k.time),
            },
            cues: by_time(&self.cues, |c| c.time),
            ..self.clone()
        }
    }

    fn compile(&self, id: &str, _cx: &Context) -> Option<Box<dyn crate::document::Compiled>> {
        crate::document::compiled(compile(id, self))
    }

    fn validate(&self, _: &Context, sink: &mut ProblemSink) {
        let length = self.length();
        // A length it says is its field's rule; one from its keys is checked here.
        let fits = length > 0.0 && length <= MAX_LENGTH;
        if self.length.is_none() && !fits {
            sink.report(
                codes::CUTSCENE_LENGTH,
                "The keys all sit at time 0, so the cutscene is no time long: set `length`, or key later times",
                Some("$.length".into()),
            );
        }
        let length = fits.then_some(length);
        let camera = &self.camera;
        if camera.position.is_empty() {
            sink.report(
                codes::CUTSCENE_TRACK_EMPTY,
                "The camera has no position keys, so it has nowhere to be",
                Some("$.camera.position".into()),
            );
        }
        if camera.rotation.is_empty() {
            sink.report(
                codes::CUTSCENE_TRACK_EMPTY,
                "The camera has no rotation keys, so it has no way to look",
                Some("$.camera.rotation".into()),
            );
        }
        key_times(
            "$.camera.position",
            camera.position.iter().map(|k| k.time),
            length,
            sink,
        );
        key_times(
            "$.camera.rotation",
            camera.rotation.iter().map(|k| k.time),
            length,
            sink,
        );
        for (i, key) in camera.position.iter().enumerate() {
            if key
                .value
                .coordinates()
                .iter()
                .any(|c| c.abs() > MAX_COORDINATE)
            {
                sink.report(
                    codes::CUTSCENE_POSITION,
                    format!(
                        "A camera position may be at most {} blocks from the origin",
                        format_number(MAX_COORDINATE)
                    ),
                    Some(format!("$.camera.position[{i}].value")),
                );
            }
        }
        for (i, cue) in self.cues.iter().enumerate() {
            let at = format!("$.cues[{i}]");
            if cue.event.is_none() && cue.text.is_none() {
                sink.report(
                    codes::CUTSCENE_CUE_EMPTY,
                    "A cue needs an `event`, `text`, or both",
                    Some(at.clone()),
                );
            }
            if cue.duration.is_some() && cue.text.is_none() {
                sink.report(
                    codes::CUTSCENE_CUE_DURATION,
                    "`duration` is how long `text` stays, and this cue has none",
                    Some(child_path(&at, "duration")),
                );
            }
            if let Some(length) = length
                && !(cue.time >= 0.0 && cue.time <= length)
            {
                sink.report(
                    codes::CUTSCENE_KEY_TIME,
                    format!(
                        "A cue's time must be from 0 to the cutscene's length ({} seconds)",
                        format_number(length)
                    ),
                    Some(child_path(&at, "time")),
                );
            }
        }
    }
}

/// A cutscene ready to play or draw: keys in time order, its length decided,
/// cues in time order (those at one time in the file's).
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct CompiledCutscene {
    pub id: String,
    /// Seconds, always more than 0.
    pub length: f64,
    /// Whether the player may end it early by sneaking, when a script says nothing.
    pub skippable: bool,
    pub path: CameraPath,
    pub cues: Vec<CompiledCue>,
}

impl CompiledCutscene {
    /// Where the camera is at `time` seconds.
    pub fn pose_at(&self, time: f64) -> CameraPose {
        self.path.at(time)
    }
}

/// A cue as it plays: its duration its schema's default when it says none.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct CompiledCue {
    pub time: f64,
    pub event: Option<String>,
    pub text: Option<String>,
    /// Seconds its text stays.
    pub duration: f64,
}

/// Where the camera is and which way it looks: yaw and pitch in degrees, the
/// yaw not wrapped (a turn through 180 stays continuous).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct CameraPose {
    pub position: crate::math::Vec3,
    pub yaw: f64,
    pub pitch: f64,
}

/// The camera's two tracks, each in time order and never empty, sampled as a
/// centity's channels are (`animation::segment`), a yaw turning the short way
/// round: shared by the server and the editor, so a preview can't disagree.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct CameraPath {
    pub positions: Vec<Keyframe>,
    pub rotations: Vec<RotationKey>,
}

impl CameraPath {
    pub fn at(&self, time: f64) -> CameraPose {
        let p = crate::animation::segment(&self.positions, |k| k.time, |k| k.easing, time);
        let (a, b) = (self.positions[p.from].value, self.positions[p.to].value);
        let position = if p.from == p.to { a } else { a + (b - a) * p.t };
        let r = crate::animation::segment(&self.rotations, |k| k.time, |k| k.easing, time);
        let (a, b) = (&self.rotations[r.from], &self.rotations[r.to]);
        let (yaw, pitch) = match r.from == r.to {
            true => (a.yaw, a.pitch),
            false => (
                a.yaw + crate::math::shortest_delta(a.yaw, b.yaw) * r.t,
                a.pitch + (b.pitch - a.pitch) * r.t,
            ),
        };
        CameraPose {
            position,
            yaw,
            pitch,
        }
    }
}

/// `file` (the cutscene `id`) as it plays, for a file whose checks found no errors.
pub fn compile(id: &str, file: &CutsceneFile) -> CompiledCutscene {
    assert!(
        !file.camera.position.is_empty() && !file.camera.rotation.is_empty(),
        "cutscene \"{id}\" needs a key on each track"
    );
    CompiledCutscene {
        id: id.to_owned(),
        length: file.length(),
        skippable: file.skippable.unwrap_or(false),
        path: CameraPath {
            positions: by_time(&file.camera.position, |k| k.time),
            rotations: by_time(&file.camera.rotation, |k| k.time),
        },
        cues: by_time(&file.cues, |c| c.time)
            .iter()
            .map(crate::resolve::resolved::<Cue, CompiledCue>)
            .collect(),
    }
}
