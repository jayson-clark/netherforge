//! NetherForge's profiler (Kotlin's `Profiler`, `ScriptProfile`'s other half and `ProfileReport`): exact time per
//! tick step, per script scope and per function scripts run, measured with the host's clock (which leaves out time
//! a debugger held the server paused), never sampled.
//!
//! It outlives sessions, so a recording goes on across a full reload. The session feeds it: each tick's steps
//! ([`Profiler::tick`]), each scope's own time that tick ([`Profiler::scopes`]) and, about once a second
//! ([`Profiler::due`]), what every function cost ([`Profiler::calls`]). Every [`BATCH_TICKS`] ticks that becomes a
//! [`ProfileSample`]: sent on the bridge's `profiler` stream while the editor subscribes, and added to the recording
//! `/nf profile` started, which writes a report when it ends.
//!
//! **When it measures** ([`Profiler::active`]): always on a dev server (the editor's), and on a production server
//! only while `/nf profile` records, since it costs a little on every call into a script.

use std::cell::{Cell, RefCell};
use std::collections::{BTreeSet, HashMap};
use std::path::{Path, PathBuf};
use std::rc::Rc;
use std::sync::mpsc::{Receiver, Sender, channel};

use indexmap::IndexMap;
use netherforge_format::bridge::{
    self, HandlerTime, ProfileSample, ProfileTick, ScopeTime, SourceRef,
};
use serde::Serialize;

/// Ticks in a batch of the stream: a second's.
pub const BATCH_TICKS: u64 = 20;

pub const TICKS_PER_SECOND: u64 = 20;

/// The longest `/nf profile`: ten minutes keeps a report a few megabytes.
pub const MAX_SECONDS: u64 = 600;

/// Scopes named per tick, the most expensive first.
pub const TOP_SCOPES: usize = 3;

/// A report written (what to tell, and whom: the recording's number).
type Written = (String, usize);
/// Whoever asked for a report, told what came of it.
type Done = Box<dyn Fn(String)>;
/// Where samples go besides the bridge.
pub type Sink = Box<dyn Fn(&ProfileSample)>;

/// One function's cost in one scope, named: `script` its scope's script (`centity tower`), `scope` the scope itself.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Call {
    pub script: String,
    pub scope: String,
    pub kind: String,
    pub source: Option<SourceRef>,
    pub nanos: i64,
    pub calls: i32,
    pub max: i64,
}

#[derive(Default)]
struct ScopeSum {
    nanos: i64,
    calls: i32,
    max: i64,
}

#[derive(Default)]
struct HandlerSum {
    nanos: i64,
    calls: i32,
    max: i64,
    scopes: BTreeSet<String>,
}

type HandlerKey = (String, String, Option<(String, Option<i32>)>);

/// Times summed over a run of ticks: a batch, or a whole recording.
#[derive(Default)]
struct Totals {
    ticks: Vec<ProfileTick>,
    scopes: HashMap<String, ScopeSum>,
    handlers: HashMap<HandlerKey, HandlerSum>,
}

impl Totals {
    fn scope(&mut self, time: &ScopeTime) {
        let sum = self.scopes.entry(time.scope.clone()).or_default();
        sum.nanos += time.nanos;
        sum.calls += time.calls;
        sum.max = sum.max.max(time.nanos);
    }

    fn call(&mut self, call: &Call) {
        let key = (
            call.script.clone(),
            call.kind.clone(),
            call.source.as_ref().map(|s| (s.file.clone(), s.line)),
        );
        let sum = self.handlers.entry(key).or_default();
        sum.nanos += call.nanos;
        sum.calls += call.calls;
        sum.max = sum.max.max(call.max);
        sum.scopes.insert(call.scope.clone());
    }

    fn scope_times(&self) -> Vec<ScopeTime> {
        let mut out: Vec<ScopeTime> = self
            .scopes
            .iter()
            .map(|(name, sum)| ScopeTime {
                scope: name.clone(),
                nanos: sum.nanos,
                calls: sum.calls,
                max: sum.max,
            })
            .collect();
        out.sort_by(|a, b| b.nanos.cmp(&a.nanos).then(a.scope.cmp(&b.scope)));
        out
    }

