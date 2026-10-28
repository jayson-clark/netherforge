local this = this --[[@as Centity]]

-- Required by script.lua. It runs once for each tower, in that tower's
-- script: `this` is the tower, and `count` is its own.
local turns = {}

local count = 0

-- Spins the turret with a sparkle around it, and says how many turns this tower has made.
function turns.spin()
  this:play_animation("spin")
  local here = this:location()
  if here then
    nf.particles.play("sparkle", here:offset(vec3(0, 2.5, 0)))
  end
  count = count + 1
  return count
end

return turns
