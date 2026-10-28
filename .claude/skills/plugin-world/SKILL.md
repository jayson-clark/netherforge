---
name: plugin-world
description: The plugin's world features - custom blocks (note block carriers, records, placing, mining with BlockMining, breaking, falling, the game's blocks' rules), terrains and the main world's generator, dimension types and biomes, the start-up datapack and advancements, managed worlds, borders, structures and gradual placement, loot tables, mob goals, cutscenes, and screens (maps, frames, sending, aim, videos read off the main thread). Read before touching apps/plugin/runtime/.../block, .../terrain, .../world, .../structure, .../datapack, .../loot, .../mob, .../cutscene or .../screen.
---

# Plugin world features

What the runtime does in and to the world beyond centities (entities-and-physics) and windows (menus-and-dialogs). Each is a `RuntimeService` (plugin-runtime skill, "The session and its services") over the platform's groups.

## Cutscenes (`cutscene/Cutscenes.kt`)

`Cutscenes.tick()` (`TickPhase.EFFECTS`, after the world has moved) makes one
pass over every cutscene playing. **Playing one** (`nf.cutscenes.play`) saves
the player's `PlayerState` (location, game mode, `CAN_FLY`/`FLYING`, the
entity they were spectating), closes their window, dismounts them, spawns the
**camera**, makes them spectate it, and returns the handle. The camera is a
text display with no text and a fully transparent background (`EntityOps.spawnDisplay`,
tagged `EntityTag(run, "cutscene:camera", DISPLAY)` so scripts never see it and a
stray is cleaned like a centity's), non-persistent, with `DisplayLook(teleportTicks = 1)`:
**each tick the pass samples format's `CameraPath` at that tick's time and teleports
the camera there**, and the client tweens the one tick (the vanilla teleport
duration), which is the smooth movement. The server moves a spectating player to
the entity each tick (`ServerPlayer.tick`), so their chunks follow the camera;
`PlayerViewOps.setCamera` accepts any entity the server has, ours included (it
used to refuse a tagged one). Spectator mode is what stops them moving and
interacting.

Each pass, per cutscene: the player gone ends it `player_left`; a game mode that
isn't spectator or a camera that's gone ends it `stopped` (something else took
them out); a camera that isn't ours (the server lets go when the player
sneaks) ends it `skipped` if skippable, else is put back (`setCamera` again);
then the tick advances, the camera moves, and each cue whose time has come
fires (`cue` event when something listens, the text as a subtitle through
`players.title`, cleared when its duration is up). The cutscene ends one tick
after the last pose so the client has drawn the camera arriving.

**Putting back** (`restore`) runs on every way it ends, in this order, each
step on its own so one the server refuses (a player already gone) never skips
the rest: let go of the camera, teleport back, restore the game mode, then
`CAN_FLY` and `FLYING` (a mode change decides what they may do, so what they
were allowed is said after it), then the entity they were spectating if they
were in spectator mode. It happens **before** the `end` event, so a handler
that moves the player has the last word. Ways it ends: `finished`, `stopped`
(`stop()`), `skipped`, `replaced` (a play over a cutscene keeps the first's
saved state and tells the old one only once the new one is running),
`player_left` (`RuntimeService.playerQuit`: on Paper the player is still online
in the quit event and their data is saved after it, so the restore is what's
saved; the fake removes them first, so only the integration scenario proves
this), `unloaded` (`scopeReleased`, and `stop()` for a reload or the server
stopping: Paper disables plugins before it saves players). **A hard crash**:
the saved state is also a row in the store (`cutscene_states`, in `001_init.sql`,
`Store.CutsceneStates`), staged when the first of a run starts and deleted by
`restore` itself (so every way it ends clears it). `start()` reads the rows left
(at most a tick behind, as every staged write is), and `playerJoined` puts
that player back and logs a warning, before scripts hear the join
(`RuntimeGameEvents.playerJoin` calls the services first). The camera is
non-persistent, so a crash leaves nothing else to clean. A reload of the cutscene's file never moves a playing one:
each run holds the `CompiledCutscene` it started with. Tests: `CutsceneTest`
(the fake), `EntityOpsContract`'s camera test (the fake and Paper),
`CutsceneScenario` (a bot, on Paper: the position follows the path, a quit and
a restart mid-cutscene both put the bot back), `CutsceneTest`'s crash test (state saved, runtime abandoned without stopping, recreated, joined, restored).

## Loot tables

`loot/LootTables.kt` keeps every running loot table (the packages' as
`ns:id`) and rolls them: `roll(table, LootRoll, seed)` runs format's
`LootRoller` from `Random(seed)`, turns each `LootDrop.Stack` into stacks
(a project item through `Items.create`, split at its `maxStackSize`, else 64)
and hands each `LootDrop.Vanilla` to `LootOps.roll` with the drop's own seed,
at the roll's `location`, else its player's or looted entity's place. A name
in the project's namespace or a package's that has no table is an error; any
other namespaced id is the game's (`LootOps.exists`), and a game table that
needs what the roll doesn't say comes back from the platform as an
`IllegalArgumentException`, which becomes the script's error. `LootRoll` is
the Kotlin side of a script's `LootContext`, and what blocks (W4.1) and
natural spawning (W5.3) will roll drops with; `nf.loot.fill` places the
stacks in random empty slots (seeded), never a player's armour or offhand.
`NfLootImpl` names a table as every resource a script names: a namespace
that's neither the project's nor a package's is the game's and goes through
as written; anything else goes through `names.resource(LootTableKind, …)`
(bare is the calling package's, another package's only where exported, the
same error as for items and menus) and must be one of `LootTables.idOf`'s.
A package's tables run qualified (format's `running`), so their bare names
stay their package's whoever rolls them, and the stacks a roll gives go back
through the `ITEM` codec, spelled for the caller like every stack.