    fn handler_times(&self) -> Vec<HandlerTime> {
        let mut out: Vec<HandlerTime> = self
            .handlers
            .iter()
            .map(|((script, kind, source), sum)| HandlerTime {
                script: script.clone(),
                kind: kind.clone(),
                source: source.as_ref().map(|(file, line)| SourceRef {
                    file: file.clone(),
                    line: *line,
                }),
                calls: sum.calls,
                nanos: sum.nanos,
                max: sum.max,
                scopes: sum.scopes.len() as i32,
            })
            .collect();
        out.sort_by(|a, b| {
            b.nanos
                .cmp(&a.nanos)
                .then_with(|| a.script.cmp(&b.script))
                .then_with(|| a.kind.cmp(&b.kind))
                .then_with(|| {
                    let at = |h: &HandlerTime| h.source.as_ref().map(|s| (s.file.clone(), s.line));
                    at(a).cmp(&at(b))
                })
        });
        out
    }

    fn sample(&self) -> ProfileSample {
        ProfileSample {
            ticks: self.ticks.clone(),
            scopes: self.scope_times(),
            handlers: self.handler_times(),
        }
    }
}

/// One `/nf profile` run: the ticks after `first_tick` up to `last_tick`.
struct Recording {
    first_tick: u64,
    last_tick: u64,
    seconds: u64,
    started: chrono::DateTime<chrono::FixedOffset>,
    done: Box<dyn Fn(String)>,
    totals: Totals,
}

/// The profiler: runtime-level, made once with the plugin.
pub struct Profiler {
    /// A dev server: always measuring.
    dev: Cell<bool>,
    /// Whether the editor wants the stream, and where its samples go (the dev bridge).
    stream: RefCell<Option<Rc<crate::bridge::Bridge>>>,
    /// Where reports go: `plugins/NetherForge/profiles`.
    directory: PathBuf,
    /// The server and plugin, for a report's heading.
    server: String,
    /// The wall clock, for a report's name and start: a test's, fixed, or the system's.
    now: Box<dyn Fn() -> chrono::DateTime<chrono::FixedOffset>>,
    recording: RefCell<Option<Recording>>,
    batch: RefCell<Totals>,
    tick_scopes: RefCell<Vec<ScopeTime>>,
    /// What each step of the tick is made of this tick, by path (`services/centities`).
    parts: RefCell<IndexMap<String, i64>>,
    /// Reports written on their own thread: what to tell whoever asked, on the main thread.
    written: (Sender<Written>, Receiver<Written>),
    writing: RefCell<HashMap<usize, Done>>,
    next_write: Cell<usize>,
    log: RefCell<Option<Rc<dyn netherforge_platform::Platform>>>,
    /// Where samples go besides the bridge, wanted as the editor's stream is (tests).
    sink: RefCell<Option<Sink>>,
}

impl Profiler {
    /// A profiler writing reports into `directory`, naming `server` in them.
    pub fn new(directory: PathBuf, server: String) -> Profiler {
        Profiler {
            dev: Cell::new(false),
            stream: RefCell::new(None),
            directory,
            server,
            now: Box::new(|| chrono::Local::now().fixed_offset()),
            recording: RefCell::new(None),
            batch: RefCell::new(Totals::default()),
            tick_scopes: RefCell::new(Vec::new()),
            parts: RefCell::new(IndexMap::new()),
            written: channel(),
            writing: RefCell::new(HashMap::new()),
            next_write: Cell::new(0),
            log: RefCell::new(None),
            sink: RefCell::new(None),
        }
    }

    /// Hands every sample to `sink` as it would the editor's stream, which counts as subscribed (tests).
    pub fn set_sink(&self, sink: Option<Sink>) {
        *self.sink.borrow_mut() = sink;
    }

    /// The wall clock reports are named and dated by (a test's).
    pub fn with_clock(
        mut self,
        now: impl Fn() -> chrono::DateTime<chrono::FixedOffset> + 'static,
    ) -> Profiler {
        self.now = Box::new(now);
        self
    }

