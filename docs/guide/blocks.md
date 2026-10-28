# Custom blocks

A custom block is a block of your own: a ruby ore with its own texture that
takes a pickaxe three seconds to mine and drops rubies, or a lamp post drawn by
a centity. It lives in `blocks/<id>/block.json`, with an optional script beside
it. The [block format](../format/block.md) has every key.

```
blocks/
  ruby_ore/
    block.json
    script.lua
```

```json
{
  "$schema": "../../.netherforge/schema/block.schema.json",
  "model": "ui/ruby_ore",
  "hardness": 3,
  "tool": "pickaxe",
  "requiresTool": true,
  "drops": "ruby_ore",
  "script": { "file": "script.lua" }
}
```

## Its look

A block's `model` is a look from a [resource pack](./resource-packs.md#block-looks): the
resource pack's `blocks` names a cube's textures (one for every face, or a top, a bottom
and sides). The editor's block screen shows the cube as the game will draw it.
Without a `model` a block is drawn as the plain game block it's held as (a
note block, by default), which is fine while you're still drawing it.

A block can also be a [model](../format/resource-pack.md#models) of any shape
(a crystal, a pot, a flower) on a `tripwire` or `leaves` carrier; see
[What it's held as](#what-it-s-held-as).

## Mining and drops

`hardness` is how long it takes, as the game counts it (stone is 1.5, obsidian
50): the right `tool` is faster, and with `requiresTool` the wrong one drops
nothing. What a block drops is a [loot table](../format/loot.md) (`drops`), rolled with
the player and the tool they used, so `fortune` and `silk_touch` conditions work.
A script may change what drops in the `break` event:

```lua
this:on("break", function(event)
  for _, item in ipairs(event.drops) do
    item.count = item.count * 2
  end
end)
```

## Placing one

An item places a block: put its id in the item's `block`
(`"block": "ruby_ore"`) and a right click with it puts the block down, using one
up. A script places one with `nf.blocks.get("ruby_ore"):place(location)`, and
`block:custom()` says whether a block is one of yours. What a script keeps about
one block (a counter, an owner) goes in `block:data()`, which is saved with the
world and goes when the block does.

## Clicks and ticks

`this:on("click", ...)` hears left and right clicks on any block of the kind, and
`this:on("tick", ...)` once every `tick` ticks (set it in `block.json`) for each
one placed in a loaded chunk. A block with neither costs nothing while it stands.

## Shapes that move

A block with a `centity` is drawn by it: the centity is spawned over the block
when it's placed and removed with it, so it can animate and have hitboxes of
its own. The block stays a solid cube to walk into and mine; make the
centity's displays the size you want to see.

## What it's held as

Minecraft can't add new blocks, so each custom block is one of the game's
blocks in a state the game rarely uses (its **carrier**), which your resource
pack draws as your block. The carrier decides how it behaves physically, so
pick the one that fits (`carrier` in `block.json`):

| Carrier                | Behaves like                                             | Good for                             |
| ---------------------- | -------------------------------------------------------- | ------------------------------------ |
| `note_block` (default) | A solid, opaque cube.                                    | Ores, machines, anything cube-shaped |
| `leaves`               | A solid cube you can see through, letting light past.    | Glass, crystals, pots, leafy blocks  |
| `tripwire`             | Nothing: players walk through it, and it breaks at once. | Flowers, plants, flat decorations    |

Things worth knowing:

- Players need the resource pack: it's sent when they join, and again when
  you change a block or its pictures.
- A world's ordinary note blocks keep working: tuning, playing and the
  instrument from the block under them all behave as usual. While the project
  has a tripwire block, tripwire hooks don't trip.
- Walking on and hitting a block sound like its carrier (a note block's
  wood); `sounds` gives the sounds of placing and breaking it.
- Pistons won't push one.

The [block format](../format/block.md#carriers) has the details of each
carrier.
