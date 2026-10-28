-- Booth: centities. A centity is an entity made of nodes (block, item, model and text displays),
-- each with a transform, a hitbox and keyframed animations, and a script of its own.
local expo = require("expo")

expo.booth({
  id = "centities",
  title = "Centities & animation",
  summary = "Entities built from nodes, animated by keyframes, run by scripts",
  icon = "minecraft:armor_stand",
  lines = {
    "<gold>Centities</gold> are the project's own entities (centities/<id>/centity.json).",
    "",
    "<white>Nodes</white> <gray>are a tree of displays: blocks, items, the resource pack's own",
    "<gray>3D models, and text. A node turns about its own origin, so empty",
    "<gray>pivot nodes put hinges and axles where they belong.",
    "<white>Animations</white> <gray>are keyframed tracks per node, with easing: `once`, `loop`",
    "<gray>or `hold`, some autoplay, some `additive` on top of the rest.",
    "<white>Scripts</white> <gray>(script.lua beside the file) run once per instance, with `this`.",
    "",
    "<gray>Click each one.",
  },
  build = function(booth)
    booth:spawn("tower", 4, 1, 1.5)
    booth:caption(
      4,
      1.5,
      "<white>Tower\n<gray>`spin` turns an empty pivot node, so the turret turns about its middle. turns.lua counts this tower's turns.",
      4.4,
      150
    )
    booth:spawn("lamp", 4, 1, 4)
    booth:caption(
      4,
      4,
      "<white>Floating lamp\n<gray>`bob` autoplays and loops. A click swaps its block and plays `squash`, additive, on top.",
      3.3,
      150
    )
    booth:spawn("crystal", 4, 1, 6.5)
    booth:caption(
      4,
      6.5,
      "<white>Model flipbook\n<gray>One model display: `spin` turns it, the script swaps its model every 10 ticks. Click to speed it up.",
      3,
      150
    )
    booth:spawn("owl", 4, 1, 9)
    booth:caption(
      4,
      9,
      "<white>Clockwork owl\n<gray>Four models of the pack's own, one per part. It watches you, blinks, breathes; click it to flap.",
      4.4,
      150
    )
    booth:spawn("door", 4, 1, 11.2)
    booth:caption(
      4,
      11.2,
      "<white>Hinged door\n<gray>`open` holds its last pose until `close` plays. A task shuts it after 3s.",
      3.5,
      150
    )
  end,
})
