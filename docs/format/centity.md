# Centities

A **centity** is a composed, scripted entity: a tree of nodes, each of which
can draw something, be clicked and fall under physics, with one Lua script
for the whole centity. On the server
each node with a display becomes a Minecraft display entity, and each node
with a hitbox becomes an interaction entity, all moving together: the
displays ride one invisible entity at the centity's place, so moving the
centity moves that one entity.

It lives at `centities/<id>/centity.json`, with its script beside it.

```json
{
  "$schema": "../../.netherforge/schema/centity.schema.json",
  "name": "Stone tower",
  "nodes": {
    "root": {
      "display": { "type": "block", "block": "minecraft:stone_bricks" },
      "hitbox": {}
    },
    "top": {
      "parent": "root",
      "transform": { "translation": [0, 1, 0] },
      "display": { "type": "block", "block": "minecraft:oak_stairs[facing=east]" }
    }
  },
  "animations": {
    "spin": {
      "loop": "loop",
      "tracks": {
        "top": {
          "rotation": [
            { "time": 0, "value": [0, 0, 0] },
            { "time": 1, "value": [0, 180, 0] }
          ]
        }
      }
    }
  },
  "script": { "file": "script.lua" }
}
```

| Key          | Meaning                                          |
| ------------ | ------------------------------------------------ |
| `name`       | Display name for people. Optional.               |
| `nodes`      | The tree, keyed by node name. At least one node. |
| `animations` | Clips over the nodes, keyed by clip name.        |
| `script`     | The centity's one Lua script.                    |

## Nodes

Node names are letters, digits, `_` and `-`, at most 64 characters. Every key
on a node is optional.

| Key         | Meaning                                               |
| ----------- | ----------------------------------------------------- |
| `parent`    | The parent node's name. A node without one is a root. |
| `transform` | Local transform relative to the parent.               |
| `display`   | What the node draws.                                  |
| `hitbox`    | Where it can be clicked.                              |
| `physics`   | Makes it a rigid body.                                |
| `rope`      | Makes it a rope of links: see below.                  |

Parents can't form a cycle. Siblings are ordered by name.

A node has no script of its own. The centity's script reaches a node with
`this:node("top")` and listens on it there.

### `rope`

```json partial
"chain": {
  "display": { "type": "item", "item": "minecraft:iron_chain" },
  "rope": { "links": 12, "linkLength": 0.35 }
}
```

A rope node draws nothing itself: its `display` is drawn by `links` nodes of
its own (1 to 64), named `chain[1]` to `chain[12]`, each `linkLength` blocks
long (default 0.5, at most 8). Until a script strings it they lie in a line
along its Z. `node:rope_between(from, to, { length })` strings it between two
ends (points, nodes or entities) and the server lays its links between them
every tick as they move, sagging by the slack when `length` is more than the
distance, and showing only as many links as that needs. A link's display is
stretched along its Z to its length, so draw it one block long along Z,
centred on its origin. A rope can't have a `hitbox` or `physics`
(`centity.rope`), and only `centity.json` can make one.

### `transform`

| Key           | Units                            | Default     |
| ------------- | -------------------------------- | ----------- |
| `translation` | blocks, `[x, y, z]`              | `[0, 0, 0]` |
| `rotation`    | degrees, applied X then Y then Z | `[0, 0, 0]` |
| `scale`       | multiplier                       | `[1, 1, 1]` |

A node's world transform is its parent's world transform times its own local
one: translate, then rotate, then scale.

### `display`

One per node; `type` says which kind.

**Block**: `{ "type": "block", "block": "minecraft:oak_stairs[facing=east]" }`.
A block state; properties you leave out take the block's defaults. A block
display fills the unit cube from `[0, 0, 0]` to `[1, 1, 1]` in the node's
space.

**Item**: `{ "type": "item", "item": "minecraft:diamond_sword", "itemTransform": "fixed" }`.
`itemTransform` is Minecraft's item display context: `none`, `thirdperson_lefthand`,
`thirdperson_righthand`, `firstperson_lefthand`, `firstperson_righthand`, `head`,
`gui`, `ground` or `fixed`.

