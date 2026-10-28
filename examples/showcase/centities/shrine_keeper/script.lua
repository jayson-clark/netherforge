local this = this --[[@as Centity]]

-- The shrine keeper. Nothing spawns one by hand: structures/ruined_shrine.nbt holds a marker
-- tagged nf.centity.shrine_keeper, and each shrine the hills world generates (structures/
-- ruined_shrine.json: in the ruby grove) turns its marker into a keeper, once. The lantern on its
-- staff sways (`idle`, autoplay); it bows to whoever talks to it, and fills the shrine's chest
-- the first time it's greeted.

local REACH = 5

-- The chest nearest the keeper, if there's one within reach.
local function nearest_chest()
  local location = this:location()
  if not location then
    return nil
  end
  local world, centre = location.world, location.position:floor()
  for dx = -REACH, REACH do
    for dy = -2, 2 do
      for dz = -REACH, REACH do
        local block = world:block(centre + vec3(dx, dy, dz))
        if block and block:kind() == "minecraft:chest" then
          return block
        end
      end
    end
  end
  return nil
end

this:on("click", function(event)
  if event.click ~= "right" then
    return
  end
  local player = event.player
  local position = player:position()
  if position then
    this:look_at(position)
  end
  this:play_animation("bow")
  -- The instance's own saved table: kept with this keeper across restarts.
  local data = assert(this:data())
  local chest = not data.filled and nearest_chest()
  local inventory = chest and chest:inventory()
  if inventory then
    data.filled = true
    nf.loot.fill("shrine_chest", inventory, { player = player })
    player:send_message("<light_purple>The keeper nods at the chest: <gray>it's full now.")
  else
    player:send_message(
      "<light_purple>Shrine keeper: <gray>I came from a marker in a structure file. Your tour guide takes you home."
    )
  end
end)
