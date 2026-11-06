//! Newline-delimited frames with a cap (Kotlin's `FrameReader`): a peer can't make the server buffer an endless line.
//! The cap can change between frames, as it grows once the hello is accepted.

use std::io::{BufRead, BufReader, Read};

/// Why a frame couldn't be read.
#[derive(Debug)]
pub enum FrameError {
    /// A frame longer than the cap: the line is never fully read, and the connection is unusable after it.
    TooLarge(usize),
    Io(std::io::Error),
}

impl std::fmt::Display for FrameError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            FrameError::TooLarge(limit) => write!(f, "a frame longer than {limit} bytes"),
            FrameError::Io(e) => write!(f, "{e}"),
        }
    }
}

pub struct FrameReader<R> {
    input: BufReader<R>,
    pub limit: usize,
    line: Vec<u8>,
}

impl<R: Read> FrameReader<R> {
    pub fn new(input: R, limit: usize) -> FrameReader<R> {
        FrameReader {
            input: BufReader::new(input),
            limit,
            line: Vec::new(),
        }
    }

    /// The next frame without its line ending (a carriage return dropped), or none at the end of the stream.
    pub fn read_frame(&mut self) -> Result<Option<String>, FrameError> {
        self.line.clear();
        loop {
            let available = self.input.fill_buf().map_err(FrameError::Io)?;
            if available.is_empty() {
                if self.line.is_empty() {
                    return Ok(None);
                }
                break;
            }
            match available.iter().position(|&b| b == b'\n') {
                Some(end) => {
                    if self.line.len() + end > self.limit {
                        return Err(FrameError::TooLarge(self.limit));
                    }
                    self.line.extend_from_slice(&available[..end]);
                    self.input.consume(end + 1);
                    break;
                }
                None => {
                    let length = available.len();
                    if self.line.len() + length > self.limit {
                        return Err(FrameError::TooLarge(self.limit));
                    }
                    self.line.extend_from_slice(available);
                    self.input.consume(length);
                }
            }
        }
        if self.line.last() == Some(&b'\r') {
            self.line.pop();
        }
        Ok(Some(String::from_utf8_lossy(&self.line).into_owned()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_reader_splits_frames_on_newlines_drops_a_carriage_return_and_ends_cleanly() {
        let mut reader = FrameReader::new(&b"one\n\ntwo\r\nlast"[..], 100);
        let mut frames = Vec::new();
        while let Some(frame) = reader.read_frame().unwrap() {
            frames.push(frame);
        }
        assert_eq!(frames, ["one", "", "two", "last"]);
        assert!(reader.read_frame().unwrap().is_none());
    }

    #[test]
    fn a_frame_at_the_limit_reads_and_one_byte_over_fails() {
        let ok = "x".repeat(10);
        let text = format!("{ok}\n{}\n", "y".repeat(11));
        let mut reader = FrameReader::new(text.as_bytes(), 10);
        assert_eq!(reader.read_frame().unwrap().as_deref(), Some(ok.as_str()));
        assert!(matches!(reader.read_frame(), Err(FrameError::TooLarge(10))));
    }

    #[test]
    fn the_limit_can_grow_between_frames_and_text_counts_in_bytes() {
        let mut reader = FrameReader::new("ab\nééé\n".as_bytes(), 2);
        assert_eq!(reader.read_frame().unwrap().as_deref(), Some("ab"));
        assert!(matches!(reader.read_frame(), Err(FrameError::TooLarge(2))));
        let mut again = FrameReader::new("ééé\n".as_bytes(), 2);
        again.limit = 6;
        assert_eq!(again.read_frame().unwrap().as_deref(), Some("ééé"));
    }
}
