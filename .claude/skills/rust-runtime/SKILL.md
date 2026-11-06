---
name: rust-runtime
description: The port of the plugin's runtime to Rust with Luau scripts (phase 1 of the cost roadmap, ../ROADMAP.md) - where the crates are, the decisions taken along the way, and first of all how numbers work once every Lua number is a double. Read before touching anything under crates/, or before porting a piece of apps/plugin/runtime.
---

# The Rust runtime

The runtime (`apps/plugin/runtime/`) and `packages/format` are being ported to Rust, and
scripts move from Lua 5.4 to Luau (phase 1 of the cost roadmap, `../ROADMAP.md`). The Kotlin runtime stays
the reference until the Rust one passes the integration scenarios; then it's deleted. Nothing
is deployed, so the port changes formats and APIs freely and migrates the examples in the
same change: no shims.

This skill records the decisions the port takes. A decision that took thought goes here.

## Numbers

Luau has one number type, a 64-bit float. Lua 5.4 has two (`math.type` tells them apart),
and today's runtime leans on the difference in five places. This section says what each one
becomes.

### The rule

- **A number is an f64.** There is no integer subtype, so there's nothing to keep: `2.0` and
  `2` are the same value, and nothing may tell them apart.
- **A whole number is safe up to 2^53.** Every integer with `|n| ≤ 2^53 − 1`
  (`9007199254740991`, JavaScript's `Number.MAX_SAFE_INTEGER`) is exact. Anything that must
  be exact above that is a **string of decimal digits**.
- **The spec's `integer` stays, and means "a whole number".** It's checked where a value
  crosses into Rust (`want_integer`): finite, `n == floor(n)` and inside the safe range, or a
  type error naming the argument. On the Rust side it's an `i64`. Going out, an `i64` outside
  the safe range is a bug in the binding (an `integer` the spec promises can't be one), and
  it panics in tests and is clamped and logged in production. A value that can really be that
  big is typed `Int64` instead (below).
- **`Int64`: the spec's type for real 64-bit values**: world seeds, a database's `BIGINT`
  column, a WASM `i64`, ids from outside (a Discord snowflake). Scripts may pass a whole
  number in the safe range or a string of digits. They always **get back a string**, so a
  script never holds a value that silently lost its low bits. It's rare enough that the string
  costs nothing that matters, and the type checker says which values are strings.

### The five places

1. **The JSON codec** (`prelude/json.lua`'s `json_number`, `CodecNumbers`, `PlainJson`). Today an
   integer is written `%d` and a float keeps a point (`2.0`) so that it reads back as a float.
   With one type, the point means nothing:
   - **Writing**: a whole number in the safe range is written as an integer (`2`). Anything
     else is written as the shortest text that reads back as the same double (Rust's own
     formatting), with an exponent from 1e21 and below 1e-6, as JavaScript writes them
     (`json::number`). NaN and infinities are still
     refused. `CodecNumbers` (the C `%g` emulation) goes: there's no C library's text to match
     any more, because Rust writes the numbers on both sides.
   - **Reading**: a JSON number is a double. **An integer literal outside the safe range reads
     as a string of its digits**, so an id in an HTTP response survives `nf.json.decode`
     instead of turning into the nearest double. A literal with a fraction or an exponent is a
     double whatever its size, and so is an integer past 64 bits (serde_json reads it as one),
     which no service sends as a number.
   - The codec is `crates/luau/src/json.rs`, the prelude's `json.lua` in Rust. Handles and
     locations are written through its `Tags` trait, which the runtime implements once they
     exist. A string's bytes that aren't UTF-8 are written as U+FFFD, since JSON text is
     UTF-8. The Lua codec wrote them raw, which made invalid JSON.
   - Canonical JSON (format's writer, project files) is unaffected: project files hold no
     numbers that big, and format checks the safe range where a field is whole.
2. **Saved data** (`data()` tables and centity records, saved through the codec). It goes
   through the same codec, so `2.0` saved today reads back as `2`, the same value under Luau.
   A table key that's a whole number is an array index, as before. There's no migration:
   nothing is deployed, and the store's existing JSON reads correctly by the rule above.
3. **Databases** (`SqlDatabase`, `Cells.lua`, `Store`). Today `BIGINT` comes back as a Lua
   integer, and only past `Long` as text.
   - **Parameters**: a whole number in the safe range binds as `INTEGER`/`BIGINT` (`i64`),
     any other number as `REAL`/`DOUBLE`, a string as text. A script that wants a 64-bit
     value exact passes it as a string, and the database converts it as it converts any text
     to a number column (or a cast in the SQL).
   - **Results**: an integer column's value in the safe range is a number, and outside it a
     string of digits. `DECIMAL`/`NUMERIC` stays a number. A column's type doesn't change the
     rule, so `SELECT COUNT(*)` is a number whatever the driver says it is.
4. **Random numbers.** `nf.random`'s generators (xoshiro256** in Lua's 64-bit integers,
   `prelude/utilities.lua`) and the terrain glue's `mix64`, `hash_name` and `make_random`
   (`caps`' sibling `terrain-glue.lua`) do 64-bit integer arithmetic in Lua, which Luau can't
   express (`bit32` is 32 bits). **They move into Rust**: a `Random` is userdata holding the
   xoshiro256** state, and the terrain's seeds and hashes are computed natively.
   - The algorithms stay, so the streams don't change: the same seed gives the same numbers as
     today's prelude. `crates/luau/src/random.rs` is the generator, and `tests/random.rs` holds
     it to numbers made by running the prelude's own Lua on Lua 5.4 (words, dice, fractions
     and a wide range, for five seeds). A seed is a whole number
     (`integer`), and a seed too big for that is an `Int64` string.
   - `math.random` is Luau's own (PCG32 in Luau, seeded per room by the runtime), which also
     makes it deterministic per room's seed, as the roadmap wants for replays.
5. **The prelude's own uses** (`math.type`, `math.tointeger`, `math.ult`, `string.pack`'s
   integer formats, `%d`). Each becomes `is_whole(n)` (`n == floor(n)`, finite) or goes with
   the code that moves to Rust. Luau has no `math.type`, so scripts that called it get the
   same error any missing function gives. **`%d` keeps its meaning**: Luau's own `string.format("%d", 2.5)` writes `"2"`, where Lua
   5.4's raises "number has no integer representation". The caps refuse it as Lua 5.4 does.

### What scripts see

- Integer division `//` exists in Luau and means `floor(a / b)`, as before.
- Bitwise operators (`&`, `|`, `~`, `<<`, `>>`) don't exist: `bit32` does it on 32-bit values.
  None of the examples or Kosmoi's games use them.
- Whole numbers print without a point (`print(2.0)` is `2`), as Luau prints them.
- `Int64` values are strings: compare them as strings of equal length, or hand them to the
  API that wants them. Nothing in the API does arithmetic on them.

## Where things are

| Path           | What it is                                                                                                                                                                                               |
| -------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `Cargo.toml`   | The runtime's workspace (`crates/*`). The editor's backend is still its own workspace, taking `netherforge-format` by path (map colours so far), which turns on serde_json's `preserve_order` there too. |
| `crates/luau/` | `netherforge-luau`: the Luau host. One state per room, scopes, the guards, memory per scope.                                                                                                             |
| `crates/api/`  | `netherforge-api`: the Lua API for the Rust runtime, generated from `packages/api`.                                                                                                                      |

`pnpm test` runs `cargo test --workspace`, and `pnpm lint` runs rustfmt and clippy on it.

## The host (`crates/luau`)

What replaces `LuaHost`, the prelude's `guard` and `sandbox` modules, and the memory census.

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

- **The library's caps are format's `caps.lua`**, the same file the Kotlin runtime runs: it's
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
    nothing there. It stays for the Kotlin runtime.

- **Rust functions scripts call raise strings** (`Host::script_function`, `waiting_function`).
  mlua raises a Rust function's error as a userdata of its own, so `pcall` would hand a script a
  value of type `userdata` where Lua and the Kotlin runtime give a string (Kosmoi's SDK inspects
  them). Every binding, `require` and `nf`'s `__newindex` go through one Luau shim (`SHIM`, without
  debug information): a failure comes back as a sentinel and its message, which the shim raises
  located at the script's line, as the caps' `refuse` does (`modules/shop/init.lua:3: no module
"x"`); a waiting binding's `waiting` yields its task there. Not yet: a handle's metamethods
  (assigning to a handle's field), which are mlua `UserData` methods and raise mlua's userdata.

## The API crate (`crates/api`)

`pnpm generate` writes `crates/api/src/generated/` from the same binding model as the Kotlin
runtime's (`packages/api/scripts/emit/bindings.ts`), through `emit/rust.ts`, so the two
runtimes can't disagree about what crosses. `pnpm lint`'s generated-files check covers it.

- `handles.rs`: a struct per handle class holding its root's key (`Entity { uuid }`),
  `From` conversions up its chain (a `Mob` into the `Living` and the `Entity` it is), and
  `Handle`, any of them.
- `shapes.rs` and `unions.rs`: a struct per shape that crosses, an enum per union (a variant
  per member). A shape that holds itself through other shapes is boxed there.
- `api.rs`: a trait per class with functions (`CentityApi`, `NfServerApi`), and `LuaApi`, all
  of them. A handle method gets `this: &handles::<Class>`, a namespace function
  `caller: &Caller`, and every function returns `ApiResult<T>`. An `async` function returns a
  `Pending<T>`, a future whose value reaches the room at a tick boundary. A function the
  runtime doesn't implement is a compile error, as the roadmap asks.
- Types: `integer` is `i64` (checked to be whole and safe where it's read), a literal union is
  a `String` checked against its choices, a map is a `BTreeMap` (keys are strings or
  integers; anything else is an error at generate), `any` and `table` are mlua's `Value`.
  The runtime's own types (`Vec3`, `Location`, `Item`, `ItemMatch`, `Text`) are
  `crates/api/src/values.rs`, filled in as their areas are ported.
- The generated modules are `#[rustfmt::skip]` and allow clippy's lints, so the emitter doesn't
  have to write rustfmt's layout.

- **The glue** (`bindings.rs`, `codecs.rs`): a Rust function per binding reads its arguments
  with their codecs (`codec.rs`, the Kotlin `LuaCodec.kt` with its messages), calls the trait
  and pushes what it returns, listed in `BINDINGS`. Codecs are zero-sized types composed at
  compile time (`Opt(List(Str))`, a unit struct per shape and union), so a call builds none.
  A shape is read strictly (`unknown field 'options.colour' (fields: ...)`), and a bad choice
  says what it got (`got "mauve"`).
- **Unported classes.** `LuaApi`'s accessors default to `Unported` (`unported.rs`), whose every
  function fails with "`BossBar:exists` isn't on the Rust runtime yet", so the runtime ports
  a class at a time. A class's own trait has no defaults: once a class is implemented, it
  must match the spec. **The end of the port removes the defaults**, so a class left out
  stops building.
- **The prelude's functions are bound like the rest.** The Kotlin runtime writes the spec's
  `impl: "lua"` functions (`:on`, `nf.after`, `nf.task`, `Random`, `Task`...) in the prelude;
  the Rust target plans them too (`bindings(spec, { handwritten: true })`), so they're trait
  methods the Rust runtime implements in Rust, with typed arguments. For them only, `nf` in a
  union (`nf.wait_for(nf, ...)`) and a table keyed by anything (`Random:weighted`) cross as a
  `table` (`Value`). The rest of the arguments (`...`) is `rest: Vec<Value>`.
- **Waiting.** A function that waits (`waits` in the spec: `nf.wait`, `wait_for`, `dialog:ask`)
  returns `Waiting` once its task's wake-up is registered. mlua's Rust functions can't yield
  without its async machinery, which would want to drive tasks itself, so the binding is
  wrapped in a three-line Luau shim (`bind.rs`'s `SHIM`): it yields when the binding returns
  the `waiting` sentinel, and what the scheduler resumes the task with is the call's value.
- `crate::Runtime` is what every binding asks around its call: `requires` (the one
  capability check, before anything is read); the runtime's answers through format's `Requirement`).

## Handles (`crates/api/src/handle.rs`)

- **A handle is one userdata type** (`HandleData`) for every class: its class (which can
  become more specific: an `Entity` that turns out to be a `Mob`) and its root's key. Its
  `__index` looks the method up in its class's table (its own and its chain's, built once by
  `install`), and `__newindex` refuses: "a BossBar handle is shared by every script and can't
  be changed". There's nothing left for the old `rawset` refusal to guard.
- **One userdata per thing**: a weak-valued registry table by root and key (`push_key`). So
  `a == b` and `rawequal(a, b)` hold for two handles to the same thing, and a handle no
  script holds is collected. `__eq` compares root and key besides.
- `typeof(handle)` is `"handle"`, and mlua hides the metatable (`getmetatable` gives `false`).
- A method called with `.` fails with "call BossBar methods with ':'".

## `nf` per scope (`crates/api/src/bind.rs`)

Each scope gets its own `nf` (`nf_for(lua, scope)`), whose `__index` binds a function to the
scope the first time the scope reads it and keeps it (namespaces below it the same way). A
function then calls the API as the scope whose **code** read it, wherever it's called from: a
module's function called during another scope's call is still the module's. That's the
prelude's `make_nf(scope)`, without making 900 closures for every scope that opens. `nf`
refuses assignment. Handle methods use the running scope, as before.

## Async values (`crates/api/src/pending.rs`)

An `async` function's trait method returns a `Pending<T>` (a future). The binding hands it to
`pending::defer` with the script's callback, or none for the waiting form. `pending::deliver`
polls every wait once at the room's tick boundary and hands back the ready ones, which the room
calls back (through the host's guarded call) or wakes their task with, `value, err`. Nothing is
delivered inside the call, and game logic never runs off the room's thread.

- **The events registry** (`events.rs`, `crate::events`): every built-in event with its owner,
  payload shape, `cancellable`, `bubbles`, `writable`, `options`, `local` and `since`, and
  `BY_CLASS`, each class's events with inheritance resolved (`events::of("Mob", "death")` is
  `Living`'s). Kotlin's `Events.kt`, as data.

## Scripts (`crates/runtime`)

A `Room` is a host and its `Scripts`, with the API installed (`Api`: the classes ported so far,
every other unported). `Scripts` is the prelude's `events`, `tasks` and `scopes` modules and the
Kotlin `Scripts`, in Rust, implementing `nf.on`/`once`/`emit`, `nf.after`/`every`, `nf.task`/
`wait`/`wait_until`/`wait_for`, `nf.instructions_left`, `Subscription` and `Task`.

- **Scopes**: `open(name, memory_key, budget)` gives a scope its own `nf`; `run_body` runs a body,
  and a failing body disables its scope (its subscriptions, timers and tasks go until it
  reloads). `close` ends a scope and drops its environment.
- **Subscriptions** are filed under their target (a handle's userdata, which they keep alive,
  or `nf`) and their scope. Handlers run in registration order, each in its own scope's
  guarded call. One that fails is reported (`take_reports`) and the rest still run; after
  `MAX_ERRORS` (20) failures in a row it's cancelled (`gave_up`). A `tick` handler's `every`
  is phased by its target, or its id on `nf`. `listening(target, event)` tells the server
  whether to bother.
- **Events** are the prelude's design: an empty table whose `__index` is its view (`name`,
  `current`, `cancelled`, `stopped`, the methods), whose `__index` is the payload's fields, so
  a read runs nothing; `__newindex` allows only the event's writable fields (any field of a
  custom event). The view keeps the event's own state under a private key. `raise` returns
  `Delivered` (cancelled, and the fields as handlers left them). A custom event's payload is
  the caller's very table, so the caller reads what handlers set.
- **Timers** run at the start of a tick, in order of when they're due. A repeating one that
  fails 20 times in a row is cancelled.
- **Tasks** are Luau threads, started at once, resumed through `coroutine.resume` inside the
  host's guarded call (so the budget, time limit and memory category apply). A wait registers
  its wake-up (a timer, a predicate checked every tick, or a subscription) and returns
  `Waiting`, and the binding's shim yields. Only the task's own code may wait: the running
  thread must be the task's, at the depth its resume opened (so a handler the task sets off
  can't), and the shim checks `coroutine.isyieldable()` (so `table.sort`'s comparator can't;
  Rust can't tell, since mlua's raw calls run protected). A task that yields by itself fails
  with "coroutine.yield can't pause a task". Errors are the prelude's, word for word.
