//! A converted video's frames, `videos/<id>/video.nfv` (`docs/format/video.md` is the layout): a 72-byte header,
//! an index of where each frame starts (a big-endian `u64` each), then the frames, each a record of its kind
//! (`KEY`: every pixel; `DELTA`: the rectangles that changed since the frame before), its length (`u32`) and its
//! payload, zlib-deflated. A delta's rectangles are per map, so a change in one corner of a big screen is one map's
//! corner, not a rectangle across the whole screen.
//!
//! The one definition of the layout: the editor writes it (its converter streams frames through [`record`]), the
//! runtime reads it a frame at a time ([`Header::parse`], [`index`], [`unpack`], [`apply`]), and [`encode`] writes a
//! whole video in memory, for tests.

use std::io::{Read, Write};

use flate2::Compression;
use flate2::read::ZlibDecoder;
use flate2::write::ZlibEncoder;

use crate::kind::video::{Dither, VideoFit};

pub const MAGIC: &[u8; 4] = b"NFV1";
pub const VERSION: u16 = 1;
pub const HEADER_SIZE: usize = 72;
pub const KEY: u8 = 0;
pub const DELTA: u8 = 1;
/// A map's side in pixels.
pub const TILE: usize = 128;
/// No frame of a 16 by 16 map video is bigger, deflated or not.
pub const MAX_FRAME: usize = 16 * 16 * TILE * TILE + 1024 * 1024;

/// What a converted video was made from and as, at the top of its file.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Header {
    pub columns: u16,
    pub rows: u16,
    pub fps: u16,
    pub frames: u32,
    pub key_interval: u16,
    /// The [`Dither`] it was converted with, by its place in the enum.
    pub dither: u8,
    /// The [`VideoFit`] it was converted with, by its place in the enum.
    pub fit: u8,
    pub start_ms: u32,
    pub end_ms: u32,
    pub audio: bool,
    /// The source file's SHA-256: what tells a video whose source changed since.
    pub source_sha256: [u8; 32],
    /// Which palette its colours were matched against (`map_colors::Palette::hash`).
    pub palette_hash: u32,
}

impl Header {
    pub fn width(&self) -> usize {
        self.columns as usize * TILE
    }

    pub fn height(&self) -> usize {
        self.rows as usize * TILE
    }

    /// Seconds it lasts.
    pub fn length(&self) -> f64 {
        f64::from(self.frames) / f64::from(self.fps.max(1))
    }

    /// The key frame at or before `index`: where drawing frame `index` starts from.
    pub fn key_before(&self, index: usize) -> usize {
        index - index % self.key_interval.max(1) as usize
    }

    /// How it was dithered; an ordinal this doesn't know reads as none.
    pub fn dither(&self) -> Dither {
        match self.dither {
            1 => Dither::FloydSteinberg,
            2 => Dither::Ordered,
            _ => Dither::None,
        }
    }

    /// How it was fitted; an ordinal this doesn't know reads as contain.
    pub fn fit(&self) -> VideoFit {
        match self.fit {
            1 => VideoFit::Cover,
            2 => VideoFit::Stretch,
            _ => VideoFit::Contain,
        }
    }

    pub fn to_bytes(&self) -> [u8; HEADER_SIZE] {
        let mut out = [0u8; HEADER_SIZE];
        out[0..4].copy_from_slice(MAGIC);
        out[4..6].copy_from_slice(&VERSION.to_be_bytes());
        out[6..8].copy_from_slice(&self.columns.to_be_bytes());
        out[8..10].copy_from_slice(&self.rows.to_be_bytes());
        out[10..12].copy_from_slice(&self.fps.to_be_bytes());
        out[12..16].copy_from_slice(&self.frames.to_be_bytes());
        out[16..18].copy_from_slice(&self.key_interval.to_be_bytes());
        out[18] = self.dither;
        out[19] = self.fit;
        out[20..24].copy_from_slice(&self.start_ms.to_be_bytes());
        out[24..28].copy_from_slice(&self.end_ms.to_be_bytes());
        out[28] = self.audio as u8;
        out[32..64].copy_from_slice(&self.source_sha256);
        out[64..68].copy_from_slice(&self.palette_hash.to_be_bytes());
        out
    }

    /// A header read back from a file's first bytes, or why it isn't one this reads (words the server logs).
    pub fn parse(bytes: &[u8]) -> Result<Header, String> {
        if bytes.len() < HEADER_SIZE || &bytes[0..4] != MAGIC {
            return Err("it isn't a converted video (no NFV1 at its start)".into());
        }
        let u16_at = |at: usize| u16::from_be_bytes([bytes[at], bytes[at + 1]]);
        let u32_at = |at: usize| u32::from_be_bytes(bytes[at..at + 4].try_into().expect("4 bytes"));
        let version = u16_at(4);
        if version != VERSION {
            return Err(format!(
                "it's a version {version} video file, and this reads version {VERSION}: convert it again"
            ));
        }
        let header = Header {
            columns: u16_at(6),
            rows: u16_at(8),
            fps: u16_at(10),
            frames: u32_at(12),
            key_interval: u16_at(16),
            dither: bytes[18],
            fit: bytes[19],
            start_ms: u32_at(20),
            end_ms: u32_at(24),
            audio: bytes[28] != 0,
            source_sha256: bytes[32..64].try_into().expect("32 bytes"),
            palette_hash: u32_at(64),
        };
        let sane = (1..=16).contains(&header.columns)
            && (1..=16).contains(&header.rows)
            && (1..=20).contains(&header.fps)
            && header.frames <= i32::MAX as u32
            && header.key_interval >= 1;
        if !sane {
            return Err(format!(
                "its header doesn't make sense ({} by {} maps, {} fps, {} frames)",
                header.columns, header.rows, header.fps, header.frames
            ));
        }
        Ok(header)
    }
}

