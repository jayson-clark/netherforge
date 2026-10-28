-- /companion: a wisp that floats along with you, riding you as you walk (a
-- centity following a player). Run it again to send the wisp away. It's gone
-- after a restart, and so is the follow: a companion is made fresh each time.

local companions = {} -- player name -> the wisp following them

nf.commands.register("companion", {
  description = "Summon a wisp that follows you; again to send it away",
  players_only = true,
}, function(event)
  local player = assert(event.player) -- players_only: never the console
  local key = player:name()

  local wisp = companions[key]
  if wisp and wisp:exists() then
    wisp:remove()
    companions[key] = nil
    player:send_message("<gray>The wisp drifts off.")
    return
  end

  wisp = nf.centities.spawn("wisp", assert(player:location()):offset(vec3(0, 1.5, 0)))
  if not wisp then
    player:send_message("<red>No wisp could be made here.")
    return
  end
  if not wisp:follow(player) then
    wisp:remove()
    player:send_message("<red>The wisp couldn't find you.")
    return
  end
  companions[key] = wisp
  player:send_message("<aqua>A wisp comes to your side.")
end)
