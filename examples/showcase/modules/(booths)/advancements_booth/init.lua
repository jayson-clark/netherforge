-- Booth: advancements. advancements/*.json make a tab of the project's own in the advancements
-- screen, its criteria granted by scripts or by the game's own triggers; scripts read and grant
-- any advancement, the game's included.
local expo = require("expo")

local function progress_line(player, key, name)
  local progress = player:advancement_progress(key)
  if not progress then
    return "<gray>" .. name .. ": ?"
  end
  local done, total = #progress.done, #progress.done + #progress.remaining
  return ("%s%s <gray>%d/%d"):format(
    done == total and "<green>✔ " or "<white>",
    name,
    done,
    total
  )
end

expo.booth({
  id = "advancements",
  title = "Advancements",
  summary = "A tab of your own, granted by scripts or the game's triggers",
  icon = "minecraft:knowledge_book",
  lines = {
    "<gold>Advancements</gold> live in advancements/<id>.json.",
    "",
    "<white>Adventurer</white> <gray>the tab's root, granted on joining.",
    "<white>The Grand Tour</white> <gray>one criterion per booth: you meet one each time",
    "<gray>you step into a booth for the first time.",
    "<white>Treasure Hunter</white> <gray>three criteria, one per treasure roll, with a HUD bar.",
    "<white>Shiny!</white> <gray>hidden; the game's own trigger (a diamond in your inventory),",
    "<gray>or a script grants it. <white>Gem Hoarder</white> <gray>hangs under the library's tab.",
    "",
    "<gray>Press L to see the tab.",
  },
  build = function(booth)
    booth:kiosk(4, 4, "<yellow>My progress", function(player)
      player:send_message(table.concat({
        "<gold>Your advancements",
        progress_line(player, "adventurer", "Adventurer"),
        progress_line(player, "tour", "The Grand Tour"),
        progress_line(player, "treasure_hunter", "Treasure Hunter"),
        progress_line(player, "diamonds", "Shiny!"),
        progress_line(player, "minecraft:story/mine_stone", "Stone Age (the game's)"),
      }, "\n"))
    end)
    booth:caption(4, 4, "<white>Progress\n<gray>advancement_progress: criteria done and left.", 3.2)
    booth:kiosk(4, 8, "<yellow>Hand me a diamond", function(player)
      player:give_item({ kind = "minecraft:diamond" })
    end)
    booth:caption(
      4,
      8,
      "<white>The game's trigger\n<gray>A diamond in your inventory meets Shiny!'s `found`.",
      3.2
    )
  end,
})
