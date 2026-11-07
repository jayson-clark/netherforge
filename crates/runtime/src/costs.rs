//! What each scope's code costs the server (Kotlin's `ScriptCosts`), for `/nf scripts` and for warning about a script
//! that's slowing the server down.
//!
//! The host times every call in (a body, a handler, a timer, a task's resume, a command), each its own time less
//! the calls nested in it, charged to its scope; [`ScriptCosts::record`] takes those totals once a tick and keeps
//! the last [`WINDOW`] ticks per scope. Work done between ticks (a reload run from the bridge) lands in the next
//! tick. A scope whose average over the last `performance.warn-ticks` ticks is over `performance.warn-ms` is
//! [`Slow`], at most once per [`QUIET_TICKS`] per scope; its first tick (where its body's load lands) is never part
//! of that average: a slow start is a one-off, which `/nf scripts` shows as its max.

use std::collections::HashMap;

use netherforge_luau::{Host, Location, ScopeCost, ScopeId};

use crate::config::PerformanceConfig;

/// Ticks of history kept per scope: what `/nf scripts` averages over.
pub const WINDOW: usize = 100;

/// Ticks after warning about a scope before it's warned about again.
pub const QUIET_TICKS: u64 = 1200;

const NANOS_PER_MILLI: f64 = 1_000_000.0;

/// A scope over its time: its average over `ticks` ticks, and where its slowest recent call's function is.
#[derive(Clone, Debug, PartialEq)]
pub struct Slow {
    pub scope: ScopeId,
    pub average_millis: f64,
    pub ticks: usize,
    pub at: Option<Location>,
}

/// What `/nf scripts` shows for one scope: milliseconds a tick over the window, calls in, subscriptions, tasks,
/// what else it holds (each service's count, by the word it's shown as), and the bytes its script's memory key
/// holds.
#[derive(Clone, Debug, PartialEq)]
pub struct Entry {
    pub scope: ScopeId,
    pub title: String,
    pub average_millis: f64,
    pub max_millis: f64,
    pub calls: u32,
    pub subscriptions: usize,
    pub tasks: usize,
    pub counts: Vec<(&'static str, usize)>,
    pub bytes: usize,
}

struct Window {
    nanos: [u64; WINDOW],
    calls: [u32; WINDOW],
    recorded: usize,
    sum: u64,
    call_sum: u32,
    recent_sum: u64,
    quiet_until: Option<u64>,
}

impl Window {
    fn new() -> Window {
        Window {
            nanos: [0; WINDOW],
            calls: [0; WINDOW],
            recorded: 0,
            sum: 0,
            call_sum: 0,
            recent_sum: 0,
            quiet_until: None,
        }
    }

    fn push(&mut self, recent: usize, time: u64, count: u32) {
        let i = self.recorded % WINDOW;
        if self.recorded >= recent {
            self.recent_sum -= self.nanos[(self.recorded - recent) % WINDOW];
        }
        if self.recorded >= WINDOW {
            self.sum -= self.nanos[i];
            self.call_sum -= self.calls[i];
        }
        self.nanos[i] = time;
        self.calls[i] = count;
        self.sum += time;
        self.call_sum += count;
        self.recent_sum += time;
        self.recorded += 1;
    }

    fn ticks(&self) -> usize {
        self.recorded.min(WINDOW)
    }

    fn average(&self) -> f64 {
        match self.ticks() {
            0 => 0.0,
            n => self.sum as f64 / n as f64 / NANOS_PER_MILLI,
        }
    }

    fn max(&self) -> f64 {
        self.nanos[..self.ticks()]
            .iter()
            .copied()
            .max()
            .unwrap_or(0) as f64
            / NANOS_PER_MILLI
    }
}

/// The session's costs: each live scope's window, and what the last tick took.
#[derive(Default)]
pub struct ScriptCosts {
    performance: Option<PerformanceConfig>,
    windows: HashMap<ScopeId, Window>,
    last_tick: HashMap<ScopeId, ScopeCost>,
}

/// What a tick's accounts found: the scopes over their time, and those whose timers or `tick` handlers waited.
#[derive(Default)]
pub struct Recorded {
    pub slow: Vec<Slow>,
    pub deferred: Vec<ScopeId>,
}

impl ScriptCosts {
    /// Costs warned about by `performance` (none: never warned about).
    pub fn new(performance: Option<PerformanceConfig>) -> ScriptCosts {
        ScriptCosts {
            performance,
            ..ScriptCosts::default()
        }
    }

    fn recent(&self) -> usize {
        self.performance
            .map_or(20, |p| p.warn_ticks.clamp(1, WINDOW as i64) as usize)
    }

    /// What each scope's code took in the tick `record` last took, by scope: only scopes that ran (the profiler
    /// reads it).
    pub fn last_tick(&self) -> &HashMap<ScopeId, ScopeCost> {
        &self.last_tick
    }

    /// Takes the time every scope's code took since the last tick, at the end of server tick `tick`; `live` is
    /// every scope still running.
    pub fn record(&mut self, host: &Host, live: &[ScopeId], tick: u64) -> Recorded {
        let recent = self.recent();
        let costs = host.take_costs(tick.is_multiple_of(recent as u64));
        let deferred = host
            .take_deferred()
            .into_iter()
            .filter(|s| live.contains(s))
            .collect();
        self.windows.retain(|s, _| live.contains(s));
        let warn = self.performance.map_or(0.0, |p| p.warn_millis);
        let limit = (warn * NANOS_PER_MILLI * recent as f64) as u64;
        let mut slow = Vec::new();
        for &scope in live {
            let window = self.windows.entry(scope).or_insert_with(Window::new);
            let cost = costs.get(&scope).copied().unwrap_or_default();
            window.push(recent, cost.nanos, cost.calls);
            if warn > 0.0
                && window.recorded > recent
                && window.recent_sum > limit
                && window.quiet_until.is_none_or(|q| tick >= q)
            {
                window.quiet_until = Some(tick + QUIET_TICKS);
                slow.push(Slow {
                    scope,
                    average_millis: window.recent_sum as f64 / recent as f64 / NANOS_PER_MILLI,
                    ticks: recent,
                    at: host.hot_spot(scope),
                });
            }
        }
        self.last_tick = costs;
        Recorded { slow, deferred }
    }

    /// The window's numbers for `scope`: its average and worst milliseconds a tick, and its calls.
    pub fn of(&self, scope: ScopeId) -> (f64, f64, u32) {
        self.windows
            .get(&scope)
            .map_or((0.0, 0.0, 0), |w| (w.average(), w.max(), w.call_sum))
    }

    /// Forgets everything (the Lua state closed).
    pub fn clear(&mut self) {
        self.windows.clear();
        self.last_tick.clear();
    }
}

/// `entries`, the most expensive first.
pub fn sorted(mut entries: Vec<Entry>) -> Vec<Entry> {
    entries.sort_by(|a, b| {
        b.average_millis
            .total_cmp(&a.average_millis)
            .then(b.max_millis.total_cmp(&a.max_millis))
    });
    entries
}

/// A scope's title without an instance's short id: the script it runs (`centity tower 1a2b3c4d` is `centity
/// tower`), as the profiler and the warnings name it.
pub fn script_label(title: &str) -> String {
    match title.rsplit_once(' ') {
        Some((head, tail))
            if head.contains(' ')
                && tail.len() == 8
                && tail.chars().all(|c| c.is_ascii_hexdigit()) =>
        {
            head.to_owned()
        }
        _ => title.to_owned(),
    }
}
