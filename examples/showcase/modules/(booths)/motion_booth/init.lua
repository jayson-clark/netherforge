-- Booth: motion. Three ways a whole centity moves: a body the server walks, a flight as a
-- projectile, and pathfinding round the blocks. And health, with a bar and death.
local expo = require("expo")

expo.booth({
  id = "motion",
  title = "Bodies, projectiles & health",
  summary = "Walking bodies, launched projectiles, pathfinding and health bars",
  icon = "minecraft:bow",
  lines = {
    "<gold>Moving whole centities</gold>",
    "",
    "<white>body</white> <gray>in centity.json: an upright box the server walks, drops and jumps.",
    "<white>launch</white> <gray>flies it as a projectile: gravity, drag, bounces, hits and homing.",
    "<white>move_to</white> <gray>finds a way round blocks (or flies) and reports `path_end`.",
    "<white>health</white> <gray>gives it hit points, a bar over it, `damage` and `death` events.",
  },
  build = function(booth)
    booth:spawn("bouncer", 4, 1, 2)
    booth:caption(
      4,
      2,
      "<white>Bouncer\n<gray>A body. Click: it's knocked away and jumps, then walks itself home.",
      2.8
    )

    local dummy = booth:spawn("dummy", 8, 1, 6)
    booth:caption(
      6,
      6,
      "<white>Training dummy\n<gray>Health 20 with a bar. Hit it (a sword hits harder) or fire darts at it: a model, aimed along its flight.",
      3.4
    )
    booth:kiosk(2, 6, "<yellow>Fire a dart", function(player)
      local from = booth:pos(2, 2.2, 6)
      local dart = nf.centities.spawn("dart", expo.world:location(from), { temporary = true })
      if not dart then
        return
      end
      local target = dummy and dummy:position() or booth:pos(8, 2, 6)
      dart:launch({
        velocity = ((target + vec3(0, 1.2, 0)) - from):normalized() * 18,
        gravity = 6,
        radius = 0.15,
        owner = player,
        lifetime = 60,
        aim = "nose",
        hits = { players = false, kinds = { "dummy" } },
        -- Kept where it hit, point in, for a moment: then it goes.
        remove_on_end = false,
      })
      dart:on("hit", function(event)
        local hit = event.target
        if hit then
          hit:damage(4, { source = player, cause = "dart" })
        end
      end)
      dart:on("flight_end", function()
        nf.after(30, function()
          if dart:exists() then
            dart:remove()
          end
        end)
      end)
    end)

    -- A pen with a wall across it, so the runner has to go round.
    expo.world:fill_blocks(booth:cell(3, 1, 9), booth:cell(7, 2, 9), "minecraft:oak_planks")
    booth:spawn("runner", 5, 1, 8)
    booth:caption(
      3,
      8,
      "<white>Runner\n<gray>Click it: `move_to` finds the way round the wall to the far side, then back.",
      2.8
    )
  end,
})
