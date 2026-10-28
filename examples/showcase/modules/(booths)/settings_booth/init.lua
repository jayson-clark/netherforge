-- Booth: settings, commands and permissions. What a server's owner changes without touching the
-- scripts (netherforge.json's settings), the slash commands modules add, and the permission nodes
-- the project may grant.
local expo = require("expo")

local SETTINGS = { "greeting", "show_welcome", "treasure_rolls", "tour_bossbar" }

local panel ---@type Centity?

local function show()
  local lines = { "<gold>Settings, live</gold> <gray>(nf.config)" }
  for _, name in ipairs(SETTINGS) do
    lines[#lines + 1] = ("<white>%s</white> <gray>= <aqua>%s"):format(
      name,
      nf.text.escape(tostring(nf.config(name)))
    )
  end
  lines[#lines + 1] = ""
  lines[#lines + 1] = "<gray>Change one in the editor's Settings, or /nf settings, and watch."
  local node = panel and panel:exists() and panel:node("text")
  if node then
    node:set_display_text(table.concat(lines, "\n"))
  end
end

-- Listening for setting_changed keeps this module running through a change: a script that reads
-- a setting without listening is restarted instead (modules/rewards is one).
nf.on("setting_changed", function()
  show()
end)

expo.booth({
  id = "settings",
  title = "Settings, commands & permissions",
  summary = "Owner settings, typed slash commands and granted permission nodes",
  icon = "minecraft:command_block",
  lines = {
    "<gold>Settings</gold> <gray>netherforge.json's settings, read with nf.config.",
    "<gold>Commands</gold> <gray>nf.commands.register: typed arguments (numbers with a range,",
    "<gray>choices, players, positions...), subcommands, permissions, tab completion.",
    "<gold>Permissions</gold> <gray>player:set_permission, for nodes netherforge.json's",
    "<gray>allow.permissions lists (showcase.*); kept and given back on every join.",
    "",
    "<white>Try:</white> <gray>/tower 4  /shop  /visits  /treasure chest  /quests",
    "<gray>/milestones  /companion  /flyby  /wisps  /perm list <you>  /tour  /expo",
  },
  build = function(booth)
    panel = booth:placard(7, 1.6, 4, "<gray>…", { width = 260 })
    show()
    booth:kiosk(3, 9, "<yellow>Grant me showcase.builder", function(player)
      player:set_permission("showcase.builder", true)
      player:send_message(
        "<green>Granted. <gray>/tower now works for you (it needs showcase.builder)."
      )
    end)
    booth:caption(
      3,
      9,
      "<white>A granted node\n<gray>Kept and given back every time you join.",
      3.2
    )
  end,
})
