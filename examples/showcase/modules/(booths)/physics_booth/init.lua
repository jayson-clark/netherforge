-- Booth: physics. A node with `physics` is a rigid body the server simulates: gravity, contacts
-- against blocks and other bodies, friction, bounciness, spin and sleeping.
local expo = require("expo")

expo.booth({
  id = "physics",
  title = "Physics",
  summary = "Rigid bodies: impulses, collisions, friction and spin",
  icon = "minecraft:slime_ball",
  lines = {
    "<gold>Physics</gold> is a node's `physics` block in centity.json.",
    "",
    "<gray>Each body has mass, bounciness, friction, drag and a shape (box or",
    "<gray>sphere). It collides with blocks and with other centities' hitboxes,",
    "<gray>and falls asleep when it comes to rest.",
    "",
    "<gray>Scripts push them with <white>apply_impulse</white> and <white>apply_impulse_at</white>,",
    "<gray>and hear <white>collide</white> and <white>sleep</white> events.",
  },
  build = function(booth)
    local crates = {}
    booth:kiosk(2, 2, "<yellow>Drop a crate", function()
      for _, crate in ipairs(crates) do
        if crate:exists() then
          crate:remove()
        end
      end
      crates = { booth:spawn("crate", 4, 2, 2) }
    end)
    booth:caption(
      4,
      2,
      "<white>Falling crate\n<gray>Drops, bounces a little and settles (`sleep`). Click it to topple it where you clicked.",
      3.4
    )
    -- The skirt hangs 0.55 below the table's origin: this keeps it just off the floor.
    booth:spawn("pool", 6, 1.56, 7)
    booth:caption(
      3,
      7,
      "<white>Pool table\n<gray>Sixteen sphere bodies. Left-click the cue ball to break, right-click for a soft shot, click the table to rerack.",
      3
    )
  end,
})
