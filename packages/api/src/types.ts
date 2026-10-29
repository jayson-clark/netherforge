/**
 * The shape of the Lua API spec. Everything a script can see is declared in
 * `src/spec/` with these types; `pnpm generate` turns that into the LuaLS
 * stubs, the API reference docs and `generated/api.json` (which the plugin's
 * conformance test reads), and the editor imports it for completions.
 */

/**
 * A Lua type as written in LuaLS annotations: `string`, `number`, `boolean`, `Centity`,
 * `Player?`, `string[]`, `table<string, integer>`, `"left"|"right"`,
 * `fun(event: ClickEvent)`. Written as a string for authoring; `parseLuaType`
 * (`luaType.ts`) holds the grammar, and every name in it must be a primitive or a
 * class, shape or alias the spec declares.
 */
export type LuaType = string

/**
 * A feature in format's feature table (`FEATURES`, from `game/Features.kt`): something
 * that arrived after the oldest supported Minecraft. The table says which version.
 */
export type FeatureId = string

/** Project names a string parameter takes, so editors can complete them: `nf.centities.spawn("` → centity ids. */
export type NameKind =
  | 'centity'
  | 'menu'
  | 'dialog'
  | 'particle_effect'
  | 'cutscene'
  | 'item'
  | 'block'
  | 'recipe'
  | 'loot_table'
  | 'buff'
  | 'advancement'
  | 'animation'
  | 'node'
  | 'button'
  | 'glyph'
  | 'skin'
  | 'map'
  | 'structure'
  | 'setting'
  | 'screen'
  | 'video'
  | 'server'
  | 'channel'
  | 'network_map'

export interface Param {
  /** Its name; `...` (only as the last parameter) for any number of extra arguments, like `nf.task`'s. */
  name: string
  type: LuaType
  doc: string
  optional?: boolean
  /** The string names one of the project's things of this kind. */
  names?: NameKind
}

export interface Fn {
  name: string
  doc: string
  params: Param[]
  /** Multiple returns in order; empty for none. */
  returns: { type: LuaType; doc?: string }[]
  /** A short Lua example shown in docs and hover. */
  example?: string
  /**
   * Where it's implemented. `"kotlin"` (the default): `pnpm generate` writes its
   * binding (argument checks in Lua, a method on the class's generated Kotlin
   * interface, the marshaling between them) and the runtime implements the
   * interface. `"lua"`: the body is written by hand in the prelude, for what is logic rather
   * than a call into the server (a handle's own id, timers, tasks and their
   * waits). `pnpm generate` still writes the function scripts call: it checks `self` and each
   * argument from the declared types, then calls the body with them, so the body holds only
   * logic. The conformance test checks that the prelude defines exactly the `"lua"` set.
   */
  impl?: 'kotlin' | 'lua'
  /**
   * Asynchronous: its work runs off the main thread and the result arrives later, on the main
   * thread. Declared with `asyncFunction` (`src/async.ts`), which gives it both forms: a last,
   * optional `callback`, called with `value, err` once the work is done (the call itself
   * returns nothing), or, without one and inside a task (`nf.task`), the call waits and
   * returns `value, err`. On failure `value` is `nil` and `err` says why: the one documented
   * exception to "one return value". The generated binding is the same for every one (the
   * prelude's `async` core and the runtime's `Pending`, see the plugin-runtime skill,
   * "Threading"); the Kotlin side returns a `CompletionStage` of the value.
   */
  async?: boolean
  /**
   * It waits (`nf.wait`, `dialog:ask`), so it works only in a task's own code (`nf.task`): an
   * event handler, a timer or a script's body calling it is an error. Hand-written in the
   * prelude's `tasks` module (`running_task`). The docs say so, and the LuaLS stubs mark it
   * `---@async`, so lua-language-server flags a handler that calls it (`not-yieldable`). An
   * `async` function's waiting form is the same without being marked: its callback form works
   * anywhere.
   */
  waits?: boolean
  /**
   * The feature it needs, when that arrived after the oldest supported Minecraft (format's feature
   * table says when). Using it on an older server is an error naming the version.
   */
  since?: FeatureId
  /**
   * What the calling package must have declared to use it (see [Requirement]). Calling it
   * without is an error naming what to declare; the docs and stubs say so, and the editor
   * flags a call in a project that hasn't.
   */
  requires?: Requirement
}

