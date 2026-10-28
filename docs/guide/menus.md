# Menus

A menu is a window you put in front of a player: a shop, a class picker, a warp
menu, a chest with rules. It lives in `menus/<id>/`, as a `menu.json` with its
one script beside it. The [menu format](../format/menu.md) has every key.

```
menus/
  shop/
    menu.json
    script.lua       the menu's script: every click in the window, and each slot's
```

## The window

```json
{
  "$schema": "../../.netherforge/schema/menu.schema.json",
  "name": "Village shop",
  "rows": 3,
  "title": "<dark_gray>Village shop",
  "skin": "ui/shop",
  "slots": {
    "11": {
      "item": {
        "kind": "minecraft:bread",
        "count": 4,
        "name": "<yellow>Bread",
        "lore": ["<gray><glyph:ui/coin> 2"]
      }
    },
    "13": {
      "item": {
        "kind": "minecraft:paper",
        "name": "<red>Ruby",
        "lore": ["<gray><glyph:ui/coin> 50"],
        "itemModel": "ui/ruby"
      }
    },
    "15": { "item": { "kind": "minecraft:barrier", "name": "<red>Close" } }
  },
  "script": { "file": "script.lua" }
}
```

- **`type`**: `chest` (the default, 1–6 `rows` of nine), `barrel`,
  `shulker_box`, `hopper`, `dispenser`, `dropper` or `crafter`. Only
  containers whose every slot is plain storage are offered: a furnace's fuel
  and result slots fight a menu.
- **`title`** is MiniMessage. With no `title` and no `skin`, the window shows
  the menu's `name`.
- **`slots`** are keyed by index: `"0"` is the top left, counting left to
  right, then down. A three-row chest has slots 0–26. A slot is just the item
  that starts in it.

## Shared or per player

By default every player who opens a menu gets **their own** window: one
player's view shouldn't change another's. Set `"shared": true` for **one**
window for the whole server, such as a shop's stock or a community chest.

## Locked or not

By default a menu is **locked**: clicks never move items, and scripts hear
them instead. That's what a shop or a picker wants. Set `"locked": false` for
real storage, where players move items in and out.

## Items

Items are written the same way everywhere in a project (slots, dialog bodies,
particle emitters):

```json
{
  "kind": "minecraft:diamond_sword",
  "name": "<aqua>Frostbite",
  "lore": ["<gray>Cold to the touch"],
  "enchantments": { "minecraft:sharpness": 5 },
  "unbreakable": true,
  "data": { "frost": 3 }
}
```

