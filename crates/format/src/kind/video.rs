//! `videos/<id>/video.json`: a video screens play, which the editor converts
//! into map colours (`video.nfv`) and a sound (`audio.ogg`). Format's
//! `VideoFile` and `VideoValidator`: its size, rate and start are its fields'
//! rules; its end, source and converted files are checked here.

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use crate::document::{Context, Document};
use crate::json::format_number;
use crate::problem::{ProblemSink, codes};

pub const FILE_NAME: &str = "video.json";
/// The converted frames, beside the file.
pub const FRAMES: &str = "video.nfv";
/// The converted sound, all of it.
pub const AUDIO: &str = "audio.ogg";
/// The sound again in pieces, `audio/0.ogg`, …: the game starts a sound only at its beginning.
pub const AUDIO_CHUNKS: &str = "audio";
pub const AUDIO_CHUNK_SECONDS: i32 = 5;
pub const DEFAULT_FPS: i32 = 20;
pub const MAX_FPS: i32 = 20;

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct VideoFile {
    #[serde(rename = "$schema", skip_serializing_if = "Option::is_none")]
    pub schema: Option<String>,
    /// The video it's converted from, a file in its folder (`source.mp4`).
    pub source: String,
    /// Maps across the frames fill.
    #[schemars(range(min = 1, max = 16), extend("x-problem" = "screen.size"))]
    pub width: i32,
    /// Maps down.
    #[schemars(range(min = 1, max = 16), extend("x-problem" = "screen.size"))]
    pub height: i32,
    /// Frames a second: a server ticks 20 times a second, so 20 is the most.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 1, max = 20), extend("default" = 20, "x-problem" = "video.fps"))]
    pub fps: Option<i32>,
    /// How colours become map colours. Default Floyd–Steinberg.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dither: Option<Dither>,
    /// How the picture fills the maps when its shape differs. Default contain.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub fit: Option<VideoFit>,
    /// Where in the source to start, in seconds.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 0), extend("default" = 0, "x-problem" = "video.range"))]
    pub start: Option<f64>,
    /// Where to stop, in seconds; absent: at its end.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub end: Option<f64>,
    /// Whether its sound is kept, as the sound event `video.<id>`. Default true.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub audio: Option<bool>,
}

/// How a picture's colours become map colours (`map_colors`): each pixel the nearest one (`None`), or the error
/// of each pushed onto the pixels after it so areas average out to the right colour (`FloydSteinberg`, best for
/// photos and video, and a video's when it doesn't say), or a fixed pattern of thresholds (`Ordered`, which
/// doesn't crawl from one video frame to the next).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum Dither {
    None,
    #[default]
    FloydSteinberg,
    Ordered,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum VideoFit {
    /// All of it, as big as fits, with black bars.
    Contain,
    /// All the maps, the picture cut where it overflows.
    Cover,
    /// All the maps, the picture squashed or stretched.
    Stretch,
}

/// The path of piece `index` of a video's sound, in its folder.
pub fn chunk_file(index: u32) -> String {
    format!("{AUDIO_CHUNKS}/{index}.ogg")
}

/// Which piece `file` (a path in a video's folder) is, or `None` for any other file.
pub fn chunk_of(file: &str) -> Option<u32> {
    let name = file.strip_prefix(AUDIO_CHUNKS)?.strip_prefix('/')?;
    let index: u32 = name.strip_suffix(".ogg")?.parse().ok()?;
    (name == format!("{index}.ogg")).then_some(index)
}

/// A video's sound in the resource pack: `video.<id>`.
pub fn sound_key(id: &str) -> String {
    format!("video.{id}")
}

/// Piece `index` of it: `video.<id>.<index>`.
pub fn chunk_key(id: &str, index: u32) -> String {
    format!("video.{id}.{index}")
}

impl Document for VideoFile {
    const SCHEMA: &'static str = "../../.netherforge/schema/video.schema.json";

    fn set_schema(&mut self, schema: Option<String>) {
        self.schema = schema;
    }

    fn validate(&self, cx: &Context, sink: &mut ProblemSink) {
        if let Some(end) = self.end
            && end <= self.start.unwrap_or(0.0)
        {
            sink.report(
                codes::VIDEO_RANGE,
                format!(
                    "end is a time in seconds after start, not {}",
                    format_number(end)
                ),
                Some("$.end".into()),
            );
        }
        if !crate::names::is_relative_file(&self.source)
            || [FRAMES, AUDIO, FILE_NAME].contains(&self.source.as_str())
        {
            sink.report(
                codes::VIDEO_SOURCE,
                "source is the video file in the video's folder, like \"source.mp4\"",
                Some("$.source".into()),
            );
            return;
        }
        let Some(files) = cx.files else { return };
        let converted = files.contains(FRAMES);
        // One with neither its source nor its frames yet is being made: there's nothing to convert.
        if !converted && files.contains(&self.source) {
            sink.report(codes::VIDEO_NOT_CONVERTED, "The video hasn't been converted for screens, so it can't play: convert it in the editor", Some("$".into()));
        } else if converted && self.audio.unwrap_or(true) && !files.contains(AUDIO) {
            sink.report(
                codes::VIDEO_AUDIO_MISSING,
                format!("audio is on, but there's no {AUDIO}: convert it again in the editor, or set audio to false"),
                Some("$.audio".into()),
            );
        }
    }
}
