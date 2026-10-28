local this = this --[[@as ProjectBlock]]

-- The ore's one script. It runs once for the block, not once per placed block,
-- and hears what players do with any ruby ore: the one in the quarry, the one
-- a player placed from the item.

-- Hitting or right-clicking it. Each placed block keeps its own table.
this:on("click", function(event)
  if event.click ~= "right" then
    return
  end
  local data = event.block:data()
  if data then
    data.touched = (data.touched or 0) + 1
    event.player:send_actionbar("<red>Ruby ore <gray>(touched " .. data.touched .. " times)")
  end
end)

-- Breaking it. The loot table has already rolled what it drops, and changing
-- `event.drops` changes it: a diamond pickaxe gets double.
this:on("break", function(event)
  local tool = event.player:held_item()
  if tool and tool.kind == "minecraft:diamond_pickaxe" then
    for _, item in ipairs(event.drops) do
      item.count = (item.count or 1) * 2
    end
  end
end)

-- `tick` in block.json is 100: every five seconds, each ore glints.
this:on("tick", function(event)
  event.block
    :world()
    :spawn_particle("minecraft:end_rod", event.block:position() + vec3(0.5, 1.1, 0.5))
end)
