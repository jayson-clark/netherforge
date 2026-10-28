---
name: plugin-runtime
description: How the NetherForge Paper plugin is put together - the runtime/adapter split and the Platform interface, where each package and file is, one Lua state with a scope per script, require and handles, the session and its services, packages, commands, threading, luajava's sharp edges, and testing. Its areas have skills of their own (plugin-sandbox, plugin-capabilities, plugin-world, plugin-persistence, plugin-bots, entities-and-physics, menus-and-dialogs). Read before changing anything under apps/plugin/.
---

# Plugin runtime

This is the plugin's core: how it's split, where things are, and the rules every part follows. Each area has a
skill of its own; read it before working there:

| Skill                | Covers                                                                                                                                 |
| -------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| plugin-sandbox       | the sandbox (hook, budget, time, memory, the library's caps), the profiler, the debugger, the prelude's modules                        |
| plugin-capabilities  | `requires`, HTTP, streams and WebSockets, web routes, WebAssembly, server networks, plugin interop, owner settings, the threat model   |
| plugin-world         | custom blocks, terrains, dimension types and biomes, the start-up datapack, worlds and structures, loot, mob goals, cutscenes, screens |
| plugin-persistence   | the store, package and remote databases, saved tables, centity records, permissions, schedules, per-session state                      |
| plugin-bots          | fake players: the bridge's, scripts', and how they join and act on a real server                                                       |
| entities-and-physics | centities as the server runs them (tick pipeline, instances, natural spawning, hitboxes, clicks) and physics                           |
| menus-and-dialogs    | windows, dialogs, project items                                                                                                        |

## The split

```
apps/plugin/runtime/          dev.netherforge.plugin       no Paper types, ever; tested against FakePlatform
apps/plugin/paper-common/     dev.netherforge.plugin.paper the Platform on Paper's API, for every supported version
apps/plugin/paper-internals/  ...paper.version, ...bots    Mojang-named server code (registries, goal priorities,
                                                           the bots), compiled by each adapter against its server
apps/plugin/paper-<mc>/                                    one adapter per Minecraft version: what its server says
                                                           its own way (see the minecraft-versions skill)
```

The runtime sees only `platform/Platform.kt` (and `platform/Worlds.kt`):
grouped operations (`worlds`, `blocks`, `entities`, `players`, `commands`,
`game`; no scheduler: see "Threading") in NetherForge's own types (`Location`, `PlayerRef`,
`EntityTag`, `DisplayPose`, format's `DisplayDef` and `Matrix4`). **Every
group is required**, and so is every method in it (no nulls, no do-nothing
defaults): `menus`, `dialogs`, `resourcePacks`, `particles`,
`sounds`, `bossBars`, `sidebars`, `recipes`, `loot` (`platform/Loot.kt`: the game's own loot tables,
rolled by the server from a seed), `projectItems` (rewriting stale project
item stacks; see menus-and-dialogs), `itemViews` (stacks as they look to the one player they're sent to,
and refreshing them; see menus-and-dialogs), `teams`, `playerList`, `attributes` (`platform/Attributes.kt`:
an entity's attribute instances, modifiers by namespaced id), `pathfinding`
(`platform/PathfindingOps.kt`: a mob's own navigation; Paper has no event for a
path ending, so `mob/MobPaths.kt` checks the walks `move_to` started once a
tick and raises `path_end`), `mobGoals` (`platform/MobGoalOps.kt`: a mob's AI
goals by namespaced key, and goals written in Lua, which `mob/MobGoals.kt`
keeps per scope: see "Mob goals" in the plugin-world skill), `worldManager`, `borders`, `structures`
(`platform/WorldAdmin.kt`), and `playerViews` (what one player is shown that
isn't so: blocks, equipment, a book, a camera, a compass, a view distance),
`serverAdmin` (who has played, max players, the MOTD, the whitelist, bans),
`permissions` and `advancements` (`platform/PlayerAdmin.kt`), `pause` (`PauseOps`:
keeping a server the debugger holds at a breakpoint alive); `WorldOps.collisionBoxes`
is what physics lands on. `mannequins` (`platform/Mannequins.kt`) is what
only a `minecraft:mannequin` has (skin, description, pose, skin parts, read
and written whole as a `MannequinLook`; `EntityCategory.MANNEQUIN` makes its
handle a `Mannequin`). A feature some Minecraft version lacks is gated by version (the
minecraft-versions skill), never answered with a null group, so the runtime
has no `platform.x?.` branches that only a hypothetical server would take.
`worldEntities` (`platform/Entities.kt`) is vanilla entities by UUID for the `Entity` API and the classes below it (yes-or-no properties and numbers through
`EntityFlag`/`EntityNumber`, so a new one is an enum entry, not a method; `EntityInfo.category`
says which class an entity's handle is: `entityHandle(info)`), and
never hands out an entity carrying an `EntityTag`; `inventories` is real
inventories by `InventoryRef` (a player's, an ender chest, an entity's, a
container block's). `WorldOps` also answers what a `World` handle asks (time, weather,
game rules, chunks, explosions, a block ray cast), and `BlockOps` reads and
writes blocks by world and coordinates, never loading a chunk.
`ParticleOps.spawn` is one batch of spawns to listed viewers, the path
`world:spawn_particle` and particle effects share. The adapter calls back in two ways.

**Generated: every event the server raises and scripts only hear** (an `nf`
event whose spec has `raised`, see lua-api: joins and quits, chat, moves,
teleports, a player's state and input, combat and projectiles, block
placing and the world simulating itself, chunks, mounts and vehicles, vanilla
inventories, the server list). `pnpm generate` writes the adapter-facing sink
(`platform/GameEvents.kt`: `GameEvents`, a method per event taking its payload
as a `GameEvent.<Name>` data class in the platform's own types, a writable
field a `var` written back into, a costly one a function; the
`WatchedEvent` enum; `GameEventDelivery` for the fake, `GameEventFunnel` for
`RecordingEvents`) and the runtime's side (`binding/GameEventDispatch.kt`: the
handles made through `EventSession`, the path walked (the handle in
`raised.first`, then `nf`), the payload built only when something listens,
the writable fields read back within their `bounds`). `PlatformEvents.game`
is `RuntimeGameEvents`, which only adds what the services do around a few of
them (they hear of a join before the scripts and of a quit after, save a
chunk's blocks after `chunk_unload`, refresh an opened inventory's items).
`Scripts.emit` leaves out a stage whose target's class has its own event of
that name (a `Player`'s `death` is `player_death`, not a `Living`'s), so an
entity's event is raised the same way for every entity. In the adapter,
`PaperGameEvents` maps each Paper event to its payload, calls the sink and
applies what came back (cancelled, or a writable field set on the Paper
event when it changed) — the only hand-written line an event needs.

**Some events cost the server to watch even when nobody listens** (`watched`
in the spec): the runtime tells the adapter when a script starts and stops
listening (`Platform.watch(WatchedEvent, listening)`, from `Scripts.watch` over
`GameEventDispatch.WATCHED`). Their handlers in `PaperGameEvents` are
`@Watched(WatchedEvent.X, priority, ignoreCancelled)` in place of
`@EventHandler`, and `Watching` registers each alone (its own key `Listener`,
through `EventExecutor.create`) only while it's watched; it refuses to start
if a `WatchedEvent` has no handler, or two. Moves are filtered by
`hasChangedBlock()`; a chat line (`AsyncChatEvent`, off the main thread) waits
for the main thread's answer at most 5 seconds, a server list ping at most 2.

**Hand-written: the events the runtime does more with than raise them**
(`platform/PlatformEvents.kt`, `ServerEvents`, fed by `PaperEvents`): the
tick, clicks and interactions (a project item hears them first), block
breaks, deaths and damage, drops, pick-ups and consumes, entities loading
and unloading, worlds saving, entities clicked (centities), resource pack
statuses, menu clicks/drags/closes, dialog presses and exit-action closes. It
applies the answers (`true` from a cancellable one cancels the Bukkit event;
drops and experience (`DropsAnswer`, `DeathAnswer`, null drops for
unchanged) come back changed or not); drops are asked for lazily
(`() -> List<ItemData>`), only when a script listens. A player's
death is `player_death` (their handle, then `nf`), then `entity_death` (`nf`
only); keeping the inventory empties the drops unless a handler set them.
`ServerEvents` implements `PlatformEvents` (`runtime.events`, what
`Platform.bind` gets): each event goes to the session running now, to its
services' hooks and to its scripts. The adapter's whole job is
`NetherForgePlugin` (construct at STARTUP, enable once the server has loaded, tick, disable; see "Terrains" in the plugin-world skill), `PaperPlatform`,
`PaperEntities`, `PaperCommands`, `PaperGameData`, `PaperEvents`, `PaperGameEvents`,
`PaperMenus`, `PaperItems`, `PaperDialogs`.

**Settings.** The adapter reads `plugins/NetherForge/config.yml` into plain
values (sections as maps) and hands them over; `RuntimeConfig.read` is the
one place they're understood (`project:`, `resource-pack:`, `performance:`, `schedules:`,
and the editor's `-Dnetherforge.*` properties for a dev server), so every
version's adapter reads them alike and database connections land in one place. `RuntimeConfigTest` is the model.

`/nf` lives in the runtime (`admin/AdminCommand.kt`) so every adapter gets it and
it's tested against the fake (`/nf requires` lists the tree's declared
capabilities). A subcommand is a word in `subcommands`, a
branch in `run`, and its completions in `options`.

## Where things are

Every package of the runtime (`apps/plugin/runtime/src/main/kotlin/dev/netherforge/plugin/`), and its tests in the
same package under `src/test/` (shared test support, `TestServer`, `LuaChecks`, `NfvFiles`, stays at the root, as
do the tests of the root's own classes). The root holds only `NetherForgeRuntime`, `RuntimeConfig` (with
`ServerAddress`), `RuntimeLog` and `Folia`; each section of `config.yml` is read in `RuntimeConfig` into its
feature's own config class (`ScreensConfig` in `screen/`, `WasmConfig` in `wasm/`, `HttpConfig` in `http/`, ...).

| Package                                                              | Owns                                                                                                                                                                                                                                                                                  |
| -------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| root                                                                 | `NetherForgeRuntime` (the composition root: binding, bridge, `/nf`, building and disposing sessions, the tick, what the bridge asks), `RuntimeConfig`                                                                                                                                 |
| `session/`                                                           | `ProjectSession` (the services, lifecycle, hooks, reload, problems), `PackageNames`, `Requirements` (declared capabilities: the one check), `RuntimeService`                                                                                                                          |
| `binding/` (+ `../generated/.../binding/`)                           | the API bindings `pnpm generate` writes (`LuaApi`, `LuaHandle`, `LuaPrimitives`, `LuaShapes`, `Events` + payloads, `GameEventDispatch`) and the hand-written support they call (`Marshal`, `Caller`, `EventSession`, `LuaLocation`, handles, `notInProject`): what every service uses |
| `api/`                                                               | every implementation of a generated `*Api` interface, whatever its area (`RuntimeApi` holds them); no service imports one (lua-api skill)                                                                                                                                             |
| `lua/`                                                               | `LuaHost` (the Lua 5.4 state, calling in, typed values), `LuaHostTypes`, `PlainJson`, `LuaCodec`, `SandboxLimits`; the prelude and caps in `resources/.../lua/` (plugin-sandbox)                                                                                                      |
| `script/`                                                            | `Scripts`, `Scope` (scopes, the error policy, raising events, who listens, timers), `ScriptCosts`, `ScriptFiles` (`nf.files.get`), `PerformanceConfig`                                                                                                                                |
| `events/`                                                            | `ServerEvents` (`PlatformEvents`: the server's events raised to the running session) and `RuntimeGameEvents` (the services' part around `GameEventDispatch`)                                                                                                                          |
| `async/`                                                             | `Workers` (the one pool and its lanes), `SerialExecutor`, `Completions` and `MainThread`, `AsyncWork` and `Pending` (see "Threading")                                                                                                                                                 |
| `admin/`                                                             | `/nf`: `AdminCommand`, `DataCommand`                                                                                                                                                                                                                                                  |
| `command/`                                                           | typed commands: definitions, what the server read turned into handler values, usage, completion                                                                                                                                                                                       |
| `module/`, `project/`                                                | `Modules` (module scopes, `require`, dependents); `ProjectFiles` (the project's and its packages' files, dependencies, bundles)                                                                                                                                                       |
| `platform/`                                                          | the `Platform` interface and its groups, a file per area (`Worlds`, `Entities`, `Displays`, `PlayerOps`, `Items`, `Windows`, `ResourcePacks`, ...)                                                                                                                                    |
| `centity/`, `physics/`                                               | centities and the physics solver: `Centities`, `CentityClicks`, `Instance`, `NaturalSpawner`, ... (entities-and-physics skill)                                                                                                                                                        |
| `menu/`, `dialog/`, `item/`                                          | windows, dialogs, project items, recipes (menus-and-dialogs skill)                                                                                                                                                                                                                    |
| `block/`                                                             | `CustomBlocks` (the project's blocks: records, carriers, placing, breaking, falling, ticking), `BlockMining` (plugin-world)                                                                                                                                                           |
| `terrain/`, `structure/`, `world/`                                   | terrains and the main world's generator; structures, gradual placement, structure spawns; worlds, spawn rates, daylight, effects, block and entity data, hidden entities, flood (plugin-world)                                                                                        |
| `datapack/`, `loot/`, `mob/`, `cutscene/`, `screen/`                 | the start-up datapack; loot tables; mob goals and paths; cutscenes; screens and videos (plugin-world)                                                                                                                                                                                 |
| `scoreboard/`, `stats/`, `sky/`, `hud/`, `particle/`, `advancement/` | boss bars, sidebars, teams, player lists; stat sheets and buffs; skies; HUDs; particle effects; advancements                                                                                                                                                                          |
| `store/`, `data/`, `schedule/`                                       | the store and databases, saved tables and permissions, schedules (plugin-persistence)                                                                                                                                                                                                 |
| `http/`, `web/`, `wasm/`, `network/`, `interop/`, `settings/`        | the network, web routes and logins, WebAssembly, server networks (`nf.network`: the link to the hub, for the plugin's life, and the session's half), Vault and PlaceholderAPI, owner settings (plugin-capabilities)                                                                   |
| `bridge/`, `debug/`, `profile/`                                      | the dev bridge (hot-reload skill); the debugger and the profiler (plugin-sandbox)                                                                                                                                                                                                     |
| `bots/`, `pack/`, `testing/`                                         | scripts' bots (plugin-bots); the resource pack (resource-packs skill); script tests under a test run (`ScriptTests`, `TestHarness`)                                                                                                                                                   |

## Lua: one state, a scope per script

- **One `LuaHost` per project** runs every module and every resource script
  (each centity instance's, each menu window's, each dialog's). That's what makes `require("combat")` return the module
  that's _running_, state and all, from anywhere. A scope (`Scope`) is just an
  environment table: its own globals, falling back to `base` (a whitelist of
  the standard library; shared library tables are read-only proxies).
- **Surfaces.** A scope's owner (`ScopeOwner`: `Module`, `CentityScript`,
  `MenuScript`, `DialogScript`, `ItemScript`) decides its extra global: `this`, the
  `Centity` instance, `Menu` window, `Dialog` or `ProjectItem` (`host.new_env(scope, budget,
surface, id)`; a module has none). `Scripts.open(owner, budget)` picks it.
  A resource has at most one script, so an instance, a
  window and a dialog each hold one scope. The `.lua` files beside that
  script are part of it: they run in that scope (see `require` below).
- **The body is load.** A script's top level runs every time it starts
  (spawn, restart, reload); there are no hook globals. Everything else is an
  event on a handle (`this:on("tick", ...)`), kept by the prelude's event
  core.
- **Per-scope `nf`, `require`, `log`.** `make_nf(scope)` closes over the
  scope id, so `nf.on` registers into the scope whose _code_ called it, and a
  bare `require("util")` resolves against the file's own module. A handle's
  `:on` can't close over anything (handles are shared), so it registers into
  the **running** scope: each budget frame records whose call it is
  (`invoke(budget, scope, fn)`), and `require` switches it to the module
  while a module file loads.
- **`require`.** `Modules.resolve` picks the file and the scope it runs in.
  The caller's own files come first: a module's folder, or (for a
  `CentityScript`, `MenuScript`, `DialogScript`, `ItemScript`) the folder of
  the owner's `file`, `<folder>/a/b.lua` then `<folder>/a/b/init.lua`; then
  modules. A module file runs in its module's scope; a file beside a
  resource's script runs in the **requiring scope itself**, so every
  instance, window and dialog has its own copy with its own `this`. The
  prelude's cache (`loaded`, `loading`) is keyed by (scope the file runs in,
  path) for both: a module has one scope, so its files still run once for
  everyone, and a circular require is per scope. `host.drop_env` forgets a
  scope's entries; `host.forget(prefix)` (module reload) clears a path
  prefix in every scope. Requiring the script's own file is an error (it's
  already running; it never went through the cache). A sibling that requires
  a module records it in the scope's `requires`, so the module's reload
  restarts the resource as if the script had required it.
- **Lifetimes.** A subscription is filed under its target (the handle table
  itself, which it keeps alive) and its scope, and lives as long as both:
  `host.drop_env` (the scope closes) and `Scripts.dropTarget` (a removed
  centity, with its nodes; a retired window, with its slots; a deleted dialog,
  with its buttons: `LuaHandle.partOf`) each cancel it. `:on` on a handle
  whose thing is already gone returns an inactive subscription
  (`events.alive`).
- **Handles are keys.** A handle is an empty table with its class's protected
  metatable, standing for one integer key in `LuaHost.handles` (a
  `HandleTable`), whose entry Kotlin keeps: the `LuaHandle` (a UUID, a node's
  name) and the most specific class known for it. The prelude keeps each
  table's key (`ids`) and each key's table (`cache`), both weak, so two
  handles to the same thing are the same table and `a == b` works;
  `LuaHost.pushHandle` makes the tables. A table Lua collects notes its key
  (`__gc`), and `sweepHandles` (once a tick) releases the keys with no table
  again. One thing is one entry whatever its class: an `Entity` handed out
  while its entity was unloaded becomes the `Mob` it is (the same table, given
  `Mob`'s metatable) when Kotlin hands it out again, or when a method its
  class lacks asks (`handles.refine`, `LuaMarshal.refine`). Nothing else is
  cached in a handle: a player's name is looked up when it's asked.
- **Primitives are unreachable.** The prelude receives the Kotlin primitive
  table as its chunk argument and hands it only to its own modules
  (`require("input")`, which no script can reach). Scripts see handles,
  never their keys' values or primitives.
- **A resource's files are where the snapshot says** (group folders,
  `centities/(towers)/tower/`, see project-format): a service that reads a
  script or file asks `ProjectSession.placeOf(kind, id)` (handed to
  services as `placeOf`), `Modules` keeps each module's place, and reloads
  match files to a resource by classifying them (`Resource.of`, `mainOf`,
  `ReloadBatch.exists(resource)`), never by building its path from the id.
- **Chunk names are project paths** (`@centities/tower/root.lua`), so Lua's
  own messages and tracebacks already speak the editor's language. The
  prelude is `=nf` and its modules `=nf:<name>`. `describe()` (the message handler for every call in)
  takes the location from the message prefix, else from the first `@` frame,
  so an error raised in Kotlin or in the prelude points at the script's line.
- **Budget, time, memory, the library's caps**: the plugin-sandbox skill.
- **Time per scope.** Every frame also times itself with
  the `clock` primitive (`System.nanoTime`, or a test's; `LuaHost`'s own,
  not RuntimeApi's): its own time is what it took less the frames nested in
  it, charged to its scope (`timing.close`, in `invoke` and a task's
  `resume`). So a spawn's new centity body, or a custom event's handlers in
  another module, are theirs, not the caller's. (A module's file loaded by
  `require` runs in the requirer's frame, so its first load is the
  requirer's.) That's two JNI calls per call in; nothing else is added to a
  call. `ScriptCosts.record` drains the totals once a tick
  (`host.take_costs`, one string) into a 100-tick ring per scope, read by
  `/nf scripts` (`report`, with subscriptions and tasks from
  `host.scope_counts`, and every service's `costs()` for whatever else a
  scope owns: particle effects' and mob goals'). A scope whose average over
  `performance.warn-ticks` ticks is over `warn-ms` (config.yml, read on dev
  servers too; 0 = off) is reported once per `QUIET_TICKS` per scope; its
  first recorded tick (its body's load) never counts. The location is
  `host.hot_spot`: the function of the slowest single call over this window
  of `warn-ticks` ticks and the one before (`nf.after` wrappers resolve to
  the script's function through `timing.alias`, a task to its function);
  null for prelude code (a command's dispatcher). `onScriptSlow` coalesces
  scopes of one script slow in one place into one line a minute.
- **Tasks** (`nf.task`) are coroutines in the
  prelude's tasks section. Each resume gets its own budget frame with the
  scope's budget, charged to the caller like any nested call in. A wait of
  ticks is a one-shot Kotlin timer that wakes the task; `wait_for` and
  `dialog:ask` subscribe in the task's scope, and the event's handler resumes
  the task inline, during delivery (so it can still cancel the event). Every
  wait has a token so a stale wake-up does nothing. Tasks die with their
  scope (`host.drop_subscriptions`, which `drop_env` calls) and when a handle
  they wait on goes (`host.drop_targets` calls the subscription's `gone`).
  `Task` handles are prelude ids for `nf.after`/`every` too (their Kotlin
  timer id is inside); the prelude finds a `Task`'s or `Subscription`'s id by
  its handle (`keys.Task`, `keys.Subscription`: the key accessors generated
  from the spec's `handle`). Errors in a task are reported as `task` through
  `events.failed` and end it.
- **Where a task may wait**: only in its own code, which `running_task`
  checks: the running coroutine must be the task's and the budget frame the
  one its resume opened. A handler run from inside the task, a function
  Kotlin calls back (always a new frame, and a yield there would cross JNI and
  fail), a script's own coroutine, and a C function's callback (`table.sort`,
  `string.gsub`: `coroutine.isyieldable()` is false) each get a clear error.
  `pcall`/`xpcall` are fine (Lua 5.4 yields across them). A primitive called
  from a task runs with `LuaHost.current` set to the task's thread.
- **Errors.** A failing **body** disables its scope
  (`Scripts.guard`): subscriptions, timers, tasks and commands go until its
  resource reloads. A failing **handler, timer, task or command** doesn't (a
  failing task ends): it's reported
  (`Scripts.onError` → `ScriptReports.errored`: console, a
  `script_error`, and a `script.error` problem until the scope closes), the
  same error again within five seconds is only counted, and a subscription or
  repeating timer that fails 20 times in a row is cancelled with a line saying
  so (a success resets the count). A command stays registered and the sender
  is told; an argument's `complete` that fails is reported the same way and
  offers nothing.
- **Calls back into Lua from inside a primitive** use the thread the
  primitive was called on (`LuaHost.current`): a script's coroutine may be
  running, and pushing onto the main thread's stack then corrupts it.

## luajava's sharp edges (each cost a JVM crash once)

- **`Lua.ref(int)` is `luaL_ref` on a _table_ index.** A Kotlin extension
  `fun Lua.ref(...)` is silently shadowed by it (members win) and corrupts the
  Lua heap. Name helpers so they can't collide (the generated `argString`).
- A Kotlin exception thrown from a primitive becomes a Lua error carrying its
  `toString()`; `LuaApiException` overrides it to the bare message.
- Never run Lua that can error outside a protected call: a `longjmp` across
  JNI frames kills the JVM. Everything goes through `pCall` into the prelude's
  `xpcall`-wrapped host functions.
- luajava installs a `java` global before `openLibraries()`. The prelude's
  whitelist leaves it (and `io`, `os`, `debug`, `load`, …) out.
- A crash shows up as `hs_err_pid*.log` with a frame in Java code that's
  innocent. Suspect the last native call; rerun the one test with
  `jvmArgs("-Xcheck:jni")`.

## The session and its services

`NetherForgeRuntime` is the composition root and lives as long as the
plugin: the platform binding (`ServerEvents`), the bridge, the admin
command, the resource pack (`Packs`: the last good build, the server that
serves it, what each player was sent and answered; a full reload neither
resends it nor loses it to a resource pack with errors), the store (see
"Persistence"), the worker pool and the tick count. Everything the project runs is a `ProjectSession`
(`session/ProjectSession.kt`, `runtime.session`): the snapshot, the Lua
state, `Scripts` and every service, built on start and disposed whole on a
full reload or disable. The new session starts from the old one's
`Handover` only: the spawned centities' records, and each saved table's
encoding as it ended. A refused project is a session too: it defines
nothing, has no Lua state and keeps spawned centities inert.

**A subsystem is a `RuntimeService`** (`session/RuntimeService.kt`)
registered in the one list in `ProjectSession` (`val x = service(X(...))`),
and nothing else names it: `SessionTest` fails if a class implementing it
isn't registered, if the session or the runtime holds one that isn't, if
any other file constructs one, or if anything but the session calls its
hooks. Every hook is optional:

| Hook                                                                                 | When                                                                                                             |
| ------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------- |
| `define(project)`                                                                    | the project's resources, before the Lua state exists (`SessionProject.running` is empty when refused)            |
| `start()` / `stop()`                                                                 | in registration order / in reverse; `stop` runs before the Lua state closes                                      |
| `tick(phase)`                                                                        | every `TickPhase` in order (timers, async, events, world, effects, upkeep, accounts, save), each step on its own |
| `scopeReleased(scope)`                                                               | a scope closed or was disabled, its own subscriptions gone                                                       |
| `playerJoined` / `playerQuit`                                                        | joins before the script event, quits after it                                                                    |
| `entityGone`, `entitiesLoaded`, `entitiesUnloading`, `chunkUnloading`, `worldSaving` | the server's (after the scripts' own `chunk_unload`)                                                             |
| `problems()`, `costs()`                                                              | its part of the problems list; what a scope holds, for `/nf scripts`                                             |
| `liveness()`                                                                         | whether a handle of a class it keeps is still there (`events.alive`); one service per handle class               |
| `reloads` + `reload(kind, ids, batch)`                                               | the kinds it reloads: every kind in `Kinds.all` has exactly one (checked when the session is built)              |
| `follows` + `followed(kind, ids, batch)`                                             | kinds (or `default_font`) it's built from, told right after their turn (recipes follow items)                    |

**Order** is the list's: scripts, async work, saved data, block and entity data,
permissions (before any script, so `has_permission` already answers),
particles and sounds, the resource pack (built first: skins and glyphs read it),
script reports, structures, worlds, boss bars, sidebars, teams, player
lists, hidden entities, mob paths and goals, costs, particle effects and
cutscenes (stopped after every script, so `unload` may still play), modules
(stopped after every resource's script, which may require them), items
(their looks exist before any window builds a stack), recipes (built from
those looks), loot tables (data: a roll reads the current ones), menus (shared windows are made in `define`, so a module's
body finds them; their scripts start in `start`), dialogs, centities
(started last, stopped first). So `start()` runs module bodies, then
items', menus', dialogs' and centities' scripts, and anything can
`require` a module (it starts on first require).

**What a scope owns.** One rule: a scope owns what its script **makes**,
the things with a create call and a handle of their own (subscriptions,
timers, tasks, commands, boss bars, teams, playing particle effects and cutscenes, menu
templates, dialogs from `nf.dialogs.create`, recipes from
`nf.recipes.register`, mob goals, an async function's wait), and they go
when it's released (`scopeReleased`). What a script **sets** on a thing
the server has (a player's sidebar, list entries, below-name line and
permission nodes, an entity's visibility, a mob's walk, `data()` tables)
belongs to that thing for the session: it outlives a module reload, and
the session's end undoes it (sidebars hidden, entities shown, everyone
listed again, lines and permissions taken off) except what's persisted
(permission nodes and saved tables), which the next session applies again.
Things the world keeps (spawned centities and entities, worlds, blocks)
are the world's.

**Lua refs carry their host's generation.** `LuaRef` is the registry slot
plus `LuaHost.generation` (one per host, counting in the JVM);
`pushValue`, `invoke`, `encodeData` and `unref` check it and throw
`StaleLuaRef` for another host's. Every function or value the runtime keeps
(timers, command dispatchers, mob goal callbacks, an async function's waker,
menu and dialog contexts, `data()` tables) is a `LuaRef`, never a bare
`Int`, and all of them die with the session that kept them.

**Git packages on a dev server.** The editor starts the server with
`-Dnetherforge.packages=<data>/packages` (`RuntimeConfig.packageCache`), and
`ProjectFiles` opens a git dependency at the commit `netherforge.lock` pins
from its checkout there (`Packages.gitCheckout`), hashed on every load so
format refuses one that isn't what the lock pins (`package.hash`). The server
never fetches: an unpinned or unfetched dependency is `package.git`, and the
editor fetches and locks it (the lock's rewrite reloads everything).

**Packages in a session.** Every scope is one package's
(`Scope.namespace`), and names cross between packages through one class,
`session.names` (`session/PackageNames.kt`); the script-facing rules are
`docs/reference/packages.md` (the spec's `articles.ts`).

- **Whose code it is** (`names.calling()`): the package a value Kotlin is
  pushing or reading is spelled for when the host says so
  (`LuaHost.reading`: a call's arguments as the scope it calls reads them,
  `invoke(…, namespace)`; an event's fields, see below), else the caller
  scope an `nf.*` function passes (`LuaHost.callerScope`, set by
  `Marshal.caller` and reset for every primitive call, so a tail call
  doesn't lose it), else the package of the innermost script frame on the
  stack (`host.calling_file`), else the project's. **A handle method reached
  through a Lua tail call** (`return player:open_menu("shop")` in a library
  function the project called) resolves in the package of the frame left:
  Lua 5.4 reuses the tail-calling function's frame and keeps no record of
  which function it was (only `istailcall` on the callee), so no stack walk
  can recover it, and handle methods can't close over a scope (handles are
  shared). It's documented for script authors and pinned by
  `PackageNamesTest`; `nf.*` functions don't have it. Where that would grant
  something (a requirement), `names.callingHidden()` says the walk was blind
  (`LuaHost.callerHiddenByTailCall`: a frame between the primitive and the
  first script frame entered by a tail call, found with the real
  `debug.getinfo` the host keeps from before the prelude runs), and
  `Requirements` then grants only what every package declares.
- **In.** A name a script hands the API resolves in the calling package and
  is held to what that package's files could name: `names.resource(kind, …)`
  for a resource (the format's `ProjectSnapshot.nameable`: its own, or one a
  package it depends on exports; existence is the registry's to say),
  `names.entry(refKind, …)` for a resource pack entry (a look by `look`'s
  rule, below; a HUD element by that package's `ReferenceIndex.check`, the
  files' words), `names.text(…)` for the glyph
  tags in MiniMessage (`Text`, below), `names.definition(…)` for a menu,
  dialog or recipe table (every `@Ref` and glyph tag in it, items skipped),
  `names.item(…)` for an item table. What comes out is the name **as the
  project names it** (`ruby`, `library:gem`, `library:gems/gem`), which every
  registry is keyed by and the adapter resolves in the project's namespace.
  Errors are the format's messages (`BrokenRef.forScript()`):
  `package "library" doesn't export its item "secret", so only it can use it`.
- **Item tables are values.** `names.item` checks a stack's `item` only for
  its namespace being a loaded package's (`stackName`), and its looks by
  `look`: any script may hold, read and hand back any stack it got, and must
  be able to write back what it read. Making one from a definition
  (`nf.items.create`) is a use and needs the export. `nf.items.all()` lists
  only what the caller may use.
- **Looks are values** (`names.look`, `PackageNames.LOOKS`: item models,
  glyphs, skins, tooltips, equipment looks, sounds). `ns:<pack>/<key>` may be
  any loaded package's look, exported or not, depended on or not, as long as
  that package has it; bare is the caller's own. It's the one rule for every
  place a script names a look (`Node:set_model`, an item table's looks,
  `names.text`'s glyph tags, `names.definition`, `nf.text.glyph`, skins,
  boss bar icons, and `Effects.soundId` through `names.packSound`, which
  still falls back to the server's sounds). It's what lets a project hand a
  package it depends on a look of its own (`nf.looks.qualify` writes the full
  name, a plain string that survives `data()`), which no export could allow:
  dependencies point one way. Showing a look uses nothing, and every client
  has every pack. Runtime only: files are checked as before, so a package
  still validates on its own. A namespace no package has is
  `no package on this server has the namespace "x"`, and a bare name the
  caller lacks but another loaded package has says how to write that one
  (`elsewhere`).
- **Out.** A name read back is spelled for the reading package: bare for its
  own, `ns:id` for another's (`names.spell`, `spellText`, `spell(item, to)`).
  That's every id an implementation returns (`ProjectItem:id`,
  `nf.items.id`, `Menu:id`/`kind`/`skin`, `Dialog:id`, `Centity:kind`,
  `Effect:kind`, `nf.recipes.all`, `notInProject`'s suggestions), every item
  table (`RuntimeApi.pushItem`), every `Text`, and command arguments naming a
  centity or menu (`ResourceName`, spelled by `LuaHost.pushValue`).
- **An item table remembers whose words it's in.** `pushItem` marks each
  table it pushes with the package it spelled it for (the prelude's weak
  `host.spelled`, `LuaHost.markSpelled`), and `Marshal.item`/`match` read a
  marked table's bare names in _that_ package (`LuaHost.spelledFor`), not the
  caller's. So the project's ruby stack passed to a library function says
  `"ruby"` there and still means the project's ruby when the library hands it
  back. A plain string can't be marked: a name passed between packages' code
  should be passed in full.
- **Events are spelled per package.** `Scripts.emit` pushes the payload in
  the project's spelling (`LuaHost.emit(…, home)`); the prelude knows each
  scope's namespace (`host.new_env`'s `namespace`), and before the first
  handler of a different package's (`deliver`) asks Kotlin to spell the
  fields again (`events.respell`): the writable fields are read back into
  the payload in the old package's words, then the payload is pushed in the
  new one's. So what one package's handler assigns carries on to the next,
  and `host.emit` hands back which package the fields end in, which they're
  read back in. `events.check` reads an assignment in the current package's
  words. Only crossing packages costs anything.
- **`Text`.** MiniMessage in the spec is the alias `Text`
  (`packages/api/src/spec/values.ts`), crossed by `LuaCodecs.TEXT`
  (`Marshal.text` / `spellText`): read, its glyph tags are written in full
  as the server names them (`<glyph:library:gems/gem>`), so the adapter,
  which parses text with no script on the stack and maybe later (an item's
  name, a dialog shown later), draws the writing package's glyph; a tag the
  writer can't name is dropped and logged once. Item names and lore and
  definitions' text go through `names.item`/`definition` the same way.
- **Resource pack entries** are looked up by name, exported or not (`Packs.glyph(key)`,
  `textGlyph`, `skinTitle`, `glyphAdvance`): whether a script may name one was
  checked as it named it, and files by the format. Sounds resolve in the
  caller's package (`Effects` reads `names.references()`).

`nf.data(name)` is per package
(the store's `named_data`, by namespace and name); a thing's table (`player:data()`,
`centity:data()`, `entity:data()`, `block:data()`) is the thing's, shared by
every package. Server-owner settings are per package the same way (`nf.config`,
`settings/<namespace>.json`; see Server-owner settings).
Script-registered recipes are named in the registering package
(`library:<id>`).

## Start order

`ProjectSession.start()`: every service's `define` (load and validate came
first, in the constructor), problems reported, then the Lua state, then
every service's `start` (see the order above), then a line saying what
runs. `stop()` runs every service's `stop` in reverse, then closes the
Lua state, and hands over.

## Commands

A project command is a `CommandSpec` whose `CommandSyntax` (subcommands,
typed `ArgumentSyntax`es, permissions, `players_only`) comes from the
definition `nf.commands.register` was given. That function is Kotlin
(`api/NfCommandsImpl.kt`): the generated codec reads the definition
strictly (unknown keys, field types, the argument types the spec lists), and
`CommandDefinitions` checks what a codec can't say (optional arguments last,
`text` last, bounds only on numbers, choices are words, a default of the
argument's type within its bounds), naming the place (`definition.arguments[2]`).
`ProjectCommands` keeps each declared tree with its handlers, defaults and
script completions.

**The server reads the line; the runtime never does.** Each argument type has
an `ArgumentReading` (`platform/Commands.kt`): what the server parses it as and
so which `ArgumentValue` it hands over (`Text`, `Integer`, `Players`,
`Entities`, `Position` with `~`/`^` already resolved for the sender,
`BlockState` in full, `Item`). Types only the runtime can resolve (`world`,
`centity`, `centity_kind`, `menu`, `dialog`) are read as words and marked
`names`.

- **The adapter** turns the syntax into the server's command tree and reads
  each run back out of it. On Paper that's Brigadier nodes with real argument
  types (selectors, fine positions centring whole x and z, block states,
  items, bounded longs and doubles), permissions as requirements, a `choice`
  as a word with its choices as suggestions (and refused in `execute` with a
  `CommandSyntaxException` when it isn't one), and a suggestion provider that
  asks the runtime (`CommandHandler.suggest` with a `SuggestionRequest`: the
  path, the argument, the values before it, what's typed of it) for `names`
  types and anything the script completes. Running a node builds a
  `CommandInput` from the `CommandContext`: the literals typed (`path`), each
  argument's resolved value (`PlayerSelectorArgumentResolver.resolve`, …),
  and the text after the label. Aliases and `netherforge:<name>` the
  registrar adds are redirects, whose context is the redirect's child (its
  root is our node, and the label is the word typed before it), which
  `execute` allows for. A selector that picks no one is Brigadier's error,
  never the handler's.
- **The runtime** (`command/ProjectCommands`, `ArgumentValues`) checks what
  only it knows: each subcommand's permission and `players_only` (Brigadier
  hides a node without permission, but `execute as` can reach it), and the
  project's names (no such world, centity, menu or dialog is a red line and
  the usage). It turns each `ArgumentValue` into the handler's value (a
  `Player` handle, a `Vec3`, a `Location` in the sender's world facing their
  way, a `World`), fills in defaults, and calls the handler with a
  `CommandEvent` (`Scripts.callKept`, timed as `command`; a script completion
  as `complete`).
- **The fake's Brigadier** (`testkit/FakeBrigadier.kt`) does what Paper's does
  for tests: reads a line against the syntax into the same `CommandInput`,
  answers its own mistakes in red (no usage line, like Brigadier), and
  completes as the client asks. It doesn't hide subcommands without
  permission when reading, so the runtime's own check is what tests see.
  `CommandOpsContract` holds both to the same values (typed arguments,
  subcommand paths, aliases, refusals).
- **Paper's registrar is only offered in the `COMMANDS` lifecycle event**
  (start-up and datapack reloads), but module commands change on every
  reload. `PaperCommands` registers through the registrar in the event and
  keeps the dispatcher it got; between events it adds nodes to that root
  (Paper's mirror root converts them as the registrar would) and removes them
  with Brigadier's `removeCommand`, found by reflection because the API's
  Brigadier doesn't declare it. Then every player gets `updateCommands()` on
  the next tick. `pnpm test:integration` covers registration, typed values,
  refusals and a reload swapping a command's arguments.
- `/nf` is free-form: one optional `text` argument the admin command reads
  and completes itself.

## Packages

A project's dependencies (the project-format skill's "Packages") run on the
same server, in the same Lua state, beside the project, each script in its
own package's scope. Inside the runtime, every package's resources are
**named as the project names them**:
`ruby` is the project's item, `library:gem` a package's. That's
`snapshot.running(kind)`/`everywhere(kind)`'s keys, and every registry
(centities, menus, dialogs, items, recipes, modules, particle effects) is
keyed by it; `ResourceRef.nameIn(home)` turns a reference (`shop:ruby`,
`library:gem`) into that name (`Items.idOf`, `Dialogs.idOf`). A package's
compiled files have every reference written in full, so the adapter
resolves them in the project's namespace and still lands in the package's
(`library:gems/gem`); recipes are keyed by their own namespace
(`PaperRecipes.key`), resource packs are built per namespace (`Packs.packOf`).

- **Files.** `ProjectFiles` is the runtime's `source`: project paths for the
  project, package paths (`library:modules/greetings/init.lua`) for a
  package's, so chunk names, script errors and problems name a package's
  files that way and `KindSpec.fileOf("library:gem", …)` just works.
  `rootFolder` is the project's own folder (maps and structures
  are read from there).
- **Where packages come from.** On the editor's dev server
  (`RuntimeConfig.resolvesPackages`, true for `-Dnetherforge.project` and in
  tests) where `netherforge.json`'s paths point, without checking
  `netherforge.lock` (the editor keeps it). A production server
  (`config.yml`'s `project`) never resolves: a folder with
  `netherforge-bundle.json` is a bundle (each package in `<bundle>/<ns>/`,
  hashed with `ProjectFiles.hash` and refused on a mismatch,
  `runtime.bundle-hash`), and a project with dependencies that isn't one is
  refused (`runtime.unbundled`). Both are `loadProblems`, which `refusal`
  checks first.
- **`require`.** A bare name is in the caller's package (the owner's
  resource id says which: `library:greetings`'s bare `require("phrases")` is
  `library:phrases`); `require("acme:api")` needs the caller's package to
  depend on `acme` and `acme` to export `api` (`Modules.checkExported`, in
  the format's words), else an error naming the package. The module runs in
  its own scope, its own package's code. A package's modules start at load
  like the project's.
- **Everything else a script names or reads back**: see "Packages in a
  session" (`PackageNames`).
- **Resource packs.** A package's resource packs are built into the one resource pack whether
  or not the project has resource packs of its own (`Packs.rebuild` asks
  `everywhere(ResourcePackKind)`).

## Threading

Everything runs on the server's main thread. **Folia is unsupported**:
`Folia.detected()` looks for the class Folia's docs name
(`io.papermc.paper.threadedregions.RegionizedServer`), and the adapter's
`onEnable` logs `Folia.REFUSAL` and disables the plugin before anything else
(`paper-plugin.yml` also says `folia-supported: false`, so Folia itself
normally refuses to load it first). Work that mustn't hold the main thread
goes to the runtime's workers, and comes back through one of two queues;
nothing else makes a thread or an executor of its own (the bridge's socket
threads, the resource pack's HTTP server (which also reads and answers web
routes' requests), open streams and WebSockets (`OkHttpConnections`) and the network's link to its hub (`:network`'s `HubClient`, a reader and a writer) excepted: they block on sockets for their whole life,
which would starve the pool; and WebAssembly's `WasmThreads`, whose calls are computation that would hold up files and
sockets, and whose programs block on their input for their whole life: the plugin-capabilities skill's "WebAssembly"). All of it is in
`kotlin/.../async/`.

- **The Lua state is single-threaded, and checks.** `LuaHost` remembers the
  thread that made it (the main thread); every entry (`runFile`, `invoke`,
  `emit`, `ref`, `unref`, `pushValue`, `close`, ...) calls `owned` first and
  throws `WrongThread` ("LuaHost.invoke was called on thread X, but this Lua
  state belongs to thread Y...") for any other, rather than letting a second
  thread into the native state, which crashes the JVM. `ThreadingTest` calls
  it from another thread and gets the exception, the state still working.
- **`Workers`: one pool for the plugin's life** (`runtime.workers`, made in
  `enable`, closed in `disable` after the session stops, so writes it started
  finish). Four daemon threads (`Workers.THREADS`: the work is files and
  sockets), idle ones dropped after 30 s. Its **lanes** (`lane(name)`, one per
  name for the plugin's life: the standard serial executor over the pool) run
  their work one at a time in order: `"world files"` (copies and deletions),
  `"store"` (the store's connection: every read and committed batch, see
  "Persistence"), `"profiles"`, `"biome search"` (`world:locate_biome`: one search at
  a time, so a script asking for many can't hold up a database or HTTP). **`submit(on, work)`** runs work for a
  script as a `CompletableFuture`; at most `MAX_SCRIPT_WORK` (256) of those at
  once, past which the stage fails at once with `WorkFailed` (a script sees
  `nil, err`), so a loop can't queue without bound. The runtime's own lane work
  is never refused. Nothing a worker runs may touch Lua or anything else of
  the main thread's.
- **The pool is plugin-lifetime; what comes back is session-tagged.**
  `Completions` (`runtime.completions`, one queue for the plugin's life) takes
  entries tagged with the session's `generation` (`ProjectSession.generation`,
  counting in the JVM) and the scope they're for (null: the session's own).
  The running session's `AsyncWork` service drains it in
  `TickPhase.ASYNC` (after timers, before `nf.on("tick")`), until it's empty,
  so a step that completes the next stage carries on in the same tick. An
  entry for an ended session, or a released scope, is dropped unrun. A
  service carries on with its own work on `session.async.mainThread`
  (session-tagged, scope null); `ManagedWorlds` loads a copied world there.
- **`MainThread`** (`runtime.mainThread`) is for the runtime's own threads:
  the bridge's requests (`BridgeThread.MAIN`), its connect and shut-down,
  and failures the workers report. It runs at the start of
  `NetherForgeRuntime.tick()`, before the session's phases, because a request
  can reload the whole project and replace the session, which must never
  happen inside one session's tick. (`Platform` has no scheduler: the
  runtime ticks itself and hands work to itself. Paper's async chat event and
  a ping wait for the main thread inside the adapter, with Paper's
  `callSyncMethod`.)
- **Async API functions** (`async: true` in the spec, see lua-api) return a
  `CompletionStage` of their value; the generated primitive hands it to
  `Marshal.await` with the waker the prelude made, which becomes a `Pending`
  in `AsyncWork`: the scope it belongs to, the function to call and the
  value's codec. When the stage completes (any thread), the delivery is
  queued on `Completions` for that session and scope, and runs the waker as
  the scope's code with `value, nil`, or `nil, err` (a `WorkFailed`'s words;
  any other exception is logged and the script told it failed
  unexpectedly). Never during the call that started it. A wait is cancelled
  (waker unref'd, nothing delivered) when its scope is released, when its
  task ends (`Task:cancel`, or the scope's tasks dropped: the prelude's
  `end_task` calls the `async.cancel` primitive with the wait's id), and when
  the session stops. The work itself isn't stopped: what it did stands.
  `/nf scripts` counts each scope's `waits`.
- A bridge request whose method is marked `BridgeThread.BRIDGE` (`ping`,
  `profiler_subscribe`) is answered on the bridge's thread, touching only
  what's thread-safe; so are the debugger's DAP messages (`dap`
  notifications), which only queue for the main thread. While the debugger
  holds the main thread at a breakpoint, `MAIN` requests are refused at once
  rather than queued (plugin-sandbox skill, "The debugger").
  `RuntimeLog.bridge` is the only thing called from anywhere (it just
  queues).
- **Adding async work**: an implementation validates on the main thread
  (a mistake is a `LuaApiException` at once, before anything starts), then
  `session.async.workers.submit(lane or pool) { ... }`, then
  `.handleAsync({ value, failure -> ... }, session.async.mainThread)` for any
  main-thread step, throwing `WorkFailed("why")` for a failure the script
  should read. Return the stage; the binding does the rest.

## How to…

**Give the runtime a new server capability**: add a required group
interface in `Platform.kt` (no default), implement it in the fake
(`:plugin:testkit`) and every adapter, and give it a contract suite (see
"Testing" below) so the two can't disagree. Never import a Paper type
into the runtime. An event the server raises that only scripts hear is an
`nf` event with `raised` in the spec (lua-api), generated into `GameEvents`;
one the runtime's services act on goes in `PlatformEvents`. The fake delivers
them through `FakePlatform.events` (set by `TestServer`), the generated ones
through `FakePlatform.raise` (`GameEventDelivery`: a watched one only while
watched), the way the real server would, and tests may call them on
`server.runtime` (`server.runtime.game`) directly.

**Add an event or API function scripts can use**: the lua-api skill.

**Support a new Minecraft version**: the minecraft-versions skill
(`ServerInfo.supportedTargets` is what refuses other targets).

**Change what a reload does**: the hot-reload skill.

## Testing

- **Script tests** (`nf.test`, W1.1): `RuntimeConfig.testing` (a
  `testing/TestHarness`: `advance(ticks)` and `join(name)`, which the runner in
  `:plugin:test-runner` implements over a `FakePlatform`) turns the runtime into
  a test run. Only then does the prelude leave `nf.test` in a scope's `nf`
  (`prim.testing()`, `scopes.lua` takes every `schema.test_only` namespace out
  otherwise), and `NfTestImpl` refuses without a harness as a second lock.
  `testing/ScriptTests` is the `RuntimeService` that runs a test file in a
  `ScopeOwner.TestScript` scope (no resource: `ScopeOwner.resource` is null; it
  `require`s like a script beside its folder, so a test reaches the project's
  modules and the files beside it), collects its `nf.test.case`s while the body
  runs, and `run(name)` calls one as the file's code. **`advance` is
  synchronous and nested**: the test's Lua call asks Kotlin to run whole ticks
  (`runtime.settled`: workers idle and WebAssembly settled; `runtime.tick()`, the fake's later-queue, `tickWorld()`), and
  handlers run inside it as they do for `nf.emit`; the runner raises the call's
  time limit (`SandboxLimits.deadlineMillis`), since it includes every tick.
  `nf.test.raise` emits along the path `RaisableEvents` (generated from the
  event registry) gives: the handle in `raised.first`, then `nf`. A run never
  reloads: each test is a fresh runtime. See the testing skill.
- `FakePlatform` and its groups live in `:plugin:testkit`
  (`apps/plugin/testkit`, package `dev.netherforge.plugin.testkit`), main
  sources, so a runner for a project's own script tests can use them; the
  runtime's tests depend on it. The **Platform contract suites** (runtime test
  fixtures, one per `Ops` interface; see the testing skill) run against it and
  against `PaperPlatform` inside a real server, so it can't drift from Paper:
  where they disagree, Paper is the truth and the fake is fixed.
  **Its files**, one per `Ops` area, each group a top-level class taking the
  platform (`FakeWorlds(platform)`), never an inner class:
  `FakePlatform.kt` (the groups, `bind`, `raise`, `stack`, `tickWorld`, the
  `GAME` fixture), `FakeWorlds.kt` (`BlockAt`, worlds, blocks),
  `FakeEntities.kt` (NetherForge's displays and hitboxes, structure markers),
  `FakeWorldEntities.kt` (`FakeBody`, vanilla entities), `FakePlayers.kt`
  (`FakePlayer`, players), `FakeInventories.kt`, `FakeScoreboards.kt` (boss
  bars, sidebars, teams, the player list), `FakeMenus.kt` (`FakeWindow`,
  menus, dialogs), `FakeCommands.kt` (with `FakeBrigadier.kt`),
  `FakeEffects.kt` (particles, sounds, resource packs), `FakeItems.kt`
  (project items, recipes, loot), `FakeServer.kt` (scheduler, log,
  performance, text, the start-up datapack, pause), and beside them
  `FakeMobs.kt`, `FakeWorldAdmin.kt`, `FakePlayerAdmin.kt`, `FakeInterop.kt`
  and `FakeBots.kt`. A new group goes in its area's file.
- **The fake bots' client**: each bot online has one (`FakeBots`), as a real
  bot does, so the contract suites can check what a player receives on both
  servers: its numbered events (`FakeBots.sent(player) { seq -> BotEvent }`,
  called where Paper would send the packet: sounds and stops, particles,
  boss bars shown and hidden, resource packs) and, in `state`, its boss bars,
  sidebar, packs and player list read off the fake's own groups as plain
  text. What it receives follows Paper's rules (a particle within 32 blocks,
  512 forced, of a particle the game has; a world's sound within 16 blocks,
  times the volume above 1), so a runtime test reading the fake's state reads
  what a player would.
- `TestServer` (runtime tests) writes a project to a temp dir and runs a
  `NetherForgeRuntime` on `FakePlatform` (its services are
  `server.runtime.session.x`, the server's events `server.runtime.events.x`), which records entities, poses,
  messages and commands. `server.logs` are script `log()` lines;
  `server.errors` are `ScriptError`s. `server.tick()` first waits for the
  runtime to settle (`runtime.settled`: its workers idle and WebAssembly's calls done; so work a script started off the main
  thread lands on a known tick, through `Completions` in that tick's
  `ASYNC` phase), then ticks, then runs the fake's own later-queue
  (`platform.scheduler`: a bot's join) and the mobs' AI; `server.runMain()`
  runs what the bridge handed the main thread without a tick. The pool,
  lanes, the thread check and completions are `ThreadingTest`'s. Lua behaviour is tested with real
  scripts that check themselves with `LuaChecks` (`check`, `near`, `fails`, then `done`; see the testing skill): see `SandboxTest`, `CentityTest`, `ModuleTest`, `ReloadTest`, and
  `ServerEventTest` for the server events, and
  `EventTest` for the event core (event objects, custom events, lifetimes,
  the error policy). The fake raises what a server would: `menus.click`
  and `drag`, `dialogs.press` and `escape` (a notice's button, a
  confirmation's second, or the exit action). A package is files at
  `../<name>/…` in the map (`TestServer.example` adds the ones an example
  depends on); `resolvesPackages = false` is a production server, and
  `PackagesTest` builds a bundle by hand.
- The integration scenarios (`apps/plugin/integration`, `pnpm test:integration`;
  see the testing skill) boot each adapter's jar on its own headless Paper
  with `examples/showcase` (and `examples/library` beside it) and play the editor
  over the bridge, including a restart. They also fetch the built resource
  pack over HTTP and check its SHA-1, rebuild it, and drop the example crate
  onto the real ground.
- `FakePlatform` also fakes windows (`menus.click`, viewers, close
  events), dialogs (`dialogs.press`), resource pack sends, and a world whose ground is
  solid below `worlds.groundBelow` (plus `worlds.solid` blocks) for physics.
  Blocks scripts set land in `worlds.blocks` (and `blocks.changes`);
  `worlds.unloaded` chunks can't be read; `players.move`, `players.chat` and
  a player's teleport raise what Paper would (moves and chat only while
  `watched`), `players.quit` leaves as a quit does (an open window closes,
  heard; the runtime hears the quit; what the server keeps only while
  they're online goes), a kick is a quit, `bots.join` makes a player who
  answers resource packs, deaths record `drops` and `playerDeaths`; `particles.sent` and
  `sounds.played` record what reached players. `WorldTest` is the model.
  The integration test also runs the world API on the real server (a block,
  rays at a block and a hitbox, a particle, a sound, time and weather).
- Wall time: `TestServer(wallClock = <a java.time.Clock the test moves>, scheduleZone = "UTC")` sets what `nf.schedule` and `nf.server.unix_time()` read and the owner's zone (`ScheduleTest`; the machine's own zone otherwise, so always pass one).
- Costs: `TestServer(clock = ...)` replaces the Lua state's clock; a clock
  that moves 1 ms per read makes every call in cost exactly 1 ms of its own
  (`ScriptCostsTest`). `performance = PerformanceConfig(...)` sets the warning
  limit; slow warnings land in `platform.log.lines`. That clock is also the
  time limit's, so a stepping clock and `sandbox = SandboxLimits(deadlineMillis
= 5)` stop a call deterministically; a small `memoryMegabytes` (32) with
  `memoryEvery = 1` makes the memory checks testable in a few megabytes
  (`SandboxLimitsTest`). A test that could hang if a guard regressed gets a
  JUnit `@Timeout` and a worst case that can't take the machine down (the
  doubling test stops at 2^30 bytes): one that allocated without bound once
  pushed the test JVM into the OOM killer.
- Commands on the fake: `commands.run(player, line)` and
  `commands.runAsConsole(line)` return what the sender was told,
  `commands.complete(player, line)` what tab completion offers (only what the
  player has the permissions for, so a test completing `/nf` grants
  `netherforge.admin`). Lines are read by `FakeBrigadier`, as Brigadier reads
  them on Paper. Its selectors are `@a`, `@e` (players only), `@p`, `@r` and
  `@s`; item text with components reads from `commands.items`. `CommandTest`
  is the model.

```sh
node tools/gradle.mjs :plugin:runtime:test
node tools/gradle.mjs :plugin:paper-26.3:assemble       # NetherForge-<version>-paper-26.3.jar and its bots in build/libs
node tools/gradle.mjs :plugin:paper-26.3:runServer      # a local server running examples/showcase, by hand
pnpm test:integration                                   # every scenario on every adapter
```
