-- Two rooms beside the main one, each a copy of this module in a Lua state of its own: room 1 a match (no key) and
-- room 2 keyed "b". Every room says what it hears, tagged with its name ("main", "r1", "b"); its players get its boss
-- bar and team, and a room's players play on its own copy of the it_track map ("track", the server's track__r1 or
-- track__b).

local here = nf.rooms.current()
local name = here:is_main() and "main" or (here:key() or ("r" .. here:id()))

local function names(list)
  local out = {}
  for _, each in ipairs(list) do
    out[#out + 1] = each:name()
  end
  table.sort(out)
  return table.concat(out, ",")
end

local bar = nf.bossbars.create({ text = "Room " .. name, progress = 1 })
local team = nf.teams.create("racers", { prefix = "[" .. name .. "] " })
local track

if not here:is_main() then
  nf.worlds.copy("it_track", "track", function(world, err)
    track = world
    log("track", name, world and world:name() or "nil", tostring(err), names(nf.worlds.all()))
  end)
end

nf.on("world_load", function(event)
  log("world_load", name, event.world:name())
end)

nf.on("player_join", function(event)
  local player = event.player
  if track then
    player:teleport(track:spawn_location())
    player:set_game_mode("creative")
  end
  bar:show_to(player)
  team:add_member(player)
  log("join", name, player:name(), names(nf.players.online()))
end)

nf.on("player_quit", function(event)
  log("quit", name, event.player:name(), names(nf.players.online()))
end)

nf.on("player_chat", function(event)
  log("chat", name, event.player:name(), event.message, names(event.recipients))
end)

nf.on("block_break", function(event)
  log("break", name, event.block:world():name(), event.player and event.player:name() or "nil")
end)

nf.on("room_close", function()
  log("closing", name, names(nf.players.online()))
end)

local function by_name(wanted)
  for _, room in ipairs(nf.rooms.all()) do
    if (room:key() or ("r" .. room:id())) == wanted then
      return room
    end
  end
end

local steps = {}

function steps.open()
  local match = nf.rooms.open()
  local keyed = nf.rooms.open({ key = "b" })
  log("opened", match:id(), match:state(), keyed:id(), keyed:key())
end

-- From the console (the main room): Alice and Bob to r1, Carol and Dave to b.
function steps.send()
  local r1, b = by_name("r1"), by_name("b")
  local sent = {}
  for _, player in ipairs(nf.players.online()) do
    local to = (player:name() == "Alice" or player:name() == "Bob") and r1 or b
    sent[#sent + 1] = tostring(to:send(player))
  end
  log("sent", table.concat(sent, ","))
end

-- What the sender's room sees: run by a player, in their room.
function steps.who(event)
  local worlds = {}
  for _, world in ipairs(nf.worlds.all()) do
    worlds[#worlds + 1] = world
  end
  log(
    "who",
    name,
    event.player and event.player:name() or "console",
    names(nf.players.online()),
    names(worlds),
    names(here:players()),
    tostring(nf.players.get("Carol"):exists())
  )
end

-- A stone two blocks east of the player, for them to break.
function steps.mark(event)
  local at = event.player:location().position:floor() + vec3(2, 0, 0)
  event.player:world():set_block(at, "minecraft:stone")
  log("marked", name, at.x, at.y, at.z)
end

function steps.go(event, to)
  log("go", name, event.player:name(), to, tostring(by_name(to):send(event.player)))
end

function steps.close(_, which)
  local room = by_name(which)
  log("close", which, tostring(room:finish()), tostring(room:close()))
end

nf.commands.register("it-rooms", {
  arguments = { { name = "step", type = "word" }, { name = "arg", type = "word", default = "" } },
}, function(event)
  steps[event.arguments.step](event, event.arguments.arg)
end)
