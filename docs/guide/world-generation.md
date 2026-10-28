# World generation

A terrain shapes the land of a world your project creates, or of the
server's main world: hills and seas, what the ground is made of, caves, ores,
flowers and trees, and which biomes go where, each with ground of its own. It's
a file, `terrain/<id>.json`, and the editor draws the world it makes while you
change it. [The format page](../format/terrain.md) has every key.

```
terrain/
  ruby_hills.json
  ruby_hills.lua     (only if the file hands stages to a script)
```

## Shaping the ground

Open (or add) a terrain in the editor. The picture is the world a server
makes with the **seed** at the top: a map from above, with a dashed line you can
move by clicking it, and a slice through the ground along that line (drag the line
between them to give either more room). Drag the map to look elsewhere and scroll
or pinch to zoom (the arrow keys, + and - work too); "Detail" trades sharpness for
speed. A new seed is a new world, and the same
seed is the same world every time, here and on the server. The preview is drawn
beside the editor, not in its way: it catches up with your typing a moment
later.

- **Terrain.** A height is the _base height_ plus noises you add, each a pattern
  from -1 to 1 spread over some blocks of _amplitude_. A tight pattern (a high
  frequency) is bumps; a wide one is hills; stacking octaves adds detail. A
  column lower than the _sea level_ is under the sea.
- **Ground.** Layers from the surface down (grass over dirt), then stone. Under the sea the
  ground can be something else (sand). A biome area can have layers of its own.
  Any of them can be one of **your own blocks** (pick "the project's").
- **Caves.** `cheese` makes big rooms, `spaghetti` winding tunnels. They never
  open the surface within the _below the surface_ distance.
- **Ores.** A block, a vein size, how many veins a chunk tries, and the heights
  they start at. An ore can be one of **your own blocks**: pick "the project's"
  and a block of the project (a plain cube; a block drawn by a centity can't be
  an ore). The server adopts those blocks when their chunk loads, so a generated
  ruby ore drops its loot table and runs its script like one a player placed.
