---
name: plugin-persistence
description: What the plugin keeps and how - the namespacing rule, the store (one SQLite file, its lane, staged writes, migrations, errors), package databases (nf.db), remote MySQL/PostgreSQL connections, /nf data, spawned centities' records, saved data() tables, permissions, schedules, and the per-session state services keep for things the server forgets (boss bars, sidebars, teams, player lists, block data). Read before touching crates/runtime/src/store.rs, data.rs, databases.rs, remote_db.rs, schedule.rs, bossbar.rs or team.rs, a migration, or anything a restart must not lose.
---

# Plugin persistence

Where everything that outlives a tick lives, and what outlives a session, a restart or a crash.

## Persistence

**Everything the project registers or persists on the server is named in its
namespace** (`netherforge.json`'s): stacks of
its items carry `shop:ruby`, its recipes are `shop:<id>`, and the attribute
modifiers and goals scripts add are `shop:<name>` (`own_id` in `crates/runtime/src/living.rs`). The
runtime hands the adapter references as files and scripts write them
(`ruby`, `ui/gem`); the adapter resolves them in the namespace it gets from
`Platform.bind` when it writes to the game and reads them back the same way.
NetherForge's own keys stay `netherforge:` (`netherforge:item`,
`netherforge:data`, the `EntityTag`): they're the engine's, holding the
project's names.

**The store.** What the runtime itself keeps on a server is one SQLite
database, `plugins/NetherForge/netherforge.db` (`crates/runtime/src/store.rs`), open for the
plugin's life (the adapter's composition opens it, `crates/ffi/src/compose.rs`). Its tables
(`crates/runtime/migrations/NNN_name.sql`, applied in order as it opens and recorded in `migrations`):
`instances` and `instance_entities` (the instance index), `centity_data`, `player_data`, `named_data` (saved
tables), `permissions`, `worlds`, `schedule_runs` (when each package's id'd `nf.schedule`s last ran),
`cutscene_states` (what a player was before the cutscene holding them), `screen_maps` and `screen_map_next`. A
row a package persists says whose it is: a `namespace` column, or a name written in full (`instances.centity`
is `shop:turret`, turned into the project's spelling as it's read, so a changed namespace leaves the old one's
instances inert). Per-block and per-entity script data stays in the game's persistent data, where it travels with
the chunk; node poses only in memory.

- **SQLite is the runtime's own** (rusqlite, bundled into the library): nothing comes from the server or Maven.
- **One connection, one lane**: the connection is used on the store's own lane for commits, and on the main
  thread only for a read once the lane has caught up. Services **stage** writes on the main thread (each under a
  key: a later write for the same key replaces it, and every keyed write sets its rows to their final state, so
  order between keys doesn't matter), and `Store::commit` (the end of every tick) hands what's staged to the lane
  as one transaction (`BEGIN IMMEDIATE`), nothing at all when nothing changed. Reads (start-up, a table asked for
  the first time, `/nf data`) `flush` first and wait for the lane, so they see every earlier write.
- **Errors, once, here.** A write that's its own fault (bad SQL, a constraint) is rolled back alone (a savepoint
  each) and dropped with a log line; a batch the database can't commit (busy past a second, full, failing) stays
  on the lane and is written with the next, newer writes for the same keys winning, logged once and again when it
  recovers ("The store is writing again"). A file that isn't a database is moved aside
  (`netherforge.db.unreadable`) and a new one started, saying so. Failures are said through `take_failures`.
  `crates/runtime/tests/store.rs` holds both; the module's own tests the rest.