/**
 * Something a package declares before its scripts may use the functions that need it, so
 * whoever runs it sees in one place what it may do ("Trust" in PLAN.md's decisions):
 * `console` (running commands as the console), `moderation` (banning, the whitelist, the
 * server's settings), `http` (the network), `db`
 * (a database) or `plugin:<name>` (another plugin, like `plugin:vault`). `REQUIREMENTS`
 * (`src/requirements.ts`) says how each is declared.
 */
export type Requirement =
  | 'console'
  | 'moderation'
  | 'http'
  | 'db'
  | 'web'
  | 'bots'
  | 'wasm'
  | 'network'
  | `plugin:${string}`

/** What the server reads a command argument as, before the runtime sees it: the runtime's `ArgumentReading`. */
export type ArgumentReading =
  | 'word'
  | 'text'
  | 'integer'
  | 'number'
  | 'boolean'
  | 'choice'
  | 'player'
  | 'players'
  | 'entity'
  | 'entities'
  | 'position'
  | 'block_state'
  | 'item'

/**
 * A type a command argument can be (`{ name = "target", type = "player" }`). `pnpm generate`
 * writes the runtime's `ArgumentType` from these, with the codec a `default` of it is read
 * with; the runtime resolves what the server read into the handler's value by hand.
 */
export interface CommandArgumentType {
  /** Its name in a definition: `"player"`. */
  name: string
  /** What may be typed, as a phrase the `type` field's doc lists: `"an online player by name, …"`. */
  doc: string
  /** The type of the value its handler gets, and of a `default` for it: `Player`, `Vec3`. */
  value: LuaType
  /** What the server parses it as (an argument type in Brigadier on Paper). */
  reading: ArgumentReading
  /**
   * Read as a word that only the runtime can resolve and complete (the project's centities,
   * the server's worlds), rather than something the server knows.
   */
  names?: boolean
}

/**
 * What identifies a handle: the values its entry in the runtime's handle table
 * is made of. Lua sees a handle as one opaque key (an integer into that table),
 * so a script can't read or forge what it's made of; Kotlin holds the values.
 * Each is a string or an integer, and there are at most two. Nothing else is
 * kept with a handle: whatever else it answers (a player's name) is looked up
 * when it's asked, so it never goes stale.
 */
export interface HandleSpec {
  /** Its identity, in order: two handles of the same values are the same table. */
  key: { name: string; type: 'string' | 'integer' }[]
}

/** A field of a class, shape or value. */
export interface Field {
  name: string
  type: LuaType
  doc: string
  /**
   * For an option table's field: the oldest Minecraft version that takes it, when that's newer
   * than the oldest supported one. Setting it on an older server is an error naming the version.
   */
  since?: FeatureId
}

/**
 * A table with fields and methods, like `nf`, or an object type like `Centity` whose methods
 * are called with `:`. Shapes (plain tables: event payloads, option tables) are written with
 * this type too, with `methods: false` and only fields.
 */
