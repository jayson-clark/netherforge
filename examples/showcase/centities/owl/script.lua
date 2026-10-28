local this = this --[[@as Centity]]

-- An owl made of the resource pack's own models (resource_packs/ui/models/owl_*.json):
-- a body, a head and two wings, one model display each, so every part moves on its
-- own. The file animates the body (`breathe`, additive, always on) and the wings
-- (`flap`); this script turns the head to watch whoever is nearest and blinks it
-- by swapping its model.
local head = assert(this:node("head"))

-- How far away it notices a player, in blocks.
local WATCH = 10

local function nearest_eyes()
  local world, here = this:world(), this:position()
  if world == nil or here == nil then
    return nil
  end
  local best, best_distance = nil, WATCH
  for _, player in ipairs(world:players()) do
    local eyes = player:eye_position()
    local distance = eyes and (eyes - here):length()
    if distance and distance < best_distance then
      best, best_distance = eyes, distance
    end
  end
  return best
end

-- Every tick, so the head follows smoothly: each new rotation eases over a tick.
local watching = false
this:on("tick", function()
  local eyes = nearest_eyes()
  if eyes then
    head:look_at(eyes)
    watching = true
  elseif watching then
    -- Nobody near: face front again, once.
    head:tween({ rotation = vec3.zero, ticks = 15 })
    watching = false
  end
end)

-- A blink every few seconds: the shut-eyed model for three ticks.
nf.task(function()
  while true do
    nf.wait(math.random(50, 110))
    head:set_model("ui/owl_head_blink")
    nf.wait(3)
    head:set_model("ui/owl_head")
  end
end)

this:on("click", function(event)
  this:play_animation("flap")
  local world, here = this:world(), this:position()
  if world and here then
    world:play_sound("minecraft:entity.parrot.fly", here + vec3(0, 2, 0), { pitch = 0.7 })
  end
  event.player:send_actionbar("<gold>Hoo!")
end)
