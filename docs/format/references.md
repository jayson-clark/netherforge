# References

Files point at each other: a menu's slot holds a project item, a menu draws
a resource pack's skin, a dialog lists other dialogs, an item takes its look from a
resource pack, any text can show a resource pack's glyph. Every one of those is a
**reference**, and every reference is written the same way.

## Namespaces

Every project has a [`namespace`](project.md#netherforge-json) (`shop`), and
everything it defines is named inside it. A reference is:

- `id` for something in the project's own namespace: `ruby`, `welcome`
- `namespace:id` for something in another one: `acme:ruby`

So `ruby` and `shop:ruby` mean the same item in the `shop` project; the editor
writes the short form. A reference to another namespace is to a
[package](packages.md) the project depends on, and names only what that
package exports (`reference.not-exported`); any other namespace is an error
(`reference.namespace`).

Everything the project registers on a server or saves there is named
`namespace:id` too: a stack of the item `ruby` carries `shop:ruby`, the recipe
`ruby_sword` is the server's `shop:ruby_sword`, and the attribute modifiers and
mob goals scripts add are `shop:<name>`. Two projects on one server never
collide, and a stack keeps meaning the same item wherever it goes.

## What can be referenced

| What                                                                            | Written                        | Where it's defined                                                                            |
| ------------------------------------------------------------------------------- | ------------------------------ | --------------------------------------------------------------------------------------------- |
| A project item                                                                  | `ruby`                         | `items/ruby/item.json`                                                                        |
| A dialog                                                                        | `welcome`                      | `dialogs/welcome/dialog.json`                                                                 |
| A loot table                                                                    | `treasure`                     | `loot/treasure.json`                                                                          |
| A block                                                                         | `ruby_ore`                     | `blocks/ruby_ore/block.json`                                                                  |
| A centity                                                                       | `lamp`                         | `centities/lamp/centity.json`                                                                 |
| An advancement                                                                  | `adventurer`                   | `advancements/adventurer.json`                                                                |
| A biome                                                                         | `ruby_grove`                   | `biomes/ruby_grove.json`                                                                      |
| A resource pack's skin, glyph, item model, tooltip, equipment look, block model | `<pack>/<key>`: `ui/coin`      | `resource_packs/ui/pack.json`'s `skins`, `glyphs`, `items`, `tooltips`, `equipment`, `blocks` |
| A resource pack's sound                                                         | `<pack>/<key>`: `ui/menu/open` | `resource_packs/ui/sounds/menu/open.ogg`, or `pack.json`'s `sounds`                           |

A biome field names the game's biomes too, always in full: `minecraft:plains`, or a tag
`#minecraft:is_forest`. Those are the game's to check (against its data), never the project's: a plain id is
the project's (`reference.biome` when there's none, saying how the game's are written).

A resource pack's entries are named by resource pack and key, inside the project's namespace:
`ui/coin` is the glyph `coin` of `resource_packs/ui/`, and another package's would be
`acme:ui/coin`. That's also exactly what the game calls it: a project's resource packs
are built into its namespace in the resource pack, each under its own folder
(`assets/shop/textures/ui/…`), so `itemModel: "ui/ruby"` is the item model
`shop:ui/ruby` on the client and the sound `ui/menu/open` is the sound event
`shop:ui/menu/open`. See [resource packs](resource-pack.md).

## Where references are

| File                     | Fields                                                                                                                  |
| ------------------------ | ----------------------------------------------------------------------------------------------------------------------- |
| any item                 | `item` (a project item), `itemModel` (an item model), `tooltipStyle` (a tooltip), `equipment.asset` (an equipment look) |
| `item.json`              | `itemModel`, `tooltipStyle`, `equipment.asset`, `block`                                                                 |
| `block.json`             | `model` (a block model), `drops` (a loot table), `centity`, `sounds.place` and `sounds.break` (resource pack sounds)    |
| `menu.json`              | `skin`, and every slot's item                                                                                           |
| `dialog.json`            | `dialogs`, and every item body's item                                                                                   |
| `recipes/<id>.json`      | every ingredient's `{ "item": … }`, and the result                                                                      |
| `loot/<id>.json`         | every item entry's item, every table entry's `table`, every tool condition's `{ "item": … }`                            |
| `advancements/<id>.json` | `parent`, and the icon's `item` and `itemModel`                                                                         |
| `effect.json`            | every emitter's `item`                                                                                                  |
| `centity.json`           | `spawning.biomes`                                                                                                       |
| `terrain/<id>.json`      | every area's `biome`, every ore's `customBlock`                                                                         |
| `structures/<id>.json`   | `biomes`                                                                                                                |
| any MiniMessage          | `<glyph:ui/coin>`, wherever text a player reads goes: titles, names, lore, labels, text displays                        |

Inside a resource, files are named by their path from the resource's folder
(a script's `file`) or from the resource pack's `textures/` and `sounds/` folders
(`texture: "gui/shop.png"`). Those are checked as files, not as references,
but the editor follows them the same way when a file is renamed.

## Glyph tags

`<glyph:ui/coin>` is a reference inside text. MiniMessage splits a tag's
arguments at `:`, and the glyph tag joins them back, so another namespace's
glyph is `<glyph:acme:ui/coin>` (or `<glyph:'acme:ui/coin'>`); both are the
same. A tag escaped as `\<glyph:…>` isn't one.

## Broken references

A reference that names nothing is an error in the file that makes it, at the
value's JSON path. When the other end exists in part (the resource pack is there, but
not the key), the problem points at it too, so the editor can take you to
either:

| Problem                       | When                                                                                                     |
| ----------------------------- | -------------------------------------------------------------------------------------------------------- |
| `reference.syntax`            | It isn't shaped like a reference of its kind: `ui:coin` where `ui/coin` is meant.                        |
| `reference.namespace`         | It names a namespace that's neither the project's nor a package's.                                       |
| `reference.item`              | There's no such project item.                                                                            |
| `reference.dialog`            | There's no such dialog.                                                                                  |
| `reference.loot-table`        | There's no such loot table.                                                                              |
| `reference.block`             | There's no such project block.                                                                           |
| `reference.centity`           | There's no such centity.                                                                                 |
| `reference.advancement`       | There's no such advancement.                                                                             |
| `reference.biome`             | There's no such project biome.                                                                           |
| `reference.resource-pack`     | There's no such resource pack.                                                                           |
| `reference.resource-pack-key` | The resource pack has no such skin, glyph, item model, tooltip, block model or sound (and points at it). |

Every problem code is listed in [problems](problems.md).

## Renaming and deleting

Because every reference is found the same way, the editor follows every kind
of rename: an item, a dialog, a loot table, a block, a centity, an advancement, a biome, a resource pack (every reference into it moves), a resource pack's
skin or glyph, a texture or a folder of them, a script. Deleting something
first says which files refer to it.
