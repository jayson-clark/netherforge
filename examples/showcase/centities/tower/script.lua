local this = this --[[@as Centity]]

-- Turning lives in turns.lua, beside this script. A file beside a centity's
-- script runs as part of it, so every tower has its own copy: each counts
-- its own turns.
local turns = require("turns")

-- Clicking any part of the tower spins its turret, with a sparkle around it.
-- The turret is an empty pivot node in the middle of the tower's top, with the
-- stair and the flag under it, so it turns about its middle rather than about
-- the stair's corner.
this:on("click", function(event)
  local count = turns.spin()
  event.player:send_actionbar("<gold>The tower turns.</gold> <gray>Turns of this tower: " .. count)
end)
