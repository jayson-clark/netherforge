-- Booth: recipes. Recipes in recipes/<id>.json of every type the game has (shaped, shapeless,
-- furnace, blasting, smoking, stonecutting, smithing), taking the project's own items as
-- ingredients, and one more a script registers while the server runs.
local expo = require("expo")

local FILES = {
  "ruby",
  "ruby_dust",
  "ruby_sword",
  "crystal_glass",
  "cut_crystal_glass",
  "glow_shard",
  "glow_lamp",
  "glow_pickaxe",
  "candy",
}

-- A recipe no file has: registered by this script, gone when it unloads.
nf.recipes.register("sparkler", {
  type = "shapeless",
  ingredients = { { item = "ruby" }, "minecraft:stick" },
  result = {
    kind = "minecraft:torch",
    count = 8,
    name = "<red>Sparkler",
    lore = { "<gray>From a recipe a script registered" },
  },
  category = "misc",
})

expo.booth({
  id = "recipes",
  title = "Recipes",
  summary = "Every recipe type, with your own items as ingredients",
  icon = "minecraft:crafting_table",
  lines = {
    "<gold>Recipes</gold> live in recipes/<id>.json:",
    "",
    "<white>shaped</white> <gray>ruby_sword, crystal_glass · <white>shapeless</white> <gray>ruby, glow_lamp",
    "<white>furnace</white> <gray>ruby_dust · <white>blasting</white> <gray>glow_shard · <white>smoking</white> <gray>candy",
    "<white>stonecutting</white> <gray>cut_crystal_glass · <white>smithing</white> <gray>glow_pickaxe",
    "",
    '<gray>An ingredient `{ item = "ruby" }` only takes a ruby: a ruby is paper,',
    "<gray>but never crafts as paper. Scripts add recipes with nf.recipes.register",
    "<gray>(a ruby and a stick make sparklers) and teach them with discover_recipe.",
  },
  build = function(booth)
    local stations = {
      { "minecraft:crafting_table", "Crafting table" },
      { "minecraft:furnace", "Furnace" },
      { "minecraft:blast_furnace", "Blast furnace" },
      { "minecraft:smoker", "Smoker" },
      { "minecraft:stonecutter", "Stonecutter" },
      { "minecraft:smithing_table", "Smithing table" },
    }
    for index, station in ipairs(stations) do
      local v = 1 + (index - 1) * 2
      expo.world:set_block(booth:cell(8, 1, v), station[1])
      booth:caption(8, v, "<white>" .. station[2], 2.5, 90)
    end
    booth:kiosk(3, 3, "<yellow>Take ingredients", function(player)
      player:give_item(nf.items.create("ruby", { count = 8 }))
      player:give_item(nf.items.create("shard", { count = 16 }))
      for _, kind in ipairs({
        "minecraft:redstone_block",
        "minecraft:emerald",
        "minecraft:stick",
        "minecraft:glass",
        "minecraft:amethyst_block",
        "minecraft:amethyst_shard",
        "minecraft:iron_pickaxe",
        "minecraft:glowstone_dust",
        "minecraft:coal",
      }) do
        player:give_item({ kind = kind, count = kind == "minecraft:iron_pickaxe" and 1 or 8 })
      end
    end)
    booth:caption(3, 3, "<white>Ingredients\n<gray>Everything the project's recipes take.", 3.2)
    booth:kiosk(3, 9, "<yellow>Learn every recipe", function(player)
      for _, id in ipairs(FILES) do
        player:discover_recipe(id)
      end
      player:discover_recipe("sparkler")
      player:send_message(
        "<gray>They're in your recipe book now (players can craft them either way)."
      )
    end)
    booth:caption(
      3,
      9,
      "<white>Recipe book\n<gray>player:discover_recipe puts them in the book.",
      3.2
    )
  end,
})
