//! What a program started with `nf.wasm.start` shares between its thread and the main thread (Kotlin's
//! `WasmProgram`): the lines scripts `send` (its input, until it's closed), the lines it prints (cut at line ends,
//! long ones into pieces), each taking a permit of its `room` (`wasm.max-queued-lines`) and waiting for one while
//! scripts haven't taken them: **backpressure, not dropping**. The main thread takes lines each tick, up to
//! `wasm.lines-per-tick`, and ends it. And `nf.wasm.run`'s capped output.

use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};

use wasmtime::Result;

use super::threads::{Closed, Guard};
use super::wasi::{Input, Output};

fn lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|e| e.into_inner())
}

/// Why a program ended, as its `end` event's `reason` says it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ProgramEnd {
    Exited,
    Stopped,
    Failed,
    Limit,
    Unloaded,
}

impl ProgramEnd {
    pub fn name(self) -> &'static str {
        match self {
            ProgramEnd::Exited => "exited",
            ProgramEnd::Stopped => "stopped",
            ProgramEnd::Failed => "failed",
            ProgramEnd::Limit => "limit",
            ProgramEnd::Unloaded => "unloaded",
        }
    }
}

/// What a program's thread hands the main thread: a line it printed, or its end.
#[derive(Clone, Debug, PartialEq)]
pub enum ProgramOutput {
    Line {
        text: String,
        stream: &'static str,
    },
    Ended {
        reason: ProgramEnd,
        exit_code: Option<i32>,
        detail: Option<String>,
    },
}

#[derive(Default)]
struct InputState {
    lines: VecDeque<Vec<u8>>,
    /// The end of its input, after what's queued.
    finished: bool,
    /// What's left of the line being read.
    current: Vec<u8>,
    at: usize,
}

/// What a program and the main thread share.
pub struct ProgramShared {
    pub guard: Arc<Guard>,
    output: Mutex<VecDeque<ProgramOutput>>,
    /// Permits for lines printed and not yet taken.
    room: Mutex<usize>,
    room_freed: Condvar,
    input: Mutex<InputState>,
    input_sent: Condvar,
    capacity: usize,
    pub max_line_bytes: usize,
    /// Whether the thread is waiting for room to print, or to read.
    printing: AtomicBool,
    reading: AtomicBool,
    /// Whether the thread has finished (or never started).
    pub finished: AtomicBool,
}

impl ProgramShared {
    pub fn new(
        max_queued_lines: usize,
        max_line_bytes: usize,
        guard: Arc<Guard>,
    ) -> Arc<ProgramShared> {
        Arc::new(ProgramShared {
            guard,
            output: Mutex::default(),
            room: Mutex::new(max_queued_lines),
            room_freed: Condvar::new(),
            input: Mutex::default(),
            input_sent: Condvar::new(),
            capacity: max_queued_lines,
            max_line_bytes,
            printing: AtomicBool::new(false),
            reading: AtomicBool::new(false),
            finished: AtomicBool::new(false),
        })
    }

    /// The next thing the thread handed over.
    pub fn take(&self) -> Option<ProgramOutput> {
        lock(&self.output).pop_front()
    }

    /// The thread's end (or one the main thread decided before it started).
    pub fn ended(&self, reason: ProgramEnd, exit_code: Option<i32>, detail: Option<String>) {
        lock(&self.output).push_back(ProgramOutput::Ended {
            reason,
            exit_code,
            detail,
        });
        self.finished.store(true, Ordering::SeqCst);
    }

    /// A line taken by scripts: its permit back.
    pub fn release(&self, permits: usize) {
        let mut room = lock(&self.room);
        *room = room.saturating_add(permits);
        self.room_freed.notify_all();
    }

    /// Whether scripts may still send.
    pub fn open(&self) -> bool {
        !lock(&self.input).finished
    }

    /// Queues `line` for the program; false when it already holds as many lines it hasn't read as it may
    /// (`wasm.max-queued-lines`): a program that doesn't read mustn't make the server keep everything sent to it.
    pub fn send(&self, line: &str) -> bool {
        let mut input = lock(&self.input);
        if input.finished || input.lines.len() >= self.capacity {
            return false;
        }
        let mut bytes = line.as_bytes().to_vec();
        bytes.push(b'\n');
        input.lines.push_back(bytes);
        self.input_sent.notify_all();
        true
    }

