# Items, recipes and loot

This guide covers the project's own items, the recipes that make them, the
loot tables that hand them out, and the buffs and advancements that often go
with them. The file format pages ([items](../format/item.md),
[recipes](../format/recipe.md), [loot tables](../format/loot.md),
[buffs](../format/buff.md), [advancements](../format/advancement.md)) list
every key.

## Items in scripts

Anywhere a script takes or gives an item, it's a plain Lua table:

```lua
player:give_item({ kind = "minecraft:diamond_sword", name = "<aqua>Frostbite", enchantments = { ["minecraft:sharpness"] = 3 } })

local held = player:held_item()      -- nil for an empty hand
if held and held.kind == "minecraft:stick" then
  held.count = held.count - 1
  player:set_held_item(held.count > 0 and held or nil)
end
```

`kind` is the Minecraft item, `count` how many, and everything else
(`name`, `lore`, `enchantments`, `glint`, `item_model`, …) is how it looks and
behaves: the same fields a [menu slot's item](../format/menu.md#items) has, in
Lua spelling. A table is a copy: changing it changes nothing until you hand it
back (`set_held_item`, `set_item`, `give_item`).

An item's **`data`** is yours: any table of values, kept on that one stack
wherever it goes (a chest, the ground, after a restart).

```lua
player:give_item({ kind = "minecraft:paper", name = "Ticket", data = { seat = "B12" } })
```

::: warning A player in creative mode can make any item
The game lets a creative-mode client hand the server whole stacks it made
itself, so such a player can make any item, a project item and its `data`
included (as they can make any vanilla item). Don't let an item's `data` alone
grant anything that matters (a rank, money, access) to a player who is or has
been in creative: keep what's owed in a database or `nf.data` and check it
there.
:::

To find items in an inventory, give a partial table to match. A `data` in it
matches if the stack's data has those values, so a tag of your own is enough:

```lua
local coins = player:inventory():count_item({ data = { coin = true } })
player:inventory():remove_item({ data = { coin = true } }, 3)
```

## Project items

A **project item** is an item kind of your own: a ruby, a wand, a key card.
Create one in the Project explorer under **Items**; it lives at
`items/<id>/item.json`:

```json
{
  "$schema": "../../.netherforge/schema/item.schema.json",
  "kind": "minecraft:paper",
  "name": "<red>Ruby",
  "lore": ["<gray>Warm to the touch"],
  "itemModel": "ui/ruby",
  "rarity": "rare",
  "script": { "file": "script.lua" }
}
```

`kind` is the Minecraft item it's built on, which decides how it behaves in
the game (paper does nothing; a sword hits). `itemModel` gives it a look of
its own from your [resource packs](resource-packs.md#item-models-custom-item-looks), so a
ruby doesn't have to look like paper.

A project item is **always told apart**: a stack of rubies is a ruby wherever
it goes, after a restart and in any chest, and is never mistaken for plain
paper, by recipes or by anything else. Make stacks of it in a script with
`nf.items.create`, and ask what a stack is with `nf.items.id`:

```lua
player:give_item(nf.items.create("ruby", { count = 5 }))

nf.on("player_drop_item", function(event)
  if nf.items.id(event.item) == "ruby" then
    event:cancel()
    event.player:send_message("<red>You can't drop that.")
  end
end)
```

Elsewhere a project item is named with `item`: in a menu slot
(`{ "item": "ruby" }`), a recipe's result, a loot table, an advancement's icon.

**Changing the file changes every stack.** When you edit `item.json` (a new
name, a new look), stacks that already exist take the new look the next time
the server sees them: in a player's inventory when they join, in a chest when
it's opened. What belongs to each stack (its count, its damage, its `data`)
stays.

### The item's script

An item has at most one script, which runs once for the item (not once per
stack). `this` is the [ProjectItem](../reference/projectitem.md), and it hears
what players do with any stack of it:

```lua
local this = this --[[@as ProjectItem]]

this:on("use", function(event)
  event.player:send_message("<red>The ruby glows in your hand.")
end)

this:on("pickup", function(event)
  event.player:send_actionbar("<red>+" .. event.item.count .. " ruby")
end)
```

Its events are `use`, `interact`, `interact_entity`, `consume`, `hit`,
`break_block`, `drop` and `pickup`: the player's events of the same name,
heard first by the item. `event:cancel()` stops it for everyone.

Two more change how stacks look. **`render`** runs as each stack is made, for
a name or lore worked out from that stack's own `data` (a rolled stat, the
player who crafted it). **`view`** runs each time a stack is shown to one
player, for text that depends on who's looking (a price while they have a
shop open), and changes nothing else: the real stack stays as it is. See
[Prices on a player's own items](menus.md#prices-on-a-player-s-own-items).

```lua
this:on("render", function(event)
  local power = event.item.data and event.item.data.power
  if power then event.lore = { "<gray>Power: <white>" .. power } end
end)
```

### Facts about an item

Put numbers and settings that belong to an item in its file, under
`properties`, and read them in scripts with `this:properties()` (or
`nf.items.get(id):properties()`), instead of keeping a table in step with the
items:

```json
"properties": { "damage": 12, "class": "melee" }
```

### Items that place blocks

An item with `"block": "<id>"` places one of the project's
[custom blocks](blocks.md) on a right click.

## Recipes

A recipe teaches the server something new to make: at a crafting table, a
furnace (or blast furnace, smoker or campfire), a smithing table or a
stonecutter. Each is one file, `recipes/<id>.json`:

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

Ingredients are a Minecraft item (`"minecraft:stick"`), an item tag
(`"#minecraft:planks"`) or a project item (`{ "item": "ruby" }`). A recipe
takes a project item only where it names it: rubies built on paper never
count as paper, in your recipes or in Minecraft's.

Players see your recipes in their recipe book once they've discovered them,
or straight away with `player:discover_recipe("ruby_sword")`. A script can
also add recipes while the server runs with `nf.recipes.register`, in the
same shape as the file.

### Crafting of your own: station recipes

A `station` recipe is one Minecraft never sees. It's for a crafting menu of
your own: a list of what a player can make, from what's in their inventory,
near the blocks it needs (a workbench of your own, an anvil):

```json
{
  "type": "station",
  "materials": [
    { "ingredient": { "item": "gel" }, "count": 1 },
    { "ingredient": "#minecraft:planks", "count": 1 }
  ],
  "stations": ["work_bench"],
  "result": { "item": "torch", "count": 3 }
}
```

```lua
for _, recipe in ipairs(nf.recipes.available(player)) do
  -- show it in a menu…
end
nf.recipes.craft(player, "torch")   -- takes the materials, gives the result
```

## Loot tables

A loot table says what a roll gives: a chest's contents, a reward, a boss's
drops. Each is a file, `loot/<id>.json`, made of **pools**; each pool picks
some **entries** by weight:

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
    }
  }
}
```

An entry can also roll another of your tables (`"type": "table"`) or one of
Minecraft's (`"type": "vanilla"`, like `minecraft:chests/simple_dungeon`).
Conditions limit an entry or a pool to a chance, to rolls a player caused,
or to a tool or an enchantment.

Scripts roll them:

```lua
for _, item in ipairs(nf.loot.roll("treasure", { player = player, luck = 1 })) do
  player:give_item(item)
