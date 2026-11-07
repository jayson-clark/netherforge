use std::cell::Cell;
use std::rc::Rc;
use std::time::Instant;

/// The clock the time limit reads, injectable so that tests (and a room's
/// replay) don't depend on how fast the machine is.
pub trait Clock {
    /// Nanoseconds since some fixed point, never going backwards.
    fn nanos(&self) -> u64;
}

/// The machine's monotonic clock.
pub struct SystemClock(Instant);

impl SystemClock {
    pub fn new() -> Self {
        SystemClock(Instant::now())
    }
}

impl Default for SystemClock {
    fn default() -> Self {
        Self::new()
    }
}

impl Clock for SystemClock {
    fn nanos(&self) -> u64 {
        self.0.elapsed().as_nanos() as u64
    }
}

/// A clock a test moves by hand, or that moves itself by a step each time
/// it's read (so a loop "takes time" without taking any).
#[derive(Clone, Default)]
pub struct ManualClock {
    now: Rc<Cell<u64>>,
    step: Rc<Cell<u64>>,
}

impl ManualClock {
    pub fn new() -> Self {
        Self::default()
    }

    /// Moves the clock forward.
    pub fn advance(&self, nanos: u64) {
        self.now.set(self.now.get() + nanos);
    }

    /// Makes every read move the clock forward by `nanos` first.
    pub fn step_on_read(&self, nanos: u64) {
        self.step.set(nanos);
    }
}

impl Clock for ManualClock {
    fn nanos(&self) -> u64 {
        self.advance(self.step.get());
        self.now.get()
    }
}