    /// A dev server's: it always measures, and streams to the editor while it subscribes.
    pub fn attach_bridge(&self, bridge: Rc<crate::bridge::Bridge>) {
        self.dev.set(true);
        *self.stream.borrow_mut() = Some(bridge);
    }

    /// Where the profiler logs what happened to a report.
    pub fn set_log(&self, platform: Rc<dyn netherforge_platform::Platform>) {
        *self.log.borrow_mut() = Some(platform);
    }

    /// Measure on a dev server even without a bridge (tests).
    pub fn set_dev(&self, dev: bool) {
        self.dev.set(dev);
    }

    fn streaming(&self) -> bool {
        self.sink.borrow().is_some()
            || self
                .stream
                .borrow()
                .as_ref()
                .is_some_and(|b| b.profiler_streaming())
    }

    /// Whether to measure at all.
    pub fn active(&self) -> bool {
        self.dev.get() || self.streaming() || self.recording.borrow().is_some()
    }

    /// Whether `calls` is wanted at the end of tick `tick`: a batch ends there.
    pub fn due(&self, tick: u64) -> bool {
        tick.is_multiple_of(BATCH_TICKS)
            || self
                .recording
                .borrow()
                .as_ref()
                .is_some_and(|r| r.last_tick == tick)
    }

    /// A part of this tick's steps took `nanos` (a service's tick): added under `name`.
    pub fn part(&self, name: &str, nanos: u64) {
        *self.parts.borrow_mut().entry(name.to_owned()).or_default() += nanos as i64;
    }

    /// Each scope's own time this tick, named (`calls` its calls in).
    pub fn scopes(&self, scopes: Vec<ScopeTime>) {
        let mut batch = self.batch.borrow_mut();
        let mut recording = self.recording.borrow_mut();
        for scope in &scopes {
            batch.scope(scope);
            if let Some(r) = recording.as_mut() {
                r.totals.scope(scope);
            }
        }
        *self.tick_scopes.borrow_mut() = scopes;
    }

    /// What each function cost since the last time, one entry per scope and function.
    pub fn calls(&self, calls: &[Call]) {
        let mut batch = self.batch.borrow_mut();
        let mut recording = self.recording.borrow_mut();
        for call in calls {
            batch.call(call);
            if let Some(r) = recording.as_mut() {
                r.totals.call(call);
            }
        }
    }

    /// One tick ended: `nanos` in all, `phases` by step. Ends a batch when it's due.
    pub fn tick(&self, tick: u64, nanos: u64, phases: IndexMap<String, i64>) {
        let mut scopes = std::mem::take(&mut *self.tick_scopes.borrow_mut());
        let scripts = scopes.iter().map(|s| s.nanos).sum();
        scopes.sort_by_key(|s| std::cmp::Reverse(s.nanos));
        let top = scopes
            .into_iter()
            .take(TOP_SCOPES)
            .map(|s| ScopeTime { max: s.nanos, ..s })
            .collect();
        let sample = ProfileTick {
            tick: tick as i64,
            nanos: nanos as i64,
            phases,
            scripts,
            top,
            parts: std::mem::take(&mut *self.parts.borrow_mut()),
        };
        self.batch.borrow_mut().ticks.push(sample.clone());
        let ends = {
            let mut recording = self.recording.borrow_mut();
            if let Some(r) = recording.as_mut()
                && tick > r.first_tick
            {
                r.totals.ticks.push(sample);
            }
            recording.as_ref().is_some_and(|r| tick >= r.last_tick)
        };
        if self.due(tick) {
            self.flush();
        }
        if ends && let Some(recording) = self.recording.borrow_mut().take() {
            self.finish(recording);
        }
    }

    /// Sends what's gathered since the last batch, if the editor wants it, and starts a new batch.
    pub fn flush(&self) {
        let batch = std::mem::take(&mut *self.batch.borrow_mut());
        let sample = batch.sample();
        if sample.ticks.is_empty() {
            return;
        }
        if let Some(sink) = self.sink.borrow().as_ref() {
            sink(&sample);
        }
        if let Some(bridge) = self.stream.borrow().as_ref()
            && bridge.profiler_streaming()
        {
            bridge.stream::<bridge::Profiler>(&sample);
        }
    }