    /// The end of its input, after what's queued. False when it was already closed.
    pub fn finish(&self) -> bool {
        let mut input = lock(&self.input);
        if input.finished {
            return false;
        }
        input.finished = true;
        self.input_sent.notify_all();
        true
    }

    /// Ends it from the main thread: its code stops at the next epoch, its input ends, a print waiting for room
    /// goes on (to find it stopped), and what it printed and wasn't taken is dropped.
    pub fn stop(&self) {
        self.guard.close();
        self.finish();
        lock(&self.output).clear();
        self.release(usize::MAX / 2);
    }

    /// Whether it can go no further on its own: done, waiting for scripts to take its lines, or waiting for input
    /// when none has been sent. What a test run waits on.
    pub fn quiet(&self) -> bool {
        self.finished.load(Ordering::SeqCst)
            || self.printing.load(Ordering::SeqCst)
            || (self.reading.load(Ordering::SeqCst) && lock(&self.input).lines.is_empty())
    }

    /// Queues a line from the thread, waiting for room.
    fn printed(&self, text: String, stream: &'static str) -> Result<()> {
        {
            let mut room = lock(&self.room);
            self.printing.store(true, Ordering::SeqCst);
            while *room == 0 && !self.guard.closed() {
                room = self
                    .room_freed
                    .wait(room)
                    .unwrap_or_else(|e| e.into_inner());
            }
            self.printing.store(false, Ordering::SeqCst);
            if self.guard.closed() {
                return Err(Closed.into());
            }
            *room -= 1;
        }
        lock(&self.output).push_back(ProgramOutput::Line { text, stream });
        Ok(())
    }
}

/// A program's standard input: the lines scripts send.
pub struct ProgramInput(pub Arc<ProgramShared>);

impl Input for ProgramInput {
    fn read(&mut self, into: &mut [u8], guard: &Guard) -> Result<usize> {
        let shared = &self.0;
        let mut input = lock(&shared.input);
        while input.at >= input.current.len() {
            if guard.closed() {
                return Err(Closed.into());
            }
            if let Some(line) = input.lines.pop_front() {
                input.current = line;
                input.at = 0;
                break;
            }
            if input.finished {
                return Ok(0);
            }
            shared.reading.store(true, Ordering::SeqCst);
            input = shared
                .input_sent
                .wait(input)
                .unwrap_or_else(|e| e.into_inner());
            shared.reading.store(false, Ordering::SeqCst);
        }
        let n = into.len().min(input.current.len() - input.at);
        let at = input.at;
        into[..n].copy_from_slice(&input.current[at..at + n]);
        input.at += n;
        Ok(n)
    }
}

/// One of a program's output streams, cut into lines (LF or CRLF; a long one into pieces of at most
/// `max_line_bytes`, never through a UTF-8 character: what's left of one starts the next piece).
pub struct LineOutput {
    shared: Arc<ProgramShared>,
    stream: &'static str,
    line: Vec<u8>,
}

impl LineOutput {
    pub fn new(shared: Arc<ProgramShared>, stream: &'static str) -> LineOutput {
        LineOutput {
            shared,
            stream,
            line: Vec::new(),
        }
    }

    fn emit(&mut self) -> Result<()> {
        let mut bytes = std::mem::take(&mut self.line);
        if bytes.last() == Some(&b'\r') {
            bytes.pop();
        }
        self.shared
            .printed(String::from_utf8_lossy(&bytes).into_owned(), self.stream)
    }

    /// A line too long for one: as much of it as ends on a whole character, the rest kept for the next piece.
    fn piece(&mut self) -> Result<()> {
        let cut = whole_characters(&self.line);
        let rest = self.line.split_off(cut);
        let piece = std::mem::replace(&mut self.line, rest);
        self.shared
            .printed(String::from_utf8_lossy(&piece).into_owned(), self.stream)
    }

    /// What's left without a line ending, at the end.
    pub fn finish(&mut self) -> Result<()> {
        if self.line.is_empty() {
            Ok(())
        } else {
            self.emit()
        }
    }
}

impl Output for LineOutput {
    fn write(&mut self, bytes: &[u8], _: &Guard) -> Result<()> {
        for &b in bytes {
            if b == b'\n' {
                self.emit()?;
            } else {
                self.line.push(b);
                if self.line.len() >= self.shared.max_line_bytes {
                    self.piece()?;
                }
            }
        }
        Ok(())
    }
}

