-- Booth: the player's screen. Everything a script can put in front of one player: titles, the
-- action bar, boss bars, the sidebar, the player list's header, footer and names, a line under
-- their name, books, blocks only they see, and where their compass points.
local expo = require("expo")

expo.booth({
  id = "interface",
  title = "Titles, bars & the player list",
  summary = "Titles, boss bars, sidebars, tab list, books and fake blocks",
  icon = "minecraft:name_tag",
  lines = {
    "<gold>On one player's screen</gold>",
    "",
    "<white>send_title</white> <gray>and <white>send_actionbar</white>: big text and the line over the hotbar.",
    "<white>nf.bossbars</white> <gray>bars across the top, coloured and notched (the tour's is one).",
    "<white>sidebar</white> <gray>up to 15 lines of MiniMessage, no score numbers (yours shows the tour).",
    "<white>set_tab_header/footer</white><gray>, <white>set_tab_name</white><gray>: the player list.",
    "<white>set_below_name</white><gray>: a line under your name over your head.",
    "<white>open_book</white><gray>, <white>send_block_change</white> <gray>(a block only you see), compass targets.",
  },
  build = function(booth)
    booth:kiosk(3, 1.5, "<yellow>Title", function(player)
      player:send_title(
        "<gold><glyph:ui/coin> Hello!",
        "<gray>send_title, with fades",
        { fade_in = 10, stay = 40, fade_out = 20 }
      )
    end)
    booth:kiosk(3, 3.75, "<yellow>Boss bar countdown", function(player)
      local bar =
        nf.bossbars.create({ text = "<red>Ten seconds", color = "red", style = "notched_10" })
      bar:show_to(player)
      local left = 10
      local timer
      timer = nf.every(20, function()
        left = left - 1
        bar:set_progress(left / 10)
        bar:set_text(("<red>%d seconds"):format(left))
        if left <= 0 then
          bar:remove()
          timer:cancel()
        end
      end)
    end)
    booth:kiosk(3, 6, "<yellow>Player list", function(player)
      player:set_tab_header("<gold><bold>NetherForge Showcase</bold>\n<gray>set_tab_header")
      player:set_tab_footer("<gray>You're in the interface booth\n<dark_gray>set_tab_footer")
      player:set_tab_name("<gold>★ </gold>" .. player:name())
      player:set_below_name("<yellow>visiting the expo")
      player:send_actionbar(
        "<gray>Hold Tab to see the list; look at yourself in F5 for the line under your name."
      )
    end)
    booth:kiosk(3, 8.25, "<yellow>Read a book", function(player)
      player:open_book({
        "<gold><bold>The Showcase</bold></gold>\n\n<gray>A book opened by player:open_book, without giving you one.\n\nTurn the page.",
        "<dark_gray>Every booth along the avenue shows one part of NetherForge.\n\nUse your tour guide to jump between them.",
      })
    end)
    booth:kiosk(3, 10.5, "<yellow>A block only you see", function(player)
      -- A whole block inside the booth, on the floor across from the kiosk.
      local spot = booth:cell(6, 1, 10)
      player:send_block_change(spot, "minecraft:diamond_block")
      player:set_compass_target(expo.world:location(spot + vec3(0.5, 0, 0.5)))
      player:send_actionbar("<aqua>A diamond block, only for you. Your compass points at it.")
    end)
    booth:caption(
      6,
      4,
      "<white>One player at a time\n<gray>Each kiosk shows its feature to whoever pressed it, and no one else.",
      3,
      260
    )
  end,
})
