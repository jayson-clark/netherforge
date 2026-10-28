# Screens and videos

A screen is a wall of maps your scripts draw on: a scoreboard in the lobby, a
minimap, a shop sign that changes with the stock, a menu players click with
the mouse, or a cinema playing a video with its sound. Each map is 128 by 128
pixels; a screen is up to 16 by 16 of them.

```
screens/
  lobby_board/
    screen.json
    script.lua
screen_fonts/
  pixel/
    font.json
videos/
  trailer/
    video.json
    trailer.mp4
```

The [screen format](../format/screen.md), [screen fonts](../format/screen-font.md)
and [videos](../format/video.md) have every key, and the reference has every
function: `nf.screens`, `Screen` and `Playback`.

## Making one

Make a screen from the explorer, then set its size in maps and where it hangs:
the block its top left map hangs in (the air in front of a wall) and the way it
faces. The server hangs invisible frames there and keeps them there. Its script
draws it:

```lua
local this = this --[[@as Screen]]

this:clear("#101018")
this:text(this:width() / 2, 12, "<gold>Welcome!", { align = "center", scale = 3 })
this:rectangle(16, 60, 220, 40, { fill = "#20203a", outline = "#ffcc00", radius = 6 })
this:text(28, 72, "Play", { scale = 2 })
this:image(this:width() - 80, 16, "ui/logo", { width = 64 })
```

What a screen shows isn't saved, so its script draws it each time it starts.
Draw as often as you like: each player is sent only the pixels that changed.

Colours are `#rrggbb`. A map can show about two hundred colours, so each is
drawn as the nearest of them (`nf.screens.map_color` says which); pictures are
dithered as they're drawn, so photos still look right. Text is MiniMessage
(colours, bold, underline, strikethrough), in the game's own map font or a
screen font of yours: draw a pixel font right in the editor, or use a picture
of a font's characters, or a TrueType file.

## Clicks and the cursor

```lua
this:on("click", function(event)
  if event.x >= 16 and event.x < 236 and event.y >= 60 and event.y < 100 then
    event.player:send_message("Let's go!")
  end
end)

this:on("hover", function(event)
  -- event.x, event.y: the pixel this player aims at, a tick at a time
end)
```

A click on a frame is the screen's, with the pixel the player aimed at. From
further away, a swing of the arm aimed at the screen counts too, up to its
click range (24 blocks unless the file says). `hover` and `leave` follow where
each player aims, which is how a menu lights a button up under the cursor.

## One player's own view

`screen:for_player(player)` is a player's own view of a screen: the same maps
in the same frames, drawn differently for them alone. Draw their name, their
score, their own minimap; everyone else sees the screen as it is.

```lua
nf.on("player_join", function(event)
  local mine = this:for_player(event.player)
  mine:text(8, 110, "Hello, " .. event.player:name())
end)
```

## Screens a script makes

```lua
local scores = nf.screens.create({ width = 2, height = 1, key = "scores" })
scores:place(nf.worlds.default():location(vec3(0, 70, 0)), "south")
player:give_item(scores:item())   -- a map in hand shows it too
```

A script's screen goes when its script unloads. With a `key`, its maps keep
their numbers for good, so a map item you handed out still shows it after a
restart, once a script makes it again.

## Videos

Make a video, put the film in its folder (any file ffmpeg reads), set its size
in maps and its rate, and click **Convert**. The editor needs
[ffmpeg](https://ffmpeg.org/) on your `PATH` (or set where it is in Settings →
Server) and the game data of your Minecraft version (start the dev server
once). It writes the frames, already in map colours, and the sound into the
resource pack. Commit them with the project; the film itself needn't be.

```lua
local show = this:play("trailer", { loop = true, volume = 0.8 })
show:on("end", function(event) log("ended:", event.reason) end)
-- show:pause(), show:resume(), show:seek(12), show:set_speed(2)
```

The sound plays to the players near the screen. Minecraft can only start a
sound from its beginning, so after a pause or a seek it comes back at the next
five-second mark.

## How much it costs

A whole map is 16 KiB, and the server sends each player at most 64 KiB of
screens a tick (`screens.bytes-per-tick` in `config.yml`). Drawing a few pixels
costs a few bytes; a 4 by 3 video at 20 frames a second changes up to 192 KiB a
frame, so a big video drops frames for a player whose share it outruns. Keep
videos small, or slower, for many players at once.