    /// How many seconds the recording running has left, or none when none is.
    pub fn recording_left(&self, tick: u64) -> Option<u64> {
        self.recording
            .borrow()
            .as_ref()
            .map(|r| r.last_tick.saturating_sub(tick).div_ceil(TICKS_PER_SECOND))
    }

    /// Starts recording the `seconds` after server tick `tick`; `done` hears where the report went (or why it
    /// couldn't be written), on the main thread. Refused while another recording runs. What was gathered before
    /// isn't part of it: the caller has the session hand over what's pending first, and the batch is flushed.
    pub fn record(&self, tick: u64, seconds: u64, done: Box<dyn Fn(String)>) -> Result<(), String> {
        if !(1..=MAX_SECONDS).contains(&seconds) {
            return Err(format!("a profile runs 1 to {MAX_SECONDS} seconds"));
        }
        if let Some(left) = self.recording_left(tick) {
            return Err(format!(
                "a profile is already running ({left} s left); wait for its report"
            ));
        }
        self.flush();
        *self.recording.borrow_mut() = Some(Recording {
            first_tick: tick,
            last_tick: tick + seconds * TICKS_PER_SECOND,
            seconds,
            started: (self.now)(),
            done,
            totals: Totals::default(),
        });
        Ok(())
    }

    fn finish(&self, recording: Recording) {
        let report = ProfileReport::of(
            &recording.started,
            recording.seconds,
            &self.server,
            &recording.totals,
        );
        let stamp = recording.started.format("%Y%m%d-%H%M%S").to_string();
        let id = self.next_write.get() + 1;
        self.next_write.set(id);
        self.writing.borrow_mut().insert(id, recording.done);
        let directory = self.directory.clone();
        let sender = self.written.0.clone();
        std::thread::Builder::new()
            .name("netherforge profile writer".into())
            .spawn(move || {
                let message = match report.write(&directory, &format!("profile-{stamp}")) {
                    Ok(text) => {
                        let json = text.with_extension("json");
                        format!(
                            "Profile written to {} (and {} beside it)",
                            text.display(),
                            json.file_name().unwrap_or_default().to_string_lossy()
                        )
                    }
                    Err(e) => format!(
                        "Couldn't write the profile into {}: {e}",
                        directory.display()
                    ),
                };
                let _ = sender.send((message, id));
            })
            .expect("a thread for the profile's report");
    }

    /// Tells whoever asked what came of the reports written since the last time: on the main thread, each tick.
    pub fn poll(&self) {
        while let Ok((message, id)) = self.written.1.try_recv() {
            if let Some(platform) = self.log.borrow().as_ref() {
                platform.log().info(message.clone());
            }
            if let Some(done) = self.writing.borrow_mut().remove(&id) {
                done(message);
            }
        }
    }

    /// Waits for the reports being written (tests, and the plugin disabling): what they said is then told.
    pub fn await_writes(&self) {
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(30);
        while !self.writing.borrow().is_empty() && std::time::Instant::now() < deadline {
            match self
                .written
                .1
                .recv_timeout(std::time::Duration::from_millis(50))
            {
                Ok((message, id)) => {
                    if let Some(platform) = self.log.borrow().as_ref() {
                        platform.log().info(message.clone());
                    }
                    if let Some(done) = self.writing.borrow_mut().remove(&id) {
                        done(message);
                    }
                }
                Err(_) => continue,
            }
        }
    }
}

/// A time over the ticks recorded: mean, median, 95th percentile and worst, in nanoseconds; `worst_tick` is when.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Stats {
    pub mean: i64,
    pub median: i64,
    pub p95: i64,
    pub max: i64,
    pub worst_tick: Option<i64>,
}

