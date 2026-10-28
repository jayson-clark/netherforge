---
name: hot-reload
description: The dev bridge between the NetherForge editor and the plugin on its dev server (JSON-RPC 2.0 over NDJSON, the hello with its token and protocol version, requests, notifications and batched streams, threads, the bots extension, the relay and vscode-jsonrpc client in the editor) and what a save reloads (per-resource semantics, reattaching live centities, restarting modules and their dependents, problems, script errors with file:line). Read before touching format's bridge/, the plugin's bridge or reload code, or the editor's bridge client.
---

# Hot reload and the dev bridge

## The loop

1. The user saves; the editor writes the file through `format`'s canonical
   writer.
2. The editor sends `reload` with the saved paths.
3. The plugin maps each path to the resource that owns it and reloads each
   resource once, then answers with a `ReloadResult`: each resource by
   `{ package, kind, id }` (the package is the project's namespace until
   dependencies reload as their own).
4. Along the way it sends `problems` (the whole project's, replacing the
   last set) and, on the `console` stream, `script_error`s located at
   `file:line` and `log` lines.

## Transport

- **The editor listens** on `127.0.0.1:<port>`, then starts the server with
  `-Dnetherforge.project=<project dir>`, `-Dnetherforge.bridge.port=<port>` and
  the environment variable `NETHERFORGE_BRIDGE_TOKEN`. Constants are in
  `packages/format/.../bridge/Bridge.kt` (`PROJECT_PROPERTY`, `PORT_PROPERTY`,
  `TOKEN_ENV`).
- **Frames are JSON-RPC 2.0**, one message (or batch) per line (NDJSON):
  requests with an `id` get exactly one response (`result`, or `error` with a
  `code` and a `message`), notifications have no `id` and are never answered.
  Unknown keys in params are ignored, so an optional field one side added
  doesn't break the other.
