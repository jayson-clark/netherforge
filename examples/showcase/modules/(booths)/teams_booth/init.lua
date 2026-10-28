-- Booth: teams. The project's scoreboard teams: prefixes and suffixes on names, name and glow
-- colours, friendly fire, name tags and collision.
local expo = require("expo")

-- Teams belong to this script: made in its body, gone when it unloads.
local red =
  nf.teams.create("expo_red", { prefix = "<red>[Red] ", color = "red", friendly_fire = false })
local blue = nf.teams.create(
  "expo_blue",
  { prefix = "<blue>[Blue] ", color = "blue", collision = "push_other_teams" }
)

local function join(player, team, other)
  other:remove_member(player)
  team:add_member(player)
  player:set_glowing(true)
  player:send_message(
    ("<gray>You're on %s<gray>: see the prefix in chat and the player list, and your glow colour."):format(
      team:prefix() or team:name()
    )
  )
end

expo.booth({
  id = "teams",
  title = "Teams",
  summary = "Name prefixes, colours, glow, friendly fire and collision",
  icon = "minecraft:red_banner",
  lines = {
    "<gold>Teams</gold>: nf.teams.create.",
    "",
    "<gray>A team puts a prefix and suffix on its members' names (over their heads",
    "<gray>and in the player list), colours their names and their glow, and decides",
    "<gray>whether they hurt each other, see each other invisible, see name tags",
    "<gray>and push each other.",
    "",
    "<gray>Red has friendly fire off. Blue members pass through each other.",
  },
  build = function(booth)
    booth:kiosk(5, 3, "<red>Join red", function(player)
      join(player, red, blue)
    end)
    booth:kiosk(5, 6, "<blue>Join blue", function(player)
      join(player, blue, red)
    end)
    booth:kiosk(5, 9, "<gray>Leave teams", function(player)
      red:remove_member(player)
      blue:remove_member(player)
      player:set_glowing(false)
    end)
    booth:caption(5, 6, "<white>Pick a side\n<gray>Your glow is drawn in the team's colour.", 3.4)
  end,
})
