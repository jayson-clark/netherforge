---
name: plugin-sandbox
description: Inside the plugin's one Lua state - the sandbox (one hook, the instruction budget, time limits, the memory census, the standard library's caps and what a pattern costs, with the benchmarks behind every number), the profiler, the debugger (DAP over the bridge, breakpoints, stepping, freezing the tick), and the prelude's modules. Read before touching apps/plugin/runtime/.../lua, the prelude (lua/prelude.lua, lua/prelude/*.lua, lua/caps.lua), SandboxLimits, .../profile or .../debug.
---

# Plugin sandbox, profiler and debugger

What keeps a script from stalling the server, and the tools that look inside a running one. The Lua state itself (one state, a scope per script, handles, `require`) is the plugin-runtime skill's "Lua"; this is what runs inside it.

## The sandbox: the hook, the budget, time, memory and the library's caps

A script can stall the server three ways: running Lua for too long, running
one C function for too long (a call to `string.rep` is one VM instruction
however long it takes), or taking memory. Each has its own guard, and
`SandboxLimits` (`lua/SandboxLimits.kt`, passed to `LuaHost` through
`RuntimeConfig.sandbox`; `TestServer(sandbox = ...)` in tests) holds every
number. `HangTest` is the list of known hangs, each a test that fails at the
script's line; `SandboxLimitsTest` covers the rest.