/// Where each frame's record starts, from the index after the header (`bytes`, `frames` × 8 of them).
pub fn index(bytes: &[u8]) -> Vec<u64> {
    bytes
        .chunks_exact(8)
        .map(|b| u64::from_be_bytes(b.try_into().expect("8 bytes")))
        .collect()
}

/// A frame's payload deflated, as its record holds it.
pub fn pack(payload: &[u8]) -> Vec<u8> {
    let mut encoder = ZlibEncoder::new(Vec::new(), Compression::default());
    encoder.write_all(payload).expect("writing to memory");
    encoder.finish().expect("writing to memory")
}

/// A record's payload inflated, at most [`MAX_FRAME`] bytes.
pub fn unpack(packed: &[u8]) -> Result<Vec<u8>, String> {
    let mut out = Vec::with_capacity(packed.len() * 4);
    let read = ZlibDecoder::new(packed)
        .take(MAX_FRAME as u64 + 1)
        .read_to_end(&mut out);
    match read {
        Err(_) => Err("a frame is cut short".into()),
        Ok(_) if out.len() > MAX_FRAME => Err("a frame inflates past what a video can hold".into()),
        Ok(_) => Ok(out),
    }
}

/// A frame's record: its kind, its packed length and its packed payload.
pub fn record(kind: u8, packed: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(5 + packed.len());
    out.push(kind);
    out.extend_from_slice(&(packed.len() as u32).to_be_bytes());
    out.extend_from_slice(packed);
    out
}

/// The rectangles of `after` that differ from `before`, one round each map's changes: `x, y, width, height`
/// (screen pixels, big-endian u16s) then the rectangle's pixels row by row.
pub fn delta(before: &[u8], after: &[u8], width: usize, rows: u16, columns: u16) -> Vec<u8> {
    let mut out = Vec::new();
    for row in 0..rows as usize {
        for column in 0..columns as usize {
            let (x0, y0) = (column * TILE, row * TILE);
            let (mut left, mut top, mut right, mut bottom) =
                (usize::MAX, usize::MAX, 0usize, 0usize);
            let mut any = false;
            for y in y0..y0 + TILE {
                let line = y * width;
                for x in x0..x0 + TILE {
                    if before[line + x] != after[line + x] {
                        any = true;
                        left = left.min(x);
                        right = right.max(x);
                        top = top.min(y);
                        bottom = bottom.max(y);
                    }
                }
            }
            if !any {
                continue;
            }
            let (w, h) = (right - left + 1, bottom - top + 1);
            for v in [left, top, w, h] {
                out.extend_from_slice(&(v as u16).to_be_bytes());
            }
            for y in top..=bottom {
                out.extend_from_slice(&after[y * width + left..y * width + left + w]);
            }
        }
    }
    out
}

/// Draws an inflated frame's pixels: `row(x, y, pixels)` for each run of a row, in video pixels from its top left.
/// A key frame is `width` by `height` pixels; a delta's rectangle that runs past its end is an error.
pub fn apply(
    header: &Header,
    key: bool,
    payload: &[u8],
    mut row: impl FnMut(usize, usize, &[u8]),
) -> Result<(), String> {
    if key {
        let w = header.width();
        for (y, line) in payload.chunks_exact(w).take(header.height()).enumerate() {
            row(0, y, line);
        }
        return Ok(());
    }
    let mut at = 0;
    while payload.len() - at >= 8 {
        let u16_at = |i: usize| u16::from_be_bytes([payload[i], payload[i + 1]]) as usize;
        let (rx, ry, rw, rh) = (u16_at(at), u16_at(at + 2), u16_at(at + 4), u16_at(at + 6));
        at += 8;
        if rw * rh > payload.len() - at {
            return Err("a frame's rectangle runs past its end".into());
        }
        for py in 0..rh {
            row(rx, ry + py, &payload[at..at + rw]);
            at += rw;
        }
    }
    Ok(())
}

/// A whole video in memory: `frames`, each the whole screen's map colours row by row, a key frame every
/// `header.key_interval` and the changes in between. `header.frames` is counted from them.
pub fn encode(header: &Header, frames: &[Vec<u8>]) -> Vec<u8> {
    let mut header = header.clone();
    header.frames = frames.len() as u32;
    let records: Vec<Vec<u8>> = frames
        .iter()
        .enumerate()
        .map(|(i, frame)| {
            assert_eq!(
                frame.len(),
                header.width() * header.height(),
                "frame {i}'s size"
            );
            if i % header.key_interval.max(1) as usize == 0 {
                record(KEY, &pack(frame))
            } else {
                let changes = delta(
                    &frames[i - 1],
                    frame,
                    header.width(),
                    header.rows,
                    header.columns,
                );
                record(DELTA, &pack(&changes))
            }
        })
        .collect();
    let mut out = header.to_bytes().to_vec();
    let mut offset = (HEADER_SIZE + frames.len() * 8) as u64;
    for r in &records {
        out.extend_from_slice(&offset.to_be_bytes());
        offset += r.len() as u64;
    }
    for r in records {
        out.extend_from_slice(&r);
    }
    out
}
