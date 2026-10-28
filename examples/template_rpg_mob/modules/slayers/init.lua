-- Slayers: counts every goblin a player kills, and decides who goblins may
-- appear near. It is what the goblin's script announces its deaths to, so
-- the goblin itself knows nothing about counting.
--
-- Kills are kept in the package's own SQLite database (nf.db), so they survive
-- restarts. The module makes its table itself, so Copy into project brings
-- everything it needs; the project must declare `"requires": { "db": true }`.

local db = nf.db()

db:execute(
  "CREATE TABLE IF NOT EXISTS slayer_kills (player TEXT PRIMARY KEY, kills INTEGER NOT NULL)",
  {},
  function(_, err)
    if err then
      log("slayers couldn't make its table: " .. err)
    end
  end
)

local slayers = {}

--- How many goblins a player has killed. Only in a task (it waits for the database).
---@param player Player
---@return integer
function slayers.kills(player)
  local rows = assert(db:query("SELECT kills FROM slayer_kills WHERE player = ?", { player:id() }))
  return rows[1] and rows[1].kills or 0
end

-- The goblin's script raised this; nobody waits for the answer, so the write
-- takes a callback (a handler can't wait) and only reports a failure.
nf.on("goblin:slain", function(event)
  db:execute(
    "INSERT INTO slayer_kills (player, kills) VALUES (?, 1) ON CONFLICT (player) DO UPDATE SET kills = kills + 1",
    { event.player:id() },
    function(_, err)
      if err then
        log("couldn't count a kill: " .. err)
      end
    end
  )
  event.player:send_actionbar("<green>Goblin slain!")
end)

-- Natural spawning (centity.json's "spawning") picks the place; this is the
-- project's say in it. A creative player is building, not fighting: goblins
-- don't appear for them. event.location could be assigned to move one instead.
nf.on("centity_natural_spawn", function(event)
  if event.centity == "goblin" and event.player:game_mode() == "creative" then
    event:cancel()
  end
end)

nf.commands.register("slayer", {
  description = "How many goblins you have killed",
  players_only = true,
}, function(event)
  local player = assert(event.player)
  nf.task(function()
    player:send_message("<green>Goblins slain: <white>" .. slayers.kills(player))
  end)
end)

return slayers
