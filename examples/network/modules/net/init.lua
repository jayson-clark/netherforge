-- What every server of the demo shares: which part it plays, the network's channels and maps, and
-- small helpers the other modules use.
--
-- The same project runs on every server of the network. Each server's name in the network (the
-- editor's Network panel, or `network.server-name` in config.yml) picks its part:
--
--   arena   the arena: bots fight, and so can you (/duel)
--   market  the market: prices move, bots trade, and every server buys here (/buy)
--   (other) the lobby: portals to the others, and the network's board
--
-- Run alone, the dev server is a network of its own named "dev": it plays the lobby, and says how
-- to start the rest.

local net = {}

--- The parts a server can play.
net.ROLES = { "lobby", "arena", "market" }

--- What each part is called, and its colour, as players see them.
net.TITLES = {
  lobby = "<aqua>Lobby</aqua>",
  arena = "<red>Arena</red>",
  market = "<gold>Market</gold>",
}

--- `[Arena]` for a server named `name`, to put before what came from it.
---@param name string
---@return string
function net.tag(name)
  return "<dark_gray>[</dark_gray>" .. net.TITLES[net.role_of(name)] .. "<dark_gray>]</dark_gray>"
end

--- The part a server named `name` plays.
---@param name string?
---@return "lobby"|"arena"|"market"
function net.role_of(name)
  if name == "arena" or name == "market" then
    return name
  end
  return "lobby"
end

--- This server's name in the network ("dev" when it runs alone).
---@return string
function net.name()
  return nf.network.server_name() or "dev"
end

--- The part this server plays.
---@return "lobby"|"arena"|"market"
function net.role()
  return net.role_of(nf.network.server_name())
end

--- The server playing `role` that's connected now, or nil: the lobby is whichever server isn't
--- the arena or the market.
---@param role string
---@return string?
function net.server_for(role)
  for _, server in ipairs(nf.network.servers()) do
    if server.connected and net.role_of(server.name) == role then
      return server.name
    end
  end
  return nil
end

--- Whether the other servers are up: false while this one runs alone.
---@return boolean
function net.is_whole()
  return net.server_for("arena") ~= nil and net.server_for("market") ~= nil
end

-- The network's channels: published on one server, heard on every server listening.

--- Chat, players' and bots' alike, shown on every server.
net.chat = nf.network.channel("chat")
--- News every server shows: a fight won, a bot moving on, a big sale.
net.news = nf.network.channel("news")
--- A bot leaving one server for another: the other server joins it.
net.travel = nf.network.channel("travel")
--- Asking the market for a price or a sale (`channel:request`).
net.market = nf.network.channel("market")

-- The network's shared maps: every server reads its own copy and writes through the hub.

--- Coins, by player or bot name: earned in the arena, spent at the market, from anywhere.
net.coins = nf.network.map("coins")
--- Arena wins, by name.
net.wins = nf.network.map("wins")
--- Each server's people (players and bots) by server name, refreshed every two seconds; a
--- server that stops drops out when its key expires.
net.population = nf.network.map("population")
--- The market's prices, by item: only the market writes them.
net.prices = nf.network.map("prices")
--- Who claimed their daily coins: a claim per name that expires, made once across the network.
net.daily = nf.network.map("daily")

--- A name as MiniMessage shows it, never read as tags.
---@param text string
---@return string
function net.plain(text)
  return nf.text.escape(text)
end

--- Sends news to every server: each shows it to its players.
---@param text string MiniMessage.
function net.announce(text)
  net.news:publish({ text = text })
end

net.news:on("message", function(event)
  nf.server.broadcast(net.tag(event.server) .. " <gray>" .. event.value.text)
end)

return net
