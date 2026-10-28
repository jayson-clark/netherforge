-- Booth: particle effects. particles/<id>/effect.json: timelines of emitters (bursts, rates,
-- shapes, motion, colours and sizes over curves) made in the editor and played by scripts.
local expo = require("expo")

local orbiting = {} ---@type table[]

expo.booth({
  id = "particles",
  title = "Particle effects",
  summary = "Timelines of emitters: shapes, curves, bursts and trails",
  icon = "minecraft:blaze_powder",
  lines = {
    "<gold>Particle effects</gold> live in particles/<id>/effect.json.",
    "",
    "<gray>An effect is a timeline of emitters. Each spawns a particle in a burst or",
    "<gray>at a rate, from a shape (point, sphere, disc, ring, box), moving outward,",
    "<gray>inward or one way, with colours, sizes, radius and rate keyed over time.",
    "",
    "<gray>nf.particles.play starts one at a place; it can loop, follow a node of",
    "<gray>a centity, scale, and tell a script when it ends.",
  },
  build = function(booth)
    for index, effect in ipairs({ "shockwave", "sparkle", "burst" }) do
      local v = 2 + (index - 1) * 3
      booth:kiosk(4, v, "<yellow>Play " .. effect, function()
        nf.particles.play(effect, booth:at(7, 1.2, v))
      end)
    end
    booth:caption(
      4,
      2,
      "<white>shockwave\n<gray>Four emitters: a flash, block debris, a widening coloured ring, sparks.",
      3.2
    )
    booth:caption(4, 5, "<white>sparkle\n<gray>Dust whose colour moves along a curve.", 3.2)
    booth:caption(
      4,
      8,
      "<white>burst\n<gray>Gathers inward, then a spinning ring and rising sparks.",
      3.2
    )

    -- A wisp circling with a looping trail following it.
    local wisp = booth:spawn("wisp", 7, 2.5, 11)
    if wisp then
      local root = assert(wisp:node("root"))
      nf.particles.play("trail", assert(wisp:location()), { follow = root })
      orbiting[1] = { wisp = wisp, centre = booth:pos(7, 2.5, 10.5), angle = 0 }
    end
    booth:caption(
      4,
      11,
      "<white>trail\n<gray>A looping effect told to follow a node, as it circles.",
      3.2
    )
  end,
})

nf.every(2, function()
  for _, orbit in ipairs(orbiting) do
    if orbit.wisp:exists() then
      orbit.angle = orbit.angle + 0.15
      orbit.wisp:teleport(
        orbit.centre
          + vec3(
            math.cos(orbit.angle) * 1.2,
            math.sin(orbit.angle * 2) * 0.3,
            math.sin(orbit.angle) * 1.2
          )
      )
    end
  end
end)