**Model**: `{ "type": "model", "model": "mobs/wing", "itemTransform": "none" }`.
`model` is one of the project's [resource pack looks](resource-pack.md#item-models)
(`<pack>/<key>`, an entry of a pack's `items`): usually a [model file](resource-pack.md#models)
with elements, drawn whole by one display entity however complex it is, so a
creature is a display per moving part rather than dozens of block displays.
`itemTransform` picks which of the model's `display` transforms applies, as for
an item; the default, `none`, draws it as modelled (16 of its units to a
block, centred on the node). A script swaps the look with
`node:set_model("mobs/wing_2")`: the same entity shows the new model, one
small update to each player who sees it, so a few frames swapped every few
ticks animate it as a flipbook, alongside the animation tracks.

**Text**: `{ "type": "text", "text": "<gold>Hello" }`, with optional keys:

| Key          | Meaning                                                                                                         |
| ------------ | --------------------------------------------------------------------------------------------------------------- |
| `text`       | MiniMessage. `<glyph:<pack>/<key>>` shows a [resource pack glyph](resource-pack.md#glyphs).                     |
| `billboard`  | `fixed`, `vertical`, `horizontal` or `center`. Anything but `fixed` turns to face the viewer. Default `center`. |
| `alignment`  | `center`, `left` or `right`.                                                                                    |
| `background` | `#AARRGGBB`.                                                                                                    |
| `lineWidth`  | Pixels before wrapping. Default 200.                                                                            |
| `seeThrough` | Visible through blocks.                                                                                         |
| `shadow`     | Draw a text shadow.                                                                                             |
| `anchor`     | Which point of the text is at the node's origin (see below). Default none: it stands on the origin, centred.    |

**Anchors.** `anchor` is one of `top_left`, `top`, `top_right`, `left`,
`center`, `right`, `bottom_left`, `bottom` or `bottom_right`: the point of the
display's rectangle that's put at the node's origin, the engine accounting for
how the game lays a text display out (a font pixel is 1/40 of a block, a line
10 pixels, the background's pixel of padding). Text with an `anchor` is
measured by the server with the game's font (`fonts/default.json`, which the
editor writes) and the resource packs' glyphs (as `<glyph:…>` tags or the
characters `nf.text.glyph` gives, which take the text's colour), its rectangle being its widest line (every character and the
gap after it) by its lines; lines break only at line breaks, never at
`lineWidth`. Text the server can't measure is drawn unanchored, with a warning
in the log. A glyph with `ascent` 7 has its top at the top of its line, so a
glyph anchored `top_left` lines up with a rect anchored `top_left` at the same
place.

**Rect**: `{ "type": "rect", "color": "#FF3366", "size": [8.4, 4.8], "anchor": "top_left" }`.
A flat rectangle of one colour in the node's XY plane, facing -Z as text does,
exactly `size` blocks (width, height; default `[1, 1]`) before the node's
scale. `color` is `#RRGGBB`, or `#AARRGGBB` for one you can see through.
`anchor` defaults to `center`. It's drawn as a text display's background, so
it needs only the space characters every built resource pack has. A script
recolours and resizes it (`node:set_display_color`, `node:set_display_size`)
with one small update, nothing respawned.

**Image**: `{ "type": "image", "width": 96, "height": 54, "size": [6, 3.375] }`.
A picture `width` by `height` pixels (at most 256 either way and 16384 in
all) that a script sets at runtime with `node:set_image` (from `nf.images`),
filling `size` blocks (default 16 pixels a block) in the node's XY plane,
facing -Z. `anchor` defaults to `center`. It's blank until a script sets a
picture. Pixels are drawn as rows of the engine's pixel glyphs, one text colour
per run of equal pixels, so a picture moves and eases like any display and
needs no resource pack change; pixels less than half opaque are left out. Its
viewers are sent a picture once: setting the same pixels again sends nothing.
A picture with many colour changes is a lot of text (a 128 by 72 photo is
about 300 KiB of it, and about 25 KiB once the connection compresses it), so a
centity's changed images send at most about 64 KiB a tick between them,
counted compressed, and the rest follow on the next ticks. Every character is
also the client's to lay out and draw each frame: a few photo-sized pictures
on screen cost its frame rate, so fewer colours or fewer pixels draw faster.

### `hitbox`

```json partial
"hitbox": {}
"hitbox": { "boxes": [{ "min": [0, 0, 0], "max": [1, 0.5, 1] }] }
"hitbox": { "shape": "collision" }
```

- No `boxes` and no `shape`: the unit cube a block display fills.
- `boxes`: explicit boxes in the node's space, as `min`/`max` corners.
- `shape: "collision"`: follow the collision shape of the node's block
  display, live. The server knows any block's collision shape, so this keeps
  up when a script swaps the block. Needs a block display on the same node.

Minecraft's interaction entities are axis-aligned boxes with a square
footprint, so the server spawns one box around everything. A hitbox whose
box is centred on the centity's place, with its bottom there (a mob's body
box), rides along with the displays; one anywhere else is a separate entity
that the server moves after them, which costs a little more. With `raycast`
on, a click that lands in that box but outside the real boxes is ignored. It's
on by default whenever the shape is more than one box.

