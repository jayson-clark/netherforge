-- Booth: screens. Walls of maps (128 by 128 pixels each) in item frames that scripts draw on:
-- pixels, shapes, text in any font (screen_fonts/ too), pictures from the resource pack, clicks
-- and hovers where they land, and the same screen as a map in your hand.
local expo = require("expo")

local COLOURS = { "#ff4040", "#ffcc00", "#40c040", "#40a0ff", "#c060ff", "#101018" }

expo.booth({
  id = "screens",
  title = "Screens",
  summary = "Map walls scripts draw on, click and hover on",
  icon = "minecraft:filled_map",
  lines = {
    "<gold>Screens</gold>: screens/<id>/screen.json, or nf.screens.create.",
    "",
    "<gray>A screen is maps side by side, hung in invisible frames. Scripts draw",
    "<gray>pixels, rectangles, circles, lines and polygons, text (Minecraft's font,",
    "<gray>or a pixel font of your own in screen_fonts/) and pictures from the pack.",
    "<gray>`click` and `hover` say which pixel; only changed maps are resent.",
    "",
    "<gray>This wall is a paint wall: click it to paint, the palette on the left",
    "<gray>picks a colour. The board at the hub is a project screen with a file.",
  },
  build = function(booth)
    -- Made by this script rather than a file, so it goes when this module unloads.
    local wall =
      nf.screens.create({ width = 3, height = 2, background = "#f4f0e8", click_range = 32 })
    local colour = COLOURS[1]

    local function palette()
      for index, swatch in ipairs(COLOURS) do
        local y = 8 + (index - 1) * 36
        wall:rectangle(4, y, 28, 28, {
          fill = swatch,
          outline = swatch == colour and "#000000" or "#c8c0b0",
          thickness = 3,
          radius = 4,
        })
      end
      wall:text(
        wall:width() - 8,
        wall:height() - 18,
        "<dark_gray>paint wall",
        { align = "right", font = "pixel", scale = 2, color = "#606060" }
      )
    end
    local function clear()
      wall:clear("#f4f0e8")
      wall:line(40, 0, 40, wall:height() - 1, "#c8c0b0")
      palette()
    end
    clear()

    wall:on("click", function(event)
      if event.x < 40 then
        local index = (event.y - 8) // 36 + 1
        if COLOURS[index] then
          colour = COLOURS[index]
          palette()
        end
        return
      end
      wall:circle(event.x, event.y, event.click == "left" and 4 or 10, { fill = colour })
    end)
    wall:on("hover", function(event)
      wall:set_pixel(event.x, event.y, colour)
    end)
    booth:hang(wall, 4)
    booth:caption(
      6,
      6,
      "<white>Paint wall\n<gray>Left click: a dot. Right click: a blob. Hover leaves a trail.",
      1.4,
      260
    )

    booth:kiosk(3, 2, "<yellow>Clear the wall", function()
      clear()
    end)
    booth:kiosk(3, 10, "<yellow>Hold it as a map", function(player)
      local item = wall:item(0, 0)
      if item then
        player:give_item(item)
        player:send_message(
          "<gray>The wall's top-left map, in your hand: paint the wall and watch it change."
        )
      end
    end)
    booth:caption(
      3,
      10,
      "<white>Map in hand\n<gray>screen:item gives one of its maps as an item.",
      3.2
    )
  end,
})
