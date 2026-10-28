local this = this --[[@as ProjectItem]]

-- Raising a lantern. What it does depends on the stack's own data (item.json's lantern carries
-- none; the altar's infusion gives one `kindled = true`), so two lanterns of the same item
-- behave differently.
this:on("use", function(event)
  event:cancel()
  local player = event.player
  local here = player:location()
  if not here then
    return
  end
  if event.item.data and event.item.data.kindled then
    nf.particles.play("burst", here:offset(vec3(0, 1.2, 0)), { scale = 0.6 })
    player:send_actionbar("<light_purple><glyph:ui/spark> The kindled lantern blazes")
  else
    nf.particles.play("lamp_glow", here:offset(vec3(0, 1.6, 0)))
    player:send_actionbar("<gray>The lantern is cold. A glow lamp's altar could kindle it.")
  end
end)
