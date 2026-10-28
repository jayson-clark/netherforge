-- Booth: mobs and their AI. The game's own mobs, spawned and steered by scripts: goals written in
-- Lua run in the mob's own AI beside the game's, with priorities and the controls they claim.
local expo = require("expo")

-- The nearest player within `range` blocks of a mob who holds `kind` in their hand.
local function holder(mob, kind, range)
  local here = mob:position()
  local world = mob:world()
  if not here or not world then
    return nil
  end
  for _, player in ipairs(world:players()) do
    local held = player:held_item()
    local at = player:position()
    if
      held
      and at
      and at:distance(here) < range
      and (nf.items.id(held) == kind or held.kind == kind)
    then
      return player
    end
  end
  return nil
end

expo.booth({
  id = "mobs",
  title = "Mobs & AI goals",
  summary = "The game's mobs, with goals written in Lua",
  icon = "minecraft:sheep_spawn_egg",
  lines = {
    "<gold>Mobs</gold>: world:spawn_entity, then mob:add_goal.",
    "",
    "<gray>A goal says when it should start and continue, what it does each tick,",
    "<gray>its priority (smaller goes first) and which controls it claims (move,",
    "<gray>look, jump, target), so the game's own goals make way for it.",
    "",
    "<white>The sheep</white> <gray>has a goal: it follows anyone holding a ruby.",
    "<white>The pig</white> <gray>has had every goal cleared, and one added: it walks",
    "<gray>back and forth between the two posts, and nothing else.",
  },
  build = function(booth)
    expo.world:fill_blocks(booth:cell(3, 1, 1), booth:cell(9, 1, 1), "minecraft:oak_fence")
    expo.world:fill_blocks(booth:cell(3, 1, 11), booth:cell(9, 1, 11), "minecraft:oak_fence")
    expo.world:fill_blocks(booth:cell(3, 1, 1), booth:cell(3, 1, 11), "minecraft:oak_fence")
    -- The way in is a gate, which mobs can't open. It shuts itself behind visitors.
    local gate = booth:cell(3, 1, 6)
    expo.world:set_block(gate, "minecraft:oak_fence_gate[facing=east]")

    local sheep =
      booth:spawn_entity("minecraft:sheep", 6, 1, 3, { custom_name = "<white>Follows rubies" }) --[[@as Mob?]]
    if sheep then
      sheep:add_goal("follow_ruby", {
        priority = 2,
        controls = { "move", "look" },
        should_start = function(mob)
          return holder(mob, "ruby", 10) ~= nil
        end,
        tick = function(mob)
          local player = holder(mob, "ruby", 10)
          local at = player and player:position()
          if at then
            mob:move_to(at, { speed = 1.2 })
          end
        end,
        stop = function(mob)
          mob:stop_pathing()
        end,
      })
    end
    booth:caption(
      4,
      3,
      "<white>Sheep\n<gray>A Lua goal: hold a ruby (from the items booth) near it. Come in by the gate.",
      3
    )

    local posts = { booth:pos(5, 1, 8), booth:pos(8, 1, 8) }
    for _, post in ipairs(posts) do
      expo.world:set_block(post - vec3(0, 0, 0), "minecraft:oak_log")
    end
    local pig =
      booth:spawn_entity("minecraft:pig", 6.5, 1, 9, { custom_name = "<light_purple>Patrols" }) --[[@as Mob?]]
    if pig then
      pig:clear_goals()
      local going = 1
      pig:add_goal("patrol", {
        priority = 1,
        controls = { "move" },
        tick = function(mob)
          if not mob:has_path() then
            going = going % 2 + 1
            mob:move_to(posts[going] + vec3(0, 0, 1), { speed = 1 })
          end
        end,
      })
    end
    booth:caption(4, 9, "<white>Pig\n<gray>clear_goals, then one goal: patrol between the logs.", 3)

    -- Every couple of seconds: shut the gate if nobody is standing in it, and bring back a mob
    -- that got out anyway (through the gate while it stood open), to where it started.
    local homes = {}
    if sheep then
      homes[#homes + 1] = { mob = sheep, home = booth:at(6, 1, 3) }
    end
    if pig then
      homes[#homes + 1] = { mob = pig, home = booth:at(6.5, 1, 9) }
    end
    local inside_low, inside_high = booth:cell(4, 1, 2), booth:cell(9, 1, 10)
    local function in_pen(at)
      local x0, x1 =
        math.min(inside_low.x, inside_high.x), math.max(inside_low.x, inside_high.x) + 1
      return at.x >= x0 and at.x < x1 and at.z >= inside_low.z and at.z < inside_high.z + 1
    end
    local keeper
    keeper = nf.every(40, function()
      local block = expo.world:block(gate)
      if block and block:property("open") == "true" then
        local near = false
        for _, player in ipairs(expo.world:players()) do
          local at = player:position()
          if at and at:distance(gate + vec3(0.5, 0, 0.5)) < 1.5 then
            near = true
          end
        end
        if not near then
          block:set_property("open", "false")
        end
      end
      for _, entry in ipairs(homes) do
        local at = entry.mob:exists() and entry.mob:position()
        if at and not in_pen(at) then
          entry.mob:teleport(entry.home)
        end
      end
      if not (sheep and sheep:exists()) and not (pig and pig:exists()) then
        keeper:cancel()
      end
    end)
  end,
})
