//! Converting a project's video (`videos/<id>/video.json` and the source file it
//! names) into what screens play: `video.nfv`, the frames in the game's map
//! colours ([nfv]), and its sound as `audio.ogg` and five-second pieces in
//! `audio/` (format's `VideoFile`).
//!
//! The server can't decode video, so the editor does, once: ffmpeg (from the
//! user's setting, or `PATH`) scales the source to the video's maps and rate
//! and hands back raw RGBA frames, which are dithered into the palette of the
//! project's Minecraft version (the dev server's game data export, `mapColors`)
//! and written a frame at a time. ffmpeg reads a file the project holds, so only
//! a trusted project converts (`commands::video`).

pub mod nfv;
pub mod palette;

use std::io::{BufRead, BufReader, Read};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::error::{Context, Error, ErrorCode, Result, bail};
use crate::fs::{ProjectRoot, atomic};
use palette::{Dither, Palette};

/// A video's file, as far as converting it goes (format's `VideoFile`; unknown keys are format's to refuse).
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VideoSettings {
    pub source: String,
    pub width: u16,
    pub height: u16,
    pub fps: Option<u16>,
    pub dither: Option<String>,
    pub fit: Option<String>,
    pub start: Option<f64>,
    pub end: Option<f64>,
    pub audio: Option<bool>,
}

impl VideoSettings {
    pub fn fps(&self) -> u16 {
        self.fps.unwrap_or(20)
    }

    pub fn audio(&self) -> bool {
        self.audio.unwrap_or(true)
    }

    fn fit_code(&self) -> u8 {
        match self.fit.as_deref() {
            Some("cover") => 1,
            Some("stretch") => 2,
            _ => 0,
        }
    }
}

/// How far a conversion has got.
#[derive(Debug, Clone, PartialEq, Serialize, specta::Type, tauri_specta::Event)]
#[serde(rename_all = "camelCase")]
#[tauri_specta(event_name = "video://convert-progress")]
pub struct ConvertProgress {
    /// The video's folder (`videos/trailer`).
    pub folder: String,
    /// `"audio"`, `"frames"` or `"writing"`.
    pub stage: String,
    /// Frames converted so far.
    pub done: u32,
    /// Frames there will be, as far as the source's length says; 0 when unknown.
    pub total: u32,
}

/// What a conversion made.
#[derive(Debug, Clone, PartialEq, Serialize, specta::Type)]
#[serde(rename_all = "camelCase")]
pub struct VideoConverted {
    pub frames: u32,
    pub seconds: f64,
    /// Whether it has a sound: false when the file says so, or the source has none.
    pub audio: bool,
    /// Bytes of `video.nfv`.
    pub bytes: u64,
}

/// What a video's converted frames say about themselves, against its file and source now.
#[derive(Debug, Clone, PartialEq, Serialize, specta::Type)]
#[serde(rename_all = "camelCase")]
pub struct VideoInfo {
    /// Whether `video.nfv` is there and reads.
    pub converted: bool,
    pub columns: u16,
    pub rows: u16,
    pub fps: u16,
    pub frames: u32,
    pub audio: bool,
    /// Bytes of `video.nfv`.
    pub bytes: u64,
    /// Whether the source is in the folder.
    pub source_present: bool,
    /// Whether the source differs from what was converted (null when either isn't there to compare).
    pub source_changed: Option<bool>,
}

pub const FILE: &str = "video.json";
pub const FRAMES: &str = "video.nfv";
pub const AUDIO: &str = "audio.ogg";
pub const CHUNKS: &str = "audio";
/// Seconds a piece of `audio/` lasts (format's `VideoFile.AUDIO_CHUNK_SECONDS`).
pub const CHUNK_SECONDS: u32 = 5;

