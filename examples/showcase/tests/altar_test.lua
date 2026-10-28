-- Tests for the altar: its rules (modules/altar) and its window (menus/altar). Every test starts on
-- a fresh fake server, so every player starts with an empty inventory.
local altar = require("altar")

local function give(player, id, count)
  player:give_item(nf.items.create(id, { count = count }))
end

local function count(player, id)
  return player:inventory():count_item({ item = id })
end

nf.test.case("a cold lantern is kindled for three shards, and only once", function()
  local alex = nf.test.player("Alex")
  give(alex, "shard", 5)
  local lantern = nf.items.create("lantern")
  assert(assert(altar.rule_for(lantern)).name == "kindle")
  local kindled, rest = altar.infuse(alex, lantern)
  assert(kindled and kindled.data and kindled.data.kindled == true, "the stack carries `kindled`")
  assert(nf.items.id(kindled) == "lantern" and rest == nil)
  assert(count(alex, "shard") == 2, "three of five shards are taken")
  assert(altar.rule_for(kindled) == nil, "a kindled lantern has nothing more to take")
end)

nf.test.case("nothing is taken when the altar can't pay or doesn't answer", function()
  local alex = nf.test.player("Alex")
  give(alex, "shard", 3)
  local result, why = altar.infuse(alex, { kind = "minecraft:golden_helmet" })
  assert(result == nil and tostring(why):find("4 glow shards", 1, true), tostring(why))
  assert(count(alex, "shard") == 3)
  assert(altar.infuse(alex, { kind = "minecraft:diamond" }) == nil)
  assert(altar.describe({ kind = "minecraft:diamond" })[1]:find("doesn't answer", 1, true))
end)

nf.test.case("a stack in the ring is infused one at a time", function()
  local alex = nf.test.player("Alex")
  give(alex, "shard", 2)
  local candy, rest = altar.infuse(alex, { kind = "minecraft:glass_bottle", count = 3 })
  assert(nf.items.id(assert(candy)) == "candy")
  assert(type(rest) == "table" and rest.count == 2, "two bottles go back")
end)

nf.test.case("the altar's window describes the ring, and gives it back on close", function()
  local alex = nf.test.player("Alex")
  local menu = assert(alex:open_menu("altar"))
  local button = assert(menu:item(22))
  assert(nf.items.id(button) == "shard" and button.name == "<light_purple>Infuse")
  menu:set_item(13, nf.items.create("lantern"))
  alex:close_menu()
  nf.test.advance(2)
  assert(count(alex, "lantern") == 1, "what was in the ring comes back")
end)
