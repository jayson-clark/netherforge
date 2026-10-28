-- Booth: terrain scripts. A terrain file can name a script (terrain/<id>.lua) whose stages change
-- what the file makes, point by point: here, the sky islands' ground is hollowed away.
local expo = require("expo")

expo.booth({
  id = "terrain_scripts",
  title = "Terrain scripts",
  summary = "Lua that reshapes a terrain as it generates: floating islands",
  icon = "minecraft:amethyst_block",
  lines = {
    "<gold>The sky world</gold> <gray>is netherforge.json's `sky`: terrain/sky_islands.json.",
    "",
    "<gray>Its 3D density makes islands round y 112 over solid ground; its script,",
    "<gray>terrain/sky_islands.lua, has one stage, `density`, that says nothing below",
    "<gray>y 72 is solid. What's left floats.",
    "",
    "<gray>A script runs on the generator's threads, within the file's `budget`,",
    "<gray>the same on the server and in the editor's preview. Its dimension type,",
    "<gray>dimension_types/sky.json, gives it a purple sky, fog and high clouds.",
  },
  build = function(booth)
    booth:portal(4, 6, "<light_purple>Go to the sky islands", "sky")
    booth:placard(
      7,
      1.5,
      6,
      table.concat({
        "<gold>terrain/sky_islands.lua</gold>",
        "<gray>local stages = {}",
        "<gray>function stages.density(_, y, _, value)",
        "<gray>  if y < 72 then",
        "<gray>    return math.min(value, -1)",
        "<gray>  end",
        "<gray>  return value",
        "<gray>end",
        "<gray>return stages",
      }, "\n"),
      { width = 260 }
    )
  end,
})