end

nf.loot.fill("treasure", block:inventory(), { location = block:location() })
```

The loot editor in the NetherForge editor can roll a table for you, to see
what it gives before you play.

## Buffs

A buff (or debuff) is something scripts put on a player or a centity for a
while: "Ironskin, +8 defence for a minute". Each is a file,
`buffs/<id>.json`, with its name, icon and **stats**:

```json
{
  "$schema": "../.netherforge/schema/buff.schema.json",
  "name": "<gray>Ironskin",
  "icon": "ui/buffs/ironskin",
  "stats": { "defense": 8 },
  "cures": ["frail"]
}
```

```lua
player:add_buff("ironskin", 20 * 60)        -- a minute
local defense = player:stat("defense")      -- every buff's defense, plus modifiers
```

A stat is just a name: NetherForge adds up what every buff gives it, and your
scripts decide what it means (take `defense` off damage, add `speed` to a
walk). Scripts can add their own numbers too, with `set_modifiers`. Buffs
don't last through a restart.

## Advancements

An advancement is one of the project's own quests, on the game's
advancements screen (<kbd>L</kbd>), with a toast and a chat line when it's
done. Each is a file, `advancements/<id>.json`; ones with a `parent` make a
tree:

```json
{
  "$schema": "../.netherforge/schema/advancement.schema.json",
  "parent": "adventurer",
  "display": {
    "icon": { "item": "ruby" },
    "title": "Treasure Hunter",
    "description": "Roll for treasure three times with /treasure",
    "frame": "goal"
  },
  "criteria": { "first": {}, "second": {}, "third": {} }
}
```

A criterion can be one of Minecraft's triggers (pick up a diamond), or left
empty (`{}`) so a script decides when it's met:

```lua
player:grant_advancement("treasure_hunter", "first")
nf.on("player_complete_advancement", function(event) end)
```

Advancements need a **server restart** to change: the game only reads them as
it starts. The editor restarts the dev server for you when you save one.
