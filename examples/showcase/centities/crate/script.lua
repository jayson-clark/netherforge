local this = this --[[@as Centity]]

-- A crate that drops from where it's spawned, bounces a little and settles.
-- Click it to shove it away from you, at the point you clicked: hit it high and
-- it topples, low and it slides.
local box = assert(this:node("box"))

box:on("sleep", function()
  log(("crate settled at %.3f"):format(box:translation().y))
end)

box:on("click", function(event)
  -- Where your line of sight met it, or the middle of its top face, wherever it has rolled to.
  local at = event.hit_position or box:to_world(vec3(0.5, 1, 0.5))
  if at == nil then
    return
  end
  -- The shove runs along your line of sight, flattened so a click from above
  -- doesn't just press it into the floor, with a little lift to unstick it.
  local eye = event.player:eye_position()
  if eye == nil then
    return
  end
  local away = (at - eye):flat()
  if away:length() < 1e-4 then
    return
  end
  box:apply_impulse_at(away:normalized() * 3 + vec3(0, 1.5, 0), at)
end)