/// ffmpeg: the user's setting when it names one, else the first on `PATH`.
pub fn find_ffmpeg(setting: &str) -> Option<PathBuf> {
    let setting = setting.trim();
    if !setting.is_empty() {
        let path = PathBuf::from(setting);
        return path.is_file().then_some(path);
    }
    let names: &[&str] = if cfg!(windows) {
        &["ffmpeg.exe"]
    } else {
        &["ffmpeg"]
    };
    crate::platform::path_dirs()
        .into_iter()
        .flat_map(|dir| names.iter().map(move |name| dir.join(name)))
        .find(|candidate| candidate.is_file())
}

/// The game's map colours from cached game data (`mapColors`), or None when the export has none.
pub fn palette_from_game_data(json: &str) -> Option<Palette> {
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct Colors {
        map_colors: Option<Vec<i64>>,
    }
    let colors: Colors = serde_json::from_str(json).ok()?;
    let palette = Palette::new(
        &colors
            .map_colors?
            .into_iter()
            .map(|c| c as u32)
            .collect::<Vec<_>>(),
    );
    (!palette.is_empty()).then_some(palette)
}

/// A video's folder, checked: `videos/…/<id>` holding its `video.json`.
fn folder_of(root: &ProjectRoot, folder: &str) -> Result<PathBuf> {
    // A path that isn't one is that, whatever it names.
    let path = root.resolve(folder)?;
    if folder.split('/').next() != Some("videos") || folder.split('/').count() < 2 {
        bail!(Invalid, "{folder} isn't a video's folder");
    }
    if !path.join(FILE).is_file() {
        bail!(NotFound, "{folder} has no {FILE}");
    }
    Ok(path)
}

fn settings_of(dir: &Path, folder: &str) -> Result<VideoSettings> {
    let text = std::fs::read_to_string(dir.join(FILE))
        .context(|| format!("Couldn't read {folder}/{FILE}"))?;
    serde_json::from_str(&text).map_err(|e| {
        Error::new(
            ErrorCode::Invalid,
            format!("{folder}/{FILE} can't be read: {e}"),
        )
    })
}

/// The source's SHA-256.
fn hash_file(path: &Path) -> Result<[u8; 32]> {
    let mut file =
        std::fs::File::open(path).context(|| format!("Couldn't read {}", path.display()))?;
    let mut hasher = Sha256::new();
    let mut chunk = vec![0u8; 1 << 20];
    loop {
        let n = file.read(&mut chunk)?;
        if n == 0 {
            break;
        }
        hasher.update(&chunk[..n]);
    }
    Ok(hasher.finalize().into())
}

/// What the converted frames of the video in [folder] say, against its source now.
pub fn info(root: &ProjectRoot, folder: &str) -> Result<VideoInfo> {
    let dir = folder_of(root, folder)?;
    let settings = settings_of(&dir, folder)?;
    let frames = dir.join(FRAMES);
    let source = root
        .resolve(&format!("{folder}/{}", settings.source))
        .ok()
        .filter(|p| p.is_file());
    let header = nfv::Header::read(&frames);
    let source_changed = match (&header, &source) {
        (Some(h), Some(s)) => Some(hash_file(s)? != h.source_sha256),
        _ => None,
    };
    Ok(VideoInfo {
        converted: header.is_some(),
        columns: header.as_ref().map_or(0, |h| h.columns),
        rows: header.as_ref().map_or(0, |h| h.rows),
        fps: header.as_ref().map_or(0, |h| h.fps),
        frames: header.as_ref().map_or(0, |h| h.frames),
        audio: header.as_ref().is_some_and(|h| h.audio),
        bytes: std::fs::metadata(&frames).map(|m| m.len()).unwrap_or(0),
        source_present: source.is_some(),
        source_changed,
    })
}

