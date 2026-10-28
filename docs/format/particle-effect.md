# Particle effects

A particle effect is a timeline of particle spawns, played at a place by
scripts. It lives at `particles/<id>/effect.json`. There's nothing else in
the folder and no script: an effect is data, and scripts play it by its id.

An effect is `duration` ticks long and has named **emitters**. On each tick,
each active emitter decides how many **points** to spawn (a burst, or a
rate), places each one on its **shape**, gives it a **motion**, and sends one
Minecraft particle spawn per point. Properties that change over time (rate,
size, colour, speed, radius) are **curves**: keyframes read at the tick a
point spawns.

Nothing changes a particle after it spawns. Minecraft particles are
fire-and-forget: the game client owns their lifetime, gravity and fade. "Over
time" means across successive spawns.

```json
{
  "$schema": "../../.netherforge/schema/particle_effect.schema.json",
  "duration": 30,
  "emitters": {
    "flash": { "particle": "minecraft:flash", "burst": 1 },
    "ring": {
      "particle": "minecraft:dust",
      "end": 20,
      "burst": 48,
      "every": 5,
      "shape": { "type": "ring" },
      "distribution": "even",
      "offset": [0, 0.1, 0],
      "size": 1.5,
      "curves": {
        "radius": [
          { "time": 0, "value": 0.5 },
          { "time": 20, "value": 4, "easing": "ease_out" }
        ],
        "color": [
          { "time": 0, "color": "#ffd34d" },
          { "time": 20, "color": "#ff3b1f", "easing": "ease_in" }
        ]
      }
    },
    "sparks": {
      "particle": "minecraft:end_rod",
      "start": 2,
      "rate": 3,
      "shape": { "type": "sphere", "radius": 0.3 },
      "motion": "outward",
      "speed": 0.15,
      "curves": {
        "rate": [
          { "time": 2, "value": 6 },
          { "time": 30, "value": 0 }
        ]
      }
    }
  }
}
```

Read it as: a flash at tick 0; a dust ring of 48 evenly spaced points every 5
ticks until tick 20, growing from 0.5 to 4 blocks and fading from gold to
red; end-rod sparks flying outward from tick 2, thinning out over the effect.

| Key        | Meaning                                                                    | Default |
| ---------- | -------------------------------------------------------------------------- | ------- |
| `duration` | Ticks, 1–72000. Required.                                                  |         |
| `loop`     | Start again at tick 0 after the last tick, until stopped.                  | false   |
| `emitters` | Name → emitter. Names are letters, digits, `_` and `-`. Run in name order. | none    |

An effect with no emitters is a warning: it shows nothing.

## Emitters