Besides `kind`, `count`, `name` and `lore`, an item can have a `glint` (the
enchanted shimmer, handy for "selected"), `hideTooltip` (for decorative filler
panes), `damage`, `color` (`#RRGGBB` for leather, potions, maps), `profile`
(whose head, for player heads) and `data`: your scripts' own data, saved on the
stack so it goes wherever the item goes. See [Items](../format/menu.md#items).

To hide only some of the tooltip, so your lore can say it better,
`hiddenTooltipComponents` names the lines to leave out:
`["unbreakable", "attribute_modifiers", "can_break"]` hides "Unbreakable", the
attribute lines and "Can break" but keeps the name and lore. The item is still
unbreakable and still has its modifiers and blocks; only the tooltip changes.
[Hiding tooltip lines](../format/menu.md#hiding-tooltip-lines) lists every line
you can hide.

## Custom looks

With a [resource pack](resource-packs.md) in the project:

- `"skin": "<pack>/<key>"` draws your own background art behind the window,
  with the title's words written over it where vanilla puts the title;
- an item's `"itemModel": "<pack>/<key>"` gives that one stack a custom
  picture without replacing the vanilla item;
- an item's `"tooltipStyle": "<pack>/<key>"` gives its tooltip a custom frame;
- `<glyph:<pack>/<key>>` in any name, lore line or title puts a small picture
  in the text, like the coin in `"<gray><glyph:ui/coin> 50"`.

A dispenser, dropper or crafter centres its title on the title's words, which
would drag a skin along with them. The server puts the skin back where it
belongs by measuring the words with the default font's advances in
`fonts/default.json`, which the editor writes once Minecraft is imported.
Without that file (or for characters it doesn't cover) the skin moves with the
title's length, and the project shows a `font.needed` warning. A skin with no
title words never moves.

Items can carry more than a name and lore: how many stack (`max_stack_size`),
a `rarity` (the name's colour), `attribute_modifiers` (what wearing or holding
it changes), `can_break` and `can_place_on` (adventure mode), `food` (any item
can be made edible) and a `cooldown` after use:

```json
{
  "kind": "minecraft:paper",
  "name": "<gold>Travel bread",
  "rarity": "rare",
  "maxStackSize": 16,
  "food": { "nutrition": 6, "saturation": 4, "eatSeconds": 0.8 },
  "cooldown": { "seconds": 5, "group": "shop:snacks" },
  "attributeModifiers": [
    {
      "attribute": "minecraft:movement_speed",
      "amount": 0.1,
      "operation": "add_multiplied_total",
      "slot": "off_hand"
    }
  ]
}
```

See [Items](../format/menu.md#items) for every field.

## Opening one

From any script, open a menu for a player by its id:

```lua
nf.commands.register("shop", { players_only = true }, function(event)
  event.player:open_menu("shop")
end)
```

For a per-player menu, `player:open_menu` makes a new window filled
from the file. For a `shared` one, it opens the one window everybody shares,
which you can also reach at any time with `nf.menus.shared("bank")`.
`player:close_menu()` closes the menu they have open.

A new window can be handed anything you like as its `context`: the item being
bought, the centity that was clicked, a callback. Its script reads it with
`this:context()`, and every event on the window carries it as `event.context`.
It's the very value you passed, kept on the server, never a copy:

```lua
-- modules/greeter/init.lua
event.player:open_menu("shop", { context = { nickname = "Lex" } })

-- menus/shop/script.lua
local context = this:context() or {}
this:on("open", function(event)
  if context.nickname then
    event.player:send_message("<gold>Welcome to the shop, " .. nf.text.escape(context.nickname) .. "!")
  end
end)
```

A shared menu has one window for everyone, so opening it with a context is an
error.

## The script

A menu has one script, named by the top-level `script` in `menu.json`. Slots
have none: the script handles every slot, and the window around them. (A long
script can `require` more files beside it, which run as part of it: see
[`require`](scripting.md#require).) In it,
`this` is the window (a [Menu](../reference/menu.md)), and `this:slot(n)` is
one of its slots (a [Slot](../reference/slot.md)):

```lua
-- menus/shop/script.lua
local this = this --[[@as Menu]]

-- The ruby's slot hears its clicks first.
this:slot(13):on("click", function(event)
  event.player:send_message("<red>Rubies are sold out.")
  event:cancel() -- the ruby stays put
  event:stop() -- and the window's handler below doesn't hear it
end)

-- Every other click in the window.
this:on("click", function(event)
  if event.in_menu and event.index == 15 then
    event.player:close_menu()
    event:cancel()
  end
end)
```

The first line tells lua-language-server which `this` it is (see
[External editors](external-editors.md#lua-luals-stubs)). The editor writes
it into every script it creates.

**Every window gets its own copy of the script**, with its own globals,
started when the window is made: when it's opened for someone, or when the
project loads for a `shared` one. So a top-level `local` in a per-player
menu's script is that player's state for as long as the window is open.

The script's body runs when the window is made, before anyone sees it: the
place to fill it in (and again after a reload). Then it listens on the window
and its slots:

| Event   | When                                                                                                                                                                                                                                                                                       |
| ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `open`  | Someone opened this window. Then `nf.on("menu_open")` hears it.                                                                                                                                                                                                                            |
| `click` | Someone clicked while it was open. A slot's `click` handlers hear clicks on it first, then the window's, then (for a window made from a script's `nf.menus.create` template) the template's, then `nf.on("menu_click")`. `event:cancel()` cancels the click; `event:stop()` ends it there. |
| `drag`  | Someone dragged a stack across its slots.                                                                                                                                                                                                                                                  |
| `close` | Someone closed it, or left with it open. When the last viewer of a per-player window leaves, the window goes, and its handlers with it.                                                                                                                                                    |

`nf.on("unload", handler)` hears the script stopping: its window went, the
menu reloaded, or the server is stopping. A **locked** window starts every
click that would move items in or out already cancelled; a handler can let one
through with `event:uncancel()`. What a handler returns means nothing.

The click event says who clicked (`player`), where (`index`, and `in_menu`:
`true` in the window, `false` in the player's own inventory below it; `target`
is the window's [Slot](../reference/slot.md), or `nil` below), how (`click`:
`left`, `right`, `shift_left`, `number_key`, …) and what was there (`item`,
`cursor_item`). `hotbar_index` is the hotbar slot for a `number_key` click. See [MenuClickEvent](../reference/types.md#menuclickevent).

Behaviour several menus share (a price list, a "back" button) goes in a
[module](modules.md) that each script requires.

## Changing what's in it

Scripts read and write items as plain tables with the same fields as the
file, in `snake_case` (`item_model`, `tooltip_style`, `hide_tooltip`):

```lua
-- A menu that shows each player their own balance.
this:on("open", function(event)
  local coins = load_coins(event.player) -- your own function
  this:set_item(4, {
    kind = "minecraft:gold_nugget",
    name = "<gold>" .. coins .. " coins",
  })
end)
```

```lua
-- Selling one loaf: read a slot, change it, write it back.
local bread = this:item(11)
if bread and bread.count > 1 then
  bread.count = bread.count - 1
  this:set_item(11, bread)
end
```

```lua
-- Tag what you hand out, and recognise it later wherever it ends up.
this:slot(13):set_item({ kind = "minecraft:gold_nugget", name = "<gold>Coin", data = { coin = true } })

this:on("click", function(event)
  if event.item and event.item.data and event.item.data.coin then
    event.player:send_message("<gold>That's a coin.")
  end
end)
```

Reading an item, changing a field and writing it back keeps everything else
about the stack, including what these fields can't describe: that rides along
in `raw`, which you never write yourself. `data` is your own: an empty
`data = {}` clears the stack's data, and leaving `data` out leaves it alone.
Something a `data()` table couldn't keep in `data` (a function, a `Menu` window) is an error. `add_item` puts
an item wherever it fits, `items()` lists everything by slot, `clear()` empties
every slot (`set_item(index, nil)` empties one), `set_items({ [11] = a, [15] = b })`
writes several at once (all checked before any changes), `fill(item, indices?)`
fills every empty slot (or the listed ones), `slot(index)` hands out one slot
as a handle, `rows()` says how many rows it has, `cursor_item(player)` and
`set_cursor_item(player, item)` read and change what a viewer carries, and
`viewers()` lists who's looking. The [Menu reference](../reference/menu.md)
has the full list, including `set_title`, `set_skin` and `set_locked`.

Changes a script makes are never written into the project: a per-player
window starts from the file every time it's opened, and a shared one starts
from the file when the project loads.
Keep anything that must last (a shop's stock, a bank) in a [saved table](scripting.md#saving-data), like `nf.data("shop")`.

## Menus made in a script

A menu that depends on what's happening (a confirmation, a picker of
whatever's for sale today) can be made in Lua instead of a file.
`nf.menus.create(definition)` takes what `menu.json` would say, in Lua
spelling, and returns a `MenuTemplate`:

```lua
-- modules/reset/init.lua
local confirm = nf.menus.create({
  rows = 1,
  title = "<red>Reset your island?",
  slots = {
    [3] = { kind = "minecraft:lime_wool", name = "<green>Yes" },
    [5] = { kind = "minecraft:red_wool", name = "<red>No" },
  },
})

confirm:on("click", function(event)
  if event.index == 3 then
    event.player:send_message("<green>Done.")
  end
  event.player:close_menu()
end)

nf.commands.register("reset", { players_only = true }, function(event)
  event.player:open_menu(confirm)
end)
```

The definition takes `type`, `rows`, `title`, `skin`, `locked` (true unless
you say otherwise) and `slots`, items by slot number from 0, and is checked by
the same rules as a file: a misspelled key, a slot outside the window, an item
the server doesn't have or a skin your resource packs don't have is an error.

`player:open_menu(template, options?)` opens a new window of it each time,
never shared, with a `context` if you like. A template has no script of its
own: handlers on the template (`open`, `close`, `click`, `drag`) hear every
window made from it, after the window's own handlers and before `nf.on`. A
window knows where it came from (`menu:template()`; its `kind()` is `nil`, as it
has no folder), and `template:windows()` lists the ones open.

A template lasts as long as the script that made it: when that script
unloads, or you call `template:remove()`, its windows close and its handlers
go. See the [MenuTemplate reference](../reference/menutemplate.md).

## Prices on a player's own items

A shop often wants to say what the player's own items would sell for, but only
while the shop is open. Don't write that into their items' lore: give the
item a `view` handler, which changes how a stack looks to one player without
changing the stack. Their real items keep stacking with new ones, and nothing
is left behind if the server stops with the shop open.

```lua
-- items/ruby/script.lua
local this = this --[[@as ProjectItem]]

this:on("view", function(event)
  local menu = event.player:menu()
  if menu and menu:kind() == "shop" then
    local lore = event.lore or {}
    lore[#lore + 1] = "<gold>Sells for 5 coins"
    event.lore = lore
  end
end)
```

```lua
-- menus/shop/script.lua
local this = this --[[@as Menu]]

this:on("open", function(event)
  event.player:refresh_items()
end)

this:on("close", function(event)
  event.player:refresh_items()
end)
```

The game is only sent a stack again when the stack changes, so the menu's
`open` and `close` handlers call `player:refresh_items()`: at the end of the
tick, the handlers work out everything the player can see again (their
inventory, the window, their cursor), and only the stacks that now look
different are sent. Anything that reads the stacks, `player:inventory()`, a
click's `event.item`, `event.item` in the handler itself, gets the real stack,
and a stack a creative-mode player's game sends back comes back as the real one.
To price items that aren't the project's, or every item at once, listen with
`nf.on("item_view", ...)` instead; while it has a handler, every stack sent to
every player goes through it, so keep it quick.

## Items a player keeps

A menu's window holds its items only while it's open. For items a player keeps
(extra equipment slots, a backpack, a bank), give them a **storage** and show
it in a menu:

```lua
-- menus/trinkets/menu.json: { "rows": 3, "locked": true, "script": { "file": "script.lua" } }
this:on("open", function(event)
  local trinkets = event.player:storage("trinkets", 4)
  for i = 0, 3 do
    this:slot(11 + i):bind(trinkets, i)
  end
end)
```

`player:storage(name, size)` is an `Inventory` (kind `"storage"`) saved in the
player's own save data, beside their inventory: the server writes both in the
same file at the same moment. `slot:bind(storage, index)` makes a window's
slot a view of one of its slots. Whatever goes in or out of a bound slot, by a
click, a drag, a shift-click or a script, is written to the storage as the game
moves it. So an item is in the storage or out of it, never in the window alone,
and nothing is duplicated or lost by closing the window, leaving, dying, a
reload or the server crashing.

- In a locked window, players can still use a bound slot by hand.
  Double-click gathering stays locked, since it reaches the window's other
  slots. A shift-click from the player's own inventory is locked too, unless
  the window says where it goes with `menu:shift_into(storage, first?, last?)`:
  then it fills the bound slots of that storage, first slot first, and nothing
  else. To accept only some items, give the slot a rule (below), or cancel
  its `click` (`event.item` is the stack going in).
- Only a window of its own can be bound, not a `shared` one.
- Scripts read and change a storage like any inventory: `item`, `set_item`,
  `add_item`, `remove_item`, `has_item`. A slot showing it updates at once.
- Changing the size keeps every item: slots past the size are hidden, not
  emptied.
- A storage can only be read or changed while its player is online.
- `player:data()` lives in NetherForge's own database, saved apart from the
  inventory. Keep items in a storage, not in `data()`, or a crash between the
  two saves can duplicate them.

A window shown to two players (`menu:open_for`) can move one player's items
into another's inventory, and two players' files are saved separately: keep
bound windows for their own player.

### What a slot takes, and what a player wears

`slot:set_rule(rule)` says what a slot takes: project items by id (`items`),
the game's by id or `#tag` (`kinds`), project items with a key in their
`properties` (`property`), things worn in a place (`wearable`), and how many
of them (`max`). Clicks, drags, number keys and `shift_into` are held to it:
a stack it doesn't take stays put, and of one it does, only `max` goes in.
`slot:accepts(item)` asks it.

`slot:bind_equipment(player, "head")` makes a slot show what the player wears
there (`head`, `chest`, `legs`, `feet` or `off_hand`): what goes in is put on
at once, and what they put on some other way shows by the next tick.

```lua
this:on("open", function(event)
  local rings = event.player:storage("rings", 5)
  for i = 0, 4 do
    local slot = this:slot(2 + i)
    slot:bind(rings, i)
    slot:set_rule({ property = "accessory", max = 1 })
  end
  for i, place in ipairs({ "head", "chest", "legs", "feet" }) do
    local slot = this:slot(10 + i)
    slot:bind_equipment(event.player, place)
    slot:set_rule({ wearable = place, max = 1 })
  end
  this:shift_into(rings)
end)
```

## Measuring text

`nf.text.width(text)` says how many pixels wide a line of MiniMessage text
draws in the game's font, glyphs included, for lining text up in a title or a
text display:

```lua
local width = nf.text.width("<bold>Shop")
```

The game's font is part of Minecraft itself, which the server doesn't have, so
the editor writes its widths into the project as `fonts/default.json` from the
Minecraft you imported (commit it with the rest). Without that file, or for a
character it doesn't cover, the answer is `nil`.
