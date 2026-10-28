# Modules and commands

A **module** is server-wide Lua: code that belongs to the server rather than
to one centity. Use modules to listen for events, add commands, keep shared
state, and spawn centities. A module is a folder under `modules/`:

```
modules/
  greeter/
    init.lua         runs when the server loads the module
    messages.lua     reached with require("greeter.messages")
```

In the editor's Project explorer, pick **Modules** and choose **New module**.
It creates `modules/<id>/init.lua` and opens it. The outline lists the
module's files, where you can add more (<kbd>Ctrl</kbd>/<kbd>Cmd</kbd>+<kbd>P</kbd>
finds any of them by name).

## How modules run

- When the project loads, every module with an `init.lua` runs it, in id
  order. A module without one is a library: it runs when something requires
  it. NetherForge warns about one only while no script requires it.
- A module runs **once** for the whole server. All its files share one set of
  globals, and every `require` of it gets the running module, state and all.
- What a module registers (event handlers, timers, commands) lasts until the
  module unloads.

```lua
-- modules/greeter/init.lua
local messages = require("greeter.messages")

nf.on("player_join", function(event)
  event.player:send_message(messages.welcome(event.player:name()))
end)
```

```lua
-- modules/greeter/messages.lua
local messages = {}

function messages.welcome(name)
  return "<green>Welcome, " .. name .. "!"
end

return messages
```

## Events

`nf.on(event, handler)` calls the handler every time the event happens, until
the module unloads or you call `:cancel()` on what `nf.on` returned:

```lua
local sub = nf.on("player_chat", function(event)
  log(event.player:name() .. " said " .. event.message)
end)
-- later: sub:cancel()
```

Some events are **cancellable**: call `event:cancel()` in any handler and what
caused it doesn't happen. (What a handler returns means nothing.)

```lua
-- Nobody breaks diamond ore.
nf.on("block_break", function(event)
  if event.state == "minecraft:diamond_ore" then
    event:cancel()
  end
end)
```

A module can listen on any handle it holds, too: `centity:on("remove", ...)`,
`menu:slot(4):on("click", ...)`. Such a handler lasts as long as both the
module and the thing it's on. Custom events, named with a `:`, carry anything
between scripts: `nf.emit("arena:scored", { player = player })` reaches every
`nf.on("arena:scored", ...)`.

The [events reference](../reference/events.md) lists every event, its payload
and whether it can be cancelled. Naming an event that doesn't exist is an
error, so a typo fails where you made it.

## Commands

Only modules can add commands, because a command outlives any one centity.

```lua
nf.commands.register("tower", {
  description = "Spawn a stone tower in front of you",
  players_only = true,
  arguments = {
    { name = "distance", type = "integer", min = 1, max = 16, default = 2 },
  },
}, function(event)
  local distance = event.arguments.distance
  nf.centities.spawn("tower", event.player:location():offset(vec3(distance, 0, 0)))
  event.sender:send_message("<green>Here you go")
end)
```

- The name is lowercase letters, digits, `_` and `-`, starting with a letter,
  without the slash.
- The definition: `description` (for the server's help), `permission` (a
  permission node the sender needs), `aliases`, `arguments`, `subcommands` and
  `players_only`. A key that isn't one of these is an error, and so is any
  other mistake in it (an unknown argument type, an optional argument before a
  required one).
- `players_only = true` refuses the console with a message, so the handler can
  count on `event.player`.
