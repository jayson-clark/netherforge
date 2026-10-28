-- Who's where: every server shares its people (players and bots) in the `population` map, and every
-- player sees the whole network in their sidebar and tab list, with /who and /find.
--
-- The proxy knows where the players are (nf.network.players), but not the bots: it never saw them
-- log in. So each server says who it has, every two seconds, in a key that expires after five: a
-- server that stops drops out of everyone's sidebar by itself.

local net = require("net")
local crowd = require("crowd")
local economy = require("economy")

local presence = {}

--- Who's on this server: players and bots, each in name order.
---@return string[] players
---@return string[] bots
function presence.here()
  local players, bots = {}, {}
  for _, player in ipairs(nf.players.online()) do
    local list = crowd.is_bot(player) and bots or players
    list[#list + 1] = player:name()
  end
  table.sort(players)
  table.sort(bots)
  return players, bots
end

--- Says who's here, for the whole network.
function presence.publish()
  local players, bots = presence.here()
  net.population:set(net.name(), { players = players, bots = bots }, { expires = 100 })
end

---@class ServerPeople
---@field server string
---@field role "lobby"|"arena"|"market"
---@field players string[]
---@field bots string[]

local ORDER = { lobby = 1, arena = 2, market = 3 }

--- Every server's people, from the shared map: the lobby first, then the arena and the market.
---@return ServerPeople[]
function presence.network()
  local list = {}
  for server, people in pairs(net.population:all()) do
    list[#list + 1] = {
      server = server,
      role = net.role_of(server),
      players = people.players or {},
      bots = people.bots or {},
    }
  end
  table.sort(list, function(a, b)
    if a.role ~= b.role then
      return ORDER[a.role] < ORDER[b.role]
    end
    return a.server < b.server
  end)
  return list
end

--- How many people the network has, and on how many servers.
---@return integer people
---@return integer servers
function presence.totals()
  local people, servers = 0, 0
  for _, entry in ipairs(presence.network()) do
    people = people + #entry.players + #entry.bots
    servers = servers + 1
  end
  return people, servers
end

--- Which server `name` is on, and whether they're a bot: the proxy's word for players, the shared
--- map's for bots. Nil when nobody of that name is online.
---@param name string
---@return string? server
---@return boolean? bot
function presence.find(name)
  local player = nf.network.find_player(name)
  if player and player.server then
    return player.server, false
  end
  local wanted = name:lower()
  for _, entry in ipairs(presence.network()) do
    for _, list in ipairs({ entry.players, entry.bots }) do
      for _, who in ipairs(list) do
        if who:lower() == wanted then
          return entry.server, list == entry.bots
        end
      end
    end
  end
  return nil
end

local function sidebar(player)
  local lines = { "<gray>You're on the " .. net.TITLES[net.role()], "" }
  for _, entry in ipairs(presence.network()) do
    local here = entry.server == net.name() and "<white>▶ " or "  "
    lines[#lines + 1] = ("%s%s <white>%d</white> <dark_gray>(%d bots)"):format(
      here,
      net.TITLES[entry.role],
      #entry.players + #entry.bots,
      #entry.bots
    )
  end
  lines[#lines + 1] = ""
  lines[#lines + 1] = "<yellow>Coins: <white>" .. economy.balance(player:name())
  lines[#lines + 1] = "<gray>/go  /who  /daily  /buy"
  player:sidebar():set_title("<gold><b>Network demo")
  player:sidebar():set_lines(lines)
end

-- Every two seconds: say who's here, then show everyone the network as it stands.
nf.every(40, function()
  presence.publish()
  local people, servers = presence.totals()
  for _, player in ipairs(nf.players.online()) do
    if not crowd.is_bot(player) then
      sidebar(player)
      player:set_tab_header(
        "<gold><b>Network demo</b>\n<gray>You're on the " .. net.TITLES[net.role()]
      )
      player:set_tab_footer(("<gray>%d people on %d servers"):format(people, servers))
    end
  end
end)

nf.commands.register(
  "who",
  { description = "Who's on which server of the network" },
  function(event)
    local network = presence.network()
    if #network == 0 then
      event.sender:send_message(
        "<gray>Nobody yet: the servers say who they have every two seconds."
      )
      return
    end
    for _, entry in ipairs(network) do
      local names = {}
      for _, name in ipairs(entry.players) do
        names[#names + 1] = "<white>" .. net.plain(name)
      end
      for _, name in ipairs(entry.bots) do
        names[#names + 1] = "<gray>" .. net.plain(name)
      end
      event.sender:send_message(
        net.tag(entry.server)
          .. " "
          .. (#names > 0 and table.concat(names, "<dark_gray>, ") or "<gray>nobody")
      )
    end
    event.sender:send_message("<dark_gray>Players in white, bots in grey.")
  end
)

nf.commands.register("find", {
  description = "Which server someone is on",
  arguments = { { name = "name", type = "word" } },
}, function(event)
  local name = event.arguments.name
  local server, bot = presence.find(name)
  if server then
    event.sender:send_message(
      ("<white>%s</white> <gray>is on the</gray> %s%s"):format(
        net.plain(name),
        net.TITLES[net.role_of(server)],
        bot and " <dark_gray>(a bot)" or ""
      )
    )
  else
    event.sender:send_message("<gray>Nobody called " .. net.plain(name) .. " is online.")
  end
end)

return presence
