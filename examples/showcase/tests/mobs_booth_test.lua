-- Tests for the mobs booth's pen (modules/(booths)/mobs_booth), on a fake server.
local expo = require("expo")

local function build_expo()
  nf.test.advance(21)
  local ticks = 0
  while expo.building do
    nf.test.advance(1)
    ticks = ticks + 1
    assert(ticks < 20 * 60, "the expo took over a minute to build")
  end
end

nf.test.case("a sheep that gets out of the pen is brought back, and the gate shuts", function()
  build_expo()
  local booth = assert(expo.get("mobs"))
  local sheep
  for _, thing in ipairs(booth.spawned) do
    if thing.kind and thing:kind() == "minecraft:sheep" then
      sheep = thing
    end
  end
  assert(sheep, "the booth has a sheep")

  local gate = expo.world:block(booth:cell(3, 1, 6))
  assert(gate and gate:kind() == "minecraft:oak_fence_gate", "the way in is a gate")
  gate:set_property("open", "true")

  -- Out on the avenue, as if it had wandered through the open gate.
  sheep:teleport(booth:at(-2, 1, 6))
  assert(not booth:contains(assert(sheep:position())))
  nf.test.advance(41)
  assert(booth:contains(assert(sheep:position())), "it's back in the booth")
  assert(gate:property("open") == "false", "the gate shut with nobody in it")
end)
