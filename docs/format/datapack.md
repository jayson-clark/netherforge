# Datapacks

For experts: the game's own worldgen JSON, written by hand (density functions,
noise settings, noises, biomes, configured and placed features, carvers,
structures, structure sets, template pools, processor lists, world presets…)
and passed through to the server as it is, beside what NetherForge writes
itself. Anything the game's datapacks can say about generating a world, a
project can say this way, and the game's own documentation and the community's
tools (generators, viewers, the vanilla files to copy from) apply, because a
datapack here is laid out exactly as the game's are:

```
datapacks/
  ruby_boulders/
    pack.mcmeta
    data/showcase/worldgen/placed_feature/ruby_boulders.json
    mc1_21/data/showcase/worldgen/configured_feature/ruby_boulder.json
    mc26_1/data/showcase/worldgen/configured_feature/ruby_boulder.json
    mc26_3/data/showcase/worldgen/feature/ruby_boulder.json
```

Each folder of `datapacks/` is one datapack: its `pack.mcmeta`, and its files
under `data/` and in its overlays' folders. Its id is only the folder's name:
what's in it is named as the game names it (`showcase:ruby_boulders`). Copy a
datapack you have into `datapacks/`, or make an empty one in the editor (New
datapack): its `pack.mcmeta` is written for your server's data pack format
alone, which the editor knows once the dev server has run. The editor lists a
datapack's files and opens each as JSON.

Most projects never need one: a [terrain](terrain.md) and
[biomes](biome.md) are the way to shape a world. A datapack is for what those
don't do, such as replacing the game's own terrain or adding features of your
own.

## `pack.mcmeta`

The game's file, in the game's spelling:

```json
{
  "$schema": "../../.netherforge/schema/datapack.schema.json",
  "pack": {
    "description": "Boulders of red terracotta, for the ruby grove",
    "min_format": 94,
    "max_format": 121
  },
  "overlays": {
    "entries": [
      { "min_format": 94, "max_format": 94, "directory": "mc1_21" },
      { "min_format": 101, "max_format": 107, "directory": "mc26_1" },
      { "min_format": 121, "max_format": 121, "directory": "mc26_3" }
    ]
  }
}
```

| Key                | Meaning                                                                                                                                                   |
| ------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `pack.min_format`  | Required. The oldest **data pack format** the pack's files are written for: a number (`94`), or `[major, minor]` (`[94, 1]`).                             |
| `pack.max_format`  | Required. The newest: a number (`121`, any minor of it), or `[major, minor]`.                                                                             |
| `pack.description` | What the game's pack list would say: any text component. NetherForge only keeps it.                                                                       |
| `overlays.entries` | Folders beside `data/` (`directory`, holding a `data/` of their own) whose files replace the pack's, or add to them, for a range of formats of their own. |

Nothing else is read from it (`filter`, `features` and `language` aren't
something worldgen needs, and are refused as unknown keys).

### Versions

