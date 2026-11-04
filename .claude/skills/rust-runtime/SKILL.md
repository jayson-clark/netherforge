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
     else is written as the shortest text that reads back as the same double (Rust's `{}` for
     `f64`, ryu), with an exponent past 1e21 as JavaScript does. NaN and infinities are still
     refused. `CodecNumbers` (the C `%g` emulation) goes: there's no C library's text to match
     any more, because Rust writes the numbers on both sides.
   - **Reading**: a JSON number is a double. **An integer literal outside the safe range reads
     as a string of its digits**, so an id in an HTTP response survives `nf.json.decode`
     instead of turning into the nearest double. A literal with a fraction or an exponent is a
     double whatever its size.
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
