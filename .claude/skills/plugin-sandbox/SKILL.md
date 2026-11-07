---
name: plugin-sandbox
description: Inside the plugin's Luau state - the sandbox (the stripped base, one interrupt, the step budget, time limits, memory by the allocator and Luau's memory categories, the standard library's caps from format's caps.lua and what a pattern costs, scripts' errors as strings), what scripts cost and the profiler, the cost golden, and the debugger (DAP over the bridge, breakpoints, stepping, freezing the tick). Read before touching crates/luau (the host, its limits, timing and debugger), format's lua/caps.lua, crates/runtime/src/costs.rs, profile.rs or debug.rs.
---

# Plugin sandbox, profiler and debugger

What keeps a script from stalling the server, and the tools that look inside a running one. The Luau state itself (a
scope per script, handles, `require`, the API's bindings) is the plugin-runtime skill's; this is what runs inside it.
The host is `crates/luau` (`Host`, `Limits`, `Guard`), on mlua's Luau.

## The sandbox (`crates/luau/src/host.rs`, `limits.rs`)

- **The base is the real globals, stripped.** `Host::new` removes every global not in `BASE` (a
  whitelist: no `os`, `debug`, `getfenv`/`setfenv`, `loadstring`, `require`, `collectgarbage`,
  `print`, `_G`), then `Lua::sandbox(true)` makes the globals and every library read-only. That
  replaces the per-scope library proxies, and `rawset` on a library fails on its own. They have
  to be the real globals, not a copy: a scope's environment is `safeenv` (as
  `luaL_sandboxthread`'s proxy is, so `math.floor` is resolved at load time rather than looked
  up per call), and in a `safeenv` environment **Luau resolves a builtin's name straight from
  the real globals, past the environment's `__index`**. A copy left `os` and `_G` reachable from
  every script (`what_scripts_dont_get` holds it). A scope that assigns a global of its own
  name (`math = ...`) gets its own, as before.
- **A scope** is an environment table over the base (`open_scope(name, memory_key)`). Chunks
  are loaded into it under their project path (`@modules/m/init.lua`), so Luau's messages and
  tracebacks speak the editor's language. A `Failure::Error` keeps the message and the
  traceback apart (mlua appends one to every runtime error).
- **One interrupt does the guarding.** Luau calls it on every function call and every loop's
  backward jump, on every thread, so there's nothing to arm per coroutine and no hook for the
  debugger to share. Each call in is a frame (`invoke(scope, budget, f, args)`):
  - **The budget counts steps (interrupts), not instructions.** Luau doesn't count
    instructions. A step is a call or a loop iteration, so a script's `budget` means "how many
    iterations and calls", which is closer to what a developer can reason about anyway.
  - **The time limit** is read from the injectable `Clock` every `clock_every` (256) steps. A
    nested call in gets what's left of its caller's deadline, if that's sooner.
  - **A nested call in's steps are charged to its caller** when it returns, so a loop of
    call-ins can't outrun its own budget.
  - **A stop is sticky.** The interrupt raises an error, which `pcall` can catch, so the frame
    keeps the stop and its location: every interrupt after it raises again, and `invoke` reports
    the call stopped whatever it returned.
- **Memory is the allocator's.** `Limits::memory_bytes` is mlua's allocator limit for the
  state, so an allocation past it fails (`Failure::Memory`), exactly. No census and no
  periodic `collectgarbage("count")`.
