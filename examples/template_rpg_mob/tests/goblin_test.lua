-- Tests for the goblin and the slayers module, run by `netherforge test` (or the editor's Run tests).
-- Every test starts a fresh fake server with the project loaded.
local slayers = require("slayers")

-- A goblin next to a player, and the player.
local function goblin_and_player()
  local alex = nf.test.player("Alex")
  local goblin = assert(nf.centities.spawn("goblin", assert(alex:location()):offset(vec3(2, 0, 0))))
  return goblin, alex
end

local function count_goblins()
  return #nf.centities.all({ kind = "goblin" })
end

nf.test.case("a goblin takes four hits to kill, and shows its health", function()
  local goblin, alex = goblin_and_player()
  goblin:emit("goblin:hit", { player = alex, damage = 3 })
  assert(assert(goblin:node("label")):display_text() == "<green>Goblin <red>7/10")
  assert(goblin:exists())
  for _ = 1, 3 do
    goblin:emit("goblin:hit", { player = alex, damage = 3 })
  end
  assert(not goblin:exists(), "the goblin should be gone")
end)

nf.test.case("a kill is counted for the killer in the database", function()
  local goblin, alex = goblin_and_player()
  -- Kill it with one big hit, as a stronger weapon would.
  goblin:emit("goblin:hit", { player = alex, damage = 100 })
  nf.test.advance(2) -- the database works off the main thread

  local kills
  nf.task(function()
    kills = slayers.kills(alex)
  end)
  nf.test.advance(2)
  assert(kills == 1, "one kill should be counted, got " .. tostring(kills))
end)

nf.test.case("the loot table drops ears and gold, and sometimes nothing", function()
  -- loot/goblin_loot.json names the goblin_ear item: a rename of either keeps working.
  local ears, nuggets, rolls = 0, 0, 60
  for _ = 1, rolls do
    for _, item in ipairs(nf.loot.roll("goblin_loot")) do
      if nf.items.id(item) == "goblin_ear" then
        ears = ears + 1
      elseif item.kind == "minecraft:gold_nugget" then
        nuggets = nuggets + 1
      else
        error("the goblin dropped a " .. tostring(item.kind))
      end
    end
  end
  assert(
    ears > 0 and nuggets > 0,
    ("%d ears and %d nuggets in %d rolls"):format(ears, nuggets, rolls)
  )
end)

nf.test.case("goblins appear by themselves near a player, up to their cap", function()
  nf.test.player("Alex")
  assert(count_goblins() == 0)
  nf.test.advance(20 * 20)
  local goblins = nf.centities.all({ kind = "goblin" })
  assert(#goblins == 3, "the cap is 3 goblins near a player, got " .. #goblins)
  assert(goblins[1]:is_natural(), "they are temporary until kept")
end)

nf.test.case("the project may refuse a natural goblin", function()
  local alex = nf.test.player("Alex")
  alex:set_game_mode("creative")
  nf.test.advance(20 * 20)
  assert(count_goblins() == 0, "no goblins for a creative player")

  alex:set_game_mode("survival")
  nf.test.advance(20 * 20)
  assert(count_goblins() > 0, "but for a survival one there are")
end)
