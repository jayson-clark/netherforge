# Resource packs

A resource pack is a set of named pictures and sounds that menus, items,
dialogs and scripts point at. NetherForge builds every resource pack in a
project into the one Minecraft resource pack it sends players as they join.

It lives at `resource_packs/<id>/pack.json`, with its PNGs under
`resource_packs/<id>/textures/` and its sounds under `resource_packs/<id>/sounds/`.
Its folder name is its id, and everything in it is named by that id and a key:
`ui/shop` is the skin `shop` of `resource_packs/ui/`, written like any other
[reference](references.md) (`acme:ui/shop` for another package's).

A project's resource packs all go into its own namespace in the built resource pack,
each under its id: the skin above is drawn from
`assets/<namespace>/textures/ui/…`, and an item whose `itemModel` is
`ui/ruby` uses the client's item model `<namespace>:ui/ruby`. So what a file
says is literally what the game resolves, and two projects' resource packs can't
collide however they name them.

```json
{
  "$schema": "../../.netherforge/schema/resource_pack.schema.json",
  "description": "Artwork for the shop",
  "skins": { "shop": { "texture": "gui/shop.png", "height": 168 } },
  "glyphs": { "coin": { "texture": "glyph/coin.png" } },
  "items": { "ruby": { "texture": "item/ruby.png" } },
  "tooltips": { "fancy": { "frame": "tooltip/frame.png", "background": "tooltip/background.png" } },
  "blocks": { "ruby_ore": { "texture": "block/ruby_ore.png" } },
  "equipment": {
    "ruby": { "humanoid": "armor/ruby.png", "humanoidLeggings": "armor/ruby_leggings.png" }
  }
}
```

`name` and `description` are labels for whoever reads the file (the editor's
resource pack list shows them); the game never sees them. Every other key is a section
below, each a set of entries by key.

Texture paths are relative to the resource pack's `textures/` folder and must be PNGs.
Keys follow the [id rules](project.md#ids).

## Skins

A whole GUI background, drawn behind a menu that names it in `skin`.

Minecraft doesn't let a server choose a container's texture per window, but it
does let it choose the title. So a skin is a very tall character in a font of
the resource pack's own, put at the front of the title: space characters move to where
the picture starts (`offset`), the picture is drawn, and more space characters
move back to where the title started. The prefix takes up no room, so the
title's words are drawn **over** the artwork, where vanilla puts a title.

Moving back needs the picture's advance, which Minecraft measures from its
pixels: the rightmost column with any visible pixel, plus one, scaled to
`height`, rounded, plus one pixel of gap (`floor(0.5 + opaqueWidth × height /
imageHeight) + 1`). The server reads the PNG to work it out. If it can't read
the picture, the resource pack gets a `resource_pack.image` warning and the prefix doesn't move
back, so the words start after the art.

Dispensers, droppers and crafters centre their title. The game centres it on
its whole width, which is just the words, so on those windows a skin moves
with the title's length: leave their `title` empty if the art must stay put.

| Key            | Meaning                                                                               | Default |
| -------------- | ------------------------------------------------------------------------------------- | ------- |
| `texture`      | The picture.                                                                          |         |
| `height`       | GUI pixels. A chest window is 114 + 18 × rows tall: 168 for three rows, 222 for six.  | 256     |
| `ascent`       | Pixels from the picture's top to its baseline. Can't exceed `height`.                 | 13      |
| `offset`       | Where drawing starts, in pixels from where the title starts. -8 is the window's edge. | -8      |
| `type`, `rows` | Editor hints: the container the art was drawn for.                                    |         |

## Glyphs

A small picture you can use in any text: item names, lore, titles, chat,
dialogs, text displays. Glyphs merge into Minecraft's default font.

Put one in MiniMessage text with the tag `<glyph:<id>/<key>>`, the resource pack's id and the glyph's key:

```json partial
"lore": ["<gray><glyph:ui/coin> 2"]
```

The glyph is drawn in its own colours (white, so the text's colour doesn't
tint it), never bold, and takes up its picture's advance, like any character. In a
project file, a `<glyph:…>` naming a glyph no resource pack has is an error
(`reference.resource-pack-key`, or `reference.resource-pack` when there's no such resource pack). In text a script builds, it shows nothing and is
reported once. Scripts can also get the glyph's bare character with
`nf.text.glyph("ui/coin")`; that character is tinted by the text around it.

| Key       | Meaning                                                                                        | Default |
| --------- | ---------------------------------------------------------------------------------------------- | ------- |
| `texture` |                                                                                                |         |
| `height`  | GUI pixels. 8 is a line of text.                                                               | 8       |
| `ascent`  | Pixels from the top to the baseline. Can't exceed `height`; may be negative, to hang it lower. | 7       |

Minecraft draws a font picture over 256 pixels either way blank (its font
sheets are 256×256), so a picture wider than that, a glyph's, a skin's or a HUD
element's, is drawn in equal pieces of 256 or less, each its own character,
placed side by side and taking up the whole picture's advance: a full-width
plate or banner is one glyph. It must cut evenly: a width that doesn't
(257) is an error that says what to pad it to, and so is a picture over 256
tall, which can't be cut (`resource_pack.picture-size`). What's measured is
the picture's pixels, not the `height` it's drawn at.

The built resource pack's default font also holds the space characters that
`<space:N>` and `<at:N>…</at>` move the cursor with (see the
[resource packs guide](../guide/resource-packs.md#spacing-moving-the-cursor-in-text)), so
those tags work in any text whenever there's a resource pack.

## Item models

A custom look for one item stack, through its `itemModel`. No vanilla item is
replaced: a stack of paper with `"itemModel": "ui/ruby"` looks like a ruby,
and every other piece of paper is untouched.

| Key          | Meaning                                                                                                                | Default                    |
| ------------ | ---------------------------------------------------------------------------------------------------------------------- | -------------------------- |
| `texture`    | The picture, drawn flat.                                                                                               |                            |
| `block`      | A [block look](#block-models) (`ui/ruby_ore`) to draw instead: a cube in slots and the hand, as a block item is.       |                            |
| `model`      | A [model file](#models) (`crystal.json`, under `models/`): a shape of its own, drawn as modelled.                      |                            |
| `parent`     | With a `texture`: `minecraft:item/generated` (a flat sprite) or `minecraft:item/handheld` (held like a tool)           | `minecraft:item/generated` |
| `guiTexture` | A different picture shown only in inventories and item frames.                                                         |                            |
| `drawn`      | [Draw stages](#draw-stages) (`["bow_pull_0.json", …]`): model files shown while the item is in use, as a bow is drawn. |                            |

A look has one of `texture`, `block` and `model`, and `parent` only goes with a
`texture` (`resource_pack.item-look`). The item that places a [block](block.md) usually
takes the block's look:

```json partial
"items": { "ruby_ore": { "block": "ui/ruby_ore" } },
"blocks": { "ruby_ore": { "texture": "block/ruby_ore.png" } }
```

and the item `"itemModel": "ui/ruby_ore"`.

The game only stitches its own `textures/item/` folder into its `items` atlas,
and a model drawing a texture outside every atlas shows the missing texture, so
the built resource pack adds the looks' textures to it
(`assets/minecraft/atlases/items.json`). A texture a block look uses too goes
in the `blocks` atlas only: the game wants each texture in one atlas.

### Draw stages

An item that's drawn over time, like a bow, names a model per stage in
`drawn`, from the first pull to a full draw. While the player uses the item,
the first stage shows; each later one from its share of the first second of
use, so the last from a full draw (20 ticks, the bow's). Once the item is put
down, its look shows again.

```json partial
"items": {
  "bow": {
    "model": "bow.json",
    "drawn": ["bow_pull_0.json", "bow_pull_1.json", "bow_pull_2.json"]
  }
}
```

Each stage is a [model file](#models) under `models/`, checked like any other
(`resource_pack.model-file`), and the built pack gives the item a definition
that chooses between them with the game's `use_duration`. A list needs at
least one stage (`resource_pack.item-look`). A stage can be an empty model (`{}`) to hold
its place until the art is drawn.

## Models

A shape of your own: a JSON model with elements, the game's own format, as
[Blockbench](https://www.blockbench.net/) exports one (File → Export → Export
Block/Item Model). Put it in the resource pack's `models/` folder, and the
textures it uses in `textures/`:

```
resource_packs/mobs/
  pack.json
  models/wing.json
  textures/wing.png
```

```json partial
"items": { "wing": { "model": "wing.json" } }
```

An item look (`"itemModel": "mobs/wing"` on an item, held, worn and shown in
slots as the model's own `display` transforms say) or a
[centity node](centity.md#display) (`{ "type": "model", "model": "mobs/wing" }`)
shows it. A block look can be a model too (`"blocks": { "crystal": { "model":
"crystal.json" } }`); see [blocks](block.md) for which blocks can draw a shape
that isn't a cube.

**Textures.** A texture the model names by a path (`"0": "wing"`, as Blockbench
writes a texture saved beside the model) is the pack's own when there's such a
file under `textures/` (`textures/wing.png`), and the built pack names it as its
own. Anything else is the game's (`block/stone`, `minecraft:item/stick`), as
written. A texture in another namespace (`"mypack:item/wing"`, which Blockbench
writes when the project names one) is in nothing the client has, so it's an
error (`resource_pack.model-texture`): name the pack's textures by their path
under `textures/`. A face naming a texture variable the model doesn't give
(`"#3"` with no `"3"` in `textures`) is the same error, unless the model has a
`parent`, which may give it.

**What the game takes**, checked as you save (`resource_pack.model`,
`resource_pack.model-bounds`), the same on every supported version (1.21.11
and every 26.x):

- An element's `from` and `to` are three numbers each, in sixteenths of a
  block, every one from **-16 to 32**: a model fits within a box three blocks
  wide, centred on its block. One coordinate past that and the game refuses
  the whole model (it draws as the missing model).
- An element's `rotation` is either **one axis at any angle**
  (`{ "origin": [8, 0, 8], "axis": "y", "angle": 37.5 }`; any angle, not only
  the 22.5° steps older versions took) or **any angles about all three axes**
  (`{ "origin": [8, 0, 8], "x": 10, "y": 30, "z": -5 }`, turned about X, then
  Y, then Z). `rescale: true` stretches a rotated face back to the size of
  the box it was cut from.
- Faces are `down`, `up`, `north`, `south`, `west` and `east`, each with a
  `texture` and optionally `uv` (four numbers), `rotation` (0, 90, 180 or
  270), `cullface` and `tintindex`. An element may also have `shade` and
  `light_emission` (0 to 15: drawn as if lit to that level).
- A `display` transform's `translation` is clamped to ±80 and its `scale` to
  ±4 by the game (`resource_pack.model-display`, a warning).

Keys the game ignores (Blockbench's `groups`, `texture_size`, `credit`) are
kept as they are.

**A model bigger than three blocks** doesn't fit the bounds. Model it smaller
and scale it up where it's shown: a centity node's `transform.scale` (or the
model's `display` transform, up to 4 times, for the context it's shown in)
draws it as big as you like, at the cost of its pixels growing with it. Or
split it into parts, each its own model within the bounds, on nodes of their
own: a creature's limbs as separate models is also what lets each move with
the centity's animation tracks.

## Tooltips

A custom tooltip frame and/or backing, through an item's `tooltipStyle`. Both
are nine-slice sprites, so one style fits any tooltip size.

| Key          | Meaning                   |
| ------------ | ------------------------- |
| `background` | The fill behind the text. |
| `frame`      | The border around it.     |

## Block models

How a [block](block.md) looks, through the block's `model`. A block
whose `model` is `ui/ruby_ore` is drawn as the resource pack's block model
`<namespace>:block/ui/ruby_ore`: a [model file](#models) of its own shape
(`{ "model": "crystal.json" }`), or a cube with these textures:

| Key       | Meaning                                                                 |
| --------- | ----------------------------------------------------------------------- |
| `texture` | Every face that has no texture of its own.                              |
| `top`     | The top face.                                                           |
| `bottom`  | The bottom face.                                                        |
| `side`    | The four sides that have no texture of their own.                       |
| `north`   | The north face (and `south`, `east` and `west`): one side that differs. |

A face takes the first of its own texture, `side` (for the four sides) and
`texture`, and every face needs one: `{ "texture": "block/ore.png" }` is a cube
of one picture, `{ "top": …, "bottom": …, "side": … }` a log. A look that
leaves a face out is an error (`resource_pack.block-faces`), and so is a look
with a `model` and textures both. Breaking the block throws up the first of
`texture`, `side` and `top` that's set, or a model's `particle` texture (else
its first).

In the built resource pack the look is `assets/<namespace>/models/block/ui/ruby_ore.json`
(a child of the game's `block/cube`, or the model file as written), and the
project's blocks are drawn by one more file the build writes per game block
they're held in (`assets/minecraft/blockstates/note_block.json`,
`tripwire.json`, `oak_leaves.json`…), which draws each state a custom block
holds as its model and every other as the game does. See
[blocks](block.md#carriers).
A block model's textures are added to the game's `blocks` atlas in the same way
(`assets/minecraft/atlases/blocks.json`).

## Equipment

How an item looks **worn**, through the item's [`equipment`](item.md). An item
with `"equipment": { "asset": "ui/ruby", "slot": "head" }` is set to the
`equippable` component of that slot, with the asset `<namespace>:ui/ruby`:
players put it on like any armour (right-click, the armour slot, a dispenser),
and the client draws the look's layers on them. Nothing vanilla is replaced.

Each key names the textures drawn for one kind of wearer; give the layers the
item's slot is drawn with, and leave the rest out:

| Key                | Drawn on                                                                 |
| ------------------ | ------------------------------------------------------------------------ |
| `humanoid`         | A player's or mob's head, chest and feet armour: the 64×32 armour sheet. |
| `humanoidLeggings` | A humanoid's leggings: its own 64×32 sheet.                              |
| `wings`            | An elytra-style chest piece's wings.                                     |
| `horseBody`        | A horse's body armour (`body` slot).                                     |
| `wolfBody`         | A wolf's body armour (`body` slot).                                      |

An equipment look with no layer is a warning (`resource_pack.equipment-empty`): nothing
is drawn. A texture that is missing or isn't a PNG in `textures/` is the same
error as anywhere else in a resource pack (`resource_pack.texture-missing`, `resource_pack.texture-path`).

In the built resource pack, the look `ruby` of the resource pack `ui` is
`assets/<namespace>/equipment/ui/ruby.json` (the asset id the item sets), and
each layer's texture is copied to
`assets/<namespace>/textures/entity/equipment/<layer>/ui/ruby.png`
(`humanoid`, `humanoid_leggings`, `wings`, `horse_body`, `wolf_body`). The
equipment asset format is the same on every supported Minecraft version (the
last 1.21.x and 26.x).

## Sounds

Every `.ogg` file under the resource pack's `sounds/` folder is a sound event, with
nothing to declare: `resource_packs/ui/sounds/menu/open.ogg` is the sound `ui/menu/open`,
its key being the file's path without `.ogg`. Scripts play it by that
reference, the same way as a Minecraft sound (the game knows it as
`<namespace>:ui/menu/open`).

Minecraft only plays Ogg Vorbis. A file in `sounds/` that isn't an `.ogg` is
ignored with a warning (`resource_pack.sound-file`), and an `.ogg` whose path isn't
lowercase letters, digits and `_` in each folder and name is an error
(`resource_pack.sound-name`): it can't be a resource name. Hidden files (`.DS_Store`)
are ignored.

`sounds` in `pack.json` refines an event, or adds one that picks among
several files. It's keyed by the event's key, and every key is optional:

```json partial
"sounds": {
  "menu/open": { "volume": 0.6, "subtitle": "Menu opens" },
  "step": { "files": ["step/grass_1.ogg", "step/grass_2.ogg"], "pitch": 1.2 }
}
```

| Key        | Meaning                                                                                                                                                                                                                                  | Default         |
| ---------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------- |
| `files`    | Paths under `sounds/`, one picked at random each time it plays. Without it, the event plays `sounds/<key>.ogg`.                                                                                                                          | `["<key>.ogg"]` |
| `volume`   | 1 is the file as recorded. Above 1 the sound carries further rather than getting louder.                                                                                                                                                 | 1               |
| `pitch`    | 1 is the file as recorded. The game clamps what it plays to 0.5–2.                                                                                                                                                                       | 1               |
| `stream`   | `true` streams the files from disk as they play, instead of loading them whole: set it on long tracks (music, ambience; vanilla does for its music and discs). Never decided from the file, so a build only depends on the files' bytes. | `false`         |
| `subtitle` | What players with subtitles turned on read: a translation key, or plain text.                                                                                                                                                            | none            |

Each file listed in `files` is still a sound event of its own. In the built
resource pack the files go to `assets/<namespace>/sounds/<id>/` and the
events, every resource pack's, into `assets/<namespace>/sounds.json`.

### Replacing the game's sounds

A key in the game's namespace replaces one of the game's own sound events
with the pack's files, for every player, wherever the game plays it:

```json partial
"sounds": {
  "minecraft:block.stone.break": { "files": ["blocks/stone_break_1.ogg", "blocks/stone_break_2.ogg"] },
  "minecraft:block.gravel.step": { "files": ["blocks/silt_step.ogg"], "volume": 0.8 }
}
```

The built pack writes it into the game's own `assets/minecraft/sounds.json`
with `"replace": true`, so the game's files for the event are dropped and only
the pack's play, one picked at random each time. `volume`, `pitch` and
`stream` apply to the pack's files as for any sound. `files` is required
(`resource_pack.sound-replace`). The game's subtitle goes with its files, so
give `subtitle` to keep one (`subtitles.block.generic.break` is the game's for
breaking a block).

**It changes everything that plays the event.** Blocks play the sounds of
their sound type: every block of the stone type breaks with
`block.stone.break`, and every gravel-sounding block steps with
`block.gravel.step`. Footsteps, hits and falls are chosen by each player's own
game from the block's sound type, so replacing the event is the only way to
change them, for the [game's blocks](game-block.md) and for a project block's
[carrier](block.md#carriers) (a note block steps as wood).

The names are the game's sound events (`block.stone.break`, `entity.zombie.ambient`,
`music.overworld.forest`), which the server's game data lists: with it, one
the target version doesn't have is an error (`resource_pack.sound-event`).
Two resource packs can't replace the same event (also `resource_pack.sound-event`).
Only the game's own namespace is replaced this way; a key in another namespace
is `resource_pack.sound-key`.

## HUD

`hud` changes what players see around the hotbar: it hides any of the game's
own HUD elements, for every player, and adds elements of the pack's own that
scripts fill in per player through [`player:hud()`](../reference/hud.md): bars of a
value out of a max, rows of icons, and lines of text.

```json partial
"hud": {
  "hide": ["health", "food"],
  "bars": {
    "health": { "full": "hud/heart.png", "half": "hud/heart_half.png", "empty": "hud/heart_empty.png", "segments": 10, "x": -91, "y": 30 },
    "mana": { "full": "hud/mana.png", "empty": "hud/mana_empty.png", "segments": 10, "x": 10, "y": 30, "reverse": true }
  },
  "icons": { "effects": { "icons": { "poison": "hud/poison.png", "regeneration": "hud/regen.png" }, "x": -91, "y": 42 } },
  "texts": { "mana_label": { "x": 50, "align": "center" } }
}
```

```lua
local hud = player:hud()
hud:fill_bar("ui/health", player:health(), player:max_health())
hud:fill_bar("ui/mana", mana, 100)
hud:set_icons("ui/effects", { "poison" })
hud:set_text("ui/mana_label", "<aqua>" .. mana .. "/100")
```

### Hiding the game's elements

`hide` lists any of `health` (every kind of heart, a mount's too), `food`,
`armor`, `air` and `experience` (the bar; the level number is text the game
draws while it's above 0). The built pack replaces the sprites that draw them
with empty ones, so it's for the whole project: a resource pack is the same
for every player, so one player can't see them while another doesn't.

Which sprites there are is the client's to say, so the editor writes their
names into [`client/vanilla.json`](project.md#clientvanillajson) from your
imported Minecraft. Until it has (`resource_pack.hud-hide`), nothing is
hidden. Mods that draw these elements themselves still draw them.

### Redrawing the game's sprites

`replace` draws the game's own GUI sprites with pictures of the pack instead,
for every player: the sprite's name (its path under the client's
`textures/gui/sprites/`, without `.png`) to a texture of the pack. It's how a
pack restyles the hotbar:

```json partial
"hud": {
  "replace": {
    "hud/hotbar": "hud/hotbar.png",
    "hud/hotbar_selection": "hud/hotbar_sel.png",
    "hud/hotbar_offhand_left": "hud/offhand_left.png",
    "hud/hotbar_offhand_right": "hud/offhand_right.png"
  }
}
```

The built pack writes the pack's PNG over the sprite
(`assets/minecraft/textures/gui/sprites/hud/hotbar.png`), so it goes with
`hide`: whatever isn't hidden or replaced stays the game's. The names are the
HUD's sprites in [`client/vanilla.json`](project.md#clientvanillajson), which
the editor writes from your imported Minecraft; one the game doesn't have is an
error (`resource_pack.replace-name`), and until the file lists them nothing is
replaced (`resource_pack.vanilla-missing`). Two resource packs can't replace the
same sprite.

The game draws a sprite at the size its own picture is, at the place it draws
its own, so draw yours to the same pixel size and leave the gaps inside the
picture: take the original from your client jar to start from. Some sprites
scale: a `.mcmeta` file beside the picture (`hud/hotbar.png.mcmeta` in the
pack's `textures/`) is built in beside the sprite, with the game's own
[nine-slice](https://minecraft.wiki/w/Resource_pack#Texture_scaling) settings if
you write them. Without one the sprite is stretched, as the game's hotbar is;
the game's own `.mcmeta` isn't kept.

### Placing elements

Positions are GUI pixels, so they scale with the player's GUI scale like the
game's own HUD. `x` is from the middle of the screen (negative to the left) to
the element's left edge, within ±1000. `y` is from the bottom of the screen up
to its bottom edge, from 0 to 65. For reference, the game's own rows: the
hotbar's top at 22, the experience bar 24 to 29, hearts and hunger 30 to 39
(hearts from x -91, hunger ending at x 91), armour 40 to 49.

| Bar key    | Meaning                                                                                                           | Default |
| ---------- | ----------------------------------------------------------------------------------------------------------------- | ------- |
| `full`     | The picture of a full segment.                                                                                    |         |
| `empty`    | The picture of an empty one.                                                                                      |         |
| `half`     | The picture of a half one: with it, the bar shows to the nearest half segment, and without, to the nearest whole. | none    |
| `segments` | How many, 1 to 64.                                                                                                |         |
| `x`, `y`   | Where its left and bottom edges are.                                                                              |         |
| `gap`      | Pixels between segments. Each segment takes the width of the widest picture.                                      | 0       |
| `reverse`  | Fill from the right, like the game's hunger bar.                                                                  | `false` |
| `align`    | Which part of the segments drawn is at `x`: `left`, `center` or `right`.                                          | `left`  |

A script can draw fewer of a bar's segments than it has (`hud:fill_bar(name,
value, max, { segments = 4 })`: a row of hearts as long as the player's max
life), and `align` keeps such a bar where it belongs: `right` for one that
ends at `x`, `center` for one centred on it. `hud:fill_bars` fills several
bars as one, row after row.

A row of `icons` has its pictures by key, an `x`, a `y`, a `gap` (1) and an
`align` (`left`): a script says which it shows, in what order, and they're
drawn left to right, with their left edge, middle or right edge at `x`. A line of `texts` has an `x` and an `align` (`left`, `center` or
`right`: which part of the text is at `x`); it's drawn on the action bar's own
line, in the game's font, in its own colours and shadow.

Every key is unique across the pack's bars, icons and texts, since scripts
name them `<pack>/<key>` whatever kind they are (`resource_pack.hud`).

### How it's drawn

The elements are characters of a font of the pack's own (`<namespace>:<pack>/hud`),
drawn in the player's action bar: each picture's ascent puts it at its height,
and moves of the cursor put it across, so the line takes no width and the
game's centring leaves everything where it says. Pictures are drawn as they
are, untinted and without the text shadow. So:

- The HUD and `player:send_actionbar` share the line: a message is shown with
  the HUD for a few seconds, then the HUD alone.
- The server sends the line again every second, as the game fades the action
  bar out after a few. A player with a slow connection may see it flicker.
- Lines of text are measured with the game's font (`fonts/default.json`) to be
  placed; one the server can't measure isn't drawn.
- Elements are above other screens' contents only as the action bar is: they
  hide with the HUD (F1) and under a chat that's open.

## Redrawing the game's textures

`textures` redraws any of the game's own textures with a picture of the pack,
for every player: blocks, items, mobs and other entities, the sky, particles,
paintings, the font, the screens behind containers, the GUI's sprites. It's
a top-level map of the texture's name (its path under the client's
`textures/`, without `.png`) to a texture of the pack:

```json partial
"textures": {
  "block/stone": "vanilla/block/stone.png",
  "item/diamond": "vanilla/item/diamond.png",
  "entity/zombie/zombie": "mobs/zombie.png",
  "gui/container/inventory": "gui/inventory.png"
}
```

The built resource pack writes the pack's PNG over the game's
(`assets/minecraft/textures/block/stone.png`). Every block, item and model
that draws the texture shows the new picture. Nothing else changes: models,
shapes and sounds stay the game's. Two resource packs can't redraw the same
texture.

In the editor, **Game textures** → **+** asks for the texture's name and puts
a copy of the game's picture (and its animation) in the pack under
`textures/vanilla/`, ready to draw over.

**Sizes.** Most textures are drawn by where things are on them as a share of
the whole, so a replacement can be any size of the **same shape**: 32×32 for
a 16×16 block is the same art at twice the detail. Another shape is a warning
(`resource_pack.replace-shape`): the game stretches it. The exceptions are a
screen's sheet (anything under `gui/` except `gui/sprites/`: the inventory,
chests, furnaces…) and the colour maps (`colormap/`). The game cuts those at
fixed pixel places, so **the replacement must be exactly the game's pixel
size** (256×256 for the inventory). Another size is an error
(`resource_pack.replace-size`). Keep every slot, the armour column, the
player's preview box and the recipe button where the original has them: only
the art changes, not where the game draws things.

**Animations.** A texture the game animates (water, lava, fire, sea lanterns,
prismarine) is its frames stacked in one tall picture, and the frames are
read from the `.mcmeta` file beside it **in the same pack**. So a replacement
with no `.mcmeta` of its own is a still picture, one square frame
(`resource_pack.replace-shape` otherwise). To animate a replacement, or any
texture the game draws still, put a `.mcmeta` beside the picture
(`textures/vanilla/block/water_still.png.mcmeta`), in the game's
[animation format](https://minecraft.wiki/w/Resource_pack#Animation). It's
built in beside the texture, and the editor copies the game's along with its
picture.

**Names.** The game has thousands of textures, so
[`client/vanilla.json`](project.md#clientvanillajson) lists the sizes of the
ones the project's packs name, and the names the game doesn't have. The
editor writes it from your imported Minecraft as you add names. A name it
hasn't looked up yet is a warning that nothing is replaced yet
(`resource_pack.vanilla-missing`), and one the game doesn't have is an error
(`resource_pack.replace-name`).

Unlike [skins](#skins), which draw behind menus the project opens itself, a
redrawn screen texture changes the screen of every player who opens it,
menus of the project's or not. Unlike [item models](#item-models) and
[block models](#block-models), which give the project's own items and blocks
a look of their own, it changes every one of the game's blocks or items that
draws the texture.

## Redrawing the game's models

`models` does for the game's models what `textures` does for its pictures: a
model file of the pack (a path under its [`models/`](#models) folder) is
written over the game's model of that name, for every player. A plant, a
lantern or a pot changes shape without becoming a project block:

```json partial
"models": {
  "block/red_mushroom": "mushroom.json",
  "item/lantern": "lantern_item.json"
}
```

The name is the model's path under the client's `models/`, without `.json`.
Every block state and item the game draws with the model takes the new
shape; which model a state draws is the game's blockstate file, which stays
the game's. The model's textures are the pack's (by their path under
`textures/`, as in any model of the pack) or the game's (`block/stone`), and a
block model's own textures are stitched into the blocks atlas. The shape is
only drawn: what collides with the block and what a player selects stay the
game's.

As for textures, [`client/vanilla.json`](project.md#clientvanillajson) lists
the models the project's packs name, which the editor looks up in your
imported Minecraft. A name it hasn't looked up yet replaces nothing yet
(`resource_pack.vanilla-missing`), one the game doesn't have is an error
(`resource_pack.replace-name`), and two resource packs can't redraw the same
model.

## How it reaches players

The plugin builds the resource pack whenever one of the project's changes, serves it from the server
itself (or a URL you configure), and sends it with its SHA-1 so clients only
download it again when it actually changed. The pack format is the one your
target Minecraft version uses.
