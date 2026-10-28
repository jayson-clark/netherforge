# Screens

A screen is a wall of maps that scripts draw on: pixels, lines, shapes, text
and pictures, and videos playing. Each map is 128 by 128 pixels, so a screen
of 4 by 3 maps is 512 by 384. It hangs in invisible item frames the server
puts on a wall (or nowhere, and players hold its maps as items). Each is a
folder, `screens/<id>/`, with its `screen.json` and the script that draws it.

```json
{
  "$schema": "../../.netherforge/schema/screen.schema.json",
  "width": 3,
  "height": 2,
  "background": "#101018",
  "place": { "world": "world", "x": 8, "y": 67, "z": -6, "facing": "south" },
  "viewers": "near",
  "range": 64,
  "glowing": true,
  "clickRange": 24,
  "video": { "video": "trailer", "loop": true, "volume": 0.8 },
  "script": { "file": "script.lua" }
}
```

| Key          | Meaning                                                                                                                                                                          | Default  |
| ------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- |
| `width`      | Maps across, 1 to 16.                                                                                                                                                            | required |
| `height`     | Maps down, 1 to 16.                                                                                                                                                              | required |
| `background` | What it's filled with before anything is drawn, `#rrggbb` (as the nearest colour a map can show).                                                                                | clear    |
| `place`      | Where it hangs (below). Left out, it hangs nowhere until a script places it.                                                                                                     | nowhere  |
| `viewers`    | Who it's drawn for: `near`, every player within `range` of it (everyone online while it hangs nowhere), or `script`, only those a script shows it to.                            | `near`   |
| `range`      | How near is near, in blocks: 1 to 256.                                                                                                                                           | 64       |
| `glowing`    | Whether its frames glow, drawing it at full brightness whatever the light.                                                                                                       | `true`   |
| `clickRange` | How far a click reaches it from, in blocks, 0 to 64 (below).                                                                                                                     | 24       |
| `video`      | A [video](video.md) it plays from the start: `video` names it, `loop` (default `true`) and `volume` (0 to 1, default 1).                                                         | none     |
| `script`     | Its script, `{ "file": "script.lua", "budget": 200000 }`, which draws it and hears its clicks (`this` is the screen); `budget` is the Lua instructions one call into it may use. | none     |

A side outside 1 to 16 maps is `screen.size`; a colour not written `#rrggbb`
is `screen.color`; `range` or `clickRange` out of bounds is `screen.range`;
a `volume` outside 0 to 1 is `screen.volume`; a video the project doesn't
have is `reference.video`.

## Where it hangs

`place` is `{ world, x, y, z, facing }`: the block its top left map hangs in
(the air in front of the wall, where an item frame goes) and the way it
faces, `north`, `south`, `east` or `west`: out of the wall, towards whoever
looks at it. Its other maps go to that player's right and down. The frames
are fixed and invisible, need nothing behind them, and nothing breaks, turns
or empties them. The server hangs them whenever their chunk is loaded and
never saves them with it, so a screen whose file moves leaves nothing behind.
A world name that isn't one is `screen.world`; two screens hanging in the
same block is `screen.place`, a warning.

## Drawing it

What a screen shows is the server's to send, and isn't saved: its script
draws it whenever it starts (every restart, every reload of its file). The
server sends each player only what changed since they were last sent it,
at most a few maps' worth a tick (`screens.bytes-per-tick` in the server's
`config.yml`), so drawing every tick costs only the pixels that change.

```lua
local this = this --[[@as Screen]]

this:clear("#101018")
this:text(this:width() / 2, 12, "<gold>Lobby", { align = "center", scale = 3 })
this:rectangle(8, 60, 200, 40, { fill = "#202040", outline = "#ffcc00", radius = 6 })
this:image(16, 16, "ui/logo")

this:on("click", function(event)
  this:circle(event.x, event.y, 3, { fill = "#ff4040" })
end)
```

Colours are `#rrggbb`, drawn as the nearest of the colours a map can show (a
map has about two hundred), or `"clear"`. Pictures are a resource pack's
textures, dithered into map colours as they're drawn. Text is in the game's
own map font, or a [screen font](screen-font.md) of the project's. The
reference has every function: `Screen`, `nf.screens` and `Playback`.

## Clicks and aim

A click on one of its frames is the screen's: its `click` event says the
pixel the player aimed at and which button. From further than the game's
reach, a swing of the arm aimed at it counts too, up to `clickRange` blocks
(0 is the game's reach alone). `hover` and `leave` follow where a player
aims, a pixel at a time, while someone listens. A block in front of the
screen is in the way, and only players it's drawn for can click it.

## Maps for good

A screen's maps are numbers the server never gives a map of its own, kept in
the server's store for good: a map item a script hands out
(`screen:item(column, row)`) or a frame a player hung shows the screen again
after a restart, as soon as its script draws it.
