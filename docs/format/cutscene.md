# Cutscenes

A cutscene is a camera path a script plays for one player. It lives at
`cutscenes/<id>.json`: one file, no script, nothing beside it. Scripts play it
by its id (`nf.cutscenes.play(player, "intro")`).

The camera is keyed the way a centity's animation is: **keys** at times in
seconds, each with an optional **easing** that shapes the segment leaving it,
on two tracks: where the camera is (`position`) and which way it looks
(`rotation`). **Cues** are things that happen at a time: an event a script
hears, a line of text for the player.

While it plays, the player is in spectator mode looking through a display
entity that the server moves along the path, one tick at a time, with the
game's own interpolation (the display's teleport duration), so the movement is
as smooth as the player's frame rate. They can't move or interact. When it
ends, however it ends, they're put back (see [what a play does to a
player](#what-a-play-does-to-a-player)).

```json
{
  "$schema": "../.netherforge/schema/cutscene.schema.json",
  "length": 6,
  "skippable": true,
  "camera": {
    "position": [
      { "time": 0, "value": [0, 6, -12] },
      { "time": 3, "value": [10, 8, 0], "easing": "ease_in_out" },
      { "time": 6, "value": [0, 10, 12], "easing": "ease_out" }
    ],
    "rotation": [
      { "time": 0, "yaw": 0, "pitch": 25 },
      { "time": 3, "yaw": 90, "pitch": 20, "easing": "ease_in_out" },
      { "time": 6, "yaw": 180, "pitch": 30 }
    ]
  },
  "cues": [
    { "time": 0.5, "text": "<gold>The village at dawn", "duration": 2 },
    { "time": 3, "event": "turn" }
  ]
}
```

Read it as: six seconds. The camera starts 12 blocks north of the origin, high
up, looking south and down; it swings out to the east (turning to look west),
easing in and out, and ends 12 blocks south of the origin looking north. Half a
second in, a subtitle shows for two seconds; at three seconds the cutscene's
`cue` event fires with `turn`. The player may skip it by sneaking.

| Key         | Meaning                                                                                            | Default             |
| ----------- | -------------------------------------------------------------------------------------------------- | ------------------- |
| `length`    | Seconds, more than 0 and at most 3600.                                                             | the last key or cue |
| `skippable` | Whether the player may end it early by sneaking. A script's `skippable` option wins.               | false               |
| `camera`    | The two tracks. Each needs at least one key.                                                       |                     |
| `cues`      | What happens when, in time order (the editor and the canonical writer keep them so). At most 4096. | none                |

## The camera

| Key                 | Meaning                                                                                                                                                                   |
| ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `camera.position[]` | `{ time, value: [x, y, z], easing? }`: where the camera is, in world coordinates, or measured from the `origin` a script plays it at.                                     |
| `camera.rotation[]` | `{ time, yaw, pitch, easing? }`: degrees. Yaw 0 faces south, 90 west; any number, the camera turns the short way round. Pitch is -90 (straight up) to 90 (straight down). |

A track's keys are in time order (written so), at most one at a time, from 0 to
the cutscene's length. Before its first key the camera holds the first key's
pose; after its last, the last. `easing` is the same set as a centity
animation's (`linear`, `step`, `ease_in`, `ease_out`, `ease_in_out`) and shapes
the segment from that key to the next; `step` holds this key until the next.
Position and rotation are separate tracks, so they may have different keys.

Positions may be at most 30 million blocks from the origin, and a track has at
most 4096 keys.

## Cues

| Key        | Meaning                                                                                    | Default |
| ---------- | ------------------------------------------------------------------------------------------ | ------- |
| `time`     | Seconds, from 0 to the cutscene's length. Required.                                        |         |
| `event`    | A name (an id: lowercase letters, digits, `_`) scripts hear as the cutscene's `cue` event. |         |
| `text`     | MiniMessage shown to the player as a subtitle.                                             |         |
| `duration` | Seconds the text stays, more than 0. Only with `text`.                                     | 3       |

A cue has an `event`, `text`, or both. The text is a title with no title line, so it
can use [glyphs](resource-pack.md) like any MiniMessage.

## What a play does to a player

The player's game mode, position, and whether they could fly and were
flying are saved when the first of a run of cutscenes starts, and put back when
it ends: at its end, or when a script stops it, when the player sneaks out of
a skippable one, when they leave the server (their state is restored before
the server saves them), when the script that played it is reloaded or removed,
and when the server stops or the project reloads. A player who was riding is
dismounted for the camera and isn't put back on their ride. A cutscene played
for a player who is already watching one replaces it without putting them back
in between.

What a player was is also kept on disk while the cutscene holds them. A
server that crashes or is killed mid-cutscene can't put anyone back then, so
it does when they next join, before any script hears that they joined.

## Problems

Every rule has a code in the [problem catalogue](problems.md#cutscenes).
