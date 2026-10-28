//! A converted video's frames, `videos/<id>/video.nfv`, as the plugin reads
//! them (`screen/Nfv.kt`; `docs/format/video.md` is the layout): a 72-byte
//! header, an index of where each frame starts, then the frames, each a key
//! frame (every pixel) or the rectangles that changed since the one before,
//! zlib-deflated. Rectangles are per map, so a change in one corner of a big
//! screen sends one map's corner, not a rectangle across the whole screen.
//!
//! Frames go to a body file as they come (a long video needn't fit in memory);
//! [Writer::finish] then writes the header, the index and the body into the
//! video's file, through a temporary file renamed into place.

use std::fs::File;
use std::io::{BufReader, BufWriter, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

use flate2::Compression;
use flate2::write::ZlibEncoder;

use crate::error::{Context, Result};
use crate::fs::atomic;

pub const MAGIC: &[u8; 4] = b"NFV1";
pub const VERSION: u16 = 1;
pub const HEADER_SIZE: usize = 72;
pub const KEY: u8 = 0;
pub const DELTA: u8 = 1;
/// A map's side in pixels.
pub const TILE: usize = 128;

/// What a converted video was made from and as, at the top of its file.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Header {
    pub columns: u16,
    pub rows: u16,
    pub fps: u16,
    pub frames: u32,
    pub key_interval: u16,
    pub dither: u8,
    pub fit: u8,
    pub start_ms: u32,
    pub end_ms: u32,
    pub audio: bool,
    /// The source file's SHA-256: what tells a video whose source changed since.
    pub source_sha256: [u8; 32],
    /// Which palette its colours were matched against ([super::palette::Palette::hash]).
    pub palette_hash: u32,
}

impl Header {
    pub fn width(&self) -> usize {
        self.columns as usize * TILE
    }

    pub fn height(&self) -> usize {
        self.rows as usize * TILE
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

    /// A header read back, or None when [bytes] isn't one this writes.
    pub fn parse(bytes: &[u8]) -> Option<Self> {
        if bytes.len() < HEADER_SIZE || &bytes[0..4] != MAGIC {
            return None;
        }
        let u16_at = |at: usize| u16::from_be_bytes([bytes[at], bytes[at + 1]]);
        let u32_at = |at: usize| u32::from_be_bytes(bytes[at..at + 4].try_into().unwrap());
        if u16_at(4) != VERSION {
            return None;
        }
        Some(Self {
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
            source_sha256: bytes[32..64].try_into().unwrap(),
            palette_hash: u32_at(64),
        })
    }

    /// The header of the converted video at [path], or None when it isn't there or isn't one.
    pub fn read(path: &Path) -> Option<Self> {
        let mut bytes = [0u8; HEADER_SIZE];
        File::open(path).ok()?.read_exact(&mut bytes).ok()?;
        Self::parse(&bytes)
    }
}

/// Writes a video's frames as they come, then the whole file.
pub struct Writer {
    header: Header,
    body_path: PathBuf,
    /// The frames written so far; taken when [finish] closes it.
    body: Option<BufWriter<File>>,
    offsets: Vec<u64>,
    written: u64,
    previous: Option<Vec<u8>>,
}

impl Writer {
    /// A writer for frames of [header]'s size (its `frames` is counted as they're added), its body kept beside [target].
    pub fn new(header: Header, target: &Path) -> Result<Self> {
        let body_path = atomic::sibling_temp(target);
        let body =
            Some(BufWriter::new(File::create(&body_path).context(|| {
                "Couldn't write the converted frames".to_string()
            })?));
        Ok(Self {
            header,
            body_path,
            body,
            offsets: Vec::new(),
            written: 0,
            previous: None,
        })
    }

    /// Adds the next frame: [pixels] the whole screen's map colours, row by row.
    pub fn add(&mut self, pixels: Vec<u8>) -> Result<()> {
        assert_eq!(pixels.len(), self.header.width() * self.header.height());
        let index = self.offsets.len();
        let key = index.is_multiple_of(self.header.key_interval as usize);
        let (kind, payload) = match (&self.previous, key) {
            (Some(before), false) => (
                DELTA,
                delta(
                    before,
                    &pixels,
                    self.header.width(),
                    self.header.rows,
                    self.header.columns,
                ),
            ),
            _ => (KEY, pixels.clone()),
        };
        let mut encoder = ZlibEncoder::new(Vec::new(), Compression::default());
        encoder.write_all(&payload)?;
        let packed = encoder.finish()?;
        self.offsets.push(self.written);
        let body = self
            .body
            .as_mut()
            .expect("a writer is written to until it finishes");
        body.write_all(&[kind])?;
        body.write_all(&(packed.len() as u32).to_be_bytes())?;
        body.write_all(&packed)?;
        self.written += 5 + packed.len() as u64;
        self.previous = Some(pixels);
        Ok(())
    }

