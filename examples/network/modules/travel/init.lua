-- Getting around: walk into a portal (or /go <server>) and the proxy moves you to that server
-- (nf.network.send_player), with a note of where you came from that arrives with you
-- (player_arrive), so you step out of the portal that leads back.
--
-- Players only: a bot can't be sent through the proxy (it never logged in there). The crowd module
-- moves bots its own way.

local net = require("net")
local plaza = require("plaza")
local crowd = require("crowd")

local travel = {}

local random = nf.random.new()

-- No portal sends anyone twice in three seconds: arriving next to one, or a failed trip, isn't
-- another trip.
local COOLDOWN = 60
local next_trip = {} ---@type table<string, integer>

local function rest(player)
  next_trip[player:name()] = nf.server.tick() + COOLDOWN
end

--- Sends `player` to the server playing `role`. False and why not when they can't go now; the
--- move itself is the proxy's, and a failure then is told to them.
---@param player Player
---@param role string
---@return boolean
---@return string? why
function travel.go(player, role)
  if role == net.role() then
    return false, "you're already here"
  end
  local server = net.server_for(role)
  if not server then
    return false, "its server isn't running: start the dev network (see the README)"
  end
  rest(player)
  nf.network.send_player(player, server, { from = net.role() }, function(moved, err)
    if not moved and player:exists() then
      player:send_message("<red>Couldn't go: " .. tostring(err))
    end
  end)
  return true
end

nf.on("player_move", function(event)
  local player = event.player
  local portal = plaza.portal_at(event.to.position)
  if not portal or crowd.is_bot(player) or (next_trip[player:name()] or 0) > nf.server.tick() then
    return
  end
  local went, why = travel.go(player, portal.to)
  if not went then
    rest(player)
    player:teleport(portal.front)
    player:send_message("<gray>The portal flickers: " .. tostring(why) .. ".")
  end
end)

nf.on("player_arrive", function(event)
  local player = event.player
  local from = net.role_of(event.from)
  rest(player)
  local portal = plaza.portal_to(from)
  if portal then
    player:teleport(portal.front)
  end
  player:send_title(net.TITLES[net.role()], "<gray>from the " .. net.TITLES[from])
  player:play_sound("minecraft:entity.enderman.teleport", { volume = 0.6 })
  -- Someone always says hello.
  local greeter = crowd.idle()
  if greeter then
    local name = player:name()
    nf.after(30, function()
      if greeter.bot:exists() then
        greeter.bot:chat(random:pick({
          "welcome to the " .. net.role() .. ", " .. name .. "!",
          "hey " .. name .. " o/",
          "look who's here, " .. name,
        }))
      end
    end)
  end
end)

--- The lines that tell a player what there is to do, with links to click.
---@return string[]
function travel.help()
  local links = {}
  for _, role in ipairs(net.ROLES) do
    if role ~= net.role() then
      links[#links + 1] = ("<click:run_command:'/go %s'><hover:show_text:'Go to the %s'>[%s]</hover></click>"):format(
        role,
        role,
        net.TITLES[role]
      )
    end
  end
  return {
    "<gold><b>Network demo</b></gold> <gray>— three servers behind one proxy, with bots for company.",
    "<gray>Walk into a portal, or click: " .. table.concat(links, " "),
    "<gray>Chat reaches every server. <white>/demo</white>, <white>/who</white>, <white>/find</white>, <white>/coins</white>, <white>/daily</white>, <white>/price</white>, <white>/buy</white>; <white>/duel</white> in the arena.",
  }
end

--- What a dev server running alone tells a player: how to start the rest.
---@return string[]
function travel.setup_help()
  return {
    "<yellow>This server is running alone.</yellow> <gray>The demo is three servers: in the editor,",
    "<gray>open <white>View → Network</white>, tick <white>Run the dev server as a network</white>, name this",
    "<gray>server <white>lobby</white>, and add <white>arena</white> and <white>market</white> with project <white>.</white> (this one).",
    "<gray>Then stop and start the server, and join it again.",
  }
end

nf.on("player_join", function(event)
  local player = event.player
  if crowd.is_bot(player) or net.role() ~= "lobby" then
    return
  end
  nf.after(20, function()
    if not player:exists() then
      return
    end
    for _, line in ipairs(net.is_whole() and travel.help() or travel.setup_help()) do
      player:send_message(line)
    end
  end)
end)

nf.commands.register("go", {
  description = "Go to another server of the network",
  players_only = true,
  arguments = { { name = "server", type = "choice", choices = net.ROLES } },
}, function(event)
  local player = assert(event.player)
  local went, why = travel.go(player, event.arguments.server)
  if not went then
    player:send_message("<gray>Can't go: " .. tostring(why) .. ".")
  end
end)

nf.commands.register("demo", { description = "What the network demo has to do" }, function(event)
  for _, line in ipairs(travel.help()) do
    event.sender:send_message(line)
  end
end)

return travel