**Fitting to a display.** A stair's model or a text quad are only known to the
game client, so the server can't follow them. Instead, the editor's _Fit to
display_ writes the display's model boxes into `boxes` and records what it
fitted to in `fittedTo`. If the display later changes, validation warns that
the fit is stale.

### `physics`

All optional. Rates are per second.

| Key           | Meaning                                                       | Default                      |
| ------------- | ------------------------------------------------------------- | ---------------------------- |
| `gravity`     | Downward acceleration, blocks/s²                              | 32                           |
| `mass`        | Matters only between bodies                                   | collider volume              |
| `bounciness`  | 0 lands dead, 1 bounces forever                               | 0                            |
| `friction`    | Coulomb coefficient; 0 is ice, ~0.6 wood on stone             | 0.6                          |
| `drag`        | Fraction of speed shed per second in the air                  | 0.02                         |
| `angularDrag` | The same for spin                                             | 0.05                         |
| `maxSpeed`    | Blocks per second                                             | 60                           |
| `maxSpin`     | Degrees per second                                            | 3600                         |
| `collider`    | Its own box (`min`/`max`), instead of the hitbox              | the hitbox, or the unit cube |
| `shape`       | `box` collapses a multi-box hitbox to one box; `sphere` rolls | follows the hitbox           |
| `rotates`     | Whether it can turn at all                                    | true                         |
| `sleeps`      | Stop simulating once at rest                                  | true                         |
| `blocks`      | Collide with the world                                        | true                         |
| `entities`    | Collide with other centities' hitboxes                        | true                         |

## `body`

```json partial
"body": { "width": 0.9, "height": 0.75, "stepHeight": 0, "bounce": 0.2 }
```

A body moves the **whole centity**: an upright box, `width` square and
`height` tall, standing with its bottom middle at the centity's position,
which the server walks, drops, jumps and stops against the blocks' collision
shapes every tick (a slab is half a block, a fence a block and a half). It's
what a mob, a critter or a rolling boulder moves by, where a node's `physics`
tumbles one node. Scripts steer it ([`Centity`](../reference/centity.md):
`set_walk_velocity`, `jump`, `set_velocity`) and read what it's doing
(`is_on_ground`, `is_in_liquid`, `wall`; the `land` and `bump` events). Its
hitboxes are separate: they're what clicks and queries test.

All optional. Rates are per second, as `physics`'s are.

