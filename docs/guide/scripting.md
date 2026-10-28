# Scripting basics

NetherForge scripts are [Lua 5.4](https://www.lua.org/manual/5.4/), running
inside the server. Any Lua tutorial applies, minus a few things the
[sandbox](performance.md#the-sandbox) takes away. This page covers where
scripts live, when they run, and the everyday tools: events, handles, errors,
time, saved data and `require`. The [Lua API reference](../reference/index.md)
lists every function.

## Where scripts live

| Script               | File                                                        | Runs                                                  |
| -------------------- | ----------------------------------------------------------- | ----------------------------------------------------- |
| A **centity** script | `centities/<id>/*.lua`, named by `script` in `centity.json` | once per spawned instance, with `this` = the instance |
| A **menu** script    | `menus/<id>/*.lua`, named by `script` in `menu.json`        | once per window, with `this` = the window             |
| A **dialog** script  | `dialogs/<id>/*.lua`, named by `script` in `dialog.json`    | once for the whole server, with `this` = the dialog   |
| An **item** script   | `items/<id>/*.lua`, named by `script` in `item.json`        | once for the whole server, with `this` = the item     |
| A **block** script   | `blocks/<id>/*.lua`, named by `script` in `block.json`      | once for the whole server, with `this` = the block    |
| A **module**         | `modules/<id>/init.lua` and its other files                 | once for the whole server                             |

**One script per resource.** A centity, menu, dialog, item or block has at
most one script, and its own handle is `this`. The parts inside it have no
scripts of their own: the script reaches them through `this`
(`this:node("lid")`, `this:slot(13)`, `this:button("done")`) and listens on
them there. A long script can be split into more `.lua` files beside it,
which it loads with [`require`](#require). Behaviour several resources share
goes in a [module](modules.md).

Every script the editor creates starts with a line saying which `this` it is:

```lua
local this = this --[[@as Centity]]
```

It changes nothing when the script runs; it tells the editor what `this` is,
so completions and checks are exact. Keep it.

This page uses centity scripts as the example; [Menus](menus.md),
[Dialogs](dialogs.md), [Items](items.md) and [Custom blocks](blocks.md) cover
theirs. The [Scripts](../reference/scripts.md) reference lists what each kind
of script can see.

Each instance's script has **its own variables**: two towers' scripts don't
see each other's. A top-level `local` in a centity's script lives as long as
that instance's script does, which makes it the place for per-instance state:

```lua
local this = this --[[@as Centity]]

local clicks = 0

this:on("click", function(event)
  clicks = clicks + 1
  event.player:send_message("Clicked " .. clicks .. " times")
end)
```

All of one module's files share one set of globals.

## The body, and events

**A script's body runs every time it starts**: when its centity is spawned,
after a server restart, and after you save a change to it. That's where it
sets up its nodes and registers its handlers. Variables aren't kept between
runs, so the body sets everything up again each time (see
[Saving data](#saving-data) for what must last).

Everything else is an **event on the thing it's about**: `this:on("tick", ...)`,
`this:node("lid"):on("click", ...)`. A centity has these, among others:

| Event                        | When                                                                                                |
| ---------------------------- | --------------------------------------------------------------------------------------------------- |
| `spawn`                      | Once, when the instance is first put into the world, after the body. Not after a restart or reload. |
| `tick`                       | Every tick (20 a second) while its chunk is loaded; `{ every = n }` for every `n` ticks.            |
| `click`                      | A player clicked one of its hitboxes, after the node clicked and the nodes above it.                |
| `animation_start`            | An animation started.                                                                               |
| `animation_end`              | A `once` clip finished, or a `hold` clip reached its last pose.                                     |
| `chunk_load`, `chunk_unload` | Its chunk came back, or went (it does nothing until it's back).                                     |
| `remove`                     | The instance is being removed for good.                                                             |

The [Centity reference](../reference/centity.md#events) lists them all. A node
has `click` (which goes on to its parent, then the centity, then
`nf.on("centity_click")`), and, for a physics body, `collide`, `wake` and
`sleep`.

```lua
this:on("spawn", function()
  this:play_animation("rise")
end)

this:on("tick", function(event)
  this:node("blade"):rotate(vec3.up, 6)
end, { every = 2 })
```

A handler gets one **event object**: the event's fields, plus `event:stop()`
(nothing later hears it), `event:cancel()` (cancel what caused it, for
cancellable events) and `event.name`. **What a handler returns means
nothing.** `:on` returns a subscription (`subscription:cancel()` stops it),
and `:once` listens for the next one only.

Modules have no `this`. They listen with `nf.on` and add commands with
`nf.commands.register`; see [Modules and commands](modules.md). Any script can
listen to server-wide [events](../reference/events.md) with `nf.on`, including
`unload`, which runs just before the script itself stops. A handler lives as
long as both the script that registered it and the thing it's on.

## Handles

Things in the world come to scripts as **handles**: a
[Centity](../reference/centity.md), a [Node](../reference/node.md), a
[Player](../reference/player.md), a [World](../reference/world.md), a
[Menu](../reference/menu.md) window and so on. Call their methods with a colon
(`player:send_message("hi")`); `nf` and its namespaces take a dot
(`nf.centities.spawn(...)`).

A handle is safe to keep. If the thing it names goes away (a player logs off,
a centity is removed), its methods answer `nil` or `false` instead of
erroring, and a player's handle works again when they come back. Two handles
to the same thing are equal: `a == b`.

## Errors

NetherForge separates mistakes from conditions:

- **A typo is an error**: an event name that doesn't exist, a centity id the
  project doesn't have, an animation the centity lacks, an argument of the
  wrong type. The error points at your line.
- **A condition is `nil` or `false`**: a player who left, a removed centity, a
  block state the server doesn't have, a file path outside the data folder.

An error in a script's **body** switches that script off until you save it
again, because its setup didn't finish. An error in a **handler, timer or
command** is reported, and the script keeps running; the other handlers for
that event still run. Either way it's reported with the file, the line and a
traceback: in the editor's Console (with a link to the line) and the Problems
panel, and in the log on a server. The same error over and over is reported
only now and then, and a handler (or an `nf.every` timer) that fails 20 times
in a row is cancelled, with a line saying so.

Use `pcall` for things you expect might fail. See
[Debugging scripts](debugging.md) for breakpoints and stepping.

## Time

Time in scripts is counted in **ticks**: 20 a second. `nf.after` and
`nf.every` schedule in them, and `nf.server.tick()` counts them:

```lua
nf.after(20 * 5, function() nf.server.broadcast("<gold>Five seconds in") end)

local timer = nf.every(20, function() log("another second") end)
-- later: timer:cancel()
```

To do several things with pauses in between, start a task and wait in it; see
[Waiting](modules.md#waiting).

For dates and the wall clock, `nf.server.unix_time()` is milliseconds since
1970, for timestamps that must outlive a restart (a daily reward, a saved
cooldown). [`nf.time`](../reference/nf.time.md) turns them into text and back:
`nf.time.format`, `nf.time.parse`, and `nf.time.duration` /
`nf.time.parse_duration` for lengths like `"1h 30m"`.

To run something at a time of day, use [`nf.schedule`](../reference/nf.schedule.md):

```lua
nf.schedule.daily("18:00", function() nf.server.broadcast("Daily reward!") end)
nf.schedule.weekly("sat", "18:00", function() end)
nf.schedule.cron("*/30 * * * *", function() end) -- every half hour
```

Times are in the server's time zone, which the server owner sets
([Server configuration](server-config.md#time-zone-for-schedules)), not the
player's. A run missed while the server was off is skipped, unless you ask
for it to catch up:

```lua
nf.schedule.daily("04:00", restock, { id = "restock", catch_up = true })
```

With an `id`, NetherForge remembers when the schedule last ran, and
`catch_up = true` runs it once when the script starts if a run was missed. On
the days clocks change, a time that's skipped runs just after the jump
(`"02:30"` runs at 03:30), and a time that happens twice runs once.

## Randomness and maths

`math.random` works as usual, and is seeded afresh each time the server
starts. All scripts share that one generator, so for numbers that repeat for
a seed, make one of your own with `nf.random.new(seed)`: `rng:integer(1, 6)`,
`rng:pick(list)`, `rng:weighted({ common = 3, rare = 1 })`.
[`nf.random`](../reference/nf.random.md) also has smooth noise (`noise2`,
`noise3`) and `uuid()`, and [`nf.math`](../reference/nf.math.md) has `lerp`,
`clamp`, `remap` and `ease` for animating by hand.

Positions, directions and offsets are [`Vec3`](../reference/vec3.md) values:
`vec3(x, y, z)`, with `+`, `-` and `*`, `:length()`, `:normalize()` and more.
A [`Location`](../reference/location.md) is a position in a particular world,
with a facing.

## Saving data

Nothing in Lua survives a restart on its own. For what must, ask for a saved
table: a plain Lua table NetherForge saves for you and gives back after a
restart.

| Call             | One table per                                   | Kept                                                   |
| ---------------- | ----------------------------------------------- | ------------------------------------------------------ |
| `centity:data()` | centity instance                                | with the instance, and removed with it                 |
| `player:data()`  | player, shared by every script                  | per player, also while they're offline                 |
| `nf.data(name)`  | name, for the whole project (`nf.data("shop")`) | on the server, until a script clears it                |
| `block:data()`   | block position, shared by every script          | with the block ([The world](world.md#data-on-a-block)) |

```lua
-- modules/greeter/init.lua
nf.on("player_join", function(event)
  local data = event.player:data()
  -- Everyone shares a player's table, so keep yours under your module's name.
  data.greeter = data.greeter or {}
  data.greeter.visits = (data.greeter.visits or 0) + 1
end)
```

The table is live: change it and the change is saved. NetherForge saves
changed tables every five minutes, when a player leaves (theirs) and when the
server stops. Saving a script you're working on doesn't reset its table.

What a table can hold:

- strings, numbers (an integer stays an integer), booleans, and tables of
  them, keyed by strings or numbered from 1;
- `Vec3`s and `Location`s;
- `Item` tables;
- handles to entities (a `Player`, a `Mob`, any kind of entity), centities and
  worlds: `data.owner = player` just works, and comes back as a handle after
  a restart.

Anything else (a function, a `Menu` window, a subscription) is left out when
the table is saved, with a line in the log saying where it was:
`data.inventory[3]: a function can't be saved`. A table over 1 MiB isn't
saved, and the log says so.

An item's `data` field follows the same rules, but is checked when you set it,
so something it can't keep is an error at that line. [`nf.json`](../reference/nf.json.md)
and a file's `read_json`/`write_json` use the very same rules, so a `Vec3` or
a player you encode comes back as one.

For bigger or more structured data, a package can have a SQLite database of
its own: see [`nf.db`](../reference/nf.md#db) and
[Migrations](../format/migrations.md).

### Files

For anything else, [`nf.files.get`](../reference/nf.files.md) gives you files
in a folder of your own (`plugins/NetherForge/data/` on the server), which
survive restarts. Files are capped at 1 MiB.

```lua
local log_file = nf.files.get("chat.log")

nf.on("player_chat", function(event)
  log_file:append(event.player:name() .. ": " .. event.message .. "\n")
end)
```

## `require`

`require` loads project modules and their files, the files beside a
resource's script, and nothing else:

| Call                                         | Loads                                                                                                                 |
| -------------------------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| `require("greeter")`                         | `modules/greeter/init.lua`                                                                                            |
| `require("greeter.messages")`                | `modules/greeter/messages.lua` (or `messages/init.lua`)                                                               |
| `require("util")`, inside a module           | that module's own `util.lua` first, then a module named `util`                                                        |
| `require("helpers")`, in a resource's script | `helpers.lua` (or `helpers/init.lua`) in the script's folder first, like `centities/tower/helpers.lua`, then a module |
| `require("acme:api")`                        | the module `api` of the [package](../format/packages.md) `acme` the project depends on, if `acme` exports it          |

Dots are folders: `require("ai.steering")` in a centity's script is
`ai/steering.lua` beside it.

A **module** runs **once**, and everyone who requires it gets the same value
back: the running module, state and all.

A **file beside a resource's script** runs as part of that script, with its
`this`, once for each copy of the script. Every centity instance, menu window
and dialog gets its own copy, as if the file's code were written in the
script itself, so a local in it is per instance:

```lua
-- centities/tower/turns.lua
local turns = {}
local count = 0 -- this tower's own

function turns.spin()
  this:play_animation("spin")
  count = count + 1
  return count
end

return turns
```

```lua
-- centities/tower/script.lua
local turns = require("turns")
this:on("click", function(event)
  event.player:send_actionbar("Turns: " .. turns.spin())
end)
```

A script can't require its own file, and circular requires are an error. When
a module is saved, everything that required it restarts too, so nobody keeps
an old copy.

## Text

Text players see (`player:send_message`, `nf.server.broadcast`,
`node:set_display_text`, command replies) is
[MiniMessage](https://docs.advntr.dev/minimessage/format.html): `<gold>Hello`,
`<bold>`, `<#ff8800>`, and so on. `<glyph:ui/coin>` shows a picture from one
of the project's [resource packs](resource-packs.md#glyphs-pictures-in-text).
[`nf.text`](../reference/nf.text.md) escapes what players typed, strips tags
and measures text.

## Logging

`log(...)` (or `print`) writes a line to the server console. On the dev server
it also shows in the editor's Console, with a link to the file and line that
logged it.

## Next

- [Limits and performance](performance.md): the sandbox, the instruction
  budget, and keeping scripts fast.
- [Modules and commands](modules.md): server-wide code, commands, tasks.
- [Players, entities and inventories](players-and-entities.md) and
  [The world](world.md): what scripts can do to the game.