- **Memory per scope is Luau's memory categories.** `invoke` sets the running thread's
  category (`lua_setmemcat`) to the scope's, and Luau charges every object to the category it
  was allocated under and credits its free back to that category. So `scope_memory` is exact
  at any moment, and a script churning garbage doesn't look like a leak
  (`churning_garbage_isnt_a_leak`). Luau has only 256 categories (0 is the host's), so a
  category is per **memory key**, not per scope: the runtime passes the script's resource, so
  a centity's instances share one, and past 255 keys new ones share the last. A coroutine
  inherits its creator's category, so a task stays its scope's wherever it's resumed from.
- **Blame**: when an allocation fails, the scope holding the most is blamed if that's at
  least a quarter of the limit (`take_blamed`), and the runtime stops it at the end of the
  tick, as today.
- **No proper tail calls.** Luau keeps a frame for `return f()`, so endless tail recursion is
  a stack overflow (an error), not a loop for the budget to stop.

- **The library's caps are format's `caps.lua`**, the same file the editor's Lua 5.4 runs: it's
  written for both dialects (Luau's missing `math.tointeger` and `math.ult` are written out in
  it), so there's one cost model, not two. `install_caps` runs it before the libraries are
  made read-only, with the library's own functions (`raw`, plus a native `getmetatable` that
  sees past `__metatable`, since Luau has no `debug.getmetatable`), a `spend` that moves the
  next look at the clock closer (`STEPS_PER_INTERRUPT`: a step of the caps' model is about a
  nanosecond, an interrupt about five), and a `refuse` that raises at the script's line. The
  caps and `refuse` are compiled without debug information, so the library's own errors (a bad
  argument), which it locates at its caller, aren't located there. The host then locates such a
  message at the traceback's first script frame. `tests/caps.rs` is `HangTest` on Luau.
  - Luau's own `string.rep` has no separator (it's Lua 5.1's) and silently ignores one, so the
    cap writes it, as Lua 5.4 does (`REP_IGNORES_SEP`, probed once).
  - Luau's own `%d` (and `%x`, `%c`...) drops a fraction; the cap on `format` refuses one with
    Lua 5.4's message.
  - Luau has no `__gc` for tables, so the cap on `setmetatable` refusing `__gc` changes
    nothing there. It stays for Lua 5.4's sake.

- **Rust functions scripts call raise strings** (`Host::script_function`, `waiting_function`).
  mlua raises a Rust function's error as a userdata of its own, so `pcall` would hand a script a
  value of type `userdata` where Lua and the Kotlin runtime give a string (Kosmoi's SDK inspects
  them). Every binding, `require` and `nf`'s `__newindex` go through one Luau shim (`SHIM`, without
  debug information): a failure comes back as a sentinel and its message, which the shim raises
  located at the script's line, as the caps' `refuse` does (`modules/shop/init.lua:3: no module
"x"`); a waiting binding's `waiting` yields its task there. A handle's `__newindex` (assigning to its field) is
  made through the shim too, as a meta field built with the handles' metatable (`handle.rs`: mlua won't let it be
  replaced afterwards); its other metamethods don't fail.

### The caps and what a pattern costs

- **What the caps hold** (format's `src/commonMain/lua/caps.lua`, shared with the editor and the terrain scripts'
  states, which run the same chunk). The functions whose work isn't bounded by their arguments'
  size are replaced in the real `string` and `table` tables (which the
  proxies and the string metatable read through) by wrappers that work out
  the most the call could cost first: a string over `maxStringBytes`
  (16 MB) is refused (`rep`, its count too, `format`, `gsub`'s result,
  `pack`, `table.concat`), and so is work over `maxWork` (1e8 steps, about a
  nanosecond each, so at most about a tenth of a second for one call:
  patterns, a plain `find` with a long needle, `table.move`, `insert` and
  `remove` shifting, `sort` without a comparator). What's allowed is spent
  towards the time limit's next check (`spend`). The host's own Lua (the shim, the glue) calls the library's own
  functions (`raw`), never the caps, so nothing of the host's can be refused or stopped inside one; keep it that way
  (`raw.match(s, p)`, not `s:match(p)`).
- **A pattern's cost** is the worst case of lstrlib's backtracking matcher
  for the pattern's shape (`steps`, from the last item back: what a failing
  and a successful try of the rest can cost, whether the rest can fail, and
  whether it matches nothing at the end), so it's linear for `[^,]+`,
  quadratic for `%w+=` unanchored or `^%s*(.-)%s*$` (trim: 4,000 bytes
  allowed, 6,000 refused), and L⁵ for `.-.-.-.-b`. An item whose rest can
  only start with a character outside its own class (`%s*` before `[%w_]`,
  `[^"]*` before `"`: `cheap_tries`, by matching the classes against every
  byte) costs its backtracking as L constant-cost tries, not L full tries
  of the rest, so `^%[%s*([%w_]+)%s*"` is linear. It overestimates: a
  pattern that only backtracks on unlucky input is refused on a long string
  even when this one would have been quick, which the message says. Each
  compiled pattern also keeps `scale * (L+1)^degree`, an upper bound for
  every length, and from it the longest string the call is cheap on
  (`find_safe`, `match_safe`, `every_safe`), so a call on a short string is
  a table lookup and a comparison; the exact model runs only past that.

The Kotlin runtime's benchmarks of its Lua 5.4 hook (how often to count, read the clock and the memory) don't apply
to Luau's interrupt; the numbers that matter now are measured again in the Phase 0 baseline (worklog).

