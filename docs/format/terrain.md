# Terrain

A terrain shapes the land of a world your project makes, or of the
server's main world: hills and seas from noise, the layers the ground is made
of, caves, ores, decorations scattered on and in the ground (flowers, rocks,
your own [structures](worlds.md#structures) such as trees), and biome areas
chosen by climate, each with terrain of its own if you like, blended into its
neighbours across wandering borders. The game's blocks and your own
[blocks](block.md) go anywhere a block does. It lives at `terrain/<id>.json`,
and what the file can't say a Lua [script](#script) beside it can: the file hands
it stages (a column's height, blocks filled into a chunk). The editor draws it as
you change it: a map from above and a slice through the ground, from the same code
the server runs, the script included.

```json
{
  "$schema": "../.netherforge/schema/terrain.schema.json",
  "terrain": {
    "base": 66,
    "seaLevel": 62,
    "noises": {
      "hills": { "noise": { "frequency": 0.004, "octaves": 4 }, "amplitude": 28 },
      "detail": { "noise": { "frequency": 0.04, "octaves": 2 }, "amplitude": 3 }
    },
    "blend": 24
  },
  "layers": [{ "block": "minecraft:grass_block" }, { "block": "minecraft:dirt", "thickness": 3 }],
  "underwater": [{ "block": "minecraft:sand", "thickness": 2 }],
  "floor": { "block": "minecraft:bedrock" },
  "caves": { "caverns": {} },
  "ores": {
    "iron": { "block": "minecraft:iron_ore", "size": 8, "veins": 10, "minY": -32, "maxY": 64 },
    "ruby": {
      "customBlock": "ruby_ore",
      "size": 5,
      "veins": 6,
      "minY": -48,
      "maxY": 32,
      "distribution": "triangle",
      "biomes": ["snowy"]
    }
  },
  "decorations": {
    "flowers": {
      "block": "minecraft:poppy",
      "count": 10,
      "noise": { "frequency": 0.03 },
      "threshold": 0.2,
      "biomes": ["plains"],
      "on": ["minecraft:grass_block"]
    },
    "oaks": { "structure": "oak_tree", "count": 2, "chance": 0.6, "biomes": ["plains"] }
  },
  "climate": { "jitter": { "amplitude": 24 } },
  "biomes": {
    "plains": {
      "biome": "minecraft:plains",
      "temperature": { "min": -0.2 },
      "terrain": { "scale": 0.7 }
    },
    "snowy": {
      "biome": "minecraft:snowy_plains",
      "temperature": { "max": -0.2 },
      "layers": [
        { "block": "minecraft:snow_block" },
        { "block": "minecraft:dirt", "thickness": 3 }
      ],
      "terrain": {
        "scale": 1.6,
        "noises": {
          "peaks": {
            "noise": { "frequency": 0.012, "fractal": "ridged", "octaves": 3 },
            "amplitude": 22
          }
        }
      }
    }
  },
  "structures": { "vanilla": true }
}
```

Its id is how a world names it: `nf.worlds.create("realm", { terrain = "ruby_hills" })`, or
`netherforge.json`'s [`worlds`](project.md#netherforgejson) (see [worlds that use one](#worlds-that-use-one)).

## How a column is made

Every column of the world (one x and z) is made the same way, in this order:

1. **Terrain.** The height of its top block is `base` plus each of the `noises` (a value from -1 to 1 times its
   `amplitude`), rounded down; in a biome area with [terrain of its own](#an-area-s-own-terrain), that area's, blended
   with its neighbours' near a border. Everything below it is `stone`, topped by the `layers` from the surface down.
   Where the top is below `seaLevel`, the space up to it is `fluid`, and the `underwater` layers are used instead.
   With a [`density`](#3d-terrain-density), the ground is 3D instead: solid wherever its density says, around that
   height, so a column can have overhangs, arches and islands over it, each surface topped by the layers.
2. **Caves** are carved out of the ground, nowhere nearer the surface than each cave's `depth`.
3. **The floor** is laid at the bottom of the world.
4. **Ores** replace stone in veins.
5. **Decorations** are scattered: blocks and structures on the ground, on the sea floor, in the ground, and on the
   floors and ceilings of caves.

A file with a [script](#script) hands it stages of that: the column's **height** (given the file's, after step
1's noises), with a density the **density** at a point, a chunk's **terrain** (after step 1, before caves) and its
**decorations** (after step 5).

Each column is in a **biome area**, chosen from the climate, and the area gives it a biome, the game's or one of the
project's [own](biome.md) (its sky and water colour, its grass, the mobs that spawn, the features it's decorated
with) and, if it likes, its own layers and terrain. Ores, caves and decorations can keep to the areas they name.

Everything depends on the world's **seed** and on where a block is, never on which chunk was generated first, so
exploring in any order gives the same world, and the editor's preview for a seed is the world a server with that
seed makes.

## Noise

A noise is a smooth random pattern with values from -1 to 1, with a pattern of its own made for every use from the
seed. Each is a `NoiseDef`; every key is optional.

| Key          | Meaning                                                                                                                | Default        |
| ------------ | ---------------------------------------------------------------------------------------------------------------------- | -------------- |
| `type`       | `openSimplex2`, `openSimplex2s`, `perlin`, `value`, `valueCubic` or `cellular`.                                        | `openSimplex2` |
| `frequency`  | How tight the pattern is, per block (more than 0, at most 1): at 0.01 it changes over about a hundred blocks.          | 0.01           |
| `octaves`    | Layers of detail, 1 to 8.                                                                                              | 1              |
| `fractal`    | How octaves combine (with more than one): `fbm` (finer detail), `ridged` (sharp ridges), `pingPong` (bands) or `none`. | `fbm`          |
| `lacunarity` | How much finer each octave is (1 to 4).                                                                                | 2              |
| `gain`       | How much weaker each octave is (0 to 1).                                                                               | 0.5            |

The noise is [FastNoiseLite](https://github.com/Auburn/FastNoiseLite) (MIT), ported to the same code on the server and
in the editor, with the same numbers on both.

## `terrain`

| Key        | Meaning                                                                                                                                     | Default           |
| ---------- | ------------------------------------------------------------------------------------------------------------------------------------------- | ----------------- |
| `base`     | The height where every noise is 0.                                                                                                          | 64                |
| `seaLevel` | A column whose top is lower than this is flooded up to it.                                                                                  | 62                |
| `fluid`    | What the sea is made of.                                                                                                                    | `minecraft:water` |
| `noises`   | Noises added to `base`, by name: `{ "noise": …, "amplitude": 16 }`, the noise's -1 to 1 becoming that many blocks either way.               | none              |
| `blend`    | How far, in blocks, the heights of two biome areas whose terrain differs are blended across the border between them, 0 to 64. 0 is a cliff. | 16                |
| `density`  | Makes the ground 3D: see [3D terrain](#3d-terrain-density).                                                                                 | none              |

A world with no `noises` is flat at `base`.

### Heights

A terrain doesn't know the worlds it will shape, so nothing in it assumes a world's height: the server runs it for
each world with that world's own bottom and top (-64 and 320 in a normal overworld), and keeps every column's ground
inside them. A height a file names (a `base`, a `seaLevel`, a `minY` or a `maxY`) is checked against what any world can
have, -2032 to 2031 (`terrain.height`), and what lies outside the world it's run for is left out: an ore's veins below
its bottom, say. The editor's preview draws a normal overworld's heights.

## 3D terrain: `density`

Heights make hills and valleys, but every column is solid up to its top. A `terrain.density` makes the ground 3D, so
it can lean out over itself: overhangs, arches, sheer cliffs, and islands floating in the sky.

```json
{
  "$schema": "../.netherforge/schema/terrain.schema.json",
  "terrain": {
    "base": 70,
    "seaLevel": 62,
    "noises": { "hills": { "noise": { "frequency": 0.006, "octaves": 3 }, "amplitude": 16 } },
    "density": {
      "noises": {
        "overhangs": { "noise": { "frequency": 0.03, "octaves": 2 }, "amplitude": 10, "squash": 2 }
      },
      "islands": { "y": 150, "thickness": 28, "threshold": 0.35, "biomes": ["sky"] }
    }
  },
  "layers": [{ "block": "minecraft:grass_block" }, { "block": "minecraft:dirt", "thickness": 3 }],
  "underwater": [{ "block": "minecraft:sand", "thickness": 2 }],
  "caves": { "caverns": {} },
  "decorations": {
    "flowers": { "block": "minecraft:poppy", "count": 16, "on": ["minecraft:grass_block"] }
  },
  "climate": { "temperature": { "frequency": 0.004 } },
  "biomes": {
    "cliffs": {
      "biome": "minecraft:windswept_hills",
      "temperature": { "max": 0 },
      "terrain": {
        "base": 84,
        "density": {
          "scale": 1.5,
          "noises": {
            "arches": {
              "noise": { "frequency": 0.025, "octaves": 2 },
              "amplitude": 14,
              "squash": 0.6
            }
          }
        }
      }
    },
    "sky": {
      "biome": "minecraft:plains",
      "temperature": { "min": 0 },
      "terrain": { "base": 56, "scale": 0.5 }
    }
  }
}
```

**How it works.** Every block has a **density**, in blocks: how far its column's height is above it (the height the
`noises` above make, so 2.5 for the block two below the top, -0.5 for the air on it), plus each of the density's
`noises`, a 3D noise (-1 to 1) times its `amplitude`. A block is solid where its density is above 0. A 3D noise of
amplitude 10 moves the ground's surface up to 10 blocks up or down, and by a different amount at each height, which is
what leans it out over the air: a ledge sticking out, a hollow under it. Deeper below the height than the amplitudes
add up to, the ground is always solid; higher above it, always air. With no 3D noises (`"density": {}`), the ground is
exactly the heights' ground.

| Key       | Meaning                                                    | Default |
| --------- | ---------------------------------------------------------- | ------- |
| `noises`  | 3D noises that move the ground's surface, by name (below). | none    |
| `islands` | Land floating in a band above the ground (below).          | none    |

Each of the `noises`:

| Key         | Meaning                                                                                                                                               | Default |
| ----------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- | ------- |
| `noise`     | The [pattern](#noise), read in 3D.                                                                                                                    |         |
| `amplitude` | How far it moves the surface, in blocks, either way.                                                                                                  | 16      |
| `squash`    | How much flatter the pattern is up and down than across, more than 0 and at most 16: 2 makes ledges and shelves, 0.5 tall cliffs, pillars and arches. | 1       |

**Islands** float in a band `thickness` blocks tall around height `y`: land wherever their 3D noise is above
`threshold` in the band's middle, needing more of it towards the band's top and bottom, so each island is thickest in
the middle and thins to nothing at the band's edges. They float over the areas `biomes` names (all of them without
it), and fade out across those areas' borders.

| Key         | Meaning                                                                         | Default                          |
| ----------- | ------------------------------------------------------------------------------- | -------------------------------- |
| `y`         | The middle of the band.                                                         | 160                              |
| `thickness` | How tall the band is, 2 to 256 blocks: the tallest an island can be.            | 32                               |
| `noise`     | Their pattern.                                                                  | `openSimplex2`, 0.012, 2 octaves |
| `threshold` | Where islands start, -1 up to but not including 1: higher is fewer and smaller. | 0.4                              |
| `biomes`    | The [biome areas](#biomes), by name, they float over.                           | all of them                      |

**An area's own density.** A biome area's `terrain.density` changes the 3D noises in its columns as its `scale` does
the heights: `scale` multiplies the file's 3D noises (0 for none, at most 16, default 1) and its own `noises` are
added. Near a border the areas' densities are blended like their heights, over `terrain.blend`. An area can only have
one in a file that has a `terrain.density` (`terrain.density`).

**Everything after the terrain follows the solid blocks.** The `layers` top every surface from it down: the ground,
the top of an overhang, the ground under it, an island (an overhang's underside is what lies that deep under its top:
the layers if it's thin, stone if not). Every space at or below `seaLevel` is the `fluid`, and a surface under it gets
the `underwater` layers. Caves are carved only where at least their `depth` of solid ground is above, so they don't
open an overhang or an island from below. A `surface` or `underwater` decoration goes on any of a column's tops (each
try picks one), so flowers and trees grow on islands and ledges as well as on the ground under them. A column's
height for everything else (the preview's map, a world's spawn, where the game puts its structures) is its topmost
solid block.

**What it costs.** The 3D noises are read every 4 blocks across and 8 up, as the game reads its own, and blended
between, so a chunk with a density takes about one and a half times as long as one of heights. Keep `squash` and
`frequency` such that a feature is several blocks across: smaller ones are blended away.

## Layers, stone and the floor

`layers` is a list from the surface down: `{ "block": "minecraft:grass_block", "thickness": 1 }` (`thickness` 1 to 256,
default 1). Under all of them is `stone`: `{ "block": "minecraft:deepslate" }` (default `minecraft:stone`).
`underwater` takes the place of `layers` in a column whose top is under the sea (if it's empty, `layers` stay).

`floor` is the bottom of the world: `{ "block": "minecraft:bedrock", "thickness": 5 }`. The lowest layer is all of it,
and each layer above has less, as the game's bedrock does, from the world's seed. Without a `floor` the stone goes down
to the bottom of the world.

Each of them (a layer, the stone, the floor) names one block, one of two ways (`terrain.one-block`):

| Key           | Meaning                                                                                                                                       |
| ------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| `block`       | A block state of the game's: `minecraft:grass_block`, or with properties, `minecraft:oak_log[axis=y]`.                                        |
| `customBlock` | One of the project's [blocks](block.md), by id (`loam`, a package's `acme:marble`). See [custom blocks](#custom-blocks-in-a-generated-world). |

A layer and the stone need one; the floor's default is `minecraft:bedrock`.

## `caves`

Caves are carved out of the ground by name. Each is a noise, a `type` and a `threshold`, between `minY` and `maxY`,
and never within `depth` blocks of the surface (so caves don't open the whole ground).

| Key         | Meaning                                                                                                                                                           | Default                             |
| ----------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------- |
| `type`      | `cheese`: big rooms with pillars, carved where the noise is above `threshold`. `spaghetti`: winding tunnels, where two noises are both within `threshold` of 0.   | `cheese`                            |
| `noise`     | The pattern.                                                                                                                                                      | `openSimplex2`, 0.03, 2 octaves     |
| `threshold` | `cheese`: -1 to 1, higher is smaller rooms. `spaghetti`: more than 0, at most 1, higher is wider tunnels.                                                         | 0.55 (`cheese`), 0.08 (`spaghetti`) |
| `minY`      | The lowest it goes.                                                                                                                                               | -56                                 |
| `maxY`      | The highest it goes.                                                                                                                                              | 56                                  |
| `depth`     | How far below the surface it stays.                                                                                                                               | 4                                   |
| `fade`      | How many blocks above `minY` and below `maxY` it narrows over, 0 to 256: rooms shrink and tunnels thin towards each end, so bands blend instead of stopping flat. | 0 (a flat cut)                      |
| `biomes`    | The [biome areas](#biomes), by name, whose columns it's carved in.                                                                                                | all of them                         |

## `ores`

Veins of a block, scattered through what the ground is made of, by name.

| Key                   | Meaning                                                                                                   | Default            |
| --------------------- | --------------------------------------------------------------------------------------------------------- | ------------------ |
| `block`               | A vanilla block state. Exactly one of `block` and `customBlock`.                                          |                    |
| `customBlock`         | One of the project's [blocks](block.md), by id: see [custom blocks](#custom-blocks-in-a-generated-world). |                    |
| `replace`             | The block ids a vein may replace.                                                                         | the file's `stone` |
| `replaceCustomBlocks` | The project's [blocks](block.md) a vein may replace too, by id (`ebonstone`).                             | none               |
| `size`                | Most blocks in a vein, 1 to 64.                                                                           | 8                  |
| `veins`               | How many veins are tried in each chunk, 0 to 256.                                                         | 8                  |
| `minY`, `maxY`        | Where a vein may start.                                                                                   | -64, 64            |
| `distribution`        | `uniform`: equally likely at any height. `triangle`: likeliest halfway between `minY` and `maxY`.         | `uniform`          |
| `biomes`              | The [biome areas](#biomes), by name, a vein may start in.                                                 | all of them        |

Without `replace` or `replaceCustomBlocks` a vein replaces the file's `stone`; with either, only what they list (so
an ore in a project stone and the game's lists both).

A vein is a clump of blocks grown from the one it starts in, never further than 15 blocks from it. Veins cross chunk
edges: a chunk works out the veins that start in the chunks around it and keeps its own part.

## `decorations`

Things scattered through the world by name: single blocks (flowers, grass, rocks, crystals in caves) or the project's
[structures](worlds.md#structures) (trees, boulders, ruins). Each chunk tries `count` places, at random columns of it,
each kept at `chance` and, with a `noise`, only where the noise allows; then a place has to be in one of `biomes`,
between `minY` and `maxY`, and on a block `on` lists.

```json partial
"decorations": {
  "flowers": { "block": "minecraft:poppy", "count": 10, "noise": { "frequency": 0.03 }, "threshold": 0.2, "on": ["minecraft:grass_block"] },
  "oaks": { "structure": "oak_tree", "count": 2, "chance": 0.6, "biomes": ["plains"] },
  "glow": { "customBlock": "glow_crystal", "placement": "caveCeiling", "count": 6, "maxY": 20 }
}
```

| Key              | Meaning                                                                                                                                                             | Default                                        |
| ---------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------- |
| `block`          | A block state of the game's. Exactly one of `block`, `customBlock` and `structure` (`terrain.one-block`).                                                           |                                                |
| `customBlock`    | One of the project's [blocks](block.md): see [custom blocks](#custom-blocks-in-a-generated-world).                                                                  |                                                |
| `structure`      | One of the project's structures (`structures/<id>.nbt`, or a package's `acme:tree`): its blocks, not its air, with its lowest layer at the place and centred on it. |                                                |
| `placement`      | Where it goes (below).                                                                                                                                              | `surface`                                      |
| `count`          | Places tried in each chunk, 0 to 256.                                                                                                                               | 1                                              |
| `chance`         | How likely each place tried is kept, 0 to 1.                                                                                                                        | 1                                              |
| `noise`          | A pattern that gathers it into patches: none where the noise is at most `threshold`, rising to `chance` where it's 1.                                               | none                                           |
| `threshold`      | With a `noise`: where the patches start, -1 up to but not including 1.                                                                                              | 0                                              |
| `biomes`         | The [biome areas](#biomes), by name, it's placed in.                                                                                                                | all of them                                    |
| `minY`, `maxY`   | The heights it's placed between.                                                                                                                                    | the bottom and top of the world                |
| `on`             | Block ids it may sit on (`surface`, `underwater`, `caveFloor`), hang from (`caveCeiling`) or replace (`underground`).                                               | any ground (`underground`: the file's `stone`) |
| `onCustomBlocks` | The project's [blocks](block.md), by id, it may sit on, hang from or replace too (`ebonstone`). With `on` or this, only what they list.                             | none                                           |
| `rotate`         | For a `structure`: whether each one is turned a random quarter turn (its blocks' facing turned with it).                                                            | true                                           |
| `loot`           | One of the project's [loot tables](loot.md): the containers it places are filled from it. See [loot in generated containers](#loot-in-generated-containers).        | none                                           |

| `placement`   | Where                                                                                                                                                                                                        |
| ------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `surface`     | On the top block of dry ground, in the air above it. On top of the [layers](#layers-stone-and-the-floor), before caves open the ground. With a [density](#3d-terrain-density), any of the column's dry tops. |
| `underwater`  | On the sea floor, in the sea.                                                                                                                                                                                |
| `underground` | In the ground at a height between `minY` and `maxY`, replacing the block there.                                                                                                                              |
| `caveFloor`   | On the floor of a cave: the first floor at or below a height between `minY` and `maxY`.                                                                                                                      |
| `caveCeiling` | Hanging from a cave's ceiling: the first ceiling at or above a height between `minY` and `maxY`.                                                                                                             |

A structure goes on the `surface`, `underwater` or `underground` (`terrain.decoration`): where it's placed is decided
by its column alone, which is what lets a structure that reaches into the next chunks be placed whole, whichever chunk
is generated first. A block decoration goes where the chunk's blocks let it: a flower only on air over the ground it
names. Decorations are placed in name order, each over what's there, so a later one can cover an earlier one.

The structures a terrain places are read by the server with the game's own structure loader (so a structure saved by
an older version is brought up to date) when the project loads and whenever the structure's file is saved. One that
can't be read isn't placed, and says so (`runtime.terrain`). A structure's entities and the data of its blocks
(a chest's items) aren't placed: a decoration is blocks, and its containers are filled by its `loot`.

### Loot in generated containers

A decoration's `loot` fills the containers it places (chests, barrels, hoppers: any block whose structure file gives it
block data, or the decoration's own `block`) from one of the project's loot tables, and a script's
[`chunk:set_loot`](#script) fills any container it likes. Nothing is rolled while the world is made: the container
remembers its table, and it's rolled the first time a player opens it, breaks it or a hopper takes from it, with that
player as the roll's player (so a table's `player` conditions hear who found it), as the game's own chests are. A
place the server finds holding no container once the chunk is made is left alone. Its items are rolled once: a table
changed since is rolled as it is then.

```json partial
"decorations": {
  "shrines": { "structure": "shrine", "count": 1, "chance": 0.02, "loot": "shrine_chest" }
}
```

## Custom blocks in a generated world

Any block a terrain places (in a layer, the stone, the floor, an ore or a decoration) can be one of the project's
[blocks](block.md), as `customBlock`. It must be a plain cube: a block drawn by a centity can't be generated, since the
terrain can't spawn its centity (`terrain.custom-block`).

A project block is held in the world as a state of its carrier (see [blocks](block.md#carriers)). A terrain
writes that state into the chunk, and the plugin adopts it as the block as soon as the chunk loads, exactly as it does
for blocks a world paste brings: the block's drops, `break` handlers and ticks all work in a generated one. The state a
block has can move when the project's blocks change, which is no problem: the plugin sets the right one when a chunk
loads. A block is known by its state (each chunk keeps a legend of which state is which of its blocks, a few entries
however many blocks), so a custom block costs the same in a thin vein as in the `stone` of every column.

## Biomes

`climate` has two noises, `temperature` and `humidity` (default: `openSimplex2`, 0.002, 2 octaves; each its own
pattern). Each **biome area** (by name) names a `biome` and the `temperature` and `humidity` ranges (`min` and
`max` between -1 and 1, each default the whole range) it's found at. A column is in the area whose ranges it fits:
the most specific one (the smallest box) when several do, the nearest when none does. A file with no areas is `minecraft:plains`
everywhere. An area's `layers` and `underwater` replace the file's for its columns, so a desert can be sand to a
depth of its own, and its `terrain` shapes its ground.

### More climate values

Two values put every area somewhere on one map of temperature and humidity. `climate.noises` adds values of the
file's own, by name (at most 8), each a [noise](#noise) with a pattern of its own read where the temperature is, and an
area's `climate` gives its range of each (`min` and `max`, -1 to 1, default the whole range): an `evil` value spreads
the corruption across every climate instead of one corner of it, a `continentalness` one puts the oceans where it's
low. Areas are picked by every value at once, as by the first two: the smallest box that holds the column, else the
nearest.

```json partial
"climate": { "noises": { "evil": { "frequency": 0.004 } } },
"biomes": {
  "forest": { "biome": "minecraft:forest", "climate": { "evil": { "max": 0.4 } } },
  "corruption": { "biome": "corruption", "climate": { "evil": { "min": 0.4 } } }
}
```

### Biomes that change with height

An area with a `y`, `depth` or `surface` range is a **volume**: not a column's area, but a biome inside the columns'
areas, as the game puts lush caves under a forest. A place is in the most specific volume (the smallest climate box)
whose every range holds it and whose climate box holds its column's climate exactly; anywhere else it's in its column's
area. Its biome is the place's: the sky and fog a player sees, the music they hear and the mobs that spawn there all
follow where they stand, height included. A place is read at the corner of its 4x4x4 cell, as the game keeps biomes.

| Key       | Meaning                                                                                                    |
| --------- | ---------------------------------------------------------------------------------------------------------- |
| `y`       | `{ "min", "max" }`: the heights it's between, both included; either end left out is open.                  |
| `depth`   | `{ "min", "max" }`: how far below the column's surface (its top block is 0, the air above it is negative). |
| `surface` | `{ "min", "max" }`: the heights of the surfaces of the columns it's in (an ocean's are below the sea).     |

```json partial
"biomes": {
  "forest": { "biome": "minecraft:forest" },
  "jungle_caves": { "biome": "underground_jungle", "depth": { "min": 24 }, "y": { "min": -40 }, "temperature": { "min": 0.3 } },
  "underworld": { "biome": "underworld", "y": { "max": -40 } },
  "space": { "biome": "space", "y": { "min": 260 } },
  "ocean": { "biome": "minecraft:ocean", "surface": { "max": 55 }, "y": { "max": 62 } }
}
```

A volume gives its places a biome and nothing else: it has no `layers`, `underwater` or `terrain`, islands don't
float over one, and a file needs at least one area that isn't a volume (`terrain.volume`). The `biomes` filters of
caves, ores and decorations take volumes too: a cave in `["jungle_caves"]` is carved only there, and one in
`["forest"]` in the forest's columns, volumes and all. The script's [`biome` stage](#script) can change any place's
area, and its [`area` stage](#script) any column's.

### Borders

The climate's patterns are smooth, so the borders between areas would be smooth curves. `climate.jitter` makes them
wander: a column takes the climate of a place up to `amplitude` blocks away, in a direction its own noise says.

| Key         | Meaning                                                          | Default                         |
| ----------- | ---------------------------------------------------------------- | ------------------------------- |
| `amplitude` | How far a border moves either way, in blocks, 0 (not at all) up. | 16                              |
| `noise`     | The pattern it wanders by.                                       | `openSimplex2`, 0.02, 2 octaves |

### An area's own terrain

An area's `terrain` makes its ground different from the rest: hills where the plains are flat, peaks in the snow.

| Key       | Meaning                                                                                   | Default           |
| --------- | ----------------------------------------------------------------------------------------- | ----------------- |
| `base`    | The height where every noise is 0 in this area.                                           | the file's `base` |
| `scale`   | What the file's own `noises` are multiplied by here, 0 (flat) to 16.                      | 1                 |
| `noises`  | More noises added in this area only, by name, as the file's are.                          | none              |
| `density` | Its own 3D noises, in a file with a [density](#3d-terrain-density): `scale` and `noises`. | the file's        |

Where two areas whose terrain differs meet, their heights are **blended** across the border, over the file's
`terrain.blend` blocks: a column near it is a mix of both areas' heights, weighed by how much of the land around it is
each. The mix depends on the column's place alone (the areas are read on a grid of points 8 blocks apart around it),
so a chunk's edge is the same whichever side was generated first. With `blend` 0, the border is a cliff.

The `biome` is one of the game's, written in full (`minecraft:plains`), or one of the project's
[biomes](biome.md) by its id (`ruby_grove`, or a package's `acme:grove`), or a biome one of its
[datapacks](datapack.md) defines, the same way (`caves/deep` for `data/<namespace>/worldgen/biome/caves/deep.json`): a
plain id is always the project's. It
decides the colour of sky, water and grass, the weather and the mobs that spawn naturally in the area, and what it's
**decorated** with: once a chunk's ground is made, the game places its biomes' features on it, as in its own worlds. A
game biome brings all of its own (the plains' grass, flowers and oaks, and its ores, lakes and springs too, on top of
the file's), a project biome exactly the ones it [lists](biome.md#features), and none when it lists none. The preview
draws the ground the file makes, without them. For a world that is exactly what the file says, name project biomes
without features.

The biomes a world has are worked out once, as it's made (or loaded): a saved file that names a biome the world
didn't have puts the world's first biome in its areas until the world is loaded again (the server restarted).

## `structures`

`{ "vanilla": true }` lets the game generate its structures in the world, as it decides: villages, ruins, and the
[structures that generate](worlds.md#structures-that-generate) your project registered, each in the biomes it's allowed
in (so your `biomes` decide where) on the ground this file made. Default false. The world's own `structures` option
(`nf.worlds.create`'s) still turns them off.

It's only about structures: what decorates the world is the biomes' features (see [biomes](#biomes)), with it on or
off.

## `script`

A file can hand stages to a Lua script beside it, `terrain/<id>.lua` (named by the file's id, so it goes with the file
when it's renamed): whatever the file's keys can't say, from ridges to roads to a lantern in every chunk. The file
says it has one, and declares what the script uses, so it's checked with the file and known before any chunk is
made:

```json
{
  "$schema": "../.netherforge/schema/terrain.schema.json",
  "terrain": {
    "base": 66,
    "noises": { "hills": { "noise": { "frequency": 0.005, "octaves": 2 }, "amplitude": 10 } }
  },
  "layers": [{ "block": "minecraft:grass_block" }, { "block": "minecraft:dirt", "thickness": 3 }],
  "script": {
    "budget": 200000,
    "noises": { "ridges": { "fractal": "ridged", "frequency": 0.01, "octaves": 3 } },
    "blocks": ["minecraft:cobblestone", "minecraft:glowstone"],
    "customBlocks": ["ruby_ore"]
  }
}
```

| Key            | What it is                                                                                                                                                                             |
| -------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `budget`       | Lua instructions each call into the script may run (its body once, one column's height, one chunk's stage), 1,000 to 100,000,000. Default 1,000,000.                                   |
| `noises`       | Noises the script samples by name (`terrain.noise("ridges")`), each a [noise](#noise) with a pattern of its own from the world's seed. Up to 16.                                       |
| `blocks`       | The game's block states the script places besides those the file names (`minecraft:cobblestone`, properties too). A block the generator doesn't know is an error at the script's line. |
| `customBlocks` | The project's [blocks](block.md) it places besides the file's, by id: plain cubes, as anywhere in the file.                                                                            |
| `loot`         | The project's [loot tables](loot.md) it fills containers from (`chunk:set_loot`), by id. At most 64.                                                                                   |

`"script": {}` is a script with nothing declared. Together `blocks` and `customBlocks` list at most 1024.

A block the script or its modules write whole, as a string, needn't be listed:
`"minecraft:oak_stairs[facing=north]"` (a game block state) and `"ruby_ore"` (a
project block's id) join the generator by themselves. List only the ones the
script puts together from pieces (`"minecraft:" .. color .. "_wool"`).

The script's body runs once in each Lua state (see below) and returns its stages, any of six (`density`, `area` and
`biome` are below):

```lua
---@type Terrain
local terrain = ...
local ridges = terrain.noise("ridges")

---@type TerrainStages
local stages = {}

-- The y of a column's top block: `height` is the file's.
function stages.height(x, z, height)
  return height + math.max(0, ridges:at(x, z)) * 14
end

-- After the file's terrain, before caves: cap the high ground with cobblestone.
function stages.terrain(chunk)
  for x = chunk:min_x(), chunk:min_x() + 15 do
    for z = chunk:min_z(), chunk:min_z() + 15 do
      local top = terrain.height(x, z)
      if top > 80 then chunk:fill(x, top - 1, z, x, top, z, "minecraft:cobblestone") end
    end
  end
end

-- After the file's decorations: a glowing block somewhere deep in every chunk.
function stages.decorate(chunk)
  local x, z = chunk:min_x() + math.random(0, 15), chunk:min_z() + math.random(0, 15)
  chunk:set(x, terrain.min_y() + 8, z, "minecraft:glowstone")
end

return stages
```

- **`height(x, z, height)`** gives a column's top block's y from the file's (`terrain.height(x, z)` asks it for any
  column, as a chunk is made with it). The rest of the column follows it: layers, the sea, caves, ores and
  decorations all see the script's height.
- **`density(x, y, z, value)`**, in a file with a [`terrain.density`](#3d-terrain-density), gives the density at a
  point from the file's `value` there (in blocks, above 0 is solid): return `value` to keep it, more to fill, less to
  hollow out. It's asked at the points the file's 3D noises are read at (`x` and `z` multiples of 4, `y` of 8, every
  height of the world) and blended between them for the blocks, so what it adds is blended too: a sphere it fills has
  soft edges. Its `height` is still the column's height the density is shaped around, and so is `terrain.height`; the
  ground's blocks are the chunk's to read. A density stage in a file without a density never runs, and says so as the
  script loads.

  ```lua
  -- A floating rock wherever a noise of the script's peaks, high up.
  function stages.density(x, y, z, value)
    if y > 180 and y < 220 and rocks:at(x, y, z) > 0.6 then
      return math.max(value, 4)
    end
    return value
  end
  ```

- **`area(x, z, area)`** picks a column's [biome area](#biomes), given the name of the one its climate picks: return
  the name of one of the file's areas that isn't limited by height. Everything about the column follows it (its height,
  its layers, the blending at its borders, the `biomes` filters), so this is how a world gets a layout no climate
  makes: the dungeon always near the snow, the jungle on the far side. It's asked for every column, often: keep it
  quick, and it can't ask `terrain.height` or `terrain.area` (they depend on it).

  ```lua
  -- Snow to the west of the spawn, jungle to the east, whatever the climate says between.
  function stages.area(x, z, area)
    if x < -3000 then return "snow" end
    if x > 3000 then return "jungle" end
    return area
  end
  ```

- **`biome(x, y, z, area)`** picks the area of a place (the corner of each 4x4x4 cell, as the game keeps biomes),
  given the one the file picks there: a [volume](#biomes-that-change-with-height), else the column's. Return the name
  of any of the file's areas. It decides the place's biome (its sky, fog, music, mobs) and what `biomes` filters see
  there, never the ground's shape. It can ask `terrain.height` and a column's `terrain.area`.
- **`terrain(chunk)`** and **`decorate(chunk)`** change the chunk being made: `chunk:fill(x1, y1, z1, x2, y2, z2,
block)` fills a box (world coordinates, clipped to the chunk), `chunk:set(x, y, z, block)` one block,
  `chunk:block(x, y, z)` reads one. A block is the game's id as the file writes it, or one of the project's by id.
  `chunk:set_loot(x, y, z, table)` fills the container there from a loot table `script.loot` lists (see
  [loot in generated containers](#loot-in-generated-containers)).

**Plans** are for the big things a script draws across many chunks: a dungeon, a temple, a road. Each chunk it touches
would otherwise work out the whole layout again. `terrain.plan(name, size, make)`, in the script's body, cuts the
world into cells `size` blocks wide and works out a cell's plan once, the first time a chunk asks for it
(`plan:get(cell_x, cell_z)`, or `plan:at(x, z)` for the cell a column is in), keeping the last 256 cells. `make` must
depend on the cell alone; `math.random` in it gives numbers of the cell's own, the same whichever chunk made it.

```lua
local dungeons = terrain.plan("dungeon", 512, function(cell_x, cell_z)
  local rooms = {}
  for i = 1, math.random(4, 9) do
    rooms[i] = { x = cell_x * 512 + math.random(64, 448), y = math.random(-40, 20), z = cell_z * 512 + math.random(64, 448) }
  end
  return rooms
end)

function stages.decorate(chunk)
  local rooms = dungeons:at(chunk:min_x(), chunk:min_z())
  for _, room in ipairs(rooms) do
    chunk:fill(room.x - 4, room.y, room.z - 4, room.x + 4, room.y + 5, room.z + 4, "minecraft:air")
    chunk:set(room.x, room.y, room.z, "minecraft:chest")
    chunk:set_loot(room.x, room.y, room.z, "dungeon_chest")
  end
end
```

A room near a cell's edge can reach into the next cell's chunks, which ask for their own cell: look at the
neighbouring cells' plans too when what you draw is wider than the margin you leave.

The whole API (`terrain.seed()`, `min_y()`, `max_y()`, `sea_level()`, `noise(name)`, `height`, `area` and `biome` of a
column or a place, `plan`, a noise's `:at(x, z)` and `:at(x, y, z)`) is the
[terrain scripts reference](../reference/terrain-scripts.md).

**It isn't a server script.** It runs while chunks are being made (and in the editor's preview), apart from your
other scripts, so it has no `nf`, no events and nothing of the server. Several chunks can be made at once, each with
its own copy of the script, so don't rely on a global one call set being there for the next. Lua 5.4's basics are
there, `require` reaches the project's modules (only modules that don't use `nf` work), and `io`, `os`, `debug`,
`load` and `collectgarbage` aren't.

**The same blocks for a seed, every time.** Everything it's given depends only on the world's seed and where it's
asked (the noises are the file's own noise, seeded from the world's), and `math.random` is seeded for each call from
the world's seed and the column, place or chunk (in a plan, the cell), so a chunk is the same in whichever order the
world was explored, and the preview for a seed is that world.

**A script that fails changes nothing.** A call that raises an error, or runs past its `budget` (a `pcall` can't catch
that), leaves that column's height, that point's density or that chunk's stage as the file makes it: what the stage had changed is put
back. The server says each failure once (`terrain.script-failed` at the script's line, and in the log); a script
that doesn't load is said as the terrain is published, and only the file's own stages run. The editor's preview says
the same over the pictures.

**Saving the script, or a module it requires,** publishes the terrain again, as saving the file does: chunks
generated afterwards use it.

There's one script a file; a file with a `script` and no `.lua` beside it is an error (`terrain.script-missing`), and
a `.lua` beside a file with no `script` never runs (`terrain.script-unused`).

## Worlds that use one

```lua
local world = nf.worlds.create("realm", { terrain = "ruby_hills", seed = 42 })
```

`terrain` is the id of a terrain of the project (or a package's, `acme:hills`); it's kept with the world, so the world
generates the same way when it's loaded again. Only a normal-environment world takes one, and not with a `generator`,
which is one of the game's own world types (`"normal"`, `"flat"`, `"void"`, `"amplified"`, `"large_biomes"`). A seed
gives one world, always.

`netherforge.json` can name the worlds a project makes by itself:

```json partial
"worlds": {
  "realm": { "terrain": "ruby_hills", "seed": 42 }
}
```

When the project loads, a world named there with a `terrain` is made if the server has none by that name (and
loaded, with its terrain, if it has one saved). It's the project's from then on, like one a script created.

A world's heights are its [dimension type](dimension-type.md)'s: -64 to 319 in the overworld, whatever a project's
`dimension_types/<id>.json` says in a world made with one (`nf.worlds.create(name, { terrain = …, dimension_type = "deep" })`,
or `"dimensionType"` beside `"terrain"` in `netherforge.json`). The terrain fills the world from its bottom to its top.
On its own, a file is held only to what any world can be (-2032 to 2031); a world in `netherforge.json` naming both a
terrain and a dimension holds the terrain to that dimension's heights (`project.world-height`, at the world, naming
what's out of range). The editor's preview draws a world of the dimension a `netherforge.json` world that names the
terrain has (pick which above the preview); the overworld's otherwise.

### The main world

The server's main world (`level-name` in `server.properties`, `world` unless you changed it) can be generated by one
too. Name it in `netherforge.json` like any world:

```json partial
"worlds": {
  "world": { "terrain": "ruby_hills" }
}
```

The server makes its main world as it starts, before any project runs, and asks a plugin for the generator only when
`bukkit.yml` says so:

```yaml
worlds:
  world:
    generator: NetherForge
```

The editor's dev server writes that line itself whenever the project names a terrain for the main world (and takes
it out when it doesn't). On your own server, add it once. NetherForge then reads `netherforge.json` as the server
starts and gives the world that terrain: its land, its biomes, and its spawn (the first dry column near 0, 0). The
world keeps the server's own seed (`level-seed`), so `seed` isn't for the main world (`runtime.terrain`). The Nether
and the End stay the game's.

**Which terrain** the main world has is the server's to take as it starts: naming another one (or none) in
`netherforge.json` asks for a restart (`runtime.restart`; the dev server restarts itself), and until then new chunks
are still the one it started with. **What that terrain makes** is a file like any other: saving it changes the
chunks generated afterwards, with no restart. When `netherforge.json` names a terrain and `bukkit.yml` doesn't ask
NetherForge, that's `runtime.restart` too, saying what to add.

**Without a terrain of the project's**, the main world is the game's own, and a [datapack](datapack.md) shapes it the
game's way: replacing `minecraft:overworld`'s noise settings
(`datapacks/<pack>/data/minecraft/worldgen/noise_settings/overworld.json`, with its density functions) changes the
terrain of the main world's new chunks, and of every world made with the game's `"normal"` type. A terrain of the
project's makes the ground itself, so noise settings don't reach it; a datapack's biomes and features do, through its
areas.

**A main world that already exists keeps its chunks.** Only chunks generated from then on are the terrain's, so a
world first made by the game has a seam where its vanilla land meets the new terrain, and its spawn stays where it
was. For a world that's all the terrain's, stop the server and delete the world's folder (on a dev server, the
world in [the dev server's folder](../guide/install.md#where-the-editor-keeps-things)); the next start makes it anew.

## A changed terrain only affects new chunks

A chunk is generated once, when a player first goes near it; the game then saves it. Saving a terrain file
(hot reload) swaps the terrain for the chunks generated **afterwards**: chunks already in the world keep what they
have, so a world explored before the change has a seam where old chunks meet new ones. To see a change everywhere,
make a new world (or delete the world's `region` files with the server stopped). The editor's preview always shows
the file as it is now.

## What a terrain doesn't do

The game's **carvers** and its **noise-based terrain** aren't used: the ground is exactly what this file says,
decorations included, which is what the preview draws. The game's **features** (its trees, flowers, grass, lakes) are
the biomes' (see [biomes](#biomes)), on top of the file's decorations.

A terrain doesn't slow the server down while chunks generate, and nothing else in the project is asked while it
does: the structures its decorations place, its script and the modules it requires are all read when the terrain
is saved.

## Problems

[Problems](problems.md) lists them. The codes are `terrain.*`.