export interface LuaClass {
  name: string
  doc: string
  /** `true` for objects used with `:` (handles); `false` for plain tables used with `.` (namespaces). */
  methods: boolean
  /**
   * For a handle class (`methods: true`): what identifies one. Required on every handle class
   * but one that `extends` another, which is keyed as the top of its chain is.
   */
  handle?: HandleSpec
  functions: Fn[]
  fields: Field[]
  /**
   * The events `:on` accepts on this class (on `nf`, the server-wide events `nf.on` accepts),
   * besides those it inherits (see [extends]); one of the same name as an inherited one
   * replaces it. A class with events of its own has `on` and `once`, typed per event.
   */
  events?: EventSpec[]
  /**
   * Whether this class also takes custom events, named with a `:` (`"shop:purchased"`), which
   * scripts raise with `emit`. Only where something can emit them: `nf` and `Centity`.
   */
  customEvents?: boolean
  /**
   * For a shape: the shape whose fields and methods it has too (`ClickEvent` extends `Event`).
   * For a handle class: the handle class it is a kind of (`Mob` extends `Living`, which extends
   * `Entity`), to any depth. It has the methods and events of every class up its chain, its own
   * replacing those of the same name, and a handle of it is taken wherever one of a class up
   * its chain is. It has no `handle` of its own: a thing is one handle whatever its class, keyed
   * as the top of its chain says, and the runtime says which class it is. A method goes on the
   * class it applies to, so no class has one that can only answer `nil`.
   */
  extends?: string
  /**
   * For a handle class at the top of its chain: a `data()` table (and `nf.json`, and
   * `Item.data`) may keep one, saved as its key under the tag `$<lowercase name>`
   * (`{"$entity":["…"]}`) and a handle again when it's read back. A class below it is saved as
   * the one at the top (a `Player` is an `Entity`).
   */
  saveable?: boolean
  /**
   * For a namespace: it exists only in a test run (`netherforge test`), never on a server. The
   * runtime takes it out of `nf` unless a test harness is running the project.
   */
  testOnly?: boolean
}

/** An arithmetic operator a value type defines, by its LuaLS name: `add` is `a + b`, `unm` is `-a`. */
export type OperatorName = 'add' | 'sub' | 'mul' | 'div' | 'unm'

export interface Operator {
  op: OperatorName
  /** The right-hand operand's type; absent for `unm`. */
  operand?: LuaType
  result: LuaType
  doc: string
}

/**
 * An immutable value a script builds and computes with, like `Vec3`: a table
 * with read-only fields, methods called with `:`, and operators. Values are
 * written in Lua (the prelude), and cross to and from Kotlin by value (a
 * `Vec3` as its three numbers), never as a handle: the generator marshals each
 * one it knows (`bindings.ts`).
 */
export interface ValueType {
  name: string
  doc: string
  /** Read-only: assigning to one is an error. */
  fields: Field[]
  /** Methods, called with `:`. All implemented in the prelude. */
  functions: Fn[]
  operators: Operator[]
  /** The global table that builds one and holds its constants, when it has one (`vec3`). */
  library?: ValueLibrary
}

/** A value type's global table: callable to build a value (`vec3(1, 2, 3)`), with constants and functions on it. */
export interface ValueLibrary {
  name: string
  doc: string
  /** What calling the table itself does: its parameters and the value it returns. */
  call: Fn
  /** Constants, like `vec3.zero`. */
  fields: Field[]
  /** Functions called with a dot, like `vec3.from_yaw_pitch`. Implemented in the prelude. */
  functions: Fn[]
}

/** Where a script runs: a centity node's script, a module file, … */
export interface Surface {
  name: string
  doc: string
  /** Globals available on this surface beyond the shared ones, e.g. `this`. */
  globals: Field[]
}

/**
 * Something `:on(name, handler)` can listen for on the class that declares it. The handler
 * gets an event object: the payload's fields, plus `name`, `current`, `stop()`, `cancel()`
 * and the rest of `Event`. What a handler returns means nothing.
 */
export interface EventSpec {
  name: string
  doc: string
  /** The shape the handler receives (a shape extending `Event`), or absent for a bare `Event`. */
  payload?: LuaType
  /** `event:cancel()` cancels what caused it; on any other event, `cancel()` is an error. */
  cancellable?: boolean
  /**
   * After this handle's handlers, the event goes on along a path the doc describes: a node's
   * click to its parent's, then the centity's, then `nf`'s `centity_click`. `event:stop()` ends it.
   */
  bubbles?: boolean
  /**
   * Payload fields handlers may assign; the runtime reads them back after every handler has
   * run. Assigning any other field is an error.
   */
  writable?: string[]
  /** The fields of `EventOptions` that `:on` accepts for this event (`every`, for `tick`). */
  options?: string[]
  /** Delivered only to the script that registered it, not to every script. */
  local?: boolean
  example?: string
  /**
   * The oldest Minecraft version that has it, when that's newer than the oldest supported one.
   * Listening for it on an older server is an error naming the version.
   */
  since?: FeatureId
  /**
   * For an event on `nf` that the server raises: how. `pnpm generate` writes everything from
   * the server's adapter to the scripts out of it (see [Raised]). An `nf` event without it is
   * one the runtime raises itself (a tick, a click on a centity), or one whose path the
   * runtime decides (a project item hears it first, the services hear of a death).
   */
  raised?: Raised
}

