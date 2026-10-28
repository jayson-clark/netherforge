local this = this --[[@as Centity]]

-- A runner that finds its way: click it and it runs to the far corner of its pen with `move_to`,
-- round the walls in the way, then back. `path_end` hears whether it got there.

local home = this:position()

this:on("click", function()
  if this:has_path() or not home then
    return
  end
  local properties = this:properties() or {}
  local goal = home + vec3(properties.dx or 0, 0, properties.dz or 3)
  if this:move_to(goal, { speed = 1.4 }) then
    assert(this:node("label")):set_display_text("<yellow>Running…")
  end
end)

this:on("path_end", function(event)
  local label = assert(this:node("label"))
  local here = this:position()
  if home and here and here:distance(home) > 1 then
    label:set_display_text(
      event.reached and "<green>Made it! Coming back" or "<red>Couldn't get there"
    )
    nf.after(20, function()
      this:move_to(home, { speed = 1.4 })
    end)
  else
    label:set_display_text("<green>Runner\n<gray>click me")
  end
end)
