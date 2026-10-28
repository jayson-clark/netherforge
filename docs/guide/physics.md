# Physics

A node with `physics` is a **rigid body**: it falls, slides, bounces and
tumbles, colliding with the world's blocks and with other centities. Use it
for crates that can be knocked over, balls that roll, debris, anything that
should move on its own rather than along an animation.

```json
"crate": {
  "display": { "type": "block", "block": "minecraft:barrel" },
  "hitbox": {},
  "physics": { "bounciness": 0.2, "friction": 0.8 }
}
```

Every key is optional; `"physics": {}` is a body with sensible defaults. The
[physics table](../format/centity.md#physics) has the full list.

## Mass, gravity and friction

| Key           | Default         | Meaning                                                       |
| ------------- | --------------- | ------------------------------------------------------------- |
| `gravity`     | 32              | Downward acceleration in blocks/s².                           |
| `mass`        | collider volume | Only matters between bodies: a heavy body pushes a light one. |
| `bounciness`  | 0               | 0 lands dead; 1 bounces forever.                              |
| `friction`    | 0.6             | 0 is ice; about 0.6 is wood on stone.                         |
| `drag`        | 0.02            | Fraction of speed lost per second in the air.                 |
| `angularDrag` | 0.05            | The same for spin.                                            |
| `maxSpeed`    | 60              | Blocks per second.                                            |
| `maxSpin`     | 3600            | Degrees per second.                                           |

## Shape

A body collides with its **hitbox** by default, or with the unit cube if it
has none. To collide with something else, give it a `collider` box
(`min`/`max`, like a hitbox box). `shape: "box"` collapses a multi-box hitbox
into one box; `shape: "sphere"` makes it roll.

## What it collides with

- `blocks` (default true): the world's blocks, using the server's own
  collision shapes.
- `entities` (default true): other centities' hitboxes.
- `rotates` (default true): set false for a body that slides but never turns.
- `sleeps` (default true): a body stops being simulated once it's at rest,
  which keeps many resting bodies cheap.

## Pushing bodies from Lua

Physics moves the node: each tick it writes the node's translation and
rotation, after animations and before scripts. A script can push it:

```lua
local crate = this:node("crate")
crate:on("click", function(event)
  crate:add_velocity(vec3(0, 8, 0)) -- hop
end)
```

A body's node also hears `collide` (it started touching something: the
`speed` it hit at, where, and the `other` body when it's one of its own
centity's), `sleep` (it settled) and `wake` (it moves again).

| Method                                       | Does                                                                                               |
| -------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| `set_velocity(v)`                            | Replaces its velocity, in blocks per second along the world's axes.                                |
| `add_velocity(v)`                            | Adds to it, whatever the body weighs ("go this much faster").                                      |
| `apply_impulse(impulse)`                     | Pushes through the centre of mass: the same push moves a light body further.                       |
| `apply_impulse_at(impulse, point)`           | Pushes at a world position, so it turns the body too.                                              |
| `set_angular_velocity(v)`                    | Replaces how fast it turns, in degrees per second about each world axis.                           |
| `velocity()`, `angular_velocity()`, `mass()` | Read them back.                                                                                    |
| `is_on_ground()`                             | Whether it's resting on something; `not is_on_ground()` means falling.                             |
| `is_asleep()`, `wake()`                      | Whether it has settled; wake it after changing something it can't feel (a block placed beside it). |
| `apply_force(force)`                         | Pushes for the next tick: call it every tick for a steady push (a balloon, a current).             |
| `set_gravity(b)`, `has_gravity()`            | Turns gravity off, so the body floats and drifts at the speed it has.                              |
| `set_kinematic(b)`, `is_kinematic()`         | Moved only by the velocity a script gives it; the centity's other bodies hit it like a wall.       |
| `set_physics_enabled(b)`                     | Freezes it in place (solid to the centity's other bodies), keeping its velocity for later.         |

Every one of them is in world space, whichever way the centity faces (see
[Facing](centities.md#facing)): a push along +X moves the body east, and a
turned centity's bodies fall and collide with the blocks where they really
are. A point on the body comes from `node:to_world`:

```lua
-- Shove the top edge: it topples rather than slides.
crate:apply_impulse_at(vec3(0, 0, 4), crate:to_world(vec3(0.5, 1, 0.5)))
```

A sleeping body wakes by itself when something hits it, when a script pushes
it, or when what holds it up goes (checked about once a second).

Setting a node's translation or rotation from a script has the last word for
that tick, so you can also teleport a body; setting its rotation re-seeds the
body's orientation. Turning the whole centity (`set_yaw`) turns its moving
bodies with it: one sliding east on a centity turned a quarter to the right
slides south afterwards. On a node without `physics`, these methods return `false`
(or `vec3.zero`).

The [Node reference](../reference/node.md) has the details of each method.

## In the editor

The Inspector's **Physics** section edits these keys.
