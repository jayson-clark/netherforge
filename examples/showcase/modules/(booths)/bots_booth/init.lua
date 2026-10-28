-- Booth: bots. Fake players a script joins to the server and drives: real players to the server
-- and every plugin, doing everything by sending what a player's client would.
local expo = require("expo")

local bot ---@type Bot?

expo.booth({
  id = "bots",
  title = "Bots",
  summary = "Fake players that join and act through a real client's moves",
  icon = "minecraft:zombie_head",
  lines = {
    '<gold>Bots</gold>: nf.bots.join, behind "requires": { "bots": true }.',
    "",
    "<gray>A bot is a real player to the server: on the player list, joining, moving,",
    "<gray>clicking, chatting and dying like anyone, with every check applied. It",
    "<gray>walks a step a tick and aims before it clicks.",
    "",
    "<gray>For opponents and teammates in games, crowds, and tests that play the",
    "<gray>game. The dev server always allows them; a production server's owner",
    "<gray>turns them on.",
  },
  build = function(booth)
    local corners =
      { booth:pos(3, 1, 2), booth:pos(8, 1, 2), booth:pos(8, 1, 10), booth:pos(3, 1, 10) }
    booth:kiosk(2, 6, "<yellow>Call a bot", function(player)
      if bot and bot:exists() then
        player:send_message("<gray>The bot is already here.")
        return
      end
      nf.task(function()
        local joined, err = nf.bots.join("ExpoBot", booth:at(5, 1, 6))
        if not joined then
          player:send_message("<red>No bot: " .. tostring(err))
          return
        end
        bot = joined
        joined:chat("Hi " .. player:name() .. "! I'm a bot: watch me walk the booth.")
        for lap = 1, 2 do
          for _, corner in ipairs(corners) do
            joined:walk_to(corner, { sprint = lap == 2 })
            joined:jump()
          end
        end
        joined:look_at(assert(player:eye_position()))
        joined:swing()
        joined:chat("That's me done. Bye!")
        nf.wait(40)
        joined:leave()
        bot = nil
      end)
    end)
    booth:caption(
      2,
      6,
      "<white>A bot joins\n<gray>It chats, walks the corners (sprinting the second lap), waves and leaves.",
      3.2
    )
  end,
})