| Key               | Meaning                                                                         | Default |
| ----------------- | ------------------------------------------------------------------------------- | ------- |
| `width`           | Blocks across, square: more than 0, at most 16                                  | 0.6     |
| `height`          | Blocks tall: more than 0, at most 32                                            | 1.8     |
| `gravity`         | Downward acceleration, blocks/s²                                                | 32      |
| `maxFallSpeed`    | The fastest it falls, blocks/s                                                  | 78.4    |
| `drag`            | Fraction of its speed shed per second in the air (every axis), 0 to 1           | 0.33    |
| `liquidDrag`      | The same in water or lava                                                       | 0.98    |
| `liquidGravity`   | How much of `gravity` pulls it in water or lava: 0 floats, 1 sinks as in air    | 0.25    |
| `acceleration`    | How fast it reaches the speed it walks at, on the ground (or flying), blocks/s² | 20      |
| `airAcceleration` | The same in the air: how much it can steer while it falls or jumps              | 5       |
| `friction`        | How fast it slows to a stop on the ground when it isn't walking, blocks/s²      | 24      |
| `stepHeight`      | The tallest step it walks straight up, blocks; 0 means it must jump             | 0.6     |
| `bounce`          | 0 stops dead against what it hits, 1 bounces off at the speed it came           | 0       |
| `fly`             | No gravity: walking steers it in any direction, and drag slows it on every axis | false   |
| `blocks`          | Collides with the world's blocks; `false` passes through them                   | true    |

A body that starts inside blocks (spawned there, or the world changed round
it) rises out of them. It doesn't move into a chunk that isn't loaded. While
`move_to` walks the centity, the walk moves it and the body waits; a teleport
keeps its velocity. Nothing about it is saved: after a restart it starts at
rest where the centity stands.

## `health`

```json partial
"health": { "max": 14, "bar": { "segments": 10, "showTicks": 100 } }
```

Health it loses to scripts' `centity:damage` and dies at 0: its `damage`
handlers may change or cancel each blow, and `death` hears it at 0, after
which it's removed (unless `removeOnDeath` is `false`). Scripts read and set it
([`Centity`](../reference/centity.md): `health`, `max_health`, `set_health`,
`heal`, `is_dead`). It isn't saved: after a restart it's whole.

| Key             | Meaning                                                       | Default |
| --------------- | ------------------------------------------------------------- | ------- |
| `max`           | Its health when whole, more than 0. Required.                 | —       |
| `removeOnDeath` | Removed when it dies, after its `death` handlers.             | true    |
| `bar`           | A bar over it showing what it has left (below). Absent: none. | none    |

`bar` draws the health left as a line of `segments` pieces, the full ones in
`filled` and the rest in `empty`, on a text display node called `health_bar`:
the file's own (a text display, placed and styled as you like), or one the
server adds `offset` blocks over the top of its hitboxes. It shows for
`showTicks` after each change and hides again; with `showTicks` 0 it always
shows.

| Key         | Meaning                                                          | Default       |
| ----------- | ---------------------------------------------------------------- | ------------- |
| `segments`  | How many pieces, 1 to 40                                         | 10            |
| `showTicks` | Ticks it shows after a change; 0 always                          | 100           |
| `offset`    | Blocks over the top of its hitboxes, for the bar the server adds | 0.35          |
| `glyph`     | One piece, as text                                               | `▮`           |
| `filled`    | MiniMessage before the full pieces                               | `<red>`       |
| `empty`     | MiniMessage before the empty ones                                | `<dark_gray>` |

## `properties`

```json partial
"properties": { "damage": 12, "class": "melee", "tags": ["blade"] }
```

The project's own facts about this centity, as any JSON: what scripts read with
`centity:properties()` on an instance, or `nf.centities.properties(kind)` without one, so data that belongs to it lives in its file instead of
a table a script keeps in step. Nothing but scripts reads it, and changing it
reloads it like any change to its file.

## `script`

```json partial
"script": { "file": "script.lua", "budget": 200000 }
```

| Key      | Meaning                                                                | Default |
| -------- | ---------------------------------------------------------------------- | ------- |
| `file`   | A `.lua` file in this centity's folder.                                | —       |
| `budget` | Lua instructions one call into the script may use before it's stopped. | 200000  |

