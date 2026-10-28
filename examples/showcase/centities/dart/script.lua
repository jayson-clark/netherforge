local this = this --[[@as Centity]]

-- A dart: one model (resource_packs/ui/models/dart.json) drawn along +Z, its point
-- forward. Launched with `aim = "nose"`, the server turns that node every tick to
-- point the way it flies, so it noses over as gravity takes it. It leaves a
-- streak of crit sparks while it flies.
this:on("tick", function()
  if not this:is_launched() then
    return
  end
  local world, here = this:world(), this:position()
  if world and here then
    world:spawn_particle("minecraft:crit", here, { count = 1 })
  end
end)