    pub fn frames(&self) -> usize {
        self.offsets.len()
    }

    /// Writes the video's file at [target]: header, index, body.
    pub fn finish(mut self, target: &Path) -> Result<Header> {
        if let Some(mut body) = self.body.take() {
            body.flush()?;
        }
        self.header.frames = self.offsets.len() as u32;
        let temp = atomic::sibling_temp(target);
        let result = (|| -> Result<()> {
            let mut out = BufWriter::new(
                File::create(&temp).context(|| "Couldn't write the converted video".to_string())?,
            );
            out.write_all(&self.header.to_bytes())?;
            let start = (HEADER_SIZE + self.offsets.len() * 8) as u64;
            for offset in &self.offsets {
                out.write_all(&(start + offset).to_be_bytes())?;
            }
            let mut body = BufReader::new(File::open(&self.body_path)?);
            body.seek(SeekFrom::Start(0))?;
            std::io::copy(&mut body, &mut out)?;
            out.flush()?;
            Ok(())
        })();
        let _ = std::fs::remove_file(&self.body_path);
        if let Err(e) = result {
            let _ = std::fs::remove_file(&temp);
            return Err(e);
        }
        atomic::rename_replacing(&temp, target)
            .context(|| "Couldn't put the converted video in place".to_string())?;
        Ok(self.header.clone())
    }
}

impl Drop for Writer {
    fn drop(&mut self) {
        // A conversion that failed part way leaves no body behind.
        self.body.take();
        let _ = std::fs::remove_file(&self.body_path);
    }
}

/// The rectangles of [after] that differ from [before], one round each map's changes: `x, y, width, height`
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

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::read::ZlibDecoder;

    fn header(columns: u16, rows: u16) -> Header {
        Header {
            columns,
            rows,
            fps: 20,
            frames: 0,
            key_interval: 3,
            dither: 1,
            fit: 0,
            start_ms: 0,
            end_ms: 0,
            audio: true,
            source_sha256: [7; 32],
            palette_hash: 42,
        }
    }

    #[test]
    fn a_header_reads_back() {
        let h = header(4, 3);
        assert_eq!(Header::parse(&h.to_bytes()), Some(h.clone()));
        let mut wrong = h.to_bytes();
        wrong[0] = b'X';
        assert_eq!(Header::parse(&wrong), None);
    }

    #[test]
    fn a_delta_is_a_rectangle_round_each_maps_changes() {
        let width = 256;
        let before = vec![0u8; width * 128];
        let mut after = before.clone();
        after[5 * width + 2] = 9;
        after[6 * width + 3] = 9;
        after[10 * width + 200] = 8;
        let d = delta(&before, &after, width, 1, 2);
        // Two rectangles: (2,5) 2 by 2 in the left map, (200,10) 1 by 1 in the right.
        assert_eq!(&d[0..8], &[0, 2, 0, 5, 0, 2, 0, 2]);
        assert_eq!(&d[8..12], &[9, 0, 0, 9]);
        assert_eq!(&d[12..20], &[0, 200, 0, 10, 0, 1, 0, 1]);
        assert_eq!(d[20], 8);
        assert_eq!(d.len(), 21);
        assert!(delta(&before, &before, width, 1, 2).is_empty());
    }

    #[test]
    fn frames_are_key_frames_at_the_interval_and_changes_between() {
        let dir = tempfile::tempdir().unwrap();
        let target = dir.path().join("video.nfv");
        let mut writer = Writer::new(header(1, 1), &target).unwrap();
        for i in 0..5u8 {
            let mut frame = vec![4u8; 128 * 128];
            frame[0] = 4 + i;
            writer.add(frame).unwrap();
        }
        let written = writer.finish(&target).unwrap();
        assert_eq!(written.frames, 5);
        let bytes = std::fs::read(&target).unwrap();
        assert_eq!(Header::parse(&bytes).unwrap().frames, 5);
        let offset = |i: usize| {
            u64::from_be_bytes(
                bytes[HEADER_SIZE + i * 8..HEADER_SIZE + i * 8 + 8]
                    .try_into()
                    .unwrap(),
            ) as usize
        };
        let kinds: Vec<u8> = (0..5).map(|i| bytes[offset(i)]).collect();
        assert_eq!(kinds, vec![KEY, DELTA, DELTA, KEY, DELTA]);
        // Frame 1 changed one pixel: one rectangle, 1 by 1, at 0,0.
        let at = offset(1);
        let length = u32::from_be_bytes(bytes[at + 1..at + 5].try_into().unwrap()) as usize;
        let mut payload = Vec::new();
        ZlibDecoder::new(&bytes[at + 5..at + 5 + length])
            .read_to_end(&mut payload)
            .unwrap();
        assert_eq!(payload, vec![0, 0, 0, 0, 0, 1, 0, 1, 5]);
    }
}
