-- Booth: natural spawning. A centity's `spawning` in centity.json makes it appear by itself, the
-- way mobs do: in some worlds and biomes, on some blocks, in some light, at some time, in groups,
-- up to a cap, and only when a script's condition says so.
local expo = require("expo")

expo.booth({
  id = "spawning",
  title = "Natural spawning",
  summary = "Centities that appear by themselves, by rules and conditions",
  icon = "minecraft:spawner",
  lines = {
    "<gold>Natural spawning</gold>: `spawning` in centity.json.",
    "",
    "<white>Tortoise</white> <gray>appears in the hills world's ruby grove, on grass, in light,",
    "<gray>in ones and twos, at most 3 near a player. Feed it berries and it stays.",
    "<white>End wisp</white> <gray>appears in the End at night on end stone, but only while",
    "<gray>`wisp_season` is on: nf.spawning.condition, toggled with /wisps.",
    "",
    "<gray>A natural centity isn't saved and goes when nobody is near, unless",
    "<gray>`keep` makes it the world's. `centity_natural_spawn` can cancel one.",
  },
  build = function(booth)
    booth:spawn("tortoise", 6, 1, 3)
    booth:caption(
      6,
      3,
      "<white>Tortoise\n<gray>This one was spawned by the booth; in the hills they come by themselves.",
      2.8
    )
    booth:spawn("wisp", 6, 2, 9)
    booth:caption(
      6,
      9,
      "<white>End wisp\n<gray>Its season: /wisps toggles the condition (saved with nf.data).",
      3.2
    )
    booth:portal(2, 6, "<light_purple>Go to the hills", "hills")
  end,
})