/// The `-vf` that scales a source to [width] by [height] pixels as [fit] says, at [fps].
pub fn filter(width: usize, height: usize, fit: u8, fps: u16) -> String {
    let scaled = match fit {
        // Cover: as big as covers the maps, cut to them.
        1 => format!(
            "scale={width}:{height}:force_original_aspect_ratio=increase:flags=area,crop={width}:{height}"
        ),
        // Stretch: squashed to them.
        2 => format!("scale={width}:{height}:flags=area"),
        // Contain: as big as fits, black bars where it doesn't reach.
        _ => format!(
            "scale={width}:{height}:force_original_aspect_ratio=decrease:flags=area,pad={width}:{height}:(ow-iw)/2:(oh-ih)/2:color=black"
        ),
    };
    format!("{scaled},setsar=1,fps={fps}")
}

/// `-ss`/`-t` for the part of the source to keep, before `-i`.
fn range_args(settings: &VideoSettings) -> Vec<String> {
    let mut args = Vec::new();
    let start = settings.start.unwrap_or(0.0).max(0.0);
    if start > 0.0 {
        args.extend(["-ss".to_string(), format!("{start}")]);
    }
    if let Some(end) = settings.end.filter(|end| *end > start) {
        args.extend(["-t".to_string(), format!("{}", end - start)]);
    }
    args
}

/// Seconds from a line of ffmpeg's like `  Duration: 00:01:23.45, start: …`.
pub fn duration_of(line: &str) -> Option<f64> {
    let rest = line.trim().strip_prefix("Duration:")?.trim();
    let time = rest.split(',').next()?.trim();
    let mut parts = time.split(':');
    let (h, m, s) = (
        parts.next()?.parse::<f64>().ok()?,
        parts.next()?.parse::<f64>().ok()?,
        parts.next()?.parse::<f64>().ok()?,
    );
    Some(h * 3600.0 + m * 60.0 + s)
}

