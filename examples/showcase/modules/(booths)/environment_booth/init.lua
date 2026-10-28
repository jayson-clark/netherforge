-- Booth: the sky, the weather and time. Skies of the project's own (skies/<id>.json) shown to one
-- player or a whole world, and the world's weather, time and lightning.
local expo = require("expo")

expo.booth({
  id = "environment",
  title = "Sky, weather & time",
  summary = "Skies of your own, weather, time of day and lightning",
  icon = "minecraft:clock",
  lines = {
    "<gold>Skies</gold> live in skies/<id>.json: sky, fog, water and cloud colours.",
    "",
    "<gray>player:set_sky shows one to a single player, fading in; world:set_sky to",
    "<gray>everyone in a world. Only what players see changes: biomes, mobs and",
    "<gray>weather stay as they were.",
    "",
    "<gray>world:set_weather, set_time_of_day and strike_lightning change the",
    "<gray>world itself, for everyone in it.",
  },
  build = function(booth)
    booth:kiosk(4, 1.5, "<red>Blood moon (just me)", function(player)
      if player:sky() then
        player:clear_sky({ fade_ticks = 40 })
      else
        player:set_sky("blood_moon", { fade_ticks = 40 })
      end
    end)
    booth:caption(4, 1.5, "<white>A sky of your own\n<gray>Press again to clear it.", 3.2)
    booth:kiosk(4, 4.5, "<aqua>Rain / clear", function()
      local world = expo.world
      world:set_weather(world:weather() == "clear" and "rain" or "clear")
    end)
    booth:kiosk(4, 7.5, "<yellow>Noon / midnight", function()
      local world = expo.world
      world:set_time_of_day(world:is_day() and 18000 or 6000)
    end)
    booth:kiosk(4, 10.5, "<white>Lightning", function()
      expo.world:strike_lightning(booth:pos(8, 1, 10.5), { effect_only = true })
    end)
    booth:caption(4, 9, "<white>The world itself\n<gray>Everyone in the world sees these.", 3.4)
  end,
})
