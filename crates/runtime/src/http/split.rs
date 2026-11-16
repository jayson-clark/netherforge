//! How `nf.http.stream` splits a response into messages (Kotlin's `StreamSplitter`): lines (LF, CRLF or a lone CR
//! end one), Server-Sent Events (`data:` lines joined, `event:` and `id:` kept, comments and `retry:` skipped, an
//! event at each blank line) or decoded chunks. Text is UTF-8, decoded leniently (a bad byte is U+FFFD), and a
//! character split between two pieces is decoded whole.

/// `HttpStreamOptions.format`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum StreamFormat {
    Lines,
    Events,
    Chunks,
}

impl StreamFormat {
    pub fn of(name: Option<&str>) -> StreamFormat {
        match name {
            Some("events") => StreamFormat::Events,
            Some("chunks") => StreamFormat::Chunks,
            _ => StreamFormat::Lines,
        }
    }
}

/// A line or event longer than the owner allows: the stream ends `"limit"`.
#[derive(Debug, PartialEq, Eq)]
pub struct TooLarge(pub usize);

impl TooLarge {
    pub fn words(&self) -> String {
        format!(
            "a message is larger than http.connections.max-message-bytes ({} bytes)",
            self.0
        )
    }
}

/// One message: its data, and for events its type and the stream's last id.
pub struct Message {
    pub data: String,
    pub r#type: Option<String>,
    pub id: Option<String>,
}

/// Splits a body fed in pieces of any size. Used by one thread at a time.
pub struct StreamSplitter {
    format: StreamFormat,
    max_bytes: usize,
    line: Vec<u8>,
    after_cr: bool,
    data: String,
    has_data: bool,
    data_bytes: usize,
    r#type: Option<String>,
    last_id: Option<String>,
    /// A chunk's last bytes that may be the start of a character.
    pending: Vec<u8>,
}

const CR: u8 = b'\r';
const LF: u8 = b'\n';

impl StreamSplitter {
    pub fn new(format: StreamFormat, max_bytes: usize) -> StreamSplitter {
        StreamSplitter {
            format,
            max_bytes,
            line: Vec::new(),
            after_cr: false,
            data: String::new(),
            has_data: false,
            data_bytes: 0,
            r#type: None,
            last_id: None,
            pending: Vec::new(),
        }
    }

    /// Takes more of the body, handing each message it completes to `emit` (which may stop the reading).
    pub fn feed<E>(
        &mut self,
        bytes: &[u8],
        emit: &mut impl FnMut(Message) -> Result<(), E>,
    ) -> Result<(), Split<E>> {
        if self.format == StreamFormat::Chunks {
            return self.chunk(bytes, false, emit).map_err(Split::Stopped);
        }
        for &b in bytes {
            match b {
                CR => {
                    self.end_line(emit)?;
                    self.after_cr = true;
                }
                LF if self.after_cr => self.after_cr = false,
                LF => self.end_line(emit)?,
                _ => {
                    self.after_cr = false;
                    if self.line.len() >= self.max_bytes {
                        return Err(Split::TooLarge(TooLarge(self.max_bytes)));
                    }
                    self.line.push(b);
                }
            }
        }
        Ok(())
    }

    /// The body ended: a last line without its ending is a line; a half-built event is dropped, as the SSE rules say.
    pub fn finish<E>(
        &mut self,
        emit: &mut impl FnMut(Message) -> Result<(), E>,
    ) -> Result<(), Split<E>> {
        match self.format {
            StreamFormat::Chunks => self.chunk(&[], true, emit).map_err(Split::Stopped),
            StreamFormat::Lines if !self.line.is_empty() => self.end_line(emit),
            _ => Ok(()),
        }
    }

    fn end_line<E>(
        &mut self,
        emit: &mut impl FnMut(Message) -> Result<(), E>,
    ) -> Result<(), Split<E>> {
        let text = String::from_utf8_lossy(&self.line).into_owned();
        self.line.clear();
        if self.format == StreamFormat::Lines {
            return emit(Message {
                data: text,
                r#type: None,
                id: None,
            })
            .map_err(Split::Stopped);
        }
        self.event_line(text, emit)
    }

    fn event_line<E>(
        &mut self,
        text: String,
        emit: &mut impl FnMut(Message) -> Result<(), E>,
    ) -> Result<(), Split<E>> {
        if text.is_empty() {
            return self.dispatch(emit);
        }
        if text.starts_with(':') {
            return Ok(());
        }
        let (field, value) = match text.find(':') {
            Some(at) => {
                let value = &text[at + 1..];
                (&text[..at], value.strip_prefix(' ').unwrap_or(value))
            }
            None => (text.as_str(), ""),
        };
        match field {
            "data" => {
                if self.has_data {
                    self.data.push('\n');
                }
                self.data_bytes += value.len() + usize::from(self.has_data);
                if self.data_bytes > self.max_bytes {
                    return Err(Split::TooLarge(TooLarge(self.max_bytes)));
                }
                self.data.push_str(value);
                self.has_data = true;
            }
            "event" => self.r#type = Some(value.to_owned()),
            "id" if !value.contains('\0') => self.last_id = Some(value.to_owned()),
            _ => {}
        }
        Ok(())
    }

    fn dispatch<E>(
        &mut self,
        emit: &mut impl FnMut(Message) -> Result<(), E>,
    ) -> Result<(), Split<E>> {
        let r#type = self.r#type.take().filter(|t| !t.is_empty());
        let data = std::mem::take(&mut self.data);
        let had = std::mem::replace(&mut self.has_data, false);
        self.data_bytes = 0;
        if had {
            emit(Message {
                data,
                r#type: Some(r#type.unwrap_or_else(|| "message".into())),
                id: self.last_id.clone(),
            })
            .map_err(Split::Stopped)?;
        }
        Ok(())
    }

    fn chunk<E>(
        &mut self,
        bytes: &[u8],
        last: bool,
        emit: &mut impl FnMut(Message) -> Result<(), E>,
    ) -> Result<(), E> {
        self.pending.extend_from_slice(bytes);
        // Up to the last whole character; what may start one waits for the next piece (or, at the end, is U+FFFD).
        let whole = if last {
            self.pending.len()
        } else {
            whole_prefix(&self.pending)
        };
        let text = String::from_utf8_lossy(&self.pending[..whole]).into_owned();
        self.pending.drain(..whole);
        if text.is_empty() {
            return Ok(());
        }
        emit(Message {
            data: text,
            r#type: None,
            id: None,
        })
    }
}

/// Why splitting stopped: a message over the limit, or the reader was told to stop.
#[derive(Debug, PartialEq, Eq)]
pub enum Split<E> {
    TooLarge(TooLarge),
    Stopped(E),
}

/// How many of `bytes` end before a UTF-8 character that may be cut short (one started in the last three bytes
/// whose continuation hasn't all come).
fn whole_prefix(bytes: &[u8]) -> usize {
    let len = bytes.len();
    for back in 1..=3.min(len) {
        let b = bytes[len - back];
        if b & 0xC0 == 0x80 {
            continue;
        }
        let needed = match b {
            0xC0..=0xDF => 2,
            0xE0..=0xEF => 3,
            0xF0..=0xF7 => 4,
            _ => return len,
        };
        return if back < needed { len - back } else { len };
    }
    len
}
