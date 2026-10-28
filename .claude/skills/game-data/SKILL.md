---
name: game-data
description: Where every Minecraft fact NetherForge uses comes from (the live server vs. the player's client install), the GameData interface, the per-user cache, install detection, and the rule that the plugin never needs a client-only fact. Read before touching anything that knows about blocks, items, entity types, shapes, fonts, textures or models.
---

# Game data

**NetherForge ships no Minecraft data.** Not in the repo, not in the editor, not
in the plugin jar. Every game fact comes from the game itself, at runtime.

## Two sources

| Fact                                                                                                                                                                    | Who knows it   | How NetherForge gets it                                                                                                       |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| Every registry and its tags (items, entity types, sounds, biomes, structures, features, loot tables…), block states, collision shapes, particle data kinds, pack format | the **server** | Plugin: the live server. Editor: the dev server exports it over the bridge (`export_game_data`) on first start for a version. |
| Textures, block/item models, blockstate files, fonts (glyph widths), lang files                                                                                         | the **client** | Editor only: imported from the player's own Minecraft installs (Settings → Minecraft).                                        |

Both land in the per-user cache: `<app data>/NetherForge/minecraft/<version>/{server,client}/`,
shared by every project that targets that version. Never in a project, never
in the repo.

## `GameData`

`packages/format/src/commonMain/.../game/GameData.kt`: what validation needs,
which is server facts only. Client facts (model boxes, the default font's
advances) aren't on it, so the plugin can't be asked for one: the editor
reads model boxes from the bundle and passes advances to text measuring
directly (and resolves them into the project for the server; see below).

- The editor answers it with the cached `GameDataBundle` (the dev server's
  export).
- The plugin implements it from the live server (`PaperGameData`).
- Tests use small hand-written `GameDataBundle` fixtures;
  `packages/format/testdata/game-data/bundle.json` is the shared one.

**Ids are registry lookups.** `registry(key)` is every id in one of the
game's registries and `tag(key, id)` what one tag holds (nested tags
resolved), both asked by the game's own registry name as a `RegistryKey`
(`RegistryKey.ITEM` is `minecraft:item`; `RegistryKey("minecraft:worldgen/biome")`
works the same). A check asks `game?.has(RegistryKey.ITEM, id) == false`:
`has` is null when the registry isn't known, and checks then say nothing. So a
new kind of id (biomes, damage types, loot tables) costs no new method, no
bundle field and no export code: name its registry, and add a `RegistryKey`
constant once a check uses it. Registry names are data, not the "Minecraft ids
as literals in logic" the repo rule forbids.

Only facts that aren't plain id sets have their own lookups: `block` (states:
properties and defaults; `BlockCarriers` reads the note block's from it, so which states hold custom blocks follows the game version), `collisionBoxes`, `particle` (the data kind a spawn
carries), `itemDurability` (live server only) and `resourcePackFormat`.

The adapter exports every registry and tag generically
(`MojangRegistries`, the adapter's `ServerRegistries`, from the server's full registry lookup, so worldgen
and reloadable registries like loot tables are there too; Paper's registry API
can't list them, see minecraft-versions). For 26.3 the export is
6.8 MB: about 5 MB of collision shapes, 1.2 MB of registries and tags (the
integration test prints its size), written once per version.

`GameData.mapColors` is the game's map colours by the byte a map pixel holds (`base * 4 + shade`, `0xAARRGGBB`,
alpha 0 for none), read off the server's `MapColor` table (`PaperVersion.mapColors`) and exported in the bundle
(`GameDataBundle.SCHEMA` 3): what screens match colours against and the editor converts videos to. Test fixtures
hold made-up palettes, never the game's.

The plugin asks its platform one server fact `GameData` doesn't carry: which
game rules exist (`WorldOps.gameRuleType`, which is a type, not an id set). A
sound for `play_sound` is checked with `GameData.has(SOUND_EVENT)`; a resource pack's
sounds (`ui/click`) aren't in that registry, so they're checked against the
project's reference index first. `world:spawn_particle` checks its options
against `GameData.particle`'s data kind, the same lookup particle effects use.

A biome (`biomes/<id>.json`) checks its entity types, sound events, particle (and that its `GameData.particle`
kind is `NONE`) and placed features (`RegistryKey.PLACED_FEATURE`, `minecraft:worldgen/placed_feature`) the same way,
and the biome editor suggests them from the same registries. Which features a game biome has, and in what order,
isn't exported: nothing checks a project biome's feature order against the game's.

