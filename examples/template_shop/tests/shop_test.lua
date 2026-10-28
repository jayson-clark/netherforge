-- Tests for the shop, run by `netherforge test` (or the editor's Run tests).
-- Every test starts a fresh fake server, so every wallet starts at its 100 coins.
local shop = require("shop")

-- Runs [body] as a task (it may wait for the database), lets the server catch up,
-- and hands back what it returned. A mistake inside it fails the test.
local function in_task(body)
  local result
  nf.task(function()
    result = table.pack(body())
  end)
  -- Each database call lands a tick or so later, so give the task a few.
  for _ = 1, 10 do
    if result then
      break
    end
    nf.test.advance(1)
  end
  assert(result, "the task didn't finish")
  return table.unpack(result, 1, result.n)
end

nf.test.case("a new wallet starts with the starting coins", function()
  local alex = nf.test.player("Alex")
  assert(in_task(function()
    return shop.balance(alex)
  end) == 100)
end)

nf.test.case("buying takes the coins and gives the item", function()
  local alex = nf.test.player("Alex")
  local ok = in_task(function()
    return shop.buy(alex, 11)
  end)
  assert(ok, "Alex can afford bread")
  assert(alex:inventory():count_item("minecraft:bread") == 4)
  assert(in_task(function()
    return shop.balance(alex)
  end) == 98)
end)

nf.test.case("a project item is sold as the item, and a purchase is announced", function()
  local alex = nf.test.player("Alex")
  local announced
  nf.on("shop:purchased", function(event)
    announced = event
  end)
  in_task(function()
    return shop.buy(alex, 13)
  end)
  assert(alex:inventory():count_item({ item = "lucky_charm" }) == 1)
  assert(announced and announced.slot == 13 and announced.price == 25)
end)

nf.test.case("nobody spends coins they don't have", function()
  local alex = nf.test.player("Alex")
  local bought = 0
  for _ = 1, 5 do
    local ok = in_task(function()
      return shop.buy(alex, 13)
    end)
    bought = bought + (ok and 1 or 0)
  end
  assert(bought == 4, "100 coins buy four 25-coin charms, not " .. bought)
  assert(in_task(function()
    return shop.balance(alex)
  end) == 0)
  assert(alex:inventory():count_item({ item = "lucky_charm" }) == 4)

  -- Paying in is how a reward reaches the shop.
  in_task(function()
    shop.deposit(alex, 30)
  end)
  assert(in_task(function()
    return shop.balance(alex)
  end) == 30)
end)

nf.test.case("the window shows each price, taken from the module", function()
  local alex = nf.test.player("Alex")
  alex:open_menu("shop")
  nf.test.advance(2)
  local menu = assert(alex:menu())
  assert(assert(menu:item(11)).lore[1] == "<gray>Price: <gold>2 coins")
  local charm = assert(menu:item(13))
  assert(charm.lore[#charm.lore] == "<gray>Price: <gold>25 coins")
  assert(menu:item(15).kind == "minecraft:barrier", "the close button has no price")
end)