- **Package databases (`nf.db()`, `crates/runtime/src/databases.rs`).** Each package's own data is a second
  SQLite file, `plugins/NetherForge/databases/<namespace>.db` (in memory when the server gives no folder), apart
  from the store, on a lane of its own (`database:<namespace>`), so one package's slow query never holds up
  another's and one package's statements run in order. Its connection can attach no other database
  (`SQLITE_LIMIT_ATTACHED` 0), and `ATTACH`, `VACUUM` and the pragmas that name a folder are refused before they
  run. Each package's `migrations/NNN_name.sql` are applied in order as the session starts (and when one is added
  on a reload), each once, tracked in the file's own `migrations` table; a failure or a package whose migration
  files have errors leaves **no** database for that package: a `migration.failed` problem on the file, and its
  scripts' calls answer `nil, err` with the reason. A script's work runs on its package's lane, so it never
  blocks the main thread; results reach the script at a tick boundary like any async value. SQL that isn't one
  statement is refused at the script's line; values bind as parameters, never into the SQL; fewer values than `?`
  are NULL (Luau can't tell a trailing nil), more is the work's failure. Numbers follow the plugin-runtime skill's
  rule: a whole number in the safe range binds as an integer, any other as a real; an integer column's value past
  the safe range comes back as a string of its digits. Rows are tables (NULL left out, a blob is a failure telling
  the script to `hex()` it). The `db` requirement is the generated binding's. Tests:
  `crates/runtime/tests/databases.rs` (real files in the test's temp folder).
- **MySQL and PostgreSQL** (`nf.db("network")`, and a package's own database there, `own-databases`): the Rust
  runtime's `remote_db.rs`, its own drivers (no JDBC, nothing fetched). What scripts see, the pool, the own
  databases' schemas, transactions behind a pooler and the migrations' lock are the plugin-runtime skill's "Package
  databases".
- `/nf data` (`crates/runtime/src/admin.rs`) shows it: table sizes (by namespace), a
  table's first rows, one saved table's JSON (`show player|centity|named`),
  and `export` (every table into `exports/store-<time>.json` on the lane,
  saved tables' values as the JSON they are). Each saves tables and places
  first, so it shows now.

**Spawned centities.** Two halves: every entity carries an `EntityTag`
(instance, node, role) in its persistent data; the store is the index
(instance → centity, anchor, yaw, entity UUIDs, orphans). Tagged entities
nobody owns are strays and are removed when they load. `centity/saving.rs` stages an
instance's whole record at the end of the tick its **identity** changed
(spawned, removed, inert or back, its entities reconciled, an orphan swept).
Its **place and facing** change all the time (a turret aiming, a body
re-anchoring, a walk, a teleport) and are kept in memory; the store gets them
when the world would save the entities anyway: `worldSaving` (that world's
instances), `chunkUnloading` (instances anchored in that chunk) and `stop`,
only where they differ from what the store has. So a centity turning every
tick writes nothing, and after a crash the store's anchors
are as old as the world's own entities. Node transforms aren't persisted:
scripts re-establish state in their body.

What scripts keep is `nf.files.get` and the saved
tables, `crates/runtime/src/data.rs`:

- `centity:data()`, `player:data()`, `nf.data(name)` are live Luau tables, made on first ask from the store and
  kept for the session's life: hot reloads never re-read them, and the next session's start from their encoding as
  it ended. Rows: `centity_data` by instance (deleted when the instance is removed), `player_data` by UUID,
  `named_data` by namespace and name. `nf.data(name)` is per package; a thing's table is the thing's, one for every
  package's scripts.
- Saved (encoded by the runtime's one JSON codec, `netherforge_luau::json`, with the handle tags of
  `SavedHandles`; staged only when the text changed) every `AUTOSAVE_EVERY` ticks, after every reload, for a player
  when they quit (after their `player_quit` handlers), and as the session stops, after every script's unload
  handlers (each table's latest text is kept in memory and the next session's table starts from it, so a full
  reload doesn't go back to the store). Over `MAX_BYTES` (1 MiB) encoded: not saved, logged once, the last save
  stands. A value that can't be saved (a `Task`) is said by its key path when that changes, not at every save. A
  stored value that isn't a table starts the table empty, with a warning.

`entity:data()` tables (`crates/runtime/src/entity.rs`) work the same way, kept as
JSON in the entity's persistent data container under `netherforge:data` (the
key an item's script data uses): written back on `entitiesUnloading`
(`EntitiesUnloadEvent`), a world saving and the session's end, forgotten unsaved when the
entity dies or is `remove()`d (which also drops its subscriptions, only when something listens to it: entities
die all the time). `spawn_entity`'s `data` option is written straight into the container.

Permission nodes scripts set (`player:set_permission`, `crates/runtime/src/permissions.rs`)
are kept in the store's `permissions` under the project's namespace. What
applies to a player is what's kept and what any `netherforge.json` in the tree
(the project's or a package's) lists under `allow.permissions` now (setting one is
held to the _calling package's own_ list, `check_permission` in `player.rs`, tail
calls included), held on them through `PermissionOps.apply`
(on Paper one `PermissionAttachment` per player, replaced whole, then
`updateCommands`): everyone online gets theirs when the project starts
(before any script, so `has_permission` agrees), each player again on join,
and nobody keeps any while the project isn't running. Moderation writes
(`player:ban`, `nf.server.set_motd`, …) are spec `requires: "moderation"`:
the generated binding calls `Runtime::requires`, which asks the room's `Grants`
whether the _calling package's own_ `netherforge.json` declares it
(`"requires": {"moderation": true}`: a dependency is held to its manifest, not the project's; see the
plugin-capabilities skill). Format's
`Requirement.grantedBy` is the rule, and mirrors `granted` in
`packages/api/src/requirements.ts`.

Boss bars (`crates/runtime/src/bossbar.rs`, with sidebars) are runtime state: what each shows and its
viewers by UUID, re-shown on join (the server forgets viewers on quit), each
owned by its scope and removed when the scope is released; one attached to a centity shows to who's near it and
follows its health.
Sidebars keep each online player's title, lines and visibility, forgotten on
quit; the adapter gives a shown sidebar a per-player scoreboard copying the
main board's teams and below-name lines (`MainBoard.copyTo`): right after any
change through `TeamOps` (`PaperSidebars.copySoon`, on the next tick),
and every second for what `/team` or other plugins change. `entity:hide_from`
is kept by the entities service (`entity.rs`) and re-applied on join and when the entity loads; when the session ends
every one shows again (what a script sets lasts as long as the session).

**Schedules** (`crates/runtime/src/schedule.rs`). `nf.schedule.daily/weekly/cron` are the runtime's, not
prelude. `Recurrence` is the one rule (`next(after, zone)`: chrono and chrono-tz for daily and weekly, with
java.time's gap and overlap rules, as Kotlin's were: a wall-clock time the clocks skip is shifted by the gap,
one that happens twice is the first; croner for Unix cron, five fields, matching the clock as it reads), and
`Schedules` is the service: each tick it compares the wall clock (`Schedules::now`; a test sets one,
`Config::wall_clock`, and `nf.server.unix_time()` reads it too) with every entry's next run and calls the due ones
once each, as their script's code (the profiler lists them as `schedule`), then sets the next run from now, so a
lagging tick or a clock jump never runs a schedule twice. Each belongs to its scope (its release cancels it, so a
reload cancels). The time zone is the **server owner's, not a package's**: settings are declared by packages and
read with `nf.config`, which doesn't fit one setting for the whole server, so it's `schedules.time-zone` in
`config.yml` (`Config::time_zone`; unknown zones warn and fall back to the server's). An `id` (per namespace,
unique among live schedules) keys the last run in the store's `schedule_runs`; a schedule with an id and no last
run records "now" as one, and `catch_up` runs once at once when `next(last_run) <= now`. `catch_up` needs an id.
The zone is also `nf.time`'s default (a `zone` option still wins, and a bad config name falls back the same way
for both). `/nf schedules` lists the live ones soonest first, each rule as `Recurrence::describe()`. The module's
own tests hold the rules (DST included). A dev server started with the test-only
`-Dnetherforge.test.wall-clock-rate=<n>` (`config.rs`, ignored on production) runs the wall clock n times as fast
from the first time it's read in the process, so a reload doesn't set it back: the integration test's
`ScheduleScenario` runs at 60, so a cron schedule for every minute fires on the real tick every second. The editor
never sets it.

Teams (`crates/runtime/src/team.rs`; the Platform's `TeamOps`) live on the **main**
scoreboard, called `nf.<name>` there, so a project's never
collide with vanilla's or another plugin's and only ours are ever removed.
Like boss bars, each belongs to the scope that made it and goes when it's
released; every one goes when the session ends, and a session's start removes any `nf.*` team (and line under
name tags) a crash left in the saved scoreboard. The runtime keeps a
team's look as the script set it (getters read that back); membership is the
scoreboard's (entries: a player's name, any other entity's UUID). The line
under name tags is one dummy objective, `nf.below_name`, in the below-name
slot with blank numbers and an empty title, each line a score's fixed number
format; it's unregistered when nobody has a line (Minecraft draws an empty
line under everyone while the slot shows anything). Lines go on quit and when the session ends.
`player:set_listed_for` is kept by the teams service too and re-applied
when either player joins; a player's tab name and order are the server's and
last until they leave.

`block:data()` tables (`crates/runtime/src/block_data.rs`) are JSON in the block's chunk's
persistent data container (`netherforge:block_data/<x>_<y>_<z>`), read the
first time a script asks and then held for the Lua state's life (one table
per position, shared by every script). They're written back, only when
changed, on `chunkUnloading` (Paper's `ChunkUnloadEvent`, while the chunk can
still be written), `worldSaving` (`WorldSaveEvent`: autosaves and `/save-all`)
and when the session ends, after every script's `unload`. A table outlives its chunk being unloaded: a script
holding it keeps the same table, written again once the chunk is back. They hold what every `data()` table does,
through the same codec and handle tags as `entity:data()`, with problems logged once per change; an empty table is
no data.
