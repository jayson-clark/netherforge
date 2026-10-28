# Resource packs

A resource pack is a set of named pictures that the rest of the project points at:
backgrounds for menus, small glyphs for text, custom item looks and
tooltip frames. NetherForge builds every resource pack in a project into one Minecraft
resource pack and sends it to players as they join. The [file format](../format/resource-pack.md)
has every key.

```
resource_packs/
  ui/                    the folder name is the pack's id
    pack.json
    textures/
      gui/shop.png
      glyph/coin.png
      item/ruby.png
      tooltip/frame.png
      tooltip/background.png
```

```json
{
  "$schema": "../../.netherforge/schema/resource_pack.schema.json",
  "description": "Artwork for the shop",
  "skins": { "shop": { "texture": "gui/shop.png", "height": 168 } },
  "glyphs": { "coin": { "texture": "glyph/coin.png" } },
  "items": { "ruby": { "texture": "item/ruby.png" } },
  "tooltips": { "fancy": { "frame": "tooltip/frame.png", "background": "tooltip/background.png" } }
}
```

**The folder name is the resource pack's id.** Everything in the resource pack is referred to
as `<pack>/<key>`, so `ui/shop` above is the `shop` skin of the `ui` resource pack. The
resource pack is built into your project's namespace, so that's also the name the game
client resolves (`<namespace>:ui/shop`), and no other project's `ui` resource pack can
clash with it. Texture paths are relative
to the resource pack's `textures/` folder and must be PNGs.

## Skins: menu backgrounds

A skin replaces the look of a whole menu window. Name it in a
menu's `skin`:

```json
{ "rows": 3, "title": "<dark_gray>Village shop", "skin": "ui/shop" }
```

Minecraft doesn't let a server pick a container's texture, but it does let it
pick the title, and a title can use any font. So a skin is a very tall
character in a font of the resource pack's own, drawn at the start of the title and
pulled back so it takes up no room. Your title's words are written over the
art, starting where vanilla starts a title.