/// Both of a program's output streams, which its thread finishes once it ends.
pub struct SharedOutput(pub Arc<Mutex<LineOutput>>);

impl Output for SharedOutput {
    fn write(&mut self, bytes: &[u8], guard: &Guard) -> Result<()> {
        lock(&self.0).write(bytes, guard)
    }
}

/// How many of `bytes` end on a whole character: all of them, or fewer when the last character is cut off (its
/// lead byte and what came of it are left out). Bytes that aren't UTF-8 at all are cut where they are.
pub fn whole_characters(bytes: &[u8]) -> usize {
    let size = bytes.len();
    if size == 0 {
        return 0;
    }
    let mut lead = size as isize - 1;
    // A character is at most four bytes: its lead and up to three continuations (10xxxxxx).
    while lead >= 0 && size as isize - lead <= 4 && bytes[lead as usize] & 0xC0 == 0x80 {
        lead -= 1;
    }
    if lead < 0 || size as isize - lead > 4 {
        return size;
    }
    let lead = lead as usize;
    let first = bytes[lead];
    let length = if first < 0x80 {
        1
    } else if first >> 5 == 0b110 {
        2
    } else if first >> 4 == 0b1110 {
        3
    } else if first >> 3 == 0b11110 {
        4
    } else {
        return size;
    };
    // A whole first character is never held back, so a piece always moves on.
    if lead + length > size && lead > 0 {
        lead
    } else {
        size
    }
}

/// What a run printed to one stream, kept up to a limit.
#[derive(Clone)]
pub struct CappedOutput {
    bytes: Arc<Mutex<Vec<u8>>>,
    limit: usize,
}

impl CappedOutput {
    pub fn new(limit: usize) -> CappedOutput {
        CappedOutput {
            bytes: Arc::default(),
            limit,
        }
    }

    pub fn text(&self) -> String {
        String::from_utf8_lossy(&lock(&self.bytes)).into_owned()
    }
}

impl Output for CappedOutput {
    fn write(&mut self, bytes: &[u8], _: &Guard) -> Result<()> {
        let mut kept = lock(&self.bytes);
        let room = self.limit.saturating_sub(kept.len());
        kept.extend_from_slice(&bytes[..bytes.len().min(room)]);
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_cut_never_goes_through_a_character() {
        let text = "aé€😀".as_bytes();
        // Every prefix ends on a whole character, or is cut back to the one before.
        assert_eq!(whole_characters(&text[..1]), 1);
        assert_eq!(whole_characters(&text[..2]), 1);
        assert_eq!(whole_characters(&text[..3]), 3);
        assert_eq!(whole_characters(&text[..5]), 3);
        assert_eq!(whole_characters(&text[..6]), 6);
        assert_eq!(whole_characters(&text[..9]), 6);
        assert_eq!(whole_characters(text), 10);
        // A first character cut short is never held back.
        assert_eq!(whole_characters(&"😀".as_bytes()[..2]), 2);
        // Bytes that aren't UTF-8 are cut where they are.
        assert_eq!(whole_characters(&[0xFF, 0xFF]), 2);
    }

    #[test]
    fn long_lines_come_in_pieces_and_carriage_returns_go() {
        let shared = ProgramShared::new(100, 5, Arc::default());
        let mut out = LineOutput::new(shared.clone(), "stdout");
        let guard = Guard::default();
        out.write("abcdefg\r\nhé€x".as_bytes(), &guard).unwrap();
        out.finish().unwrap();
        let mut lines = Vec::new();
        while let Some(ProgramOutput::Line { text, .. }) = shared.take() {
            lines.push(text);
        }
        assert_eq!(lines, ["abcde", "fg", "hé", "€x"]);
    }

    #[test]
    fn a_full_room_holds_the_program_until_scripts_take_a_line() {
        let shared = ProgramShared::new(1, 100, Arc::default());
        let printer = shared.clone();
        let thread = std::thread::spawn(move || {
            let mut out = LineOutput::new(printer, "stdout");
            out.write(b"one\ntwo\n", &Guard::default())
        });
        while !shared.quiet() {
            std::thread::yield_now();
        }
        assert!(shared.take().is_some());
        assert!(shared.take().is_none(), "the second waits for room");
        shared.release(1);
        thread.join().unwrap().unwrap();
        assert!(shared.take().is_some());
    }
}
