//! Writing a converted video's frames, `videos/<id>/video.nfv`. The layout is
//! format's (`netherforge_format::nfv`, which the server reads it with): a
//! header, an index of where each frame starts, then the frames, each a key
//! frame or the rectangles that changed since the one before, deflated.
//!
//! Frames go to a body file as they come (a long video needn't fit in memory);
//! [Writer::finish] then writes the header, the index and the body into the
//! video's file, through a temporary file renamed into place.

use std::fs::File;
use std::io::{BufReader, BufWriter, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

pub use netherforge_format::nfv::{DELTA, HEADER_SIZE, Header, KEY, TILE, delta};
use netherforge_format::nfv::{pack, record};

use crate::error::{Context, Result};
use crate::fs::atomic;

/// The header of the converted video at [path], or None when it isn't there or isn't one.
pub fn read_header(path: &Path) -> Option<Header> {
    let mut bytes = [0u8; HEADER_SIZE];
    File::open(path).ok()?.read_exact(&mut bytes).ok()?;
    Header::parse(&bytes).ok()
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
        let packed = record(kind, &pack(&payload));
        self.offsets.push(self.written);
        let body = self
            .body
            .as_mut()
            .expect("a writer is written to until it finishes");
        body.write_all(&packed)?;
        self.written += packed.len() as u64;
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
        assert_eq!(Header::parse(&h.to_bytes()), Ok(h.clone()));
        let mut wrong = h.to_bytes();
        wrong[0] = b'X';
        assert!(Header::parse(&wrong).is_err());
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
