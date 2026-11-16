//! The threads WebAssembly runs on (Kotlin's `WasmThreads`, `SerialExecutor` and `WasmMemoryBudget`): modules are
//! computation, so they get threads of their own rather than HTTP's workers, whose work is sockets.
//!
//! - `Pool`: `wasm.threads` threads for compiling, instantiating, calls and runs, so at most that many cores are
//!   ever busy with modules. Each instance's calls go through a `Lane` over it, one at a time.
//! - Programs get a thread each while they run (they block reading their input), bounded by
//!   `wasm.max-processes-total`, which the service holds to before starting one.
//! - Time: the engine's epoch is ticked every `EPOCH` by one thread, and every store checks its `Guard` at each
//!   tick (`Guard::check`, wasmtime's epoch deadline callback): closed, or past its deadline, its code traps there.
//!   That's Kotlin's watchdog and `Thread.interrupt` in one, and it stops compiled code and the interpreter alike.

use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex, mpsc};
use std::time::{Duration, Instant};

type Job = Box<dyn FnOnce() + Send>;

/// How often the engine's epoch moves: how soon a closed instance or a call past its time stops.
pub const EPOCH: Duration = Duration::from_millis(10);

/// A thread's stack: enough for wasmtime's own (`max_wasm_stack`) and the host's code under it.
const STACK_BYTES: usize = 8 * 1024 * 1024;

fn lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|e| e.into_inner())
}

/// A few threads taking jobs in order, made at once, ended when it's closed or dropped. `unfinished` counts what's
/// queued or running, for `idle`.
pub struct Pool {
    send: Mutex<Option<mpsc::Sender<Job>>>,
    unfinished: Arc<AtomicUsize>,
}

impl Pool {
    pub fn new(name: &str, count: usize) -> Pool {
        let (send, jobs) = mpsc::channel::<Job>();
        let jobs = Arc::new(Mutex::new(jobs));
        for n in 0..count.max(1) {
            let jobs = jobs.clone();
            let _ = std::thread::Builder::new()
                .name(format!("{name} {}", n + 1))
                .stack_size(STACK_BYTES)
                .spawn(move || {
                    loop {
                        let job = lock(&jobs).recv();
                        let Ok(job) = job else { break };
                        // A job that panics mustn't take the thread with it.
                        let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(job));
                    }
                });
        }
        Pool {
            send: Mutex::new(Some(send)),
            unfinished: Arc::default(),
        }
    }

    /// Queues `job`; false once the pool is closed (nothing will run it).
    pub fn submit(&self, job: impl FnOnce() + Send + 'static) -> bool {
        let unfinished = self.unfinished.clone();
        unfinished.fetch_add(1, Ordering::SeqCst);
        let job: Job = Box::new(move || {
            struct Done(Arc<AtomicUsize>);
            impl Drop for Done {
                fn drop(&mut self) {
                    self.0.fetch_sub(1, Ordering::SeqCst);
                }
            }
            let _done = Done(unfinished);
            job();
        });
        let sent = lock(&self.send)
            .as_ref()
            .is_some_and(|s| s.send(job).is_ok());
        if !sent {
            self.unfinished.fetch_sub(1, Ordering::SeqCst);
        }
        sent
    }

    /// Whether nothing given to it is queued or running.
    pub fn idle(&self) -> bool {
        self.unfinished.load(Ordering::SeqCst) == 0
    }

    /// No more jobs: each thread ends once what's queued has run (a running module has been told to stop by its
    /// guard already).
    pub fn close(&self) {
        lock(&self.send).take();
    }
}

#[derive(Default)]
struct LaneState {
    queue: VecDeque<Job>,
    running: bool,
}

/// Jobs run one at a time, in order, on a pool's threads (Kotlin's `SerialExecutor`): an instance's calls.
#[derive(Clone)]
pub struct Lane {
    pool: Arc<Pool>,
    state: Arc<Mutex<LaneState>>,
}

impl Lane {
    pub fn new(pool: Arc<Pool>) -> Lane {
        Lane {
            pool,
            state: Arc::default(),
        }
    }

    /// Queues `job` after what's queued; false once the pool is closed.
    pub fn execute(&self, job: impl FnOnce() + Send + 'static) -> bool {
        let mut state = lock(&self.state);
        state.queue.push_back(Box::new(job));
        if state.running {
            return true;
        }
        state.running = true;
        drop(state);
        let lane = self.state.clone();
        let sent = self.pool.submit(move || {
            loop {
                let job = {
                    let mut state = lock(&lane);
                    match state.queue.pop_front() {
                        Some(job) => job,
                        None => {
                            state.running = false;
                            return;
                        }
                    }
                };
                let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(job));
            }
        });
        if !sent {
            let mut state = lock(&self.state);
            state.queue.clear();
            state.running = false;
        }
        sent
    }
}

/// What a store's code checks at every epoch tick: whether it was closed (an instance closed, a program stopped)
/// and whether its call is past its deadline. Shared between the main thread and the one running it.
#[derive(Debug, Default)]
pub struct Guard {
    /// The session's: set as it stops, so whatever still runs stops too.
    stopping: Arc<AtomicBool>,
    closed: AtomicBool,
    deadline: Mutex<Option<Instant>>,
    timed_out: AtomicBool,
}

