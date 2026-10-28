-- Booth: loot tables. loot/<id>.json: pools of weighted entries (items, other tables, the game's
-- tables, nothing), rolls and bonus rolls, conditions on the player, the tool and chance.
local expo = require("expo")

expo.booth({
  id = "loot",
  title = "Loot tables",
  summary = "Weighted pools, nested tables, conditions and luck",
  icon = "minecraft:chest",
  lines = {
    "<gold>Loot tables</gold> live in loot/<id>.json.",
    "",
    "<gray>loot/treasure.json has two pools: gems (1-2 rolls, bonus rolls with luck,",
    "<gray>an entry with a 50% chance, an empty entry), and extra, only for a player,",
    "<gray>rolling another of ours (junk), the library's gems, or the game's",
    "<gray>simple_dungeon chest.",
    "",
    "<gray>loot/junk.json's diamond needs Looting II; its emerald a diamond sword.",
    "<gray>Blocks drop by loot tables too, and so do the shrine chests in the hills.",
    "<gray>Scripts roll with nf.loot.roll and fill chests with nf.loot.fill.",
  },
  build = function(booth)
    local chest = booth:cell(7, 1, 6)
    expo.world:set_block(chest, "minecraft:chest")
    booth:caption(
      7,
      6,
      "<white>Treasure chest\n<gray>Filled by nf.loot.fill from loot/treasure.json.",
      2.6
    )
    booth:kiosk(4, 6, "<yellow>Refill the chest", function(player)
      local block = expo.world:block(chest)
      local inventory = block and block:inventory()
      if inventory then
        inventory:clear()
        nf.loot.fill(
          "treasure",
          inventory,
          { player = player, tool = player:equipment("main_hand") }
        )
      end
    end)
    booth:kiosk(4, 2, "<yellow>Roll treasure", function(player)
      local items =
        nf.loot.roll("treasure", { player = player, tool = player:equipment("main_hand") })
      for _, item in ipairs(items) do
        player:give_item(item)
      end
      player:send_message(
        ("<gold>%d stacks</gold> <gray>rolled. Rolling counts towards Treasure Hunter, with its HUD bar."):format(
          #items
        )
      )
      nf.emit("rewards:rolled", { player = player })
    end)
    booth:caption(
      4,
      2,
      "<white>Roll for yourself\n<gray>nf.loot.roll; what you hold counts for the table's conditions.",
      3.2
    )
    booth:kiosk(4, 10, "<yellow>Roll with luck 10", function(player)
      local items = nf.loot.roll("treasure", { player = player, luck = 10 })
      for _, item in ipairs(items) do
        player:give_item(item)
      end
      player:send_message(
        ("<gold>%d stacks</gold> <gray>with luck: more bonus rolls, and the ruby's quality weighs in."):format(
          #items
        )
      )
    end)
    booth:caption(4, 10, "<white>Luck\n<gray>bonusRolls and each entry's quality.", 3.2)
  end,
})