- **Weak host.** `Scripts` holds the host weakly: the host's state holds the installed API,
  which holds `Scripts`, so a strong one would be a cycle. The `Room` owns the host.

- **A writable field is checked as it's assigned**, read by the payload shape's codec for that
  field as it will be read back (`codecs::check_field`, generated for every event's writable
  fields), so a mistake is an error at the handler's line: `bad value for event.message (string or
nil expected, got number)`, Kotlin's words.

- **Async values** are delivered at the start of each tick (`pending::deliver`, `deliver_async`):
  to the callback, as its scope's code (not if the scope has stopped), or, for the waiting form
  (called without a callback, in a task), to the task, resumed with `value, err`. The binding asks
  `Runtime::waiting_task` before any work starts (outside a task: Kotlin's "only works inside a
  task"), registers the wait (`wait_on`, `Wait::Async`) and yields through the host's waiting
  wrapper; a callback that isn't a function is refused first too. The callback form returns
  nothing, as the spec says. Ending a waiting task drops its wait (`pending::cancel`).

Not ported yet (the Kotlin runtime does them): `:on` on handles other than `nf` (it needs each
service's liveness), `nf.wait_for` on a handle, the per-scope time per tick (`maxTickMillis`, which
holds back timers and tick handlers), and time per scope and the profiler.

## Modules (`crates/runtime/src/modules.rs`)

The Kotlin `Modules` and the prelude's `scopes`, in Rust. A `Project` is the files' text by
project path; a module is a folder `modules/<id>/` with an `init.lua`, started on its first
`require` or by `start()` (all, in name order).

- **Resolution** is `Modules.resolve`'s: dots are folders, a folder may be required by its
  `init.lua`; the caller's own files first (a module's folder, or a resource script's folder),
  then the module the first part names. A file beside a resource's script runs in the
  requiring scope itself, once per scope (so each instance has its own copy); requiring the
  script's own file is an error. The messages are Kotlin's.
- **Each file runs once per scope it runs in** (`loaded`, by scope and path, `true` for a file
  that returns nothing), and a second require while it runs is "circular require of ...".
  A module file runs as the module's code wherever it's required from: it's a call in on the
  module's scope (its `nf`, its memory category), whose steps are charged to the requirer.
- **A failed module** is disabled and reported once; its next requirer is told
  "module "x" failed to load; fix it and save to reload".
- `dependents(id)` is every other scope that required the module, which its reload restarts.
  `reload(id, project)` closes the module's scope and starts it from the new files.
- Every scope also gets `_G` (its own environment), and `log` and `print`, whose lines
  (`Scripts::take_logs`) carry the script's location.

- **Packages' modules** are named as the runtime names a package's resources (`acme:api`), their
  files at package paths (`acme:modules/api/init.lua`), each in a scope of its package's namespace
  (`Scripts::open_in`, `namespace`). A bare name is in the caller's own package;
  `require("acme:api")` needs the caller's package to depend on `acme` and `acme` to export the
  module (`exports.modules`), with format's messages (`unknown_namespace`, `not_exported`). A
  module's folder is its place (`Project::places`, from format: group folders, packages), so
  `modules/(combat)/sword` is `require("sword")`. Every module runs, errors or not, as Kotlin's do.

## Sessions (`crates/runtime/src/session.rs`)

`Session` is the Kotlin `ProjectSession` as far as the ported services go: the project read through
format with its packages (`project::load`), refused when `netherforge.json` has an error or doesn't
read, or when it targets a Minecraft the Platform doesn't run (`runtime.minecraft-unsupported`,
Kotlin's words), otherwise its services started in a `Room`, ticked, reloaded and stopped.

- **Services are one list** (`services()`): a `Service` starts, ticks and hears every hook in
  registration order, stops in reverse, and a kind of resource reloads through the one service whose
  `reloads()` names it. `reload(changed paths)` reads the project again and hands each changed kind's
  ids to its service; a change to `netherforge.json` or the lock restarts the session whole. A reload has two
  halves: every changed kind's service `unload`s first (modules close their scopes, and their dependents'), the
  released scopes go to every service, then each `reload`s. So a module's new body never sees what its old scope
  made (Kotlin releases a scope as it closes).
- **Modules** are the first service: every module of the tree (the packages' included) with an
  `init.lua` starts in a scope of its own (`Modules`), and one that changed restarts from its new
  files with every module that required it.
- **Reports go to the Platform's log** (Kotlin's `ScriptReports`): a body that failed (`module
dead: body failed: … (modules/dead/init.lua:2). It's disabled until it's reloaded.`); a handler,
  timer or task that failed, located, the same error again within `ERROR_QUIET_TICKS` (100) only
  counted and said with the next (`(and 49 more like it)`); one cancelled after `MAX_ERRORS` in a
  row, always said. Each is a `script.error` problem in `Session::problems` until its scope closes.
  A line a script logged is info with its scope and place (`[module door] hello
(modules/door/init.lua:1)`); the project's errors are warnings as it starts, with what the tree
  requires (`requirement::combined`).
- **Grants are per package** (`Grants`, by namespace): a call is held to the `requires` of the
  package whose code makes it, which the binding knows (the scope `nf` was bound to, or the running
  scope's for a handle method), so Kotlin's rule for a tail call hiding the caller isn't needed. A
  call no scope makes passes only when every package declares it. The policy (`policy.json`) comes
  with the server's config.

- **A service's hooks take `&self`** (Kotlin's `RuntimeService`'s): `player_joined` (before scripts hear),
  `player_quit` (after), `entity_gone`, `entities_loaded`, `entities_unloading`, `chunk_unloading` (after its
  scripts), `world_saving`, `plugins_changed`, `block_changed`; the session fans each out in registration order
  (`Session::each`) and commits the store after a save. They're `&self`, and so is `Session::tick`, because the
  server's events reach a session while it ticks (a script teleports a player: the teleport is an event): a
  service keeps what changes in cells and never holds one borrowed across a script call. Only `reload` and
  `stop` take `&mut`.

Not yet: the game's facts from the Platform (`external::GameData` is a placeholder, so format reads
without them), reloading a package's files, the policy, the bridge to the editor, the debugger, and every service but modules and centities (menus, dialogs, items...),
each with the compiler it needs.

## The server's events (`crates/runtime/src/server.rs`)

`ServerEvents` is Kotlin's `ServerEvents` (with `NetherForgeRuntime`'s hold on the session): it owns the
`Session` in a `RefCell` and implements `PlatformEvents`, which is what the adapter will call.

- **Each event opens the session once** (`Running::open`, `events.rs`): a shared borrow, and only while it
  runs. While the session is borrowed mutably (`session_mut()`: a reload, a stop) an event can't reach it
  and goes through unchanged, as when no project runs, rather than panicking. `tick` and the hooks that
  keep the world in order (`entity_clicked`, `entities_loaded`, `world_saving`...) reach a session that
  isn't running too, as Kotlin's do.
- **The game's events** (`game()`) are the generated `GameEventDispatch` over an `EventSource` (the session's
  cell). The generator writes, per event, a raise function and an `Around` trait method defaulting to only
  raising it: Kotlin's `open class GameEventDispatch` with `RuntimeGameEvents` overriding some, in Rust.
  `Running`'s `Around` is that: services hear `player_join` before scripts and `player_quit` after,
  `chunk_unload` after scripts, and a `block_place` scripts let through changes physics' blocks.
- **`PlatformEvents`' own events raise their paths by hand**, as Kotlin's do: `player_interact`
  (`interact` on the player, `player_interact`), `use_item`, `drop_item`, `pickup_item`, `consume_item`,
  `block_break` (on the world, then `nf`; drops and experience written back, `None` when cancelled),
  `player_died` (`player_death`, then `entity_death` with what that left; keeping the inventory drops nothing
  unless a handler said what does), `entity_damaged` (the amount back, at least 0), `entity_died` (then
  `entity_gone`, which drops the entity handle's subscriptions), `player_interact_entity`. A payload is built
  only when something listens. What handlers print is logged at the next tick, as other reports are.
- Not yet, each with its service, answering "unchanged" until then: project items' stages (a held item's
  handlers first) and looks, custom blocks, structure markers, generated loot, menus, dialogs, resource packs.
  Screens' are wired: `player_interact` asks the screens first (a swing aimed at one), `entity_clicked` after centities.

## Centities (`crates/runtime/src/centity`)

Kotlin's centity package, whole: every `Centity`, `Node`, `nf.centities`, `nf.spawning`, `Image` and
`nf.images` function is implemented (`nf.images.download` through HTTP's `fetch`: see HTTP).

**Instances** (`instance.rs`): per-node state in vectors aligned with the definition's nodes (format's
`CompiledCentity`, parents first; script-added nodes after the declared ones, `declared` keeping the file's),
entities by node name. Local transforms are the truth; `placed()` is each node's matrix from the anchor along
the world's axes (turned by the yaw: quarter turns exact, 90 is west). `reshape` carries every node's state by
name when nodes are added or removed. Ids are random UUIDs (`saving::random_uuid`).

**Drawing and sync** (`mod.rs`, `drawing.rs`, `keyframes.rs`): an anchor entity and a display per node, each
riding the anchor; a hitbox's interaction entity rides it at the anchor and stands alone elsewhere. `sync`
writes only what changed; `reconcile` makes the entities match the definition. A rect, an image (its pixels
as runs the platform sends) or anchored text is a text display with a fit. While only clips move a node, its
pose is sent ahead as far as the client's easing follows (`reach`). A private instance is made of client
entities only its viewers have (`ops`).

**The API and an instance's life** (`api.rs`, `CentityContext`, built on the session's `Shared`):

- `spawn` runs the centity's script with `this` (`Modules::script_with`), then raises `spawn`; `remove`
  raises `remove` while the instance is still itself, then its script, handlers and entities go.
- **One tick** (`CentityContext::tick`): lifetimes run out; buffs count down; per loaded instance (`chunk_load`
  / `chunk_unload` when that changes; an unloaded one doesn't tick): its health bar, tweens, clips
  (`animation_end`), follow, walk, flight, body (`land`, `bump`); then **one physics step for the room**; then
  per instance `tick`, ropes, sync; then `touch`; then natural spawning. Kotlin ran physics and `tick` per
  instance; here every body is stepped together first.
- **Liveness** (`Scripts::add_liveness`): `:on` on a removed instance or a missing node never runs.
- **Unported methods were explicit** while the classes were partial: delegations `CentityApi::x(&Unported,
...)`, generated by a script from the trait, removed as each was ported. Do the same for the next class.
- A temporary instance goes with the scope that spawned it (`Scripts::take_released`, drained by the session
  after every call in) or its lifetime; natural ones with nobody near. Neither is stored.

**Areas**, a file each: `animation.rs` (Kotlin's `AnimationPlayer`; clips by centity and index), `health.rs`
(health and its bar), `crate::stats` (stat sheets and buffs, players' too), `hitboxes.rs` and clicks (line of
sight picks the node; the path is node, ancestors, centity, `centity_click`), `spatial.rs` (the obstacle grid,
filed by cell and refiled from a dirty set each instance marks; query shapes and filters; `touch`),
`movers.rs` and `world_shapes.rs` (a centity `body`: vanilla-style movement, kept custom per the roadmap),
`flights.rs` (`launch`), `pathing.rs` and `paths.rs` (ground and air A*, walks, doors), `ropes.rs`,
`definitions.rs` (a node from Lua, read as `centity.json` would be), `quat.rs`, `natural.rs`, `saving.rs`.

**Physics on Rapier** (`physics.rs`, rapier3d-f64): one Rapier world per Minecraft world of a room. Every body
is simulated together (a crate knocks over another centity's crate: the roadmap's deliberate change). World
blocks are fixed colliders built per chunk section around awake bodies, read again after `SECTION_TICKS` (40)
and dropped when idle; full cubes buried among full cubes are skipped. Hitboxes no body carries are fixed
colliders, rebuilt when an instance's obstacles change. A body's collider is its hitbox boxes (or
`physics.collider`, unit cube by default; one box for `shape: box`, a ball for `sphere`) scaled by the node; mass
is `physics.mass` or the volume. Gravity is the body's gravity scale against a world gravity of 1. Physics
writes the node's pose through the setters and remembers it; a node moved by anything else teleports its body.
Bodies past `REANCHOR` (16) blocks move the anchor under them. `collide` (with the other body's node, any
instance), `wake`, `sleep`. Collision groups: blocks, hitboxes, bodies (`physics.blocks`/`entities` filter).
A block placed or broken (`block_place` scripts let through, `block_break`) drops its section and wakes the bodies
near it (`Physics::block_changed`), so a crate on a broken block falls at once; changes no event names (explosions,
pistons, fluids) still wait for the section's re-read. Not yet: the spec's `rigid_body()`, joints, materials and
sensors.

**Persistence** (`saving.rs`, `crate::store`): the store is rusqlite on the Kotlin runtime's own migration
file (one `netherforge.db` for both). A normal instance's record is staged when it changes and written at the
end of the tick (`CentityContext::commit`), its place saved when the world saves, a chunk unloads, the
session stops or autosave (6000 ticks). A stopping session leaves instances in the world; the next restores
them (their scripts start in `Service::after_start`, once modules run, without `spawn`), inert while their
centity is gone and revived when it's back. `centity:data()` is one of the saved tables
(`crate::data`, below). The store commits on the main thread for now.

**Images** (`crate::images`): pictures held by number, bounded by `MAX_HELD` pixels, decoded with the `image`
crate (PNG, JPEG, GIF, BMP), encoded as PNG. Not yet: letting go when Lua drops a handle (they go with the
session).

**The session's platform events**: `Session::entity_clicked`, and the centities service's hooks:
`entities_loaded` (pushed whole and hidden again, strays removed), `player_joined`, `player_quit` (the natural
spawner forgets them), `world_saving`, `chunk_unloading`, `block_changed` (physics). `ServerEvents` calls them.

## Worlds and blocks (`crates/runtime/src/world.rs`, `block.rs`)

`WorldContext` implements `World`, `nf.worlds` and `Block` (Kotlin's `WorldImpl`, `NfWorldsImpl`,
`BlockImpl`), read live through the Platform: everything answers nil or false once its world has gone.

- The centity questions go to the spatial index: `world:raycast` tests blocks, then entities, then centity
  hitboxes (`centity::api::raycast`, Kotlin's `CentityClicks.raycast`: the shapes clicks use, unloaded
  instances left out), each within what the one before left; `overlap_box`/`sphere`/`cone`, `sweep` (a
  block stops the capsule and is the last hit) and `nearest` are `Centities::find` with the query filter.
- `world:flood` is Kotlin's `Flood`: breadth-first, reading 16-sided bricks (`BlockOps::box`), each state
  asked once. Its matcher takes ids, `#tags`, `solid`, `liquid`, `passable`.