- **One hook.** `hooks.run` is the only debug hook, set on the main thread
  at load and on every coroutine a script makes (`hooks.arm`, from the
  sandbox's `coroutine.create`/`wrap` and `nf.task`; `hooks.threads` keeps
  each armed thread, weakly). Its count event drives everything below. Any
  other event goes to `hooks.listeners[event]`, with `hooks.mask` the events
  they want, set on every live thread by `hooks.set_mask`: **the debugger's
  line hook** plugs in there (see "The debugger"), not with a hook of its own
  (a second `sethook` replaces this one and the budget stops; the profiler
  needs none: see "The profiler"). `describe` also calls
  `hooks.listeners.error` with an uncaught error's message, where the
  stack is still whole (the debugger's "break on script errors"). Paused
  time isn't moved frame by frame: the runtime's clock leaves it out
  (`NetherForgeRuntime.time`), so every frame's `started` and `deadline`
  are right when the script runs on.
- **Budget.** The count hook fires every `hookEvery` (1000) instructions and
  charges the innermost _frame_; each call in from Kotlin opens one
  (`host.invoke`, `guard.frame`), nested calls (a spawn running the new
  centity's script) get their own, and what they used is charged to the
  caller when they return (`guard.charge`), so a loop of call-ins can't
  outrun its own budget. A budget is exact to within one hook's worth, so a
  budget under 1000 is in effect 1000. A frame told to stop while the
  prelude's own code runs for the host (`host.new_env`, the end of a nested
  call in) isn't stopped there, where the error would escape the host's
  call: its thread's hook counts every instruction until the first of a
  script's, which stops (`guard.count_every`, `hooks.fast`). Stopping raises
  a Lua error (`OVERRUN`) that `pcall` could catch, so the frame keeps why it
  stopped (`overran`, also its file and line, since a body's error is
  re-raised by `require`'s loader and loses them) and the host reports it
  anyway. A frame marked `spent` stops counting: every call in's frame as its
  function returns, still inside the protected call (`run_spending`: a
  marked frame raises again at its next count, and one landing in `invoke`
  after the call would escape unreported), a task's resume frame once its
  coroutine yields or returns (so the bookkeeping after an overrun can't
  overrun again and lose the task's line), and the timer or handler frame
  that's only there to wake a task (its resume is charged to it, and on to
  whoever raised the event, but it can't fail for what the task did).
- **Time limit.** Each frame may run `deadlineMillis` (1000 ms) from when it
  opened, or its outer frame's deadline if that's sooner, so a nested call
  in gets what's left and a stall stops both ("ran past its time limit of
  1000 ms"). The hook reads the clock (`timing.clock`, the same injectable
  clock as the costs; `TestServer(clock = ...)` makes it deterministic)
  every `deadlineEvery` (10) hooks, and sooner when capped library calls
  spend enough work (`guard.spend`: their steps converted to hooks at 2.5 ns
  an instruction). That catches a loop of C calls each under the caps, which
  the budget alone would let run for a minute.
- **Memory.** Every script shares one Lua state. Every `memoryEvery` (1)
  hooks, and at the end of every collection cycle (a sentinel table's
  `__gc`, `guard.collected`: a string doubled with `..` outgrows any limit
  between two hooks, and cycles keep pace with it; inside a finalizer
  `collectgarbage` does nothing in Lua 5.4.4+, so it only asks the next
  instruction to check), the hook compares `collectgarbage("count")` with
  `memoryMegabytes` (512). Over it, it collects fully; still over, it runs
  the **census** (`guard.census`): what each scope holds, walking its
  globals, its required files, its handlers, its tasks and timers' callbacks
  (`task.fn`), and for the running scope the locals of project code on the
  running threads. Scopes are walked oldest first and a value is counted
  once, for the first that reaches it, so a module's table that requirers
  share is the module's; the prelude's and the library's functions and the
  metatables of handles and values aren't walked. The scope holding the
  most, if that's at least a quarter of the limit, is **blamed**: the host
  stops it at the end of the tick (`host.take_blamed` →
  `Scripts.stopBlamed`: reported like a failed body, "using memory failed:
  it held about N MB, the most of any script…", disabled, and its env
  dropped so what it held can go), and if it's the running call's own
  scope, that call fails there and then. Otherwise (memory the census can't
  place: what Kotlin keeps for scripts, like `data()` tables) the running
  call fails, and the next census waits until memory grows by another
  eighth of the limit (`guard.floor`), since a full collection and a census
  at every check would stall the server instead. While a blamed scope waits,
  only a running call that doubles the limit fails too. A real allocation
  failure ("not enough memory": Lua calls no message handler for it) is
  reported as the call's error.
- **Why a census, not an allocator.** luajava gives no allocator hook: the
  native library exports `lua_setallocf`, but setting it from the JVM means
  an FFM upcall on every allocation. And an allocator only knows who
  _allocated_, not who still holds it: sampling `collectgarbage("count")`
  around each call (what an allocator would amount to) charges the
  incremental collector's frees to whoever happens to run, so a script
  churning garbage looks like the leak. The census measures what each scope
  _holds_, only when it matters. `LuaHost.memory()` (`host.memory`) runs it
  on demand: `/nf scripts` shows a scope's memory once it's a megabyte. Per
  package (F3) is a sum of its scopes' numbers; to make a package's own
  modules own what they share, walk its scopes together.
- **The library's caps** (`resources/.../lua/caps.lua`, the chunk
  `=nf/caps`). The functions whose work isn't bounded by their arguments'
  size are replaced in the real `string` and `table` tables (which the
  proxies and the string metatable read through) by wrappers that work out
  the most the call could cost first: a string over `maxStringBytes`
  (16 MB) is refused (`rep`, its count too, `format`, `gsub`'s result,
  `pack`, `table.concat`), and so is work over `maxWork` (1e8 steps, about a
  nanosecond each, so at most about a tenth of a second for one call:
  patterns, a plain `find` with a long needle, `table.move`, `insert` and
  `remove` shifting, `sort` without a comparator). What's allowed is spent
  towards the time limit's next check. Scripts can't set `__gc` either
  (`caps.setmetatable`): a finalizer runs inside whichever call the
  collector happens to run in. The prelude calls the library's own
  functions (`raw`), never the caps, so nothing of the prelude's can be
  refused or stopped inside one; keep it that way (`raw.match(s, p)`, not
  `s:match(p)`). The caps chunk runs stripped of debug information: the
  library locates its own errors (a bad argument) at its caller, and a
  caller with no lines makes the message handler find the script's line.
  (In Lua 5.4 a tail call into a C function keeps the caller's frame, so
  that doesn't help.)
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

**Benchmarks** (`SandboxBenchmark`, `NETHERFORGE_BENCH=1`; Apple M-series
laptop, best of ten; ns an iteration of each loop). The intervals: the same
checks per instruction (the clock every 10,000, memory every 1,000) with
fewer, longer hooks; then one check changed at a time, at 1000.

| case                        | hook 100 | 250 | 500 | **1000** | 1000, budget only | clock every hook | clock every 100 | memory every 10 |
| --------------------------- | -------: | --: | --: | -------: | ----------------: | ---------------: | --------------: | --------------: |
| loop, 3 instructions        |       21 |  15 |  13 |   **12** |                11 |               13 |              12 |              12 |
| allocating `{}`             |       58 |  51 |  50 |   **51** |                44 |               47 |              49 |              52 |
| `string.format("%d-%s")`    |      481 | 397 | 377 |  **368** |               347 |              389 |             370 |             369 |
| `s:find("%d+")`             |      251 | 212 | 202 |  **201** |               175 |              185 |             199 |             198 |
| `s:match("^(%S+)%s+(.*)$")` |      266 | 235 | 228 |  **223** |               196 |              200 |             221 |             220 |
| `s:gsub("%s", "_")`         |      557 | 524 | 489 |  **484** |               435 |              445 |             494 |             493 |
| `gmatch("%S+")` loop        |      591 | 554 | 549 |  **534** |               531 |              516 |             517 |             529 |
| `table.concat(four, ",")`   |      647 | 596 | 558 |  **494** |               450 |              564 |             517 |             531 |
| `table.insert(t, v)`        |      141 | 117 | 112 |  **103** |               103 |              113 |             108 |             107 |
| `("x"):rep(16)`             |      207 | 184 | 173 |  **171** |               150 |              162 |             166 |             165 |

Before S5 (a hook every 100 that read memory every time, no caps): loop 20,
format 206, find 131, match 148, gsub 318, gmatch 481, concat 100, rep 75.
So: a hook every 1000 halves a plain loop's cost; the clock (a JNI call,
~300 ns) and `collectgarbage("count")` (~30 ns) cost nothing measurable at
these intervals, and only "clock every hook" shows (+15% on a loop); a capped
call costs about 50-150 ns more than the library's own (concat more, as it
reads every element to size the result). The library's own costs, per unit
of the caps' model (`the library's own costs`): `rep` 1.3-1.9 ns a byte,
`table.move` 9 ns an element, shifting 5-8 ns an element, `sort` 13 ns per
n·log₂n for numbers and 150 for strings (`strcoll`), a pattern step
0.7-1.2 ns, a plain find step 0.8 ns; `.-.-.-.-b` runs 50x under its bound.

## The profiler

Exact time, not samples (W1.4): `System.nanoTime` (the runtime's `clock`)
around every step of the tick and every call into Lua, attributed to the
function that ran. The pieces:

- **The prelude** (`timing.close`, which already charges each frame's own
  time to its scope): while `timing.profiling` (`host.profile(on)`), also
  sums `{ nanos, calls, slowest, kind }` per scope per **function**
  (`timing.fns`). The function is what the frame ran, made more precise by
  whoever opened it: `deliver` says the handler's event (`timing.next_kind`),
  a command's dispatcher swaps itself for the handler it runs (`f.what`,
  `"command"`/`"complete"`), `host.start` times a module's body as its file's
  path, `nf.after`'s wrapper resolves to the script's function through
  `timing.alias`, and `timing.kinds` marks timers and mob goals' callbacks;
  a task's resume is `"task"`. `host.take_profile` drains it (one tab-separated
  line per scope and function, with its file and the line it starts on,
  resolved once per function, `timing.where`).
- **`ScriptProfile`** (a session service, after `ScriptCosts`): `sync()` at the
  start of every tick turns the Lua side on or off to match
  `Profiler.active`; in `ACCOUNTS` it hands the profiler each scope's own time
  that tick (`ScriptCosts.lastTick`) and, when a batch ends, drains the
  functions (`LuaHost.takeProfile`), naming scopes (`Scope.title`) and
  remembering released ones until then; `stop()` drains before the Lua state
  closes, so a recording carries across a full reload.
- **`ProjectSession.tick`** times each `TickPhase` (only while active) and
  hands the tick to the profiler.
- **`Profiler`** (runtime-level, like `Packs`): every 20 ticks a
  `ProfileSample` (the ticks; scopes and functions summed, a function's rows
  merged across the scopes that ran it, `scopes` counting them), sent on the
  `profiler` stream while `streaming` (set by `profiler_subscribe` from the
  bridge's thread), and added to a recording. **Active** means a dev server
  (always), or subscribed, or `/nf profile` recording (production measures
  only then). `/nf profile <seconds>` (`AdminCommand`, `runtime.profile`):
  drains what's pending first, then records the ticks after the command,
  refuses an overlapping one, and at the end writes `profiles/profile-<stamp>.json`
  and `.txt` (`ProfileReport`: stats per step, scripts, every scope and
  function, every tick) on a writer thread, then tells the sender and the
  console on the main thread. `close()` (plugin disable) waits for a write.

**Why not the hook.** Lua's call/return hooks fire on every function call,
the prelude's included, and would need a JNI-free clock per event; the
frames already measure every call in exactly at two clock reads, so the
profiler only adds bookkeeping per call in. Line-level sampling (the count
hook reading `debug.getinfo`) would be the reason to plug into
`hooks.listeners`; it isn't needed for per-handler numbers, and leaving the
hook alone keeps it to the guard and the debugger's line hook. Time the
debugger holds the server paused is left out of the clock the profiler reads
(`NetherForgeRuntime.time`), so a paused tick isn't a slow one.

**Overhead** (`ProfilerBenchmark`, `NETHERFORGE_BENCH=1`; Apple M-series,
one server alternating off and on batch by batch, best of 30, each batch's
drain included): empty `tick` handlers, the worst case since the cost is per
call in, cost about 2 µs a call either way; the profiler adds 40-100 ns a
call in for one function in 400 subscriptions and about the same for 400
different functions (noise is ±5% of the tick), and nothing measurable for
handlers doing real work. Per tick it adds 16 clock reads and, once a second,
the drain (one line per function that ran). That's why production measures
only while `/nf profile` records.

`ProfilerTest` covers the attribution (kinds, lines, instances sharing a
row, a closed scope's time kept), production measuring nothing until asked,
the report and its refusals, and a recording across a full reload;
`BridgeTest` the subscription on the bridge's thread and the stream.

## The debugger

Breakpoints, stepping and variables on a dev server (W1.5), spoken as the
Debug Adapter Protocol: `debug/Debugger.kt` is the debug adapter (lsp4j's
DAP types, `DebugRemoteEndpoint` and `DebugMessageJsonHandler`; lsp4j's
Gson is the server's own, never shaded), each DAP message one `dap`
notification on the bridge (the hot-reload skill). It exists only with the
bridge (`runtime.debugger`, made in `enable`), outlives sessions like the
profiler, and the editor attaches whenever the bridge comes up.

- **Source paths** are the chunk's: a project path (`modules/m/init.lua`) or a
  package path (`library:modules/greetings/init.lua`, what `ProjectFiles`
  names a dependency's files, and what stacks, errors and the editor's
  read-only documents say). `Debugger.sourcePath` is the one place a
  `setBreakpoints` path is mapped: an absolute path, as an editor that sees the
  files on disk (VS Code) says it, goes through `ProjectFiles.pathOf` (the
  project's folder, a path dependency's, or a checkout in the package cache;
  the most specific folder wins), anything else is taken as already a chunk
  name. Stacks and the `setBreakpoints` answer always say the chunk's name.
- **Configuration** (attach, breakpoints by project path, break on errors,
  pause) arrives on the bridge's thread and is kept under the debugger's
  lock with a version; the main thread hands it to the Lua state
  (`LuaHost.debugConfigure`/`debugDetach`, the prelude's `host.debug_*`) at
  the start of every tick (`NetherForgeRuntime.tick`) and as soon as a
  session's Lua state exists (`ProjectSession.start`, before any body runs,
  so breakpoints in a body hold on a full reload).
- **The Lua side** (`prelude/debugger.lua`) listens on the guard's hook:
  `hooks.listeners.line` with the mask `"l"` only while there are breakpoints
  or a step or pause is under way (a server nobody debugs runs with `""`),
  and `hooks.listeners.error` while breaking on errors. A line event reads
  `getinfo(3, "S")` and looks the line up; stepping counts frames (from the
  line's frame to the bottom) rather than hearing calls and returns, which
  fire for every call, the prelude's too, while counting is paid only on
  lines during a step. A step belongs to its thread, and on the main thread
  to the guard frame (the call in) it started in: a nested call in runs
  through, and once that frame is `spent` the step ends and the server runs
  on (stepping off the end of a handler doesn't stop in the next one). In a
  task the step follows the coroutine across its waits.
- **A stop** captures the stack at once (each project frame's name, file,
  line, function and locals, copied: levels move as soon as anything is
  called; a coroutine's stop adds the main thread's frames below it), then
  calls the `debug.wait` primitive with why and where. Kotlin holds the main
  thread there (`hold`): it sends DAP `stopped`, and loops on its inbox,
  handing Lua what only Lua can answer (`stack`, `scopes`, `variables`,
  new `breakpoints`, `errors`) as commands that Lua answers with
  `debug.answer` (tab-separated lines, escaped), until a resume (`continue`,
  `next`, `stepIn`, `stepOut`, `detach`) ends the stop (`continued` is sent,
  unanswered asks fail). A hook runs with hooks off, so the debugger's own
  Lua work is never charged; a stop from `describe` isn't in a hook and
  turns its thread's hook off while it waits. Variable references are per
  stop (a table gets one reference however often it's shown).
- **Values are read without running the script**: `next`, `rawget`,
  `rawlen` and the debug library, never a metamethod (a script's `__index`
  or `__tostring` could loop forever with no hook to stop it); only the
  prelude's own `__tostring`s (Vec3, Location, Event) run. A handle shows
  Kotlin's description (`debug.handle`: `Player Steve`, `Centity tower
