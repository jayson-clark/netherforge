# Biomes

A biome is one of the project's own places in the world: how warm and wet it
is, the colours of its sky, water, grass and leaves, the particles in its air,
its sounds and music, the mobs that spawn in it, and the game's features
(trees, flowers, grass, lakes, ores) it's decorated with. Each is one file,
`biomes/<id>.json`, with nothing beside it.

```json
{
  "$schema": "../.netherforge/schema/biome.schema.json",
  "climate": { "temperature": 0.7, "downfall": 0.8 },
  "colors": {
    "sky": "#f2a7c3",
    "fog": "#f7d3e0",
    "water": "#d94f7a",
    "waterFog": "#5a1028",
    "grass": "#c2406a",
    "foliage": "#e0587f"
  },
  "particle": { "particle": "minecraft:cherry_leaves", "probability": 0.01 },
  "sounds": { "music": { "sound": "minecraft:music.overworld.cherry_grove" } },
  "spawns": {
    "monster": [{ "entity": "minecraft:zombie", "weight": 100, "group": { "min": 2, "max": 4 } }],
    "animal": [{ "entity": "minecraft:sheep", "weight": 12, "group": { "min": 2, "max": 4 } }]
  },
  "features": {
    "vegetal_decoration": [
      "minecraft:patch_grass_plain",
      "minecraft:flower_cherry",
      "minecraft:trees_cherry"
    ]
  }
}
```

Every key is optional: `{}` is a temperate biome with the game's usual water,
no mobs and nothing growing.

## Naming one

