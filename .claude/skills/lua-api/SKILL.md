---
name: lua-api
description: Adding or changing anything a NetherForge Lua script can call - the spec in packages/api/ comes first, then pnpm generate (api.json, LuaLS stubs, docs/reference, and the runtime's bindings - bindings.lua, the Kotlin LuaApi interfaces, primitives, the event registry and typed payloads), then implementing the generated interface (or the prelude, for impl lua), then the conformance test. The type grammar, the binding model, events on classes, and the API's naming, error and nil-vs-error conventions. Read before touching packages/api/, the prelude (lua/prelude.lua and lua/prelude/*.lua), the runtime's api/ (implementations) or binding/ (generated bindings and their support) packages, or src/generated/.
---

# The Lua API

## One source, five consumers

`packages/api/src/spec/` (TypeScript, typed by `packages/api/src/types.ts`) declares every
global, namespace, handle class, event (on the class it's about) and payload shape.

| Consumer                    | How it gets the spec                                                     |
| --------------------------- | ------------------------------------------------------------------------ |
| the editor's Lua features   | its lua-language-server reads `nf.lua` (see editor-lua)                  |
| LuaLS users (VS Code, nvim) | `packages/api/generated/luals/nf.lua` (`---@meta` stubs), `surfaces/`    |
| the docs                    | `docs/reference/*.md`                                                    |
| the plugin's bindings       | `apps/plugin/runtime/src/generated/` (`bindings.lua` and `binding/*.kt`) |
| the conformance test        | `packages/api/generated/api.json`                                        |

**Terrain scripts have an API of their own** (W5.6): `packages/api/src/terrain.ts` (`terrainApi`:
`Terrain`, `Noise`, `Chunk`, the `TerrainStages` a script returns, what the sandbox removes). It isn't `nf`'s and
nothing of the runtime is generated from it: the script runs in format's own Lua states (`TerrainScriptGlue`, see
project-format's "Lua stages"), on the server's chunk threads and in the editor's preview. `pnpm generate` writes its
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

- **The generated bindings** (`scripts/emit/bindings.ts` works out one codec
  per node of each type, `lua.ts` and `kotlin.ts` write the two halves). The
  Kotlin half is package `dev.netherforge.plugin.binding`
  (`src/generated/kotlin/.../binding/`), beside the hand-written support it
  calls in `src/main/kotlin/.../binding/`: `Marshal.kt` (`Marshal`, `Caller`),
  `EventSession.kt`, `Locations.kt` (`LuaLocation`, `LuaPlace`,
  `BlockPosition`), `Handles.kt` (the handle for an entity, a block, an
  inventory or the console) and `Messages.kt` (`notInProject`,
  `luaSpelling`). Services use `binding`, never `api`. For every function
  not marked `impl: "lua"`:
  - `bindings.lua`: the method on its class (or the function on `nf` or a
    namespace under it), the `:` check (inline: the handle's key in the
    handle table, `ids[self]`, and its metatable's name in the class's
    `<Class>_is` set, the class and every class below it; `self_of` is
    called only to raise the error), and the call
    `prim["Class.name"](...)` with each argument as the one Lua value it is. Kotlin reads and checks them. The one
    exception is the `Vec3` fast path: a `Vec3` (or `Vec3?`) parameter is
    checked by `want_vec3` and crosses as its three numbers, and a `Vec3`
    return comes back as three numbers the binding rebuilds itself, since
    transforms are set and read every tick (`BindingBenchmark` measures it);
  - `LuaApi.kt`: one interface per class (`CentityApi`, `MobApi`, `NfApi`,
    `NfServerApi`, ...), each with only the class's own functions. A handle
    method gets `self: LuaHandle.<Class>` (`call.self(1)`, found by its key); a
    namespace function gets `caller: Caller` (its scope, looked up only when
    asked for);
  - `LuaPrimitives.kt`: reads each argument with its type's codec
    (`call.arg(index, name, codec)`), calls the interface, pushes the results
    with theirs. The only place Lua values meet Kotlin types. A codec that's
    built (`LuaOptional(LuaCodecs.VEC3)`) is made once per file, not per call;
  - `LuaShapes.kt`: a data class per shape that crosses (an option table, a
    returned record like `RaycastHit`, an event's payload, and every shape
    inside them), each with its `Codec`: read strictly, pushed as a table of
    the fields that are set. A payload's writable fields are `var`s, read back
    by their codecs (`write`);
  - `LuaUnions.kt`: a sealed interface per union that crosses and isn't only
    string literals, a case per member holding its value
    (`is LocationOrVec3.Vec3 -> target.value`), and `LuaUnions.<Name>` for a
    union of handle classes only, which Kotlin sees as the `LuaHandle` it is;
  - `LuaHandle.kt`: a class per handle class holding what identifies it (the
    `key` of the `handle` at the top of its chain, `keyValues`), a Kotlin
    subclass for each class below another (`Mob : Living : Entity`, equal
    across the chain by key), `PARENTS`, `build` (for `new.<Class>` in the
    prelude), and each class's `Codec`;
  - `Events.kt`: the registry (`Events.NODE_CLICK`, `Events.NF_PLAYER_JOIN`:
    an `EventType<Payload>` with its owner class, its payload's codec,
    `cancellable`, `bubbles`, `writable`, `local`, `since`), so payload field
    names can't drift, and `Events.of(luaClass, name)`: the event a name means
    on a handle of that class, inherited ones included (`Mob`'s `death` is
    `LIVING_DEATH`).
  - `bindings.lua` also carries the Lua side of the registry (`events`,
    `custom_events`) that `:on` checks names, options and writable fields against.
- **The binding model.** A `LuaCodec<T>` (the runtime's `lua/LuaCodec.kt`) is
  how one node of the type grammar crosses, the same way wherever the type
  appears (a parameter, a return, an option table's field, a payload, a
  writable field): `accepts` (whether a value is this type's kind of Lua
  value, for a union picking a member), `read` (from a stack index, named
  `where` in a mistake: `options.near`, `lines[2]`, `event.drops[1]`) and
  `push`. The generator composes the runtime's: `LuaCodecs` (`STRING`,
  `NUMBER`, `INTEGER`, `BOOLEAN`, `FUNCTION`, `VEC3`, `LOCATION`, `ITEM`,
  `ITEM_MATCH`, `TEXT`; `VALUE` and `TABLE` for `any` and `table` taken, a `LuaValue`
  read while the call lasts; `DYNAMIC` for them given back, pushed by
  `LuaHost.pushValue`), `LuaChoice`, `LuaOptional`, `LuaList`, `LuaMap`,
  `LuaUnion` (members tried in the type's order, so a handle class put before
  the class it extends wins for its handles), `LuaHandleUnion`, `HandleCodec`
  and `ShapeCodec`. Kotlin tells what a value is by its genuine metatable: the
  prelude's `kinds` (metatable → the number of its kind: a handle class,
  `Vec3`, `Location`) and a handle's key (`ids`), which `LuaHost.kindAt`,
  `handleAt`, `vec3At` and `locationAt` read. No script can reach those
  metatables, so none can fake one. **A handle is one opaque key**: an
  integer into `LuaHost.handles` (a `HandleTable`), whose entry holds the
  `LuaHandle` and the most specific class the runtime has said it is. Lua
  never sees what a handle is made of, and nothing else is kept with it
  (whatever else it answers, a player's name, is looked up each time). A
  handle pushed back is its entry's table from the prelude's `cache` (or a new
  one, `LuaHost.pushHandle`), so two of the same thing are the same table; a
  `HandleCodec` taking a class above the value's (`Mob` where an `Entity` was
  handed out) asks the runtime again first (`refineAt`). A mistake is `bad argument 'where' (X expected, got Y)`,
  located at the line that called the API function (`luaL_where`, as
  `error(message, 2)` would). A type with no codec (a value type missing from
  `RUNTIME_TYPES`, a union whose members a value couldn't be told apart by, a
  table keyed by anything but strings or whole numbers, a writable field
  that's a function) is an error at `pnpm generate`. A shape that holds
  itself (`Subcommand.subcommands`) is fine: the emitter names its own codec
  lazily (`LuaLazy`), since it can't name a codec still being built.
- **Type mapping**: `string` → `String`, `number` → `Double`, `integer` → `Long`
  (Lua's integers are 64-bit; `2.0` reads as `2`), `boolean` → `Boolean`, a
  handle class → `LuaHandle.<Class>`, `Vec3` → format's `Vec3`, `Location` →
  `LuaLocation` (its `world` a `World` handle), `Text` → `String` (its glyph
  tags in the server's terms, `Marshal.text`), `Item` → `ItemData` and
  `ItemMatch` → the runtime's `ItemMatch` (both read by the runtime through
  `Marshal`, against the server and the project), a function → `LuaFunction`
  (kept in the registry until released), `any` → `LuaValue` taken (`LuaValue?`
  as a field, which may be left out) and `Any?` given back, `table` the same
  but checked to be a table (a `LuaRef` given back is the very table, like a
  `data()` table), `T?` → `T?`, `T[]` → `List<T>`, `table<K, V>` → `Map<K, V>`
  (keys strings or whole numbers), a union of string literals → `String`
  (checked against its choices), a union of handle classes → `LuaHandle`
  (whichever it is: `when` on it), any other union → its sealed interface
  (`Location|Vec3` → `LocationOrVec3`, which the runtime turns into a
  `LuaPlace` with `.place`), a shape → its data class. Several returns are a
  `Pair`/`Triple`/`Quadruple`, null for "a single nil".
- **The runtime's implementations** live in `apps/plugin/runtime/.../api/`,
  every one whatever its area (`RuntimeApi` holds them and implements
  `Marshal`; `NfImpl`, `CentityImpl`, `ScreenImpls`, `WasmImpls`,
  `NfTestImpl`, ...): a file per class, or `<Area>Impls.kt` for an area's
  few. They call the services; no service imports them. A missing or
  mistyped implementation doesn't compile.
- **The prelude** is a set of modules, `resources/.../lua/prelude/<name>.lua`
  (the entry `prelude.lua` loads them; see plugin-sandbox, "The prelude's
  modules"), each a chunk that `require`s what it uses and returns what it
  exports. It keeps the core: `guard` (the hook: budget, time, memory; error
  locations; `invoke`), `sandbox`, `handles` (`class`, `self_of`, the cache),
  `values` (`Vec3`, `Location`, the per-scope `vec3` global, `want_vec3`
  for the `Vec3` fast path), `json` (the one Lua↔JSON codec), `events` (the
  **event core**), `tasks`, `scopes` (`require`, a scope's globals), `census`,
  `host` (what Kotlin calls), and `api`, which runs the generated bindings.
  The **hand-written functions** are the bodies in `handwritten` (and
  `utilities`, `values`): those the spec marks `impl: "lua"` (`Entity`'s
  `is_player`, `is_living` and `is_mob`, a class check that asks the runtime
  when the handle's class is one above, `World.location`, `File.lines`,
  `nf.after`/`every`, `nf.task` and the waits (`nf.wait`, `wait_until`,
  `wait_for`, `Dialog.ask`), `Task`'s and `Subscription`'s methods,
  `Mob.add_goal`, `nf.instructions_left`, `nf.random.new` and `Random`'s
  methods (xoshiro256** in Lua's 64-bit integers, its state in a weak table by
  the handle, so a dropped generator is collected; `UtilitiesTest` holds it to
  a Kotlin reference), `nf.math`, every class's `on`/`once`/`emit`, and the
  methods of `Vec3` and `Location`). They are tables keyed by class (and by
  namespace path for `nf` and the namespaces under it), and the conformance
  test holds them to exactly the spec's `impl: "lua"` set.
  **A hand-written function is two functions.** What scripts call is a
  generated wrapper in `bindings.lua`: it checks `self` (`self_of`) and each
  argument from its declared type (`want`, `want_integer`, `want_handle`,
  `vector_arg`, `check_shape` for a table of a spec shape, `choice.want` for a
  union of string literals, against a generated lookup set), with the same
  messages and at the same line as a Kotlin-backed function, then **tail-calls**
  the body with the checked values (`return hand.Mob.add_goal(self, id,
definition)`). So a body holds only logic: it takes `self` and the arguments
  as typed, a namespace function's gets the calling scope first (as a Kotlin
  implementation does), and an error it raises at level 2 is at the script's
  line (the wrapper's frame is gone). A type the grammar can't check alone (a
  union of classes, `any`, `...`) is left to the body (`nf.wait_for`'s
  handle). A parameter can't be named after the wrapper's own locals
  (`hand`, `want`, `scope`, ...): generate says so.
  A handle's key values come from `keys.<Class>(handle)` (generated from the
  class's `handle`: `keys.Task(task)` is its id). A few primitives only the
  core calls (`resolve`, `source`, `log`, `timer`, `timer.cancel`,
  `timer.active`, `async.cancel`, `goals.add`, `events.*`, `events.check`
  among them, which reads a handler's value for a writable field as its codec would) are in
  `RuntimeApi.corePrimitives`; `LuaHost` adds its own `handles.new` (a handle
  the prelude makes: a `Task`, a world named in saved data), `handles.key`
  (its key values, for saving one, `tostring` and the key accessors) and
  `handles.refine`. A hand-written function's own string and table work calls
  the library's originals (`raw.match(s, p)`, `format(...)`, `raw.sort(t)`,
  from `std`), never `s:match(p)` or `table.sort`: those are the sandbox's
  caps, for scripts (plugin-sandbox skill, "The sandbox"). The tables a
  hand-written function takes are checked from the spec: the generated
  `schema.lua` (the data the prelude reads: `events`, `custom_events`,
  `event_functions`, `shapes`, `saved_handles`) has `shapes`, every shape
  reachable from an `impl: "lua"` function's parameters (`DialogOpenOptions`,
  `EventOptions`, `GoalDefinition`) with each field's kind and the required
  ones, and `check_shape` holds a table to it (an unknown key is an error).
- **LuaLS checks the prelude.** `pnpm lint` runs the pinned lua-language-server
  (`tools/luals.mjs`, the editor's own binary and version) over
  `apps/plugin/runtime/src` (`tools/lint-prelude.mjs`): each module's
  `require`s resolve as they do at runtime, `src/luals/input.lua` types what
  the Kotlin host hands the entry, and every diagnostic down to an unused local
  fails the check. Annotate what a new module exports (`---@param`, `---@return`,
  `---@class`): a half-annotated signature is a diagnostic too.
- **Events.** Subscriptions live in the prelude's event core, filed under the
  target handle (the table itself, kept alive by it) and the scope that
  registered them; they go when either does (`host.drop_env`,
  `Scripts.dropTarget`, which drops the parts with the whole). Kotlin raises an event
  with `scripts.emit(Events.X, target, Payload(...))`, or along a bubbling
  path with `scripts.emit(listOf(Events.NODE_CLICK to node, ..., Events.NF_CENTITY_CLICK to null), payload)`
  (a stage whose target's class has its own event of that name is left out:
  `LIVING_DEATH` isn't a `Player`'s, whose `death` is `player_death`);
  the core builds one event object, runs each stage's handlers (each in its
  scope's budget frame) until one calls `stop()`, and hands back whether it's
  cancelled and the writable fields, which `emit` writes back into the
  payload. `scripts.listening(target, event)` says whether anyone listens, so
  work nobody listens to (ticking a centity, a physics contact) is skipped,
  and `scripts.watch(events...) { listening -> }` hears whenever anything
  starts or stops listening to any of them on any target (how a `watched`
  event, `player_move` or `player_chat`, turns the adapter's listener on and
  off: `GameEventDispatch.WATCHED`, `Platform.watch`).
  A handle's `:on` belongs to the scope whose code is running (the frame's
  scope); `nf.on` to the scope whose `nf` it is.
- **A name a script passes** (an item, menu, dialog, centity, particle
  effect, recipe, glyph, skin, sound) is resolved in its own package and
  checked against what that package may name: an implementation runs it
  through `session.names` (`resource(kind, …)`, `entry(refKind, …)`) before
  looking it up, and **a name it hands back** through `names.spell`
  (bare for the reader's own package, `ns:id` otherwise). Item tables and
  `Text` do this in their codecs. A look (an item model, glyph, skin,
  tooltip, equipment look or sound) goes through `names.look` or `entry`,
  never `references().check`: any loaded package's look may be shown by its
  full name ("Looks are values"). See plugin-runtime, "Packages in a
  session", and `docs/reference/packages.md` for the rules scripts see.
- **`Text`** is the spec's one type alias (`spec/values.ts` `aliases`,
  `ApiSpec.aliases`): MiniMessage a player reads, a `string` to Lua and
  LuaLS (`---@alias Text string`), crossed by `LuaCodecs.TEXT`, which writes
  its glyph tags in the calling package's terms. Any parameter, field or
  return that's MiniMessage is `Text`, never `string`; a new alias needs a
  `RUNTIME_TYPES` entry (`bindings.ts`) or generate fails.

- **A test-only namespace** (`testOnly: true` on a namespace class, `nf.test`
  in `spec/test.ts`): generated like any other (interface `NfTestApi`, bindings,
  stubs, docs), but `schema.lua`'s `test_only` list makes the prelude remove it
  from every scope's `nf` unless the runtime runs under a test harness (the
  script test runner, plugin-runtime's "Testing"). The conformance test leaves
  it out of "exactly the declared functions" and checks a server has none.
  `nf.test.raise`'s `event` parameter is a union of every non-local `nf` event,
  and `emit/raisable.ts` writes `binding/RaisableEvents.kt` from the same registry
  (an `EventType`, plus the one on the handle in `raised.first`, and a lambda
  finding that handle in the payload): a new event is raisable with no list to
  keep. Only `nf` events; a handle's own events (a node's click) aren't.

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
  names a file field (`maxStackSize`) goes through `luaSpelling`
  (`api/Definitions.kt`) on its way to Lua.
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
  implementation `keep()`s as a `LuaRef` (the very value, in the registry until
  `host.unref`) to hold past the call and hands back through `pushValue`.
- **JSON.** The prelude's one Lua↔JSON codec (the `json` module:
  `host.json_encode` and `json_decode`, reached through `LuaHost.encodeData`,
  `json`, `keepData` and every `JsonElement` `pushValue` pushes) is what
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
  with `saveable: true` on the class at the top of its chain (the generator writes `saved_handles` into `bindings.lua`). What can't be written is a problem at its
  key path saying what can't be done (`data.fn: a function can't be saved`,
  `value.fn: a function can't be written as JSON`): logged and skipped for a
  saved table (`data/ScriptData.kt`), an error anywhere else. Anything new that
  turns script values into JSON or back (`block:data()`, a database row) uses
  the same codec.
- **A typo is an error; a condition is `nil`/`false`.** An unknown event,
  centity id or animation is an error at the script's line (a
  `LuaApiException` from Kotlin, or `want()` in Lua for argument types).
  A player who logged off, a removed centity, a path outside the data folder,
  a block state the server doesn't have: `nil` or `false`, never an error, so
  code holding a handle from earlier needn't guard every call.
- Argument _type_ errors come from the codecs, in Kotlin, located at the
  caller's line (`LuaCall.arg`); a hand-written function raises its own in Lua
  (`want`, `want_opt`) with a level that points at the caller. Kotlin can
  throw for everything else; the error handler finds the script's frame.
- Every position, offset, rotation, velocity, direction and scale is a
  `Vec3`, and every getter returns one value (`translation()` → `Vec3?`, one
  nil check). A place to put something is `Location|Vec3`, a bare vector
  meaning "this handle's world". Methods that take or return a position say
  which space it's in (world, the centity's, a node's parent's); physics is
  always world space. Rotations are degrees; durations are ticks (20 a second), and
  `nf.server.unix_time()` is the wall clock, for timestamps only.
- Text players see is MiniMessage, typed `Text`.
- **Version gates**: a function, event or option-table field
  newer than the oldest supported Minecraft has `since` in the spec. The docs
  and stubs say so, `VersionGates.kt` lists it, and the runtime's `gated` core
  primitive hands the prelude every gate newer than the server: it replaces
  such a function with one that errors "`X` needs Minecraft V (this server
  runs W)", and `:on` and `check_shape` refuse such an event or field the
  same way, as does a shape's codec in Kotlin (`LuaHost.gates`, from
  `RuntimeApi.gates`). Nothing is gated while the target is the oldest
  supported version.
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
  `this:slot(index)`, `this:button(key)`. The conformance test checks each
  surface's globals and that `this` is the class it declares. LuaLS can't
  tell surfaces apart (it ignores nested `.luarc.json`), so `nf.lua` types
  `this` as the union `Centity|Menu|Dialog|ProjectItem`, `luals/surfaces/<surface>.lua`
  types it per surface, and new scripts start with
  `local this = this --[[@as Centity]]` (format's `Templates.script`).
- Things a script registers belong to its scope and go when it unloads; a
  handler on a handle also goes when the handle's thing does. So do things a
  script _makes_ (a playing particle effect, a playing cutscene (whose player is put back), a menu template, a dialog from
  `nf.dialogs.create`): the runtime files each under its scope and ends it in
  its service's `scopeReleased` (see plugin-runtime, "The session and its services"), after the scope's own subscriptions are
  gone (so only other scopes' handlers hear an effect's `end`).
- **Made in Lua, held to the files' rules.** `nf.menus.create` and
  `nf.dialogs.create` take a table in the file's shape in Lua spelling
  (snake_case keys, `Item` tables), turned into the format's model and run
  through the same strict parser and validator as `menu.json`/`dialog.json`
  (`api/Definitions.kt`); a problem's JSON path comes back in Lua spelling
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
  minecraft-versions), `waits` (task-only: hand-written in the prelude's `running_task`,
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
  one: `async.begin(what, callback)` (the prelude's `tasks` module) checks the
  callback or, without one, that a task is running (the error at the
  script's line, before anything starts) and makes its waker; the primitive is
  called straight from the API function (so its argument errors point at the
  script), and Kotlin's interface returns a `CompletionStage<T>`, which the
  primitive hands to `Marshal.await` with the waker (`call.keep`), answering
  the wait's id; in a task, `async.wait` suspends until the waker resumes it
  with `value, err`, and ending the task cancels the wait (`async.cancel`).
  The callback form returns nothing. Delivery is the runtime's `AsyncWork`
  (plugin-runtime, "Threading"); a failure the script should read is a
  `WorkFailed`. A handle method's wait belongs to the scope whose code called
  it (`async.begin` hands that scope back, and the binding passes it to the
  primitive); a namespace function's to the scope whose `nf` it is. The callback
  is always the last argument, so a call without options passes `nil` for them
  (`world:locate_biome("minecraft:desert", nil, function(position, err) ... end)`).
  A parameter can't take a name the binding's own locals use (`waker`, `task`,
  `token`, `scope`, `wait_id`, `value`, `err`) or the chunk's (`prim`, `want`, ...):
  generate says so, for every generated function, since a shadowed one is a bug
  (and a lint failure in `bindings.lua`).
  An async function's value can't be optional: a search that finds nothing
  (`World:locate_biome`) is `nil, err` saying so, like any other failure.
- **Handlers are synchronous; tasks wait.** Only code
  running in an `nf.task` may call something that waits (`nf.wait`,
  `wait_until`, `wait_for`, `dialog:ask`); anywhere else it's an error that
  says to start a task. A new waiting function is hand-written in the
  prelude's `tasks` module: `running_task(what)` straight from the API
  function (it's what refuses handlers, foreign coroutines and C callbacks),
  then `sleep` or `wait_events`. A wait that listens to a handle ends the task
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
plugin-persistence skill). `NfImpl.db(caller, name)` checks the name exists and
lists the calling package (`RemoteDatabases.problem`), an error at the line;
the `db` requirement is the generated primitive's, as for `nf.db()`. Its three functions are `asyncFunction`s (`spec/db.ts`), so the
Kotlin interface (`DatabaseApi`) returns `CompletionStage`s: `DatabaseImpl`
validates on the main thread (SQL that isn't one statement, a param SQLite has
no value for: errors at the script's line) and starts the work on the
database's lane; what only the database can say is a `WorkFailed`, the
script's `nil, err`. `params` is a `table` read while the call lasts and
converted to Kotlin values before the work starts (a Lua value must never
cross to the worker). The handle isn't checked against the calling package:
a handle only comes from its package's `nf.db()`, and `PackageNames.calling()`
is the scope running, which for a package's module called by another
package's script is the caller's. An async function in `ConformanceTest`
returns nothing when it's given its callback, so its `returns` aren't
checked there; tests that use it in a task cover those.

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
server name, spelled for the reader by `module()`) are what they make. A call's `args` is a `table`, read in Kotlin
like `Database`'s params so integers stay 64-bit; results are `any[]` (integers and numbers as they are). A program is
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
3. Implement the new interface method in `apps/plugin/runtime/.../api/`
   (or, for `impl: "lua"`, the body in the prelude's `handwritten` module: the
   generated wrapper already checks its arguments).
4. Test the behaviour with a Lua script against the fake platform.
5. `node tools/gradle.mjs :plugin:runtime:test` (conformance included: it calls
   every generated binding once and checks its results' types).

**An event on a class (or on `nf`)**

1. Spec: the class's `events` (name, doc, `payload`, `cancellable`, `bubbles`,
   `writable`, `options`, `local`; `nfEvents` in `events.ts` for `nf`), and the
   payload in `shapes`, extending `Event`. A writable field may be of any type
   but a function. A handler's assignment is checked at its line by the payload
   field's codec (the event core's `__newindex` calls the `events.check`
   primitive with the first stage's owner and event), and after the handlers
   `LuaHost.emit` reads every writable field back with it into the payload, so
   a value changed in place (an item in a list) counts too; one that can't be
   read leaves the field as it was, with a warning in the log. A new
   `player_*` event on `nf` is on `Player` too, without the prefix
   (`playerEvents` in `entities.ts`). A class with events gets `on` and
   `once` from `eventFunctions(...)` (and `emit` with `customEvents`).
2. `pnpm generate`: the `Events.X` entry, the payload data class, LuaLS
   overloads typing the handler, the docs' events tables.
3. Where it happens. **An event the server raises** (most are): on `nf`,
   give it `raised` (`types.ts`'s `Raised`): `first`, the handle it's heard on
   before `nf` (its class, its event there and the payload field holding it:
   `playerEvent`, `entityEvent` and `worldEvent` in `spec/gameEvents.ts`, and
   `byPlayer`/`inWorld` in `spec/events.ts`, fill it in, and build the event on
   the handle and on `nf` together); `watched` if the server raises it all the
   time; `lazy`, payload fields costly to work out; `bounds` for a writable
   number. `pnpm generate` (`scripts/emit/raised.ts`) then writes the sink
   method (`GameEvents.<name>(GameEvent.<Payload>)`), its payload in the
   platform's types, the runtime's dispatch (`GameEventDispatch`: handles,
   path, listening check, writable fields back within bounds) and, if
   watched, its `WatchedEvent`; it refuses a payload type the platform has
   no counterpart for (`PLATFORM_HANDLES`, `PLATFORM_VALUES`), a writable field
   with no way back, a lazy writable field or a `first` that doesn't fit.
   What's left by hand is **one mapping** in `PaperGameEvents`: a handler that
   builds the payload from the Paper event, calls the sink and applies what
   came back (`@Watched(WatchedEvent.X, …)` in place of `@EventHandler` for a
   watched one; the plugin won't start without it). The fake raises it through
   `platform.raise` if a fake action causes it. Only an event the runtime's
   services act on too (a project item hears it first, a death reaches the
   services) is hand-written: a `PlatformEvents` method, `ServerEvents`, and
   `PaperEvents`. **An event the runtime raises itself** (a tick, a centity
   click): build the payload and `scripts.emit(Events.X, target, payload)`.
   A writable field is read back into the payload after `emit` returns;
   check `scripts.listening` first if building the payload costs.
4. A runtime test with real Lua against the fake platform (`EventTest`,
   `ServerEventTest`, `GameEventTest`).

**A command argument type**: add it to `ARGUMENT_TYPES` in
`spec/commands.ts` (and its line in the `type` field's doc), `pnpm generate`,
then its entry in `ArgumentType` in the runtime's `platform/Commands.kt`: its
Lua name and its `ArgumentReading` (what the server parses it as; a new
reading is a new `ArgumentValue` too), or `names` for a word only the runtime
can resolve. Then `ArgumentValues` (`resolve`, the handler's value; `codec`,
how a `default` of it is read; `suggestions` for a `names` type), each
adapter's Brigadier type and how it reads the value back (`PaperCommands.type`
and `value`), and `FakeBrigadier.value` for the fake.
`CommandTest` fails until the spec and the enum agree.

**A surface** (scripts attached to a new kind of resource): a `Surface` in
`packages/api/src/spec/index.ts` (its `this`), a `ScopeOwner` case (label, `file`), its class in
`Scripts.open` and the prelude's `surface_classes` (`host.new_env`), the conformance test's
`withScopes`, reload of its resource (hot-reload skill), and the first line
its new scripts start with (format's `Templates.script`), which is how
lua-language-server knows what `this` is there.

**A namespace under `nf`**: a class named `nf.<name>` with `methods: false` in
`spec/nf.ts`, added to `namespaces` there (which makes it a field of `nf`),
`pnpm generate` (its filler in `namespaces`, an `Nf<Name>Api` interface), an
implementation in `api/NfImpl.kt` and its property on `RuntimeApi`.

**A value type** (like `Vec3`): `values` in the spec (`spec/values.ts`:
fields, methods, operators, and its `library` global if it has one), its entry
in `RUNTIME_TYPES` (`bindings.ts`: its Kotlin type and codec) and the codec in
`LuaCodecs` (reading its array part, pushing through a `host` constructor),
the implementation in the prelude's `values` module (its metatable registered
with `kind(mt, name)`, so Kotlin can tell it; genuine values are told by their
real metatable, `raw_getmetatable`, so a script can't fake one; its methods
are bodies in `values.body`, wrapped by the generated bindings), a tag in the
JSON codec, and `host.values` for the conformance test.

**A handle class**: spec class with `methods: true` and a `handle` (its key
values, at most two in all), `pnpm generate` (constructor, `LuaHandle` class,
interface), an implementation in `api/` and its property on `RuntimeApi`, and a
body in the prelude's `handwritten` module if it has `impl: "lua"` methods.

**A handle class that extends another** (`Mob` extends `Living`, which extends
`Entity`; `Player` extends `Living`, and `Bot` extends `Player`; `Mannequin` extends `Living`; `DroppedItem` extends `Entity`;
`CustomBlock` extends `Block`): `extends` names it, to any depth, and it has
no `handle` (it's keyed as the top of its chain is). It has every method and
event up its chain, its own replacing those of the same name (`Player.name`
replaces `Entity.name`; `Player`'s `death` replaces `Living`'s). **A method
goes on the class it applies to**, so no class has one that can only answer
`nil` (a health getter isn't an `Entity`'s; `spec.test.ts` refuses the old
"for an entity that isn't a mob" docs). What the generator does with it:

- Kotlin: `LuaHandle.Mob` is a subclass of `Living` and `Entity`, equal to
  either by key, so one thing is one entry in the handle table whatever its
  class; the entry holds the most specific class known (`HandleTable.intern`).
  The runtime says which class an entity is when it hands one out
  (`runtime.entityHandle(uuid)`, from `EntityInfo.category`; `livingHandle`,
  `mobHandle` for an event that says so) and when asked again
  (`LuaMarshal.refine`). An inherited method's primitive is the class that
  declares it (`prim["Living.health"]`, `LivingApi`), so Kotlin implements it
  once. `HandleCodec`'s `classes` are the class and everything below it.
- Lua: `class("Mob", "Living")` records the parent; `self_of`,
  `want_handle` and option tables' handle checks accept anything below the
  class (`is_a` walks the chain), and the prelude copies each class's methods
  (generated and hand-written) down the chain, parents first. A class others
  extend has an `__index` that, for a method its class lacks, asks the runtime
  what the handle is now (`handles.refine`) and looks again, so an `Entity`
  handed out while its mob was unloaded works as the `Mob` it is. The event
  registry (`schema.events`) has each class's events with those it
  inherits, each with the class that declares it (`owner`).
- LuaLS writes `---@class Mob: Living`; a class with events of its own gets
  `on`/`once` overloads for every event it has, inherited ones included, and a
  `Mob.Event` alias of their names. The docs say what it is a kind of and
  list its kinds. `classFunctions(spec, cls)` and `classEvents(spec, cls)`
  list everything a class has. The conformance test holds each method table to
  own + everything up the chain.
- A class with events of its own declares `on`/`once`
  (`eventFunctions('Mob', ...)`), and `schema.event_functions` lists it, so the
  prelude's `handwritten` gets the shared `on`/`once` bodies for it.

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
The id is resolved by `Advancements` (the `names` resolver, as for items),
so an unknown one is a script error, not a silent false.

**Settings.** `nf.config(name)` (spec `nf.ts`, `NfImpl.kt`) gives the caller's
own package's setting, typed, and an unknown name is an error (it's a typo, not
absence). It also records that the scope read it, which is what `OwnerSettings`
restarts it by; a script that listens for `setting_changed` (events.ts: `setting`,
`value`, `previous`, delivered only to that package's scripts) is told instead.

## Gotchas

- Never name a Kotlin `Lua.*` extension after a luajava member
  (`ref`, `push`, `type`, …): members win silently, which is why the
  generated reads are `argString`. See plugin-runtime.
- `nf` is built per scope; don't store functions from it in another scope's
  tables and expect the registrations to belong to that other scope.
- A handler's error never disables its scope (only a failing body does): it's
  reported through `Scripts.onError`, rate-limited in `ScriptReports`, and
  the subscription is cancelled after `Scripts.MAX_ERRORS` in a row.
- The event core's error levels count frames: `on`/`once`/`emit` call
  `subscribe`/`emit_custom` as a statement, never a tail call, so `level 3`
  is the script's line.
- `packages/api/src/spec.test.ts` (vitest) checks the spec itself: unique names,
  every type parses and names only declared types, every handle class says
  what identifies it, everything documented. `scripts/emit/bindings.test.ts`
  covers the binding model (its decisions on small made-up specs, not the emitted text); the runtime's `CodecTest` holds the codecs'
  messages, `ConformanceTest` round-trips a sample of every shape that
  crosses (every field set to a value of its declared type) through its codec,
  and `HandleTest` holds the handle table to its promises: a handle Lua lets
  go of leaves it (`LuaHost.collectGarbage`, `sweepHandles`), one handed out
  while its entity was unloaded becomes its class, and a player's name is
  looked up. A test that needs a handle Lua no longer holds collects first:
  one still held keeps what its entry knew.
- **What calls cost a script is golden** (`function-costs.tsv`, `scenario-costs.tsv`; testing
  skill, "Goldens"). Every check a binding makes is inline, its checker called only to raise
  the error (`type_of(x) ~= "number"`, a handle's set, a choice set's `has`, `genuine_mt(v) ~=
Vec3_mt`), and a namespace under `nf` is filled when a script first reaches it
  (`namespaces[path]`, the prelude's `scopes.lazy`): a scope is made for every centity spawned.
  JSON a script hands over is read in Kotlin (`PlainJson`, exactly as the codec would
  write it, floats through `CodecNumbers`), the codec only for what Kotlin can't say the same.
- `BindingBenchmark` times the hot bindings (a node's transform, a handle back,
  a handle and a union as arguments, an option table); run it before and after
  changing the codecs or the prelude's handles (it's skipped unless
  `NETHERFORGE_BENCH=1`).
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
node tools/gradle.mjs :plugin:runtime:test --tests '*ConformanceTest*'
LUA_LANGUAGE_SERVER=/path/to/bin/lua-language-server pnpm --filter @netherforge/api exec vitest run luals
```