1a2b3c4d`, `Node top of tower`, `World world`, else its class and keys)
  and expands to its facts (where it is, its uuid).
- **Freezing the tick.** While held, nothing else runs on the main thread:
  bridge requests for it are refused at once with why
  (`Debugger.refusal`, in `BridgeService.request`), the editor holds its
  reloads until the server runs on, and once a second
  `Platform.pause.hold(message)` keeps the server alive (`PauseOps`: on
  Paper, `WatchdogThread.tick()` through `PaperVersion.holdWatchdog`, and the
  message on every player's action bar, which also keeps their connections
  from timing out). The time paused is `Debugger.pausedNanos`, left out of
  `NetherForgeRuntime.time`, the clock the Lua state, the time limit, the
  costs and the profiler's phases read: a call held at a breakpoint is
  never stopped for running long, and costs and profiles don't count it.
- **An editor that goes away** while the server is paused can't leave it
  paused: the bridge's `onDisconnected` detaches (forgets breakpoints, and a
  held server goes on). The editor also detaches before it stops its dev
  server (a held server would never read `stop`).

`DebuggerTest` plays the editor over a real bridge socket with lsp4j's
client side: breakpoints and the stack, scopes and variables (tables,
upvalues, globals, a centity handle), stepping in, out and over, a task
stepping over its wait, pause, break on errors, the time limit and costs
leaving paused time out, an editor leaving while paused, and breakpoints
across a full reload. A stop holds the test's own thread (it runs the
ticks), so the editor's part runs on a `Watcher` thread. `PauseOpsContract`
holds the fake and Paper to the action bar; `DebuggerScenario` holds a real
Paper server paused for 40 s with a bot online.

## The prelude's modules

The prelude is `lua/prelude.lua` (the entry) and one chunk per module in
`lua/prelude/` (the generated `schema.lua` is one of them). Kotlin loads the
entry as `=nf` and each module as `=nf:<name>` (`LuaHost.MODULE_NAMES`, which
`PreludeModulesTest` holds to the folder), and calls the entry with the
primitives, the compiled bindings (`=nf/bindings`) and caps (`=nf/caps`), the
limits and the compiled modules. The entry's own `require` runs each module
once and hands every requirer its table, so what a module exports is what it
returns, and no order is written down: a module requires what it uses. The
real globals, this `require` among them, are emptied last (`sandbox.seal`).

| Module        | What it holds                                                                                                                 |
| ------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| `std`         | the standard library as it is before the sandbox touches it (`raw`, `debug`'s functions, `load`, ...)                         |
| `guard`       | the hook, the budget and time frames, timing, error locations, `invoke`; `current()` is the running frame                     |
| `sandbox`     | `base`, the library proxies, the caps, `scope_base`, and `seal`                                                               |
| `args`        | argument checks (`bad`, `want`, `want_integer`, `choice`)                                                                     |
| `handles`     | `ids`, `kinds`, `class`, `self_of`, the cache, `new` (constructors) and `keys` (key accessors), both filled by the bindings   |
| `values`      | `Vec3`, `Location`, the `vec3` global, the `Vec3` fast path                                                                   |
| `gates`       | version gates (`needs_version`, `gate_functions`)                                                                             |
| `checks`      | `check_shape`                                                                                                                 |
| `json`        | the one Lua↔JSON codec                                                                                                        |
| `events`      | the event core; `tasks`: tasks, waits and the async forms                                                                     |
| `handwritten` | the bodies of the `impl: "lua"` functions (`utilities` holds `nf.random` and `nf.math`)                                       |
| `api`         | runs the generated bindings with the core, then the gates, the inheritance of methods and the `__index` that refines a handle |
| `scopes`      | a scope's environment (`new_env`), `require`, its `nf`, `log`                                                                 |
| `census`      | what each scope holds (memory blame)                                                                                          |
| `host`        | what the Kotlin side calls (`host.new_env`, `emit`, `take_costs`, ...), and what the conformance test reads                   |

Shared state is a field of a table a module exports, never a local another
module needs: the running frame is `guard.current()`/`set_current`, a scope's
budget is `events.budgets`, a handle's constructor is `handles.new.Task`, so
nothing is reassigned from afar. A module that must run after the sandbox (the
caps replace `table.sort`) requires it. No module may come near Lua's limit of
200 locals: `PreludeModulesTest` fails at half. `pnpm lint` type-checks them
all with the pinned lua-language-server (see lua-api).
