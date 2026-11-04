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
     today's prelude, and `Random`'s tests keep their expected values. A seed is a whole number
     (`integer`), and a seed too big for that is an `Int64` string.
   - `math.random` is Luau's own (PCG32 in Luau, seeded per room by the runtime), which also
     makes it deterministic per room's seed, as the roadmap wants for replays.
5. **The prelude's own uses** (`math.type`, `math.tointeger`, `math.ult`, `string.pack`'s
   integer formats, `%d`). Each becomes `is_whole(n)` (`n == floor(n)`, finite) or goes with
   the code that moves to Rust. Luau has no `math.type`, so scripts that called it get the
   same error any missing function gives. **`%d` changes**: Lua 5.4's `string.format("%d", 2.5)`
   errors ("number has no integer representation"), and Luau's truncates it to `"2"`. The
   prelude formats whole numbers only, so it's unaffected; scripts get Luau's behaviour.

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
  - Luau's `string.rep` has no separator (it's Lua 5.1's) and ignores one. The cap still
    counts it, which only makes it refuse sooner.
  - Luau has no `__gc` for tables, so the cap on `setmetatable` refusing `__gc` changes
    nothing there. It stays for the Kotlin runtime.

Still to come in the host: the prelude's event core, tasks and `require`, and generated
bindings.