## What scripts cost and the profiler (`crates/luau/src/timing.rs`, `crates/runtime/src/costs.rs`, `profile.rs`)

Exact time, not samples: the host's clock around every call in and every part of the tick, attributed to the
function that ran.

- **The profiler** (`profile.rs`'s `Profiler`, the server's: it outlives sessions, like the packs): every 20 ticks a
  `ProfileSample` (the ticks; scopes and functions summed, a function's rows merged across the scopes that ran it,
  `scopes` counting them), sent on the bridge's `profiler` stream while the editor subscribes, and added to a
  recording. **Active** means a dev server (always), or subscribed, or `/nf profile` recording: production measures
  only then. `/nf profile <seconds>` drains what's pending first, records the ticks after the command, refuses an
  overlapping one, and at the end writes `profiles/profile-<stamp>.json` and `.txt` (stats per part, scripts, every
  scope and function, every tick) on a thread of its own, then tells the sender and the console on the main thread.
- **Why not per call event.** Every call in is already timed exactly at two clock reads, so the profiler only adds
  bookkeeping per call in; line-level sampling isn't needed for per-handler numbers, and the interrupt stays the
  guard's and the debugger's stepping.

- **The host times every call in** (`Host::invoke`): two reads of the clock a frame, its own time (less the frames
  nested in it) charged to its scope, its slowest call kept per scope over this window and the last (`hot_spot`, where
  a slow warning points), its time this tick (`over_tick` against `Limits::max_tick`, which `performance.max-ms-per-tick`
  sets), and, while profiling (`set_profiling`), per function: keyed by the function's pointer, its kind the caller's
  (`Host::next_kind`: an event's name from `run_handlers`, `timer`, `task`, `callback`, `command`, `complete`, `goal`,
  `schedule`, `route`, `load` from `Modules::run_file`), its file and line read once per drain (`take_profile`). **Luau
  says no `main` for a chunk's body** and gives it line 1, so a body is told by its `load` kind, and the profiler names it
  by its file alone (line none), as Kotlin's did. A scope closed while profiling keeps its name until the next drain
  (`scope_title`), so a reloaded module's old rows land with the new.
- **The host's clock leaves paused time out** (`Host::now`, `add_paused`, `Guard::paused`): every deadline, cost and the
  session's phase timing read it, so a tick held at a breakpoint is neither slow nor stopped.
- **Costs** (`ScriptCosts`, in the session): `record` once a tick (the accounts) keeps a 100-tick window per live scope;
  a scope averaging over `warn-ms` across `warn-ticks` (its first tick never counted) is `Slow`, warned about once per
  `QUIET_TICKS` per script's place (log and the editor's console, `held back` counted) and a `script.slow` problem while
  it runs; a scope whose timers or `tick` handlers waited (`Scripts` asks `over_tick` before each) is warned about once
  per `QUIET_TICKS`. **A session made without the server's config (`Config::performance` none, every test's) warns of
  nothing and holds nothing back**: tests run unoptimised, where 25 ms a tick is easy to reach.
- **`Service::costs(scope)`**: what a scope owns, by the word `/nf scripts` shows it as (effects, cutscenes, goals,
  gradual, connections, schedules, routes, logins, placeholders, wasm instances, wasm programs, screens, videos); the
  session adds `waits` (`pending::waiting`). `Session::costs()` is `/nf scripts`' list, the most expensive first, with
  subscriptions and tasks (`Scripts::held`) and the bytes its script's memory key holds (per key, so a centity's
  instances each show the script's).
- **Phases**: Rust's tick has three, `scripts` (timers, async values, `tick` handlers), `services` (each service's share
  a part, `services/<name>`) and `accounts`. Kotlin's eight `TickPhase`s don't exist on the Rust runtime.
- **The profiler** is `ServerEvents`' (it outlives sessions; `set_profiler` hands the adapter's, writing into the plugin's
  `profiles/`); the session syncs the host's bookkeeping to `active()` at each tick's start and as each Lua state starts.
  Samples go on the bridge's `profiler` stream while the editor subscribes (or to a test's `set_sink`). Reports are
  written on a thread of their own; whoever asked is told on the main thread (`poll` each tick, `await_writes`). The
  first run's bodies aren't profiled: the session starts before `ServerEvents` (and its profiler) exists.

## The cost golden (`crates/runtime/tests/costs.rs`)

