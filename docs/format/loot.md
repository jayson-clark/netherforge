# Loot tables

A loot table says what a roll gives: a chest's contents, a mob's or a
block's drops, a reward. Each is one file, `loot/<id>.json`, with nothing
beside it. It's shaped like Minecraft's loot tables, cut down to what people
write by hand.

```json
{
  "$schema": "../.netherforge/schema/loot_table.schema.json",
  "pools": {
    "gems": {
      "rolls": { "min": 1, "max": 2 },
      "entries": [
        {
          "type": "item",
          "item": { "item": "ruby" },
          "count": { "min": 1, "max": 3 },
          "weight": 3
        },
        {
          "type": "item",
          "item": { "kind": "minecraft:diamond" },
          "conditions": [{ "type": "chance", "chance": 0.25 }]
        },
        { "type": "empty", "weight": 2 }
      ]
    },
    "extra": {
      "conditions": [{ "type": "player" }],
      "entries": [
        { "type": "table", "table": "junk" },
        { "type": "vanilla", "table": "minecraft:chests/simple_dungeon" }
      ]
    }
  }
}
```

The id is the file's name, and it's how scripts roll it
(`nf.loot.roll("treasure")`) and other loot tables include it
(`{ "type": "table", "table": "treasure" }`).

| Key     | Meaning                                                                                |
| ------- | -------------------------------------------------------------------------------------- |
| `pools` | The pools, by name. A roll rolls every pool, in name order, and gives what each picks. |

A pool's name is only for people and the editor: lowercase letters, digits
and `_` (`loot.pool-name`). Pools are keyed by name rather than listed, so
two people adding one merge cleanly.

## Pools

A pool picks an entry `rolls` times. Each pick weighs the entries whose
conditions pass against each other and takes one.

| Key          | Meaning                                                                                            |
| ------------ | -------------------------------------------------------------------------------------------------- |
| `rolls`      | How many picks: a number, or a [range](#ranges). Default 1, at most 100.                           |
| `bonusRolls` | More picks per point of the roll's luck, rounded down. Default 0.                                  |
| `conditions` | [Conditions](#conditions) that must all pass for the pool to pick anything.                        |
| `entries`    | What it picks from, in order. A pool without entries gives nothing (`loot.no-entries`, a warning). |

## Entries

Every entry has a `type`, and these besides:

| Key          | Meaning                                                                                                             |
| ------------ | ------------------------------------------------------------------------------------------------------------------- |
| `weight`     | How likely a pick lands on it, against the other entries that may be picked. At least 1 (`loot.weight`). Default 1. |
| `quality`    | Added to `weight` per point of the roll's luck; negative makes it rarer with luck. Default 0.                       |
| `conditions` | [Conditions](#conditions) that must all pass for it to be picked at all.                                            |

A pick weighs each entry whose conditions pass by `weight + quality × luck`,
rounded down; an entry at 0 or below can't be picked. With one entry left it
takes that one.

| `type`    | Gives                                                                                                                                                                                                                                                                                                                                                           |
| --------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `item`    | `item`, an [item](menu.md#items): a Minecraft item (`{ "kind": "minecraft:diamond" }`) or a [project item](item.md) (`{ "item": "ruby" }`), styled as any stack can be. `count` says how many: a number or a [range](#ranges), default 1, at most 1000. The item itself has no `count` (`loot.item-count`). More than a stack holds is given as several stacks. |
| `table`   | Rolls another of the project's loot tables whole, by [reference](references.md) (`"junk"`, or a package's `"acme:junk"`). A table that includes itself, through its entries or theirs, is an error (`loot.cycle`).                                                                                                                                              |
| `vanilla` | Rolls one of the game's loot tables on the server, by its id (`"minecraft:chests/simple_dungeon"`), as the game would. One the target version doesn't have is an error once the editor has the game's data (`loot.unknown-vanilla`).                                                                                                                            |
| `empty`   | Nothing: its weight is the chance of no loot from that pick.                                                                                                                                                                                                                                                                                                    |

A game loot table is rolled by the server, with the roll's luck and seed,
and needs a place to be rolled at: the roll's `location`, or its player's or
looted entity's. Some of the game's tables need more than that (a mob's
needs the mob), and rolling one without it is an error when it's rolled.

## Ranges

A count or a number of rolls is a whole number (`2`), or a range picked
from uniformly, both ends included: `{ "min": 1, "max": 3 }`. A range whose
ends are equal is written as the number. A negative end, a `max` below its
`min`, or more than the most allowed is `loot.range`.

## Conditions

A condition is something about the roll that must hold. Every condition
takes `"invert": true`, which turns it around: "unless".

| `type`        | Passes when                                                                                                                                                                                                                                                    |
| ------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `chance`      | A random draw is below `chance`, from 0 to 1 (`loot.chance`), drawn afresh each time it's asked.                                                                                                                                                               |
| `player`      | A player is behind the roll: the one who killed the mob, broke the block or opened the chest. Minecraft's `killed_by_player`.                                                                                                                                  |
| `tool`        | The roll's tool (what the player killed or broke it with) is `tool`: a Minecraft item (`"minecraft:shears"`), an item tag (`"#minecraft:pickaxes"`) or a project item (`{ "item": "ruby_pick" }`), matched as a [recipe ingredient](recipe.md#ingredients) is. |
| `enchantment` | The roll's tool has `enchantment` (`"minecraft:silk_touch"`) at `level` or above (default 1).                                                                                                                                                                  |

Item and tag ids the game doesn't have are errors once the editor has the
game's data (`loot.unknown-tool`, `loot.unknown-enchantment`).

Minecraft's own loot tables have many more conditions and functions. Most of
them depend on things a script can check before it rolls (the weather, a
score, a block's state), so here a script decides those and picks the table
or the luck to roll with.

## Rolling

Scripts roll a table with `nf.loot.roll(id, context)`, which gives back the
items, and fill a chest with `nf.loot.fill(id, inventory, context)`; see
[`nf.loot`](../reference/nf.loot.md). The context says who and what the roll
is for: `player`, `tool`, `luck`, `looted`, `location`, and a `seed` that
makes it give the same thing every time (the editor's roll preview gives the
same for a seed as the server does). The same rules as
Minecraft's: each pool whose conditions pass makes `rolls + bonusRolls ×
luck` picks; a table entry rolls that table with the same draws.

## In packages

A package's loot tables are its own, as its other resources are: a name in
one (an item's, another table's) means what it does in that package, so the
library's `{ "item": "gem" }` is the library's gem wherever the table is
rolled from, and a table can name its package's private items and tables. A
project that depends on the package can include or roll only the tables it
exports (`"exports": { "loot": ["gems"] }`), as `library:gems`; any other is
`reference.not-exported`, and the same error in a script. See
[packages](packages.md).

## Reloading

Saving a loot table is what the next roll uses. A file with errors keeps its
last good version, and a deleted one can't be rolled any more.
