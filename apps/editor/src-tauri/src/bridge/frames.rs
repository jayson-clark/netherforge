//! Newline-delimited frames with a size cap.
//!
//! `AsyncBufReadExt::lines` buffers a line however long it grows, so a peer
//! that never sends a newline would fill the editor's memory. [FrameReader]
//! stops at its limit instead: it reports [Frame::TooLarge] without reading
//! the rest, and the caller closes the connection.

use tokio::io::{AsyncBufRead, AsyncBufReadExt};

#[derive(Debug, PartialEq, Eq)]
pub enum Frame {
    /// One frame without its line ending. Invalid UTF-8 is replaced, as the JSON parse will refuse it.
    Line(String),
    /// A frame longer than the limit; the stream is unusable after it.
    TooLarge,
    /// The peer closed the stream.
    End,
}

pub struct FrameReader<R> {
    reader: R,
    limit: usize,
    buffer: Vec<u8>,
}

impl<R: AsyncBufRead + Unpin> FrameReader<R> {
    pub fn new(reader: R, limit: usize) -> Self {
        Self {
            reader,
            limit,
            buffer: Vec::new(),
        }
    }

    /// Changes the cap for the frames to come (it grows after the hello).
    pub fn set_limit(&mut self, limit: usize) {
        self.limit = limit;
    }

    /// The next frame. A read cancelled by a timeout leaves the reader
    /// unusable, which is fine: that connection is dropped.
    pub async fn next(&mut self) -> std::io::Result<Frame> {
        self.buffer.clear();
        loop {
            let available = self.reader.fill_buf().await?;
            if available.is_empty() {
                if self.buffer.is_empty() {
                    return Ok(Frame::End);
                }
                break;
            }
            match available.iter().position(|&b| b == b'\n') {
                Some(end) => {
                    if self.buffer.len() + end > self.limit {
                        return Ok(Frame::TooLarge);
                    }
                    self.buffer.extend_from_slice(&available[..end]);
                    self.reader.consume(end + 1);
                    break;
                }
                None => {
                    let length = available.len();
                    if self.buffer.len() + length > self.limit {
                        return Ok(Frame::TooLarge);
                    }
                    self.buffer.extend_from_slice(available);
                    self.reader.consume(length);
                }
            }
        }
        if self.buffer.last() == Some(&b'\r') {
            self.buffer.pop();
        }
        Ok(Frame::Line(
            String::from_utf8_lossy(&self.buffer).into_owned(),
        ))
    }
}

/// The sentence for the console when a frame is past the cap.
pub fn too_large(limit: usize, when: &str) -> String {
    format!("Closed the dev bridge connection: a frame {when} was longer than {limit} bytes")
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::BufReader;

    fn reader(bytes: &'static [u8], limit: usize) -> FrameReader<BufReader<&'static [u8]>> {
        FrameReader::new(BufReader::with_capacity(4, bytes), limit)
    }

    #[tokio::test]
    async fn splits_on_newlines_and_drops_a_carriage_return() {
        let mut frames = reader(b"one\r\n\ntwo\nlast", 100);
        assert_eq!(frames.next().await.unwrap(), Frame::Line("one".into()));
        assert_eq!(frames.next().await.unwrap(), Frame::Line("".into()));
        assert_eq!(frames.next().await.unwrap(), Frame::Line("two".into()));
        assert_eq!(frames.next().await.unwrap(), Frame::Line("last".into()));
        assert_eq!(frames.next().await.unwrap(), Frame::End);
    }

    #[tokio::test]
    async fn a_frame_at_the_limit_reads_and_one_byte_over_doesnt() {
        // The tiny buffer makes the line arrive in pieces.
        let mut frames = reader(b"0123456789\n0123456789x\nnext\n", 10);
        assert_eq!(
            frames.next().await.unwrap(),
            Frame::Line("0123456789".into())
        );
        assert_eq!(frames.next().await.unwrap(), Frame::TooLarge);
    }

    #[tokio::test]
    async fn an_endless_line_is_cut_off_at_the_limit() {
        let mut frames = FrameReader::new(BufReader::new(tokio::io::repeat(b'a')), 1024);
        assert_eq!(frames.next().await.unwrap(), Frame::TooLarge);
        assert!(frames.buffer.len() <= 1024);
    }

    #[tokio::test]
    async fn the_limit_can_grow() {
        let mut frames = reader(b"abc\nabcdef\n", 3);
        assert_eq!(frames.next().await.unwrap(), Frame::Line("abc".into()));
        frames.set_limit(10);
        assert_eq!(frames.next().await.unwrap(), Frame::Line("abcdef".into()));
    }
}
