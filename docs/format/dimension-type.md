# Dimension types

A dimension type is what kind of world a world is: how deep and how tall it
is (its build limits), how it's lit, what's drawn overhead, and the game's
rules that differ between the overworld, the nether and the end. Each is one
file, `dimension_types/<id>.json`, with nothing beside it. A world names one when
it's made, and keeps it.

```json
{
  "$schema": "../.netherforge/schema/dimension_type.schema.json",
  "minY": -128,
  "height": 512,
  "ambientLight": 0.05,
  "bedWorks": false,
  "respawnAnchorWorks": true
}
```

Every key is optional: `{}` is the overworld (-64 to 319, daylight, beds
work). A world of this one has blocks from y -128 to 383, can't be slept in,
and lets players set their spawn with a respawn anchor.

## Naming one

A world is made with a dimension type in three ways:

- a script's `nf.worlds.create("deep_mine", { dimension_type = "deep" })`
  ([`WorldCreateOptions`](../reference/nf.worlds.md));
- `netherforge.json`'s `worlds.<name>.dimensionType` (see
  [worlds](worlds.md#dimensions)): the project makes the world with it when the
  server has none by that name;
- the same for the server's **main world**, which the server makes itself
  (see [below](#the-main-world)).

As every [reference](references.md), the project's own is its id (`"deep"`)
and a package's `ns:id` (`"acme:deep"`, if it exports it). One that doesn't
exist is `reference.dimension-type`. On the server it's `<namespace>:<id>`
(`showcase:deep`).

**A world keeps the dimension type it was made with**: it's saved with the world,
so it loads the same after a restart. Naming another for a world the server
has already doesn't change it (`runtime.dimension-type`, a warning). Changing the
dimension type's file does change the world from the next restart: the build
limits are the file's, and a world made taller keeps its blocks while one
made shorter loses what's outside.

## Build limits

| Key             | Meaning                                                                                                                                                     | Default  |
| --------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- |
| `minY`          | The lowest block a world can hold: a multiple of 16, -2032 or more.                                                                                         | -64      |
| `height`        | How many blocks tall from `minY`: a multiple of 16, at least 16. The top (`minY + height`) is at most 2032, so the highest block is at `minY + height - 1`. | 384      |
| `logicalHeight` | How high above `minY` a nether portal, a chorus fruit's teleport or a player sent there can take anyone (the nether's is 128 of its 256): 0 to `height`.    | `height` |

These are the game's own rules (`dimension-type.height`, `dimension-type.logical-height`):
the game refuses the whole start-up datapack over a type that breaks them, so
NetherForge leaves out one that does. A world's [terrain](terrain.md) fills
whatever heights it has: a terrain named with a dimension type in
`netherforge.json` is checked against that dimension type's (`project.world-height`).

## Light and sky

| Key            | Meaning                                                                                                                                                                                                                                       | Default                |
| -------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------- |
| `skyLight`     | Whether the sky lights it: daylight, and dark at night. Without it, only blocks give light.                                                                                                                                                   | true                   |
| `ceiling`      | Whether it has a ceiling of bedrock overhead, as the nether: maps and the weather know.                                                                                                                                                       | false                  |
| `ambientLight` | How bright it is with no light at all, 0 to 1 (the nether's is 0.1).                                                                                                                                                                          | 0                      |
| `fixedTime`    | Whether the sun stands still: it's never day or night, so beds don't skip the night and the moon doesn't change.                                                                                                                              | false                  |
| `sky`          | What's drawn overhead: `overworld` (the sun, the moon, the stars), `end` (the end's dark, streaked sky) or `none` (the fog's colour, as in the nether).                                                                                       | `overworld`            |
| `colors`       | `{ "sky": "#78a7ff", "fog": "#c0d8ff", "clouds": "#ccffffff" }`: the sky's, the fog's and the clouds' colours (the clouds' may be `#aarrggbb`, how opaque first). A [biome](biome.md#colors) with its own sky or fog colour is drawn with it. | the overworld's, shown |
| `cloudHeight`  | The height clouds float at.                                                                                                                                                                                                                   | 192.33                 |

## Rules

| Key                      | Meaning                                                                                                                              | Default                           |
| ------------------------ | ------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------- |
| `bedWorks`               | Whether players can sleep in a bed (when it's dark) and set their spawn with one. When not, a bed blows up when used.                | true                              |
| `respawnAnchorWorks`     | Whether a charged respawn anchor sets a spawn. When not, it blows up when used.                                                      | false                             |
| `piglinSafe`             | Whether piglins and hoglins stay as they are. When not, they turn into zombified ones after a while.                                 | false                             |
| `raids`                  | Whether a player with Bad Omen can start a raid in a village.                                                                        | true                              |
| `ultrawarm`              | Whether it's as hot as the nether: water evaporates when placed, lava flows fast and far, snow golems melt, dripstone drips lava.    | false                             |
| `monsterSpawnLight`      | `{ "min": 0, "max": 7 }`: the light levels (sky and block together, 0 to 15) monsters may spawn at; each try picks one between them. | 0 to 7                            |
| `monsterSpawnBlockLight` | The most light from blocks monsters may spawn in, 0 to 15.                                                                           | 0                                 |
| `infiniburn`             | The block tag of the blocks fire on top of never goes out: `#minecraft:infiniburn_nether` (netherrack too).                          | `#minecraft:infiniburn_overworld` |
| `coordinateScale`        | How far one block here goes in the overworld through a nether portal (the nether's is 8): 0.00001 to 30000000.                       | 1                                 |

The game no longer has one switch for a "natural" world: what it decided
(beds, respawn anchors, piglins) is the keys above. A tag the target version
doesn't have is `dimension-type.infiniburn-tag` (a warning: no fire burns forever).

## The main world

The server makes its main world itself, as it starts and before any plugin
could make it otherwise, as an overworld. So a main world (`level-name` in
`server.properties`, `world` by default) that `netherforge.json` names a
dimension type for gets it another way: the start-up datapack replaces the game's
own overworld type, `minecraft:overworld`, with the dimension type's values.

```json partial
{ "worlds": { "world": { "dimensionType": "deep" } } }
```

That changes **every world of the overworld type** on the server that doesn't
name a dimension type of its own (worlds made with `nf.worlds.create` and no
`dimension_type`, other plugins' overworlds), not the main world alone. It's done
only while the manifest asks; take the line out and the overworld is the
game's again from the next restart. An existing main world keeps its blocks
inside the new limits: start a fresh one (delete the world folder) when you
want its terrain to fill new depths.

## On the server

The game learns dimension types only while it starts, so they're in the
[start-up datapack](advancement.md#on-the-server), as
`data/<namespace>/dimension_type/<id>.json` in the game's own format for the
server's version (26.1 gave a type a clock and the ender dragon flag, 26.3
renamed how a bed blows up and gave the straw bed its own rule; NetherForge
writes whichever the server reads). A dimension type with errors is left out of it.
A change needs a restart: on the editor's dev server, saving a dimension type
restarts the server; on a server of your own, `/nf reload` says the server
must restart (`runtime.restart`, a warning). A world naming a dimension type the
server didn't start with isn't made, or loaded (the server would make it the
overworld's height), until it does.

Bukkit has no way to make a world of a dimension type of a datapack's: the
plugin writes the world's generation settings (the server's own saved data,
naming the type) before the server makes it, which the server then reads as
it does for any world it has saved.

## In packages

A package's dimension types are in its own namespace on the server
(`library:deep`), and a project names the ones it exports as `ns:id`.

## Problems

[Problems](problems.md) lists them. The codes are `dimension-type.*`,
`reference.dimension-type` for a dimension type that doesn't exist,
`project.world-height` for a world's terrain outside its dimension type's limits,
and `runtime.dimension-type` on the server.