What scripts do all the time (vector maths, a handle's methods, item tables, JSON, events, a handler's way in,
subscribing among many others, spawning a centity), in **steps of their budget**, held to
`crates/runtime/tests/data/scenario-costs.tsv`, as Kotlin's `ApiCostTest` held its Lua 5.4 instructions. A step is
one interrupt (a call or a loop's turn) or work the runtime charged (`Host::charge`), so most calls cost a handful;
what grows with its input (a loop over blocks) shows. A cost may move by a tenth (at least 3) either way; further,
either way, fails: dearer is a regression to look at, cheaper means the golden is out of date. A scenario with no
line, or a line with no scenario, fails too. `UPDATE_GOLDEN=1 cargo test -p netherforge-runtime --test costs`
rewrites it; review the diff like any golden's. Kotlin's per-function golden (every function called once from its
conformance test) has no Rust counterpart: the scenarios cover the paths between functions, and a function's own
cost is its binding's handful of steps plus what it charges.

## The debugger (`crates/luau/src/debug.rs`, `crates/runtime/src/debug.rs`)

Kotlin's `Debugger` and `prelude/debugger.lua`. The editor's DAP client (`apps/editor/src/core/debug`) is unchanged: the
messages are DAP's, the same requests answered, the same events (`initialized`, `stopped`, `continued`).

- **Lines come from Luau's single-stepping** (`lua_singlestep`, the `debugstep` callback before every instruction), on
  every thread (`userthread` keeps the set; a new thread inherits its maker's), only while there are breakpoints or a
  step or pause is under way: a server nobody debugs runs the plain VM. A new line is a change of thread, stack depth
  or line; only then is the chunk read. Luau picks the stepping VM as a call enters from the host, so a change applies
  from the next call in: the config is handed over at each tick's start anyway. `lua_breakpoint` (bytecode patching)
  was the alternative: it needs every function each chunk made, and stepping needs single steps regardless.
- **A stop** calls a Rust function on the stopped thread from the callback (kept in the registry), so mlua reads that
  thread's stack as a callback's: frames, locals (chunks are compiled with debug level 2 for their names: nothing at run
  time), upvalues and the scope's globals (`lua_getfenv`), all copied at once; values read raw. The runtime's
  `HostHandler` holds there: it sends `stopped`, answers `stackTrace`/`scopes`/`variables` from the debugger's inbox,
  takes new configuration at once, calls `PauseOps::hold` once a second, and returns how to go on; the time held goes to
  `Guard::paused`. A frame's name is Luau's debug name (a local function's), `(body)` for a chunk, else `function (line
N)`; Luau's names are where a function was defined, not Kotlin's call-site names.
- **Steps** count stack depth: over stops at the next line at or above the step's depth, out above it, in anywhere. On the
  main thread a step belongs to the host's call in (`Frame::serial`): a nested call in runs through, and once the call
  returns the step ends. In a task it follows the thread (a freed thread ends it).
- **Break on errors**: while on, `Host::invoke` calls through `xpcall` with a Rust message handler that stops at the error
  with its stack whole and appends the traceback; then the error is raised again, so it's reported as ever. Only errors
  no `pcall` catches reach it. Not in tasks (a task's resume catches its error first).
- **Handles** show as Kotlin's (`Player Steve`, `Centity tower 1a2b3c4d` with its position, `Node top of tower 1a2b3c4d`,
  `World world`, else class and keys); events as `Event <name>`, expanding to their fields (the view's chain, raw).
- **Configuration** arrives on the bridge's thread (`bridge::Debugger::receive`) and waits under a mutex with a version;
  `Debugger::sync` hands it to the host at each tick's start (`ServerEvents::tick`) and each new Lua state gets it before
  any body runs (`Session::set_on_host`, installed by `ServerEvents::attach_bridge`/`attach_debugger`), so breakpoints in
  bodies hold on a full reload. The bridge's connection ending detaches: a held server goes on.
- **Paths**: chunks are project or package paths; a `setBreakpoints` path that's absolute and under the project's folder
  is mapped to its project path, and one under a package's folder (a path dependency's, or the package cache's
  checkout: `set_packages`, from the project files' `folders`) to its package path, the innermost folder winning. Not
  yet: a coroutine's stop listing the main thread's frames below it.
- Tests: `crates/luau/tests/debug.rs` (the host's half with a recording handler), `crates/runtime/tests/debugger.rs`
  (DAP end to end: a thread plays the editor while the test's thread is held in its tick).
