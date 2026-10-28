# Recipes

A recipe teaches the server something new to make at a crafting table, a
furnace (or blast furnace, smoker or campfire), a smithing table or a
stonecutter. Each is one file, `recipes/<id>.json`, with nothing beside it.

```json
{
  "$schema": "../.netherforge/schema/recipe.schema.json",
  "type": "shaped",
  "pattern": [" R ", " R ", " S "],
  "key": {
    "R": { "item": "ruby" },
    "S": "minecraft:stick"
  },
  "result": { "kind": "minecraft:iron_sword", "name": "<red>Ruby sword" },
  "category": "equipment"
}
```

The id is the file's name, and it's how scripts and players' recipe books
name the recipe (`player:discover_recipe("ruby_sword")`). Scripts can add
recipes too, in the same shape (`nf.recipes.register`).

| Key           | Meaning                                                                                                                                                               | Types                         |
| ------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------- |
| `type`        | `shaped`, `shapeless`, `furnace`, `blasting`, `smoking`, `campfire_cooking`, `smithing_transform`, `stonecutting` or `station` ([below](#station-recipes)). Required. | all                           |
| `pattern`     | One to three rows of one to three characters, all as wide: each character a `key`, or a space for an empty square.                                                    | `shaped`                      |
| `key`         | What each character of the pattern stands for: an ingredient.                                                                                                         | `shaped`                      |
| `ingredients` | One to nine ingredients, in any order and any squares.                                                                                                                | `shapeless`                   |
| `ingredient`  | What goes in.                                                                                                                                                         | cooking types, `stonecutting` |
| `template`    | The smithing template.                                                                                                                                                | `smithing_transform`          |
| `base`        | The item being upgraded; its components carry over to the result.                                                                                                     | `smithing_transform`          |
| `addition`    | The material.                                                                                                                                                         | `smithing_transform`          |
| `result`      | What it makes: an [item](menu.md#items), which can be a [project item](item.md) (`{ "item": "ruby" }`). Required.                                                     | all                           |
| `experience`  | Experience it gives. Default 0.                                                                                                                                       | cooking types                 |
| `cookingTime` | Ticks it takes. Defaults: 200 in a furnace, 100 in a blast furnace or smoker, 600 on a campfire.                                                                      | cooking types                 |
| `group`       | Recipes with the same group share one entry in the recipe book.                                                                                                       | all but `smithing_transform`  |
| `category`    | The recipe book tab: `building`, `redstone`, `equipment` or `misc` for crafting; `food`, `blocks` or `misc` for cooking.                                              | crafting and cooking types    |

A field a type doesn't take is an error (`recipe.field`), and so is one it
needs and doesn't have (`recipe.missing`). A pattern with too many rows or
columns, rows of different widths, or a character that isn't in `key` is
`recipe.pattern`; a key that's more than one character, or isn't in the
pattern, is `recipe.key`. A shapeless recipe takes one to nine ingredients
(`recipe.ingredients`).

## Station recipes

```json partial
{
  "type": "station",
  "materials": [
    { "ingredient": { "item": "gel" }, "count": 1 },
    { "ingredient": "#minecraft:planks", "count": 1 }
  ],
  "stations": ["work_bench"],
  "stationRange": 4,
  "group": "lighting",
  "result": { "item": "torch", "count": 3 }
}
```

A `station` recipe is one the game never sees: scripts make it, the way a
crafting menu of your own lists what a player can make and makes it
(`nf.recipes.available`, `nf.recipes.craft`, `nf.recipes.get`). It takes its
`materials` from the player's inventory, each an [ingredient](#ingredients)
and a `count` (default 1), and needs every block `stations` names within
`stationRange` blocks of them (default 4, at most 16) on every axis: a block
id (`"minecraft:crafting_table"`), a `#tag` of blocks, or one of the
project's [blocks](block.md) (`"work_bench"`). `group` sorts them, as a
menu may by station. They're never in a player's recipe book, and
`pattern`, `key`, `ingredients`, `experience` and the rest are an error on one.

| Key            | Meaning                                                            | Default        |
| -------------- | ------------------------------------------------------------------ | -------------- |
| `materials`    | What it takes: `{ "ingredient": ..., "count": n }` each. Required. | —              |
| `stations`     | Blocks that must all be near: ids, `#tags` or project blocks; `"a  | b"` is either. | none |
| `stationRange` | How near, in blocks, 1 to 16.                                      | 4              |

## Ingredients

An ingredient is one of:

| Written as            | Meaning                                                               |
| --------------------- | --------------------------------------------------------------------- |
| `"minecraft:stick"`   | A Minecraft item. `"stick"` means the same.                           |
| `"#minecraft:planks"` | Any item in an item tag.                                              |
| `{ "item": "ruby" }`  | One of the project's [items](item.md), by [reference](references.md). |

An item or item tag the game doesn't have is an error once the editor has the
game's data (`recipe.unknown-item`, `recipe.unknown-tag`), and so is a project
item the project doesn't have (`reference.item`).

A project item is matched by the id its stacks carry, never by how they
look, so a ruby whose look has changed since, that carries its own script
data, or that's half worn out still fits. And a recipe takes a project item
only where it names it: a Minecraft item or a tag never takes a project
item's stack, in the project's recipes and in Minecraft's own, so a ruby
built on paper never crafts as paper.

When a project item is in the wrong place, nothing is made: a crafting grid
shows no result, a furnace doesn't cook it (the item stays in), a smithing
table shows no result, and a stonecutter won't select the recipe. One thing
to watch for: a project recipe with the same Minecraft items and shape as one
of Minecraft's own can hide it, since a crafting grid offers one recipe.

## Reloading

Saving a recipe teaches the server the new version and sends everyone online
the recipe list again. A file with errors keeps its last good version. A
deleted file takes the recipe away. Players who discovered a recipe keep it
in their recipe book across reloads and restarts (the server saves it with
them).