One copy of the script runs per spawned centity. In it, `this` is that
centity (a [`Centity`](../reference/centity.md)). A tick handler sets its own
rate: `this:on("tick", fn, { every = 5 })`. See
[Scripting](../guide/scripting.md).

### Files beside the script

Any other `.lua` files in the centity's folder, at any depth, belong to the
centity. The script loads them with `require`, which looks in the script's
folder before it looks for a module: `require("helpers")` is `helpers.lua`
(or `helpers/init.lua`), `require("ai.steering")` is `ai/steering.lua`. Such
a file runs as part of the script: its globals are the script's, `this` is
the same centity, and every spawned centity runs its own copy, once. Saving,
adding or deleting one reloads the centity, as saving the script does. Name
them as module files are named, with letters, digits and `_`, or `require`
can't reach them (`script.file-name`, a warning). See
[`require`](../guide/scripting.md#require).

## `spawning`

```json partial
"spawning": {
  "worlds": ["world_the_end"],
  "biomes": ["#minecraft:is_end"],
  "blocks": ["minecraft:end_stone"],
  "light": { "max": 7 },
  "height": { "min": 40 },
  "weight": 4,
  "group": { "min": 1, "max": 3 },
  "cap": 6,
  "despawnDistance": 80,
  "keepOnInteract": true,
  "time": "night",
  "moonPhase": ["full_moon"],
  "weather": ["clear"],
  "when": "blood_moon"
}
```

A centity with a `spawning` block appears by itself near players, the way
mobs do. Every field is optional; a block with none lets it appear anywhere a
player is, so say where.

| Key               | Meaning                                                                                                                                               | Default |
| ----------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- | ------- |
| `worlds`          | World names it may appear in                                                                                                                          | any     |
| `biomes`          | Biomes: the game's (`minecraft:plains`) or `#tags` of them (`#minecraft:is_forest`), or the project's [own](biome.md) (`ruby_grove`)                  | any     |
| `blocks`          | The block it stands on: block ids, or `#tags` of blocks                                                                                               | any     |
| `light`           | `{ "min", "max" }`: the light level at the spot, 0 to 15 (the brighter of block and sky light)                                                        | 0 to 15 |
| `height`          | `{ "min", "max" }`: the block `y` it may appear at                                                                                                    | any     |
| `weight`          | How likely it is against the other centities that may appear at the same spot                                                                         | 10      |
| `group`           | `{ "min", "max" }`: how many appear together                                                                                                          | 1       |
| `cap`             | The most of this centity that may be natural and near one player at once                                                                              | 4       |
| `despawnDistance` | How far from every player a natural one is removed, in blocks                                                                                         | 96      |
| `keepOnInteract`  | `true`: a player clicking a natural one keeps it, as `keep()` does                                                                                    | `false` |
| `time`            | The time of day: `"day"`, `"night"`, or `{ "min", "max" }` in ticks of the day (0 to 23999)                                                           | any     |
| `moonPhase`       | The moon's phases: `full_moon`, `waning_gibbous`, `last_quarter`, `waning_crescent`, `new_moon`, `waxing_crescent`, `first_quarter`, `waxing_gibbous` | any     |
| `weather`         | The world's weather: `clear`, `rain` (a thunderstorm is rain too) or `thunder`                                                                        | any     |
| `when`            | A condition a script decides: the name it was given with `nf.spawning.condition` (a package's as `ns:name`)                                           | none    |

An empty list is the same as leaving the key out, and either end of a range
may be left out. A `weight`, `cap`, `despawnDistance` and group size must be
positive (`centity.spawning-number`), a range's `min` can't be above its `max`
(`centity.spawning-range`), and ids are checked against the target version's
biomes and blocks (`centity.spawning-biome`, `centity.spawning-block`). A plain
biome id is one of the project's biomes (`reference.biome` when there's none);
the game's are written with their namespace.

**Time, moon and weather** are the world's, asked as each place is tried. `"night"`
is from dusk to sunrise, ticks 13000 to 22999 of the day (what `/time set night`
sets is its start), and `"day"` the rest, 23000 through 12999. A range is
inclusive at both ends, and one whose `min` is above its `max` runs through
midnight: `{ "min": 22000, "max": 2000 }`. Leaving out an end runs from the
day's first tick or to its last. A tick outside 0 to 23999 is
`centity.spawning-time`. Weather is the world's, not the biome's: `rain`
lets one appear in a desert while it rains elsewhere.

**`when`** names a condition a module registers:

```lua
-- modules/events/init.lua
local blood_moon = false
nf.spawning.condition("blood_moon", function(check)
  return blood_moon
end)
```

The condition is asked last, after every other key fits the place, with the
centity, the place and the player it's near, and the centity appears only
when it answers `true`. A bare name is the centity's own package's condition;
another package's is `ns:name`. A name that isn't an id is
`centity.spawning-when`. Nothing appears while no script has registered the
condition (the server logs that once), and a condition that errors counts as
`false`. It's cheaper and clearer than refusing spawns in
`centity_natural_spawn`, which only hears a spawn after the place was chosen.

About once a second, around each
player it picks places 24 to 48 blocks away in loaded chunks, finds the
centities whose rules fit the place, picks one by `weight`, and spawns a
`group` of them near each other. The caps are the project's own: they count
only natural centities, per player, and are separate from the mob caps the
server (or `worlds.<name>.spawnLimits`) sets for vanilla mobs. A
`despawnDistance` of 48 or less removes centities as they appear
(`centity.spawning-despawn`, a warning).

A natural centity is **temporary**: it isn't saved, it's removed when no
player is within `despawnDistance` of it, and a restart drops it. A script
that wants one to stay calls `centity:keep()`, which makes it an ordinary
centity from then on (saved, never despawned, and no longer counted against
the cap). With `keepOnInteract`, a player's click on one of its nodes does
that too, before any script hears the click. Before one is placed, `nf.on("centity_natural_spawn")` can refuse it
or move it.

## Animations

```json partial
"animations": {
  "bob": {
    "loop": "loop",
    "autoplay": true,
    "length": 2,
    "tracks": {
      "light": {
        "translation": [
          { "time": 0, "value": [0, 0, 0], "easing": "ease_in_out" },
          { "time": 1, "value": [0, 0.25, 0] }
        ]
      }
    }
  }
}
```

| Key        | Meaning                                                                                 | Default             |
| ---------- | --------------------------------------------------------------------------------------- | ------------------- |
| `loop`     | `once` (play, then return to the base pose), `loop`, or `hold` (stay on the last pose). | `once`              |
| `autoplay` | Start when the centity loads, without a script.                                         | false               |
| `length`   | Seconds. Keys after it never play.                                                      | the last key's time |
| `blend`    | `replace` (its tracks own their channels) or `additive` (on top of the pose under it).  | `replace`           |
| `tracks`   | Node name → channel (`translation`, `rotation`, `scale`) → keyframes.                   | —                   |

A keyframe is `{ "time", "value", "easing" }`. `easing` shapes the segment
leaving the key: `linear` (default), `step`, `ease_in`, `ease_out` or
`ease_in_out`. A track owns its whole channel. Outside its keys, it holds the
nearest one. Rotation interpolates each axis the short way round, so a spin
needs a key at least every 180°.

An `additive` animation changes whatever poses the node otherwise (its rest
pose, what a script set, the replacing animations playing), after all of them:
its translation and rotation keys are added, its scale keys multiply. So its
keys are changes, `[0, 0, 0]` (`[1, 1, 1]` for scale) leaving the node as it
is: a squash `[1.3, 0.7, 1.3]` flattens a slime whatever size a script made it,
while it walks. With `blend_ticks` it eases in from no change.

Players' games ease display entities between poses themselves, so while only
animations move a node the server sends a pose some ticks ahead and lets the
game ease there: a `linear` segment once, an eased one in a few straight
pieces, a `step` when it happens. Looping idles, bobbing and flapping cost
almost nothing to keep playing. A node a script or physics moves is sent
every tick instead.
