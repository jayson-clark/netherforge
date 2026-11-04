---
name: rust-runtime
description: The port of the plugin's runtime to Rust with Luau scripts (phase 1 of ludicraft's ROADMAP.md) - where the crates are, the decisions taken along the way, and first of all how numbers work once every Lua number is a double. Read before touching anything under crates/, or before porting a piece of apps/plugin/runtime.
---

# The Rust runtime

The runtime (`apps/plugin/runtime/`) and `packages/format` are being ported to Rust, and
scripts move from Lua 5.4 to Luau (phase 1 of `ludicraft/ROADMAP.md`). The Kotlin runtime stays
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

| Path           | What it is                                                                                   |
| -------------- | -------------------------------------------------------------------------------------------- |
| `Cargo.toml`   | The runtime's workspace (`crates/*`). The editor's backend is still its own workspace.       |
| `crates/luau/` | `netherforge-luau`: the Luau host. One state per room, scopes, the guards, memory per scope. |
| `crates/api/`  | `netherforge-api`: the Lua API for the Rust runtime, generated from `packages/api`.          |

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

Still to come in the host: the prelude's event core, tasks and `require`, and generated
bindings.

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
  capability check, before anything is read).

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
`pending::defer` with the script's callback and returns the wait's id. `pending::deliver`
polls every wait once at the room's tick boundary and calls back the ready ones (through the
host's guarded call) with `value, err`. Nothing is delivered inside the call, and game logic
never runs off the room's thread. Tasks waiting on a value come with the prelude's tasks.

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

Not ported yet (the Kotlin runtime does them): `:on` on handles other than `nf` (it needs each
service's liveness), `nf.wait_for` on a handle, checking a writable field's value as it's
assigned (`events.check`), the same error within five seconds only counted, the per-scope time
per tick (`maxTickMillis`, which holds back timers and tick handlers), time per scope and the
profiler, and an async function's waiting form (no callback, in a task).

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

Not ported yet: packages (`acme:api`, exports, namespaces), group folders (`modules/(x)/m`),
and where the session's reload restarts dependents.

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
  language), and two of the runtime's. In Rust they're placeholders (`external::*`, carrying a
  `Dynamic`) until their side is ported.
- **The Rust target** (`scripts/rust.ts`, `pnpm generate`, covered by the generated-files check):
  records are structs (Kotlin's defaults in their docs), enums are enums with each entry's values
  as methods (`EntityFlag::settable`) and `ALL`, a union is an enum of case structs
  (`InventoryRefPlayer`, so a case can be named alone), interfaces and objects are traits
  (`ClientEntityOps: EntityOps`). An interface is `&dyn` when returned (a group, borrowed from the
  platform) and `Rc<dyn>` when taken (a callback). `Set<T>` is a `Vec<T>` of distinct items,
  `Map` a `HashMap`, a function a boxed `Fn`, `UUID` a `Uuid`, `Any` a `Dynamic`. A Kotlin
  overload (`contents(ref)`, `contents(ref, filter)`) is named by its last parameter in Rust
  (`contents_with_filter`).

Still to come: the Kotlin side generated from the spec (then `extract.ts` and the drift test
go), the FFM glue between them, the coarse crossings the roadmap asks for (per-tick batches,
handles as integers, flat buffers: annotations on the spec's methods), and the server's raised
events (`GameEvents`) in Rust, from `packages/api`'s `raised`.
