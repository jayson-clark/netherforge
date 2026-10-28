---
name: plugin-persistence
description: What the plugin keeps and how - the namespacing rule, the store (one SQLite file, its lane, staged writes, migrations, errors), package databases (nf.db), remote MySQL/PostgreSQL connections, /nf data, spawned centities' records, saved data() tables, permissions, schedules, and the per-session state services keep for things the server forgets (boss bars, sidebars, teams, player lists, block data). Read before touching apps/plugin/runtime/.../store, .../data, .../schedule, .../scoreboard, a migration, or anything a restart must not lose.
---

# Plugin persistence

Where everything that outlives a tick lives, and what outlives a session, a restart or a crash.

## Persistence

**Everything the project registers or persists on the server is named in its
namespace** (`netherforge.json`'s, `ProjectSession.namespace`): stacks of
its items carry `shop:ruby`, its recipes are `shop:<id>`, and the attribute
modifiers and goals scripts add are `shop:<name>` (`ownId` in `EntityImpl.kt`). The
runtime hands the adapter references as files and scripts write them
(`ruby`, `ui/gem`); the adapter resolves them in the namespace it gets from
`Platform.bind` when it writes to the game and reads them back the same way.
NetherForge's own keys stay `netherforge:` (`netherforge:item`,
`netherforge:data`, the `EntityTag`): they're the engine's, holding the
project's names. `instances.json` still names centities by bare id (R3 moves
it into a store with tables per namespace).