## Custom blocks

`block/CustomBlocks` (a `RuntimeService`, registered after the centities that draw some blocks, so
it starts after them and stops before). What a block is in the world: a **note block state**
(`BlockCarriers`, see project-format) plus a **record** in its chunk's persistent data
(`BlockOps.setRecord`, `custom_block/<x>_<y>_<z>` = `{"id":"ruby_ore","centity":"<uuid>"}`).
The record, not the state, is the block's identity: when a chunk loads (the service asks for
the watched `chunk_load`, `RuntimeService.needsWatched`; also at start for every loaded chunk
through `WorldOps.loadedChunks`) and every few ticks per tracked chunk (the sweep, two chunks a
tick), `reconcile` puts each record's block in the state it has now (the allocation moves with
the project's blocks), forgets a record whose position isn't a note block any more (its data
table and centity go with it), **adopts** a carrier state with no record (a generator's chunk, a
paste: `BlockOps.find` says where the states are, with a palette check first), and re-spawns a
missing centity. `at`, `stateOf`, `place` and `breakNaturally` are the internal API for other
code (a terrain, W5.2): `stateOf(name)` is the note block state to put in chunk data, and
`place` sets, records and draws one block in a loaded chunk.

- **Freeze.** The server works note blocks out itself (instrument from the blocks above and
  below, `powered` from redstone, tuning). It's stopped for the session's whole life with
  `BlockOps.freezeNoteBlocks()` (Paper's `disable-noteblock-updates`, through
  `PaperVersion.setNoteBlockUpdatesDisabled`, re-asserted every second since `/paper reload`
  restores the file's), called in `define` so a module's body may place blocks. A server that
  refuses leaves `usable` false and an `runtime.blocks` problem. **The adapter does the vanilla
  note blocks' part** (`PaperNoteBlocks`, nothing runs until frozen): a player's note block is in
  the default instrument's column; a right click tunes it (cancelling the item and block use, as
  the game's `useItemOn` then `useWithoutItem`: not when sneaking with something in hand or
  putting a head on top), a hit under a block sounds it (`PaperVersion.playNote`: the game's own
  `blockEvent`, since its `playNote` is silent there), a redstone signal sounds it (`BlockPhysicsEvent`
  of a note block, looked at a tick later, `powered` kept in step) and `NotePlayEvent` sets the
  instrument from the blocks around (`PaperVersion.noteInstrument`, the game's own rule); a
  carrier's state never sounds (`NotePlayEvent` cancelled), takes no tuning, and a right click on
  it denies only the block's use so what's in hand goes against it. The runtime knows none of it.
- **Placing.** An item's `block` (`Items.blockOf`) places on a right click (`ServerEvents.playerInteract`
  then `CustomBlocks.interact`, after the player's and the item's own events): the face's
  neighbour must be air or a liquid, `BlockOps.canPlace` (no living entity in the way), the
  block's `place` handlers may cancel it, then `BlockOps.place` sets it and raises the server's own
  `BlockPlaceEvent` (protection plugins and `block_place` scripts), records it, plays its sound and
  uses the item up (not in creative). The click is always cancelled for such an item.
- **Mining** (`BlockMining`, which `CustomBlocks` hands its blocks' names, files and rules to; it holds who's
  mining what). `block_start_break` (`RuntimeGameEvents.blockStartBreak`, before scripts) sets the
  player's `block_break_speed` modifier (`<namespace>:mining`, `ADD_MULTIPLIED_TOTAL`) to the ratio of
  the block's progress a tick (the tool's speed on the `mineable/<tool>` block over the block's
  hardness, 30 or 100 without the tool it needs) to the note block's (the same from the server's
  own numbers: `BlockOps.hardness`, `breakSpeed`, `isPreferredTool`), so the server's and the
  client's own progress agree (the game accepts a stop once 70% is done); any other block's start
  takes it off; hardness 0 is instant, -1 refuses in survival. The modifier is saved with the
  player, so start and join clear it.
- **Breaking.** `ServerEvents.blockBreak` hands a custom block to `CustomBlocks.breaking` first:
  its loot table is rolled (`LootTables.roll`, the player and tool as context; nothing when
  `requiresTool` isn't met or in creative), the stages are the held item's `break_block`, the block's
  `break`, the world's and `nf`'s (`BlockBreakEvent`, `drops`/`experience` writable), and the server's
  break is **cancelled**: the runtime sets the block to air itself, with the block's sound and
  block particles (so no wood sound), drops the items and forgets the record, data and centity.
  An explosion (`RuntimeGameEvents.entityExplode`/`blockExplode`, after scripts) breaks the blocks it
  lists the same way, with the yield's chance, and a piston doesn't move one.
  `Block:break_naturally` and a script's `set_state` (or `world:set_block`, `fill_blocks`) go through
  `breakNaturally`/`replaced` too.
- **The game's blocks** (`game_blocks/`, `GameBlockKind`): `CustomBlocks` also holds the project's rules for
  some of the game's own blocks (`gameRules`, by block id, tags resolved by `format`'s `GameBlocks.resolve` against
  the live game). A rule with `hardness`, `tool` or `requiresTool` (`GameBlockFile.minedByServer`) is mined by the
  same machinery as a project block: `startedBreakingGame` slows the player and counts with `gameFactor` (the rule's
  numbers over the game's own for the state: `BlockOps.hardness`, `breakSpeed`, `harvests`), and a `Mining.target`
  of `#<block id>` (`targetAt`) where a project block's is its name. The server's break (`breakAs`) is **not**
  cancelled for a game block: it's the game's own, so the tool wears, experience and a container's contents drop,
  and a door's other half goes; the game leaves the breaker out of the break's effect (their game never broke it),
  so `BlockOps.showBroken` sends them the game's effect 2001. Drops: `gameDrops` (the player's break, through
  `ServerEvents.blockBreak` as the `DropsAnswer`, scripts' `drops` seeing the rule's), `exploded` (set to air before
  the explosion gets there), `worldBroke` (the adapter's `PaperGameBlocks`: Paper's `BlockDestroyEvent`,
  `BlockBreakBlockEvent`, and leaves decay done there instead), and `breakNaturally` for a script. Game blocks
  can't have their own sounds: the game plays them on each client from the block's sound type (a resource pack's
  `minecraft:` sound events replace them for everything of the type). `BlockOps.watch(drops, falling)` says what the
  adapter listens for; nothing while both are empty.
