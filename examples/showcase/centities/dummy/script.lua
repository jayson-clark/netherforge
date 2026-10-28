local this = this --[[@as Centity]]

-- A training dummy with `health` (and its bar, always shown): a left click hits it for what the
-- held item's attack would roughly do, `damage` handlers may change that, and at 0 it falls over
-- (`removeOnDeath: false` keeps it), then stands up again whole.

this:on("click", function(event)
  if event.click ~= "left" then
    return
  end
  local held = event.player:held_item()
  local amount = (held and held.kind:find("sword")) and 6 or 2
  this:damage(amount, { source = event.player, cause = "melee" })
end)

this:on("damage", function(event)
  this:play_animation("wobble")
  local world, position = this:world(), this:position()
  if world and position then
    world:spawn_particle("minecraft:damage_indicator", position + vec3(0, 1.4, 0), { count = 3 })
  end
  log(("dummy took %.0f"):format(event.amount))
end)

this:on("death", function()
  local node = assert(this:node("root"))
  node:tween({ rotation = vec3(-80, 0, 0), ticks = 10, easing = "ease_in" })
  nf.after(60, function()
    node:tween({ rotation = vec3(0, 0, 0), ticks = 10 })
    this:set_health(assert(this:max_health()))
  end)
end)