A biome is named wherever the game's are: a [terrain](terrain.md#biomes)'s
area, a centity's [`spawning`](centity.md#spawning), a [structure that
generates](worlds.md#structures-that-generate). As every
[reference](references.md), the project's own is its id (`"ruby_grove"`) and
a package's `ns:id` (`"acme:grove"`, if it exports it); the game's are always
written in full, `"minecraft:plains"` (and a tag of them `"#minecraft:is_forest"`).
A plain id that isn't one of the project's biomes is an error
(`reference.biome`) that says how the game's are written.

On the server it's `<namespace>:<id>` (`showcase:ruby_grove`): what the F3 screen
shows and what `/locate biome showcase:ruby_grove` finds in a world that has it.

## `climate`

| Key                   | Meaning                                                                                                                         | Default |
| --------------------- | ------------------------------------------------------------------------------------------------------------------------------- | ------- |
| `temperature`         | -2 to 2. Below 0.15 it snows instead of raining and water freezes; it also tints grass and leaves without colours of their own. | 0.8     |
| `downfall`            | How wet, 0 to 1: with the temperature, the colour of grass and leaves without colours of their own.                             | 0.4     |
| `precipitation`       | Whether it rains (or snows) here when the world's weather does.                                                                 | true    |
| `temperatureModifier` | `frozen`: patches of it are colder, as in the game's frozen oceans; or `none`.                                                  | `none`  |

## `colors`

Each colour is written `#rrggbb`.

| Key             | Meaning                                                                                                 | Default               |
| --------------- | ------------------------------------------------------------------------------------------------------- | --------------------- |
| `sky`           | The sky.                                                                                                | the world's           |
| `fog`           | The fog in the distance.                                                                                | the world's           |
| `water`         | Water.                                                                                                  | `#3f76e4`, the game's |
| `waterFog`      | The fog seen under water.                                                                               | the world's           |
| `grass`         | Grass.                                                                                                  | from the climate      |
| `foliage`       | Leaves and vines.                                                                                       | from the climate      |
| `dryFoliage`    | Leaf litter and other dry leaves.                                                                       | from the climate      |
| `grassModifier` | How the grass colour is changed where it's drawn: `dark_forest` darkens it, `swamp` mottles it, `none`. | `none`                |

## `particle`

`{ "particle": "minecraft:white_ash", "probability": 0.01 }`: one of the game's
particles drifting through the air, at `probability` (more than 0, at most 1)
per block each tick. The game's own are around 0.01 or less. The particle must
be one that takes no options (`minecraft:dust` takes a colour, so it can't be
a biome's).

## `sounds`

Sound events of the game's (`minecraft:ambient.cave`):

| Key         | Meaning                                                                                                                                                                                               |
| ----------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ambient`   | A sound played over and over while a player is in it.                                                                                                                                                 |
| `mood`      | The game's cave sounds: `{ "sound": …, "tickDelay": 6000, "blockSearchExtent": 8, "offset": 2 }`, played after `tickDelay` ticks (at least 1) in the dark, `offset` blocks away (the defaults shown). |
| `additions` | `{ "sound": …, "chance": 0.01 }`: played at random, each tick at `chance` (0 to 1).                                                                                                                   |
| `music`     | `{ "sound": …, "minDelay": 12000, "maxDelay": 24000 }`: the track played here in place of the world's, again after `minDelay` to `maxDelay` ticks (the defaults shown).                               |

## `spawns` and `spawnCosts`

`spawns` is the game's mobs that spawn naturally in it, by spawn category:
`monster`, `animal`, `water_animal`, `water_ambient`,
`water_underground_creature`, `ambient` or `axolotl` (each category has its
own cap in a world; see [`worlds`](project.md#netherforgejson)). Each is a list:

| Key      | Meaning                                                             | Default |
| -------- | ------------------------------------------------------------------- | ------- |
| `entity` | Required. An entity type of the game's: `minecraft:zombie`.         |         |
| `weight` | How likely against the rest of its category, at least 1.            | 10      |
| `group`  | How many spawn together: `{ "min": 2, "max": 4 }`, each at least 1. | one     |

The biome's spawns are all of them: nothing of the game's own spawns there
unless it's listed. (Centities spawn by their own [`spawning`](centity.md#spawning),
which can name the biome.)

`spawnCosts` keeps a mob from crowding, as the game's soul sand valleys keep
their endermen apart: by entity type,
`{ "minecraft:enderman": { "charge": 0.7, "energyBudget": 0.15 } }`, each spawn
of it using `charge` of the `energyBudget` near it (both more than 0).

## `features`

The **placed features** the biome is decorated with, by the step of the
world's generation they're placed in, in order: `raw_generation`, `lakes`,
`local_modifications`, `underground_structures`, `surface_structures`,
`strongholds`, `underground_ores`, `underground_decoration`, `fluid_springs`,
`vegetal_decoration`, `top_layer_modification`. A placed feature is one of the
game's (`minecraft:trees_plains`, `minecraft:ore_iron`, `minecraft:lake_lava_surface`):
`/place feature` and the editor's completions list them. Or it's one a
[datapack](datapack.md) of the project defines, by its id: `"ruby_boulders"` for
`datapacks/<pack>/data/<namespace>/worldgen/placed_feature/ruby_boulders.json`
(a package's `"acme:boulders"`), which the example's grove lists in
`local_modifications`. **It has exactly the ones listed**, none of the game's
otherwise. A biome naming a datapack's feature waits, out of the start-up
datapack, while that datapack isn't in it (it has errors, isn't written for the
server's version, or the server refused it).

The game puts every feature of a step, across all the biomes of a world, into
one order, and refuses a world whose biomes list two of them in opposite
orders. Two of the project's biomes that do are an error
(`biome.feature-order`). The game's own biomes can't be checked: when you use
several of a game biome's features, keep them in the order it lists them
(the game's own files, `data/minecraft/worldgen/biome/<id>.json` in the server
jar, have them).

A biome has no carvers: the caves are the [terrain](terrain.md#caves)'s.

## On the server

The game learns biomes only while it starts, so they're in the
[start-up datapack](advancement.md#on-the-server), as
`data/<namespace>/worldgen/biome/<id>.json` in the game's own format for the
server's version (1.21.11 to 26.2 keep a biome's mobs in a list of their own,
26.3 as one of its attributes; NetherForge writes whichever the server reads).
A biome with errors is left out of it, and so is anything that names it there
(a structure that generates in it waits for it). A change needs a restart: on
the editor's dev server, saving a biome restarts the server; on a server of
your own, `/nf reload` says the server must restart (`runtime.restart`, a
warning).

A biome is part of a world through a [terrain](terrain.md#biomes)
whose area names it. Its features are placed on the terrain's ground as the
game places them in its own worlds, in every chunk generated after the server
started with it.

## In packages

A package's biomes are in its own namespace on the server (`library:grove`),
and a project names the ones it exports as `ns:id`.

## Problems

[Problems](problems.md) lists them. The codes are `biome.*`, and
`reference.biome` for a project biome that doesn't exist.
