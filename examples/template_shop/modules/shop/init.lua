-- The shop's rules: what is for sale, a coin wallet for every player, and
-- buying. The menu (menus/shop) is only a window onto this.
--
-- Wallets live in the package's own SQLite database (nf.db), so they survive
-- restarts. The module makes its table itself, so Copy into project brings
-- everything it needs. The project that uses it must declare
-- `"requires": { "db": true }` in netherforge.json, which this package does.
--
-- Everything that waits for the database (balance, deposit, buy) has to run in
-- a task (nf.task); the command handlers and the menu script start one.

local STARTING_COINS = 100

local shop = {}

--- What is for sale, by the menu slot that shows it: its price in coins and
--- the item the buyer gets (an Item table: a Minecraft kind or a project item).
---@type table<integer, { price: integer, give: Item }>
shop.offers = {
  [11] = { price = 2, give = { kind = "minecraft:bread", count = 4 } },
  [13] = { price = 25, give = { item = "lucky_charm" } },
}

local db = nf.db()

-- SQL always takes its values separately, as a list for the ?s, never glued
-- into the text. Statements to one database run in the order they were given,
-- so nothing below needs to wait for this one.
db:execute(
  "CREATE TABLE IF NOT EXISTS shop_wallets (player TEXT PRIMARY KEY, coins INTEGER NOT NULL)",
  {},
  function(_, err)
    if err then
      log("the shop couldn't make its wallets: " .. err)
    end
  end
)

-- A player is known by their id, which a rename doesn't change. The first time
-- anyone is seen they get their starting coins; later this does nothing.
local function open_wallet(player)
  db:execute(
    "INSERT OR IGNORE INTO shop_wallets (player, coins) VALUES (?, ?)",
    { player:id(), STARTING_COINS }
  )
end

--- How many coins a player has. Only in a task.
---@param player Player
---@return integer
function shop.balance(player)
  open_wallet(player)
  local rows = assert(db:query("SELECT coins FROM shop_wallets WHERE player = ?", { player:id() }))
  return rows[1].coins
end

--- Gives a player coins (a quest reward, a minigame prize). Only in a task.
---@param player Player
---@param coins integer
function shop.deposit(player, coins)
  open_wallet(player)
  assert(
    db:execute("UPDATE shop_wallets SET coins = coins + ? WHERE player = ?", { coins, player:id() })
  )
end

--- Sells the offer in a menu slot to a player. Only in a task.
--- Charging is one UPDATE that only matches when they can afford it, so two
--- clicks at once can never spend the same coins twice.
---@param player Player
---@param slot integer
---@return boolean bought
---@return string message What to tell the player.
function shop.buy(player, slot)
  local offer = shop.offers[slot]
  if not offer then
    return false, "That isn't for sale."
  end
  open_wallet(player)
  local charged, err = db:execute(
    "UPDATE shop_wallets SET coins = coins - ? WHERE player = ? AND coins >= ?",
    { offer.price, player:id(), offer.price }
  )
  if not charged then
    return false, "The shop is closed: " .. err
  elseif charged.changes == 0 then
    return false, "You need " .. offer.price .. " coins."
  end
  player:give_item(offer.give)
  -- Anything may listen: a quest that counts purchases, a log of sales.
  nf.emit("shop:purchased", { player = player, slot = slot, price = offer.price })
  return true, "Bought for " .. offer.price .. " coins."
end

nf.commands.register("shop", {
  description = "Open the shop",
  players_only = true,
}, function(event)
  assert(event.player):open_menu("shop")
end)

nf.commands.register("coins", {
  description = "How many coins you have",
  players_only = true,
}, function(event)
  local player = assert(event.player)
  nf.task(function()
    player:send_message("<gold>You have " .. shop.balance(player) .. " coins.")
  end)
end)

return shop
