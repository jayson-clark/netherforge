local this = this --[[@as ProjectBlock]]

-- A glow lamp is an altar: right-click one to open the altar menu (menus/altar), which infuses
-- what you set in its ring. Every placed lamp glimmers: block.json's `tick` is 40, so each one
-- hears `tick` every two seconds.

this:on("click", function(event)
  if event.click ~= "right" or event.hand ~= "main_hand" or event.player:is_sneaking() then
    return
  end
  -- The window's script reads this with this:context(): where its light comes from, so the
  -- infusion bursts out of the lamp rather than out of the player.
  event.player:open_menu("altar", { context = { lamp = event.block:location() } })
end)

this:on("tick", function(event)
  nf.particles.play("lamp_glow", event.block:location():offset(vec3(0.5, 1, 0.5)))
end)
