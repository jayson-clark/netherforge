-- The market: the one server that keeps the prices and the stock. Every server asks it (/price and
-- /buy are requests on the `market` channel, answered here), and it publishes its prices in the
-- `prices` map for every board to show. Its bots trade all day, which is what moves the prices.

local net = require("net")
local plaza = require("plaza")
local crowd = require("crowd")
local boards = require("boards")
local economy = require("economy")

local market = {}

local random = nf.random.new()

--- Each item's price and stock: the market's own. A price drifts with trade, between half and
--- twice its base.
---@type table<string, { price: integer, stock: integer, trend: "up"|"down"|"flat" }>
market.book = {}
for name, item in pairs(economy.ITEMS) do
  market.book[name] = { price = item.base, stock = 8, trend = "flat" }
end

local function clamp_price(name, price)
  local base = economy.ITEMS[name].base
  return math.max(math.floor(base / 2), math.min(base * 2, math.floor(price + 0.5)))
end

--- Moves `name`'s price by `step` coins (and remembers which way it went).
---@param name string
---@param step number
function market.nudge(name, step)
  local entry = market.book[name]
  local price = clamp_price(name, entry.price + step)
  entry.trend = price > entry.price and "up" or price < entry.price and "down" or "flat"
  entry.price = price
end

--- Tells the network the prices.
function market.publish()
  for name, entry in pairs(market.book) do
    net.prices:set(name, { price = entry.price, stock = entry.stock, trend = entry.trend })
  end
end

--- Answers a request from any server: `quote` (the price), `buy` (takes one from stock and says
--- what it cost: demand puts the price up) or `return` (a sale that fell through).
---@param request { action: string, item: string, buyer: string? }
---@return table
function market.answer(request)
  local entry = market.book[request.item]
  if not entry then
    return { refused = "the market doesn't sell " .. tostring(request.item) }
  end
  if request.action == "quote" then
    return { price = entry.price, stock = entry.stock }
  elseif request.action == "buy" then
    if entry.stock <= 0 then
      return { refused = "sold out: try again in a minute" }
    end
    local price = entry.price
    entry.stock = entry.stock - 1
    market.nudge(request.item, 1 + price * 0.05)
    market.publish()
    return { price = price }
  elseif request.action == "return" then
    entry.stock = entry.stock + 1
    market.publish()
    return { stock = entry.stock }
  end
  return { refused = "the market doesn't " .. tostring(request.action) }
end

if net.role() ~= "market" then
  return market
end

net.market:on("request", function(event)
  event.reply = market.answer(event.value)
end)

-- The stalls restock slowly, and prices drift back towards where they started.
nf.every(20 * 20, function()
  for name, entry in pairs(market.book) do
    entry.stock = math.min(entry.stock + 1, 12)
    local base = economy.ITEMS[name].base
    market.nudge(name, (base - entry.price) * 0.2 + random:number(-2, 2))
  end
  market.publish()
end)

-- Bots trade: they sell to the market (and are paid for it) or buy from it with their own coins,
-- walking from stall to stall and calling out what they're doing.
local STALLS = {
  vec3(-4.5, plaza.Y, -4.5),
  vec3(5.5, plaza.Y, -4.5),
  vec3(-4.5, plaza.Y, 5.5),
  vec3(5.5, plaza.Y, 5.5),
}

crowd.set_brain(function(member, random)
  if random:number(0, 1) < 0.04 and crowd.travel(member, random:pick({ "lobby", "arena" })) then
    return
  end
  -- In front of a stall's counter, on the side towards the middle.
  local stall = random:pick(STALLS)
  member.bot:walk_to(stall + vec3(random:number(-1, 1), 0, stall.z < 0 and 2 or -2))
  local item = random:pick(economy.NAMES)
  local entry = market.book[item]
  local purse = economy.balance(member.name)
  if purse >= entry.price and entry.stock > 0 and random:number(0, 1) < 0.5 then
    local sale = market.answer({ action = "buy", item = item, buyer = member.name })
    if sale.price then
      economy.pay(member.name, -sale.price)
      crowd.say(member, ("got one %s for %d, bargain"):format((item:gsub("_", " ")), sale.price))
    end
  else
    local paid = math.floor(entry.price * 0.8)
    entry.stock = entry.stock + 1
    market.nudge(item, -1 - entry.price * 0.04)
    market.publish()
    economy.pay(member.name, paid)
    crowd.say(member, ("sold one %s for %d"):format((item:gsub("_", " ")), paid))
  end
  nf.wait(random:integer(60, 160))
end)

local board ---@type Board?

nf.after(20, function()
  plaza.build()
  -- Four stalls: a counter of barrels under a striped canopy.
  local colours = { "red", "yellow", "lime", "light_blue" }
  for i, stall in ipairs(STALLS) do
    local x, z = math.floor(stall.x), math.floor(stall.z)
    plaza.world:fill_blocks(vec3(x - 1, plaza.Y, z), vec3(x + 1, plaza.Y, z), "minecraft:barrel")
    for _, corner in ipairs({
      vec3(x - 1, 0, z - 1),
      vec3(x + 1, 0, z - 1),
      vec3(x - 1, 0, z + 1),
      vec3(x + 1, 0, z + 1),
    }) do
      plaza.world:fill_blocks(
        corner:with_y(plaza.Y),
        corner:with_y(plaza.Y + 2),
        "minecraft:spruce_fence"
      )
    end
    plaza.world:fill_blocks(
      vec3(x - 1, plaza.Y + 3, z - 1),
      vec3(x + 1, plaza.Y + 3, z + 1),
      "minecraft:" .. colours[i] .. "_wool"
    )
  end
  board = plaza.board(vec3(0.5, plaza.Y + 4, -11.5), 1.3)
  market.publish()
  crowd.start()
end)

nf.every(20, function()
  if board then
    board:set(boards.join(boards.price_lines(), { "<gray>/price and /buy work from every server" }))
  end
end)

nf.on("player_arrive", function(event)
  event.player:send_message(
    "<gold>Welcome to the market.</gold> <gray>/price <item> to ask, /buy <item> to buy, from any server."
  )
end)

return market