- **Falling** (`falls`): a placed, adopted or updated (`blockUpdated`, the adapter's `BlockPhysicsEvent` for the
  watched states) falling block queues in `pendingFalls`, and in the next `WORLD` tick `BlockOps.fall` drops it as
  the game's falling block entity (neighbours told, so a column falls) and the block is forgotten. Paper's
  `EntityChangeBlockEvent` for it is `fallingLanded`, which registers it again where it lands; one gone without
  landing (`worldEntities.info` null) drops its loot table where it was last seen. Its `data()` table doesn't
  travel.
- **Shapes.** A block with a `centity` spawns it at its bottom centre (`Centities.spawn`) when
  placed and removes it with the block; it stays the solid cube for collision and selection.
  The centity's own hitboxes take clicks before the block.
- **Ticking.** Blocks with a `tick` interval are tracked per chunk (`ticking`), each with its next
  due tick, and heard in `TickPhase.WORLD` by `ProjectBlock`'s `tick` handlers.
- **Tests.** `BlockTest` runs it all on the fake (`FakeBlocks`: records, `find`, hardness, speeds,
  `place`, the note block in the fake game's registry), `GameBlockTest` the game's blocks' rules and falling blocks
  (`FakeBlocks.gameDrops`, `harvestTools`, `fallen`, `shownBroken`); the Paper contract suites
  (`BlockOpsContract`, `WorldOpsContract.loadedChunks`) and `PaperNoteBlocksTest` (Paper only: the
  vanilla note block's part) cover the adapter, `BlockScenario` a real server with a bot placing,
  mining and tuning.

## Terrains

`terrain/WorldGenerators` (a `RuntimeService`, registered after `CustomBlocks`) is the project's `terrain/<id>.json`
as the adapter's chunk threads see it, and it **runs nothing**: a generator is format's pure, immutable
`CompiledTerrain` (see project-format), the chunk threads call it, and the service only publishes it. On `define`,
on a terrain reload and after a block or structure reload (`follows` both kinds: the state a custom block is held as
moves with the blocks, and a decoration's structure is read again when its file is saved) it links the structures its
decorations place (each file found through the project's files, a package's too, read by `StructureOps.template`: the
game's own structure loader on Paper, so old files are data-fixed; one that can't be read isn't placed, with a
`runtime.terrain` problem; `CompiledTerrain.withStructures`), resolves every palette entry to the block state
written into a chunk (a vanilla block as it is; a custom block, wherever the file puts one, as `CustomBlocks.stateOf`,
or, when there's none, as the file's stone, `minecraft:stone` if that is custom too, with a `runtime.terrain`
problem) and gives the adapter the whole set: `WorldManagerOps.publishGenerators(Map<id, ProjectGenerator>)`, called
on the main thread. A reload never changes a generator in use: chunks generated afterwards use the new one, the
ones made stay (the docs say so), and a world whose generator is no longer published (the file has errors, or is
gone) keeps generating with the last one it had.

A world made with one is `WorldSettings.terrain` (the id) with `generator = "normal"`: `nf.worlds.create`'s
`terrain` option names a project terrain (`names.resource(TerrainKind, ...)`, so a package's
`acme:hills` follows the export rules; a nether or end with one, or one beside a `generator`, is an error), and its
`generator` option is only ever a vanilla type (a literal union the codec checks). `ManagedWorlds` keeps the id with the
world in the store (`worlds.terrain`) and hands it to `WorldManagerOps.load(name, environment,
terrain)`, since the server doesn't remember a plugin's generator. `netherforge.json`'s `worlds.<name>.terrain`
makes the world when the session starts if the server has none (loading it with its generator if one is saved);
`WorldGenerators.start` does it, after `CustomBlocks.start`, so the chunks it generates are looked at for custom
blocks.

**Lua stages (W5.6).** A file's script (`terrain/<id>.lua`, see project-format's "Lua stages") runs on the chunk
threads, **never in the session's Lua state**: format's `TerrainScripts` runs it in states of its own on luajava
(`LuajavaPlatform`, the same library and natives the plugin ships), one per chunk thread at a time, pooled by the bound
generator, with no `nf`, a budget per call and a memory limit of their own. The service only prepares it, on the main
thread, in `publish`: `scriptsFor` reads the script and every module's Lua of the generator's package
(`projectFiles` and `source.read`; a package's generator `acme:hills` reads its package's files by package path) so a
chunk thread never reads a file, `TerrainScripts.check` loads it once (a state made and closed: a script that
doesn't compile, errors in its body or returns no stages is a `terrain.script-failed` problem at once), and the
result rides on `ProjectGenerator.scripts`. The adapter passes it to `bind` (`PaperWorldGenerators.Prepared.boundTo`)
and closes a replaced generator's states on the next publish (`Prepared.close`: idle states now, busy ones as their
chunk finishes); a generator no longer published keeps its states, since its worlds still generate with it.
**Failures** come from the chunk threads through `TerrainScriptFailures.reporter(id)` (only queues: never logs or
touches the session off the main thread, each distinct `stage`+`message` once per publish, at most
`MAX_PER_GENERATOR`), and `WorldGenerators.tick(UPKEEP)` drains them: one warning in the log and a
`terrain.script-failed` problem at the script's file and line, until the next publish (`restart()` forgets them and
drops what an older publish still queues). What failed was the file's own result already (format's fallback), so
nothing else is done. **Hot reload**: the script is the terrain resource's companion file, so saving it reloads
`terrain:<id>` like the JSON; the service also `follows` the module kind (a saved module a script may require
publishes again, only when some generator has a script). `StartupGenerators` (the main world, before any session)
gives scripts too, with a reporter that logs each failure once itself (there are no problems before a session).
Tests: `TerrainTest`'s last cases (the fake: sources, a module's reload, a script that doesn't load, a budget
overrun on a pool's threads said once on the main thread), `PaperWorldGeneratorsTest`'s scripted world (a real
server's chunk threads make what format's generator makes with the same script).

