-- Booth: custom blocks. Blocks of the project's own (blocks/<id>/block.json) are drawn by its
-- resource pack and held in the world as states of the game's blocks (note blocks, leaves,
-- tripwire), with their own hardness, tools, drops (a loot table), sounds and script. And game
-- blocks (game_blocks/*.json) change the rules of blocks the game already has.
local expo = require("expo")

expo.booth({
  id = "blocks",
  title = "Custom blocks",
  summary = "Blocks of your own: models, hardness, drops, sounds and scripts",
  icon = "minecraft:note_block",
  lines = {
    "<gold>Blocks</gold> live in blocks/<id>/block.json.",
    "",
    "<gray>Each names a look from the resource pack, a carrier block that holds it",
    "<gray>in the world, its hardness, the tool it wants, a loot table for drops,",
    "<gray>its own place and break sounds, and a script hearing clicks, breaks,",
    "<gray>and ticks. A project item with `block` places it.",
    "",
    "<white>Game blocks</white> <gray>(game_blocks/*.json) give the game's own blocks",
    "<gray>new hardness, tools and drops: the deepslate bricks here.",
    "",
    "<gray>Take a pick from the kiosk and try mining them.",
  },
  build = function(booth)
    local function place(id, u, v)
      local block = nf.blocks.get(id)
      if block then
        block:place(booth:at(u, 1, v))
      end
    end
    place("ruby_ore", 6, 2)
    booth:caption(
      6,
      2,
      "<white>Ruby ore\n<gray>Needs a pickaxe; drops rubies (loot/ruby_ore.json, doubled by a diamond pick in its script); glints every 5s (`tick`).",
      2.8,
      130
    )
    place("ruby_crystal", 6, 4)
    booth:caption(
      6,
      4,
      "<white>Ruby crystal\n<gray>A model with elements, on the leaves carrier; it turns to face the way it's placed.",
      2.8,
      130
    )
    place("crystal_glass", 6, 6)
    booth:caption(
      6,
      6,
      "<white>Crystal glass\n<gray>Its own place and break sounds, from the pack's sounds/ folder.",
      2.8,
      130
    )
    place("glow_lamp", 6, 8)
    booth:caption(
      6,
      8,
      "<white>Glow lamp\n<gray>Different top and side textures. Glimmers every 2s; right-click opens the altar menu.",
      2.8,
      130
    )
    place("floating_lamp", 6, 10)
    booth:caption(
      6,
      10,
      "<white>Floating lamp\n<gray>A block drawn by a centity (block.json's `centity`) instead of a model.",
      2.8,
      130
    )
    expo.world:set_block(booth:cell(8, 1, 6), "minecraft:deepslate_bricks")
    expo.world:set_block(booth:cell(8, 2, 6), "minecraft:deepslate_bricks")
    booth:caption(
      8,
      6,
      "<white>Game block\n<gray>The game's deepslate bricks, made harder, with new drops and a new break sound.",
      3.4
    )

    booth:kiosk(2, 4, "<yellow>Take a pick and blocks", function(player)
      player:give_item({ kind = "minecraft:diamond_pickaxe" })
      player:give_item(nf.items.create("ruby_pick"))
      for _, id in ipairs({ "ruby_ore", "crystal_glass", "glow_lamp" }) do
        player:give_item(nf.items.create(id, { count = 4 }))
      end
      player:send_message(
        "<gray>Mine or place them anywhere. The ruby pick only breaks ruby ore and planks in adventure mode (its canBreak)."
      )
    end)
  end,
})