| Key            | Meaning                                                                                                                        | Default     |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------ | ----------- |
| `particle`     | A particle id, `minecraft:end_rod` (or `end_rod`). Required. It must be one the server knows, taking options effects can send. |             |
| `start`        | The first tick it's active, from 0 to before `duration`.                                                                       | 0           |
| `end`          | The tick it stops at (not included). After `start`, at most `duration`.                                                        | `duration`  |
| `burst`        | Points per burst, 1–256. Exactly one of `burst` and `rate`.                                                                    |             |
| `every`        | Repeat the burst every this many ticks from `start`, while before `end`. Only with `burst`. Without it, one burst at `start`.  |             |
| `rate`         | Points per tick, more than 0 and at most 256. Fractions add up: `0.25` is one point every 4 ticks.                             |             |
| `shape`        | Where the points go (below).                                                                                                   | a point     |
| `distribution` | `random`, or `even` spacing: only for `line`, `ring`, and `sphere` with `surface`.                                             | `random`    |
| `spin`         | Degrees per tick the shape turns about its up axis as the effect plays. Only for `ring`, `disc` and `line`.                    | 0           |
| `offset`       | The emitter's origin in the effect, `[x, y, z]` blocks.                                                                        | `[0, 0, 0]` |
| `rotation`     | `[x, y, z]` degrees, X then Y then Z (as in [centities](centity.md)). Turns the shape and directions.                          | `[0, 0, 0]` |
| `motion`       | How each particle moves (below).                                                                                               | `random`    |
| `direction`    | `[x, y, z]`, not zero. Required with `motion: "direction"`, and only allowed there.                                            |             |
| `count`        | Minecraft particles per point, 1–100. Only with `motion: "random"`.                                                            | 1           |
| `spread`       | Minecraft's Gaussian spread around each point, `[x, y, z]` blocks, none negative. Only with `motion: "random"`.                | `[0, 0, 0]` |
| `speed`        | Not negative. Random speed with `motion: "random"`, speed along the direction otherwise.                                       | 0           |
| `force`        | Shown even to players who turned particles down, and up to 128 blocks away instead of 32.                                      | false       |
| `color`        | `#rrggbb`, lowercase.                                                                                                          | `#ffffff`   |
| `toColor`      | `#rrggbb` a dust transition fades to.                                                                                          | `#ffffff`   |
| `size`         | Dust size, 0.01–4.                                                                                                             | 1           |
| `blockState`   | The block a block particle shows: `minecraft:stone`, with properties if wanted.                                                |             |
| `item`         | The item an item particle shows: an [item](menu.md#items).                                                                     |             |
| `curves`       | Values over time (below).                                                                                                      |             |

### Particle options

What a particle can be given depends on its type, which is a fact about the
game (the editor learns it from the dev server):

| The particle takes | Its keys                     | Examples                          |
| ------------------ | ---------------------------- | --------------------------------- |
| nothing            | none                         | flame, end rod, flash             |
| dust               | `color`, `size`              | dust                              |
| a dust transition  | `color`, `toColor`, `size`   | dust colour transition            |
| a colour           | `color`                      | entity effect, tinted leaves      |
| a block            | `blockState` (required)      | block, falling dust, block marker |
| an item            | `item` (required)            | item                              |
| anything else      | can't be played in an effect | vibration, trail, shriek          |

A key the particle doesn't take is an error at that key ("minecraft:flame
takes no color"). Until the editor has the game's data, which particles take
what isn't known, so those checks wait.

## Shapes

A shape has a `type`. It sits on the emitter's `offset`, turned by its
`rotation`, with +Y up.

| `type`   | Keys                                             | Points                                                                                                     |
| -------- | ------------------------------------------------ | ---------------------------------------------------------------------------------------------------------- |
| `point`  | none                                             | the origin                                                                                                 |
| `line`   | `to`: `[x, y, z]`, not zero. Required.           | on the line from the origin to `to`; `even` spaces them end to end (one point: the middle)                 |
| `ring`   | `radius`, more than 0                            | on the circle around +Y; `even` spaces them round it                                                       |
| `disc`   | `radius`, more than 0                            | inside the circle, evenly by area                                                                          |
| `sphere` | `radius`, more than 0; `surface` (default false) | inside the ball, evenly by volume; with `surface`, on its shell; `even` with `surface` spaces them over it |
| `box`    | `size`: `[x, y, z]`, each more than 0; `surface` | inside the box, centred on the origin; with `surface`, on its faces                                        |

A ring, disc or sphere needs a `radius` or a `radius` curve, and not both.

## Motion

How a point's particle moves is up to the game, through the two numbers a
particle spawn carries. `motion` picks one of the ways the game can use them:

| `motion`    | The particle                                                                                    |
| ----------- | ----------------------------------------------------------------------------------------------- |
| `random`    | `count` particles scattered by `spread`, drifting at random up to `speed`: the game's own look. |
| `outward`   | flies away from the shape's centre at `speed` (up the shape's axis if it's at the centre).      |
| `inward`    | flies towards the centre.                                                                       |
| `direction` | flies along `direction` (turned by `rotation`) at `speed`.                                      |

Some particles ignore the speed they're given (dust drifts on its own, many
block particles fall). That's the game client's behaviour, not something an
effect can change, which is also why `count` and `spread` are only allowed
with `random`: the game ignores them otherwise.

## Curves

```json partial
"curves": {
  "rate": [{ "time": 0, "value": 6 }, { "time": 30, "value": 0 }],
  "color": [{ "time": 0, "color": "#ffd34d" }, { "time": 20, "color": "#ff3b1f", "easing": "ease_in" }]
}
```

| Channel  | Keys                            | Allowed when                        |
| -------- | ------------------------------- | ----------------------------------- |
| `rate`   | `{ time, value }`, value 0–256  | the emitter uses `rate`             |
| `size`   | `{ time, value }`, value 0.01–4 | the particle takes a size           |
| `speed`  | `{ time, value }`, not negative | always                              |
| `radius` | `{ time, value }`, more than 0  | the shape is a ring, disc or sphere |
| `color`  | `{ time, color }`, `#rrggbb`    | the particle takes a colour         |

- `time` is a tick on the **effect's** timeline (one for the whole effect, not
  per emitter), from 0 to `duration`. Two keys at the same tick in a channel
  are an error. Keys are written in time order.
- Each key can have an `easing` (`linear`, `step`, `ease_in`, `ease_out`,
  `ease_in_out`) that shapes the stretch after it, as in centity animations.
  Default `linear`.
- Before the first key the first value holds; after the last, the last.
  Colours blend channel by channel.
- A curve replaces its key: `size` beside a `size` curve, or a shape's
  `radius` beside a `radius` curve, is an error. The exception is `rate`,
  which says the emitter is rate-driven: with a `rate` curve, the curve gives
  the value.
- A channel with no keys is the same as no channel. `toColor` has no curve: a
  transition already fades.

## Limits

An effect can spawn at most **256 points in a tick**, counted at its worst:
each burst emitter's `burst` plus each rate emitter's highest rate (its
`rate` or its curve's highest key), rounded up. More is an error.

On the server, everything playing at once shares 4096 points a tick; past
that, effects skip a tick in turn. Players see an effect within 32 blocks
(128 for a `force` emitter).
