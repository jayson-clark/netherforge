use std::fmt;
use std::time::Duration;

/// Where in a script something happened: its chunk (a project path) and line.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Location {
    pub chunk: String,
    pub line: u32,
}

impl fmt::Display for Location {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}:{}", self.chunk, self.line)
    }
}

/// Why the host stopped a call that hadn't failed on its own.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Stop {
    /// It used up its budget of steps (calls and loop iterations).
    Budget { budget: u64 },
    /// It ran past the time limit.
    Time { limit: Duration },
}

impl fmt::Display for Stop {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Stop::Budget { budget } => write!(f, "used up its budget of {budget} steps"),
            Stop::Time { limit } => {
                write!(f, "ran past its time limit of {} ms", limit.as_millis())
            }
        }
    }
}

/// How a call into a script went wrong.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Failure {
    /// The script raised an error, or didn't compile. The message is Luau's,
    /// located (`modules/m/init.lua:3: boom`) when Luau could locate it, and
    /// the traceback is the stack at the error, when there was one.
    Error {
        message: String,
        traceback: Option<String>,
    },
    /// The host stopped it, at `location` when that was in a script. A script
    /// that caught the stop with `pcall` is still reported stopped.
    Stopped {
        stop: Stop,
        location: Option<Location>,
    },
    /// An allocation failed: the state is at its memory limit.
    Memory,
}

impl fmt::Display for Failure {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Failure::Error { message, .. } => f.write_str(message),
            Failure::Stopped {
                stop,
                location: Some(at),
            } => write!(f, "{at}: {stop}"),
            Failure::Stopped {
                stop,
                location: None,
            } => write!(f, "{stop}"),
            Failure::Memory => f.write_str("not enough memory"),
        }
    }
}

impl std::error::Error for Failure {}