**The store.** What the runtime itself keeps on a server is one SQLite
database, `plugins/NetherForge/netherforge.db` (`store/Store.kt`), open for the
plugin's life (`runtime.store`, opened in `enable` after the workers, closed in
`disable` after the session stops and before the pool closes). Its tables
(`store/migrations/NNN_name.sql` in the runtime's resources, applied in order
and recorded in `migrations`): `instances` and `instance_entities` (the
instance index), `centity_data`, `player_data`, `named_data` (saved tables),
`permissions`, `worlds`, `schedule_runs` (when each package's id'd `nf.schedule`s last ran), `cutscene_states` (what a player was before the cutscene holding them). A row a package persists says whose it is: a
`namespace` column, or a name written in full (`instances.centity` is
`shop:turret`, turned into the project's spelling as it's read, so a changed
namespace leaves the old one's instances inert). Per-block and per-entity
script data stays in PDC, where it travels with the chunk; node poses only
in memory.

- **The driver is the server's.** Every supported Paper bundles xerial's
  `sqlite-jdbc` (`libs.versions.toml` pins the same version for tests), so
  the runtime has it `compileOnly` and the jar ships none. `paper-plugin.yml`
  names a `loader` (`NetherForgeLoader`, paper-common) that adds it from Maven
  Central through Paper's `MavenLibraryResolver` only when the server has no
  `org.sqlite.JDBC` (and fetches nothing else unless `config.yml` names a
  remote connection: see "Remote databases"); the coordinates come from the catalog
  (`netherforge-libraries.properties`, expanded at build time).
- **One connection, one lane** (`store/Database.kt`, the layer `nf.db` will
  sit on with a database per package): the connection is only used on the
  workers' `"store"` lane. Services **stage** writes on the main thread
  (`Database.stage(key, write)`: a later write for the same key replaces it,
  and every keyed write sets its rows to their final state, so order between
  keys doesn't matter), and `NetherForgeRuntime.tick` **commits** after the
  session's tick: one transaction on the lane, at most one tick behind,
  nothing at all when nothing changed. Reads (`Database.read`: start-up, a
  table asked for the first time, `/nf data`) commit what's staged and wait
  for the lane, so they see every earlier write. WAL mode, so a reader (an
  export, a person's `sqlite3`, the integration test) never blocks it.
- **Errors, once, here.** A write that's its own fault (bad SQL, a
  constraint) is rolled back alone (a savepoint each) and dropped with a log
  line; a batch the database can't commit (busy, full, failing) stays on the
  lane and is retried with the next, newer writes for the same keys winning,
  logged once and again when it recovers. A file that isn't a database is
  moved aside (`netherforge.db.unreadable`) and a new one started.
  `DatabaseTest` is the model for all of it.
- **Package databases (`nf.db()`).** Each package's own data is a second
  SQLite file, `plugins/NetherForge/databases/<namespace>.db`, on the same
  `Database` class (`store/PackageDatabases.kt`: opened the first time its
  package asks or migrates, closed in `disable` after the runtime's store,
  one workers lane `database:<namespace>` each, so one package's slow query
  never holds up another's, and one package's statements run in order). Two
  parts: `PackageSchemas` (a session service; `reloads = {MigrationKind}`)
  applies each package's `migrations/NNN_name.sql` in `define` (and again on a
  reload) through `Database.migrate(namespace, ...)`, tracked in the file's
  own `migrations` table; a failure (`MigrationFailed` names the file) or a
  package whose migration files have errors leaves **no** database for that
  package: a `migration.failed` problem on the file and `unavailable(ns)`,
  which `DatabaseImpl.run` turns into the script's `nil, err`. `DatabaseImpl`
  (`api/`) runs a script's work with `workers.submit(lane) { database.onLane
{ connection -> ... } }`, so scripts' database work counts against the
  workers' bound and never blocks the main thread; results come back through
  `Marshal.await` like any async function. `SqlText.problem` refuses a text
  with a second statement (JDBC would drop it quietly); values bind with
  `PreparedStatement.bind`, never into the SQL; fewer values than `?` are NULL
  (Lua can't tell a trailing nil), more is the work's failure. Rows are
  `Map<String, Any?>` (NULL left out, a blob is a failure telling the script to
  `hex()` it). The `db` requirement is the generated primitive's.
  Tests: `PackageDatabaseTest` (a real file in the test's temp folder).
- **Remote databases (`nf.db("network")`, W2.3).** The same `Database` Lua
  class and the same `DatabaseImpl`, over a `SqlDatabase` (`store/SqlDatabase.kt`:
  `onLane`, `prepare`, `lastInsertId`): `Database` (the package's SQLite) or
  `RemoteDatabase` (`store/RemoteDatabases.kt`: a `javax.sql.DataSource`, HikariCP
  in production, made on first use and never connecting on the main thread;
  `initializationFailTimeout = -1`). The owner names connections in
  `config.yml`'s `databases:` (`DatabasesConfig`, read on dev servers too:
  type mysql|postgres, host, port, database, username, password, pool-size,
  `packages`, `properties`; host and database names are matched against a
  strict pattern so they can't add driver settings to the JDBC URL; a
  connection that can't be read is in `invalid` with why, logged at start-up).
  **Who may open one:** the package declares `requires.db` (S2, the generated
  check) **and** the owner lists its namespace under the connection's
  `packages` (`RemoteDatabases.problem`, called by `NfImpl.db`: errors at the
  line, naming what to add, never a host, user or password). A package's
  manifest never grants itself the server. **Lanes:** `database:<ns>:<name>`
  per package and connection, so one package's statements run in order and
  never wait on another's; the pool bounds how many run at once. **Errors:**
  a failure to get a connection is logged with its cause (the address) and is
  `nil, "the database connection \"x\" couldn't be reached"` to the script;
  statements have a 30 s query timeout. **Migrations are the own SQLite
  database's only** (`PackageSchemas` never touches a connection): they are
  SQLite's dialect and a shared server's tables aren't the package's alone.
  `last_insert_id` is MySQL's generated key for an `INSERT`/`REPLACE`
  (`generatesKeys`), nil on PostgreSQL (use `RETURNING` through `db:query`).
  Result values are normalised by `Cells.lua` (boolean to 1/0, decimal to
  number, date to text, big integers to text). The loader
  (`NetherForgeLoader`) fetches HikariCP (any connection) and PostgreSQL's
  driver (a `postgres` one) from Maven Central (MySQL's is Paper's own) only
  when the plugin folder's `config.yml` has such a connection: the loader runs
  before the plugin, so it reads that file itself with Bukkit's
  `YamlConfiguration` and `DatabasesConfig.read` (`Libraries.remoteTypes`).
  A connection added to a running server has no libraries until the next
  start: `HikariSources.missing` says so (the script's `nil, err` from the
  pool, and an error in the log at enable). The runtime has the libraries
  `compileOnly`, so they're never shaded. Tests: `RemoteDatabaseTest` (H2 in MySQL/PostgreSQL
  mode as the server through `runtime.connectionSources`),
  `DatabasesConfigTest`.
- `/nf data` (`admin/DataCommand.kt`) shows it: table sizes (by namespace), a
  table's first rows, one saved table's JSON (`show player|centity|named`),
  and `export` (every table into `exports/store-<time>.json` on the lane,
  saved tables' values as the JSON they are). Each saves tables and places
  first, so it shows now.

**Spawned centities.** Two halves: every entity carries an `EntityTag`
(instance, node, role) in its persistent data; the store is the index
(instance → centity, anchor, yaw, entity UUIDs, orphans). Tagged entities
nobody owns are strays and are removed when they load. `Centities` stages an
instance's whole record at the end of the tick its **identity** changed
(spawned, removed, inert or back, its entities reconciled, an orphan swept).
Its **place and facing** change all the time (a turret aiming, a body
re-anchoring, a walk, a teleport) and are kept in memory; the store gets them
when the world would save the entities anyway: `worldSaving` (that world's
instances), `chunkUnloading` (instances anchored in that chunk) and `stop`,
only where they differ from what the store has. So a centity turning every
tick writes nothing (`PersistenceTest`), and after a crash the store's anchors
are as old as the world's own entities. Node transforms aren't persisted:
scripts re-establish state in their body.

What scripts keep is `nf.files.get` and the saved
tables, `data/ScriptData.kt`:

- `centity:data()`, `player:data()`, `nf.data(name)` are live Lua tables
  (registry refs, `LuaRef`), made on first ask from the store and kept for
  the session's life: hot reloads never re-read them, and the next session's
  start from their encoding as it ended (`ScriptData.kept`). Rows:
  `centity_data` by instance (deleted when the instance is removed,
  `Centities.onRemoved`), `player_data` by UUID, `named_data` by namespace and
  name.
- Saved (encoded by the prelude's one Lua↔JSON codec, see the lua-api skill,
  staged only when the text changed) every `AUTOSAVE_EVERY` ticks, after
  every reload, for a player when they quit, and in `stop()` after every
  script's unload handlers and before the Lua state closes (`detach`: each
  table's latest text is kept in memory and the next state's table starts
  from it, so a full reload doesn't go back to the store). Over 1 MiB encoded:
  not saved, logged once, the last save stands. Problems (unsavable values
  by key path) are logged when they change, not at every save. A stored value
  that isn't a table (only something else could have written it) starts the
  table empty, with a warning. Tests read what's stored with
  `runtime.store.tables.read(owner)` and save with `runtime.saveData()`
  (tables and places, then a flush).

`entity:data()` tables (`world/EntityData.kt`) work the same way, kept as
JSON in the entity's persistent data container under `netherforge:data` (the
key an item's script data uses): written back on `entitiesUnloading`
(`EntitiesUnloadEvent`), `worldSaving` and stop, forgotten unsaved when the
entity dies or is `remove()`d (`ProjectSession.entityGone`, which also drops
its subscriptions, only when something listens to it: entities die all the
time). `spawn_entity`'s `data` option is written straight into the container.

Permission nodes scripts set (`player:set_permission`, `data/PlayerPermissions.kt`)
are kept in the store's `permissions` under the project's namespace. What
applies to a player is what's kept and what any `netherforge.json` in the tree
(the project's or a package's) lists under `allow.permissions` now (setting one is
held to the _calling package's own_ list, `Requirements.checkPermission`, tail
calls included), held on them through `PermissionOps.apply`
(on Paper one `PermissionAttachment` per player, replaced whole, then
`updateCommands`): everyone online gets theirs when the project starts
(before any script, so `has_permission` agrees), each player again on join,
and nobody keeps any while the project isn't running. Moderation writes
(`player:ban`, `nf.server.set_motd`, …) are spec `requires: "moderation"`:
the generated primitive calls `Marshal.requires`, which asks `session/Requirements.kt`
whether the _calling package's own_ `netherforge.json` declares it
(`"requires": {"moderation": true}`, `PackageNames.calling`: a dependency is held to its
manifest, not the project's; see the plugin-capabilities skill). Format's
`Requirement.grantedBy` is the rule, and mirrors `granted` in
`packages/api/src/requirements.ts`.

Boss bars (`scoreboard/BossBars.kt`) are runtime state: what each shows and its
viewers by UUID, re-shown on join (the server forgets viewers on quit), each
owned by its scope and removed when the scope is released (`scopeReleased`).
Sidebars keep each online player's title, lines and visibility, forgotten on
quit; the adapter gives a shown sidebar a per-player scoreboard copying the
main board's teams and below-name lines (`MainBoard.copyTo`): right after any
change through `TeamOps` (`PaperSidebars.copySoon`, on the next tick),
and every second for what `/team` or other plugins change. `entity:hide_from`
is kept in `HiddenEntities` and re-applied on join; when the session ends
every one shows again (what a script sets lasts as long as the session).

**Schedules** (`schedule/`). `nf.schedule.daily/weekly/cron` are Kotlin
(`api/ScheduleImpls.kt`: `NfScheduleImpl`), not prelude. `Recurrence` is the one rule (`next(after,
zone)`: java.time for daily and weekly, where `ZonedDateTime.of` gives the
gap/overlap rules; cron-utils' `ExecutionTime` for Unix cron, which matches the
clock as it reads), and `Schedules` is the service: in the `TIMERS` tick phase
it compares `runtime.wallClock` (an injectable `java.time.Clock`; tests pass
`TestServer(wallClock = ...)`; `nf.server.unix_time()` reads it too) with every
entry's next run and calls the due ones once each (`scripts.callKept(..., "schedule")`,
so the profiler lists them as `schedule`), then sets the next run from now, so
a lagging tick or a clock jump never runs a schedule twice. Each belongs to its
scope (`scopeReleased` cancels it and lets go of the callback, so a reload
cancels). The time zone is the **server owner's, not a package's**: W3.1's
settings are declared by packages and read with `nf.config`, which doesn't fit
one setting for the whole server, so it's `schedules.time-zone` in
`config.yml` (`ScheduleConfig`, read at startup like `performance:`; unknown
zones warn and fall back to the server's). An `id` (per namespace, unique among
live schedules) keys the last run in the store's `schedule_runs`; on `create`
a schedule with an id and no last run records "now" as one, and
`catch_up` runs once at once when `next(lastRun) <= now`. `catch_up` needs an id.
`Schedules.zone` (resolved once, in `define`) is also `nf.time`'s default zone
(`NfTimeImpl(defaultZone)`, so a `zone` option still wins and a bad config name
falls back the same way for both). `/nf schedules` (`AdminCommand`) lists the
live ones soonest first from `Schedules.all()`, each rule as `Recurrence.describe()`.
`RecurrenceTest` holds the rules (DST included) and `ScheduleTest` the service,
`nf.time`'s zone and `/nf schedules`. A dev server started with the test-only
`-Dnetherforge.test.wall-clock-rate=<n>` (`RuntimeConfig.WALL_CLOCK_RATE_PROPERTY`,
ignored on production) runs `wallClock` n times as fast from start-up
(`ScaledClock`, `ScaledClockTest`): the integration test's `ScheduleScenario`
runs at 60, so a cron schedule for every minute fires on the real tick every
second. The editor never sets it.

Teams (`scoreboard/Teams.kt`, `platform/Teams.kt`) live on the **main**
scoreboard, called `nf.<name>` there (`Teams.PREFIX`), so a project's never
collide with vanilla's or another plugin's and only ours are ever removed.
Like boss bars, each belongs to the scope that made it and goes when it's
released; every one goes in `stop()`, and `enable()` removes any `nf.*` team
a crash left in the saved scoreboard (`clearLeftovers`). The runtime keeps a
team's look as the script set it (getters read that back); membership is the
scoreboard's (entries: a player's name, any other entity's UUID). The line
under name tags is one dummy objective, `nf.below_name`, in the below-name
slot with blank numbers and an empty title, each line a score's fixed number
format; it's unregistered when nobody has a line (Minecraft draws an empty
line under everyone while the slot shows anything). Lines go on quit and in
`stop()`. `player:set_listed_for` is kept in `PlayerListings` and re-applied
when either player joins; a player's tab name and order are the server's and
last until they leave.

`block:data()` tables (`world/BlockData.kt`) are JSON in the block's chunk's
persistent data container (`netherforge:block_data/<x>_<y>_<z>`), read the
first time a script asks and then held for the Lua state's life (one table
per position, shared by every script). They're written back, only when
changed, on `chunkUnloading` (Paper's `ChunkUnloadEvent`, while the chunk can
still be written), `worldSaving` (`WorldSaveEvent`: autosaves and `/save-all`)
and when the runtime stops, after every script's `unload`. They hold what
every `data()` table does: encoded by `LuaHost.encodeData` and read back by
`keepData`, like `ScriptData`'s, with problems logged once per change.