Draw the art at the size of the window it's for: a chest window is 176 GUI
pixels wide and 114 + 18 × rows tall (168 for three rows, 222 for six). Set
`height` to the picture's height so it's drawn pixel for pixel (the default,
256, suits art drawn on a 256×256 sheet the way vanilla's is). The default
`ascent` and `offset` put the picture's top left corner on the window's.

A dispenser, dropper or crafter centres its title on the title's words, which
would drag a skin along with them. The server puts the skin back where it
belongs by measuring the words with the default font's advances in
`fonts/default.json`, which the editor writes once Minecraft is imported.
Without that file (or for characters it doesn't cover) the skin moves with the
title's length, and the project shows a `font.needed` warning. A skin with no
title words never moves.

## Glyphs: pictures in text

A glyph is a small picture usable in any text. Glyphs are merged into
Minecraft's default font. `height` 8 is the height of a line of text.

Put one in text with the `<glyph:<pack>/<key>>` tag:

```json
{ "kind": "minecraft:bread", "name": "<yellow>Bread", "lore": ["<gray><glyph:ui/coin> 2"] }
```

The tag works in any MiniMessage text NetherForge shows: item names and lore,
menu titles, dialog text, labels and buttons, text displays,
`player:send_message` and `nf.server.broadcast`. The picture is drawn in white, so the
colour of the text around it doesn't tint it.

```lua
player:send_message("<gold><glyph:ui/coin> 1,200")
```

In the project's files, a `<glyph:…>` naming a glyph no resource pack has is a
validation error. In text a script builds at runtime, an unknown glyph shows
nothing and is reported once, in the console and the editor.

If you build text by hand, `nf.text.glyph("ui/coin")` returns the character the
glyph is drawn as. That raw character is tinted by the text's colour like
any other, unlike the tag. A glyph no resource pack has is an error, and `nf.text.glyph`
returns `nil` while the project's resource packs have errors, since nothing is built
then.

A glyph sits on the line at its `ascent`: 7 puts its top level with the top of
the letters, and lower values move it down (it can be negative). That's how a
picture can hang below the text it's in, beside a boss bar for example.

## Spacing: moving the cursor in text

Any MiniMessage text can move its cursor sideways with two tags. Both are
counted in GUI pixels (a line of text is 8 tall), and both need the resource
pack, whose default font holds the space characters they're drawn with. In a
project with no resource packs or blocks there's no resource pack, and the
tags draw nothing.

`<space:N>` moves the cursor N pixels. A negative N moves it left, so the next
thing overlaps what came before:

```lua
player:send_message("<glyph:ui/coin><space:-3><glyph:ui/coin> 2")  -- two coins, stacked
player:send_message("Sword<space:40><gold>12 gold")               -- a gap
```

`<at:N>…</at>` draws what it holds N pixels from the cursor and takes no width.
The server measures what's inside and moves the cursor back after it, so the
text that follows stays where it was:

```lua
bar:set_text("<at:-30><glyph:ui/boss_head></at>King Slime")
```

Two options, written as more arguments, say what N is counted from and which edge of the inside goes there:

| Option                            | Meaning                                                                                                                                        |
| --------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| `from=cursor\|start\|center\|end` | Count N from the cursor (the default), or from the start, middle or end of the line. The line's width doesn't count anything inside an `<at>`. |
| `align=left\|center\|right`       | Which edge of what it holds goes at N. The default is `left`.                                                                                  |

`from=center` matters for text the game centres: boss bars, and the titles of
dialogs, dispensers, droppers and crafters. The game puts the middle of the
line in the middle of the screen or window, so `<at:-100:from=center>` is
always 100 pixels left of that middle, however long the words are. With
`from=end:align=right`, a number sits flush against the end of the line.

Columns in lore line up with `from=start`, since each lore line is its own line:

```json
"lore": ["Buy<at:60:from=start:align=right><gold>5g</at>", "Sell<at:60:from=start:align=right><gold>2g</at>"]
```

Measuring needs widths. A glyph's comes from its picture, and every other
character's from `fonts/default.json`, which the editor writes when Minecraft
is imported. An `<at>` whose inside or line can't be measured (a character
that file doesn't cover, `<lang>` text only the client knows) draws what it
holds in line, as if the tag weren't there, and the server warns once.
`nf.text.width` counts both tags, and so do the centred-title fixes for menu
skins.

For text built by hand, `nf.text.space(n)` returns the characters `<space:n>`
inserts. A boss bar can do this for you: `nf.bossbars.create({ icon = "ui/boss_head", ... })`
draws the glyph just left of the bar's left end, whatever its text says. The
bar's top is 2 pixels below the text's baseline, so give the glyph a low
`ascent` to centre it on the bar.

## Item models: custom item looks

An item model gives one item stack a custom picture through its `itemModel`,
without replacing any vanilla item:

```json
{ "kind": "minecraft:paper", "name": "<red>Ruby", "itemModel": "ui/ruby" }
```

That stack of paper looks like a ruby; every other piece of paper is
untouched. `parent` chooses `minecraft:item/generated` (a flat sprite, the
default) or `minecraft:item/handheld` (held like a tool), and `guiTexture`
shows a different picture in inventories and item frames. An item that places
a custom block can look like the block instead: `"block": "ui/ruby_ore"` in
place of `texture` draws the block's cube, in 3D, as the game draws its own
block items.

## Tooltips

A tooltip style gives an item's tooltip its own `frame` and `background`,
through the item's `tooltipStyle`. Both are nine-slice sprites, so one style
fits any tooltip size.

## Equipment looks

An equipment look is how an item looks worn. Put the armour textures in the
resource pack (the humanoid sheet, and the leggings' sheet when the item is leggings),
add an `equipment` entry in `pack.json`, and point an item at it:

```json
"equipment": { "ruby": { "humanoid": "armor/ruby.png", "humanoidLeggings": "armor/ruby_leggings.png" } }
```

```json
{
  "kind": "minecraft:leather_helmet",
  "name": "<red>Ruby crown",
  "equipment": { "asset": "ui/ruby", "slot": "head" }
}
```

Whoever wears the item wears it as that armour, and scripts can set the same
`equipment = { asset = "ui/ruby", slot = "head" }` on any item table. The
editor's resource pack editor has the layers' pickers, and its item form the look and
slot.

## Block looks

A resource pack's `blocks` is how [custom blocks](./blocks.md) look: one texture for
every face (`{ "texture": "block/ruby_ore.png" }`), or a top, a bottom and sides
(`{ "top": …, "bottom": …, "side": … }`). A block names one as its `model`
(`ui/ruby_ore`), and the editor's pack screen has a Blocks section with the
picture beside the entry. The resource pack also redraws the game's note block states,
which is how a block is held; nothing else changes.

## How resource packs reach players

All of the project's resource packs are combined into one Minecraft resource
pack, which players are sent as they join. They only download it again when
it has changed. Save a change while players are online and it's sent to all
of them straight away, and open menus redraw.

If a resource pack has errors, players keep the last one that worked.

NetherForge can serve the resource pack itself, or you can host it at a URL of your own;
see [Server configuration](server-config.md#resource-packs).