impl Stats {
    fn of(values: &[i64], ticks: &[i64]) -> Stats {
        if values.is_empty() {
            return Stats {
                mean: 0,
                median: 0,
                p95: 0,
                max: 0,
                worst_tick: None,
            };
        }
        let mut sorted = values.to_vec();
        sorted.sort();
        // The first of the worst, as Kotlin's `maxBy`.
        let worst =
            (0..values.len()).fold(0, |best, i| if values[i] > values[best] { i } else { best });
        Stats {
            mean: values.iter().sum::<i64>() / values.len() as i64,
            median: sorted[(sorted.len() - 1) / 2],
            p95: sorted[((sorted.len() - 1) * 95) / 100],
            max: *sorted.last().expect("not empty"),
            worst_tick: Some(ticks[worst]),
        }
    }
}

/// What `/nf profile <seconds>` writes: a JSON file with every number (each tick's steps, every scope and every
/// function scripts ran), and a text file beside it that a person reads first. Times in the JSON are nanoseconds.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct ProfileReport {
    /// When the recording started, ISO-8601 with the server's offset.
    pub started: String,
    pub seconds: u64,
    /// `Paper 26.3, NetherForge 0.1.0`.
    pub server: String,
    /// NetherForge's whole tick, over the ticks recorded.
    pub tick: Stats,
    /// Each step of the tick, in the order they run.
    pub phases: IndexMap<String, Stats>,
    /// What the steps are made of, by path, the most expensive first.
    pub parts: IndexMap<String, Stats>,
    /// Scripts' own code, wherever in the tick it ran.
    pub scripts: Stats,
    /// Every scope that ran, the most expensive first.
    pub scopes: Vec<ScopeTime>,
    /// Every function scripts ran, the most expensive first.
    pub handlers: Vec<HandlerTime>,
    /// Every tick recorded, in order.
    pub ticks: Vec<ProfileTick>,
}

const SHOWN: usize = 30;
const NANOS_PER_MILLI: f64 = 1_000_000.0;

fn ms(nanos: i64) -> String {
    format!("{:9.3}", nanos as f64 / NANOS_PER_MILLI)
}

fn line(stats: &Stats) -> String {
    format!(
        "mean {:.3}, median {:.3}, 95th {:.3}, worst {:.3}{}",
        stats.mean as f64 / NANOS_PER_MILLI,
        stats.median as f64 / NANOS_PER_MILLI,
        stats.p95 as f64 / NANOS_PER_MILLI,
        stats.max as f64 / NANOS_PER_MILLI,
        stats
            .worst_tick
            .map_or(String::new(), |t| format!(" (tick {t})"))
    )
}

impl ProfileReport {
    fn of(
        started: &chrono::DateTime<chrono::FixedOffset>,
        seconds: u64,
        server: &str,
        totals: &Totals,
    ) -> ProfileReport {
        let ticks = totals.ticks.clone();
        let numbers: Vec<i64> = ticks.iter().map(|t| t.tick).collect();
        let mut phase_names: Vec<String> = Vec::new();
        let mut part_names: Vec<String> = Vec::new();
        for t in &ticks {
            for name in t.phases.keys() {
                if !phase_names.contains(name) {
                    phase_names.push(name.clone());
                }
            }
            for name in t.parts.keys() {
                if !part_names.contains(name) {
                    part_names.push(name.clone());
                }
            }
        }
        let stats_of = |f: &dyn Fn(&ProfileTick) -> i64| {
            Stats::of(&ticks.iter().map(f).collect::<Vec<_>>(), &numbers)
        };
        let phases = phase_names
            .iter()
            .map(|name| {
                (
                    name.clone(),
                    stats_of(&|t| t.phases.get(name).copied().unwrap_or(0)),
                )
            })
            .collect();
        let mut parts: Vec<(String, Stats)> = part_names
            .iter()
            .map(|name| {
                (
                    name.clone(),
                    stats_of(&|t| t.parts.get(name).copied().unwrap_or(0)),
                )
            })
            .collect();
        parts.sort_by_key(|p| std::cmp::Reverse(p.1.mean));
        ProfileReport {
            started: started.to_rfc3339_opts(chrono::SecondsFormat::AutoSi, true),
            seconds,
            server: server.to_owned(),
            tick: stats_of(&|t| t.nanos),
            phases,
            parts: parts.into_iter().collect(),
            scripts: stats_of(&|t| t.scripts),
            scopes: totals.scope_times(),
            handlers: totals.handler_times(),
            ticks,
        }
    }