/**
 * How the server raises an event on `nf`. `pnpm generate` writes, from the event and its
 * payload: the method on the adapter-facing sink (`GameEvents`), which takes the payload in
 * the platform's own types (a `PlayerRef` for a `Player`, a `UUID` for an `Entity`, a
 * `BlockRef`, a world's name) as a data class whose writable fields the dispatch writes back;
 * the runtime's dispatch (the handles made, the path walked, the writable fields read back and
 * kept within their bounds); and, for a watched one, its `WatchedEvent`. An adapter only maps
 * the server's event to that payload and applies what came back.
 */
export interface Raised {
  /**
   * The handle it's heard on first, with the same event, before `nf`: the class the event is
   * on there, its name on that class, and the payload field holding the handle. For `Entity`,
   * `Living`, `Mob` or `Player`, the entity in that field as that class; for `World`, the
   * world of the `World`, `Block` or `Location` in it. A field that's `nil` leaves it out.
   */
  first?: { class: string; event: string; field: string }
  /**
   * The server raises it all the time, so the adapter listens for it only while a script
   * does (it's a `WatchedEvent`, which the runtime turns on and off).
   */
  watched?: boolean
  /**
   * Payload fields the adapter hands over as a function, called only when something listens:
   * those costly to work out (every block an explosion breaks).
   */
  lazy?: string[]
  /**
   * What a writable number field is kept within, whatever a handler assigns: `{ food: { min:
   * 0, max: 20 } }`. An integer is kept within a 32-bit one's range besides.
   */
  bounds?: Record<string, { min?: number; max?: number }>
}

/** A reference page that isn't about one class (the naming rules, coordinate spaces). */
export interface Article {
  /** Its file in `docs/reference/`: \`naming.md\`. */
  file: string
  title: string
  /** What the reference's index says about it, after its title. */
  summary: string
  /** Its Markdown, under its title. */
  body: string
}

/**
 * Another name for a primitive type that means more than it: `Text` is a
 * `string` of MiniMessage. Scripts see the primitive; the runtime crosses it
 * with its own codec (`bindings.ts`' `RUNTIME_TYPES`), and the docs and stubs
 * say what it means.
 */
export interface TypeAlias {
  name: string
  /** The primitive it is. */
  type: 'string'
  doc: string
}

/**
 * A number the docs quote that the runtime owns (a limit, a default): [runtime] names each Kotlin constant it is
 * (`<class>#<field>`, the JVM's names), which the runtime's `ConformanceTest` holds it to.
 */
export interface Constant {
  name: string
  value: number
  doc: string
  runtime: string[]
}

export interface ApiSpec {
  /** Globals every surface gets (`nf`, `require`, `log`, …). */
  globals: Field[]
  classes: LuaClass[]
  /** Value types (`Vec3`, `Location`). Optional so hand-written specs (tests) can leave it out. */
  values?: ValueType[]
  /** Type aliases (`Text`). Optional so hand-written specs (tests) can leave it out. */
  aliases?: TypeAlias[]
  surfaces: Surface[]
  /** Event payload types and other plain shapes: fields only, except `Event`, the base of every payload, whose methods (`stop`, `cancel`) are the prelude's. */
  shapes: LuaClass[]
  /** Standard Lua globals the sandbox removes. */
  removed: string[]
  /** What a command argument's `type` can be. Optional so hand-written specs (tests) can leave it out. */
  commandArguments?: CommandArgumentType[]
  /** The runtime's numbers the docs quote. Optional so hand-written specs (tests) can leave it out. */
  constants?: Constant[]
}