- The handler gets a [CommandEvent](../reference/types.md#commandevent):
  `arguments` (the typed values, by name), `input` (everything after the
  command, as typed), `label` (the name or alias typed), `sender` (a
  [Sender](../reference/sender.md): the player or the console; answer with
  `event.sender:send_message(text)`) and `player` (or `nil` from the console).
- `nf.commands.register` returns `false` if the name is already taken, by the
  server or by another module.
- A handler that errors is logged and whoever ran the command is told; the
  command stays registered.

### Arguments

Each argument has a `name` (its key in `event.arguments`) and a `type`, which
decides what may be typed, what tab completion offers, and the value the
handler gets:

| Type                             | Takes                                                 | Handler gets                 |
| -------------------------------- | ----------------------------------------------------- | ---------------------------- |
| `word`, `text`                   | one word; the rest of the line                        | `string`                     |
| `integer`, `number`              | a number, within `min` and `max` when given           | `integer`, `number`          |
| `boolean`                        | `true` or `false`                                     | `boolean`                    |
| `choice`                         | one of `choices`                                      | `string`                     |
| `player`, `players`              | a name, or a selector like `@p` or `@a[distance=..5]` | `Player`, `Player[]`         |
| `entity`, `entities`             | a selector, a player's name or a UUID                 | `Entity`, `Entity[]`         |
| `world`                          | a world's name                                        | `World`                      |
| `position`, `location`           | three coordinates: numbers, `~1` or `^1`              | `Vec3`, `Location`           |
| `block_state`                    | `oak_stairs[facing=east]`                             | the full state, `string`     |
| `item`                           | `diamond_sword`, components and all                   | an `Item` table              |
| `centity`                        | a live centity's instance id                          | `Centity`                    |
| `centity_kind`, `menu`, `dialog` | one of the project's centities, menus, dialogs        | `string`, `string`, `Dialog` |

An argument with a `default` is optional, and optional arguments come last.
`default = false` makes one optional without a value, which reads well for
players: `event.arguments.target or event.player`.

Arguments are checked before your handler runs, so the handler only ever sees
good values. Players get the same experience as with Minecraft's own commands:
the arguments are highlighted and completed as they type, a mistake (a number
out of range, a selector that picks no one) gets a red message, and a command
or subcommand with a `permission` is hidden from anyone without it. A name your
project doesn't have (a world, centity, menu or dialog) is answered with a red
message and the command's usage (`/give-coins <target> [amount]`).

Tab completion comes from the type (online players, the project's menus, live
centities, …). For anything else, give an argument a `complete` function: it
gets the event so far (with the arguments before it) and what has been typed,
and returns suggestions.

```lua
nf.commands.register("warp", {
  arguments = {
    {
      name = "name",
      type = "word",
      complete = function(event, partial)
        local names = {}
        for name in pairs(nf.data("warps")) do
          names[#names + 1] = name
        end
        return names
      end,
    },
  },
}, function(event)
  event.sender:send_message("Warping to " .. event.arguments.name)
end)
```

### Subcommands

`subcommands` maps a word to its own `arguments`, `permission`, `handler`,
`players_only` and `subcommands`, as deep as you like. A subcommand wins over
an argument when both could take the word.

```lua
nf.commands.register("shop", {
  players_only = true,
  subcommands = {
    open = { handler = function(event) event.player:open_menu("shop") end },
    give = {
      permission = "shop.admin",
      arguments = { { name = "target", type = "player" }, { name = "coins", type = "integer", min = 1 } },
      handler = function(event)
        event.sender:send_message("Gave " .. event.arguments.coins .. " to " .. event.arguments.target:name())
      end,
    },
  },
})
```

A command with subcommands can leave out its own handler: typing it alone
shows the usage of each subcommand the sender may use.

`/nf modules` lists every module, whether it's running, idle or failed, and
the commands each one added.

## Timers

```lua
nf.after(20 * 5, function() nf.server.broadcast("<gold>Five seconds in") end)

local task = nf.every(20, function() log("another second") end)
-- later: task:cancel()
```

Ticks are 1/20 of a second. Timers stop when the module unloads.

## Waiting

An event handler can't wait: whether the event is cancelled has to be known
when it returns. When something takes several steps with pauses in between,
start a **task** and wait in it. The task runs straight away, up to its first
wait, and carries on where it left off when the wait is over:

```lua
nf.commands.register("countdown", function(event)
  nf.task(function()
    for i = 3, 1, -1 do
      nf.server.broadcast("<yellow>" .. i)
      nf.wait(20)                                   -- a second
    end
    nf.server.broadcast("<green>Go!")
  end)
end)
```

- `nf.wait(ticks)` pauses for a number of ticks.
- `nf.wait_until(predicate, timeout?)` pauses until `predicate()` is true,
  checking now and then every tick. It returns `false` if `timeout` ticks
  pass first.
- `nf.wait_for(handle, event, filter?)` pauses until the event next happens on
  the handle (`nf` for a server-wide one) and returns it. `filter` is a
  function that picks which one: `nf.wait_for(nf, "player_chat", function(e) return e.player == player end)`.
  The task carries on while the event is still being delivered, so it can still
  `event:cancel()` it.
- `dialog:ask(player)` opens a dialog and returns the player's press, or `nil`
  if they close it or leave.

A task ends when it returns, when `task:cancel()` is called, when its script
unloads, or when the handle it's waiting on goes (a removed centity, a closed
menu window). An error in a task is reported like a handler's and ends the
task. Each time a task carries on it gets the whole instruction budget again.