    /// The text summary: the tick and its steps, then the scopes and the functions taking the most time, each with
    /// its total, calls, mean per call, worst call and where it is.
    pub fn summary(&self) -> String {
        let count = self.ticks.len();
        let mut out = String::new();
        let mut put = |s: String| {
            out.push_str(&s);
            out.push('\n');
        };
        put(format!(
            "NetherForge profile: {} s from {} ({count} ticks), on {}",
            self.seconds, self.started, self.server
        ));
        put(String::new());
        put(format!("NetherForge's tick (ms): {}", line(&self.tick)));
        for (name, stats) in &self.phases {
            put(format!("  {name:<9} {}", line(stats)));
        }
        if !self.parts.is_empty() {
            put(
                "What the steps are made of (ms; each part inside the one its path starts with):"
                    .into(),
            );
            for (name, stats) in self.parts.iter().take(SHOWN) {
                put(format!("  {name}: {}", line(stats)));
            }
        }
        put(format!("Scripts' own code (ms): {}", line(&self.scripts)));
        put(String::new());
        put("Functions by total time (ms; mean and worst per call):".into());
        put(format!(
            "  {:>9} {:>9} {:>9} {:>9}  script, call, where",
            "total", "calls", "mean", "worst"
        ));
        for handler in self.handlers.iter().take(SHOWN) {
            let place = handler.source.as_ref().map_or_else(
                || "(NetherForge's own code)".to_owned(),
                |s| match s.line {
                    Some(line) => format!("{}:{line}", s.file),
                    None => s.file.clone(),
                },
            );
            let scopes = if handler.scopes > 1 {
                format!(" in {} scopes", handler.scopes)
            } else {
                String::new()
            };
            put(format!(
                "  {} {:>9} {} {}  {}, {}{scopes}, {place}",
                ms(handler.nanos),
                handler.calls,
                ms(handler.nanos / i64::from(handler.calls.max(1))),
                ms(handler.max),
                handler.script,
                handler.kind
            ));
        }
        if self.handlers.len() > SHOWN {
            put(format!(
                "  …and {} more in the JSON",
                self.handlers.len() - SHOWN
            ));
        }
        put(String::new());
        put("Scopes by total time (ms; mean a tick and worst tick):".into());
        put(format!(
            "  {:>9} {:>9} {:>9} {:>9}  scope",
            "total", "calls", "mean", "worst"
        ));
        for scope in self.scopes.iter().take(SHOWN) {
            put(format!(
                "  {} {:>9} {} {}  {}",
                ms(scope.nanos),
                scope.calls,
                ms(scope.nanos / count.max(1) as i64),
                ms(scope.max),
                scope.scope
            ));
        }
        if self.scopes.len() > SHOWN {
            put(format!(
                "  …and {} more in the JSON",
                self.scopes.len() - SHOWN
            ));
        }
        out
    }

    /// Writes `<name>.json` and `<name>.txt` into `directory` (each through a temporary file), and answers the
    /// text file.
    pub fn write(&self, directory: &Path, name: &str) -> std::io::Result<PathBuf> {
        std::fs::create_dir_all(directory)?;
        let json = directory.join(format!("{name}.json"));
        let text = directory.join(format!("{name}.txt"));
        let encoded = serde_json::to_string_pretty(self).map_err(std::io::Error::other)? + "\n";
        atomically(&json, &encoded)?;
        atomically(&text, &self.summary())?;
        Ok(text)
    }
}

fn atomically(file: &Path, contents: &str) -> std::io::Result<()> {
    let temp = file.with_file_name(format!(
        "{}.tmp",
        file.file_name().unwrap_or_default().to_string_lossy()
    ));
    std::fs::write(&temp, contents)?;
    std::fs::rename(&temp, file)
}
