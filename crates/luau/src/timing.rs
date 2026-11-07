//! What every call in costs, by the clock (the prelude's `timing`, Kotlin's `ScriptCosts`' and the profiler's
//! source): each frame's own time (less the frames nested in it) charged to its scope, the slowest call per scope
//! over two windows (where a slow script's warning points), the time a scope's code ran this tick (what holds back
//! its timers and `tick` handlers past `Limits::max_tick`), and, while the profiler is on, each function's own time.

use std::collections::{HashMap, HashSet};

use mlua::Function;

use crate::host::ScopeId;

/// What one scope's code took since the host was last asked: its own time and how many calls in.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct ScopeCost {
    pub nanos: u64,
    pub calls: u32,
}

/// What one function cost one scope while the profiler was on: `kind` is what the call was (`tick`, an event's
/// name, `timer`, `task`, `command`, `load` for a script's body, `callback`), `file` and `line` where the function
/// starts (none when it isn't project code).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FunctionCost {
    pub scope: ScopeId,
    pub kind: String,
    pub file: Option<String>,
    pub line: Option<u32>,
    pub nanos: u64,
    pub calls: u32,
    pub max: u64,
}

pub(crate) struct Entry {
    nanos: u64,
    calls: u32,
    max: u64,
    kind: Option<String>,
    /// What it ran, kept to say where it starts when the profile is taken.
    func: Function,
}

#[derive(Default)]
pub(crate) struct Timing {
    costs: HashMap<ScopeId, ScopeCost>,
    /// Each scope's slowest call in this window and the one before, and its function.
    slow: HashMap<ScopeId, (u64, Function)>,
    last_slow: HashMap<ScopeId, (u64, Function)>,
    /// Each scope's own time this tick, against `Limits::max_tick`.
    this_tick: HashMap<ScopeId, u64>,
    deferred: HashSet<ScopeId>,
    profiling: bool,
    functions: HashMap<(ScopeId, usize), Entry>,
    /// What the next frame runs, when its caller knows better than its function: its kind.
    pub(crate) next_kind: Option<String>,
}

impl Timing {
    /// A frame of `scope` that ran `f` closed, `own` nanoseconds its own.
    pub(crate) fn close(&mut self, scope: ScopeId, f: &Function, own: u64, kind: Option<String>) {
        let cost = self.costs.entry(scope).or_default();
        cost.nanos += own;
        cost.calls += 1;
        *self.this_tick.entry(scope).or_default() += own;
        if self.slow.get(&scope).is_none_or(|(ns, _)| own > *ns) {
            self.slow.insert(scope, (own, f.clone()));
        }
        if self.profiling {
            let entry = self
                .functions
                .entry((scope, f.to_pointer() as usize))
                .or_insert_with(|| Entry {
                    nanos: 0,
                    calls: 0,
                    max: 0,
                    kind: kind.clone(),
                    func: f.clone(),
                });
            entry.nanos += own;
            entry.calls += 1;
            entry.max = entry.max.max(own);
        }
    }

    pub(crate) fn take_costs(&mut self, rotate: bool) -> HashMap<ScopeId, ScopeCost> {
        self.this_tick.clear();
        if rotate {
            self.last_slow = std::mem::take(&mut self.slow);
        }
        std::mem::take(&mut self.costs)
    }

    pub(crate) fn hot_spot(&self, scope: ScopeId) -> Option<Function> {
        let now = self.slow.get(&scope);
        let before = self.last_slow.get(&scope);
        match (now, before) {
            (Some((a, f)), Some((b, g))) => Some(if b > a { g } else { f }.clone()),
            (Some((_, f)), None) | (None, Some((_, f))) => Some(f.clone()),
            (None, None) => None,
        }
    }

    pub(crate) fn over_tick(&mut self, scope: ScopeId, limit: u64) -> bool {
        if limit == 0 || self.this_tick.get(&scope).copied().unwrap_or(0) < limit {
            return false;
        }
        self.deferred.insert(scope);
        true
    }

    pub(crate) fn take_deferred(&mut self) -> Vec<ScopeId> {
        let mut scopes: Vec<ScopeId> = self.deferred.drain().collect();
        scopes.sort();
        scopes
    }

    pub(crate) fn profiling(&self) -> bool {
        self.profiling
    }

    pub(crate) fn set_profiling(&mut self, on: bool) {
        self.profiling = on;
        if !on {
            self.functions.clear();
        }
    }

    pub(crate) fn take_functions(&mut self) -> Vec<(ScopeId, Entry)> {
        self.functions
            .drain()
            .map(|((scope, _), entry)| (scope, entry))
            .collect()
    }

    pub(crate) fn forget(&mut self, scope: ScopeId) {
        self.slow.remove(&scope);
        self.last_slow.remove(&scope);
    }
}

/// What the profiler kept, each function's file and line said.
pub(crate) fn resolve(functions: Vec<(ScopeId, Entry)>) -> Vec<FunctionCost> {
    let mut out: Vec<FunctionCost> = functions
        .into_iter()
        .map(|(scope, entry)| {
            let info = entry.func.info();
            let file = info
                .source
                .as_deref()
                .and_then(|s| s.strip_prefix('@'))
                .map(str::to_owned);
            // A script's body (Luau's main function) starts at no line of its own. Luau says "main" for none,
            // so the runtime says `load` for the bodies it runs.
            let body = info.what == "main" || entry.kind.as_deref() == Some("load");
            let line = file.as_ref().map(|_| {
                if body {
                    0
                } else {
                    info.line_defined.unwrap_or(0) as u32
                }
            });
            let kind = entry.kind.unwrap_or_else(|| {
                if file.is_some() && body {
                    "load".into()
                } else {
                    "callback".into()
                }
            });
            FunctionCost {
                scope,
                kind,
                file,
                line,
                nanos: entry.nanos,
                calls: entry.calls,
                max: entry.max,
            }
        })
        .collect();
    out.sort_by(|a, b| (a.scope, &a.file, a.line).cmp(&(b.scope, &b.file, b.line)));
    out
}
