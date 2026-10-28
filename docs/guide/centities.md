# Centities and animation

This guide is about designing centities: how nodes, transforms, displays,
hitboxes and animations fit together. The [centity format](../format/centity.md)
is the complete list of keys.

## Nodes and transforms

A centity is a tree of named nodes. A node without a `parent` is a root; a
centity can have several. Node names are letters, digits, `_` and `-`, and
scripts find nodes by them (`this:node("lid")`), so name the ones you'll
script.

Each node has a local `transform` relative to its parent:

| Key           | Units                            | Default     |
| ------------- | -------------------------------- | ----------- |
| `translation` | blocks, `[x, y, z]`              | `[0, 0, 0]` |
| `rotation`    | degrees, applied X then Y then Z | `[0, 0, 0]` |
| `scale`       | multiplier                       | `[1, 1, 1]` |

A node's world transform is its parent's times its own: translate, then
rotate, then scale. So moving, turning or hiding a parent carries everything
under it, which is why it pays to group parts under empty "pivot" nodes:
put a lid's hinge on a pivot node at the hinge's position, the lid under it,
and turning the pivot swings the lid around the hinge.

The whole centity stands at one point, its **anchor**: where it was spawned,
or where `centity:teleport` moved it. Every node is placed relative to it.

## Displays

A node draws at most one thing:

- **block**: a block state such as `minecraft:oak_stairs[facing=east]`. It
  fills the unit cube from `[0, 0, 0]` to `[1, 1, 1]` in the node's space, so a
  block centred on its node needs a translation of `-0.5` on the axes you want
  centred.
- **item**: an item, shown in one of Minecraft's display contexts
  (`itemTransform`: `fixed`, `head`, `gui`, `ground`, …).
- **text**: MiniMessage text with optional billboarding (turn to face the
  viewer), alignment, background colour, line width, see-through and shadow.

A node without a display is still useful: as a pivot, as a hitbox, or as a
container for children.

## Hitboxes and clicks

A node with a `hitbox` can be clicked:

- `{}` is the unit cube a block display fills.
- `boxes` lists explicit boxes in the node's space.
- `shape: "collision"` follows the collision shape of the node's block, live,
  so it keeps up when a script calls `set_display_block`.

A click is an event that bubbles: the clicked node's `click` handlers hear it,
then its parent's and so on up to the root, then the centity's, then
`nf.on("centity_click")`. Any handler can end that with `event:stop()`.
`event.target` is the node that was hit, and `event.hit_position` where:

```lua
local lid = this:node("lid")
lid:on("click", function(event)
  this:play_animation("open")
  event:stop()
end)
```

**Fit to display.** The model shape of a stair or a fence is only known to
the game client, so the server can't follow it. For a block display, the
Inspector's **Hitbox** section has **Fit to display**, which writes the
block's model as explicit `boxes` and records what it fitted to in
`fittedTo`. If you later change the
display, the Problems panel warns that the fit is stale; fit again.

Minecraft's clickable areas are upright boxes with a square footprint, so a
node with several boxes is clickable within one box around all of them.
Clicks that land in that box but outside the real boxes are ignored
(`raycast`, on by default for several boxes).

## Animations

Animations are clips keyed by name. Each clip has tracks per node and per
channel (`translation`, `rotation`, `scale`), and each track is a list of
keyframes:

```json
"animations": {
  "bob": {
    "loop": "loop",
    "autoplay": true,
    "tracks": {
      "light": {
        "translation": [
          { "time": 0, "value": [0, 0, 0], "easing": "ease_in_out" },
          { "time": 1, "value": [0, 0.25, 0], "easing": "ease_in_out" },
          { "time": 2, "value": [0, 0, 0] }
        ]
      }
    }
  }
}
```

- **Loop modes**: `once` plays and returns to the base pose, `loop` repeats,
  `hold` stays on the last pose.
- **Autoplay** starts the clip when the centity loads, with no script.
- **Easing** shapes the segment _leaving_ a key: `linear`, `step`, `ease_in`,
  `ease_out` or `ease_in_out`.
- **Length** defaults to the last key's time; keys after an explicit length
  never play.
- A track owns its whole channel while the clip plays; outside its keys it
  holds the nearest one.
- Rotation interpolates each axis the short way round, so a full spin needs a
  key at least every 180°.

Several clips can play at once. Where two drive the same channel of the same
node, the one started most recently wins. In the editor, choose a clip in the
**Timeline** to preview it in the viewport; what you see is exactly what
plays.

### From a script

```lua
this:play_animation("open")            -- start (or restart) a clip
this:stop_animation("open")            -- stop one; its nodes go back to rest
this:stop_animation()                  -- stop everything
this:is_animation_playing("open")      -- true while playing (a held clip still is)

-- How it plays this time: faster, part way in, looping, easing in from the current pose.
this:play_animation("walk", { speed = 1.5, from_tick = 10, loop = true, blend_ticks = 5 })
this:pause_animation("walk")           -- hold it where it is
this:resume_animation("walk")
this:seek_animation("walk", 20)        -- jump to a tick of the clip
this:set_animation_speed("walk", 0.5)

this:on("animation_end", function(event)
  if event.animation == "open" then this:play_animation("idle") end
end)
```