/// Why a store's code was stopped at an epoch tick: it was closed.
#[derive(Debug)]
pub struct Closed;

impl std::fmt::Display for Closed {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("it was closed")
    }
}

impl std::error::Error for Closed {}

/// Why a store's code was stopped at an epoch tick: its time ran out.
#[derive(Debug)]
pub struct TimedOut;

impl std::fmt::Display for TimedOut {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("its time ran out")
    }
}

impl std::error::Error for TimedOut {}

impl Guard {
    /// A guard that also stops its code once `stopping` is set (the session's).
    pub fn under(stopping: Arc<AtomicBool>) -> Guard {
        Guard {
            stopping,
            ..Guard::default()
        }
    }

    pub fn close(&self) {
        self.closed.store(true, Ordering::SeqCst);
    }

    pub fn closed(&self) -> bool {
        self.closed.load(Ordering::SeqCst) || self.stopping.load(Ordering::SeqCst)
    }

    /// Whether the code may go on: an error once it's closed or past its deadline.
    pub fn check(&self) -> Result<(), wasmtime::Error> {
        if self.closed() {
            return Err(Closed.into());
        }
        if lock(&self.deadline).is_some_and(|d| Instant::now() >= d) {
            self.timed_out.store(true, Ordering::SeqCst);
            return Err(TimedOut.into());
        }
        Ok(())
    }

    /// Runs `work` with `seconds` to do it in; `Err(None)` when it was stopped for its time, else its own result.
    pub fn limited<T, E>(
        &self,
        seconds: u64,
        work: impl FnOnce() -> Result<T, E>,
    ) -> Result<T, Option<E>> {
        self.timed_out.store(false, Ordering::SeqCst);
        *lock(&self.deadline) = Some(Instant::now() + Duration::from_secs(seconds));
        let result = work();
        *lock(&self.deadline) = None;
        let timed_out = self.timed_out.swap(false, Ordering::SeqCst);
        match result {
            Ok(value) => Ok(value),
            Err(_) if timed_out => Err(None),
            Err(e) => Err(Some(e)),
        }
    }
}

/// The memory WebAssembly may hold across the server (`wasm.max-memory-total-mb`), in 64 KiB pages. An instance or
/// program reserves the most it may grow to as it starts and gives it back when it ends, so what's running can
/// never together grow past the limit. From any thread.
#[derive(Debug)]
pub struct Budget {
    pages: u64,
    reserved: AtomicU64,
}

impl Budget {
    pub fn new(pages: u64) -> Budget {
        Budget {
            pages,
            reserved: AtomicU64::new(0),
        }
    }

    /// Reserves `want` pages until the reservation is dropped; none when that would go over the limit.
    pub fn reserve(self: &Arc<Budget>, want: u64) -> Option<Reservation> {
        let mut now = self.reserved.load(Ordering::SeqCst);
        loop {
            if now + want > self.pages {
                return None;
            }
            match self.reserved.compare_exchange(
                now,
                now + want,
                Ordering::SeqCst,
                Ordering::SeqCst,
            ) {
                Ok(_) => {
                    return Some(Reservation {
                        budget: self.clone(),
                        pages: want,
                    });
                }
                Err(seen) => now = seen,
            }
        }
    }

    /// What's reserved now, in pages.
    pub fn used(&self) -> u64 {
        self.reserved.load(Ordering::SeqCst)
    }
}

/// Pages held against the budget, given back when it's dropped (with the instance that holds it).
#[derive(Debug)]
pub struct Reservation {
    budget: Arc<Budget>,
    pages: u64,
}

impl Drop for Reservation {
    fn drop(&mut self) {
        self.budget.reserved.fetch_sub(self.pages, Ordering::SeqCst);
    }
}

/// Moves `engine`'s epoch every `EPOCH` until it's dropped.
pub struct Ticker {
    stop: Arc<(Mutex<bool>, Condvar)>,
    engine: wasmtime::Engine,
}

impl Ticker {
    pub fn start(engine: wasmtime::Engine) -> Ticker {
        let stop: Arc<(Mutex<bool>, Condvar)> = Arc::default();
        let held = stop.clone();
        let ticked = engine.clone();
        let _ = std::thread::Builder::new()
            .name("NetherForge wasm epoch".into())
            .spawn(move || {
                let (stopped, wake) = &*held;
                let mut stopped = lock(stopped);
                while !*stopped {
                    stopped = wake
                        .wait_timeout(stopped, EPOCH)
                        .unwrap_or_else(|e| e.into_inner())
                        .0;
                    ticked.increment_epoch();
                }
            });
        Ticker { stop, engine }
    }
}

impl Drop for Ticker {
    fn drop(&mut self) {
        let (stopped, wake) = &*self.stop;
        *lock(stopped) = true;
        wake.notify_all();
        // One last tick, so code whose guard was just closed reaches its check.
        self.engine.increment_epoch();
    }
}
