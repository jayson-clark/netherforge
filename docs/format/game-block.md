# The game's blocks

A game block file gives some of the game's own blocks mining rules of the
project's: how long they take to mine, with what tool, and what they drop. It
lives at `game_blocks/<id>.json`, one file per set of rules, and the id is
only the file's name.

```json
{
  "$schema": "../.netherforge/schema/game_block.schema.json",
  "blocks": [
    "minecraft:deepslate_bricks",
    "minecraft:deepslate_brick_stairs",
    "minecraft:deepslate_brick_slab",
    "minecraft:deepslate_brick_wall"
  ],
  "hardness": 6,
  "tool": "pickaxe",
  "requiresTool": true,
  "drops": "deepslate_rubble"
}
```

It's how one of the game's blocks takes the place of a
[project block](block.md). A resource pack
[redraws its textures](resource-pack.md#redrawing-the-games-textures) (or
[its model](resource-pack.md#redrawing-the-games-models)), and this file gives
it the hardness and drops the project wants. Everything else stays the game's
own: its shape, light, collision, footstep and hit sounds, falling, spreading
and growing. A game block also comes with its stairs, slabs and walls, and
none of it takes up a [carrier](block.md#carriers) state.

| Key            | Meaning                                                                                                                                                                                                   | Default         |
| -------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------- |
| `blocks`       | The game's blocks these rules are for: ids (`minecraft:deepslate_bricks`, every state of it) and block tags (`#minecraft:logs`). Stairs, slabs and walls are ids of their own, so list each one you mean. | —               |
| `hardness`     | How long it takes to mine, as a [project block's](block.md#mining) (stone 1.5, obsidian 50). 0 breaks at once, -1 can't be broken by hand.                                                                | the game's      |
| `tool`         | `pickaxe`, `axe`, `shovel` or `hoe`: what mines it fastest.                                                                                                                                               | the game's      |
| `requiresTool` | It drops nothing unless it's mined with the right tool: `tool`, or without one a tool the game mines the block faster with (a pickaxe on stone). `false` drops the project's `drops` for any tool.        | the game's rule |
| `drops`        | `<id>`: the [loot table](loot.md) rolled when it's broken, instead of the game's drops.                                                                                                                   | the game's      |

**A key left out stays the game's.** A file with only `drops` changes what
the block drops and nothing about how it's mined. With `hardness`, `tool` or
`requiresTool`, the server mines the block (see [mining](#mining)), and
anything left out is the game's value for the block: `hardness` alone keeps
the game's tools for it. A block the project doesn't name is left alone.

A block has one set of rules. A block named twice, in one file or in two
(a tag counts every block in it), is an error (`game_block.twice`) at the
later file, by id. A saved file's rules hold from the next block mined or
broken. The blocks the project's own blocks are
[held in](block.md#carriers) (the note block, while the project has note
block blocks) are the project's to the server, so they can't be named
(`game_block.carrier`).

## In a package

A [package](packages.md)'s game block files run wherever the package does,
as its other resources do: a server running a project runs its packages'
rules beside its own. The game's blocks are the whole server's, so a block
has one set of rules across the project and every package it runs:

- A project's file naming a block a package already has rules for, or one a
  package's blocks are held in, is the project's error, at its file
  (`game_block.twice`, `game_block.carrier`). The package's rules hold.
- Two packages with rules for one block, or a package's rules for a block
  another package's blocks (or the project's own) are held in, are the
  project's error, at its `netherforge.json`'s `dependencies`, naming both
  files: each package is right on its own, and it's the project that runs
  them together.

A package is still checked on its own, so it's never wrong for what another
package or the project depending on it holds.

## Mining

A block with `hardness`, `tool` or `requiresTool` is mined the way a project
block is ([mining](block.md#mining)). The server counts each tick of it, shows
the cracks to everyone nearby, and breaks the block when it's done. While a
player looks at or mines one, their `block_break_speed` is turned right down,
so their own game never finishes first. Faster tools, enchantments, haste and
standing on the ground count as they do for any block. A block with
`hardness` 0 breaks on the first hit, -1 can't be broken in survival, and
creative players break blocks at once, as always. A block the game itself
breaks at once (flowers, torches) can't be made slower, and one it can't break
at all (bedrock) stays that way: a player's own game decides those on the
first hit.

When the server breaks it, the break is the game's own, for the player, as if
they'd mined it. Protection plugins may refuse it, the tool wears, experience
drops, and a container's contents and a door's other half go as the game
does it. The breaker sees and hears it break as everyone else does.

**Adventure mode** works as it does for any block: a player in adventure
mode mines a game block only with a tool whose `canBreak` names it (the
game's ids and tags, as an [item's](item.md) `canBreak` takes them), and
places blocks only against what `canPlaceOn` names.

**Sounds.** A game block keeps the game's sounds. Its footsteps, hits,
falls, placing and breaking are played by each player's own game, from the
block's sound type. To change them, a resource pack replaces the game's
[sound event](resource-pack.md#replacing-the-games-sounds)
(`minecraft:block.deepslate_bricks.break`). That changes every block of the
same sound type, which for most blocks is only its own family (the deepslate
bricks' stairs, slabs and walls).

## Drops

`drops` replaces the game's drops for the block whoever breaks it, and rolls
the project's loot table with what's known of the break: the player and their
tool (so a table's `tool` and `enchantment` conditions work), or nothing for a
break nobody made. With `requiresTool` and the wrong tool, nothing drops.

| How it breaks                                                                              | Drops                                                     |
| ------------------------------------------------------------------------------------------ | --------------------------------------------------------- |
| A player mines it (survival or adventure)                                                  | the table, for the player and their tool                  |
| An explosion (TNT, a creeper, a bed in the nether)                                         | the table, with the explosion's chance, as the game's are |
| What holds it up goes, water or lava washes it away, a piston breaks it, a mob destroys it | the table                                                 |
| Leaves decay                                                                               | the table                                                 |
| A script's `block:break_naturally(tool)`                                                   | the table, for that tool                                  |
| A creative player breaks it                                                                | nothing, as always                                        |

Experience and a container's contents still drop as the game's. Without
`drops`, the game's own drops fall, by the game's rule for the tool:
`requiresTool: false` can't make the game's loot drop for a tool the game
says is wrong (`game_block.requires-tool`, a warning), so name `drops` for
that.

## Checks

A game block file is checked on its own (`game_block.blocks`,
`game_block.hardness`, `game_block.requires-tool`, and with the game's data
`game_block.unknown`: a block or tag the target version doesn't have) and
against the project (`game_block.twice`, `game_block.carrier`, and its `drops`
must exist, `reference.loot-table`). [Problems](problems.md) lists them.

Against a project's packages, see [in a package](#in-a-package).
