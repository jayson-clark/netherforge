local this = this --[[@as Menu]]

-- The shop window's script. Each player who opens the shop gets their own
-- window and their own copy of this script. The window only shows and
-- clicks: what is for sale and how buying works is the "shop" module's, so a
-- command, a dialog or a sign could sell the same things.
local shop = require("shop")

-- The menu file says what each slot looks like; the module says what it
-- costs. Putting the price in the lore here keeps the two from drifting apart.
this:on("open", function(event)
  for slot, offer in pairs(shop.offers) do
    local item = this:item(slot)
    if item then
      item.lore = item.lore or {}
      table.insert(item.lore, "<gray>Price: <gold>" .. offer.price .. " coins")
      this:set_item(slot, item)
    end
  end
  nf.task(function()
    event.player:send_actionbar("<gold>You have " .. shop.balance(event.player) .. " coins")
  end)
end)

this:on("click", function(event)
  -- The window is locked, so nothing moves, but a click in the player's own
  -- inventory is heard here too.
  if not event.in_menu then
    return
  end
  local slot = assert(event.index) -- a click in the window always has a slot
  if slot == 15 then
    event.player:close_menu()
  elseif shop.offers[slot] then
    -- Buying waits for the database, and only a task can wait.
    nf.task(function()
      local ok, message = shop.buy(event.player, slot)
      event.player:send_message((ok and "<green>" or "<red>") .. message)
    end)
  end
end)
