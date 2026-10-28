# Your first centity

A **centity** is a composed, scripted entity: a tree of **nodes**, each of
which can show a block, an item or some text, and be clicked, all moving
together. A script brings it to life. Players need no mods or resource pack
to see one: it's made of Minecraft's own display entities.

## Create it

In the Project explorer, pick **Centities**, choose **New centity** and give it an
id, say `tower`. The id is the folder name, so it's lowercase letters, digits
and `_`. Scripts and commands refer to the centity by it (`nf.centities.spawn("tower")`,
`/nf spawn tower`).

The editor creates `centities/tower/centity.json` with one node, `root`, that
shows a stone block and has a hitbox, and opens it:

- the **Nodes** tree in the outline on the left (right-click a node, or use
  its <kbd>+</kbd>, to add a child; <kbd>F2</kbd> renames it in place),
- the **3D viewport** in the middle, with a gizmo to move, turn and scale the
  selected node,
- the **Inspector** on the right, for everything about the selected node
  (its transform, display, hitbox and physics), or, with no node selected,
  the centity itself: its name and its one script,
- the **Timeline** below, for animations.

## Build it

1. Select `root`, and in the Inspector's **Display** section change the block to
   `minecraft:stone_bricks`.
2. Choose **Add child** on `root` and rename the new node `top` (<kbd>F2</kbd>). Give it a block
   display of `minecraft:oak_stairs[facing=east]` and move it up one block
   (translation `0, 1, 0`), with the gizmo or by typing in **Transform**.
3. Save (<kbd>Ctrl</kbd>/<kbd>Cmd</kbd>+<kbd>S</kbd>).

What you saved is plain JSON, and you can read it, diff it and edit it by hand:

```json
{
  "$schema": "../../.netherforge/schema/centity.schema.json",
  "nodes": {
    "root": {
      "display": { "type": "block", "block": "minecraft:stone_bricks" },
      "hitbox": {}
    },
    "top": {
      "parent": "root",
      "transform": { "translation": [0, 1, 0] },
      "display": { "type": "block", "block": "minecraft:oak_stairs[facing=east]" }
    }
  }
}
```

(The editor writes it in its canonical form: two-space indentation, one key
per line, keys in a fixed order. See [How files are written](../format/project.md#how-files-are-written).)

## See it in game

With the dev server running and you in game, either:

- choose **Spawn at me** in the toolbar, which spawns the centity that's open
  in front of you, or
- type `/nf spawn tower`.

Now change something (the block, a translation) and save. The dev server
reloads the centity and the tower in front of you updates in place: live
instances are moved onto the new definition rather than respawned.

## Add an animation

In the **Timeline**, choose **New animation** and call it `spin`. Select
`top`, pick the `rotation` channel and choose **Add key** to key it at the
playhead. Once a channel has a track, moving the playhead and turning the
node with the gizmo keys that channel there. Add keys at 0, 0.5, 1 and 1.5
seconds turning it to 0°, 120°, 240° and 360° around Y (a rotation goes the
short way between keys, so a full turn needs a key at least every 180°). Set
the loop mode to `once` and save.

Nothing plays it yet. A clip with **Autoplay** on starts by itself when the
centity loads; this one we'll start from a script. Next: [your first script](first-script.md).

The full list of what a node can have is in the [centity format](../format/centity.md),
and [Centities and animation](centities.md) goes deeper.
