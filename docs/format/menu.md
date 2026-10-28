# Menus

A menu is a window you can put in front of a player: a shop, a class picker, a
warp menu, a chest with rules. It lives at `menus/<id>/menu.json`, with its
script beside it.

```json
{
  "$schema": "../../.netherforge/schema/menu.schema.json",
  "name": "Village shop",
  "rows": 3,
  "title": "<dark_gray>Village shop",
  "skin": "ui/shop",
  "slots": {
    "11": { "item": { "kind": "minecraft:bread", "count": 4, "name": "<yellow>Bread" } },
    "13": {
      "item": { "kind": "minecraft:paper", "itemModel": "ui/ruby", "data": { "ruby": true } }
    }
  },
  "script": { "file": "script.lua" }
}
```

| Key      | Meaning                                                                                                 | Default |
| -------- | ------------------------------------------------------------------------------------------------------- | ------- |
| `name`   | Display name for people.                                                                                |         |
| `type`   | `chest`, `barrel`, `shulker_box`, `hopper`, `dispenser`, `dropper` or `crafter`. See below.             | `chest` |
| `rows`   | Rows of nine, 1–6. Only for a chest; every other type has a fixed size.                                 | 3       |
| `title`  | MiniMessage. With a `skin`, it's drawn over the artwork.                                                |         |
| `skin`   | `<pack>/<key>`: a background from one of the project's [resource packs](resource-pack.md).              |         |
| `shared` | One window for the whole server (a shop's stock) instead of one per player who opens it.                | false   |
| `locked` | Clicks never move items; scripts hear them instead. Turn it off for real storage.                       | true    |
| `slots`  | What starts in each square, keyed by slot index (`"0"` is top left, counting left to right, then down). |         |
| `script` | The menu's one Lua script. See [`script`](#script).                                                     |         |

Only container types whose every slot is plain storage are allowed. A
furnace has slots the server has opinions about (fuel, result), and a menu
built on one fights vanilla behaviour in half its squares.

A hopper is one row of five slots; a dispenser, dropper or crafter three rows
of three. Those three centre their title on its words, which would move a
`skin` along with them, so the server moves the skin back by measuring the
title with the project's [default font advances](project.md#fonts-default-json) (`fonts/default.json`,
written by the editor). A skinned one with title words and no current
`fonts/default.json` is a warning (`font.needed`): the skin will be off by
half the title's width.

## Slots

```json partial
"13": { "item": { "kind": "minecraft:paper", "itemModel": "ui/ruby" } }
```

A slot is just the `item` that starts in it. Keys are slot indexes: a key that
isn't a number is an error (`menu.slot-key`), and so is one past the end of the
window (`menu.slot-range`). A slot with no item gets a warning
(`menu.slot-empty`): leave it out instead.

A slot has no script of its own. The menu's script handles a slot's clicks with
`this:slot(13):on("click", ...)`.

## `script`

```json partial
"script": { "file": "script.lua", "budget": 200000 }
```

| Key      | Meaning                                                                | Default |
| -------- | ---------------------------------------------------------------------- | ------- |
| `file`   | A `.lua` file in this menu's folder.                                   | —       |
| `budget` | Lua instructions one call into the script may use before it's stopped. | 200000  |

Every window gets its own copy of the script, started when the window is made:
when it's opened for someone, or at load for a `shared` menu. In it, `this` is
the window (a [`Menu`](../reference/menu.md)). See the
[Menus guide](../guide/menus.md).

### Files beside the script

