-- The demo's network parts, on a fake network: in a test this server is "test" (so it plays the
-- lobby), and the other servers are ones the test adds and plays with nf.test.network_*.
local net = require("net")
local chat = require("chat")
local economy = require("economy")
local market = require("market")
local arena = require("arena")
local presence = require("presence")
local boards = require("boards")

--- Runs `fn` in a task and moves time on until it's done; returns what it returned.
local function wait_for(fn)
  local results
  nf.task(function()
    results = table.pack(fn())
  end)
  for _ = 1, 20 do
    if results then
      return table.unpack(results, 1, results.n)
    end
    nf.test.advance(1)
  end
  error("the task never finished")
end

nf.test.case(
  "a server's name picks its part, and the lobby is anything that isn't the others",
  function()
    assert(net.name() == "test" and net.role() == "lobby")
    assert(net.role_of("arena") == "arena" and net.role_of("market") == "market")
    assert(net.role_of("hub") == "lobby" and net.role_of(nil) == "lobby")
    assert(net.server_for("arena") == nil and not net.is_whole())
    nf.test.network_server("arena")
    nf.test.network_server("market")
    nf.test.advance(1)
    assert(net.server_for("arena") == "arena" and net.is_whole())
  end
)

nf.test.case("chat goes out to every server, and what's said elsewhere is shown here", function()
  nf.test.network_server("arena")
  nf.test.network_received("arena", "chat")
  local ann = nf.test.player("Ann")
  nf.test.raise("player_chat", { player = ann, message = "hi all", format = "" })
  nf.test.advance(1)
  local said = nf.test.network_received("arena", "chat")
  assert(said[1].value.name == "Ann" and said[1].value.text == "hi all")

  nf.test.network_publish("arena", "chat", { name = "Flint", text = "<b>gg</b>" })
  nf.test.advance(1)
  local last = chat.recent[#chat.recent]
  assert(last.server == "arena" and last.name == "Flint")
  assert(
    chat.line("arena", "Flint", "<b>gg</b>"):find("\\<b>gg", 1, true),
    "what's said is never read as tags"
  )
end)

nf.test.case("the daily coins are claimed once across the whole network", function()
  nf.test.network_server("arena")
  assert(wait_for(function()
    return economy.claim_daily("Ann")
  end) == true)
  nf.test.advance(2)
  assert(economy.balance("Ann") == economy.DAILY_COINS)
  local again, where = wait_for(function()
    return economy.claim_daily("Ann")
  end)
  assert(again == false and where == "test")

  -- Claimed on the arena first: here it's refused, saying where.
  nf.test.network_write("arena", "daily", "Bob", "arena")
  nf.test.advance(1)
  local paid, there = wait_for(function()
    return economy.claim_daily("Bob")
  end)
  assert(paid == false and there == "arena")
end)

nf.test.case(
  "buying asks the market, takes the coins and gives the item, or puts it all back",
  function()
    nf.test.network_server("market")
    local asked = {}
    nf.test.network_answer("market", "market", function(request)
      asked[#asked + 1] = request.action
      return request.action == "buy" and { price = 30 } or {}
    end)
    nf.test.network_write("market", "coins", "Ann", 100)
    nf.test.network_write("market", "coins", "Bob", 10)
    nf.test.advance(1)

    local ann = nf.test.player("Ann")
    assert(wait_for(function()
      return economy.buy(ann, "golden_apple")
    end))
    nf.test.advance(2)
    assert(economy.balance("Ann") == 70)
    assert(ann:inventory():count_item({ kind = "minecraft:golden_apple" }) == 1)

    local bob = nf.test.player("Bob")
    local bought, why = wait_for(function()
      return economy.buy(bob, "diamond")
    end)
    nf.test.advance(2)
    assert(not bought and tostring(why):find("costs 30", 1, true), tostring(why))
    assert(economy.balance("Bob") == 10, "a short purse is paid back")
    assert(asked[#asked] == "return", "the item goes back to the market")
  end
)

nf.test.case("without a market server, there's nothing to buy", function()
  local ann = nf.test.player("Ann")
  local bought, why = wait_for(function()
    return economy.buy(ann, "diamond")
  end)
  assert(not bought and tostring(why):find("closed", 1, true))
end)

nf.test.case(
  "the market quotes, sells from its stock with demand putting the price up, and takes returns",
  function()
    local quote = market.answer({ action = "quote", item = "diamond" })
    assert(quote.price == 40 and quote.stock == 8)
    local sale = market.answer({ action = "buy", item = "diamond", buyer = "Ann" })
    assert(sale.price == 40)
    local after = market.answer({ action = "quote", item = "diamond" })
    assert(after.stock == 7 and after.price > 40)
    assert(market.answer({ action = "return", item = "diamond" }).stock == 8)
    for _ = 1, 8 do
      market.answer({ action = "buy", item = "diamond" })
    end
    assert(market.answer({ action = "buy", item = "diamond" }).refused:find("sold out", 1, true))
    assert(market.answer({ action = "quote", item = "dirt" }).refused)
    -- However much is bought, a price stays within twice its base.
    assert(market.answer({ action = "quote", item = "diamond" }).price <= 80)
  end
)

nf.test.case("a player sent from the arena steps out of the lobby's portal to the arena", function()
  nf.test.network_server("arena")
  local ann = nf.test.network_send("arena", "Ann", { from = "arena" })
  nf.test.advance(2)
  local position = ann:position()
  assert(position.x == 11.5 and position.z == 0.5, tostring(position))
end)

nf.test.case("every server says who it has, and anyone can be found by name", function()
  nf.test.network_server("arena")
  nf.test.player("Ann")
  nf.test.advance(41)
  local mine = assert(net.population:get("test"))
  assert(mine.players[1] == "Ann")

  nf.test.network_write("arena", "population", "arena", { players = {}, bots = { "Flint" } })
  nf.test.advance(1)
  local server, bot = presence.find("flint")
  assert(server == "arena" and bot == true)
  assert(presence.find("Ann") == "test")
  assert(presence.find("nobody") == nil)
  local lines = table.concat(boards.network_lines(), "\n")
  assert(lines:find("Arena", 1, true) and lines:find("1 online", 1, true), lines)
end)

nf.test.case("an arena win is a point, coins and news for the whole network", function()
  nf.test.network_server("market")
  nf.test.network_received("market", "news")
  arena.record("Flint", "Rook", false)
  nf.test.advance(3)
  assert(boards.top_wins(1)[1].name == "Flint")
  assert(economy.balance("Flint") == arena.PRIZE.bot)
  local news = nf.test.network_received("market", "news")
  assert(news[1].value.text:find("Flint", 1, true))
end)

nf.test.case(
  "a bot travels only to a server that's up and not full, and only if this one keeps a few",
  function()
    local crowd = require("crowd")
    for _, name in ipairs({ "Nova", "Pixel", "Mossy" }) do
      crowd.members[name] = { name = name, busy = false, leaving = false }
    end
    assert(crowd.can_travel("arena") == nil, "the arena isn't up")
    nf.test.network_server("arena")
    nf.test.advance(1)
    assert(crowd.can_travel("arena") == "arena")
    assert(crowd.can_travel("lobby") == nil, "this is the lobby")

    local full = { "Flint", "Rook", "Ember", "Kestrel", "Tansy", "Bramble", "Sable" }
    nf.test.network_write("arena", "population", "arena", { players = {}, bots = full })
    nf.test.advance(1)
    assert(crowd.can_travel("arena") == nil, "the arena is full")

    nf.test.network_write("arena", "population", "arena", { players = {}, bots = { "Flint" } })
    crowd.members.Mossy = nil
    nf.test.advance(1)
    assert(crowd.can_travel("arena") == nil, "the lobby keeps two")
  end
)