/// Converts the video in [folder] with [ffmpeg] against [palette], reporting [progress] as it goes.
pub fn convert(
    root: &ProjectRoot,
    folder: &str,
    ffmpeg: &Path,
    mut palette: Palette,
    progress: &mut dyn FnMut(ConvertProgress),
) -> Result<VideoConverted> {
    let dir = folder_of(root, folder)?;
    let settings = settings_of(&dir, folder)?;
    if !(1..=16).contains(&settings.width) || !(1..=16).contains(&settings.height) {
        bail!(
            Invalid,
            "A video is 1 to 16 maps a side: fix {folder}/{FILE} first"
        );
    }
    if !(1..=20).contains(&settings.fps()) {
        bail!(
            Invalid,
            "A video is 1 to 20 frames a second: fix {folder}/{FILE} first"
        );
    }
    let dither = Dither::parse(settings.dither.as_deref()).ok_or_else(|| {
        Error::new(
            ErrorCode::Invalid,
            format!("{folder}/{FILE} names a dither there isn't"),
        )
    })?;
    let source = root.resolve(&format!("{folder}/{}", settings.source))?;
    if !source.is_file() {
        bail!(
            NotFound,
            "{folder} has no {}: put the video there first",
            settings.source
        );
    }
    let source_sha256 = hash_file(&source)?;
    let mut report = |stage: &str, done: u32, total: u32| {
        progress(ConvertProgress {
            folder: folder.to_string(),
            stage: stage.to_string(),
            done,
            total,
        })
    };

    // Sound first: whether there is any goes in the frames' header.
    report("audio", 0, 0);
    let audio = settings.audio() && convert_audio(ffmpeg, &source, &dir, &settings)?;
    if !audio {
        let _ = std::fs::remove_file(dir.join(AUDIO));
        let _ = std::fs::remove_dir_all(dir.join(CHUNKS));
    }

    let width = settings.width as usize * nfv::TILE;
    let height = settings.height as usize * nfv::TILE;
    let fps = settings.fps();
    let header = nfv::Header {
        columns: settings.width,
        rows: settings.height,
        fps,
        frames: 0,
        key_interval: fps * 2,
        dither: dither.code(),
        fit: settings.fit_code(),
        start_ms: (settings.start.unwrap_or(0.0).max(0.0) * 1000.0) as u32,
        end_ms: settings.end.map_or(0, |end| (end * 1000.0) as u32),
        audio,
        source_sha256,
        palette_hash: palette.hash(),
    };
    let target = dir.join(FRAMES);
    let mut writer = nfv::Writer::new(header, &target)?;
    let mut child = Command::new(ffmpeg)
        .args(["-nostdin", "-hide_banner", "-loglevel", "info"])
        .args(range_args(&settings))
        .arg("-i")
        .arg(&source)
        .args([
            "-an",
            "-vf",
            &filter(width, height, settings.fit_code(), fps),
            "-f",
            "rawvideo",
            "-pix_fmt",
            "rgba",
            "-",
        ])
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|e| {
            Error::new(
                ErrorCode::Unavailable,
                format!("Couldn't run ffmpeg ({}): {e}", ffmpeg.display()),
            )
        })?;
    // ffmpeg's own words, for its length and for what went wrong.
    let stderr = child.stderr.take().expect("piped");
    let (lines_tx, lines_rx) = std::sync::mpsc::channel::<String>();
    let reader = std::thread::spawn(move || {
        for line in BufReader::new(stderr)
            .lines()
            .map_while(std::result::Result::ok)
        {
            if lines_tx.send(line).is_err() {
                break;
            }
        }
    });
    let mut stdout = child.stdout.take().expect("piped");
    let mut frame = vec![0u8; width * height * 4];
    let mut total = 0u32;
    let mut said = Vec::new();
    loop {
        for line in lines_rx.try_iter() {
            if total == 0
                && let Some(seconds) = duration_of(&line)
            {
                let start = settings.start.unwrap_or(0.0).max(0.0);
                let end = settings.end.unwrap_or(seconds).min(seconds);
                total = ((end - start).max(0.0) * fps as f64).ceil() as u32;
            }
            said.push(line);
        }
        match stdout.read_exact(&mut frame) {
            Ok(()) => {}
            Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => break,
            Err(e) => return Err(e).context(|| "Couldn't read ffmpeg's frames".to_string()),
        }
        writer.add(palette.dither(&frame, width, height, dither))?;
        let done = writer.frames() as u32;
        if done.is_multiple_of(fps as u32) {
            report("frames", done, total.max(done));
        }
    }
    let status = child.wait()?;
    let _ = reader.join();
    said.extend(lines_rx.try_iter());
    if !status.success() || writer.frames() == 0 {
        let why = said
            .iter()
            .rev()
            .take(4)
            .rev()
            .cloned()
            .collect::<Vec<_>>()
            .join(" ");
        bail!(
            Invalid,
            "ffmpeg couldn't convert {}: {}",
            settings.source,
            if why.is_empty() {
                "it gave no frames".to_string()
            } else {
                why
            }
        );
    }
    report("writing", writer.frames() as u32, writer.frames() as u32);
    let written = writer.finish(&target)?;
    Ok(VideoConverted {
        frames: written.frames,
        seconds: written.frames as f64 / fps as f64,
        audio,
        bytes: std::fs::metadata(&target).map(|m| m.len()).unwrap_or(0),
    })
}