- **The hello**: the plugin connects when it enables and sends the `hello`
  request first: the token, `protocol` (`Bridge.PROTOCOL`), its version, the
  server's Minecraft version and the project's absolute path. Nothing else
  goes out until the editor answers. A wrong token (or a first frame that
  isn't a hello) is closed without a word; another protocol version is
  answered with `PROTOCOL_MISMATCH` (-32001) and a sentence naming both
  versions, which the plugin logs as an error and the editor's console shows,
  and the plugin stops trying (it won't get better by retrying). **Bump
  `PROTOCOL`** (format, and `PROTOCOL` in `protocol.rs`, which the recorded
  session ties together) for any change one side can't read from the other.
- **Frame size**: every frame is capped on both ends, so a peer can't make
  the other buffer an endless line. The editor reads at most 16 KiB before
  the hello and 64 MiB after (`bridge::frames::FrameReader`; the game data
  export is the largest frame, about 7 MB); the plugin reads at most 16 KiB
  before the hello's answer and 16 MiB after (`FrameReader.kt`). A longer
  frame closes the connection and logs why (the editor's console, or the
  server's log); the plugin reconnects like after any drop. Keep new
  requests and results well under these.
- **Errors**: an unknown method is JSON-RPC's `-32601` (`Unknown method "x"`),
  params that don't read as the method's type `-32602`, a request the plugin
  understood and couldn't do `REQUEST_FAILED` (-32000) with the sentence the
  editor shows (`no player "Steve" online`), an unexpected exception `-32603`
  (and a log line). A line that isn't JSON is answered `-32700` with a null id.
- **Threads**: frames arrive on the bridge's thread; a request runs on the
  thread its method declares (`BridgeThread`): the main thread by default,
  `BRIDGE` for control requests that must answer while the main thread is
  busy or stopped (`ping`, `profiler_subscribe`; the debugger's `dap`
  notifications too). A `BRIDGE` handler may only touch what's safe from
  any thread. While the debugger holds the main thread at a breakpoint, a
  `MAIN` request is answered at once with `REQUEST_FAILED` ("the dev server is
  paused at a breakpoint (file:line); continue it in the editor first")
  rather than left waiting, and when the connection ends the debugger
  detaches, so a server is never left paused by an editor that's gone. Most
  requests are answered at once; `bots/join` and `bots/act`
  when the bot has finished, so keep a bot request's work under the editor's
  30 second timeout (`BotAction.MAX_TICKS`).
- **Streams** (`BridgeStream`): high-rate data as notifications carrying a
  batch, `{"items": [...]}`. The plugin's writer coalesces: an item joins the
  batch at the end of the outgoing queue, so a burst of log lines is a few
  frames, in order with everything else. The console (`log` and
  `script_error` entries) and `profiler` (the profiler's samples, a batch of
  twenty ticks a second while the editor subscribes) are the streams. A
  stream is fed through `RuntimeLog.stream` (`BridgeOutput.stream`).
- **Extensions**: requests only some servers answer live under their own
  namespace (`BridgeExtension`): `bots/…`, installed by `BotsBridge` only
  where the platform has bots. A server without one answers its methods
  `-32601`, which the editor reports as `unknownMethod`.
- **Reconnects**: a dropped connection is retried with backoff (250 ms
  doubling to 5 s). Frames sent while disconnected are queued (bounded: 10 000
  frames or stream items, the oldest dropped) and delivered after the next
  accepted hello, so startup logs aren't lost; on every connect the plugin
  also resends the current `problems` and `status`.
- **Abandoned servers stop themselves**: every editor run listens on a fresh
  port, so a dev server whose editor quit, crashed or was killed can never
  reconnect. After `BridgeConfig.abandonAfterMillis` (15 s) without a
  connection, counted from the last one ending or from startup, the plugin
  calls `Platform.shutdownServer`, which on Paper is a clean `/stop`.
- The bridge exists only when `netherforge.bridge.port` is set. A production
  server never opens it.
- **Why a hand-written codec** (`JsonRpc.kt`): format is Kotlin
  Multiplatform common code, and no maintained JSON-RPC library is both
  multiplatform and transport-agnostic (LSP4J is JVM-only, Gson-based and
  framed with `Content-Length`; kotlinx-rpc speaks its own protocol). The spec
  is small and `JsonRpc` is all of it, batches included (answered with one
  array once every request in it is). The editor's side is the standard
  `vscode-jsonrpc`; Rust reads only the hello and its own answers.

## Messages

Declared once, in `Bridge.kt` (and `BotsExtension` in `Bots.kt`): each
method's name, params type, result type and thread. The editor's
`BridgeRequests` and `BridgeEvents` types are generated from them.

| Direction | Method                    | Notes                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| --------- | ------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| → editor  | `hello` (request)         | first frame; `HelloParams`, answered `{protocol}` or refused                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| → editor  | `problems`                | on load, every reload, every connect; the full set                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| → editor  | `console` (stream)        | `log` entries (runtime lines, and script `log()`/`print()` with `source` = where it was called) and `script_error` entries (`message` names the script and what failed; `source` is the file and line; `traceback`), batched                                                                                                                                                                                                                                                                              |
| → editor  | `status`                  | online players and instance count, on change, at most once a second                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| → plugin  | `reload`                  | result: `ReloadResult`, each resource as `{ package, kind, id }` (a whole package: `package` alone)                                                                                                                                                                                                                                                                                                                                                                                                       |
| → plugin  | `spawn`                   | in front of the named player, else the first online, else the default world's spawn; result: `InstanceInfo`                                                                                                                                                                                                                                                                                                                                                                                               |
| → plugin  | `instances`               | result: `InstanceInfo[]` (including inert ones whose centity is missing)                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| → plugin  | `export_game_data`        | result: `GameDataBundle` from the live registries; takes a moment, do it once per version (the editor's backend sends it itself)                                                                                                                                                                                                                                                                                                                                                                          |
| → plugin  | `command`                 | runs a console command; fails when the server didn't run it; no result                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| → plugin  | `play_particle_effect`    | `effect`, `player?`, `loop?`: 3 blocks in front of the player's eyes, facing them (else the first online, else the world spawn); owned by the runtime; no result                                                                                                                                                                                                                                                                                                                                          |
| → plugin  | `stop_particle_effects`   | ends every effect `play_particle_effect` started; no result                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| → plugin  | `player_position`         | where `player` (else the first online) stands, floored to the block; result: `PlayerPosition`; fails with nobody online                                                                                                                                                                                                                                                                                                                                                                                   |
| → plugin  | `worlds`                  | the loaded worlds, the main one first; result: `LoadedWorld[]`                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| → plugin  | `save_structure`          | the box `from`–`to` in `world` (`entities?`) saved through `StructureStore.capture` to a temp file in the data folder, read and deleted; result: `SavedStructure` (base64 `nbt`, `size`). Same 48-block limit as `world:save_structure`. The editor writes `structures/<id>.nbt`                                                                                                                                                                                                                          |
| → plugin  | `save_world`              | `World.save(true)`, then where its files are relative to the server's folder (`BridgeConfig.serverDirectory`, the process's working directory): the storage's `level.dat` and the world's dimension folder, plus its spawn; result: `SavedWorld`                                                                                                                                                                                                                                                          |
| → plugin  | `settings`                | every package's server-owner settings with the values the server runs (`ServerSettings`: per `PackageSettings`, each `SettingState` with `value`, `set`, `problem`)                                                                                                                                                                                                                                                                                                                                       |
| → plugin  | `set_setting`             | `{ namespace, setting, value? }`: what `/nf settings set` does (a missing `value` resets); fails with why a value doesn't fit; answers `ServerSettings`. Listeners hear `setting_changed`; scripts that only read it restart                                                                                                                                                                                                                                                                              |
| → editor  | `settings_changed`        | every package's `ServerSettings`, whenever one changed (from anywhere) or the project restarted; the editor's Settings › Server-owner settings page follows it                                                                                                                                                                                                                                                                                                                                            |
| → plugin  | `ping`                    | `BRIDGE` thread: answered at once even while the main thread is busy; no result                                                                                                                                                                                                                                                                                                                                                                                                                           |
| both ways | `dap`                     | the debugger's channel: each notification is one Debug Adapter Protocol message (request, response or event) as DAP defines it; the plugin is the adapter (`Debugger`, lsp4j) and reads them on the bridge's thread, so they're heard while a breakpoint holds the main thread. Sent only to a connected editor (a DAP session is the connection's; nothing is queued for the next). The editor's only notification to the plugin (`Bridge.notifications`). See the plugin-runtime skill's "The debugger" |
| → plugin  | `profiler_subscribe`      | `BRIDGE` thread: `{ on }` starts or stops the `profiler` stream (it only sets a volatile flag); the editor sends it whenever the bridge comes up; no result                                                                                                                                                                                                                                                                                                                                               |
| → editor  | `profiler` (stream)       | `ProfileSample`s: twenty `ProfileTick`s (each step of the tick by name, scripts' own time, the top scopes), every `ScopeTime` and `HandlerTime` over them                                                                                                                                                                                                                                                                                                                                                 |
| → plugin  | `bots/join`               | extension. A fake player joins, through the configuration phase (`at?`, `resourcePack?`); answered once it's in the world, ticks later; result: `BotInfo`                                                                                                                                                                                                                                                                                                                                                 |
| → plugin  | `bots/act`                | extension. A bot does one `BotAction` as a client would; answered when done (walking, mining: ticks later); the editor's MCP `bot_act` lists the types (`tools.ts`), a new `BotAction` goes there too; result: `BotActResult`                                                                                                                                                                                                                                                                             |
| → plugin  | `bots/state`              | extension. What a bot's screen shows; result: `BotState`                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| → plugin  | `bots/events`             | extension. What a bot was sent, from sequence number `since`; result: `BotEvents`                                                                                                                                                                                                                                                                                                                                                                                                                         |
| → plugin  | `bots/list`, `bots/leave` | extension. The bots online (`BotInfo[]`); disconnecting one (no result)                                                                                                                                                                                                                                                                                                                                                                                                                                   |

## What a path reloads

A path is mapped to its resource by format's `Kinds.classify` (the
project-format skill), as a kind and an id (`project/Resource.kt`; the
console writes it `<kind>:<id>`):
`centities/<id>/…` → `centity:<id>`, `recipes/<id>.json` → `recipe:<id>`,
`resource_packs/<id>/…` → `resource_pack:<id>`, `maps/<id>/…` → `map:<id>`, and so
on for every kind in the registry; `netherforge.json` → the whole package, and so
is `netherforge.lock` (the editor rewrites it when a dependency changed, and
sends it: packages are only ever reloaded whole, with everything else);
`fonts/default.json` → nothing, but open skinned windows are retitled;
anything else (README, `.netherforge/…`) → nothing. A batch reloads kind by
kind in the registry's order (`Kinds.all`): resource packs first (menus' skins and
scripts' glyphs read the new characters), then particle effects and cutscenes (data the
restarting scripts may play at once), modules, structures, maps,
items (the looks menus' slots take), recipes (built from those looks), loot tables, blocks, biomes, dimension types,
terrains, advancements, menus, dialogs, centities. Each kind is reloaded by the one session service that
lists it in `RuntimeService.reloads` (checked against the registry when the
session is built, so a new kind without one fails at startup rather than
reloading nothing), with the helpers on `ReloadBatch`; services that
`follow` a kind (recipes follow items, menus' titles follow resource packs and
`fonts/default.json`) hear right after its turn. A module that reloads adds what required it (its
scopes' `ScopeOwner.resource`) to the batch, for their kind's turn. The whole
project is re-read and re-validated on every reload (it's cheap), so
`problems` is always complete.

Every centity, menu and dialog has at most one script (no node, slot or button scripts), so
a save of anything in a resource's folder (its JSON, its script, or a `.lua`
file beside the script) reloads that resource, and that reruns its one
script: the old scope unloads (its `nf.on("unload")` handlers run,
everything it registered goes, and with it the scope's copies of the files
beside the script) and the body runs again. Every `.lua` file under the
folder counts, whether or not a scope required it: simpler and more
predictable than tracking which files each scope loaded. Adding or deleting
one is the same (the file list `require` reads is refreshed by every
reload). There's no "which node scripts restart" any more. The plugin has no
file watcher of its own: reloads come from the bridge (`reload` with
paths) or `/nf reload`.

| Resource               | What happens                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| ---------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `centity:<id>`         | Live instances' script unloads (its `unload` handlers run); instances move onto the new definition (entities reconciled by node name: kept, updated, respawned if their kind changed, removed, added); the script starts again on each (body runs, no `spawn`). Nodes a script added are dropped with their handlers (the script's body adds them again). Handlers other scripts put on the instance stay, except on nodes the new definition lacks. `reattached` counts them.                                                                                                                                                                                                                                                                                                                                                                              |
| …with errors           | The last good definition keeps running; `ok: false` with the problems.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| …deleted               | Instances become inert: the script stops, entities stay, they come back if the centity does.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `module:<id>`          | `unload` handlers run; its handlers, timers, `nf.schedule`s and commands go; its files are forgotten; `init.lua` runs again. Every module that required it is reloaded too (in the result), and every centity, menu and dialog whose script (or a file beside it) required it.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `menu:<id>`            | Every open window of it: same container and size → **kept** (viewers stay; contents stay except slots whose authored item changed, which take the new item; title/skin/lock follow the file where it changed them); a new shape → rebuilt from the file and reopened for its viewers; the menu's script restarts on each window. `reattached` = windows kept. Errors: last good version keeps running. Deleted: its windows close.                                                                                                                                                                                                                                                                                                                                                                                                                          |
| `dialog:<id>`          | Its script restarts on the new file; the next show draws it. A screen already open keeps what it showed; a press for a button the new version lacks is ignored (and handlers on that button go). Deleted: the script stops.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `item:<id>`            | The item's look changes for every stack built from now on, and every online player's inventory is checked: stacks stamped with the old look are rewritten to the new one (keeping count, damage, script data and other plugins' data; `reattached` = players whose stacks changed). Its script restarts. Errors: the last good definition stays. Deleted: its script stops and its handlers go; stacks of it keep the look they have.                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `recipe:<id>`          | The server forgets the old recipe and learns the new one; everyone online is sent the recipe list once, at the next tick. Errors: the last good version stays. Deleted: the recipe goes. Recipes naming an item are built again when that item reloads (the server matches a project item by its kind). Players' recipe books are the server's to keep.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `particle_effect:<id>` | Every live effect of it takes the new definition at its next tick (`EffectSampler.redefine`): its tick carries over, a non-looping one past the new `duration` ends `finished`, a looping one wraps, same-named emitters keep their rate accumulators. `reattached` = live effects moved. Errors: the last good definition keeps playing (`play` of one that never had one is an error naming the problem). Deleted: live effects end `removed`. A script's resource reloading ends that scope's effects `unloaded`.                                                                                                                                                                                                                                                                                                                                        |
| `cutscene:<id>`        | The next play takes the new definition; ones playing keep the one they started with (a player is never moved mid-camera by a save). Errors: the last good definition stays for `play` (one that never had one is an error naming the problem). Deleted: `play` of it is an error, ones playing finish. A script's resource reloading ends that scope's cutscenes `unloaded`, which puts their players back before its `end` handlers are heard.                                                                                                                                                                                                                                                                                                                                                                                                             |
| `terrain:<id>`         | Nothing restarts and nothing live changes: the adapter is handed the new compiled generators (every one, again) and chunks generated from then on use them; chunks already made keep what they have (a world explored before the save has a seam). Errors: the last good generator stays published for worlds that have it. Deleted: worlds keep generating with the last one they had; `nf.worlds.create` refuses the id. A block reload publishes them again too (an ore's state moves with the blocks). Its script (`terrain/<id>.lua`, the companion file) reloads it the same way, and so does a saved module when any generator has a script (scripts may `require` modules, read as they're published); the old generator's Lua states are closed as their chunks finish. A script that doesn't load is `terrain.script-failed` at its line at once. |
| `dimension_type:<id>`  | Nothing live changes: the server learns dimension types only as it starts, from the start-up datapack (and the main world's, `netherforge.json`'s `worlds.<main>.dimensionType`, as the replaced `minecraft:overworld`), so a change, a new or deleted type, or the manifest naming another for the main world sets `restart` (`StartupDatapackCheck`, built with the same main world name the bootstrap used), and the dev server restarts. `ManagedWorlds` reloads the kind as data. A world keeps the type it was made with; one made with a type the server doesn't have (now) isn't made or loaded (a Lua error, or `runtime.dimension-type`).                                                                                                                                                                                                         |
| `biome:<id>`           | Nothing live changes: the server learns biomes only as it starts, from the start-up datapack, so a change (or a new or deleted biome) sets `restart` like an advancement's (`StartupDatapackCheck`), and the dev server restarts. The terrains are published again (`WorldGenerators` reloads the kind) only to say, as `runtime.terrain` at the generator, when an area's project biome has errors: such a biome is out of the datapack and its areas are plains until it's fixed and the server restarted. A world keeps the biomes it was made with until it loads again.                                                                                                                                                                                                                                                                                |
| `datapack:<id>`        | Nothing live changes, like a biome: any file of `datapacks/<id>/` reloads the resource (its other files are part of it, `KindSpec.readsFile`), and since its files for the server's format are in the start-up datapack, a change sets `restart` (`StartupDatapackCheck`) and the dev server restarts. A server that refused the datapacks as it last started and runs without them (`DatapackOps.refused`) compares against the resource pack without them, so it asks for a restart only once a file changes.                                                                                                                                                                                                                                                                                                                                             |
| `resource_pack:<id>`   | The one resource pack (all resource packs) is rebuilt; if its SHA-1 changed it's sent to everyone online, and open skinned windows are retitled. `pack` = `PackBuild(sha1, url, bytes)`; `reattached` = players sent to. A resource pack with errors keeps the last good build.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `structure:<id>`       | The adapter forgets what it read of the file, so the next `place_structure` reads it again. Nothing runs on a structure, so nothing restarts; `ok` is whether format found an error in it. `structures/<id>.json` (the generation file) reloads as the same `structure:<id>` resource (`Resource.owns` includes it, so its problems are its resource's), and a structure that generates, or one a generating structure's pools pick, is in the start-up datapack: its change sets `restart` like an advancement's (`StartupDatapackCheck`), while the markers of the structure's centities (`StructureSpawns`) look again at every loaded marker after a centity reload. Structures scripts saved (`world:save_structure`) live in the data folder, not the project, and never reload.                                                                      |
| `map:<id>`             | Nothing: a map is read only when `nf.worlds.copy` copies it, so a change affects later copies only, never a world already copied. Worlds scripts made, and the copies in progress, are untouched by a resource's reload (a script that stops before its copy finishes isn't called back; the world is made anyway). A full reload during a copy leaves its files saved and the project's, but unloaded (loading was the old session's step): `nf.worlds.load` loads it.                                                                                                                                                                                                                                                                                                                                                                                     |
| `migration:<id>`       | Nothing restarts. The package's database (`nf.db()`) is migrated again: a migration file that's new is applied now (in number order, each once, in its own transaction), so a script's next `db:query` sees the new schema. One the database has already had is **not** run again: editing it changes nothing and deleting it undoes nothing (migrations only go forward; to start a dev database over, stop the server and delete `plugins/NetherForge/databases/<ns>.db`). A migration that fails, or files with problems (a gap, a duplicate, a bad name), leave that package's database unavailable (`db:query` answers `nil, err`) and carry `migration.failed` on the file; fixing the file migrates it on the next reload, scripts untouched. Other packages' databases are separate files and unaffected.                                           |
| the package            | Everything stops and starts again on a fresh Lua state; spawned centities reattach. Also what `/nf reload` with no paths does.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |

Saved tables (`centity:data()`, `player:data()`, `nf.data(name)`) live in
memory and are never re-read by a reload: a restarted script finds them as
they were. Every reload ends by saving the tables that changed; a whole-package
reload saves them before the Lua state closes and the new state's tables
start from that text (functions and other values that can't be saved don't
make it across that one).

A script that fails while its resource reloads (syntax error, error in its
body) makes that resource `ok: false` with a `script.error` problem at the
file and line, and is reported as a `script_error`; the script is disabled
until its resource reloads again. A handler, timer or command that fails at
any other time is a `script_error` (rate-limited) and a `script.error` in
`problems` until its scope closes; the script keeps running. A script over
`performance.warn-ms` a tick (plugin-runtime skill, "Time per scope") is a
`script_error` naming its slowest handler's file and line, and a
`script.slow` **warning** problem (one per scope, identical ones shown once)
until its scope closes. Costs travel on the `profiler` stream (the
plugin-sandbox skill's "The profiler"), once a second, not as problems. `problems` is
sent again when one appears, and after every reload.

A project the plugin can't run (no manifest, a format version other than
this build's, a Minecraft version the adapter isn't for) is refused:
`problems` says why (`project.format-version`, naming
the project's version; `runtime.minecraft-unsupported` for the Minecraft
version), the console logs the same sentence, nothing runs, spawned centities
are kept, and the next reload of anything tries again. Formats are never
migrated. The editor refuses to open such a project at all (format's
`projectRefusal`).

**What needs a restart.** What the server learns only as it starts (the
start-up datapack: advancements, the dialogs of the pause screen and quick
actions, generating structures and biomes) can't be reloaded. `ReloadResult.restart` is
true while the datapack built from the files differs from the one the server
started with (`StartupDatapackCheck`, also the `runtime.restart` warning in
the problems and `/nf reload`'s message); the resource itself still reports
`ok`. The editor's run store restarts the dev server on it
(`run.restart()`, from the providers' `reloaded` hook), and so does the MCP
`reload` tool (`restarted: true`).

## Plugin half

`apps/plugin/runtime/.../bridge/BridgeClient.kt` (socket, threads, the hello
handshake, the outgoing queue with stream coalescing, reconnect),
`BridgeService.kt` (dispatch by method and thread, every request answered
once, the core protocol's handlers), `BotsBridge.kt` (the bots extension,
self-contained),
`BridgeOutput` (what `RuntimeLog` sends: console entries and notifications),
`NetherForgeRuntime.reload`/`reloadAll` (a whole-package reload disposes
the session and builds a new one) and `ProjectSession.reload` (the
semantics above).

**Adding a request**: declare a `BridgeMethod` in `Bridge.kt` (its params
and result types; `Unit` for none; `BridgeThread.BRIDGE` only for a control
request that must not wait for the main thread) and list it in
`Bridge.requests` (or in an extension's list, under its namespace), add lines
to `packages/format/testdata/bridge/session.ndjson` (which every side reads),
handle it with `BridgeService.answer` (or `handle`, for one answered later),
and test it in the plugin's `BridgeTest`. The editor's `BridgeRequests` type
follows from the generated contract, Rust passes the frames through
untouched, and the memory backend needs a case. **Adding a notification** is
a `BridgeEvent` (or, for high-rate data, a `BridgeStream`) in `Bridge.events`
(`Bridge.streams`), sent through `RuntimeLog.notify` (`BridgeOutput.console`
for the console), and heard in the editor with `onBridgeEvent`. Adding
optional fields is compatible; changing or removing them bumps
`Bridge.PROTOCOL`.

## Editor half

**Rust side** (`apps/editor/src-tauri/src/bridge/`, `server/`): a relay.

A dev network's other servers (tauri-backend's "Dev networks") each connect to
a bridge of the backend's own, not the UI's: the backend sends them `reload`
itself (its own request ids, with params), from a watcher on each one's
project, and writes the result into that server's console.

- Listens on `127.0.0.1:0` _before_ launching the server and passes the port
  as `-Dnetherforge.bridge.port`, the token as `NETHERFORGE_BRIDGE_TOKEN`. The
  hello must arrive within 10 s with the right token or the socket is closed;
  one of another protocol is refused (the sentence goes to the console as a
  `[NetherForge editor]` line); a newer connection replaces an older one.
- `bridge_send(message)` writes one frame from the UI (checked to be JSON,
  made one line); every frame from the plugin is emitted as
  `bridge://message { message }`, the text untouched. `protocol.rs` reads the
  hello and the answers to the backend's own requests, nothing else.
- On hello, if `<app data>/NetherForge/minecraft/<version>/server/game-data.json`
  is missing, it requests `export_game_data` itself (id `"backend:<n>"`, 5
  minutes to answer; those answers never reach the UI), stores the result and
  emits `mc://cache-changed`.

**UI side** (`apps/editor/src/core/bridge/client.ts`, `core/store/workspace.ts`,
`core/store/run.ts`):

- `BridgeClient` is vscode-jsonrpc over the relay: `backend.bridgeRequest(method,
params)` is typed by the generated `BridgeRequests`, times out after 30 s,
  and rejects with a `BackendError` (`unknownMethod` for `-32601`, `plugin`
  for the plugin's failures, `notConnected` once the bridge is down: the
  connection is disposed when `bridgeConnected` goes false, failing what
  waits, and a fresh one starts with the next frame).
  `backend.onBridgeEvent(event, fn)` is typed by `BridgeEvents`; there is no
  switch over message types anywhere.
- The debugger's DAP messages go out through `backend.dapSend` (a `dap`
  notification, `BridgeClient.notify`) and come back through
  `onBridgeEvent('dap')`; `core/debug/client.ts` pairs requests with
  responses (see the editor-lua skill's "The debugger").
- On save: write through format, then, if the bridge is connected, send
  `reload` with the saved path, one request per saved file. While the
  debugger has the server paused the paths wait (`RunHooks.holdReload`) and
  are sent once it runs again. Creating a file
  sends its path; a rename sends every file it moved, at the old and the
  new path; a delete sends every file that was there (a whole resource
  folder: the server finds it gone). The
  `ReloadResult` goes to the Console ("Reloaded centity:tower (2 live)", or
  a warning when a resource kept its old version).
- `console` batches go to the Console, a ring buffer (`ConsoleBuffer`, 2000
  lines) the run store mutates in place, bumping `consoleVersion` once per
  batch; `source` becomes a clickable `file:line` that opens the script
  there, and a script error brings the Console forward.
- `problems` replaces the "server" problem list shown alongside the editor's
  own; `status` feeds the player count and refreshes Instances.
- When the bridge comes up, or `mc://cache-changed` fires, the UI re-fetches
  game data and revalidates.
- Files changed outside the editor (git, VS Code) arrive through
  `fs://changed` only; the editor doesn't send `reload` for files it didn't
  write.

What the plugin expects of the editor:

- Listen before starting the server; accept one connection; answer the
  hello (and nothing before it); expect a reconnect (with a fresh hello) if
  the server restarts.
- Launch with the plugin jar `NetherForge-<version>-paper-<minecraft>.jar` (and its bots) in
  the server folder's `plugins/` (`<data>/servers/<hash>/`, never inside
  the project; see the tauri-backend skill), Java 25, and
  `--enable-native-access=ALL-UNNAMED` (the plugin loads Lua's native
  library; without it Java prints a warning).
- Send paths relative to the project root with `/`.
- Treat a `reload` response as the outcome, and `problems` as the current
  truth for the whole project.

## Testing

- `format`: `BridgeTest` round-trips `packages/format/testdata/bridge/session.ndjson`
  on JVM and JS, reads every recorded message as its declared type, and
  checks the codec's answers to what isn't a message.
- `apps/plugin/runtime`: `BridgeTest` plays the editor over a real socket against
  the fake platform (the hello and a refused one, every request, unknown
  methods and bad params, batches, `ping` with the main thread held, a burst
  of log lines arriving in a few batches); `ReloadTest` covers every row of
  the table above.
- Rust: `protocol.rs` reads the session's hellos and its own export;
  `bridge/mod.rs` relays over real sockets.
- The editor: `core/bridge/client.test.ts` drives `BridgeClient` with the
  session's frames.
- `pnpm test:integration`: the same over a real Paper server, with a
  restart, an unknown method answered and `ping`.
