# Particle effects

A particle effect is a little timeline of particles you design in the editor
and play from a script: a burst when a chest opens, a ring that grows from a
landing, a halo that follows a player. It lives in `particles/<id>/` as one
`effect.json`, with no script of its own. The
[particle effect format](../format/particle-effect.md) has every key.

```
particles/
  sparkle/
    effect.json
  shockwave/
    effect.json
```

For a single particle now and then, `world:spawn_particle` is simpler (see
[the world](./world.md#particles)); an effect is for anything with a shape or
that changes over time.

## Designing one

Open an effect and the editor shows its emitters on the left, a preview in the
middle, the selected emitter's fields on the right and the timeline along the
bottom. Each **emitter** spawns one kind of particle: in bursts (`burst`, and
`every` to repeat it) or at a rate (`rate` a tick), between its `start` and
`end` ticks, placed on a **shape** (a point, a line, a ring, a disc, a sphere
or a box) and moving outward, inward, along a direction, or scattered the
game's own way. A property that changes over the effect (the rate, a dust's
size or colour, the speed, a ring's radius) is a **curve**: double-click its
timeline row to add a key at the playhead.

The preview is an approximation: the editor knows exactly where every particle
is spawned, but how long it lives and how it drifts is the game's business.
**Play on server** (with the dev server running) plays the effect in front of
you in the game, looping when the timeline's loop toggle is on; **Stop** ends
it. A changed effect is saved first, so the game plays what you see.

## Playing one

```lua
-- a centity's script
this:on("click", function(event)
  nf.particles.play("sparkle", this:location())
end)
```

`nf.particles.play(effect, location_or_position, options?)` starts the effect
and returns an `Effect`. A `Location`'s yaw and pitch turn the effect (its +Z
is where the location faces); a bare `Vec3` plays it in the script's own world
(a centity's, or the server's first world). Options:

| Option    | Meaning                                                                                  |
| --------- | ---------------------------------------------------------------------------------------- |
| `viewers` | only these players see it (it's still only drawn for players near enough)                |
| `scale`   | multiplies its distances: shapes, offsets, spreads and speeds (0 to 16, default 1)       |
| `loop`    | play it again from the start when it ends, until it's stopped (default: the file's loop) |
| `follow`  | a centity, node, player or other entity to follow (see below)                            |
| `offset`  | with `follow`: where the effect sits from what it follows                                |

Players see an effect within 32 blocks of it (128 for an emitter marked
`force`), the same distances the game itself draws particles at. Nothing is
sent to anyone further away, and an effect never loads a chunk.

An effect you don't loop ends by itself. One that loops plays until you call
`effect:stop()`, until the script that played it unloads (a reload, its
centity removed, the server stopping), or until its file is deleted. More than
1024 effects playing at once is an error: a looping effect nobody stops is a
leak.

## Following something

An aura or a trail follows what it belongs to:

```lua
-- a module: a looping sparkle above anyone who joins, until they leave
nf.on("player_join", function(event)
  nf.particles.play("sparkle", event.player:location(), {
    follow = event.player,
    offset = vec3(0, 2.2, 0),
    loop = true,
  })
end)
```

Each tick the effect moves to the target and turns with its yaw, so `offset`
is in the effect's own space: `vec3(0, 2, 0)` is above, `vec3(0, 0, 1)` in
front. `effect:follow(target, offset?)` starts (or switches) following later,
and `effect:teleport(location_or_position)` moves it once and stops it
following. It can follow a centity, one of its nodes, a player or any other
entity (an `Entity` handle: a pet, a minecart). When what it follows is gone
(a player who left, a removed centity, an entity that died or unloaded), the
effect ends.

## Waiting for one to end

An effect's `end` event says why it ended (`event.reason` is `"finished"`,
`"stopped"`, `"target_gone"`, `"removed"` or `"unloaded"`), and a task can wait
for it:

```lua
-- a centity's script: a shockwave, then the lid opens once it's over
this:node("lid"):on("click", function(event)
  nf.task(function()
    local effect = nf.particles.play("shockwave", this:location())
    nf.wait_for(effect, "end")
    this:play_animation("open")
  end)
  event:stop()
end)
```

## Hot reload

Saving an effect changes every copy that's playing at its next tick, carrying
on from the tick it was at, so you can tune a looping effect while you watch
it. An effect whose file has errors keeps playing its last good version until
you fix it. Deleting the file ends its copies.

## Limits

An effect may spawn at most 256 points a tick (the editor says when one could
go over), and the server sends at most 4096 a tick across every effect. Past
that, the effects that would go over are skipped that tick (their points for
that tick are dropped, not sent later), taking turns so the same effects
aren't always the ones skipped. The console and the editor say so once every
ten seconds, and the editor's problems list it while it goes on.

See the [Effect reference](../reference/effect.md) and
[`nf.particles`](../reference/nf.particles.md).
