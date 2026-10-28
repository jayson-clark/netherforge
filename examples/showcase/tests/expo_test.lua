-- Tests for the expo (modules/expo) and its booths, on a fake server with the whole project
-- loaded: every booth module has run and added its booth by the time a test starts.
local expo = require("expo")

-- The expo builds a second after it loads, then a booth at a time, as fast as they're cheap.
local function build_expo()
  nf.test.advance(21)
  local ticks = 0
  while expo.building do
    nf.test.advance(1)
    ticks = ticks + 1
    assert(ticks < 20 * 60, "the expo took over a minute to build")
  end
end

nf.test.case("every place on the avenue has a booth, and every booth builds", function()
  build_expo()
  for _, id in ipairs(expo.ORDER) do
    local booth = expo.get(id)
    assert(booth, "no module adds the " .. id .. " booth")
    assert(booth.problem == nil, id .. " didn't build: " .. tostring(booth.problem))
    assert(#booth.spawned > 0, id .. " has nothing in it")
    assert(
      booth.def.title and booth.def.summary and booth.def.lines,
      id .. " doesn't explain itself"
    )
  end
end)

nf.test.case("the Grand Tour has a criterion for every booth, and no more", function()
  local alex = nf.test.player("Alex")
  local progress = assert(alex:advancement_progress("tour"))
  local criteria = {}
  for _, name in ipairs(progress.remaining) do
    criteria[name] = true
  end
  for _, id in ipairs(expo.ORDER) do
    assert(criteria[id], "advancements/tour.json has no criterion for " .. id)
    criteria[id] = nil
  end
  assert(next(criteria) == nil, "a criterion with no booth: " .. tostring(next(criteria)))
end)

nf.test.case("stepping into a booth counts it, once", function()
  build_expo()
  local alex = nf.test.player("Alex")
  local booth = assert(expo.get("physics"))
  assert(expo.booth_at(booth:at(5, 1, 6)) == booth, "the middle of the booth is in it")
  assert(expo.booth_at(expo.SPAWN) == nil, "the hub isn't a booth")
  expo.enter(alex, booth)
  expo.enter(alex, nil)
  expo.enter(alex, booth)
  assert(expo.seen(alex).physics == true)
  local progress = assert(alex:advancement_progress("tour"))
  assert(#progress.done == 1 and progress.done[1] == "physics", "one criterion met")
end)

nf.test.case("booths on both sides face the avenue", function()
  local west, east = assert(expo.get("centities")), assert(expo.get("physics"))
  assert(west.west and not east.west)
  assert(west:pos(0, 1, 6).x < 0 and east:pos(0, 1, 6).x > 0)
  assert(
    math.abs(west:pos(0, 1, 6).z - east:pos(0, 1, 6).z) < 0.01,
    "a row's booths face each other"
  )
  assert(west:yaw_out() == -90 and east:yaw_out() == 90)
end)

nf.test.case("the tour guide's menu lists every booth, and takes you there", function()
  build_expo()
  local alex = nf.test.player("Alex")
  local menu = assert(expo.open_tour(alex))
  for index, id in ipairs(expo.ORDER) do
    local item = assert(menu:item(index - 1), "no item for " .. id)
    assert(item.name:find(assert(expo.get(id)).def.title, 1, true))
  end
  assert(expo.travel(alex, "wasm"))
  assert(
    expo.booth_at(assert(alex:location())) == nil,
    "the entrance is on the avenue, in front of it"
  )
  assert(
    assert(alex:location()).position:distance(assert(expo.get("wasm")):entrance().position) < 1
  )
end)

nf.test.case("joining hands out a tour guide, and only one", function()
  local alex = nf.test.player("Alex")
  nf.test.raise(
    "player_join",
    { player = alex, first_join = false, message = "Alex joined the game" }
  )
  assert(alex:inventory():count_item({ item = "guide" }) == 1)
end)
