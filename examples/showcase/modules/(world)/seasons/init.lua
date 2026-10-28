-- Wisps drift in only at night (their `spawning.time`) and only during wisp
-- season: `when: "wisp_season"` in centities/wisp/centity.json names this
-- condition. /wisps toggles the season; it starts on, and a restart keeps it.

local season = nf.data("seasons")
if season.wisps == nil then
  season.wisps = true
end

nf.spawning.condition("wisp_season", function()
  return season.wisps == true
end)

nf.commands.register("wisps", {
  description = "Turn wisp season on or off",
  permission = "showcase.seasons",
}, function(event)
  season.wisps = not season.wisps
  event.sender:send_message(
    season.wisps and "<aqua>Wisp season has begun." or "<gray>Wisp season is over."
  )
end)