- **A state a script writes is made canonical** (`block::canonical_state`, Kotlin's `Effects.blockState`):
  parsed by format's `BlockState`, checked against the game's properties, every left-out property at the
  game's default, sorted. A block set or broken through the API tells physics (`block_changed`).
- **Entity handles keep their class** where the spec's type is `Entity` (a query hit, `world:entities`):
  `Shared::entity` pushes the handle as the class the server says it is (player, mob, mannequin, living,
  dropped item; Kotlin's `entityHandle`), and a handle is one value per thing, so the script's value has that
  class. The pushed value is held until the tick ends (`Shared::classed`) so the weak cache can't drop it first.
- A world's spawn category is format's `SpawnCategory` (the Platform spec's external, now real).
- `Inventory` (`inventory.rs`) is a real inventory by its key (`events::inventory_ref`, `inventory_handle`'s
  inverse). Taking, counting and finding go through `items::ItemMatch` (Kotlin's `ItemMatch`/`ItemSlots`): a kind,
  or a partial item whose given fields compare as a script reads them, `data` as a subset. Not yet, with menus:
  a storage's viewers and pushing a storage's changes to menu slots.
- `WorldBorder` (`border.rs`) is a world's border or a player's own (`world:border()`, `player:border()`), read
  and set through `BorderOps` with Kotlin's limits.
- `set_block` and `fill_blocks` (at most 32768, loaded chunks only) write canonical states. `spawn_entity` checks
  the kind and tags and encodes `options.data` with the saved tables' tags; `spawn_item` takes an item table.
- Not yet, delegated to `Unported`: gradual fills, structures,
  `locate_biome`, managed worlds, `block:custom` and
  `block:data`. Until custom blocks come, no block is a project block (`blocks`' `custom`, `flood`'s
  matcher), and a project biome is spelled without the project's namespace (not yet relative to a package).

## Entities (`crates/runtime/src/entity.rs`, `living.rs`)

`GameContext` (the same context as worlds and blocks) implements `Entity`, `DroppedItem`, `Living`, `Mob` and
`Mannequin` (Kotlin's `EntityImpl`... `MannequinImpl`): an entity by UUID, read live through the Platform,
nil or false while it isn't in the world.

- `is_player`, `is_living`, `is_mob` (the prelude's in Kotlin) ask the handle value's class, made as specific as
  the server says first (`Shared::entity_by_id`).
- **The entities service** (`EntitiesService`, Kotlin's `HiddenEntities` and `EntityData`): who each entity
  is hidden from, applied again when the player joins and when the entity loads, everything shown again when
  the session stops; and `entity:data()` tables, read from the entity the first time, written back on
  unload, world save and stop, only when changed (the same codec and 1 MiB cap as `centity:data()`).
- An entity a script removes is gone for good: `Shared::gone`, drained by the session into `entity_gone`
  (its table, who it was hidden from, its handle's subscriptions).
- `own_id` is Kotlin's `ownId` (a modifier's or goal's id in the project's namespace).
- `AttributeOperation` is format's in the Platform's Rust types.
- Not yet: `entity:team()` (teams), a mob's `move_to`/`stop_pathing` and goals (mob goals).
- **`Player`** (`player.rs`, Kotlin's `PlayerImpl`): what needs them online answers nil or false while they're
  offline; bans, the whitelist and played times need only a UUID. Their stats and buffs are the stat sheets
  centities use (`Holder::Player`): `buff_add` and `buff_end` raise on the player too, and a player's buffs
  count down only while they're online (`stats::Presence`: waiting, not gone). A storage is named in the calling
  scope's namespace. Advancements are the server's by key, or the project's and its packages' by name (a
  project one the server lacks says to restart). Not yet, each with its area: recipes, menus and dialogs, listings and below-name text, boss bars, resource packs,
  `player:data()` and `player:session()`.

## Saved tables (`crates/runtime/src/data.rs`)

`ScriptData` is Kotlin's: `centity:data()`, `player:data()` and `nf.data(name)` are one kind of thing, a live
Lua table by `Owner` (`Centity`, `Player`, `Named { namespace, name }`), made from the store the first time
and saved, when its encoding changed, at autosave (6000 ticks), when its player quits and when the session
stops (`DataService`, registered first so it stops after every script's unload). Its encoding as a session
ends is carried into the next session's table (a restart), not read again from the store. `nf.data` is per
package (the calling scope's namespace); a thing's table is the thing's, whoever's code asks. Over 1 MiB isn't
saved and is logged once while it stays so. The store's rows are its `centity_data`, `player_data` and
`named_data` (`Store::data`, `put_data`, `delete_data`). `nf` is `NfContext` (`lib.rs`): `Scripts`' functions,
with `nf.data` the session's. `player:session()` is `SessionTablesService`'s: a plain table per online player, never
saved, gone after their `player_quit` handlers. A saveable handle (the spec's `saveable`: `Centity`, `Entity`,
`World`, generated as `handles::SAVEABLE`) is written `{"$entity":["<uuid>"]}` and read back as that root
class (`handle::SavedHandles`, the codec's `Tags`); any other handle is a problem at its key path (`a Task
handle can't be saved`), logged once while it stays. A `Location` is a plain table holding a `World` handle,
so it round-trips without a tag of its own (Kotlin's `$location`). `entity:data()` uses the same tags.

## Permissions (`crates/runtime/src/permissions.rs`)

Kotlin's `PlayerPermissions`: the nodes scripts set (`player:set_permission`), kept in the store's `permissions`
rows under the project's namespace, held on each online player through `PermissionOps::apply`. What applies is
what's kept **and** what some package's `allow.permissions` allows now. The service starts before any script
(so `has_permission` already answers), re-applies on join, and takes everything off at stop. Setting a node
needs the calling package to allow it (`check_permission`, Kotlin's words); code no scope runs needs every
package to.

## Schedules (`crates/runtime/src/schedule.rs`)

Kotlin's `Schedules`, `Recurrence` and `ScheduleImpls`: `nf.schedule.daily`/`weekly`/`cron` in a zone
(`schedule::Zone`: the server's own, an IANA name through chrono-tz, or a fixed offset), checked each tick
against the wall clock, a due one run once however late. **Daylight saving is java.time's**: a time in a gap
is shifted by the gap (`Zone::instant` reads it with the offset before), one that happens twice is the first.
Cron is Unix's five fields through `croner` (seconds disallowed; day-of-month and day-of-week either matching,
as Unix's). A schedule with an id keeps its last run per package in the store's `schedule_runs`; `catch_up`
runs a missed one as its script starts, and one seen for the first time counts as run when it's made. A
session's `Config` (`session::Config`, the start of Kotlin's `RuntimeConfig`) gives the zone and, for tests,
a fixed wall clock (`Session::set_wall_clock`). Not yet: the zone read from `config.yml`, the test servers'
scaled clock.

## Names across packages, effects (`names.rs`, `effects.rs`)

- **`names::Calling`** is Kotlin's `PackageNames` on format's **`project::Names`**: an owned summary of the
  loaded tree (each namespace's dependencies, exports, resources that read, and reference index without its
  uses), made by `Project::names` as the session starts and reloads and kept in `Shared::names`, since the
  loaded project borrows its sources and `Shared` is `'static`. `Names::nameable` is the one rule (format's
  `Project::nameable` is defined through it). `GameContext::with_names` gives it as the calling scope's
  package sees it: `resource` (in), `spell` (out), `biome`/`spell_biome`, `look` (a look is a value: any
  loaded package's, bare is the caller's own) and `pack_sound`.
- **Effects** (Kotlin's `Effects`): `particle_spawn` checks a particle and its options against the game's
  data kind (`ParticleData`), with format's `SpawnData` (the Platform spec's external, now real); `send` picks
  the viewers in range (32 blocks, 128 forced); `sound`/`sound_id` take a pack sound by reference, else the
  server's (a registry the facts don't know says nothing, as format's `lacks`). `World:spawn_particle`,
  `play_sound` and `Player:play_sound`, `stop_sound`, `spawn_particle` use them.

## Skies (`crates/runtime/src/sky.rs`)

Kotlin's `Skies`: a world's and a player's sky (a player's own over their world's; the fade of whichever
changed last), shown to each online player a fade step at a time through `SkyOps::show`. The steps and clock a
sky has are what the server **started with** (`DatapackOps::started`, read once per sky, with format's
`startup::sky` keys): a sky with none is new since the start and refused until a restart. A first view, or
another world, jumps straight to the sky. Timers run before the service in a tick, so a change a timer makes
steps that same tick. Not yet: the refusal's own words (`DatapackOps::refused` is a placeholder).

## The small namespaces (`crates/runtime/src/utilities.rs`)

`nf.server`, `nf.players`, `Sender`, `nf.math`, `nf.json`, `nf.random`/`Random`, `nf.time`, `nf.text`: Kotlin's
`NfServerImpl`... and the prelude's `utilities.lua` for the `impl: "lua"` ones, with their words.

- `Random` is the prelude's xoshiro256** (`netherforge_luau::random`, already held to Lua 5.4's streams);
  `weighted` sorts keys as the prelude did (numbers, strings, booleans, the rest) and charges the caller as the
  prelude's `affordable` did (`Host::charge`). A generator's state is kept by id until the session ends (the
  prelude's were collected with the handle).
- **Noise is Kotlin's simplex noise over Java's `SplittableRandom`**, ported bit for bit: the tests hold both
  to numbers jshell printed (`SplittableRandom` draws, and a Java transcription of `noise2`/`noise3`).
- `nf.json` encodes and decodes with the saved tables' handle tags; text that isn't JSON decodes to nil.
- `nf.server.unix_time` reads the same wall clock schedules do (a session's `Config` can fix it).
- Not yet: `nf.time.format`/`parse` (Java's pattern language), `nf.text.glyph`/`space` (the built pack).
- `nf.encoding` and `nf.crypto` (`encoding.rs`, Kotlin's `EncodingImpls.kt`): over a text's UTF-8 bytes, nil
  for bytes that aren't UTF-8; RustCrypto's `sha1`/`sha2`/`hmac`. A form is walked raw (no metamethod runs), its
  keys sorted, a whole number written as an integer and any other in plain decimal (Rust's `{}` never writes an
  exponent, as `BigDecimal.toPlainString` didn't). The walk isn't charged to the script, where Kotlin's was
  (`meter`).

## Boss bars and sidebars (`crates/runtime/src/bossbar.rs`)

Kotlin's `BossBars` and `Sidebars`: a bar's look and its viewers by UUID (shown again when they rejoin), its icon
a glyph placed left of the bar (`<at:-92:from=center:align=right>`), gone with the scope that made it
(`Service::scope_released`, which every service now hears: the session takes the released scopes once and hands
them to centities and to each service). An attached bar follows its centity's health and shows to who's in
range; it goes, or comes off (`remove_with: false`), when the centity does. A sidebar is each online player's,
forgotten when they leave; everyone gets the main scoreboard back at the end.

## Teams (`crates/runtime/src/team.rs`)

Kotlin's `Teams` and `PlayerListings`: a team is `nf.<name>` on the main scoreboard (leftovers of ours are removed
as a session starts; others' are never touched), its look kept here as scripts set it, its members the
scoreboard's (a player by name, any other entity by UUID: `scoreboard_entry`). It goes with its scope. Lines under
name tags and who's out of whose player list are kept and applied again on join; the session's end undoes all
three. `entity:team()` is the project's team the entity is in, if any.

## Menus (`crates/runtime/src/menu.rs`)

Kotlin's `Menus` and `MenuImpl` (the menus-and-dialogs skill has the design): a window per opening (UUID id,
retired the tick after its last viewer closes it) or one shared window per `shared` menu, made at start and named
by the menu's id. Each window runs its own copy of the menu's script (`Modules::script_with`, `this` the window),
started once modules run (`after_start`; a window a module's body opens starts its script then, and only once). A
click goes slot → menu → `nf` (`raise_path`, pre-cancelled for a locked window's item-moving click); a drag to the
window only; `open` is the runtime's own, `close` the server's. Retiring drops every handler on the window and its
slots; liveness answers for `Menu` and `Slot` handles. Reload keeps a window of the same shape (contents but for
slots whose authored item changed; title, skin and lock where the file changed them) and rebuilds and reopens one
whose shape changed; its script restarts. The Platform spec's `MenuType` is format's.

A slot of a window that isn't shared can be bound to a player's storage or to what they wear (`Slot:bind`,
`bind_equipment`): every write through the API goes through (`MenuContext::write`, `wrote_through`), a game-applied
click is written back on `PlatformEvents::menu_changed`, an `Inventory` change to the storage shows in its bound
slots (`storage_changed`), and a storage's viewers are those windows'. A slot's rule (`set_rule`: project items by
id through `Names`, kinds and `#tags`, `wearable`, `max`) refuses a click with a stack it doesn't take, or moves
only what fits and hands the rest back on the cursor; a locked window's click is cancelled before any of that, as
Kotlin's. `shift_into` moves a shift-click from below into a storage's bound slots, partial stacks first, offering
each slot the stack as a click.

**Templates** (`nf.menus.create`): `definitions::menu_from` reads the table but `slots` as JSON (the file's keys,
strictly), `slots` by hand (Lua's integer keys aren't JSON's; each read by `ItemCodec` and `items::from_value`),
writes its names as the server knows them and checks it with format's `document::load`, then format's `compile`
gives the `CompiledMenu`, its id a UUID. A `Template` belongs to its scope (`MenusService::scope_released`) or
goes with `template:remove()`; either way its windows retire and its handlers go. `open_menu(template)` makes a
new window every time (`Window::template`), whose events go on to the template after the window: slot → menu →
template → `nf` for clicks, menu → template → `nf` for open and close, menu → template for drags. Its `kind()` is
nil. Not yet: a rule's `property` (project items' properties), and skins in titles (with the built resource pack:
until then a skin draws nothing).

## Dialogs (`crates/runtime/src/dialog.rs`)

Kotlin's `Dialogs`: one record per dialog file, its script started once modules run (`this` the dialog), built
fresh for every show (`DialogSpec`, a `dialog_list`'s dialogs alongside, each once along a path). An opening
(`open_dialog`, `open_for`) checks its `values`, `body` and `title` against the file first (an error even for
someone offline) and keeps its `context` until the dialog leaves their screen. A press goes button → dialog →
`nf` and closes the screen unless `afterAction` keeps it; only a dialog on the player's screen (or one in the
server's registry, for custom clicks: format's `startup::dialog::click`) can be pressed or left; anything else
is logged and ignored. A close the server causes (another dialog over it, `close_dialog`) raises `close` here.
Reload restarts the script and drops handlers on buttons the new file lacks; a deleted dialog drops all. The
Platform spec's `DialogFile` is format's. `dialog:ask` is the prelude's `wait_events` in Rust
(`Scripts::wait_any`): it subscribes to the dialog's `press` and `close` and `nf`'s `player_quit` (each
accepting only that player's), opens the dialog, and only then has the task wait, so a close the opening causes
isn't heard. A dialog that's gone or couldn't be opened hands back `nil` at the tick boundary (a ready async
wait), not at once: a binding that waits can't also return. A wait whose target goes (`drop_target`) ends its
task, `nf.wait_for`'s too, as Kotlin's `sub.gone` does. `nf.wait_for` takes any handle.

**Made in Lua** (`definitions.rs`, Kotlin's `Definitions.kt`): `nf.dialogs.create(definition)` encodes the table
with the codec, respells it as the file (snake_case to camelCase, a camelCase key a mistake, `{}` the empty list
for list keys, an item body's `item` read by `items::from_value`), writes its names as the server knows them
(`Calling::definition`, Kotlin's `PackageNames.definition`: each reference found by format's schema walk, held
to what the calling package may name) and reads it with format's `document::load`, the file's own parse and
checks; the first error is the script's, at its Lua path (`definition.inputs[1].max_length`). It's a record like
the files' with a UUID id and an owner scope, gone with that scope (`scope_released`, its handlers dropped).
`nf.dialogs.get` and `open_dialog` by name find only the project's. Not yet: the runtime's own dialogs
(`/nf settings`).

## Screens (`crates/runtime/src/screen/`)

Kotlin's `Screens`, `ScreenState`, `Canvas`, `Raster`, `ScreenText`, `ScreenFont`, `ScreenImages`, `Placement`,
`ScreenFrames`, `MapSending` and `ScreenImpls`. `canvas.rs` is `Canvas` (a map colour a pixel, each map's changed
rectangle) and the shapes, line for line; `text.rs` fonts and text (the game's map font as the server has it, a
file's pixels, a bitmap grid, or TrueType rasterised by `fontdue` where Kotlin used AWT, so a TrueType font's pixels
differ a little; MiniMessage through format's `minimessage::pieces`); `mod.rs` the rest. A screen's maps are numbered
from `MAP_BASE`; a project screen's and a keyed script screen's are kept in the store's `screen_maps` (with
`screen_map_next`, so none is given twice), the same tables as Kotlin's. Each tick (in the service's turn): frames
hung again where missing (every 20 ticks a check), who sees each worked out (every 10), and each viewer sent what
changed of the canvas they see, within `ScreensConfig::bytes_per_tick`, starting from another screen and map each
tick. A player's own view (`for_player`) is a canvas of their own until `release`. Pictures are a pack's textures
(`<pack>/<path>`), decoded by the `image` crate, scaled by format's `Pixels::resized`, dithered by format's
`Dither` into the game's palette (`GameData::map_colors`), the last 32 kept. Files are read while scripts run
through `Config::bytes` (`ProjectBytes`, a `'static` reader the adapter gives from disk; none, and pictures and font
files can't be read), since the session's source is only borrowed. `ScreenFacing` is format's in the Platform's Rust
types. Not yet: the per-scope counts for `/nf scripts` (no service has a costs hook on Rust yet).

**Aim** (`screen/aim.rs`, Kotlin's `ScreenAim`): `Placed::hit` is where a ray meets a screen's picture (a sixteenth of a
block out from the wall, from its front only). A click on one of our frames (`Session::entity_clicked`, after centities)
is the screen's when it's drawn for the player, at the pixel their sight meets (the map's middle without one); a swing
(`ServerEvents::player_interact`, before anything in the world hears it) is a click on the nearest screen they aim at
within its click range, a block in front of it (`raycast_blocks`, 0.05 slack) in the way; one click a player a tick.
`hover` runs in the screens' tick, before the sending, only for screens whose handle (or a player's own view's) has a
`hover` or `leave` listener, each player's aim worked out once a tick; `leave` when their aim moves off, they stop being
a viewer (`view` returns who went), the screen moves, goes, or they quit. Events go along the player's own view first,
then everyone's, the payload's `screen` their own view's when they have one. `nf.screens.looking_at` is the same aim
with a reach (64 by default). One change: a swing asks the player's sight only when a hung screen is drawn for them
(Kotlin asked on every click).

**Videos** (`screen/video.rs`, Kotlin's `ScreenVideos`, `Playback`, `FrameFeed`, `NfvReader`, `PlaybackImpl`): each
video that reads (`running::<VideoFile>()`, its folder's files from `present`), its frames file found on disk through
`Config::paths` (`ProjectPaths`, the adapter's: frames are read a frame at a time, so they need a file, not bytes; none,
and nothing plays), its sounds `<ns>:video.<id>` and the pieces `video.<id>.<n>` that are there in a row from 0. A
playback's `FrameFeed` reads ahead on the session's videos `Lane` (a thread of its own, made on the first play and ended
when the screens' state goes; `ScreensConfig::read_inline` runs it on the caller, for tests), and every playback of one
video shares its reader while any holds it (a `Weak` by the video's serial, which a reload renews). Playbacks advance at
the start of the screens' tick, before the sending. Sound, `screen.json`'s own `video` (played after the screens' scripts
start, and again on its reload), `video.stale` problems and the end reasons are Kotlin's. Two deliberate changes: a
looping playback's read-ahead wraps to the video's start (Kotlin's stopped at the end, so every loop held a tick, and a
short loop never showed its first frame again); and `playback:length()` after its end is the video's (from the header
read for the staleness check), as the spec says, where Kotlin's answered 0. The `.nfv` layout is format's (below).

## HUDs (`crates/runtime/src/huds.rs`)

Kotlin's `Huds` and `HudImpl`: each online player's `HudState` (format's), drawn by format's `hud::compose` per built
pack in the pack's name order into their action bar, sent the tick after it changes and again every `RESEND` (20)
ticks before it fades. `player:send_actionbar` goes through it while a HUD shows (`Huds::message`): drawn in the
middle of the line for `MESSAGE` (60) ticks when it can be measured, else on its own. Elements are named as looks
(`RefKind::Hud`, `ui/mana`); a pack with errors has no HUD (its elements are an error to set). A pack's rebuild draws
every HUD again (`reloaded`). Text is measured by `Shared::measure_text` (new): the default font with the built
pack's glyph tags and bare glyph characters, which `nf.text.width`/`measure` use too, as Kotlin's do.

## Resource packs (`crates/runtime/src/packs.rs`)

Kotlin's `Packs` (the build half), `PackZip` and `PackService`: format's `build::build` over every compiled pack but
the `onDemand` ones (each of those zipped on its own), the project's files read through `ProjectSource::bytes` (new,
a package's from its own source), zipped by `packs::zip` (entries in path order, one fixed DOS date, deflated at the
default level, UTF-8 names, no extras: written by hand on `flate2` and `crc32fast`, so the same files always make
the same SHA-1). The build lives in `Shared::packs`, so a full restart within the session keeps it, and a pack with
errors (or one whose `pack.json` doesn't parse) keeps the last good build and its characters. The pack format is
`GameData::resource_pack_format` (new; the bundle's `pack_format`): none is a `runtime.pack-format` problem. It's
rebuilt when a pack changes, and after a reload that changed blocks, videos or `client/vanilla.json`
(`Service::reloaded`, new: every service hears the kinds a reload changed). What reads it: `nf.text.glyph`/`space`,
skins in menu titles (shifted for a centred title by the words' width), and the glyph lookups the adapter's text
will ask for (`text_glyph`, `glyph_advance`, `glyph_char_advance`).

**`Project::present(kind)`** (format) is every resource of a kind whether it read or not, Kotlin's `everywhere`:
format's `everywhere` leaves out a file that doesn't parse, which made a broken `pack.json` invisible (the other packs
built without it). The pack build and `build::needed`, the blocks' plan, and the "is the file still there" checks of
particles, loot and recipes reloads use it.

**Delivery** (Kotlin's `Packs`' other half and `PackServer`): the plugin's HTTP server is `std::net` (`PackServer`: a
thread accepting, one per request, `GET`/`HEAD /<sha1>.zip` from the current and previous build of each pack it
serves, 404 and 405 otherwise), started on the first build at `PackConfig`'s bind and port (0 picks one), the URL its
address or `public_url`. An owner's `external_url` is sent instead, with `resource-pack.zip` written to `state_dir`
for them. A player gets the pack as scripts start and as they join, again only when its hash changed (the first
send replaces whatever else they had); `onDemand` packs are pushed and popped by scripts (`player:push_resource_pack`,
an error for a pack that isn't `onDemand`) and resent when rebuilt. Ids are Java's `UUID.nameUUIDFromBytes` (MD5,
`md-5`). What a client says (`PlatformEvents::resource_pack_status`) is kept for our packs only and raised as the
player's `resource_pack_status`, then `nf`'s. `nf.packs.rebuild` reads the packs again from disk at the end of the
tick (`Session::rebuild_packs`, from `ServerEvents::tick` once nothing runs). `Player` is whole now. The owner's S3 store is under
Web routes. Not yet: what the network knows a player loaded, `PackConfig` read from `config.yml`.

## Files (`crates/runtime/src/files.rs`)

Kotlin's `ScriptFiles`, `FileImpl` and `NfFilesImpl` on `std::fs`: a `File` handle's key is `<namespace>/<path>`, the
calling package's folder first (`nf.files.get`), and nothing climbs above it. Paths are checked as strings first
(`normalize`: no `..`, backslashes, names starting with a dot or a space, Windows' device names; at most 8 deep and
200 long) and then on disk, every link followed, inside the root. Each package's folder is held to the session
`Config`'s `files` limits (bytes and entries, counted once then kept up to date), files to 1 MiB. Files from before
per-package folders move into the project's as the session starts (`adopt`, once: a marker file). JSON is
`nf.json`'s (the saved tables' tags); `lines` is a Rust iterator over the file read once (the prelude's in Kotlin).
The root is `Config::data_dir`; with none (tests), every file answers nil or false. Not yet: the root and limits read
from `config.yml`.

## Custom blocks (`crates/runtime/src/custom_blocks.rs`)

Kotlin's `CustomBlocks` (the design is the plugin-world skill's) and `NfBlocksImpl`, `ProjectBlockImpl`,
`CustomBlockImpl`. The plan of carrier states is format's `carriers::plan` over every block of the tree (one with
errors keeps its carrier and rotation, so the others' states don't move), held only while the server freezes every
carrier it uses (`BlockOps::freeze`; one it won't is a `runtime.blocks` problem, as are unheld and overflowing
blocks, through `Service::problems`). A chunk's blocks are a `ChunkGrid` of numbers (a name each, numbered for the
session) plus a `Placed` for the few with more to them (ticking, a centity, a pin). Each chunk's legend (state to
`name` or `name#facing`, sorted JSON) and the records of blocks with a centity are the server's (`BlockOps::legend`,
`records`); `reconcile` reads a chunk as Kotlin's does (records first; then each found state by the legend, else as
the block it is now, a leaves state only where it can't be a player's leaves; states that moved put right quietly)
and runs once it's let the blocks go what it leaves to do (centities to spawn for adopted blocks, those whose block
went). Chunks are read at start, as they load (`Service::chunk_loaded`, new: services hear a chunk load before its
scripts, through `Running`'s `chunk_load`) and by the sweep, two a tick, passing over a chunk whose fingerprint
(`BlockOps::fingerprint` and Java's hash of the legend) hasn't changed. `world:set_block`, `fill_blocks` and
`block:set` forget a block they replace (`replaced`). A block's script runs with `this` its `ProjectBlock`; `tick`
is raised on it every `tick` ticks per placed block. `block:custom()` is the `CustomBlock` there.

`ChunkGrid` is hand-written in the platform crate (`chunk_grid.rs`): the Platform generator writes a record named
in its `HAND_WRITTEN` as a re-export, since its Kotlin class keeps its state private.

**Breaking and the rest** (Kotlin's `CustomBlocks` whole, but `block:data()`): a player's break of one of the
project's blocks is the runtime's (`ServerEvents::block_break` answers `None`, cancelling the server's): its drops
(its loot table for the tool, nothing for a tool it requires and isn't, nothing in creative) go through the held
item's `break_block`, the block's `break`, the world's and `nf`'s `block_break`, then it goes with the game's break
particles and its sound. A game block with a rule (`game_blocks/`, format's `game_block::resolve`) drops the rule's
table instead of the game's. `break_naturally`, explosions (what scripts left breaking, each with the yield's
chance) and the world breaking a ruled block (`world_broke`) roll the same tables. Pistons don't move one. A click
on one raises its `click`; a right click with an item whose `block` places one puts it on the clicked face, facing
the player, after its `place` handlers, and takes one from the hand. Picking finds the stack that places it. A block
that `falls` drops as the server's falling block when its neighbour changes (`block_updated`, a tick later), is
taken up where it lands (`falling_landed`), and drops its table if it never lands. Event payloads hand a script a
`CustomBlock` where the project's block is (`Running::block` pushes that class, kept in `Shared::classed`). The
server is told which states fall and which game blocks drop (`BlockOps::watch`) only when that changes.

**Mining** (`block_mining.rs`, Kotlin's `BlockMining`, in `Shared::mining`): the server mines the project's blocks
and ruled game blocks. A hit (`block_start_break`, before scripts) turns the player's `block_break_speed` down a
millionfold so their game never finishes; each tick counts the server's progress (divided back out) times how much
faster the block is than its carrier, shows the cracks, and at the end breaks it as theirs (`BlockOps::break_as`).
The server's break raises its own event synchronously, which comes back into the blocks, so `mine` hands back the
breaks it finished and the tick does them once nothing is borrowed (`after_break`). Looking at one slows a player
before the hit; the modifier is cleared on join, since the server saves it.

**`block:data()`** (`block_data.rs`, Kotlin's `BlockData`, in `Shared::block_data`): a table per position, read from
its chunk the first time (`BlockOps::data`) and written back when the chunk unloads, the world saves and the session
ends, only when it changed, with the saved tables' codec and tags (an empty table is no data; save problems logged
when they change, a table over 1 MiB once). A block known by its state alone that has a table keeps a record from
then on (`pin_data`, as its chunk unloads, the world saves or the session stops; the data service is registered
before the blocks so it stops after them), so a block replaced while its chunk is out is found to have gone; a
block that goes takes its table with it (`gone`). `Block` is whole now: no `Unported`.

Not yet: the editor's view of the blocks' problems (with the bridge).

## Recipes (`crates/runtime/src/recipes.rs`)

Kotlin's `Recipes`, `StationCrafting` and `NfRecipesImpl`: the running `recipes/<id>.json` and those scripts register
(`nf.recipes.register`, read by `definitions::recipe_from` as `nf.dialogs.create`'s are: snake_case keys, `result` an
`Item`, checked by format's own load, every project item it names there), the latter going with their scope. A game
recipe goes to the server at once (`RecipeOps::add`, with format's `RecipeFile`: the Platform spec's external, now
real) and to players once a tick (`resend`); a `station` recipe is never sent. `available` and `craft` read one box
of blocks per chunk column round the player (or `near`) for the stations (ids, `#tags`, `a|b` choices; `stations`
counts more as near), count the materials in the player's inventory (a project item's stacks only for it), take them
and give the result, dropping what doesn't fit; with `recipe_craft` heard, each making's result goes through it.
`player:discover_recipe` and friends: a bare id must be a project recipe. Not yet: project blocks as stations (with
custom blocks), recipes rebuilt when a project item they name changes (Kotlin's `follows`).

## Loot tables (`crates/runtime/src/loot.rs`)

Kotlin's `LootTables` and `NfLootImpl`: the running `loot/<id>.json` by the project's names (a package's `ns:id`),
the last good one kept while a file has errors. `nf.loot.roll` rolls format's `loot_roll::roll` on Kotlin's `Random`
from the context's seed (a fresh one when none), so a seed's drops are the editor's and the Kotlin runtime's; a
drop's count is split into stacks of its item's `max_stack_size` (64), a project item made through `ItemContext::create`.
A game table (a namespace that's no package's, or a `vanilla` entry, with the seed the roller drew) is the server's
(`LootOps`), rolled at the context's location, else its player's or looted entity's place. `nf.loot.fill` puts each
stack in an empty slot shuffled as Kotlin's `shuffled(Random(seed.inv()))` (`shuffle`), a player's hotbar and main
inventory only, and answers what found no slot; the storage's bound menu slots hear it (`inventory_changed`). The
tool conditions match an ingredient as recipes will. Not yet: `LootOps::roll` can't say why the server refused a
table (Kotlin's `IllegalArgumentException`: the Platform spec has no error there), and the loot blocks and natural
spawning drop (they come with custom blocks).

## Particle effects (`crates/runtime/src/particles.rs`)

Kotlin's `ParticleEffects`, `ActiveEffect`, `NfParticlesImpl` and `EffectImpl`: the compiled effects (the last good
one kept while a file has errors; playing one that never had a good version says the file's first error), and
every one playing, by a number that's the handle's key. An effect belongs to the scope that played it and ends
`unloaded` with it (`scope_released`); its `end` handlers run while it's still found (`is_active()` true, `stop()`
false), then its handlers go. The service is registered right after centities', so its pass runs after their sync:
per effect, round-robin from a rotating start, follow the target (a centity, node, player or entity; gone ends it
`target_gone`), step the sampler (format's `EffectSampler`, seeded fresh each play), send to who's within 32 blocks
(128 forced) in one `ParticleOps::spawn` per batch, within `MAX_POINTS_PER_TICK` (4096) a tick. Skipping is a
warning in the log at most every 200 ticks and a `particles.budget` problem on the first skipped effect's file
until 200 quiet ticks, which `Service::problems` (new: services' own problems, chained into `Session::problems`)
hands the session. A reload moves live effects onto a valid file's new definition (`redefine`), keeps the old one
for a file with errors, and ends them `removed` when the file goes. Not yet: the budget's warning to the editor
(the bridge), Kotlin's once-a-minute log of a platform failure (the Rust `spawn` can't fail), the editor's "Play on
server" (`stop_unowned` is there for it), and `/nf scripts`' per-scope count (`count_owned`).

## Cutscenes (`crates/runtime/src/cutscenes.rs`)

Kotlin's `Cutscenes`, `ActiveCutscene`, `NfCutscenesImpl` and `CutsceneImpl` (the plugin-world skill's Cutscenes
section is the design): definitions are format's `CompiledCutscene` behind an `Rc`, the last good one kept while a
file has errors (playing one that never had one says the file's first error); each run holds the `Rc` it started
with, so a reload never moves one playing. `play` saves the player's `PlayerState` (or keeps the run's first), holds
the camera's chunk, retires what they watched, closes their window, dismounts them, spawns the camera (an empty
text display, transparent background, tagged `cutscene:camera`, not persistent, `teleport_ticks` 1) and makes them
spectate it. The service is registered right after particle effects', so its pass runs after the centities' sync:
the player gone ends it `player_left`, a game mode or a missing camera `stopped`, a camera let go `skipped` or put
back; then the tick, the chunk hold moved, the camera teleported, cues fired (`cue` raised only when something
listens; text as the subtitle, cleared when its duration is up). Ending is retire (camera, title, chunk), restore
(each step made whatever the last answered: the Rust Platform's calls don't throw), then `end` with the run still
found (`is_active()` true, `time()` nil), then forgotten and its handlers dropped. **No borrow of the cutscenes is
held across a call to the server** (a teleport or a game mode change is an event scripts may answer by playing or
stopping one). The store's `cutscene_states` table (`Store::cutscene_states`/`put_cutscene_state`/
`delete_cutscene_state`) keeps what a crash would lose; `start` reads it and `player_joined` puts that player back
with Kotlin's warning, before scripts hear the join. `Session::stop` now reports what scripts said while services
stopped (a cutscene's `end` handler at `unloaded`), which used to be dropped. Not yet: `/nf scripts`' per-scope
count (`count_owned` is there; no service has that hook yet).

## HTTP (`crates/runtime/src/http/`)

Kotlin's `http/` and `NfHttpImpl`, `ConnectionImpls`; the plugin-capabilities skill is the design. **ureq** (blocking,
HTTP/1.1, rustls with the platform's trust through `rustls-platform-verifier`, ring as the provider) is the client:
nothing in the runtime is async, and a blocking client on threads of its own keeps network work off the main thread.
`nf.http.request`'s checks are on the main thread (`api.rs`: URL, method, headers, body size, the host requirement
through `Grants::check`, the rates), the request runs on `Workers` (four threads, made on the first request, gone with the
session), and its value is a `Reply` future (an `mpsc` receiver) that `pending::deliver` polls at the tick boundary like
every async value. `Shared::http` is the session's `Http`: the owner's `HttpConfig`, the `Fetcher`, the rates, the
connections. `HttpService` (registered late, so it stops after every script) delivers what connections heard each tick,
ends a released scope's `unloaded`, and closes the client in `stop` (`Agent::shutdown` ends what's in flight).

- **The guard is ureq's `Resolver`** (`fetch.rs`'s `Guard`): ureq asks it for every connection, an address in the URL
  included, so one place resolves a name once (the injectable `resolve`), judges every address (`guard.rs`, Kotlin's
  `AddressGuard` byte for byte, an IPv4-mapped v6 address judged as the v4 one as Java does), and hands ureq exactly
  those. An agent per set of allowed hosts (an allowance's port decides), so a pooled connection made under one
  package's allowance is never another call's. No proxy (`proxy(None)`: ureq reads the environment's otherwise), no
  redirects (followed hop by hop in `Fetcher::fetch`, Kotlin's rules), the body read in a bounded loop after gzip.
- **A URL is read by WHATWG rules** (the `url` crate), so `http://127.1/` is 127.0.0.1 and judged as loopback; Kotlin
  refused such spellings unread because the JDK and OkHttp could disagree. What's judged is what's connected to either
  way; a written address that doesn't parse plainly (`guard::literal`) is still refused.
- **Tests' stand-ins** are `session::Config::net` (`NetHooks`): `resolve`, `dial` (where a judged address really
  connects: a public name to a local server, as Kotlin's `SocketFactory` did) and `own` (this machine's addresses,
  otherwise `if-addrs`, cached a minute).
- **Connections** (`connections.rs`): a thread each, talking to the main thread only through its `Sink` (a queue, a
  room of permits for backpressure, `ended` heard once). The pass (`HttpApi::pass`) takes one `Heard` at a time out of
  the connections and raises it with nothing borrowed. Streams are ureq on an agent per hop with no pool (`close` shuts
  it down, which ends a read in progress); their bytes are counted as the body is read (after gzip: Kotlin counted on
  the wire). WebSockets are tungstenite over a socket the guard chose (rustls for `wss`, the wire counted under TLS), read
  with a 50 ms timeout so the same thread writes what scripts send, pinged every 30 s, message and frame capped at
  `max-message-bytes`.
- `nf.images.download` is `HttpApi::fetch` with a decode on the worker; the picture is held on the main thread when the
  value is delivered.

Not yet: script test runs (`nf.test.http`, `allow_private`, the test transport: they come with `nf.test`), `/nf
requires`' listing, `HttpConfig`/`WebConfig` read from `config.yml`, HTTP/2 (ureq has none).

## Web routes (`crates/runtime/src/web/`)

Kotlin's `web/` and `NfWebImpl`. **The listener is the pack server** (`PackServer`): its request threads parse the head
with `httparse` (`gate::read_head`); a path that isn't `/<sha1>.zip` goes to the `WebGate` set on it (`set_web`) while web
routes are on (`WebService::start` starts the listener even when packs aren't sent), else `404`. The gate refuses on the
listener's thread (414, 429, 413 by `Content-Length` or by reading no further, a chunked body decoded within the limit),
queues an `Arc<WebCall>`, and the request's own thread waits on the call's channel and writes the answer. At most 256
requests are in flight; past that, `503` unread.

- **Routing** (`WebContext`, Kotlin's `WebRoutes`) runs in the web service's tick, 32 calls at most: no borrow of
  `Shared::web` is held while a handler runs, the handler is called through `Scripts::call_kept` (a stopped scope: `503`,
  a failure: reported with the route's name and `500`). A route answers by `Answerer::Script` or, for a login flow,
  `Answerer::Login` (no closures kept: the flow's number). `runtime.web-disabled` is the service's problem for each
  package declaring `requires.web` while routes are off.
- **Logins** (`oauth.rs`, Kotlin's `OAuthFlows` with the confirm page): the token request runs on HTTP's workers through
  the same `Fetcher`, and its answer is polled in the web service's tick. The real exchange isn't tested (it needs HTTPS);
  the test follows a login up to its token request failing.
- `verify_signature` is `encoding::hmac` and a constant-time comparison; `WebResponse:json` is `nf.json`'s codec.

Not yet: `nf.test.request`/`oauth_token`, the bridge's view of routes.

**The owner's pack store** (`pack_storage.rs`, Kotlin's `PackStorage`): S3-compatible, SigV4 by hand (held to an
independent implementation's signature), ureq without the guard (the owner's endpoint), uploads one at a time on a
`Lane` thread; `Packs::uploads_done` (the packs service's tick) marks what's stored and sends it. A key's prefix is encoded
in the public URL too (Kotlin's left spaces in it).

## WebAssembly (`crates/runtime/src/wasm/`)

Kotlin's `wasm/` and `WasmImpls`; the plugin-capabilities skill's WebAssembly section is the design. **wasmtime** (41, the
last on the workspace's Rust 1.90; features `runtime`, `cranelift`, `pulley`, no `parallel-compilation`) runs modules,
chosen over wasmi because it holds every limit Kotlin does, and holds them better:

- **Time**: epoch interruption. One thread (`Ticker`) moves the engine's epoch every 10 ms; each store's deadline callback
  asks its `Guard` (closed? past its deadline?), so a call past `max-call-seconds` and an instance closed from the main
  thread both trap at the next tick, in compiled code and the interpreter alike. That's Kotlin's watchdog and
  `Thread.interrupt` in one. Fuel isn't used: the limit is in seconds, as Kotlin's is.
- **Memory**: a `ResourceLimiter` per store refuses a growth past `max-memory-mb` (`memory.grow` answers -1), where
  Chicory could only cap; the `Budget` still reserves each instance's or program's most against `max-memory-total-mb`
  (a `Reservation` the store holds, given back when it's dropped).
- **Speed**: Cranelift's machine code, as Chicory's compiler made JVM classes; `wasm.compile: false` is **Pulley**,
  wasmtime's interpreter (the same code compiled to its bytecode), and a machine Cranelift can't target falls back to
  it with a warning. wasmi is an interpreter only.
- **SIMD runs**: Kotlin refused it (Chicory runs none); wasmtime runs it in both modes, so that refusal is gone.

Pieces, each Kotlin's of the same job:

- `wasi.rs`: **WASI preview 1 written here** (all 46 functions on one `Linker` per engine, an `InstancePre` per compile),
  not `wasmtime-wasi`, so a module gets exactly what Kotlin gave it and nothing of the host's: standard streams are
  `Input`/`Output`s (nothing for a library, the given text and `CappedOutput` for `run`, line queues for a program),
  arguments with `argv[0]` the module's name, only the script's environment, the system's clocks, `getrandom`; sockets
  `ENOTSUP`; `proc_exit` is an `Exit` error the caller turns into a code; `poll_oneoff` answers descriptors ready and
  sleeps for clocks, checking the guard. Memory is reached through `Mem` (every access checked, `EFAULT` past the end).
- `files.rs` (`WasmData`, `Files`): Kotlin's `WasmFiles` whole: fd 3 is the data at `/`, `..` past the root is
  `ENOTCAPABLE`, anything that writes is `EROFS`. A data file is `DataFile::Disk` when `Config::paths` finds it (read at
  its position as the module reads), else its bytes from the source (tests, an archive).
- `compiled.rs`: validated (`Module::validate`, "isn't a valid WebAssembly module"), compiled, imports held to WASI's,
  exports' signatures in their order; a store per instance with its guard and limiter.
- `calls.rs`: arguments read from the script's table on the main thread and checked against the export (Kotlin's
  messages); under Luau a whole number in the safe range is an integer argument and anything else real, so `2.0`
  passes for an `i32`. An `i64` result past 2^53 comes back as its **digits** (a string), as a database's does.
- `program.rs`: input and output queues with the `room` of permits (backpressure), lines cut by `whole_characters`.
- `threads.rs`: `Pool` (`wasm.threads` threads, `idle` for tests), `Lane` (an instance's calls one at a time),
  `Guard`, `Budget`. Every guard is made under the session's `stopping` flag, so `Wasm::close` stops whatever still runs
  (the ticker's last tick reaches it). Programs get a thread each.
- `api.rs`: `WasmApi` (the three classes) and `WasmService` (registered after web, so it stops after every script). An
  async value is a `Reply` from the job; `instantiate`'s future files the instance on the main thread and drops it when
  its script went (an `Instantiating` guard counts it in flight until the future goes, done or cancelled). A program's
  lines and end reach scripts in the service's tick; `end` is raised while the handle answers, then the program goes
  with its handlers.
- **Reload** restarts the scripts that ran a module: `Service::restarts` (new) is asked before anything unloads, and
  answers each user scope's resource (`Modules::owner`: a module, or a resource script's file through `classify`).
  The session now reloads kinds **in format's order** (`kinds::ALL`, as Kotlin's `Kinds.all`), not by name, so a
  module restarts after the WebAssembly module it runs is compiled again.
- A compile's failure is the `runtime.wasm` problem and is warned at the next tick (there's no `problemsChanged` hook).

Not yet: `nf.test.stub_wasm` and the test tick's `settle` (with `nf.test`; `Wasm::quiet` is what it will wait on),
`/nf scripts`' per-scope counts, `WasmConfig` read from `config.yml`, the threads' lowest priority (std has no way to
set it), an `i64` argument past 2^53 (a string there is text), and a package's module reloading (the session doesn't
reload packages' files yet).

## Project items (`crates/runtime/src/project_items.rs`)

Kotlin's `item/Items.kt`: one record per `items/<id>/item.json` with its look and the look's hash (format's
`item::project_items`: `resolve`, `restyle`, `hash`, held to Kotlin's fixed hash; the canonical text hashed ends
with its newline). Each item's one script starts once modules run (`this` the `ProjectItem`), so a module's body
runs before any item's handlers exist. `nf.items.create` resolves overrides over the look and runs `render`.
`ServerEvents` puts the item's stage first on use, interact, consume, drop, pickup (then the inventory is
refreshed next tick), and the held item's on break, hit (attacks only) and interact-entity; `item_viewing` /
`item_viewed` run `view` then `item_view`, nil when a handler failed (`Scripts::handler_failures`). Views are sent
again at the end of a tick (`player:refresh_items`), everyone's when a session starts. Not yet: `Platform::bind`
(the platform's lookups, `ProjectItems::look`), an item's `block`, recipes.

## Commands (`crates/runtime/src/commands.rs`)

Kotlin's `ProjectCommands`, `CommandDefinitions` and `ArgumentValues`: `nf.commands.register` (modules only:
`Modules::owner`) checks the definition as Kotlin does (names, optional arguments last, `text` last, bounds and
choices, a `default` read by its type's codec: `netherforge_api::arguments::read_default`, generated from the
spec's `commandArguments` beside the platform's `ArgumentType`, `crate::api::ArgumentType` in the platform
crate), keeps the tree (`Node`: handler, defaults, `complete` functions) in `Shared::commands`, and registers a
`Handler` that holds the session only weakly (`Weak<Shared>`, `WeakScripts`), since the platform keeps it. The
server reads what's typed; the handler checks permissions and `players_only` along the path, resolves each
value (project names, worlds) and calls the handler as the module's code with a `CommandEvent` table. A failing
handler is reported and the sender told. A module's commands go with its scope (`scope_released`) and all of
them when the session stops.

## The Platform boundary (`packages/platform`, `crates/platform`)

The roadmap's "describe `Platform` and `PlatformEvents` in a language-neutral spec, and generate
each side from it". The spec is TypeScript like `packages/api`'s: a file per area
(`src/spec/teams.ts`), each declaration a record, an enum, a union, an interface (a group, or a
callback the runtime hands over) or an object (`CommandSender`: values with methods, made by one
side). Types are strings in Kotlin's spelling (`src/types.ts`, parsed by `src/type.ts`).

- **It was read from the Kotlin interfaces**, not copied by hand: `scripts/extract.ts` runs
  `src/kotlin.ts` (a parser for the subset of Kotlin `platform/` is written in) over every file.
  Until the Kotlin side is generated from the spec, `spec.test.ts` holds the two to each other,
  signatures only (docs may move to the spec): a change to a Kotlin interface is made with
  `pnpm --filter @netherforge/platform extract`. A plain class is a record unless it holds
  callbacks; an interface method with a body is kept, marked `defaulted`.
- **Types it doesn't declare** are `EXTERNAL`: format's (`Vec3`, `ItemDef`...), the Lua API's
  (`GameEvents`, `WatchedEvent`, `ArgumentType`, generated from `packages/api` for each
  language), and two of the runtime's. In Rust each is a placeholder (`external::*`, carrying a
  `Dynamic`) until its side is ported; then `RUST_EXTERNAL` in `scripts/rust.ts` names its real
  type (format's `Vec3`, `Matrix4`, `Box` as `BoundingBox`; the API's `GameEvents`, a trait object,
  and `WatchedEvent`), and the platform crate depends on format for them.
- **The Rust target** (`scripts/rust.ts`, `pnpm generate`, covered by the generated-files check):
  records are structs (Kotlin's defaults in their docs), enums are enums with each entry's values
  as methods (`EntityFlag::settable`) and `ALL`, a union is an enum of case structs
  (`InventoryRefPlayer`, so a case can be named alone), interfaces and objects are traits
  (`ClientEntityOps: EntityOps`). An interface is `&dyn` when returned (a group, borrowed from the
  platform) and `Rc<dyn>` when taken (a callback). `Set<T>` is a `Vec<T>` of distinct items,
  `Map` a `HashMap`, a function a boxed `Fn`, `UUID` a `Uuid`, `Any` a `Dynamic`. A Kotlin
  overload (`contents(ref)`, `contents(ref, filter)`) is named by its last parameter in Rust
  (`contents_with_filter`).

- **Fakes are generated too** (`unported.rs`): `Unported` implements every interface of the spec,
  its groups itself and every other answer a panic naming the method ("TeamOps::names isn't on
  this platform"); `FakeX` is `X` with every method defaulting to `Unported`'s (a blanket impl
  makes it an `X`). A test's fake implements `FakePlatform` and the `Fake` groups it means
  (`tests/session.rs`: the log and the server's info), so it's written as the ported runtime needs
  it and can't drift from the spec. Kotlin's hand-written `FakePlatform` (testkit) has no Rust twin.

- **The server's events** (`GameEvents`, from `packages/api`'s `raised`, by
  `emit/raisedRust.ts` beside the Kotlin targets): the sink is `crates/platform/src/api/`
  (`GameEvents`, a method per raised event taking `&mut game_event::<Name>`, the payload in the
  platform's types, a writable field written back through it, a lazy one a closure;
  `WatchedEvent`). The runtime's side is generated into `crates/runtime/src/generated/`
  (`GameEventDispatch<S: EventSession>`): each event's handles made, its path raised (the handle in
  `raised.first`, then `nf`: `Scripts::raise_path`, one event shared by the stages until `stop()`),
  its payload pushed only when something listens, its writable fields read back with their codecs
  and bounds. `events::Running` is the session's `EventSession`, and `Session::game_events()` the
  dispatch. Not yet: an entity's exact class (it's an `Entity`, or the `Living`/`Mob` the event
  says), and items, which cross as empty and are never written back until their area is ported.

Still to come: the Kotlin side generated from the spec (then `extract.ts` and the drift test
go), the FFM glue between them, the coarse crossings the roadmap asks for (per-tick batches,
handles as integers, flat buffers: annotations on the spec's methods), and the rest of
`PlatformEvents`' events as their services come (`ServerEvents`, above).

## Format (`crates/format`)

`packages/format` in Rust, ported area by area; Kotlin's stays the writer until a kind is ported
here. The rule is byte-identical output: a file the Kotlin side wrote reads and prints back
unchanged.

- **The printer** (`json.rs`): `CanonicalJson.print`, `formatNumber`, `quote`, `childPath`.
  Numbers take Rust's shortest round-trip digits (`{:e}`) and lay them out as format does (a
  point past 21 digits or 5 zeros becomes an exponent: `1e+21`, `1e-7`). It prints a
  `serde_json::Value` in the order its entries are given (`preserve_order`); the models put
  maps in canonical order. `tests/json.rs` holds it to every project file in `examples/`
  (118 files), leaving out what format doesn't write (`.luarc.json`, a pack's Minecraft
  models).

- **Problems** (`problem.rs`): `Problem`, `ProblemSink`, `Severity`, as format's. **The
  catalogue is format's**: `build.rs` reads `docs/format/problems.md` (which Kotlin's
  `ProblemCodes` writes through its golden test) into constants, `codes::PROJECT_NAMESPACE`,
  so a code that isn't in the catalogue doesn't compile, and there's one list. When the Kotlin
  side goes, the catalogue moves into this crate and the page is written from it.

- **The strict reader** (`read.rs`, `read::parse::<T>(text, file)`): serde models with
  `deny_unknown_fields`, `serde_path_to_error` for the path, and format's messages and
  positions as the golden tests have them, which are kotlinx's: an unknown key at the object
  that has it, with its first character's line and column (serde doesn't say where a type's
  own error is, so `locate` walks the text along the path to find the key); a missing key; a
  value that isn't one of an enum's (no position, as kotlinx); a trailing comma just past the
  comma (serde calls one before `}` "key must be a string"). `tests/read.rs` holds it to the
  goldens' parse problems.

- **Canonical models** (`canonical.rs`): a map is `Entries<K, V>` (`Map<V>` for string keys),
  **read in the file's order and written in format's**. The order matters both ways: Kotlin's
  checks walk a map in the file's order (a `LinkedHashMap`), so problems come out in that
  order (the loader's sort by file and line is stable), while writing sorts by `K`: `Key`'s
  order is kotlinx's `ordered` (whole-number keys first, as numbers, then the rest as strings),
  and an enum's derived order is its declaration's, which is format's. Fields are serde's `camelCase`, an absent optional
  is left out (`skip_serializing_if`), and a sealed type is `#[serde(tag = "type")]`, which
  writes the tag first as kotlinx does. `canonical::write` prints a model as its file.
- **`netherforge.json`** (`manifest.rs`): `Manifest` and everything in it, and `canonical()`
  (`ManifestKind`'s: the schema, sets sorted, worlds that say nothing dropped). Every manifest
  in the examples and the goldens reads, and the examples' write back to the same bytes.

- **The manifest's checks** (`manifest::load`, `listing_files`): `Projects.loadManifest`, with
  `names.rs` (format's `Names`; Rust's `regex` has no lookahead, so a pattern's length limit is
  checked on its own), `MinecraftVersion`, and the settings' checks and reading
  (`settings.rs`). `tests/goldens.rs` runs every `testdata/invalid` case and compares its
  `netherforge.json` problems with `expected.json`'s: all 51 agree, leaving out the checks
  across files (`project.world-height`, dependencies, exports) until those are ported. The
  harness grows with each kind until it compares whole cases.

- **The registry of kinds** (`kinds.rs`): `kinds::ALL` (format's 27 kinds in its order, with
  layout, contents and flags) and `classify`, `Kinds.classify` with its groups, packages,
  companions and variants. `tests/kinds.rs` has `KindsTest`'s and `GroupsTest`'s cases, and holds
  the table to the `KINDS` format generates for the editor
  (`build/generated-contract/constants.ts`) whenever format's build is there, which `pnpm test`
  makes first.

- **The scan** (`snapshot::scan`): `Projects.load`'s first part, which needs no resource models:
  every path classified, group folders checked (`project.group`), an id read from its first
  place in path order (`project.duplicate-id` for the others), and each resource checked for a
  usable id and its main or companion file (`project.id`, `project.missing-file`,
  `project.stray-file`). The goldens agree on every one of those codes in every case.

- **The models are the single source** (decided after the first kinds: format defined each
  resource type four times by hand, in Kotlin, the format docs, schemas generated from Kotlin's
  serializers, and the Rust copy, and wrote its simple rules as hundreds of hand-coded checks).
  A model derives serde and `JsonSchema` (schemars), so its JSON Schema comes from it, and
  later its TypeScript types (specta, as the editor's commands already are).
- **Simple rules are a field's**, declared once as schema constraints (`range`, `regex`,
  `length`, item counts, `propertyNames` for a map's keys) with `x-problem` (the code) and, for
  a pattern, `x-rule` (what it means in words). `rules::check` walks the document as written
  against its schema and words each broken rule from a template (`maxTicks must be at least
1`, `color must be #rrggbb`), and the editor reads the same constraints in the schema.
  `rules::uncoded::<T>()` lists a constraint with no code, which each kind's tests assert is
  none. A kind's `validate` keeps only what a schema can't say (a slot inside its menu's size).
- **A stack and a project item share their look's fields, declared once**: `item_with_look!`
  lays the look (kind, name, lore, enchantments... `useEffects`, with their rules) where a struct
  says `..look,`, and writes its `look()`. `ItemDef` is the look and then a stack's own fields
  (`item`, `count`, `damage`, `map`, `data`); `ItemFile` is `$schema`, the look, then its own.
  Format's Kotlin wrote the look twice (`ItemDef`, `ItemFile`) and copied it in `look()`; for one
  definition to serve both, a stack's own fields moved after its look in Kotlin too (a format
  change: the examples rewritten by Kotlin's writer, the reference goldens regenerated).
  `look()`'s copy is held to every look field by a test. A project item's missing `kind` is
  now the `item.kind` problem (Kotlin made it a parse error).
- **Defaults are declared once, in the field's schema** (`extend("default" = ...)`), where the
  editor's fields will read them, instead of Kotlin's constant plus `…OrDefault` getter for each
  (the dimension type had 20). The checker also takes `multipleOf` (a height in steps of 16).
- **One definition of a pattern**: a colour is `rules::RGB` (or `ARGB`, `RGB_LOWERCASE`), named by every field that
  takes one (Kotlin defined `#rrggbb` five times); an id is `names::ID_PATTERN` with `ID_RULE`, a world
  name `WORLD_NAME_PATTERN`, a node name `NODE_NAME_PATTERN`. A hand-written check that shares a
  field's pattern asks `rules::matches_pattern`.
- **Ruled strings** (`ruled_string!` in `rules.rs`): a string with a rule of its own wherever a model
  has one is a newtype whose schema carries its pattern, `x-problem` and `x-rule`, so a field of it
  (or a map keyed by it, as `Entries<PackKey, _>`) needs no attribute. A resource pack's keys,
  texture, model and sound paths, a terrain's names and a centity's worlds are; about 25 pack fields
  share `TexturePath`. Ordered as map keys are (`canonical::key_order`).
- **Enum names are serde's**: `canonical::name_of(&value)` gives a variant's name as the file
  writes it, so no kind spells its enum's names a second time in an `id()` match.
- **`GameData` is format's**: registries and tags by the game's key (`minecraft:worldgen/biome`),
  with `Registry` a shorthand for the ones checks name often (`Registry::Biome.key()`), plus a
  particle's data kind, a block's properties and the data pack format. A check reports "has no"
  through `lacks`: the facts know the registry and it hasn't got the id, which is format's
  `has(...) == false` (an unknown registry says nothing). `block_state` parses `id[k=v]` and checks
  it against `block_properties`, with each kind's codes (`BlockStateCodes`).
- **What a check can see** (`document::Context`): the resource's id and location, the files in its
  folder, a reader for them (`read`: a pack's model files, a datapack's JSON), and the game. A
  problem in another file of the resource (a sound file, a model file, a datapack's entry, a
  terrain's `.lua`) goes into the same sink with its own file (`code.at(file, ...)`).
- **Format's other homes**: `math` (`Vec3`, `BoundingBox`), `animation` (`Keyframe`, `Easing`,
  `by_time`), `noise` (`NoiseDef`), `worldgen` (`GenerationStep`, `SpawnRange`), `block_state`.
- **A field rule and a hand check may both report a doubly wrong value** where Kotlin reported once
  (a cue's duration with no text, `every` without `burst`): only doubly wrong files differ.
- **The feature table is format's** (`features.rs`): `docs/format/features.md`, which Kotlin's
  `FeatureTable.CURRENT` writes through its golden test (`FeatureCatalogue`), read by `build.rs`
  into `features::ALL`, as the problem catalogue is. A check asks its context
  (`cx.require_feature("sky-night", sink, "$.darkens")`, `project.feature`); a test loads with a
  table of its own (`project::load_with`). When the Kotlin side goes, the table moves into this
  crate and the page is written from it. Not yet: what a kind writes into the start-up datapack (a
  sky's biome twins), which comes with the datapack.
- **Patterns are compiled once**: names' and ids' checks are `names::is_*` and `game::is_id` (a
  `LazyLock` each), and `rules` caches a schema's patterns, so no check compiles a regex.
- **Shared pieces**: `item.rs` is `ItemDef` (a stack, in menus, loot, recipes and items) with
  its rules on its fields (`Rules.item`'s ranges and patterns, about half of it) and `check`,
  the rest (ids the game must have, a durable item that can't stack, modifiers sharing an id,
  near-miss names). A rule on a map's keys is reported at the key itself (`$.stats["Bad Name"]`),
  which Kotlin now does too (its buff check said `$.stats`). `script.rs` is `ScriptDef` (its
  path and budget are field rules; `check` asks that the file is there). `game.rs` is the `GameData` the checks ask (registries, tags, durability):
  a project loads without it, and those checks say nothing then. `canonical::Free` is
  free-form JSON (an item's `data`), written sorted however deep as kotlinx does.
- **Kinds, one by one** (`kind/<kind>.rs`): `kind::document_kind` is **the one list** of the document
  kinds, each id to its model behind one face (`DocumentKind`: read, write back, schema), which
  reading, writing back and the checks across the project all go by; `kind::read` adds the files
  kinds (`module`, `build`, `migration`, `map`, `wasm`). There was a second match for writing back;
  before that, a hand-kept list of ported kinds once drifted from the match (rustfmt split it a line
  apiece) and six kinds' goldens silently didn't run. Each document kind is a `Document`
  (`document.rs`): its `SCHEMA` from a file one folder deep, which `schema_at` adjusts for the
  folders the file is really in (group folders add `../`, as `schemaAt` does), its `canonical()`,
  its `validate`, its `cross_check`, and the entries it puts in the start-up datapack
  (`datapack_entries`). A scripted kind says so (`Document::SCRIPTED`, `script()`), and `load`
  checks the Lua files beside its script are named so `require` can reach them
  (`script.file-name`), for every such kind at once.
- **What the goldens hold**: `tests/goldens.rs` loads each `testdata/invalid` case whole
  (`project::load`) and compares its problems with `expected.json`'s by what's wrong and where
  (severity, file, path, code, position, related), not their wording, since the field rules word
  themselves; each kind's own tests hold its wording, and the manifest's and the layout's harnesses
  compare exact messages. A parse problem is compared by file and code only: kotlinx gives no
  path or position for a custom type's error (an ingredient's), where the reader gives both.
  **All 51 cases agree, nothing set aside.** The examples are valid projects: each loads without
  errors (`tests/references.rs`) and writes its documents (and `fonts/default.json`) back to the
  same bytes. When the Rust side takes over, the goldens are written from it. The goldens load
  without game data or pictures, as Kotlin's do, so what needs those has tests of its own
  (`tests/across.rs`).

### References and the checks across the project

- **One walk of a document by its schema** (`schema.rs`): `schema::of::<T>()` makes a model's
  schema once and keeps it, and `Schema::walk` goes through a document as written, visiting every
  schema that describes each value (its field's, the `Option` or tagged enum branch it is, an
  `allOf`'s parts) with a state its items and entries inherit. The field rules (`rules::check`),
  the references and the stacks are all visitors of it.
- **A reference is marked on its field**: `extend("x-ref" = "item")`, Kotlin's `@Ref`, and
  MiniMessage text `extend("x-minimessage" = true)`, Kotlin's `@MiniMessage`, whose
  `<glyph:pack/key>` tags name glyphs. A mark on a list or a map holds for its items or values,
  never a map's keys. A ruled string carries its own (`TexturePath` is a `texture` reference
  wherever it's used, `ModelPath` a `model`, `SoundPath` a `sound_file`), so about 30 pack fields
  need no attribute. `reference::RefKind` is format's `RefKind`, its names serde's and its facts
  (noun, scope, registry...) one match from a macro. Kotlin's pack `models` map wasn't marked, so a
  model file's rename missed it: marked in both now. `tests/references.rs` holds every reference
  the examples make to format's `testdata/references` goldens, and every mark to a `RefKind`.
  A glyph tag's place in its text is in UTF-16 code units, as the editor counts.
- **An ingredient has a real schema** (a string, or `{ "item": <item reference> }`), where it was
  any JSON; so its project item is found like any reference, and the editor can read it.
- **`project::load`** (`project.rs`) is `Projects.load` for one project: the manifest,
  `fonts/default.json` (`font.rs`), `client/vanilla.json` (`vanilla.rs`), the scan, each resource
  read on its own, what the project defines (`reference::Defined`: each kind's ids, packs' entries,
  datapacks' registry entries and tags in its namespace), every reference its files make, then each
  resource against the rest. It reads from a `ProjectSource` (format's: files, their text, and a
  picture's pixels or size when the host reads them; `Files` for files alone).
- **What a check across resources sees** (`project::Across`, Kotlin's `KindContext`): the resource,
  every other resource's model by type (`models::<BlockFile>()`, found by downcasting, so no kind
  needs a second name for its model), ids that read, places, the references, the default font,
  `client/vanilla.json`, the game, and pictures. A kind's `Document::cross_check` reports into its
  sink, a problem in another of its files with that file. A files kind's are `files::cross_check`.
- **Every stack is found by its schema**: `ItemDef` is marked `x-stack`, and `item::check_stacks`
  checks each one in any document against the project item it names (`item.kind-mismatch`), where
  Kotlin's menus, loot, recipes, dialogs and particles each called `checkStack` by hand.
- **The references' check** is `ReferenceIndex.check` with Kotlin's messages, for every reference
  of every document and the manifest. Each kind's `cross_check` is only what a reference means
  beyond existing.
- **A carrier's states** (`kind/carriers.rs`, `BlockCarriers`' pools) are counted by listing them,
  as the pack build's plan will take them, so a block's capacity has one definition.
- **A terrain in a world**: a height in the world is marked `x-world-y`, its range written once from
  `LIMITS` (it was the same two numbers ten times), and its checks of heights together take the
  height. `TerrainFile::in_world` is what a world's dimension type adds (`project.world-height`).

- **Packages** (`package.rs`, Kotlin's `Packages`, `VersionRange`, the lock's models): one package
  per namespace across the tree, from a host's `PackageSources` (folders beside the project, git
  commits it opens; `package::Folders` for a host that only has folders), version ranges, the
  lock's pins and staleness, cycles and conflicts. `project::load(source, Options { game,
features, packages })` reads each package in its own namespace, checks it on its own (what it
  can name is what its dependencies export: `Defined::exported`), and then the project. A loaded
  `Project` borrows nothing of its source, so a package's sits beside its source in
  `LoadedPackage`. A host computes a package's hash from format's listing (`package::hash`); the
  library needs no SHA-256. `tests/packages.rs` holds every `testdata/packages` case (43 problems),
  with Kotlin's `TestPackages` ported.
- **What the runtime takes** (Kotlin's `ProjectSnapshot`): `Project::everywhere(kind)` and
  `running::<T>()` give every resource the server runs by the name it knows it by (the project's
  by id, a package's as `ns:id`), `running` only those without errors and a package's from a
  copy with every reference written in full (`reference::qualify`: the references rewritten by the
  same schema walk, glyph tags in text included, which rename and move will use too).
  `place_of`, `manifest_of`, `references_of` and `nameable` answer for any namespace of the tree.
  `project::Folder` is a project on disk.

- **WebAssembly modules** (`wasm.rs`): `WasmInfo` and its reader (`wasm::read`: the framing and the sections a
  check needs, not the code), the WASI imports the server provides, and a module's checks, which run as the files
  kind's `cross_check` on what the host read (`ProjectSource::reads_wasm`/`wasm`; a host that reads none, the
  editor, says nothing). `WasmInfo` reads Kotlin's JSON for it. `tests/wasm.rs` is `WasmTest`.

- **The dev bridge** (`bridge/`): the protocol defined in Rust, which the editor's TypeScript is generated from
  (decided: Rust and specta). One `protocol!` table writes each method's typed marker, the routing tables and the
  TS maps; the messages follow Kotlin's encoding (left out when null or default: `is_default`, maps as `IndexMap`
  in their order). `rpc.rs` is `JsonRpc` with its rules and messages, `auth.rs` (feature `bridge-auth`) the
  handshake's HMAC proofs, moved from the editor's backend. `typescript_types()` (feature `specta`) strips
  `| null` from `Option` fields, which are never sent as null. `GameDataBundle` (`game.rs`, `export_game_data`'s
  answer and the editor's cache) is a `GameData`. `Problem` serializes with serde. `tests/bridge.rs` is
  `BridgeTest` on the recorded session, plus the list of methods held to `Bridge.kt`'s.

Not yet in the project load: `ProjectCache` (reading again only the files that changed, for hot reload).

Next in format: the compilers, with the runtime pieces that use them. The compilers are a centity's, a terrain's generator, a pack's build, and the start-up datapack.

- **Compiled forms** (`Document::compile`): a kind that runs something other than its model says what
  (`compiled(…)`), and the loader compiles every resource without errors, a package's from its qualified model as
  `ns:id`. `Project::compiled::<C>()` gives them back typed (`running::<T>()` stays the models, for the kinds that run
  them as they are). `tests/compiled.rs` holds every example's compiled forms to `testdata/compiled/<example>.json`,
  which Kotlin's `CompiledGoldenTest` writes by reflection; compared as JSON values, a null and an absent field alike.
- **A compiled form's defaults and clamps are its fields' schemas'** (`resolve.rs`): `resolved::<Model, Resolved>`
  fills each absent field from its `default` and holds each number in its range (a warning-only range is clamped,
  which is what its warning says), so `ResolvedBody` has no constant or clamp of its own; `filled` does the same into
  the model itself, for a compiler that reads it field by field (the terrain's). A default that depends on another
  field stays code (a cave's threshold, by its type).
- **Centities, menus, cutscenes, particle effects** compile as format's do; a centity channel and a camera path
  find their segment through one `animation::segment`. `math.rs` has `Matrix4` (JOML's conventions) and the angle
  maths. The effect sampler draws from **Kotlin's `Random`, bit for bit** (`random.rs`, XorWow), which the terrain
  generator uses too; its golden (`testdata/particles/samples.json`) agrees to 1e-12, the last bits being libm's.
- **Noise is format's doubles**: FastNoiseLite's Rust port vendored with every `f32` a double
  (`noise/fast_noise_lite.rs`; one addition regrouped to the Java reference's order). `noise::seeds` is
  `TerrainSeeds`. Every line of `testdata/noise/golden.txt` agrees to the last bit.
- **Terrain**: `kind/terrain/compiled.rs` (the compiler, `with_structures`, `with_script_blocks`),
  `generate.rs` (the generator, its `Sampler` with interior caches, the stages), `density.rs`. The fixtures of
  Kotlin's terrain goldens are files in `testdata/terrain` both sides read (`hills.json`, `areas.json`,
  `tree.json`, `density.json`). The editor's map and slice are `preview.rs` (`TerrainPreview`,
  `testdata/terrain/preview.json`); a script failure serialises straight as the editor's `TerrainScriptError`.
- **Terrain scripts run on the runtime's host** (`crates/terrain`): the generator's `TerrainScript`/`ScriptState`
  traits are format's, the Luau host is `netherforge-terrain`, so format stays free of Luau (and buildable to
  WebAssembly). Each state is a `netherforge-luau` `Host` in a per-thread pool; `glue.luau` is `terrain-glue.lua`
  with the host's budget, memory and caps, and what Lua 5.4 did in 64-bit integers in Rust (Lua 5.4's
  `math.random` stream, `Random::lua_seeded`; a plan's splitmix64). The API scripts see is unchanged, so the
  scripted worlds agree with `script.txt` and `plans.txt` hash for hash. What Luau can't say: `math.type` is by
  whole-number-ness, a world seed past 2^53 rounds, `1 << 40` is `2 ^ 40`.
- **Datapacks and resource packs compile per resource, and are built across them**: a resource pack's
  `compile_all` (`kind/resource_pack/compiled.rs`) numbers glyphs across every pack, so the compiled packs are
  `Project::compiled_resource_packs` (none while any pack has errors), not `compiled::<C>()`. Its parts are
  `fonts.rs` (`PackFonts`' layout), `hud.rs` (HUD lines, half-even rounding as Kotlin's), `sounds.rs`, `models.rs`.
- **The built pack** is `build.rs` (`PackLayout`): every file a player's pack holds, `Entry::{Text, Copy, Bytes}`;
  the blocks' plan is `carriers::plan`, and the pictures a build draws are written by `png.rs` (stored deflate,
  byte for byte Kotlin's). `tests/pack_build.rs` agrees with `testdata/packs/<example>.json`, which Kotlin's
  `PackBuildGoldenTest` writes (bytes as SHA-256).
- **The start-up datapack** is `startup::build`: each kind says its files once (`Document::datapack`, given its
  running resources), dispatched through `kinds::ALL`, so there's no list of kinds that write files. Biomes,
  dimension types, structures, advancements, dialogs, skies' twins and generated loot are `startup`'s submodules;
  the text (MiniMessage to JSON) comes in as `TextJson`. `tests/startup.rs` agrees with `testdata/datapack`.
- **Text** (`text/`): glyph tags (`text::glyph`, moved out of `reference`, so the references' walk, the measures
  and the pass read tags one way), space tags (`text::space`), the MiniMessage pass and `TextMetrics` (the editor's
  previews), and `TextWidth` (the server's exact `nf.text.width` and its `<at>` layout). The two measurers stay apart
  for Kotlin's reasons (`metrics.rs` says why). **Positions are UTF-16 code units**, as the editor counts: the pass
  and the measures walk the text's UTF-16 units, so they're Kotlin's index for index. `tests/text.rs` is the three
  Kotlin tests. The default font's advances are `font.rs`.
- **Display glyphs** (`kind/resource_pack/display.rs`, `DisplayGlyphs`): the pixel glyphs the pack build draws, an
  image's text and runs, the rect, image and text boxes and `fit`. A picture is `image::Pixels`, whose resize rounds
  half-even as Kotlin's `round` does. `tests/display.rs`.
- **Requirements and policies** (`requirement.rs`, `policy.rs`): `Requirement` (what `requires` grants, its id and
  declaration, `declared`, `combined` across the tree) and `Policy` (`policy.json`: `refuses`, `check`, `and`), with
  Kotlin's messages. `Requirement::Plugin` goes with plugin interop, on both runtimes at once. The runtime's one
  `requires` check asks `Requirement` (`Grants`: the project's own declaration until packages' code is ported;
  then per package, with the policy and Kotlin's hidden-tail-call rule). `tests/requirements.rs`.
- **Rolling loot** (`kind/loot_roll.rs`, `LootRoller`): vanilla's rules on Kotlin's `Random`, so a seed's drops are
  the JVM's and JS's (Kotlin's test pins one seed's, and it agrees). It draws `nextLong` too, which `random.rs` has
  from Kotlin's stdlib on every branch (a power of two by its bits, the rest by rejection), held to `random.txt`,
  which `RandomGoldenTest` now writes with long draws after the old ones. An error (a missing table, a cycle past
  `MOST_DEPTH`) is a `Result`, where Kotlin threw.
- **Converted videos** (`nfv.rs`): the `.nfv` layout once (header, index, records, the per-map delta, zlib through
  `flate2`), which the editor's converter writes through (its `Writer` streams `record`s) and the runtime reads with;
  `encode` writes a whole video in memory for tests. The editor and the Kotlin reader each defined it before.
- **Map colours** (`map_colors.rs`, `MapPalette` and `Dither`): the palette, the redmean match (per 15-bit cell,
  exact for Floyd–Steinberg) and the three dithers, whole-number arithmetic only. It was the editor's video
  converter's Rust copy (`src-tauri/src/video/palette.rs`), moved here and made general over where pixels come from
  (`Dither::apply` takes a pixel function and answers none for a clear one); the editor now uses it, so the server's
  pictures and the editor's videos are matched by one definition. The game's colours come from
  `GameData::map_colors`. `tests/map_colors.rs` has `ScreenTest`'s cases.
- **Workspaces** (`workspace.rs`): `netherforge-workspace.json` (its model, canonical form and checks), its project
  patterns (`is_pattern`, `expand`), and the network names scripts use (`network_uses`, `check_network_uses`) on the
  Lua tokens of `lua.rs` (`tokens`, beside `literals`). `tests/workspace.rs`.

## One source for each thing

The port's rule beyond format: nothing is defined by hand in two places, since that's where
the drift comes from. An audit of the repo (November 2026) found these, besides format's
models and rules (above). Each is fixed where its code moves to the Rust side; changing the
Kotlin or TypeScript first would only be thrown away.

| What's repeated                                                                                                                                                                                                                                             | Where today                                                                          | The fix, and when                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **The editor's inspector fields restate format's rules**: about 470 hand-written fields, each with its label, `dataPath`, default, step and `min`/`max` (138 of them) and hints restating validators' words. It has drifted (downfall had no `max`, fixed). | `apps/editor/src/editors/**`, `minecraft/item/ItemForm.tsx`                          | One `<SchemaField path="climate.downfall"/>` reading range, default, whole-number and choices from the models' schemas (schemars, through specta) and editing through one path setter; hand-written only where a field is unusual. When the editor takes format from Rust (WebAssembly).                                                                                                                                                           |
| **Server config in four places**: each `*Config` class's defaults, `RuntimeConfig.kt`'s parser per key, `config.yml`, and `docs/guide/server-config.md`'s tables (`messages-per-tick: 100` four times); `HubSettings.kt` the same way.                      | `apps/plugin/runtime/.../RuntimeConfig.kt`, `paper-common/.../config.yml`, the guide | Config declared once as serde models (kebab-case, a doc and a fallback rule per field), with `config.yml` and the docs' tables generated from them and one generic reader. When the Rust runtime reads config.                                                                                                                                                                                                                                     |
| **The Lua `Item` table mirrors `ItemDef`**: its fields copied into the API spec's `Item` record, and `LuaItems.kt` checking each field's type by hand (`FIELDS`, `FOOD_FIELDS`...).                                                                         | `packages/api/src/spec/menu.ts`, `apps/plugin/runtime/.../item/LuaItems.kt`          | An item table read through format's `ItemDef` in snake_case, strictly (as menus and dialogs from Lua already are, `api/Definitions.kt`), with only `raw`, `data` and partial matches by hand; the spec's record generated from the model. When the Rust runtime's `ItemCodec` stops being a placeholder.                                                                                                                                           |
| **Terrain scripts' own `vec3`, `nf.math` and argument checks**, a second copy of the prelude's (already disagreeing: `lerp` takes a self in one and not the other).                                                                                         | `packages/format/src/commonMain/lua/terrain-glue.lua`                                | The terrain's state now runs on the runtime's own host (`crates/terrain`): its sandbox, budget, memory and caps are the runtime's, and its 64-bit numbers Rust's. Still to do, when Kotlin goes: `vec3` as the runtime's native `vector` (32-bit, so it changes worlds: the goldens are rewritten then), `nf.math` from the runtime's `NfMathApi` once that's ported, and the wrappers generated from `packages/api/src/terrain.ts` as `nf`'s are. |
| Smaller: `/nf`'s subcommands listed three times (dispatch, completions, usage); the editor's `core/format.ts` re-exporting format's names by hand (about 300 lines).                                                                                        | `admin/AdminCommand.kt`; `apps/editor/src/core/format.ts`                            | A command table; the re-exports go when the editor imports format's generated types directly.                                                                                                                                                                                                                                                                                                                                                      |

The audit found the rest single-sourced or logic: the dev bridge (format's), the network
protocol, the per-version adapters (real API differences), `PaperGameEvents` (each handler is a
translation), the fakes (held to the real thing by contract suites), the editor's kind registry,
the datapack emitters, the terrain generator and the noise library.