- **Decorations.** Something scattered at a number of tries per chunk: a block
  (a flower, a rock, a crystal on a cave's floor or hanging from its ceiling)
  or one of your [structures](../format/worlds.md#structures), such as a tree.
  "In patches" gathers them where a noise is high, so flowers grow in meadows
  rather than evenly. The map marks where the ones on the ground start (on a
  close enough map), and the slice shows them standing on it. A tree is a
  structure you capture from the dev server: build one, save it as
  `structures/oak_tree.nbt`, and name it in a decoration.
- **Biomes.** Each area names a biome (its sky, grass, mobs, and the trees and
  flowers it grows) and the temperature and humidity it's found at. Where the
  climate noise says a place is warm and dry, that's the desert. A biome is one
  of the game's, written `minecraft:desert`, or [one of your own](#your-own-biomes).
  An area can have **its own terrain**: its base height, the file's hills made
  flatter or steeper ("Noises times"), and noises of its own, such as peaks only
  in the snow. Where it meets another area the heights are blended over "Blend
  borders over" blocks, so the plains rise into the mountains instead of meeting
  them at a cliff, and "Borders" makes the edge between two areas wander instead
  of following a smooth curve.
- **Only in some areas.** An ore, a cave or a decoration ticks the areas it
  keeps to: ruby only under the snow, flowers only on the plains. Renaming an
  area renames it there too.

## Overhangs and floating islands

Heights give every column one top. For ground that leans out over itself, tick
**3D ground** in the Terrain section: the ground keeps its heights, and each
**3D noise** you add moves its surface up or down by its _amplitude_, differently
at each height, so cliffs overhang, ledges stick out and arches open. _Squash_
shapes them: above 1 the pattern is flatter (shelves and ledges), below 1
taller (sheer cliffs and pillars). A biome area can have **its own 3D noises**,
or none ("3D noises times" 0), blended across its border like its heights.

**A floating-islands recipe**: a sea with islands in the sky over it.

```json
{
  "$schema": "../.netherforge/schema/terrain.schema.json",
  "terrain": {
    "base": 40,
    "seaLevel": 62,
    "noises": { "seabed": { "noise": { "frequency": 0.01 }, "amplitude": 8 } },
    "density": {
      "islands": {
        "y": 150,
        "thickness": 32,
        "threshold": 0.35,
        "noise": { "frequency": 0.012, "octaves": 3 }
      },
      "noises": {
        "crags": { "noise": { "frequency": 0.05, "octaves": 2 }, "amplitude": 4, "squash": 0.5 }
      }
    }
  },
  "layers": [{ "block": "minecraft:grass_block" }, { "block": "minecraft:dirt", "thickness": 3 }],
  "underwater": [{ "block": "minecraft:sand", "thickness": 3 }],
  "decorations": {
    "flowers": { "block": "minecraft:poppy", "count": 12, "on": ["minecraft:grass_block"] },
    "oaks": { "structure": "oak_tree", "count": 3, "on": ["minecraft:grass_block"] }
  }
}
```

The islands float in a band 32 blocks tall around y 150; where their noise is
above the threshold there's land, thickest in the band's middle, so each is a
lens of rock with grass on top. A lower threshold makes more and bigger islands,
a higher one fewer; a lower frequency makes them wider apart. Everything else
follows the land: the layers top every surface (the island, and the sea floor
under it), caves stay inside it, and flowers and trees grow on whichever top a
try picks, the islands included. To float them over nothing at all, put `base`
and `seaLevel` at the bottom of the world. Keep them to some biome areas with
"Only in", as an ore. The map shows the topmost ground (the islands, seen from
above), and the slice cuts through them, the sky below each island and all.

## Shaping it with Lua

When the keys can't say it (ridges that follow a noise of their own, a road, a
lantern in every chunk), let a script do it. In the inspector's **Script**
section, tick "Hand stages to a script": the file gets a `script`, and
`terrain/ruby_hills.lua` is made beside it from a template whose stages change
nothing yet. "Open ruby_hills.lua" opens it.

```lua
---@type Terrain
local terrain = ...
local ridges = terrain.noise("ridges")

---@type TerrainStages
local stages = {}

-- The file's height, then a ridge on top wherever the noise is high.
function stages.height(x, z, height)
  return height + math.max(0, ridges:at(x, z)) * 14
end

return stages
```

It returns up to four stages: `height` (a column's top, from the file's),
`density` (with 3D ground: how solid a point is, from the file's),
`terrain` (blocks filled into a chunk after the file's ground is made) and
`decorate` (after the file's decorations). Add the noises it asks for under
the Script section (`ridges` above). A block it writes whole as a string
(`"minecraft:cobblestone"`, or one of your own blocks' ids) it may place; list
under the Script section only the ones it puts together from pieces.
The preview runs the script as you save it, or as you type in it, and shows
exactly what the server will generate.
The [reference](../reference/terrain-scripts.md) has everything a script can
call; the editor's completions know it too.

It isn't like your other scripts: it runs while chunks are being made, so
there's no `nf`, no events and no players, only the world it's shaping. It may `require` your modules (ones that don't use `nf`).
Everything it does depends on the seed and the place (`math.random` included),
so a seed is still one world.

When it fails (an error, or a loop that runs past its budget) the preview
says so over the pictures, with a link to the line, and draws the file's own
ground there. The server does the same: that column or chunk is the file's
own, and the problem (`terrain.script-failed`) is in the editor's Problems
panel with the line. Saving the script swaps it for new chunks, like saving the
file.

### Structures that cross chunks

A stage only fills its own chunk, so something bigger (a tree by the border,
a ruin, a floating island) is built once as a **structure** and drawn chunk by
chunk: each chunk draws the part of it inside it.

```lua
---@type Terrain
local terrain = ...

-- At most one ruin in each 128-block cell, built once with the cell's own numbers.
local ruins = terrain.scatter("ruins", 128, function(cell_x, cell_z)
  if math.random() < 0.6 then return nil end
  local x, z = cell_x * 128 + math.random(16, 111), cell_z * 128 + math.random(16, 111)
  local y = terrain.height(x, z)
  return terrain.structure()
    :ellipsoid(x, y + 4, z, 7, 5, 7, "minecraft:mossy_cobblestone", { shell = 1 })
    :sprinkle(x - 7, y, z - 7, x + 7, y + 9, z + 7, "minecraft:cracked_stone_bricks", 40,
      { only = { "minecraft:mossy_cobblestone" } })
end)

return {
  decorate = function(chunk) ruins:place(chunk) end,
}
```

A structure has `fill`, `set`, `ellipsoid`, `sprinkle` (blocks at places
fixed by the structure, the same in every chunk) and `loot`, in the world's
coordinates; `chunk:place_structure(s, x, y, z)` draws one moved, so a tree
built once at 0, 0, 0 can stand anywhere. A chunk draws an ellipsoid of its
own with `chunk:ellipsoid` (solid, a `shell`, or `only` over some blocks).

## Looking without the editor

`netherforge preview` draws the same picture from a terminal, so a script, CI or
a [coding agent](agents.md#check-its-work) can see what a terrain makes. It
runs the server's own generator and the editor's own colours:

```sh
node .netherforge/bin/netherforge.mjs preview terrain/ruby_hills.json --seed 42 --out map.png
node .netherforge/bin/netherforge.mjs preview terrain/ruby_hills.json --seed 42 \
  --slice x --at 0 --out slice.png
```

The first writes the map from above, the second the ground through the line
along the x axis at z 0 (`--slice z --at 0` runs along z at x 0). It prints the
picture's size and what its colours are: each biome area's, the sea (blue,
darker where deeper), the marks of decorations on the map, and the blocks in a
slice. Air in a slice is transparent.

| Option                       | Default            | Meaning                                                                          |
| ---------------------------- | ------------------ | -------------------------------------------------------------------------------- |
| `--seed <n>`                 | 1337               | A whole number. The same seed is the same world.                                 |
| `--x <n>`, `--z <n>`         | 0, 0               | The block at the middle of the picture (of a slice, along its line).             |
| `--size <cells>`             | 96                 | The map's width and height in cells.                                             |
| `--step <blocks>`            | 4                  | Blocks a cell is across: a bigger one shows more ground, more coarsely.          |
| `--scale <pixels>`           | 4 (map), 3 (slice) | Pixels a map cell or a slice block is across in the picture.                     |
| `--slice x\|z`, `--at <n>`   | the map            | A slice instead of the map, along that axis, at that z (for `x`) or x (for `z`). |
| `--width <n>`                | 128                | Columns in a slice, at most 4096.                                                |
| `--top <y>`                  | the highest block  | The highest y a slice draws: the empty sky above the ground too.                 |
| `--min-y <n>`, `--max-y <n>` | -64, 320           | The world's lowest block, and one above its highest.                             |

The structures its decorations place are read from the project's
`structures/<name>.nbt`, as the editor does, and a file with a script runs
`terrain/<id>.lua` (and the modules it requires); what the script failed at
is printed after the colours, as `check` prints a warning. A file with problems
prints them as `check` does and exits 1 without a picture.

## Making a world with it

```lua
local world = nf.worlds.create("realm", { terrain = "ruby_hills", seed = 42 })
player:teleport(world:spawn_location())
```

Or let the project do it when it loads, in `netherforge.json`:

```json
"worlds": { "realm": { "terrain": "ruby_hills", "seed": 42 } }
```

The world keeps its terrain: loaded again later, with `nf.worlds.load("realm")`
or after a restart, it generates the same way.

## Deeper and taller worlds

A world is -64 to 319 unless it's made with a [dimension type](../format/dimension-type.md)
of your own. Make one (`dimension_types/deep.json`):

```json
{ "minY": -256, "height": 640 }
```

and name it beside the terrain:

```lua
local world = nf.worlds.create("realm", { terrain = "ruby_hills", dimension_type = "deep" })
```

The world now holds blocks from -256 to 383, and your terrain fills all of it:
caves and ores can go down to -256. In `netherforge.json`, `"dimensionType": "deep"`
beside `"terrain"` does the same, and the editor checks the terrain's ores,
caves and decorations against those heights, and draws the preview at them.
Dimension types are learnt as the server starts: saving one restarts the dev
server. The same file decides the world's light, sky, and whether beds and
respawn anchors work.

## The main world

The world players join first can be yours too. Name the server's main world
(`world`, unless `level-name` in `server.properties` says otherwise) with a
terrain, and leave out the seed: the main world keeps the server's own.

```json
"worlds": { "world": { "terrain": "ruby_hills" } }
```

The server makes its main world as it starts, so this takes a restart: the dev
server restarts itself when you save `netherforge.json`. Add `"dimensionType": "deep"`
to give the main world your dimension type too (the game's overworld type takes
its values: see [the main world's dimension](../format/dimension-type.md#the-main-world)). Its spawn is the
terrain's, on dry land near 0, 0. The Nether and the End stay the game's.

A main world that already exists keeps the land it has: only chunks generated
from then on are your terrain's. For a main world that's all yours, stop the
dev server, delete the world in [its folder](install.md#where-the-editor-keeps-things),
and start it again.

On your own server, the server has to be told to ask NetherForge, once, in
`bukkit.yml` (the editor does this for the dev server):

```yaml
worlds:
  world:
    generator: NetherForge
```

Then restart it. Until you do, `/nf reload` and the Problems panel say what's
missing.

## What a change does

A chunk is generated once, when a player first goes near it. **Saving the file
changes only the chunks generated afterwards**: what's already in the world
stays as it was made, so a world you explored before changing the terrain has a
seam where the old chunks meet the new ones. To see a change everywhere, make
another world. The preview always shows the file as it is now.

The game's own structures (villages, and the project's [structures that
generate](../format/worlds.md#structures-that-generate)) can generate too, with
`"structures": { "vanilla": true }`.

## Your own biomes

The game decorates the ground your terrain makes with its biomes' features: a
game biome brings all of its own (the plains' grass, flowers and oaks, and its
ores and lakes too), which the preview doesn't show. A biome of your own,
`biomes/<id>.json`, has exactly the features you list, and its own sky, fog,
water, grass and leaf colours, particles, sounds, music and mobs:

```
biomes/
  ruby_grove.json
```

Add one in the editor (Add, then Biome): pick its colours, the mobs that spawn
in it by category, and the game's placed features it grows, step by step
(`vegetal_decoration` for trees, flowers and grass; the editor lists the game's
as you type). Name it in an area of the terrain by its id, `"biome": "ruby_grove"`,
and in a centity's [`spawning`](../format/centity.md#spawning) the same way. On
the server it's `<namespace>:ruby_grove`, which `/locate biome` finds.

The server learns biomes only when it starts, so saving one restarts the dev
server. A biome without features is a world exactly as your file says.
[The format page](../format/biome.md) has every key.

## The game's own worldgen, for experts

Anything the game's datapacks can say about generating a world, a project can
say the same way, in `datapacks/`: a folder laid out as any datapack is
(`pack.mcmeta`, and `data/<namespace>/worldgen/...` beside it), passed through
to the server as it is. Copy a datapack you have into `datapacks/`, or write
the game's JSON by hand: density functions and noise settings, configured and
placed features, carvers, structures, template pools, world presets. The
editor lists its files, opens each as JSON, and checks what it can: where each
file is, its references to other entries, and what the target version has. The
rest only the server checks, as it starts.

Two things it's for:

- **Features of your own** in your biomes. The example's
  `datapacks/ruby_boulders` defines a placed feature, `ruby_boulders`, and
  `biomes/ruby_grove.json` lists it as it lists the game's:

  ```json partial
  "features": {
    "local_modifications": ["ruby_boulders"],
    "vegetal_decoration": ["minecraft:patch_grass_plain", "minecraft:flower_cherry", "minecraft:trees_cherry"]
  }
  ```

- **The game's own terrain, changed.** A file in the `minecraft` namespace
  replaces the game's: `data/minecraft/worldgen/noise_settings/overworld.json`
  reshapes the main world (when no terrain of yours makes it) and every
  `"normal"` world `nf.worlds.create` makes.

The game's worldgen JSON changes between versions, so `pack.mcmeta` says which
data pack formats the pack is written for, and overlays (folders beside
`data/`, each for a range of formats) hold each version's own shape of a file.
The server learns worldgen only as it starts: saving a datapack's file restarts
the dev server. When the server can't read one, it stops; started again, it
runs without your datapacks and shows the game's own complaint in Problems on
the file it named. [Datapacks](../format/datapack.md) has the details.

## Biomes and new chunks in scripts

Scripts can ask where they are and decorate what the terrain made. A block
knows its biome (`block:biome()`), and `world:locate_biome` finds the nearest
place of one, as `/locate biome` does, without holding up the server. Both name a biome as
your files do: your own by its id (`"ruby_grove"`), a package's as
`"acme:grove"`, the game's in full (`"minecraft:dark_forest"`); a plain id that
isn't one of your biomes is an error. Call `locate_biome` in a task, or give it a callback. The
`chunk_generated` event (on `nf`, or on one world with `world:on`) is heard
once for each chunk, the first time it's loaded, with the terrain and the
game's decorations in place, so a script can add what the file can't say. This
one plants a spirit in some of the dark forest chunks of the `wilds` world:

```lua
nf.on("chunk_generated", function(event)
  -- In one world, one chunk in five, and only where the middle of the chunk is dark forest.
  if event.world:name() ~= "wilds" or math.random() > 0.2 then
    return
  end
  local ground = event.world:highest_block(vec3(event.x * 16 + 8, 0, event.z * 16 + 8))
  if ground and ground:biome() == "minecraft:dark_forest" then
    nf.centities.spawn("grove_spirit", event.world:location(ground:position() + vec3(0.5, 1, 0.5)))
  end
end)
```

A chunk is generated once, so a chunk made while no script listened is never
heard: listen from a module, which runs as the server starts. Keep the handler
quick (it runs for every new chunk a player explores) and change only blocks
inside the chunk (`event.x * 16` to `event.x * 16 + 15`, the same along `z`):
the chunks beside it may not be generated yet.
