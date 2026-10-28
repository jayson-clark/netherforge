-- Booth: cutscenes. cutscenes/<id>.json: a camera path keyed in the editor (positions and
-- rotations with easing) and cues (text on screen, events scripts hear), played for one player.
local expo = require("expo")

expo.booth({
  id = "cutscenes",
  title = "Cutscenes",
  summary = "Keyed camera paths with on-screen cues",
  icon = "minecraft:spyglass",
  lines = {
    "<gold>Cutscenes</gold> live in cutscenes/<id>.json.",
    "",
    "<gray>The camera's position and rotation are keyframes with easing; cues put",
    "<gray>text on screen or raise an event the script hears (`cue`).",
    "",
    "<gray>nf.cutscenes.play puts the player in the camera, measured from an origin",
    "<gray>(anywhere you like), and puts them back where they were, game mode and",
    "<gray>all, when it ends. Skippable ones end when they sneak.",
  },
  build = function(booth)
    booth:kiosk(5, 3, "<yellow>Fly down the avenue", function(player)
      local scene = nf.cutscenes.play(player, "avenue", { origin = expo.SPAWN })
      if scene then
        scene:on("cue", function(cue)
          if cue.cue == "end_of_avenue" then
            cue.player:play_sound("minecraft:block.note_block.chime")
          end
        end)
        scene:on("end", function(done)
          done.player:send_message(
            done.reason == "skipped" and "<gray>Skipped." or "<green>That was the whole expo."
          )
        end)
      end
    end)
    booth:caption(5, 3, "<white>avenue\n<gray>Absolute: its origin is the hub. Sneak to skip.", 3.2)
    booth:kiosk(5, 9, "<yellow>Flyby around you", function(player)
      player:run("flyby")
    end)
    booth:caption(
      5,
      9,
      "<white>flyby\n<gray>Relative: measured from where you stand (/flyby works anywhere).",
      3.2
    )
  end,
})