A terrain (`terrain/<id>.json`) asks the same lookups: its blocks through `Rules.blockState` and the biomes
of its areas against `RegistryKey.BIOME` (`minecraft:worldgen/biome`), both skipped without game data. The plugin
resolves ids at publish time on the main thread, never on a chunk thread (`Bukkit.createBlockData`, the biome
registry), so a generator needs nothing of the game that chunk threads can't answer. A script's biome id
(`world:locate_biome`, named as a file names one: bare is the project's) is checked against the same registry on the live server, so a datapack's biome is one too;
the testkit's `FakePlatform.GAME` has a few biomes and their `is_end`/`is_forest` tags, what the examples name.

A project's datapacks (`datapacks/<id>/`) check the game's ids their files name, the registries their folders are and
the entries a `minecraft` file replaces against `GameData.registry`/`tag` (any registry by its key,
`minecraft:worldgen/noise_settings`), and the pack's range against `GameData.dataPackFormat` (`version.json`'s
`data_major`/`data_minor`, in the export since `GameDataBundle.SCHEMA` 2), only for the files the target version reads.
The live server's registries hold what the start-up datapack added, the project's own datapacks' entries included.

Every validator takes `GameData?`. **Null means "not imported yet"**: check
shape only (is it a well-formed block state?) and say nothing about
existence. Never fail a project because data is missing.

**The cache is versioned, not patched.** `GameDataBundle.schema` (written
always, though format's JSON leaves defaults out) says which shape wrote it.
Changing the bundle's shape, or what the export puts in it, raises
`GameDataBundle.SCHEMA`, the fixture's `schema` and the backend's
`GAME_DATA_SCHEMA` (`src-tauri/src/minecraft/cache.rs`) together; tests on
both sides fail until all three agree. A cache of another schema reads as no
cache, and the next dev server start exports it again, so no field is ever
"null because this cache is old". The CLI (`netherforge check`) treats it
the same way, through the generated `GAME_DATA_SCHEMA` constant.

**The script test runner is the one consumer that needs the data.**
`netherforge test` and the editor's Tests panel run a project on the real
bundle of its target version (`--game-data <file>` to the runner jar: the CLI
finds the cache file itself, `cache::game_data_file` does for the editor), so
what the fake server accepts is what the game has. Unlike validation, no
cache is an error (exit 2 / `unavailable`) saying to start the dev server
once, never a fallback to the testkit's `FakePlatform.GAME` fixture, which is
for the runtime's own tests and the repo's example/template tests (CI has no
real data to run them on). The runner also refuses a bundle of another schema
or another Minecraft version than the project's.

Ids are namespaced (`minecraft:stone`). Normalise with `GameIds.normalize`.
Block states parse with `BlockState.parse`; its `toString()` is canonical and
is the key shape tables use.

## The rule: the plugin never needs a client fact

If the server would need something only the client knows, the editor resolves
it at authoring time and writes it into the project as explicit data, or the
feature is designed so it isn't needed. The worked example:

- Hitboxes that follow a **model** shape (a stair) or a **text** quad are
  fitted by the editor into explicit `boxes`, with `fittedTo` recording what
  they were fitted to; the validator warns when that's stale.
- Hitboxes that follow a block **live** can only use `shape: "collision"`,
  which the server knows.
- The server measuring text (`nf.text.width`, placing a skin behind a centred
  title) needs the default font's advances. The editor writes them from the
  import into `fonts/default.json` (`DefaultFontFile`: `minecraft` + advances
  by code point), whenever the file is missing or differs from what it would
  write (`core/defaultFont.ts`, run after `refreshGameData` and when the file
  changes on disk). It's committed and deployed (`.netherforge/` is neither, and
  the plugin skips it). Format's `TextWidth` measures with it on both sides and
  answers null for anything it can't measure exactly; `DefaultFontValidator`
  warns when it's stale (`font.stale`) and gives anything that needs it
  `requireCurrent`/`checkCentredTitle` (`font.needed`). No import, no file: not
  a problem by itself (it's `font.needed` where something needs it); a failed
  write is (`editor.default-font`).

## Install detection (editor)

Launchers to detect on every OS, and where their client jars live:

- Vanilla launcher: `%APPDATA%\.minecraft\versions\<v>\<v>.jar`,
  `~/Library/Application Support/minecraft/versions/...`, `~/.minecraft/versions/...`
- Prism Launcher / MultiMC: `libraries/com/mojang/minecraft/<v>/minecraft-<v>-client.jar`
  under their data folders
- Modrinth App, CurseForge, ATLauncher: their own instance/library folders
- Or a client jar the user picks by hand.

If the target version isn't installed, say so and offer to open the launcher.
Previews degrade to placeholders; nothing errors.