The game's worldgen JSON changes between Minecraft versions, so a datapack says
which ones its files are for, as every datapack does, by data pack format:
1.21.11 reads format 94.1, 26.1.2 format 101.1, 26.2 format 107.1 and 26.3
format 121 (a server's is in its jar's `version.json`, `pack_version.data_major`
and `data_minor`; the game's documentation lists them all).

- A server whose format is outside `min_format` to `max_format` leaves the
  whole datapack out of what it loads. The editor says so against the
  project's [target version](project.md#netherforgejson) (`datapack.version`),
  and so does the server.
- **Overlays** are the game's own way of making one pack work on several
  versions: an overlay whose formats include the server's replaces the pack's
  files of the same name with its own, and adds the ones the pack doesn't have.
  Later entries win over earlier ones.

Keep what every version reads alike in `data/`, and each version's own shape of
a file in an overlay for the formats it's for. The example's placed feature is
the same everywhere, so it's in `data/`; its configured feature changed twice
(1.21.11's `forest_rock`, 26.1's `block_blob` with a `config`, and 26.3's
without one, in the folder 26.3 calls `worldgen/feature`), so each shape is in
an overlay of its own. 26.3 changed noise settings and density functions the
same way, so a pack replacing the overworld's terrain for every supported
version has one overlay for 1.21.11 to 26.2 and one for 26.3.

NetherForge applies the overlays itself as the server starts: the server gets
exactly the files for its own format, in one datapack with NetherForge's own.

## What goes in it

Only worldgen, and tags of blocks, pass through:

| Where                                                   | What                                                                                            |
| ------------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| `data/<namespace>/worldgen/<registry>/<path>.json`      | An entry of one of the game's worldgen registries: `worldgen/noise_settings`, `worldgen/biome`… |
| `data/<namespace>/tags/worldgen/<registry>/<path>.json` | A tag of one: `{ "values": ["showcase:caves/deep", "#minecraft:is_overworld"] }`.               |
| `data/<namespace>/tags/block/<path>.json`               | A tag of the game's blocks, which an item's `canBreak` and `canPlaceOn` name: `#showcase:soft`. |

A block tag holds the game's blocks and tags (`minecraft:dirt`,
`#minecraft:logs`), not the project's own blocks: an item names those
[directly](menu.md#items). Like every datapack file it's read when the server
starts, so a changed tag needs a restart.

The registries are the game's, and differ between versions (26.3 has
`worldgen/feature` and `worldgen/carver` where earlier versions have
`worldgen/configured_feature` and `worldgen/configured_carver`, and adds
`worldgen/material_rule`, `worldgen/material_condition` and
`worldgen/block_state_provider`): a file the target version reads (in `data/`,
or an overlay for its format) in a folder it has no registry for is a warning
(`datapack.registry`), since the game ignores it. Anything else in a datapack
(functions, loot tables, recipes, a `pack.png`) is an error (`datapack.file`):
NetherForge's own kinds are how a project has those.

### Namespaces

- **The project's own** (`showcase`): new entries. Their paths are ids, in
  folders (`caves/deep`), so the project's files can name them.
- **`minecraft`**: replacing one of the game's own entries, such as
  `data/minecraft/worldgen/noise_settings/overworld.json`. It must be one the
  game has (`datapack.override`): new entries go in the project's namespace. A
  tag of the game's (`data/minecraft/tags/worldgen/biome/is_forest.json`) adds
  to it, as the game merges tags, unless it says `"replace": true`.

