---
name: lua-api
description: Adding or changing anything a NetherForge Lua script can call - the spec in packages/api/ comes first, then pnpm generate (api.json, LuaLS stubs, docs/reference, and the runtime's Rust bindings in crates/api - a trait per class, the glue, codecs, the event registry and typed payloads, version gates), then implementing the generated trait in crates/runtime, then a runtime test. The type grammar, the binding model, events on classes, and the API's naming, error and nil-vs-error conventions. Read before touching packages/api/, the runtime's implementations (crates/runtime) or its generated bindings (crates/api).
---

# The Lua API

## One source, five consumers

`packages/api/src/spec/` (TypeScript, typed by `packages/api/src/types.ts`) declares every
global, namespace, handle class, event (on the class it's about) and payload shape.

| Consumer                    | How it gets the spec                                                  |
| --------------------------- | --------------------------------------------------------------------- |
| the editor's Lua features   | its lua-language-server reads `nf.lua` (see editor-lua)               |
| LuaLS users (VS Code, nvim) | `packages/api/generated/luals/nf.lua` (`---@meta` stubs), `surfaces/` |
| the docs                    | `docs/reference/*.md`                                                 |
| the runtime's bindings      | `crates/api/src/generated/` (Rust)                                    |
| tools reading the whole API | `packages/api/generated/api.json`                                     |

**Terrain scripts have an API of their own** (W5.6): `packages/api/src/terrain.ts` (`terrainApi`:
`Terrain`, `Noise`, `Chunk`, the `TerrainStages` a script returns, what the sandbox removes). It isn't `nf`'s and
nothing of the runtime is generated from it: the script runs in `crates/terrain`'s own Luau states (its
`glue.luau`; format's Kotlin `TerrainScriptGlue` for the editor's JS side, see project-format's "Lua stages"), on the
server's chunk threads and in the editor's preview. `pnpm generate` writes its
stubs (`generated/luals/terrain.lua`, classes on local tables, no `nf`), its page (`docs/reference/terrain-scripts.md`,
`emit/terrain.ts`, reusing `fnDoc`/`fieldsTable`/`fnStub`) and `generated/terrain.json`, which format's
`TerrainScriptApiTest` holds the glue to. Change the spec first, as for `nf`.

`pnpm generate` writes every generated output (`packages/api/scripts/generate.ts`,
emitters in `scripts/emit/`); they're committed and never edited by hand, so
Gradle never needs Node. `pnpm lint` regenerates into a temporary folder
(`generate.ts --out`) and fails if that differs from the working tree; it
never writes over your files.

**Types are a grammar.** A `LuaType` is a string, parsed by `parseLuaType`
(`packages/api/src/luaType.ts`, exported from `@netherforge/api`): primitives,
`T?`, `T[]`, `table<K, V>`, unions, string literals, `fun(...)`, and names the
spec declares. `spec.test.ts` fails on a type that doesn't parse or names
something undeclared.

## Where the API is implemented

The runtime is Rust; how its bindings work is the plugin-runtime skill's "The API crate", "Values", "Handles",
"`nf` per scope" and "Async values". In short:

- **Generated** (`pnpm generate`, `scripts/emit/bindings.ts` working out one binding model, `emit/rust.ts` writing
  it): `crates/api/src/generated/` holds a struct per handle class (`handles.rs`), a struct per shape and an enum
  per union that cross (`shapes.rs`, `unions.rs`), **a trait per class with functions** (`api.rs`: `CentityApi`,
  `NfServerApi`, ...; a handle method gets `this`, a namespace function the `Caller`, everything returns
  `ApiResult<T>`, an `async` one a `Pending<T>`), the glue that reads each argument with its codec and pushes what
  comes back (`bindings.rs`, `codecs.rs`), and the events registry (`events.rs`). The server's events (`raised`) are
  generated for the platform, the runtime's dispatch and the FFM proxy (`emit/raised*.ts`).
- **Implemented** in `crates/runtime` (a context struct per area implementing its trait, installed in `Api`,
  `crates/runtime/src/lib.rs`). A function the spec adds and the runtime doesn't implement is a compile error: no
  trait has defaults. The spec's `impl: "lua"` functions (`:on`, `nf.after`, `nf.task`, `Random`, `Task`, ...) are
  trait methods too, implemented in Rust (`bindings(spec, { handwritten: true })`).
- **Values** (`Vec3`, `Location`, `Item`, ...) are written once by hand (`crates/api/src/value_types.rs`,
  `codec.rs`), with their messages.
- **Numbers**: every number is a double; `integer` is checked to be whole and safe where it's read; a value that
  must be exact past 2^53 is `Int64`, which scripts get as a string (the plugin-runtime skill's "Numbers").
- **The conformance test**: the generated trait is the check (a function the spec has and the runtime doesn't is a
  build error), and `crates/api`'s tests hold the generated codecs to the spec's messages.
- **A test-only namespace** (`testOnly: true`, `nf.test`): only a test run's session has it (`Config::testing`).
- **LuaLS** checks the stubs (`scripts/emit/luals.check.test.ts`, below in "Gotchas").

## Conventions

- **The naming rules** are written up for script authors in
  `docs/reference/naming.md` (generated from `spec/articles.ts`), and `spec.test.ts` checks the
  mechanical ones: `snake_case` names, `PascalCase` types, no abbreviations
  (a word list, and no one-letter words but `x`/`y`/`z`: `callback`, not
  `fn`), no `get_`, one return value per function, boolean getters
  `is_`/`has_`/`can_` (or `exists`, or an action verb from `ACTION_VERBS`,
  each listed with the functions that need it), `is_active` only beside
  `cancel`, every `set_x` paired with `x` / `is_x` / `has_x` and taking what
  it returns, boolean fields without `is_`/`has_`/`can_`, a `player_*`
  event's payload named `Player…Event`, and `reason` only on an `end`
  payload (what caused an event is `cause`). Exceptions live in
  `NAMING_EXCEPTIONS` there, each with its reason; an
  exception or action verb nothing needs any more fails the test.
- Errors a script sees name fields in Lua spelling: a format message that
  names a file field (`maxStackSize`) is put in Lua spelling (`crates/runtime/src/definitions.rs`) on its way to
  Lua.
- Namespaces are called with `.` (`nf.centities.spawn`), handles with `:`
  (`this:play_animation`). A handle method called with `.` errors with a hint.
- **`nf` and its namespaces.** `nf` holds the core verbs (`on`, `emit`,
  `after`, `every`, `task`, the waits, `instructions_left`); everything else is a namespace class named
  `nf.<name>` (`methods: false`) listed in `nf`'s fields. Collections have
  `get(key)` and `all(filter?)`.
- **Option tables** are shapes taken as a parameter, read by their codec like
  any shape: strictly (a key that isn't a field is an error naming the
  fields), each field by its own type's codec, so a field may be anything a
  parameter may (a handle, a list of handles, a map, a union, another shape).
  An `any` field (a `context`) is the Lua value itself, which the
  implementation keeps as a registry reference (the very value) to hold past the call and hands back as it was.
- **JSON.** The runtime's one Lua↔JSON codec (`crates/luau/src/json.rs`, Rust, with the API crate's handle tags,
  `SavedHandles`) is what
  `data()` tables, `Item.data` (and the item table as a whole), `nf.json`,
  `File:read_json`/`write_json` and the tables `nf.menus.create`,
  `nf.dialogs.create` and `nf.recipes.create` take all go through, so a value
  means the same everywhere: JSON with typed values as one-key tagged objects
  (`{"$vec3":[x,y,z]}`, `{"$location":[world,x,y,z,yaw,pitch]}` (the world by
  name, a `World` handle again on load), `{"$entity":[id]}` (a player, a mob or any other
  entity: saved by its root class, back as whatever the server says it is),
  `{"$centity":[id]}`, `{"$world":[name]}`), object keys
  sorted, and a script's own key that would read as a tag (`$vec3`, `$$vec3`)
  written with one more `$` (any other key, `$ref`, as it is, so JSON for
  another service is what the script said). A handle class becomes saveable
  with `saveable: true` on the class at the top of its chain (the generator lists it in `handles.rs`' `SAVEABLE`, which `SavedHandles` reads). What can't be written is a problem at its
  key path saying what can't be done (`data.fn: a function can't be saved`,
  `value.fn: a function can't be written as JSON`): logged and skipped for a
  saved table (`crates/runtime/src/data.rs`), an error anywhere else. Anything new that
  turns script values into JSON or back (`block:data()`, a database row) uses
  the same codec.
- **A typo is an error; a condition is `nil`/`false`.** An unknown event,
  centity id or animation is an error at the script's line (an `ApiError` from the implementation, raised by the
  binding's shim at the script's line).
  A player who logged off, a removed centity, a path outside the data folder,
  a block state the server doesn't have: `nil` or `false`, never an error, so
  code holding a handle from earlier needn't guard every call.
- Argument _type_ errors come from the codecs (`crates/api/src/codec.rs`), located at the caller's line; the
  implementation returns an `ApiError` for everything else, and the shim raises it there too.
- Every position, offset, rotation, velocity, direction and scale is a
  `Vec3`, and every getter returns one value (`translation()` → `Vec3?`, one
  nil check). A place to put something is `Location|Vec3`, a bare vector
  meaning "this handle's world". Methods that take or return a position say
  which space it's in (world, the centity's, a node's parent's); physics is
  always world space. Rotations are degrees; durations are ticks (20 a second), and
  `nf.server.unix_time()` is the wall clock, for timestamps only.
- Text players see is MiniMessage, typed `Text`.
- **Version gates**: a function, event or option-table field
  newer than the oldest supported Minecraft has `since` (a feature of format's feature table) in the spec. The docs
  and stubs say so, and the generator puts the gate in the Rust glue: a gated function's binding, a gated option
  field's codec (when the script set it) and a gated event's `EventType.since` (checked by `:on`) each ask
  `netherforge_api::bind::gate`, which asks the runtime's `Runtime::older_than` (the server's version against the
  feature's, `crates/runtime/src/lib.rs`) and errors "`X` needs Minecraft V (this server runs W)". Nothing ungated
  pays for it. Nothing is gated while the feature table is empty; `bindings.test.ts`' version gates use made-up
  features, and `crates/api/tests/bindings.rs` the gate itself.
- Values that are data, not handles, are plain tables: an item is an `Item`
  table (`ItemDef` in Lua spelling, strict on read: `kind` is its definition,
  `data` the script's own data saved on the stack, and an opaque `raw` field
  carried through); a dialog's answers are `event.values`.
- **One script per resource, `this` on every surface**: a
  centity's, menu's, dialog's and project item's one script gets `this` (the `Centity`
  instance, the `Menu` window, the `Dialog`, the `ProjectItem`), and so does
  any file beside it that it requires (it runs in the script's scope); a
  module gets nothing extra.
  Parts of a resource are reached from it: `this:node(name)`,
  `this:slot(index)`, `this:button(key)`. The runtime's tests check each
  surface's `this` is the class it declares. LuaLS can't
  tell surfaces apart (it ignores nested `.luarc.json`), so `nf.lua` types
  `this` as the union `Centity|Menu|Dialog|ProjectItem`, `luals/surfaces/<surface>.lua`
  types it per surface, and new scripts start with
  `local this = this --[[@as Centity]]` (format's `Templates.script`).
- Things a script registers belong to its scope and go when it unloads; a
  handler on a handle also goes when the handle's thing does. So do things a
  script _makes_ (a playing particle effect, a playing cutscene (whose player is put back), a menu template, a dialog from
  `nf.dialogs.create`): the runtime files each under its scope and ends it when the scope is released (each
  service hears it: plugin-runtime, "Sessions"), after the scope's own subscriptions are
  gone (so only other scopes' handlers hear an effect's `end`).
- **Made in Lua, held to the files' rules.** `nf.menus.create` and
  `nf.dialogs.create` take a table in the file's shape in Lua spelling
  (snake_case keys, `Item` tables), turned into the format's model and run
  through the same strict parser and validator as `menu.json`/`dialog.json`
  (`crates/runtime/src/definitions.rs`); a problem's JSON path comes back in Lua spelling
  (`definition.inputs[1].max_length`, 1-based list positions). Their ids are
  UUIDs, which never collide with a folder id.
- **Events live on the handle they're about**: a bare
  verb on a handle (`click`, `press`, `tick`), `<subject>_<verb>` on `nf`
  (`player_join`). A field name means the same in every payload (`click` the
  kind of click, `target` what was hit, `key` a dialog button's key). Handler
  return values mean nothing: `event:stop()`, `event:cancel()` and writable
  fields do. Custom events have a `:` in their name (`nf.emit`,
  `centity:emit`). The script body is load; there are no hooks.
- **Spec flags beyond the signature** (`Fn` in `types.ts`): `since` (version gate, see
  minecraft-versions), `waits` (task-only: the binding returns `Waiting` and its shim yields the task,
  `---@async` in the stubs so LuaLS's `not-yieldable` flags a handler calling it; an `async`
  function's callback form is not marked) and `requires` (`moderation`, `http`, `db` or
  `plugin:<name>`; `src/requirements.ts` says how each is declared and what it lets scripts do).
  `requires` generates a `marshal.requires(...)` check before the arguments are read, a sentence
  in the docs and stubs (`REQUIREMENTS[kind].declared`: how a package declares it in its
  `netherforge.json`'s `requires`), and an entry in `generated/luals/gates.json` (with `since`)
  that the editor reads (`granted`: what a manifest declares). The check lands in the runtime's
  one entry point, `session.requirements.check(requirement, what)` (plugin-capabilities' "Declared
  capabilities"), held to the calling package. A function that knows a narrower requirement once
  it has its arguments calls that entry point itself with format's `Requirement`
  (`nf.http.request`: `Requirement.Http(host)` for each host it connects to), keeping the spec's
  kind-level flag for the docs, the stubs and the editor. A new kind of requirement is a
  `Requirement` subclass in format, a `requires` field in `ProjectRequires`, and an entry in
  `REQUIREMENTS` and `granted`. Command argument types are spec too (`commandArguments` on the spec): `pnpm generate`
  writes the runtime's `ArgumentType` and the value codecs, so a new one is a spec entry plus
  the runtime's resolver. The type grammar has `async fun(...)`, which emits one LuaLS overload
  per callable form; the `wait_for` union and the `nf` namespace list are derived, not written.
  The conformance test checks argument types and returns deeply.
- **Async functions** (`async: true`: `nf.worlds.copy`, `nf.http.request`, `Database`'s three,
  `World:locate_biome`) are declared with
  `asyncFunction` (`src/async.ts`): the author writes the params and the one
  `value` it gives; it adds a last optional `callback: fun(value: T?, err: string?)`
  and the returns `T?, string?` (the "Async failure" decision: the one
  exception to one return, which `spec.test.ts` allows for `async` only), and
  the doc sentence on both forms. The generated binding is the same for every
  one (`crates/api/src/pending.rs`): it reads the callback, or, without one, asks `Runtime::waiting_task` that a task
  is running (the error at the script's line, before anything starts); the trait's method returns a `Pending<T>`,
  which `pending::defer` files under the calling scope, and in a task the binding yields until the value is
  delivered (`wait_on`), resuming it with `value, err`; ending the task cancels the wait. The callback form returns
  nothing. Values are delivered at a tick boundary (plugin-runtime, "Async values"); a failure the script should
  read is the pending's error. A handle method's wait belongs to the scope whose code called it; a namespace
  function's to the scope whose `nf` it is. The callback
  is always the last argument, so a call without options passes `nil` for them
  (`world:locate_biome("minecraft:desert", nil, function(position, err) ... end)`).
  An async function's value can't be optional: a search that finds nothing
  (`World:locate_biome`) is `nil, err` saying so, like any other failure.
- **Handlers are synchronous; tasks wait.** Only code
  running in an `nf.task` may call something that waits (`nf.wait`,
  `wait_until`, `wait_for`, `dialog:ask`); anywhere else it's an error that
  says to start a task. A new waiting function is `waits: true` in the spec: its implementation registers the
  task's wake-up and returns `Waiting` (`crates/runtime/src/scripts.rs` has `nf.wait`, `wait_until`, `wait_for`),
  and `Runtime::waiting_task` is what refuses handlers and foreign coroutines. A wait that listens to a handle ends the task
  if the handle goes. Its doc says "Only in a task".
- A parameter named `...` (last) is a vararg: `nf.task(callback, ...)`. LuaLS and
  the docs print it as is; the conformance test accepts a function that takes
  `...` there.

## Writable `any` fields: `LuaCodecs.DATA`

An `any` field is a `LuaValue` (the Lua value itself) only while the call that reads it lasts. A writable field is
read back after the handlers have run, when that value is gone from the stack, so the generator reads a writable
`any` with `LuaCodecs.DATA` instead (`emit/kotlin.ts`, `shapeClass`): JSON by the one codec there and then, null for
nil, an error naming `event.<field>` for what JSON can't hold. `NetworkRequestEvent.reply` is the one now. Read it as a
`JsonElement?` (`event.reply as JsonElement?`).

## Handles keyed by name across servers: `NetworkChannel`, `NetworkMap`

`nf.network.channel` and `map` resolve the name in the calling package (`Network.resolve`) and return a handle keyed
by the full name, `namespace:name`, the same table on every call. Their methods need no scope (what they send is
the calling package's, `names.calling()`, for its rate); a map's copy and a channel's subscription are the session's,
not a scope's: a map is mirrored from the first time it's got until the session ends, and a channel is listened to
while any scope has a handler on it. They're always alive (`liveness` true): a name names the same channel on every
server.

## Handles that wrap a runtime-owned resource: `Database`

`nf.db()` returns a `Database` handle keyed by the calling package's
namespace and a connection name (`handle: { key: [namespace, connection] }`;
the connection is `""` for the package's own SQLite file, else a name from
`config.yml`'s `databases:`, W2.3), the model for a handle whose thing lives
for the plugin's life rather than a session. One class, one `DatabaseImpl`,
whichever it is: what differs is the `SqlDatabase` behind it (see the
plugin-persistence skill). `nf.db(name)` (`crates/runtime/src/databases.rs`) checks the name exists and
lists the calling package, an error at the line; the `db` requirement is the generated binding's, as for
`nf.db()`. Its three functions are `asyncFunction`s (`spec/db.ts`), so the trait (`DatabaseApi`) returns
`Pending`s: the implementation validates on the main thread (SQL that isn't one statement, a param SQL has no
value for: errors at the script's line) and starts the work on the database's lane; what only the database can say
is the script's `nil, err`. `params` is a `table` read while the call lasts and converted to Rust values before the
work starts (a Lua value must never cross to the worker). The handle isn't checked against the calling package: a
handle only comes from its package's `nf.db()`, and the calling package is the scope running, which for a package's
module called by another package's script is the caller's.

## Handles to a connection: `HttpStream`, `WebSocket`

`nf.http.stream` and `nf.http.websocket` aren't `async`: they return a handle at once, and what
happens later is events on it (`open`, `message`, `end`), so `nf.wait_for(socket, "open")` works
in a task and a handler needs none. The handle is keyed by number and URL (`url()` answers after
the end, like `Effect:kind()`); the rest answers as ended (`state()` `"ended"`, `status()` nil,
`send`/`close` false). An owner's limit is never an error at the call: the connection ends
`"limit"` at the next tick (plugin-capabilities, "Streams and WebSockets"). `nf.test`'s
`connections`, `accept`, `receive`, `sent` and `disconnect` take these same handles, the far
end being the test's.

## WebAssembly: namespace functions make, handles run (`WasmInstance`, `WasmProcess`)

What `nf.wasm` makes belongs to the scope that made it, and only a namespace function knows that scope (a
`Caller`); a sync handle method gets none (its binding never calls `marshal.caller`). So the module is a string
every `nf.wasm` function takes (`instantiate`, `run`, `start`, `functions`: each with `requires: 'wasm'`), not a
handle whose methods would make things. `WasmInstance` (keyed by number) and `WasmProcess` (number and the module's
server name, spelled for the reader by `module()`) are what they make. A call's `args` is a `table`, read in Rust
like `Database`'s params (a whole number in the safe range is an integer); results are `any[]` (an `i64` outside the
safe range as a string of its digits). A program is
like a WebSocket: returned at once, events after (`output`, `end`), limits ending it rather than erroring. The
naming rules made `args`/`env` `arguments`/`environment`.

**`bindings.lua`'s top-level locals.** Each handle class's section is a `do … end` block
(`emit/lua.ts`), its class table a local of the block, so the chunk stays far from Lua's 200
locals (`PreludeModulesTest` warns past 100); only the `<Class>_is` sets the inline checks read
are top-level.

## Checklists

**A function on `nf` or a method on a handle**

1. Add it to the spec (`packages/api/src/spec/*.ts`): doc, params (optional ones
   last), returns, an example if it isn't obvious. A string parameter that names
   a project thing (a centity, menu, dialog, animation, node, button, glyph or
   skin) gets `names`: the stubs type it as that kind's alias (`NodeName`,
   `NAME_ALIASES` in `src/names.ts`, a plain `string` in `nf.lua`), and the
   editor writes the project's own names as more of the same alias, so LuaLS
   completes them inside the quotes (project-wide: LuaLS can't tell one
   centity's script from another's). Mark it
   `impl: "lua"` only if it's logic rather than a call into the server.
2. `pnpm generate`. The runtime stops compiling until step 3. A class with many
   functions or events has its reference page in sections
   (`scripts/emit/layout.ts`: `FUNCTION_GROUPS`, `EVENT_GROUPS`, and `CATEGORIES`
   for a new page); generate fails naming the function, event or page to place.
3. Implement the new trait method in `crates/runtime` (the area's context
   struct's `impl <Class>Api`; an `impl: "lua"` function is a trait method too,
   written in Rust: the generated binding already checks its arguments).
4. Test the behaviour with a Luau script against a fake platform
   (`crates/runtime/tests/`).
5. `cargo test --workspace` (the build is the conformance check: a function the
   spec has and no trait implements doesn't compile).

**An event on a class (or on `nf`)**

1. Spec: the class's `events` (name, doc, `payload`, `cancellable`, `bubbles`,
   `writable`, `options`, `local`; `nfEvents` in `events.ts` for `nf`), and the
   payload in `shapes`, extending `Event`. A writable field may be of any type
   but a function. A handler's assignment is checked at its line by the payload
   field's codec (the event core's `__newindex` calls the `events.check`
   primitive with the first stage's owner and event), and after the handlers
   the runtime's emit (`crates/runtime/src/scripts.rs`) reads every writable field back with it into the payload, so
   a value changed in place (an item in a list) counts too; one that can't be
   read leaves the field as it was, with a warning in the log. A new
   `player_*` event on `nf` is on `Player` too, without the prefix
   (`playerEvents` in `entities.ts`). A class with events gets `on` and
   `once` from `eventFunctions(...)` (and `emit` with `customEvents`).
2. `pnpm generate`: the events registry's entry (`crates/api`'s `events.rs`),
   the payload's shape, LuaLS overloads typing the handler, the docs' events
   tables.
3. Where it happens. **An event the server raises** (most are): on `nf`,
   give it `raised` (`types.ts`'s `Raised`): `first`, the handle it's heard on
   before `nf` (its class, its event there and the payload field holding it:
   `playerEvent`, `entityEvent` and `worldEvent` in `spec/gameEvents.ts`, and
   `byPlayer`/`inWorld` in `spec/events.ts`, fill it in, and build the event on
   the handle and on `nf` together); `watched` if the server raises it all the
   time; `lazy`, payload fields costly to work out; `bounds` for a writable
   number. `pnpm generate` (`scripts/emit/raised.ts`) then writes the sink
   method (`GameEvents.<name>(GameEvent.<Payload>)`), its payload in the
   platform's types, the runtime's dispatch
   (`crates/runtime/src/generated/game_event_dispatch.rs`: handles, path,
   listening check, writable fields back within bounds), the FFM proxy and, if
   watched, its `WatchedEvent`; it refuses a payload type the platform has
   no counterpart for (`PLATFORM_HANDLES`, `PLATFORM_VALUES`), a writable field
   with no way back, a lazy writable field or a `first` that doesn't fit.
   What's left by hand is **one mapping** in `PaperGameEvents`: a handler that
   builds the payload from the Paper event, calls the sink and applies what
   came back (`@Watched(WatchedEvent.X, …)` in place of `@EventHandler` for a
   watched one; the plugin won't start without it). The fake raises it through
   `platform.raise` if a fake action causes it. Only an event the runtime's
   services act on too (a project item hears it first, a death reaches the
   services) is hand-written: a `PlatformEvents` method (`PlatformEvents.kt`,
   extracted into the spec), the runtime's `ServerEvents` (`server.rs`), and
   `PaperEvents`. **An event the runtime raises itself** (a tick, a centity
   click): build the payload and emit it through the room's `Scripts`; a
   writable field is read back after the handlers return. Check whether anyone
   listens first if building the payload costs.
4. A runtime test with real Luau against a fake platform
   (`crates/runtime/tests/`).

**A command argument type**: add it to `ARGUMENT_TYPES` in
`spec/commands.ts` (and its line in the `type` field's doc), `pnpm generate`,
which writes `ArgumentType` for the platform (Kotlin and Rust: its Lua name
and its `ArgumentReading`, what the server parses it as), then the runtime's
handling of it (`crates/runtime/src/commands.rs`: the handler's value, how a
`default` of it is read, suggestions for a `names` type), each adapter's
Brigadier type and how it reads the value back (`PaperCommands.type` and
`value`), and `FakeBrigadier.value` for the fake.

**A surface** (scripts attached to a new kind of resource): a `Surface` in
`packages/api/src/spec/index.ts` (its `this`), the runtime's owner of its scopes (`crates/runtime/src/scripts.rs`,
`modules.rs`: label, file, the class `this` is), reload of its resource (hot-reload skill), and the first line
its new scripts start with (format's `Templates.script`), which is how
lua-language-server knows what `this` is there.

**A namespace under `nf`**: a class named `nf.<name>` with `methods: false` in
`spec/nf.ts`, added to `namespaces` there (which makes it a field of `nf`),
`pnpm generate` (an `Nf<Name>Api` trait and its accessor on `LuaApi`), and an
implementation in `crates/runtime`, returned by the accessor on `Api`
(`crates/runtime/src/lib.rs`).

**A value type** (like `Vec3`): `values` in the spec (`spec/values.ts`:
fields, methods, operators, and its `library` global if it has one), its entry
in `RUNTIME_TYPES` (`bindings.ts`: its Rust type and codec), the codec and the
value itself in `crates/api` (`codec.rs`, `value_types.rs`: its metatable, its
methods, how a script's own table is read), and a tag in the JSON codec
(`crates/luau/src/json.rs`'s `Tags`, `crates/api`'s `SavedHandles`).

**A handle class**: spec class with `methods: true` and a `handle` (its key
values, at most two in all), `pnpm generate` (its handle struct and trait), and
an implementation in `crates/runtime`, returned by the accessor on `Api`.

**A handle class that extends another** (`Mob` extends `Living`, which extends
`Entity`; `Player` extends `Living`, and `Bot` extends `Player`; `Mannequin` extends `Living`; `DroppedItem` extends `Entity`;
`CustomBlock` extends `Block`): `extends` names it, to any depth, and it has
no `handle` (it's keyed as the top of its chain is). It has every method and
event up its chain, its own replacing those of the same name (`Player.name`
replaces `Entity.name`; `Player`'s `death` replaces `Living`'s). **A method
goes on the class it applies to**, so no class has one that can only answer
`nil` (a health getter isn't an `Entity`'s; `spec.test.ts` refuses the old
"for an entity that isn't a mob" docs). What the generator does with it:

- Rust: a handle is one userdata type for every class (`HandleData`: its class and its root's key), one per thing
  (a weak registry), so `a == b` holds for two handles to it whatever their class; its class can become more
  specific (`Runtime::refine`: an `Entity` handed out while its mob was unloaded works as the `Mob` it is). Its
  methods are its class's and its chain's (built once), and a method's trait is the class that declares it
  (`LivingApi`), so the runtime implements it once. `From` conversions run up the chain (`handles.rs`).
- LuaLS writes `---@class Mob: Living`; a class with events of its own gets
  `on`/`once` overloads for every event it has, inherited ones included, and a
  `Mob.Event` alias of their names. The docs say what it is a kind of and
  list its kinds. `classFunctions(spec, cls)` and `classEvents(spec, cls)`
  list everything a class has. The conformance test holds each method table to
  own + everything up the chain.
- A class with events of its own declares `on`/`once`
  (`eventFunctions('Mob', ...)`), and the runtime's `on`/`once` (`scripts.rs`) serve every evented class.

## Blocks

`ProjectBlock` is the project item's counterpart: one handle per `blocks/<id>/` (the surface's `this`, key
`id`), whose events (`place`, `break`, `click`, `tick`) fire for every placed block of it. `CustomBlock
extends Block` is one placed (keyed as its position is): `Block:custom()` answers it (`LuaHandle.Block`
is `refine`d to it by `RuntimeApi.refine` when the project has a block there), its `id()`/`project()`
answer nil once it isn't one, and `kind()`/`state()` are the note block it's held as. The `break` event
shares `BlockBreakEvent` with the world's and `nf`'s, as an item's `break_block` does, so one event goes
along the block's, the world's and `nf`'s handlers.

## Advancements

The player's advancement functions (`grant_advancement`, `revoke_advancement`,
`has_advancement`, `advancement_progress`) take **project ids**: bare is the
project's own (`"treasure_hunter"`), `ns:id` a package's exported one,
`minecraft:` the game's; `grant`/`revoke` take an optional criterion name.
The id is resolved by the advancements service (named as the calling package names it, as for items),
so an unknown one is a script error, not a silent false.

**Settings.** `nf.config(name)` (spec `nf.ts`, `crates/runtime/src/settings.rs`) gives the caller's
own package's setting, typed, and an unknown name is an error (it's a typo, not
absence). It also records that the scope read it, which is what `OwnerSettings`
restarts it by; a script that listens for `setting_changed` (events.ts: `setting`,
`value`, `previous`, delivered only to that package's scripts) is told instead.

## Gotchas

- `nf` is built per scope; don't store functions from it in another scope's
  tables and expect the registrations to belong to that other scope.
- A handler's error never disables its scope (only a failing body does): it's
  reported (rate-limited) by `Scripts`, and the subscription is cancelled after `MAX_ERRORS` in a row.
- `packages/api/src/spec.test.ts` (vitest) checks the spec itself: unique names,
  every type parses and names only declared types, every handle class says
  what identifies it, everything documented. `scripts/emit/bindings.test.ts`
  covers the binding model (its decisions on small made-up specs, not the emitted text) and a few emitted lines
  (version gates); `crates/api/tests/bindings.rs` runs the generated glue end to end on a fake (arguments, callers,
  mistakes, shapes, handles, unported classes, gates), and `crates/runtime/tests/` the implementations.
- **What scripts do all the time is golden** (`crates/runtime/tests/data/scenario-costs.tsv`, in steps; the
  plugin-sandbox skill's "The cost golden"). A namespace under `nf` is made when a script first reaches it
  (`bind.rs`' `namespace`), so a scope made for every centity spawned costs nothing it doesn't use.
- **The LuaLS stubs are checked by running LuaLS.** `scripts/emit/luals.check.test.ts`
  runs `lua-language-server --check` over `examples/showcase` and
  `scripts/emit/luals-probe/` (events on every class, `Vec3` maths both ways,
  tasks, commands, data, worlds and blocks, entities and players, inventories,
  boss bars and sidebars) and every spec `example` (each in the kind of
  script its class belongs to, with the free names it uses, `player`,
  `world`, `event`, declared in `FREE_NAMES`) with freshly generated stubs and
  the example's `.luarc.json`, expects zero diagnostics, and checks a few
  mistakes are still caught. An example that names something new from the
  code around it needs an entry in `FREE_NAMES`. It runs the LuaLS the editor
  ships (`node tools/luals.mjs` fetches it, and `pnpm test` does that first),
  or `LUA_LANGUAGE_SERVER=/path/to/bin/lua-language-server`, or one on the
  PATH, and skips only when there's none. LuaLS quirks the generator works
  round: an overload of a `:` method needs `self` first, or LuaLS never
  matches it (the handler's `event` goes untyped); an overload identical to
  the signature is ambiguous, so a class with one event types its handler in
  the signature instead; custom events get a stub-only `CustomEvent` (any
  field is `any`); `nf.wait_for` gets an overload per event each evented
  class declares (`World`, `Entity`, `Living`, `Mob`, `Player`, …; a subclass
  matches its parents' overloads), whatever its `handle` parameter's union says.
  A probe for a new class or event goes in `luals-probe/`.

```sh
pnpm generate
pnpm --filter @netherforge/api test lint
cargo test -p netherforge-api -p netherforge-runtime
LUA_LANGUAGE_SERVER=/path/to/bin/lua-language-server pnpm --filter @netherforge/api exec vitest run luals
```
