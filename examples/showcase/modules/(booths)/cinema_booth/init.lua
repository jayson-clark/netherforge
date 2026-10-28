-- Booth: video. A project video (videos/intro: the editor turns an .mp4 into frames the server
-- streams) played on a project screen, with a second screen as its remote control.
local expo = require("expo")

expo.booth({
  id = "cinema",
  title = "Video on screens",
  summary = "Videos streamed onto map walls, with a remote control",
  icon = "minecraft:painting",
  lines = {
    "<gold>Videos</gold> live in videos/<id>/video.json: the editor converts a clip",
    "<gray>to the map colours once (video.nfv), and the server streams its frames",
    "<gray>off the main thread to the players watching.",
    "",
    "<gray>The big screen (screens/cinema) shows a poster until the controls",
    "<gray>(screens/controls, another screen) play the video on it. A Playback",
    "<gray>pauses, resumes, seeks, loops and reports its time; `end` hears it stop.",
    "<gray>Buttons light up under your cursor (`hover`, `leave`).",
  },
  build = function(booth)
    booth:hang(nf.screens.get("cinema"), 5, 4)
    booth:hang(nf.screens.get("controls"), 4, 9)
    expo.world:fill_blocks(booth:cell(4, 0, 5), booth:cell(6, 0, 7), "minecraft:red_carpet")
    booth:caption(
      3,
      6,
      "<white>Big screen\n<gray>4 × 3 maps. Click it to mark where you clicked.",
      1.6
    )
    booth:caption(6, 10, "<white>Controls\n<gray>Play, pause, restart, loop.", 1.4)
  end,
})