Nothing else (`datapack.namespace`). A datapack can't write an entry
NetherForge writes for the project: one of its [biomes](biome.md) or
[dimension types](dimension-type.md), or a
[structure that generates](worlds.md#structures-that-generate) with its set and
pools (`datapack.conflict`), and two of the project's datapacks can't write the
same file.

Inside the files, ids are the game's: a bare id is the game's (`stone` is
`minecraft:stone`), as in any datapack, unlike the project's own files.

## Naming what it defines

The project's own files name a datapack's entries like their own resources:

- A [biome](biome.md#features) lists a placed feature by id: `"ruby_boulders"`
  for one of the project's datapacks (`showcase:ruby_boulders`), `minecraft:…` for
  the game's.
- A [terrain](terrain.md#biomes)'s area, a centity's
  [`spawning`](centity.md#spawning) and a structure that generates name a
  biome a datapack defines as they name the project's own: `"caves/deep"`.
- Find usages and renames don't follow into a datapack's files (they're the
  game's JSON, which NetherForge doesn't rewrite).

A datapack's files name the project's biomes, structures and
[dimension types](dimension-type.md), and each other's entries, as the game names
them: `showcase:ruby_grove`, a world preset's dimension of type `showcase:deep`.

### The main world, and the game's generators

What a datapack replaces in the `minecraft` namespace applies wherever the game
uses it. Replacing `data/minecraft/worldgen/noise_settings/overworld.json`
reshapes the terrain of every world the game generates as a normal overworld:
the server's main world (unless a [terrain](terrain.md#the-main-world)
makes it) and the worlds `nf.worlds.create` makes with `generator = "normal"`.
Replacing a biome, a feature or a structure set changes it in every world that
has it. A world that's already there keeps the chunks it has: only chunks
generated afterwards follow the new files.

A world preset or noise settings of the project's own (`showcase:floating`) is
used by the game where something names it, such as a world preset of
`minecraft`'s replaced to name it, or `level-type` in `server.properties`
(`level-type=showcase:my_preset`, for a main world the server makes anew).
`nf.worlds.create` doesn't take one: its `generator` is one of the game's types,
and its `terrain` one of the project's.

A [terrain](terrain.md) of the project's own makes its world's ground
itself, so noise settings and density functions don't reach it; a datapack's
biomes, features and structures do, through its areas.

## What's checked

NetherForge checks, in the editor and on the server:

- `pack.mcmeta` (`datapack.format`, `datapack.overlay`) and that the target
  version's format is the pack's (`datapack.version`);
- that every file is where a worldgen entry or tag goes and reads as JSON
  (`datapack.file`, `parse`), in a registry the target version has
  (`datapack.registry`), in a namespace the project may write
  (`datapack.namespace`, `datapack.override`), and isn't written twice
  (`datapack.conflict`);
- that a tag is `{ "values": [...] }` (`datapack.tag`);
- references (`datapack.reference`) in these places, and nowhere else:

  | In a file of                           | What it names                                                                                    |
  | -------------------------------------- | ------------------------------------------------------------------------------------------------ |
  | `worldgen/placed_feature`              | its `feature`: a configured feature                                                              |
  | `worldgen/biome`                       | its `features` (placed features, by step) and `carvers`                                          |
  | `worldgen/structure`                   | its `biomes` and `start_pool`                                                                    |
  | `worldgen/structure_set`               | each of its `structures`' `structure`                                                            |
  | `worldgen/template_pool`               | its `fallback`, and each element's `processors` and `feature`                                    |
  | `worldgen/world_preset`                | each dimension's `type`, its generator's `settings` and its biome source's `preset` and biomes   |
  | `worldgen/flat_level_generator_preset` | its `settings`' `biome` and `structure_overrides`                                                |
  | `worldgen/noise_settings`              | its `noise_router`'s density functions, its `material_rule` (26.3), and every `noise` it samples |
  | `worldgen/density_function`            | every `noise` it samples                                                                         |
  | a tag (`tags/worldgen/<registry>`)     | its values, entries or tags of its own registry                                                  |

  An id in the project's namespace must be an entry one of its datapacks (or
  NetherForge itself) defines, in any of its folders; one of the game's must be
  one the target version has (with its game data, and only in the files that
  version reads); another namespace must be a package's the project depends on.
  The same checks run over the game's own worldgen files on every supported
  version in NetherForge's tests, so they hold for each.

Everything else, whether a file is a valid entry of its registry (its fields, a
density function's arguments and the ids inside them, a feature's
configuration), only the server checks, as it starts.

## On the server

The game reads worldgen only as it starts, so a datapack is in the
[start-up datapack](advancement.md#on-the-server) with the project's own
biomes, structures and advancements: one with errors is left out of it, and
so is anything of NetherForge's that names what it defines (a biome listing one
of its features waits for it). A change needs a restart: on the editor's dev
server saving a datapack's file restarts the server; on a server of your own,
`/nf reload` says the server must restart (`runtime.restart`).

**When the server refuses it.** The game stops loading every datapack, and so
stops starting, over one entry it can't read: the server stops (the editor
shows the dev server stopped, its console the game's report). NetherForge hears
that report as the server refuses the start-up datapack and keeps it beside the
plugin (`plugins/NetherForge/datapack-refused.json`). Start the server again
and it starts **without the project's datapacks**, and without what of
NetherForge's names them, and shows the game's report as `runtime.datapack`
problems on the files it named (at `netherforge.json` for what it can't place).
Once the files change it tries them again: a reload says the server must
restart, and the next start has them.

## In packages

A package's datapacks write only its own namespace (`library:glow`), never
`minecraft`'s: replacing the game's worldgen is the project's to decide. A
project names what a package's datapack defines (`library:glow` as a biome of
an area) when the package exports the datapack (`"exports": { "datapacks": ["caves"] }`).

## Problems

[Problems](problems.md) lists them. The codes are `datapack.*`,
`reference.placed-feature` and `reference.biome` for what a project's file
names that no datapack defines, and `runtime.datapack` for what the server
refused.
