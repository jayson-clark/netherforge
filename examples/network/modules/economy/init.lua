-- Coins, one purse across the network: kept in the shared `coins` map by name, so what you win in
-- the arena you spend from the lobby. Every change goes through the hub in one step (`map:add`),
-- so two servers paying the same purse at once both count.
--
--   /coins         your purse
--   /daily         50 coins, once every five minutes across the whole network (`map:insert`)
--   /price <item>  asks the market server what it costs (`channel:request`)
--   /buy <item>    buys it from the market, from any server

local net = require("net")

local economy = {}

--- What the market sells: each item's kind and its price before the market moves it.
economy.ITEMS = {
  diamond = { kind = "minecraft:diamond", base = 40 },
  emerald = { kind = "minecraft:emerald", base = 25 },
  golden_apple = { kind = "minecraft:golden_apple", base = 30 },
  ender_pearl = { kind = "minecraft:ender_pearl", base = 15 },
  iron_sword = { kind = "minecraft:iron_sword", base = 20 },
}

--- The items' names, in order.
economy.NAMES = {}
for name in pairs(economy.ITEMS) do
  economy.NAMES[#economy.NAMES + 1] = name
end
table.sort(economy.NAMES)

--- How often /daily pays: once every five minutes.
economy.DAILY_TICKS = 20 * 60 * 5
economy.DAILY_COINS = 50

--- A purse, from this server's copy of the map.
---@param name string
---@return integer
function economy.balance(name)
  return math.floor(net.coins:get(name) or 0)
end

--- Adds `amount` to a purse (negative takes away), through the hub. In a task it waits and returns
--- the new balance; give `callback` to call it from anywhere else.
---@param name string
---@param amount number
---@param callback fun(total: number?, err: string?)?
---@return number? total
---@return string? err
function economy.pay(name, amount, callback)
  if callback then
    net.coins:add(name, amount, nil, callback)
    return nil
  end
  return net.coins:add(name, amount)
end

--- Claims `name`'s daily coins on behalf of this server: true once paid, false and the server it
--- was claimed on when it's been claimed in the last five minutes, anywhere. Call it in a task.
---@param name string
---@return boolean? paid
---@return string? where_or_err
function economy.claim_daily(name)
  local claimed, err = net.daily:insert(name, net.name(), { expires = economy.DAILY_TICKS })
  if claimed == nil then
    return nil, err
  end
  if not claimed then
    return false, net.daily:get(name)
  end
  economy.pay(name, economy.DAILY_COINS)
  return true
end

--- Buys `item` for `player` from the market server: asks it for the item (it takes one from its
--- stock and says the price), takes the coins, and gives the item; puts it all back if the purse
--- is short. Call it in a task. True, or false and why not.
---@param player Player
---@param item string
---@return boolean bought
---@return string? why
function economy.buy(player, item)
  local market = net.server_for("market")
  if not market then
    return false, "the market is closed: its server isn't running"
  end
  local name = player:name()
  local sale, err = net.market:request(market, { action = "buy", item = item, buyer = name })
  if not sale then
    return false, err or "the market didn't answer"
  end
  if sale.refused then
    return false, sale.refused
  end
  local total = economy.pay(name, -sale.price)
  if not total or total < 0 then
    -- Short, or the hub didn't answer: everything goes back.
    if total then
      economy.pay(name, sale.price)
    end
    net.market:request(market, { action = "return", item = item })
    return false, ("it costs %d coins and you have %d"):format(sale.price, economy.balance(name))
  end
  player:give_item({ kind = economy.ITEMS[item].kind })
  return true, ("%d coins"):format(sale.price)
end

nf.commands.register(
  "coins",
  { description = "Your coins, the same on every server", players_only = true },
  function(event)
    local player = assert(event.player)
    player:send_message(
      "<yellow>You have <white>" .. economy.balance(player:name()) .. "</white> coins."
    )
  end
)

local DAILY = {
  description = "Claim 50 coins, once every five minutes across the network",
  players_only = true,
}

nf.commands.register("daily", DAILY, function(event)
  local player = assert(event.player)
  nf.task(function()
    local paid, detail = economy.claim_daily(player:name())
    if paid then
      player:send_message(
        ("<green>+%d coins!</green> <gray>Claim again in five minutes, on any server."):format(
          economy.DAILY_COINS
        )
      )
    elseif paid == false then
      player:send_message(
        "<gray>You've claimed it already, on the "
          .. net.TITLES[net.role_of(detail)]
          .. "<gray>: try again later."
      )
    else
      player:send_message("<red>Couldn't claim: " .. tostring(detail))
    end
  end)
end)

local ITEM_ARGUMENT = { name = "item", type = "choice", choices = economy.NAMES }

nf.commands.register("price", {
  description = "Ask the market what something costs",
  arguments = { ITEM_ARGUMENT },
}, function(event)
  local item = event.arguments.item
  local market = net.server_for("market")
  if not market then
    event.sender:send_message("<gray>The market is closed: its server isn't running.")
    return
  end
  net.market:request(market, { action = "quote", item = item }, function(quote, err)
    if not quote then
      event.sender:send_message("<red>The market didn't answer: " .. tostring(err))
      return
    end
    event.sender:send_message(
      ("<gold>%s</gold> <gray>costs</gray> <white>%d coins</white> <gray>at the market (%d in stock)"):format(
        item,
        quote.price,
        quote.stock
      )
    )
  end)
end)

nf.commands.register("buy", {
  description = "Buy from the market, from any server",
  players_only = true,
  arguments = { ITEM_ARGUMENT },
}, function(event)
  local player = assert(event.player)
  local item = event.arguments.item
  nf.task(function()
    local bought, detail = economy.buy(player, item)
    if bought then
      player:send_message(("<green>Bought %s for %s.</green>"):format(item, detail))
      net.announce(
        ("<white>%s</white> bought %s at the market"):format(net.plain(player:name()), item)
      )
    else
      player:send_message("<red>Couldn't buy " .. item .. ": " .. tostring(detail))
    end
  end)
end)

return economy