Any other `.lua` files in the menu's folder, at any depth, belong to the menu.
The script loads them with `require`, which looks in the script's folder
before it looks for a module: `require("prices")` is `prices.lua` (or
`prices/init.lua`). Such a file runs as part of the script: its globals are
the script's, `this` is the same window, and every window runs its own copy,
once. Saving, adding or deleting one reloads the menu, as saving the script
does. Name them as module files are named, with letters, digits and `_`, or
`require` can't reach them (`script.file-name`, a warning). See
[`require`](../guide/scripting.md#require).

## Items

Items are the same everywhere in a project (menu slots, dialog bodies, particle
emitters, and scripts that build them). Names and lore can show a resource pack glyph
with `<glyph:<pack>/<key>>` (see [Glyphs](resource-pack.md#glyphs)):
`"lore": ["<gray><glyph:ui/coin> 2"]`.

| Key                       | Meaning                                                                                                                                                                      |
| ------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `kind`                    | The item type: `minecraft:diamond_sword`, or `diamond_sword`. Required, unless `item` is given.                                                                              |
| `item`                    | A [project item](item.md)'s id: the stack is one, and takes what it leaves out.                                                                                              |
| `count`                   | 1–99.                                                                                                                                                                        |
| `name`                    | Display name, MiniMessage.                                                                                                                                                   |
| `lore`                    | Lines under the name, each MiniMessage.                                                                                                                                      |
| `enchantments`            | Enchantment id → level: `{ "minecraft:sharpness": 5 }`.                                                                                                                      |
| `glint`                   | The enchanted shimmer without an enchantment; menus use it for "selected".                                                                                                   |
| `hideTooltip`             | Hide the tooltip entirely, for decorative filler panes.                                                                                                                      |
| `unbreakable`             |                                                                                                                                                                              |
| `damage`                  | Durability used: 0 is pristine.                                                                                                                                              |
| `itemModel`               | `<pack>/<key>`: a custom look from a resource pack.                                                                                                                          |
| `tooltipStyle`            | `<pack>/<key>`: a custom tooltip frame from a resource pack.                                                                                                                 |
| `equipment`               | `{ "asset": "<pack>/<key>", "slot" }`: worn in `head`, `chest`, `legs`, `feet`, `body` or `saddle`, drawn as a resource pack's [equipment look](resource-pack.md#equipment). |
| `color`                   | `#RRGGBB`, for dyed leather, potions and maps.                                                                                                                               |
| `profile`                 | Whose head, for `minecraft:player_head`: a name or a UUID.                                                                                                                   |
| `maxStackSize`            | How many stack, 1–99. More than 1 can't go with durability.                                                                                                                  |
| `map`                     | The map a `minecraft:filled_map` shows, by its number: a [screen](screen.md)'s (`screen:item`), or the game's.                                                               |
| `rarity`                  | `common`, `uncommon`, `rare` or `epic`: the colour of its name.                                                                                                              |
| `attributeModifiers`      | What wearing or holding it changes. See below.                                                                                                                               |
| `canBreak`                | The blocks it can break in adventure mode. See below.                                                                                                                        |
| `canPlaceOn`              | The blocks it can be placed on in adventure mode. See below.                                                                                                                 |
| `hiddenTooltipComponents` | Tooltip lines to hide, by the component that draws them: `["unbreakable", "attribute_modifiers"]`. See below.                                                                |
| `food`                    | `{ "nutrition", "saturation", "canAlwaysEat"?, "eatSeconds"? }`. See below.                                                                                                  |
| `cooldown`                | `{ "seconds", "group"? }`: a cooldown after using it.                                                                                                                        |
| `hold`                    | `true`, or `{ "animation" }`: held down with no end, until the player lets go. See below.                                                                                    |
| `useEffects`              | `{ "speedMultiplier"?, "canSprint"?, "interactVibrations"? }`: how a player moves while using it. See below.                                                                 |
| `data`                    | Your scripts' own data, a JSON object saved on the stack.                                                                                                                    |

A `kind` the game doesn't have is an error (`item.kind`), and so is an
enchantment (`item.unknown-enchantment`) or an attribute
(`item.unknown-attribute`) it doesn't have, once the editor has the game's
data (or on the server, which always does). A script handing over such an item
gets the same error at its line.

`attributeModifiers` is a list of
`{ "id"?, "attribute", "amount", "operation", "slot"? }`. `attribute` is an
attribute id (`minecraft:attack_damage`, `minecraft:movement_speed`, …);
`operation` is vanilla's `add_value`, `add_multiplied_base` or
`add_multiplied_total`; `slot` is where the item has to be for it to count
(`any`, the default, `hand`, `main_hand`, `off_hand`, `armor`, `head`, `chest`,
`legs`, `feet`, `body`, `saddle`). Minecraft tells modifiers apart by `id`,
which defaults to `<namespace>:<attribute>_<index>` (`shop:attack_damage_0` in a project whose namespace is `shop`);
two with the same id are an error (`item.attribute-id`). The list replaces the
kind's own modifiers (a sword's damage) rather than adding to them.

`canBreak` and `canPlaceOn` are what an adventure-mode player may do with the
item, the tool system of an adventure server. Each is a list of:

- **the project's blocks** by id (`ruby_ore`), a package's as `ns:id`: the item
  breaks every placed and generated one, whatever state the server holds it in
  (it follows the blocks as the project's change: a stack names the state its
  blocks were in, and is rewritten when the player joins or opens an
  inventory after they move);
- **the game's blocks**, with their namespace (`minecraft:stone`), and
  **`#tags`** of them (`#minecraft:logs`; a bare `#logs` is the game's);
- **a tag a project datapack defines**, as `#<namespace>:<path>`
  (`#showcase:soft`, in a [datapack's](datapack.md) `data/showcase/tags/block/soft.json`);
- **`*`**: any block. `"canPlaceOn": ["*"]` lets the item be placed
  anywhere.

```json partial
{
  "kind": "minecraft:iron_pickaxe",
  "canBreak": ["ruby_ore", "#minecraft:logs"],
  "canPlaceOn": ["*"]
}
```

A plain id is always one of the project's blocks (`reference.block` says how
the game's are written when there's none: `stone` is `minecraft:stone`), a
game block or tag the target version doesn't have is `item.unknown-block`, one
that isn't shaped like an id is `item.block`, and a project datapack's tag no
datapack defines is `reference.block`. A project block's state is what's
matched, and a tag only holds the game's blocks: a tag can't name a project
block, and `minecraft:note_block` in one matches every project block held in a
note block too.

### Hiding tooltip lines

`hiddenTooltipComponents` hides lines of the item's tooltip and keeps the
rest, where `hideTooltip` hides all of it, name and lore too. Each entry names
the data component that draws a line, by Minecraft's id without
`minecraft:`. Hiding a line changes only the tooltip: the component still
does what it does. An item that hides `unbreakable` is still unbreakable, one
that hides `attribute_modifiers` keeps its modifiers, and one that hides
`can_break` still breaks only those blocks.

```json partial
{
  "kind": "minecraft:iron_pickaxe",
  "name": "<gold>Ruby Pick",
  "lore": ["<gray>Mines faster", "<gray>Never breaks"],
  "unbreakable": true,
  "attributeModifiers": [
    {
      "attribute": "minecraft:block_break_speed",
      "amount": 0.5,
      "operation": "add_multiplied_base"
    }
  ],
  "hiddenTooltipComponents": ["unbreakable", "attribute_modifiers"]
}
```

These are the lines Minecraft draws, and so the values allowed:

| Value                      | The line                                                             |
| -------------------------- | -------------------------------------------------------------------- |
| `attribute_modifiers`      | "When in Main Hand:" and the modifiers under it ("+2 Attack Damage") |
| `banner_patterns`          | A banner's patterns                                                  |
| `bees`                     | A beehive's or bee nest's bees                                       |
| `block_entity_data`        | A spawner's or trial spawner's mob                                   |
| `block_state`              | A beehive's or bee nest's honey level                                |
| `bundle_contents`          | A bundle's contents and how full it is                               |
| `can_break`                | "Can break:" and its blocks                                          |
| `can_place_on`             | "Can be placed on:" and its blocks                                   |
| `charged_projectiles`      | A crossbow's loaded projectile                                       |
| `container`                | A shulker box's or other container's items                           |
| `container_loot`           | "???????" on a container with a loot table                           |
| `damage`                   | "Durability: x / y", in advanced tooltips (F3+H)                     |
| `dyed_color`               | "Dyed"                                                               |
| `enchantments`             | Its enchantments ("Sharpness V")                                     |
| `entity_data`              | A spawn egg's note that its mob can't spawn in peaceful              |
| `firework_explosion`       | A firework star's shape and colours                                  |
| `fireworks`                | A firework rocket's flight duration and explosions                   |
| `instrument`               | A goat horn's sound                                                  |
| `intangible_projectile`    | "Can't be picked up", on an arrow                                    |
| `jukebox_playable`         | A music disc's song                                                  |
| `lore`                     | The item's lore                                                      |
| `map_id`                   | A map's id ("Id #3"), or "Unknown Map"                               |
| `ominous_bottle_amplifier` | An ominous bottle's Bad Omen level                                   |
| `painting/variant`         | A painting's title, artist and size                                  |
| `pot_decorations`          | A decorated pot's sherds                                             |
| `potion_contents`          | A potion's effects                                                   |
| `profile`                  | A player head's owner                                                |
| `sign_text_back`           | A sign's back text                                                   |
| `sign_text_front`          | A sign's front text                                                  |
| `stored_enchantments`      | An enchanted book's enchantments                                     |
| `sulfur_cube_content`      | What a sulfur cube holds (only versions that have sulfur cubes)      |
| `suspicious_stew_effects`  | A suspicious stew's effect, in creative                              |
| `trim`                     | "Upgrade:" and an armour trim's pattern and material                 |
| `tropical_fish/pattern`    | A tropical fish bucket's fish                                        |
| `unbreakable`              | "Unbreakable"                                                        |
| `written_book_content`     | A written book's author and generation                               |

Leaving the key out shows the tooltip as it is, and `[]` shows every line. A
value that isn't one of these is `item.tooltip-component` (a near miss like
`painting_variant` is told the spelling), and one that the target version's
Minecraft doesn't have is `item.unknown-tooltip-component`. A script's
`hidden_tooltip_components` takes the same values.

`food` makes any item edible, not just food: it also gives the item
Minecraft's `consumable` component, eaten over `eatSeconds` (1.6 by default).
`cooldown`'s `seconds` must be more than 0; items with the same `group` (a
namespaced id) share a cooldown, and without one the item's kind is its group.
`hold` makes a right click a use that lasts until the player lets go: it never
finishes and nothing is used up, and letting go is
[`player_stop_using_item`](../reference/events.md), whose `event.ticks` says
how long it was held. That's how a script hears a right click being released:
a flail thrown on letting go, a charge that grows while it's held. It's
Minecraft's `consumable` component with no end, so it can't go with `food`
(`item.hold-food`), and it only works on a kind with no use of its own:
`minecraft:paper` or a stick, not a bow, a shield or a potion, whose own use
comes first. While it's held, left clicks don't attack (the game ignores them
during any use).

`"hold": true` draws nothing: no zoom, no arm pose. To pose the player while
it's held, give it the animation of one of the game's uses instead:

```json partial
"hold": { "animation": "trident" }
```

`animation` is the `consumable` component's, by the game's own name, and
defaults to `none`. Everything else is the same as `true`, `useEffects`'
defaults included: a posed hold doesn't slow the player either. Written back,
`{ "animation": "none" }` and `{}` read as `true`. A name that isn't one of
these is `item.hold-animation` (a near miss like `tridnet` says which was
meant). Every supported Minecraft version (1.21.11 to 26.3) has the same ones:

| `animation` | First person                                                                 | Third person                                                     |
| ----------- | ---------------------------------------------------------------------------- | ---------------------------------------------------------------- |
| `none`      | Held as it is.                                                               | Held as it is.                                                   |
| `trident`   | Raised over the shoulder, drawn back further for half a second, then shakes. | One arm raised over the head, the trident wind-up.               |
| `bow`       | Turned and pulled back for a second, then shakes at full draw.               | Both arms raised and aimed where the player looks.               |
| `crossbow`  | Held as it is.                                                               | Both arms, the near one pulling back: the crossbow being loaded. |
| `block`     | Turned up across the body, as a sword blocks.                                | One arm across the body, as a shield is raised.                  |
| `spyglass`  | Held as it is.                                                               | One arm raised to the eye.                                       |
| `toot_horn` | Held as it is.                                                               | One arm raised to the mouth.                                     |
| `brush`     | Swept back and forth.                                                        | The arm lowered and forward, brushing.                           |
| `spear`     | Held low and out in front, still.                                            | The arm forward and low, couched like a lance.                   |
| `eat`       | Held as it is.                                                               | Held as it is.                                                   |
| `drink`     | Held as it is.                                                               | Held as it is.                                                   |
| `bundle`    | Held as it is (it moves only when the player swings).                        | Held as it is.                                                   |

The trident's wind-up is `trident`. `spear` is the 1.21.11 spear's pose, and
the motion a real spear makes while charging comes from its
`kinetic_weapon` component, which a hold doesn't have, so a held `spear` stays
still. `eat` and `drink` move the item to the mouth over the use's length,
which for a hold is a day, so they look like `none`; `crossbow`, `spyglass` and
`toot_horn` have no first-person pose of their own except on the real items.

The zoom isn't part of the animation. The game narrows the view for a drawn
`minecraft:bow` and a raised `minecraft:spyglass` by the item's kind, and only
the real spyglass hides the hand and shows the scope overlay, so a held stick
with `bow` or `spyglass` poses without zooming. Nothing on the server changes
either: the animation only picks the pose the clients draw. A held item never
throws, fires an arrow, needs ammo or riptides, whatever its animation, since
those are the trident's, the bow's and the crossbow's own uses (their kinds,
which a hold can't go on), not their animations'. A resource pack's
[draw stages](resource-pack.md#draw-stages) work with any animation: they
follow how long the item has been used, which the animation doesn't change.

`useEffects` is how a player moves while using the item, whatever the use: a
hold, food, a bow, a shield. `speedMultiplier` (0 to 1, `item.use-effects`
otherwise) is their speed, `canSprint` whether they keep sprinting, and
`interactVibrations` whether starting to use it sets off sculk sensors. Left
out, a field is the game's (0.2, `false`, `true`), or on a `hold` item full
speed, sprinting and silent to sculk (1, `true`, `false`): a hold doesn't slow
the player unless `useEffects` says so.
`maxStackSize` above 1 on an item that takes damage (`damage` set, or a kind
with durability, which the server knows) is an error
(`item.max-stack-size-durable`), as Minecraft refuses it.

`data` is saved on the stack itself, so it goes
wherever the item goes. Tag a coin with `"data": { "coin": true }`. Scripts read
and write it as `item.data`; see [Item](../reference/types.md#item).
