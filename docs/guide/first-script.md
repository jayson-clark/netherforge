# Your first script

Scripts are Lua 5.4 files that sit next to the thing they belong to. A
centity can have one script; it runs once for every spawned instance.

## Attach a script

Open the `tower` centity from [the previous page](first-centity.md) and click
an empty spot in the viewport, so no node is selected. The Inspector shows the
**Centity** section. Next to **Script**, choose **New script** and keep the
suggested name, `script.lua`. The editor creates `centities/tower/script.lua`
and points the centity at it, at the top level of `centity.json`:

```json
"script": { "file": "script.lua" }
```

Lua never goes inside the JSON: the centity names the file, and the file is an
ordinary `.lua` you can open in any editor. Nodes don't have scripts of their
own; the centity's one script reaches them with `this:node("top")`.

## Write it

Open `script.lua`. The editor started it with one line; keep it, and write
below it:

```lua
local this = this --[[@as Centity]]

-- Clicking any part of the tower spins its top.
this:on("click", function(event)
  this:play_animation("spin")
  event.player:send_message("<gold>The tower turns.")
end)
```

- `this` is the centity instance the script runs on. The first line changes
  nothing when the script runs: it tells lua-language-server that `this` is a
  `Centity` here (see [External editors](external-editors.md#which-this)).
- `this:on("click", handler)` listens for an **event** on it: the handler runs
  whenever a player clicks one of its hitboxes. The script's body (everything
  at the top level) runs when the script starts, and that's where it registers
  its handlers.
- `this:play_animation("spin")` starts the animation you made.
- `event.player` is a [Player](../reference/player.md) handle; `send_message` sends
  them a chat line in [MiniMessage](https://docs.advntr.dev/minimessage/format.html).

The editor completes everything as you type (the API, and your project's
own names, like the `"spin"` animation), with the documentation from the
[Lua API reference](../reference/index.md), and underlines mistakes before
you save: a misspelt function, a field a value doesn't have.

Save. The dev server reloads the centity, and the script starts on every live
tower. Click one in game: the top spins and you get the message.

## When it goes wrong

Make a mistake on purpose: change `this:play_animation("spin")` to `this:play_animation("spni")`
and save, then click the tower.

The Console shows a script error naming `centities/tower/script.lua` and the
line, and clicking it opens the file there. An animation the centity doesn't
have is an error rather than a silent no-op, so typos are found where they
were made. The script keeps running (every click reports the error again, now
and then); fix the line and save, and it's right.

## Other events

```lua
-- The body runs every time the script starts: after spawning, a restart, or a reload.
log("tower ready: " .. this:id())

this:on("spawn", function()
  -- once, when the tower is first put into the world
end)

this:on("tick", function()
  -- every tick (20 a second); { every = 20 } for once a second
end)
```

[Scripting basics](scripting.md) explains when each event fires, the sandbox
and the instruction budget. [Modules](modules.md) are where server-wide code
goes: event listeners, commands and shared state.