To do one thing after another, wait for each clip in a task (see
[Waiting](modules.md#waiting)):

```lua
this:on("click", function()
  nf.task(function()
    this:play_animation("open")
    nf.wait_for(this, "animation_end")
    nf.wait(40)
    this:play_animation("close")
  end)
end)
```

### How it looks, and who sees it

A script can change how nodes are drawn and who sees them, without touching
`centity.json` (none of this is saved: set it up in the script's body):

```lua
local lamp = this:node("lamp")
lamp:set_glow_color("#ffaa00")                                -- block and item displays only
lamp:set_glowing(true)
lamp:set_brightness({ block_light = 15, sky_light = 15 })     -- lit the same in a cave
lamp:set_billboard("vertical")                                -- turns to face each viewer
lamp:set_interpolation_ticks(10)                              -- ease each new pose over half a second
this:node("sign"):set_display_text_style({ background = "#80000000", shadow = true })
this:node("sword"):set_display_item({ kind = "minecraft:diamond_sword", glint = true })
this:node("button"):set_clickable(false)                      -- drawn, but clicks pass through

this:set_view_range(128)          -- drawn from 128 blocks away (at 100% entity distance)
this:hide_from(player)            -- this player sees nothing of it, and can't click it
this:show_to(player)
```

`this:set_glowing(b)` and `this:set_glow_color(c)` set every node at once.
Hiding lasts while the server runs, through the player leaving and coming
back and the chunk unloading.

A script can also move nodes directly:

```lua
local angle = 0
this:on("tick", function()
  angle = (angle + 6) % 360
  this:node("blade"):set_rotation(vec3(0, angle, 0))
end)
```

A node shaped like a beam (one block long along Z, centred on its origin) can
be laid from one point to another with `node:stretch_between(from, to)`. For a
chain or a string between two moving things, make the node a `rope` in
`centity.json` and string it: the server lays its links every tick.

```lua
-- From the player's hands to the ball, with a little slack.
this:node("chain"):rope_between(player, this:node("ball"), { length = 6 })
```

Or ease a node there over some ticks, and back again, with `node:tween`:

```lua
this:on("land", function()
  this:node("root"):tween({ scale = vec3(1.4, 0.6, 1.4), ticks = 3, easing = "ease_out", back = true })
end)
```

A tween sets the same rest pose `set_*` does, so animations play over it. An
animation with `"blend": "additive"` plays on top of everything else instead:
a squash or a recoil keyed as a change, whatever size or pose the rest has.

Positions, offsets, rotations and scales are `Vec3` values: build one with
`vec3(x, y, z)`, and add, subtract and scale them with `+`, `-` and `*`. A
node's `translation`, `rotation` and `scale` are in its **parent's** space (a
root node's parent space is the centity's own: origin at the centity's
position, turned by its yaw). To work in the world, convert:

```lua
local lid = this:node("lid")
local hinge = lid:world_position()             -- where it is in the world
local top = lid:to_world(vec3(0.5, 1, 0.5))    -- a point on it, in the world
local here = lid:to_local(player:position())   -- the player, in the lid's space
lid:look_at(player:position())                 -- point its +Z at the player
lid:rotate(vec3.up, 15)                        -- turn it a little more
```

What a script sets is the node's **rest pose**. A playing clip overrides the
channels it has tracks for, and they fall back to the rest pose when it ends.
Displays ease to a new pose over one tick, so setting a transform every tick
looks smooth.

Nothing a script sets on a node is saved: after a restart or a reload the nodes are back
to what `centity.json` says. Set up state in the script's body, which runs
every time it starts, and keep anything that
must survive in the instance's saved table, `this:data()` (see [Scripting basics](scripting.md#saving-data)):

```lua
local data = this:data()
data.opened = data.opened or false
this:node("lid"):set_rotation(data.opened and vec3(-60, 0, 0) or vec3.zero)
```

### Adding and removing nodes

A script can add nodes to a running instance: `this:add_node(name, definition)`
adds a root, `node:add_child(name, definition)` one that hangs from a node.
The definition is one node of `centity.json` in Lua spelling, without its
`parent` (every field is optional; `Vec3`s where the file has `[x, y, z]`),
and it's checked by the same rules as the file:

```lua
local lamp = this:add_node("lamp", {
  transform = { translation = vec3(0, 2, 0) },
  display = { type = "block", block = "minecraft:sea_lantern" },
  hitbox = {},
})
local label = lamp:add_child("label", {
  transform = { translation = vec3(0.5, 1.2, 0.5) },
  display = { type = "text", text = "<yellow>Lamp" },
})
lamp:on("click", function()
  lamp:remove() -- the label goes with it
end)
```

An added node does everything a declared one does: it follows its parent
(and so an animated one), its display and hitbox are spawned for players,
clicks on it bubble up through its parents to the centity, and with `physics`
it's a body that falls and collides. Clips can't drive it, because they name
the file's nodes. A name that's taken, or a definition the file would be
refused for, is an error at the call.

Like a rest pose, added nodes **aren't saved**. A
restart or a reload of the centity (its script or `centity.json`) drops them,
and the script's body, which runs again, adds what it wants. Only nodes a
script added can be removed (`node:remove()` takes the node and everything
below it, along with its handlers); removing a node `centity.json` declares
is an error.

## Facing

A centity faces a **yaw**, in Minecraft's degrees (0 south, 90 west, 180
north, −90 east). Its +Z axis points that way, and the whole centity turns
with it about its position: every node, hitbox and physics body. A centity
spawned at a `Location` with a yaw (a player's `location()`, say) faces it;
one spawned at a bare position faces south.

```lua
this:yaw()                            -- which way it faces now
this:set_yaw(this:yaw() + 90)         -- a quarter turn to the right
this:look_at(player:position())       -- face the player (about the vertical only)
this:teleport(other:location())       -- go there, facing the way it faces
```

The turn is saved with the instance, so it survives a restart and a reload.
It only turns about the vertical: for pitch or roll, rotate a root node.
Because the centity's space is turned, `to_local` and `to_world` (on the
centity and on its nodes) account for it, so a node's `translation` never
changes when the centity turns.

## Walking

`centity:move_to(target, options?)` walks the whole centity somewhere,
finding its own way round the blocks in its way: to a position or a
`Location` in its world, or after an entity or another centity (looking for a
new path to it every half second, and stopping once it's within 2 blocks). It
answers whether it found a way, and its `path_end` handlers hear whether it
got there (`event.reached`) or gave up. `stop_pathing()` stops it, and so does
a `teleport`, without `path_end`.

```lua
-- A guard that walks between two posts, for ever.
local posts = { vec3(10.5, 64, 0.5), vec3(-10.5, 64, 0.5) }
local next_post = 1
this:on("path_end", function()
  next_post = 3 - next_post
  this:move_to(posts[next_post], { speed = 2 })
end)
this:move_to(posts[next_post], { speed = 2 })
```

The way is found over the blocks' collision shapes (a fence is too tall to
step over, a carpet is nothing), sized to its hitboxes: as wide as their wider
side, as tall as they are. A walk goes by its **feet**, the middle of the
bottom of its hitboxes, so a centity authored from its corner still walks
through a one-block gap and ends with its middle on the target. Walking, it
steps up a block (`step_height`), drops up to 3 (`max_drop`), and falls when
nothing holds it up; a block put across its path makes it find another way.
It turns to face its way as it goes (`face = false` keeps its facing). With
`fly = true` it flies instead, through the air in any direction, and finding a
way costs the server more. Its nodes, hitboxes and physics bodies all go with
it. With `open_doors = true` it goes through the game's wooden doors and fence
gates, opening each as it comes to it and shutting it behind it, as a
villager does (give it a `width` that fits a doorway, 0.6 for a person).

A walk isn't saved: a restart or a reload of its centity ends it (the
script's body runs again and can set off again). A centity whose chunk
unloads waits where it is and carries on when it loads. One search looks no
further than `range` (48 blocks by default) and at no more than 4096 places,
so a long way round a maze is found a piece at a time as it walks.

## Instances

Every spawn makes an **instance** with its own id (`centity:id()`), stable
across restarts. Instances persist: the server remembers them and reattaches
their scripts when it starts. When you change a centity and save, each live
instance moves onto the new definition: entities for nodes that still exist
are kept and updated, new nodes are spawned, removed ones go. Scripts restart:
their bodies run again, without `spawn`.

If you delete a centity's folder, its instances become **inert**: their
entities stay, their scripts stop, and they come back if the centity does.
`/nf list` shows them as "definition missing"; `/nf kill all` removes them.

A centity far from every player is in an unloaded chunk, and does nothing:
no ticks, no animation, until a player comes near again.

## Appearing by themselves

Give a centity a [`spawning`](../format/centity.md#spawning) block and it
appears near players without a script, the way mobs do: in the worlds, biomes,
light, heights and on the blocks you name, in groups, chosen by weight against
the other centities that fit the same spot, with a cap per player that is
separate from the server's mob caps.

A natural centity is **temporary**. It isn't saved, it goes when no player is
within its `despawnDistance`, and a restart drops it, so a quiet server never
fills up with them. When a script decides one is worth keeping (a player
tamed it, found it, bought it), `centity:keep()` makes it an ordinary centity
from then on; `"keepOnInteract": true` in its `spawning` block keeps one the
first time a player clicks it, with no script. `centity:is_natural()` says which it is, and its `spawn`
handler runs as for any new instance.

To refuse or move one before it exists, listen on `nf`:

```lua
nf.on("centity_natural_spawn", function(event)
  if event.centity == "wisp" and event.location.world:name() == "lobby" then
    event:cancel()
  else
    event.location = event.location:offset(vec3(0, 1, 0))
  end
end)
```
