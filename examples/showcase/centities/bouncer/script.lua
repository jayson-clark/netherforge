local this = this --[[@as Centity]]

-- A body (centity.json's `body`): the server walks, drops and bounces the whole centity against
-- the blocks every tick. Click it and it hops away from you; it walks back home by itself.

local home = this:position()

this:on("click", function(event)
  local here, from = this:position(), event.player:position()
  if not here or not from then
    return
  end
  local away = (here - from):flat()
  if away:length() > 0.01 then
    this:set_velocity(away:normalized() * 6)
  end
  this:jump(1.6)
end)

-- Twice a second: walk toward home, stop once there.
this:on("tick", function()
  local here = this:position()
  if not here or not home then
    return
  end
  local back = (home - here):flat()
  if back:length() > 0.5 then
    this:set_walk_velocity(back:normalized() * 2)
  else
    this:set_walk_velocity(nil)
  end
end, { every = 10 })

this:on("land", function()
  local world, here = this:world(), this:position()
  if world and here then
    world:play_sound("minecraft:block.slime_block.fall", here, { volume = 0.6 })
  end
end)
