# Cutscenes

A cutscene is a camera path you key in the editor and play for one player from
a script: a flyover when someone enters a town, a pan across a boss arena, a
slow walk through a dungeon door. It lives in `cutscenes/<id>.json`, with no
script of its own. The [cutscene format](../format/cutscene.md) has every key.

```
cutscenes/
  flyby.json
  boss_intro.json
```

## Keying one

Open a cutscene and the editor shows the camera's path in 3D, with the
timeline along the bottom. The path is a line through the camera's **position
keys** (the gold markers; click one to select it), with the camera itself
drawn as a frustum at the playhead. The timeline has three rows:

- **position** is where the camera is. Each key is a point and an **easing**
  for the stretch that follows it (`ease_in_out` starts and ends slowly; `step`
  jumps).
- **rotation** is which way it looks (yaw and pitch, in degrees). It has keys
  of its own, so it can turn while the camera holds still.
- **cues** are things that happen at a time: an event your script hears, a line
  of text for the player, or both.

Move the playhead (drag the ruler, the arrow keys, or **Play**) and click **Key
position** or **Key rotation**: the camera is keyed where it is at that moment,
so a first key makes a point and later keys bend the path. Drag a key to retime
it, select it to change its value and easing beside the timeline, **Delete**
removes it. **Add cue** puts a cue at the playhead. The preview follows exactly the
path the player's camera will, so what you see is what they see.

The path's coordinates are the world's by default. Key a cutscene around the
origin and play it with an `origin` to use it anywhere (the example's `flyby`
does).

## Playing one

```lua
nf.commands.register("intro", { players_only = true }, function(event)
  local player = event.player
  local scene = nf.cutscenes.play(player, "flyby", { origin = player:location().position })
  if scene then
    scene:on("cue", function(cue)
      log("reached", cue.cue)
    end)
    scene:on("end", function(done)
      done.player:send_message("That was the village (" .. done.reason .. ")")
    end)
  end
end)
```

`nf.cutscenes.play(player, id, options?)` starts it and returns a `Cutscene`
(or `nil` when the player is offline). Options:

| Option      | Meaning                                                                                                      |
| ----------- | ------------------------------------------------------------------------------------------------------------ |
| `origin`    | where the path's coordinates are measured from (a `Location` also names the world; a `Vec3` is the player's) |
| `skippable` | whether the player may end it by sneaking (default: the file's)                                              |

While it plays the player is in spectator mode looking through a camera that
moves along the path with the game's own interpolation, so it's smooth. They
can't move or interact. When it ends, however it ends, their game mode, where
they stood and whether they could fly are put back:

| `event.reason` | What ended it                                                 |
| -------------- | ------------------------------------------------------------- |
| `finished`     | it played to its end                                          |
| `stopped`      | `scene:stop()` or `nf.cutscenes.stop(player)`                 |
| `skipped`      | the player sneaked, in a skippable cutscene                   |
| `replaced`     | another cutscene was played for the player                    |
| `player_left`  | the player left the server                                    |
| `unloaded`     | the script that played it was reloaded, or the server stopped |

The `end` event is heard after the player is put back, so a handler that
teleports them or changes their game mode has the last word. A cutscene played
over another keeps the state saved before the first, so a chain of them never
flashes the world between. `scene:time()` says how far along it is,
`nf.cutscenes.current(player)` what a player is watching.

A player who was riding something is dismounted for the camera and isn't put
back on it. A skippable cutscene ends when the player sneaks; one that isn't
holds them in the camera however they try to leave it.

## In a package

A package exports its cutscenes like any resource (`exports.cutscenes`), and a
project plays one as `nf.cutscenes.play(player, "acme:intro")`.
