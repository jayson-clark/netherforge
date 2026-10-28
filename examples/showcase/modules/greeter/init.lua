-- Greets players as they join (and shows the welcome dialog the first time),
-- adds /tower to spawn one in front of you (once they've been welcomed), and
-- /shop to open the shop.
local messages = require("greeter.messages")
-- A module from the library this project depends on (netherforge.json's
-- dependencies): "library:" names the package, and the library exports it.
local greetings = require("library:greetings")

-- Server-owner settings (netherforge.json's settings): whoever runs the server
-- changes them, from the editor or with /nf settings. The greeter listens for
-- setting_changed and keeps its copies up to date, so a change doesn't restart
-- it (a script that reads a setting without listening is restarted instead).
local greeting = nf.config("greeting")
local show_welcome = nf.config("show_welcome")
nf.on("setting_changed", function(event)
  if event.setting == "greeting" then
    greeting = event.value
  elseif event.setting == "show_welcome" then
    show_welcome = event.value
  end
end)

-- The project's own database (migrations/001_init.sql makes its tables). SQL
-- always takes its values separately, as a list for the ?s, never inside the text.
local db = nf.db()

nf.on("player_join", function(event)
  local player = event.player
  db:execute(
    "INSERT INTO visits (player, joins) VALUES (?, 1) ON CONFLICT (player) DO UPDATE SET joins = joins + 1",
    { player:name() },
    function(_, err)
      if err then
        log("couldn't count " .. player:name() .. "'s visit: " .. err)
      end
    end
  )
  player:send_message(messages.welcome(greeting, player:name()))
  player:send_message(greetings.hello(player:name()))

  -- The player's saved table is shared by every script, so the greeter keeps
  -- its own part of it under its name. It's saved for us, across restarts.
  local data = player:data()
  data.greeter = data.greeter or {}
  if show_welcome and not data.greeter.welcomed then
    data.greeter.welcomed = true
    -- The nickname box starts at their name.
    player:open_dialog("welcome", { values = { nickname = player:name() } })
  end
end)

-- /tower [distance]: players only, so the handler can count on event.player.
-- The distance is optional; NetherForge checks it's a whole number from 1 to 16.
-- It needs showcase.builder, which the welcome dialog grants (netherforge.json's
-- allow.permissions lets the project grant anything under "showcase").
nf.commands.register("tower", {
  description = "Spawn a stone tower in front of you",
  permission = "showcase.builder",
  players_only = true,
  arguments = {
    { name = "distance", type = "integer", min = 1, max = 16, default = 2 },
  },
}, function(event)
  local distance = event.arguments.distance
  nf.centities.spawn("tower", event.player:location():offset(vec3(distance, 0, 0)))
  -- event.sender is whoever ran the command: replying through it works for the
  -- console too, in a command that lets the console run it.
  event.sender:send_message("<gray>A tower rises " .. distance .. " blocks ahead.")
end)

nf.commands.register("shop", {
  description = "Open the village shop",
  players_only = true,
}, function(event)
  -- The window's script reads this with this:context(): who it's greeting.
  local greeter = event.player:data().greeter or {}
  event.player:open_menu("shop", { context = { nickname = greeter.nickname } })
end)

-- /visits: how many times you've joined. A task waits for the database and
-- gives `rows, err`; an error is a message, never a thrown error.
nf.commands.register("visits", {
  description = "How many times you've joined",
  players_only = true,
}, function(event)
  -- players_only: there is always a player (LuaLS wants it said once, outside the task).
  local player = assert(event.player)
  nf.task(function()
    local rows, err = db:query("SELECT joins FROM visits WHERE player = ?", { player:name() })
    if not rows then
      player:send_message("<red>Couldn't read your visits: " .. err)
    elseif rows[1] then
      player:send_message("You've joined " .. rows[1].joins .. " time(s).")
    else
      player:send_message("No visits recorded yet.")
    end
  end)
end)
