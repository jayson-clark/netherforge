# Items

A project item is an item kind of the project's own: a ruby, a wand, a key
card. It lives at `items/<id>/item.json`, with its script beside it, and
everything that takes an item (menu slots, dialog bodies, particle emitters,
recipes, scripts) can name it by id.

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

The file holds what the item _is_: the fields an [item](menu.md#items) has
that describe its look and behaviour. What one stack holds (`count`, `damage`,
`map`, `data`) belongs to each stack, so it isn't here.

| Key                       | Meaning                                                                                                                                   |
| ------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| `kind`                    | The Minecraft item it's built on: `minecraft:paper`. Required.                                                                            |
| `name`                    | Display name, MiniMessage.                                                                                                                |
| `lore`                    | Lines under the name, each MiniMessage.                                                                                                   |
| `enchantments`            | Enchantment id → level.                                                                                                                   |
| `glint`                   | The enchanted shimmer without an enchantment.                                                                                             |
| `hideTooltip`             | No tooltip at all.                                                                                                                        |
| `unbreakable`             |                                                                                                                                           |
| `itemModel`               | `<pack>/<key>`: its look, from one of the project's [resource packs](resource-pack.md).                                                   |
| `tooltipStyle`            | `<pack>/<key>`: its tooltip frame, from one of the project's resource packs.                                                              |
| `equipment`               | `{ "asset", "slot" }`: worn as armour, drawn as a resource pack's [equipment look](resource-pack.md#equipment).                           |
| `color`                   | `#RRGGBB`, for dyed leather, potions and maps.                                                                                            |
| `profile`                 | Whose head, for `minecraft:player_head`.                                                                                                  |
| `maxStackSize`            | How many stack, 1–99.                                                                                                                     |
| `rarity`                  | `common`, `uncommon`, `rare` or `epic`.                                                                                                   |
| `attributeModifiers`      | What wearing or holding it changes.                                                                                                       |
| `canBreak`                | The blocks it can break in adventure mode: [the project's, the game's, tags, or `*`](menu.md#items).                                      |
| `canPlaceOn`              | The blocks it can be placed on in adventure mode, written as `canBreak`'s.                                                                |
| `hiddenTooltipComponents` | Tooltip lines to hide, by the component that draws them: [`unbreakable`, `attribute_modifiers`, …](menu.md#hiding-tooltip-lines).         |
| `food`                    | Eating it.                                                                                                                                |
| `cooldown`                | A cooldown after using it.                                                                                                                |
| `hold`                    | `true`, or `{ "animation": "trident" }` to pose the player: held down with no end, until the player lets go. [Animations](menu.md#items). |
| `useEffects`              | How a player moves while using it.                                                                                                        |
| `drawWithoutAmmo`         | A bow (`minecraft:bow`) that draws and shoots with no arrows, and uses none up. See below.                                                |
| `block`                   | `<id>`: a [block](block.md) of the project's that a right click with it places.                                                           |
| `script`                  | The item's one Lua script. See [`script`](#script).                                                                                       |
| `properties`              | The project's own data about the item, any JSON, for scripts. See [`properties`](#properties).                                            |

Each means what it means on any [item](menu.md#items), and is checked the
same way (the same problem codes, at the same paths), except `block`, which
only a project item has: a right click on a block with a stack of it places
that [block](block.md) (it names a block of the project, or of a package that
exports it; one that doesn't exist is `reference.block`) instead of using the
item, and the stack is built on whatever `kind` it's given, which needn't be a
block's.

## Drawing without ammo

A bow with `"drawWithoutAmmo": true` draws with no arrows in the inventory,
and fires one each time, using none up. Its arrow is the server's own: the
shot still goes through [`entity_shoot_bow`](../reference/events.md#on-nf), so a
script can cancel it (nothing is fired and the arrow is taken back), and a
project that wants its own ammo can cancel it and spend its own item instead.
The arrow can't be picked up again. It's the one behaviour, with no options.
Only `minecraft:bow` takes it (`item.draw-without-ammo` otherwise).

## Naming a project item

Anywhere an item goes, `"item"` names a project item instead of (or as well
as) a `kind`:

```json partial
"13": { "item": { "item": "ruby", "lore": ["<gray><glyph:ui/coin> 50"] } }
```

The stack is a ruby: the definition fills in every field the item leaves out
(here everything but `lore`), and its kind is always the definition's. A
`kind` given as well must be that kind (`item.kind-mismatch`); an item that
doesn't exist is an error (`reference.item`), and so is one with neither a
`kind` nor an `item` (`item.kind`).

A field the stack sets itself, like that `lore`, lasts until the definition
changes: then the stack takes the new look (see below). Keep what's a stack's
own in its `data`.

## The stack remembers

A stack made from a project item stays that item wherever it goes: in a
chest, dropped on the ground, after a restart. It's never mistaken for
another project's ruby, or for the plain item it's built on. Scripts read
which item it is as `item.item` (or `nf.items.id(item)`), and recipes match
it by that, never by its looks.

When the definition changes (you save `item.json`, or the server runs a newer
version of the project), stacks that already exist are brought up to date the
next time the server sees them: in a player's inventory when they join or the
item reloads, in an inventory when someone opens it, and in a player's
inventory just after they pick something up. A stack takes the definition's
fields as they are now, and keeps what's its own: its count, its damage, its
script `data`, whatever other plugins stored on it, and its enchantments when
the definition sets none. A stack whose item no longer exists is left as it
is.

A name or lore worked out from one stack's own `data` (a prefix, a stat
rolled for it) is the item script's `render` event's to give: it's heard for
every stack `nf.items.create` and a loot roll make, and again whenever a stack
takes the definition's new fields, so it survives the file changing. A name
a script sets on a stack any other way goes back to the definition's then.

### Looking different to one player

A name and lore that depend on who's looking (a price while they have a shop
open, a hint for their class) are the item script's `view` event's to give.
It's heard each time a stack of the item is sent to one player's game (their
inventory, a window they have open, the stack on their cursor), and what its
handlers assign to `event.display_name` and `event.lore` is what that player is
shown. Nothing the server keeps changes: the stack itself, how it stacks, what's
saved, what anyone else sees, and what scripts read, which is always the real
stack. Both fields start as the stack's own, after `render`, so `view` adds to
what `render` worked out.

A stack is only shown again when it changes, so when what a handler looks at
changes (a menu opening or closing), call `player:refresh_items()`: every
stack they can see is worked out again. `nf.on("item_view")`
hears every stack, the project's or not, after its item's own handlers. See
[Prices on a player's own items](../guide/menus.md#prices-on-a-player-s-own-items)
for an example.

## `properties`

```json partial
"properties": { "damage": 12, "class": "melee", "tags": ["blade"] }
```

The project's own facts about this item, as any JSON: what scripts read with
`nf.items.get(id):properties()` (or `this:properties()` in its script), so data that belongs to it lives in its file instead of
a table a script keeps in step. Nothing but scripts reads it, and changing it
doesn't change its stacks' look, so they're never rewritten for it.

## `script`

```json partial
"script": { "file": "script.lua", "budget": 200000 }
```

| Key      | Meaning                                                                | Default |
| -------- | ---------------------------------------------------------------------- | ------- |
| `file`   | A `.lua` file in this item's folder.                                   | —       |
| `budget` | Lua instructions one call into the script may use before it's stopped. | 200000  |

The script runs once for the item, not once per stack, from load until the
item is reloaded, like a dialog's. In it, `this` is the item (a
[`ProjectItem`](../reference/projectitem.md)), and it listens for what players do
with any stack of it: `this:on("use", ...)`, `"interact"`,
`"interact_entity"`, `"consume"`, `"hit"`, `"break_block"`, `"drop"` and
`"pickup"`. Each is the player event of the same name heard first by the
item, with the same event object, so `event:cancel()` here cancels it for
everyone. Two more are about how its stacks look: `"render"`, as a stack is
made ([The stack remembers](#the-stack-remembers)), and `"view"`, as a stack
is sent to one player ([Looking different to one player](#looking-different-to-one-player)).

### Files beside the script

Any other `.lua` files in the item's folder, at any depth, belong to the item.
The script loads them with `require`, which looks in the script's folder
before it looks for a module: `require("effects")` is `effects.lua` (or
`effects/init.lua`). Such a file runs as part of the script, once: its globals
are the script's and `this` is the item. Saving, adding or deleting one
reloads the item, as saving the script does. Name them as module files are
named, with letters, digits and `_`, or `require` can't reach them
(`script.file-name`, a warning). See
[`require`](../guide/scripting.md#require).