/// The sound as `audio.ogg` and its pieces in `audio/`, each made aside and moved into place. False when the
/// source has no sound.
fn convert_audio(
    ffmpeg: &Path,
    source: &Path,
    dir: &Path,
    settings: &VideoSettings,
) -> Result<bool> {
    let whole = atomic::sibling_temp(&dir.join(AUDIO));
    let pieces = atomic::sibling_temp(&dir.join(CHUNKS));
    let result = (|| -> Result<bool> {
        let encode = |out: &[&str], to: &Path| -> Result<std::process::Output> {
            Command::new(ffmpeg)
                .args(["-nostdin", "-hide_banner", "-loglevel", "error", "-y"])
                .args(range_args(settings))
                .arg("-i")
                .arg(source)
                .args([
                    "-vn",
                    "-ac",
                    "2",
                    "-ar",
                    "44100",
                    "-c:a",
                    "libvorbis",
                    "-q:a",
                    "4",
                ])
                .args(out)
                .arg(to)
                .stdin(Stdio::null())
                .output()
                .map_err(|e| {
                    Error::new(
                        ErrorCode::Unavailable,
                        format!("Couldn't run ffmpeg ({}): {e}", ffmpeg.display()),
                    )
                })
        };
        let output = encode(&["-f", "ogg"], &whole)?;
        if !output.status.success() {
            let said = String::from_utf8_lossy(&output.stderr);
            // A source with no sound at all is a silent video, not a failure.
            if said.contains("does not contain any stream")
                || said.contains("Output file does not contain")
                || said.contains("matches no streams")
            {
                return Ok(false);
            }
            bail!(
                Invalid,
                "ffmpeg couldn't convert the sound: {}",
                said.trim()
            );
        }
        std::fs::create_dir_all(&pieces)?;
        let output = encode(
            &[
                "-f",
                "segment",
                "-segment_time",
                &CHUNK_SECONDS.to_string(),
                "-reset_timestamps",
                "1",
                "-segment_format",
                "ogg",
            ],
            &pieces.join("%d.ogg"),
        )?;
        if !output.status.success() {
            bail!(
                Invalid,
                "ffmpeg couldn't cut the sound into pieces: {}",
                String::from_utf8_lossy(&output.stderr).trim()
            );
        }
        let _ = std::fs::remove_dir_all(dir.join(CHUNKS));
        atomic::rename_replacing(&pieces, &dir.join(CHUNKS))
            .context(|| "Couldn't put the sound's pieces in place".to_string())?;
        atomic::rename_replacing(&whole, &dir.join(AUDIO))
            .context(|| "Couldn't put the sound in place".to_string())?;
        Ok(true)
    })();
    let _ = std::fs::remove_file(&whole);
    let _ = std::fs::remove_dir_all(&pieces);
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn filters_fit_the_maps_three_ways() {
        assert_eq!(
            filter(256, 128, 0, 20),
            "scale=256:128:force_original_aspect_ratio=decrease:flags=area,pad=256:128:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1,fps=20"
        );
        assert!(filter(256, 128, 1, 10).contains("crop=256:128"));
        assert_eq!(
            filter(128, 128, 2, 5),
            "scale=128:128:flags=area,setsar=1,fps=5"
        );
    }

    #[test]
    fn reads_the_sources_length_from_ffmpeg() {
        assert_eq!(
            duration_of("  Duration: 00:01:23.50, start: 0.000000, bitrate: 1 kb/s"),
            Some(83.5)
        );
        assert_eq!(duration_of("Stream #0:0: Video: h264"), None);
    }

    #[test]
    fn the_palette_comes_from_the_game_datas_map_colours() {
        let p = palette_from_game_data(
            r#"{ "minecraft": "26.3", "mapColors": [0, 0, 0, 0, -16777216, -1], "schema": 3 }"#,
        )
        .unwrap();
        assert_eq!(p.rgb(5), Some(0xFF_FFFF));
        assert!(palette_from_game_data(r#"{ "minecraft": "26.3", "schema": 3 }"#).is_none());
    }

    #[test]
    fn a_videos_folder_is_checked() {
        let tmp = tempfile::tempdir().unwrap();
        let root = ProjectRoot::new(tmp.path()).unwrap();
        assert_eq!(
            folder_of(&root, "menus/shop").unwrap_err().code,
            ErrorCode::Invalid
        );
        assert_eq!(
            folder_of(&root, "videos/clip").unwrap_err().code,
            ErrorCode::NotFound
        );
        std::fs::create_dir_all(tmp.path().join("videos/clip")).unwrap();
        std::fs::write(
            tmp.path().join("videos/clip/video.json"),
            r#"{ "source": "a.mp4", "width": 1, "height": 1 }"#,
        )
        .unwrap();
        let status = info(&root, "videos/clip").unwrap();
        assert!(!status.converted && !status.source_present && status.source_changed.is_none());
    }

    /// A made-up palette (no game's): transparent, then black, white, red, grey.
    fn palette() -> Palette {
        Palette::new(&[
            0,
            0,
            0,
            0,
            0xFF00_0000,
            0xFFFF_FFFF,
            0xFFFF_0000,
            0xFF80_8080,
        ])
    }

    fn project(source: &str) -> (tempfile::TempDir, ProjectRoot) {
        let tmp = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(tmp.path().join("videos/clip")).unwrap();
        std::fs::write(
            tmp.path().join("videos/clip/video.json"),
            format!(r#"{{ "source": "{source}", "width": 2, "height": 1, "fps": 10, "dither": "none" }}"#),
        )
        .unwrap();
        std::fs::write(tmp.path().join("videos/clip").join(source), b"a video").unwrap();
        let root = ProjectRoot::new(tmp.path()).unwrap();
        (tmp, root)
    }

    #[test]
    fn converts_frames_and_sound_through_ffmpeg() {
        let (tmp, root) = project("source.mp4");
        let mut seen = Vec::new();
        let made = convert(
            &root,
            "videos/clip",
            &crate::testing::fake_server(),
            palette(),
            &mut |p| seen.push(p),
        )
        .unwrap();
        assert_eq!(made.frames, 6);
        assert!(made.audio);
        let dir = tmp.path().join("videos/clip");
        assert!(
            dir.join(AUDIO).is_file()
                && dir.join("audio/0.ogg").is_file()
                && dir.join("audio/1.ogg").is_file()
        );
        let header = nfv::Header::read(&dir.join(FRAMES)).unwrap();
        assert_eq!(
            (
                header.columns,
                header.rows,
                header.fps,
                header.frames,
                header.key_interval
            ),
            (2, 1, 10, 6, 20)
        );
        assert!(header.audio);
        assert_eq!(header.palette_hash, palette().hash());
        // Its length came from ffmpeg's words: 0.3 seconds at 10 a second.
        assert!(seen.iter().any(|p| p.stage == "writing" && p.done == 6));
        assert_eq!(seen.first().map(|p| p.stage.as_str()), Some("audio"));
        let status = info(&root, "videos/clip").unwrap();
        assert!(status.converted && status.source_present);
        assert_eq!(status.source_changed, Some(false));
        std::fs::write(dir.join("source.mp4"), b"another video").unwrap();
        assert_eq!(
            info(&root, "videos/clip").unwrap().source_changed,
            Some(true)
        );
        // Nothing half-written is left beside it.
        let leftovers: Vec<_> = std::fs::read_dir(&dir)
            .unwrap()
            .flatten()
            .filter(|e| e.file_name().to_string_lossy().starts_with('.'))
            .collect();
        assert!(leftovers.is_empty(), "{leftovers:?}");
    }

    #[test]
    fn a_source_without_sound_converts_silent() {
        let (tmp, root) = project("silent.mp4");
        let made = convert(
            &root,
            "videos/clip",
            &crate::testing::fake_server(),
            palette(),
            &mut |_| {},
        )
        .unwrap();
        assert!(!made.audio);
        assert!(!tmp.path().join("videos/clip").join(AUDIO).exists());
        assert!(
            !nfv::Header::read(&tmp.path().join("videos/clip").join(FRAMES))
                .unwrap()
                .audio
        );
    }

    #[test]
    fn finds_ffmpeg_where_the_setting_says() {
        let tmp = tempfile::tempdir().unwrap();
        let fake = tmp.path().join("my-ffmpeg");
        std::fs::write(&fake, b"").unwrap();
        assert_eq!(find_ffmpeg(&fake.to_string_lossy()), Some(fake));
        assert_eq!(
            find_ffmpeg(&tmp.path().join("missing").to_string_lossy()),
            None
        );
    }
}