**The main world** (W5.4) is the server's, made before any session: the standard Bukkit way, `bukkit.yml`'s
`worlds.<name>.generator: NetherForge`, makes the server call `NetherForgePlugin.getDefaultWorldGenerator` as it
loads the world, and only on an _enabled_ plugin, so the plugin is `load: STARTUP` (no Paper lifecycle event does
this, on 1.21.11 or 26.x: checked in the servers' sources). `onEnable` then only builds the platform and the runtime;
`runtime.enable()` waits for `ServerLoadEvent` (every world loaded, every POSTWORLD plugin enabled, Vault, PAPI and
the bots included), and the `COMMANDS` lifecycle event has fired by then, so `/nf` goes straight into the dispatcher.
`NetherForgeRuntime.defaultWorldGenerator(world)` is `terrain/StartupGenerators` (lives as long as the plugin, like
`StartupDatapackCheck`): it loads the project from its files (with game data: registries exist at STARTUP), takes
`netherforge.json`'s `worlds.<world>.generator`, publishes every generator with ore states from the same
`BlockCarriers` plan the session will make, and remembers what it answered; the adapter hands the server
`PaperWorldManager.startupGenerator(id)` (the same `Holder`-backed `ChunkGenerator` a project world gets, so hot
reload works the same, the server's seed is used, and `getFixedSpawnLocation` places the spawn). `bukkit.yml`'s id
after `NetherForge:` is ignored with a warning: the manifest says which. Ores in the spawn chunks, generated before
the session, are adopted by `CustomBlocks.start`'s reconcile of loaded chunks. `WorldGenerators.define` runs
`StartupGenerators.check` against the manifest: another generator (or none, or one named while `bukkit.yml` doesn't
ask) is `runtime.restart` and `ReloadResult.restart` (from `reloadAll`, since only a manifest change changes it), a
`seed` for the main world or a route with no generator named is `runtime.terrain`. References are compared by name
(a deleted file isn't a change), and one to a generator that doesn't exist never asks for a restart. The editor's
`server/setup.rs` writes the dev server's `bukkit.yml` route exactly when the manifest names one (tauri-backend).
`ensureMade` leaves the main world alone (it's loaded). `TestServer(startupWorlds = listOf("world"))` is what the
server asks in runtime tests (`TerrainTest`); `MainWorldScenario` (with `PaperServer(levelSeed, mainWorldGenerator)`,
which writes `bukkit.yml` each prepare) checks the real thing.

**Biomes.** `ProjectGenerator.biomes` maps each area's biome as the file names it to the server's key
(`BiomeKind.keyOf` in the project's namespace: `minecraft:plains`, a project biome's `showcase:ruby_grove`); the service
also `reloads` the biome kind (its file is start-up datapack data, so `StartupDatapackCheck` asks for the restart) and
reports `runtime.terrain` at the generator for an area whose project biome isn't running (the adapter makes it
plains). The natural spawner resolves `spawning.biomes` the same way (`NaturalSpawner`'s `home`), against
`WorldOps.biome`'s key.

**The Paper adapter** (`PaperWorldGenerators`, owned by `PaperWorldManager`): a `ChunkGenerator` and a
`BiomeProvider` per world, sharing one `Holder` that reads the published map by id on every call. The published
generators are `Prepared` on the main thread (their `BlockData` made once, the Bukkit biomes looked up); a world's
`TerrainGenerator` is bound lazily per (seed, min, max) from the `WorldInfo` the server passes, so a loaded world's
own seed is used. `isParallelCapable` is true: the generator is immutable and nothing here touches the main thread.
What a chunk gets is `generate(chunkX, chunkZ)`'s buffer written as runs (`setRegion`) in `generateNoise`; a custom
block's note block state is written like any, and `CustomBlocks` adopts it when the chunk loads (the watched
`chunk_load`; the fresh chunk is looked at, `BlockOps.find`). **The `shouldGenerate*` gotcha:** each asks for the
game's own stage to run _after_ ours, filling what ours left (`shouldGenerateNoise` true puts the game's stone
wherever the file has air), so noise, surface, bedrock, caves and mobs are false; structures are `structures.vanilla`;
**decorations are always true**: the game places each biome's features there (a game biome all of its own, a project
biome exactly its list), and the pieces of structures that started. The biomes are looked up in `Registry.BIOME`
(data-driven, so the start-up datapack's are there; one added since the server started isn't, and is plains with a
warning). **A world's biomes are fixed when it's made**: the server asks `BiomeProvider.getBiomes` once and sorts
every feature from that list (and `/locate biome` searches it), so a biome outside it would have features the server
can't place; `ProjectBiomes` keeps that first list and answers a later-published area's new biome with the world's
first one (logged once) until the world loads again. `getBaseHeight`
(the heightmap questions structure placement asks) answers from the generator, and `getFixedSpawnLocation` from
`TerrainGenerator.dryColumnNear`; both ask `surfaceAt`, which with 3D terrain (W5.11) is a column's topmost solid
block (an island's top, over the ground under it), the same block the game's `WORLD_SURFACE` heightmap finds. `PaperWorldGeneratorsTest` (Paper only: the world's blocks match format's
generator for the seed, the custom ore's state, decorations and areas' own terrain with a structure the server read,
the biome at columns, the spawn, a republish's chunks, decorations of a project biome and a game one, `/locate biome`;
its biomes are the contract server's own, registered by the contract plugin's `ContractBootstrap` through format's
start-up datapack as a project's are) and `TerrainScenario` (the example's generator in a real world, its areas a
featureless fixture biome so blocks compare exactly, ores adopted as `ruby_ore`, a project structure generating in a
project biome with its marker becoming a centity, hot reload and a restart) and `BiomeScenario` (the example's
`ruby_grove`: in the datapack, decorated with its cherry trees, found by `/locate biome`, a change asking for a
restart) are the tests; `TerrainTest` in the runtime is the fake's.

### Dimension types (W5.12)

A world's dimension type (`dimension_types/<id>.json`, start-up datapack data) is `WorldSettings.dimensionType`, the server's key
(`showcase:deep`): `nf.worlds.create`'s `dimension_type` (`projectDimensionType` in `WorldAdminImpl`: named as the calling package
names it, running, then `DimensionTypeKind.keyOf`) and `netherforge.json`'s `worlds.<name>.dimensionType` (`ManagedWorlds.ensureMade`,
which makes the worlds naming a terrain or a dimension type). `WorldManagerOps.dimensionTypes()` is what the server has (Paper:
the live `dimension_type` registry; the fake: the game's four plus the started datapack's files); one it lacks is a Lua
error, or `runtime.dimension-type` at the manifest (also for a world that exists with another type: a world keeps its own).
`ManagedWorlds` keeps it in the store (`worlds.dimension_type`) and refuses to load a world whose type the
server lacks: Paper would read the world's settings as missing and make it the overworld's height. Paper:
`PaperWorldManager.create` calls `PaperVersion.prepareDimension(creator, type)` before `createWorld` (saves the world's
generation settings with the type, see minecraft-versions; removed again when the server doesn't make the world), and the
server keeps it, so `load` needs nothing. The main world's is the start-up datapack's replaced `minecraft:overworld`
(`DatapackOps.mainWorld`: `level-name` from `server.properties`, read by `NetherForgeBootstrap` and handed to the restart
check too). The fake reads a world's heights from the started datapack's type (`FakeWorldManager.heightsOf`). Tests:
`DimensionTypeTest` (runtime), `PaperWorldGeneratorsTest`'s dimension case, `DimensionTypeScenario`.

### Biomes and new chunks in scripts (W5.9)

- **`Block:biome()`** is `WorldOps.biome` (the chunk's own biome storage, the cell of 4×4×4 blocks; null in an
  unloaded chunk), spelled by `PackageNames.spellBiome` as the reading package writes a biome: a project biome bare (`ruby_grove`), another
  package's `ns:id`, the game's `minecraft:x` (what `RefKind.BIOME` means in files).
- **`World:locate_biome(id, options)`** is an async method (lua-api, "Async functions"). `WorldImpl` names the id
  as a file would (`PackageNames.biome`: the game's written `minecraft:x`, a bare id the calling package's own, `ns:id` a
  package's, held to its exports like any resource; a bare id is never the game's, and the error says how to write it),
  then checks the key on the main thread (`RegistryKey.BIOME` in the live `GameData`, which has the datapacks' biomes too)
  and the radius (1 to `BIOME_SEARCH_LIMIT`, `/locate biome`'s 6400), asks `WorldOps.biomeSearch` for a `BiomeSearch`
  (the world and the biome resolved there, on the main thread) and runs it on the workers' `"biome search"` lane;
  nothing found is a `WorkFailed` (`nil, "no minecraft:desert within 6400 blocks"`). On Paper it's
  `World.locateNearestBiome(origin, radius, 32, 64, biome)`, which asks only the world's biome source and the
  climate sampler (what the server's chunk worker threads use while generating), never a chunk, so it's safe off the
  main thread (read in `CraftWorld` and `BiomeSource.findClosestBiome3d` on 1.21.11 and 26.3), and a biome the
  generator never places (`possibleBiomes`) is nowhere at once. With a project generator, it's
  `PaperWorldGenerators`' `BiomeProvider` answering, which is immutable and thread-safe already. The fake's
  generator puts `defaultBiome` everywhere but `biomes`' blocks, and records `biomeSearches`.
- **`chunk_generated`** (on `World` and `nf`, payload `ChunkEvent`) is a generated, watched event: `PaperGameEvents`
  has its own `@Watched` listener on `ChunkLoadEvent` that passes only `isNewChunk` (a chunk reaches the server's
  `ChunkLoadEvent` once it's whole: terrain, structures and decorations), so watching it costs one check per chunk
  load. A chunk generated while nobody listened is never heard. Tests: `BiomeTest` (runtime, the fake),
  `WorldOpsContract`'s biome search (run off the main thread on Paper), `PaperGameEventsTest`'s chunk step and
  `TerrainScenario`'s last step (a project generator's biomes read, searched and heard on a real server).

## Advancements and the start-up datapack

`NetherForgeBootstrap` (Paper's bootstrapper, `paper-plugin.yml`) builds the
datapack with `StartupDatapackFiles.build` (format's `StartupDatapack`) before
the worlds load and registers it (`PaperDatapacks`, the `DatapackOps`
platform group: `format`, `started`, `textJson`); `ServerVersionFile` gives
the pack format. `StartupDatapackCheck` lives as long as the plugin: after a
reload or `/nf reload` it builds the datapack again from the files, compares it
with `started`, and sets `ReloadResult.restart`, the `runtime.restart`
problem and a warning once. The `Advancements` service maps a project id
(`treasure_hunter`, a package's `ns:id`, `minecraft:…` for the game's) to
the server's id; the player's advancement functions take it plus an optional
criterion. Progress is the player's and the server's own, kept in the world
across restarts. Adding or removing a recipe makes the game reload every
online player's advancements from their file, so `PaperRecipes` first writes
them (`PaperVersion.saveAdvancements`, once per tick); `RecipeOpsContract`
holds it.

Project biomes (`data/<ns>/worldgen/biome/<id>.json`, format's `BiomeJson` for the server's data pack format), dimension
types (and the main world's replaced overworld type) and generating structures are in it too, and so are the
project's own datapacks (`datapacks/<id>/`, W5.5): their files for the server's format, copied as they are.

**A refused datapack.** The game checks a datapack's worldgen only as it loads it, and one entry it can't read stops it
loading every datapack, so the server stops before any plugin enables. Nothing raises an event for it, so the bootstrap
(only when the pack it built has something of the project's datapacks, `StartupDatapackFiles.Start.passesThrough`)
listens to the server's own Log4j for the game's report (`PaperVersion.watchRegistryErrors`, `RegistryErrorLog` in
paper-internals: an appender on the root logger for the `Registry loading errors:` message, the same on every supported
version) and writes it with the hash of the pack it refused (`DatapackRefusal`, `plugins/NetherForge/datapack-refused.json`).
The next start (`StartupDatapackFiles.forStart`, shared by the bootstrap, `StartupDatapackCheck` and the fake) builds the
pack, and while its hash is the refused one builds it again with `passThrough = false` (no datapacks, nor what names
them); `DatapackOps.refused` says so, `StartupDatapackCheck` compares against that pack and reports the report as
`runtime.datapack` problems at the file that wrote each entry the game named (`StartupDatapack.sourceOf`), and once a
file changes the hash differs and a restart tries them again. `onEnable` (the server loaded its datapacks) stops
listening and deletes a refusal that's no longer the one it started without. Tests: `DatapackTest` (runtime, the fake:
`FakeDatapacks.refusal` stands for the file), `DatapackScenario` (a real refusal on every version).

Dialogs with `pauseMenu`/`quickActions` are in the same datapack (the game's
dialog registry and tags). Their buttons come back as a custom click
(`PaperEvents.onCustomClick` → `PaperDialogs.customClicked` →
`PlatformEvents.customClicked` → `Dialogs.registryClicked`); the fake's
`dialogs.click(player, id, answers)` does the same in tests.

## Managed worlds, borders and structures

`world/WorldSpawnRates.kt` applies `netherforge.json`'s `worlds` (per world,
`spawnLimits` and `spawnIntervals` by spawn category) through
`WorldOps.setSpawnLimit`/`setSpawnInterval`: in `start()` for every named
world that's loaded (so a manifest reload, which restarts the whole session,
re-applies them over what scripts set), and in the `worldLoaded` service hook
for a world loaded or created later by anyone (`RuntimeGameEvents.worldLoad`
calls it before scripts hear `world_load`). A negative value to the
Platform call means "the server's own setting" (`bukkit.yml`); the file
only holds 0 or more.

`world/ManagedWorlds.kt` decides which worlds a project may unload: those
its scripts created (kept in the store's `worlds` under the project's
namespace, so a restart keeps the claim) or `netherforge.json`'s `managedWorlds` names, never the main
world. Worlds are the server's: a module restart or a full reload leaves them
loaded. `nf.worlds.copy` is an async function, built only from the generic
machinery (plugin-runtime skill, "Threading"): `ManagedWorlds.copy` checks the map and
the name at once, asks the adapter what the copy is (`WorldManagerOps.copy`
answers a `FileWork`; `delete` too, on the main thread, where the server
says where the files are), runs it on the workers' `world files` lane (copies
and deletions in the order asked for), then loads the copy on the main
thread (`session.async.mainThread`) and gives the `World`, or a `WorkFailed`
whose words are the script's `err` (and the log's). A script that stops
first isn't called, but the world is made. A full reload first leaves the
copy's files saved and still the project's, unloaded: loading it was the old
session's step, dropped with it; `nf.worlds.load` loads it. Where a world's files live is the version's business (`PaperVersion.worlds`,
a `WorldStorage`): from 26.1 a world is a dimension inside the main world's
storage (`world/dimensions/minecraft/<name>`), and a map copied to
`<container>/<name>/` is imported by Paper's own legacy-world migration
(`DimensionStorage`); before, it's a folder of its own there, and a map's
`level.dat` and overworld are copied into one (`WorldFolders`).

`structure/StructureStore.kt`: the project's `structures/<id>.nbt`, and those
scripts save, in `<data>/.nf/structures/` (never the project: a running
server doesn't write project files; the bridge's `save_structure` saves into a
temp file there and answers the bytes, for the editor to write).
The store also knows the project's builds (`builds/<id>/`, format's `BuildKind`; it reloads both kinds): `size` of
one is its whole parts plus its last part's size, `pieces(id)` what a gradual placement goes through (a structure's
one file, or a build's parts bottom up, each with its offset in the build and a check that its size is what its place
in the grid says), `place` refuses one (it would be one tick's work) and `save` refuses its id.
`structure/GradualJobs.kt` is `world:place_gradually` and `world:fill_gradually`: jobs owned by the calling scope (the
binding names it before the call: `host.callerScope`, which `marshal.caller` sets), ticked in `TickPhase.WORLD`,
sharing `structures.blocks-per-tick` (`StructuresConfig`) each tick in turn and in equal shares under each job's own
`per_tick`, at most `structures.max-jobs-per-package` per package (past it the stage fails at once). A placement goes
through `StructureOps.placer` a part at a time (the adapter reads a part for its placer alone and lets it go: Paper
copies each saved `BlockState` to its place, data mirrored then turned, `update(true, false)`; the fake writes its
states), then a second read of only the parts with entities, after every block; `StructureTransform` (platform) is
the game's own transform about the corner, shared by both adapters and tested once (`GradualTest`). A fill goes a
chunk column at a time, loading chunks as it reaches them. A job finishes in the same step as its last block (so its
value is heard at the next tick's `ASYNC`), fails with `WorkFailed` (world gone, a part unreadable or the wrong size),
and is dropped when its scope is released or the session stops: what it did stays. `/nf scripts` counts `gradual`.
`structure/StructureSpawns.kt` is centities in structures: a template holds
`marker` entities tagged `nf.centity.<centity>` (`StructureMarker.TAG_PREFIX`;
`Entity.scoreboardTags`, which a structure's saved entity NBT keeps, so the game
places, turns and mirrors the marker with the template however it got into the
world). The adapter raises `PlatformEvents.structureMarkersLoaded` from
`EntitiesLoadEvent` (a freshly generated chunk raises it too: checked on a real
26.3 server), one tick later because the marker is removed; the service also
scans `EntityOps.structureMarkers()` when it starts, after a centity reload
(`follows` the centity kind) and after `world:place_structure` with
`entities = true` (`StructureStore`'s `placedWithEntities`). Once only: the
marker is removed first (`EntityOps.remove`), then the centity spawned at its
position and yaw on the main thread; a marker whose centity the project lacks,
or whose chunk can't be reached, stays (one warning per centity). Natural
spawning (W5.3, `centity.json`'s `spawning`) is the spawner's, not this
service's. The fake has `FakeEntities.placeMarker`.
`WorldManagerOps.saveFiles` saves and flushes a world for the bridge's
`save_world` and says where its `level.dat` and dimension folder are. `StructureOps` caches a read file until
`forget` (a reload of it, or a save under that id). Borders are
`BorderOps` by `BorderOwner` (a world, or a player's own, which the adapter
keeps and shows again after a respawn or a change of world, and forgets on
quit).

## Mob goals

`mob:goals()`, `remove_goal` and `clear_goals` go straight to `MobGoalOps`;
`mob:add_goal` is hand-written in the prelude (its wrapper checks the definition
with `check_shape`; the body wraps each callback to call it with the mob, the yes-or-no ones
answering a real boolean, aliased for slow-script warnings) and hands the
core primitive `goals.add` the running scope and the functions, which
`mob/MobGoals.kt` keeps as refs in a `LuaGoal` (the adapter's
`GoalCallbacks`).

- **Owned by a scope.** The goal belongs to the scope whose code added it
  (like a handle's `:on`); `MobGoals.scopeReleased` takes every goal of a scope
  that stops off its mob, so after a module restart or a full reload the AI
  never calls into a dead scope or Lua state. A dropped goal goes `live =
false` first, so not even its `stop` runs then. A mob that unloads or dies
  (`entitiesUnloading`, `entityGone`, and a sweep every `SWEEP_TICKS` for
  anything missed) forgets its goals: Paper's goals live on the entity object,
  which a chunk load makes anew.
- **Callbacks** run through `Scripts.callKept`: a budget frame with the
  scope's budget, like a handler. A failure (error or overrun) is reported
  through `handlerFailed` with its file and line, and the goal is dropped at
  once: it would fail again every tick.
- **Never change goals inside the AI's loop.** Paper calls a goal from
  inside `GoalSelector.tick`, which is iterating the mob's goals; adding or
  removing one then breaks it (and `removeGoal` calls the removed goal's
  `stop`, which may be Lua too). So while any callback runs (`calling > 0`),
  `MobGoals` queues add, remove, clear and drops, and `tick()` (a step of the
  runtime's tick, which on Paper comes before entities tick) makes them.
  The fake's AI (`FakeMobGoals.think`, run after every `TestServer.tick`)
  throws on such a change, so a test catches it.
- **Selectors.** Paper puts a goal whose types include `TARGET` in the mob's
  target selector and any other in its goal selector, so the runtime lets a
  goal claim `target` only alone. A goal claiming nothing gets
  `UNKNOWN_BEHAVIOR` on Paper (and is one at a time with others like it).
  Paper's API has no priority for a goal, so the adapter reads every goal's
  from the server's `WrappedGoal`s (`PaperVersion.goalPriorities`, in
  paper-internals, Mojang-named through paperweight-userdev like the bots): the one other place the adapter
  reaches past the Paper API.

## Screens (`screen/`)

`Screens` is one service for the project's screens (`ScreenKind`), the ones scripts make (`nf.screens.create`,
owned by their scope), their fonts (`ScreenFontKind`) and videos (`VideoKind`). It keeps which screens there are,
their map numbers, who each is drawn for (`view`), fonts and colours, and hands the rest to its parts (one class
each, all in `screen/`, which only `Screens` makes): `MapSending`, `ScreenFrames`, `ScreenAim` and `ScreenVideos`.
`ScreenState` is one screen; only the screen package changes one (its setters are private, its changes named:
`moveTo`, `reconfigure`, `showTo`, `hideFrom`, `markRemoved`, `resize`), and the implementations in `api/` read it.
The pieces:

- **Pixels.** A `Canvas` per screen (and one per player's own view, `for_player`: `ScreenState.personal`) holds a
  map colour a byte, and every write marks the changed rectangle of each map it touches. `Raster` draws shapes,
  `ScreenText` lays out and draws MiniMessage (format's `MiniMessagePass`) in a `ScreenFont` (the game's from
  `ScreenOps.font`, Bukkit's `MinecraftFont`; a project's pixels, bitmap or TrueType font, the last two through AWT,
  headless), `ScreenImages` scales and dithers a pack texture with format's `Dither` against `MapPalette` (the live
  server's `GameData.mapColors`). Colours are `#rrggbb` (nearest map colour) or `"clear"`.
- **Sending** (`MapSending`). Once a tick (`TickPhase.UPKEEP`, after scripts and videos drew) each `Viewer` gets
  each map's changed rectangle (`ScreenOps.sendMap`, the game's map packet with a patch), within
  `screens.bytes-per-tick` a player (`ScreensConfig`, at least a whole map and its `PACKET_COST`, or a new viewer
  would never be sent one); what doesn't fit stays in the viewer's `pending`, merged with later changes, so a video skips
  frames for a slow player rather than falling behind. Who's a viewer is worked out every `VIEW_EVERY` ticks (and
  on show/hide/place): near (range of the middle, or everyone while unplaced), shown, not hidden; a new viewer (or
  one back from another world, whose client dropped the maps) is sent everything.
- **Maps.** Numbers from `MAP_BASE` (1e9) up, which the server never makes, so it has no saved data for them and
  never sends anything of them itself. A project screen's and a keyed screen's are in the store for good
  (`screen_maps`, by namespace, screen and a stable tile number `row * 16 + column`, so resizing keeps them). No
  number is given twice: the next one is kept too (`screen_map_next`), past every one ever given, so
  an item of a screen that's gone shows nothing rather than another screen. A script's `screen:remove()` of a keyed
  screen gives its key's numbers up (`Store.ScreenMaps.forget`, per tile, keyed as `put` is), so a script making a
  screen per player doesn't fill the table; a scope ending removes its screens without giving anything up.
- **Frames** (`ScreenFrames`, which also knows whose each frame is). `ScreenOps.hang` hangs a fixed, invisible item frame tagged `EntityRole.SCREEN` (instance: the
  screen's UUID, node: `column,row`), never saved with its chunk: `hang` checks every `HANG_EVERY` ticks and hangs
  what's missing where the chunk's entities are loaded, and `stop` takes them all down. Centities' stray cleanup
  leaves `SCREEN` tags alone; `entitiesLoaded` here removes a frame no screen owns.
- **Clicks** (`ScreenAim`). `ServerEvents.entityClicked` asks centities, then `click` (the frame's screen, the pixel
  from the player's sight through `Placement.hit`); `playerInteract` asks `swing` first, a ray from the eye against
  every placed screen within its click range, a block in front (`raycastBlocks`) in the way. One click per player
  per tick (an attack on a frame also swings). `hover`/`leave` run only while something listens, and work out each
  player's aim once a tick however many screens listen.
- **Videos** (`ScreenVideos`). `NfvReader` reads `video.nfv` a frame at a time (key frames every two seconds: a
  jump draws from the key before), **never on the main thread**: a video file is opened once (its header and index,
  as it's first played) and shared by every playback of it (closed on the lane after the last ends), and each
  `Playback` has a `FrameFeed` that reads and inflates the frames it will draw next (up to `FrameFeed.AHEAD` past
  the one shown, and the run from a key frame after a jump) on the workers' `"videos"` lane. A `Playback` moves
  `speed / 20` seconds a tick and draws the frames it passed onto its canvas once the feed has them; until then the
  picture holds (a slow disk shows as a held frame), and a read that failed ends it `stopped`. In a test, the tick's
  wait for idle workers makes the frames there by the next tick. Its
  sound: the whole `audio.ogg` (`video.<id>`) played to the listeners (viewers within `audio_range`, following the
  player, category `record`) while it plays uninterrupted from its start; after a pause, seek, speed or volume
  change, the five-second piece starting at the next boundary (`video.<id>.<n>`), since the game can't start a
  sound mid-file. `problems()` says `video.stale` when a header's size, rate, dither, fit or sound differs from
  the file. A playback belongs to the scope running when `screen:play` was called (`LuaHost.runningScope`: a handle
  method has no caller scope of its own).

`ScreenTest` is the model: what reaches each fake player's maps (`FakeScreens.pixel`, applied patch by patch);
`FrameFeedTest` holds the feed to its lane. AWT runs headless: `NetherForgeRuntime.enable` sets `java.awt.headless`
before anything of AWT loads, unless the server's owner set it.