Waits only work in the task's own code: in a function you pass to `nf.task`
and anything it calls, `pcall` included. Not in an event handler (even one the
task's own code sets off), not in a coroutine of your own, and not in a
function Lua or the server calls back, like `table.sort`'s comparator or an
argument's `complete`. Each of those is an error that says so.

## Talking to centities

Modules can spawn and find centities, and centity, menu and dialog scripts can
require modules. A module is for code shared _between_ resources, or across
the whole server: it runs once, and everyone who requires it gets the same
running module. To split one resource's own code into more files, put them
beside its script instead: each instance, window or dialog then runs its own
copy, with its own `this` (see [`require`](scripting.md#require)).

Behaviour several centities share lives in a module:

```lua
-- modules/arena/init.lua
local arena = {}
arena.score = 0

function arena.reset()
  for _, target in ipairs(nf.centities.all({ kind = "target" })) do target:remove() end
  arena.score = 0
end

nf.on("centity_click", function(event)
  if event.target:centity():kind() == "target" then
    arena.score = arena.score + 1
  end
end)

return arena
```

```lua
-- centities/reset_button/script.lua
local this = this --[[@as Centity]]
local arena = require("arena")

this:on("click", function()
  arena.reset()
end)
```

Because a module runs once, the button and the module share the same `arena`
table.

## Permissions and advancements

A project can grant players permission nodes, which a permissions plugin, a
command's `permission` or `player:has_permission` then sees. Each package (a
library too) may only grant nodes its own `netherforge.json` lists under
`allow.permissions`, so whoever runs the project can see what it hands out (see [the project file](../format/project.md)):

```json
"allow": { "permissions": ["showcase"] }
```

A grant is kept, and given back every time the player joins, until the
project takes it back:

```lua
player:set_permission("showcase.vip", true) -- or false to deny it
player:unset_permission("showcase.vip")
player:permissions() --> { ["showcase.vip"] = true }, what the project set
```

`has_permission` answers everything the player has, not only what the project
granted. On Paper a node nothing declares belongs to operators, so to ask
"did we make them a VIP", read `player:permissions()`.

Minecraft's advancements are there by key: `grant_advancement`,
`revoke_advancement`, `has_advancement` and `advancement_progress` (the
criteria met and left), and `nf.on("player_complete_advancement")` hears one
completed, with the announcement it makes writable.

The showcase example has both: `modules/ranks` adds `/perm grant|deny|unset|list`
for operators, and `modules/milestones` makes whoever finds diamonds a VIP
and adds `/milestones` to show a player's progress.

## Reloading

Saving any file in a module reloads it: its `unload` handlers run, its
handlers, timers and commands go, its files are forgotten, and `init.lua`
runs again. Every module, and every centity, menu or dialog script, that
required it reloads too.
Put anything you changed outside the module back in an `unload` handler:

```lua
nf.on("unload", function()
  nf.server.broadcast("<gray>Arena closing for maintenance")
end)
```

A module's locals start fresh on reload. Keep what matters in a [saved table](scripting.md#saving-data), like `nf.data("warps")` or `player:data().warps`, which a reload leaves as it is.
