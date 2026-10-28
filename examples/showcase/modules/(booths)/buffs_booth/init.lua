-- Booth: buffs and stats. buffs/<id>.json: timed effects of the project's own that add to named
-- stats, cure or block each other, and stack. What a stat means is the scripts': here `move`
-- changes how fast you walk.
local expo = require("expo")

local BASE_WALK = 0.2

-- Every second, everyone walks at the speed their `move` stat says.
nf.every(20, function()
  for _, player in ipairs(nf.players.online()) do
    local speed = BASE_WALK * (1 + player:stat("move"))
    if math.abs(player:walk_speed() - speed) > 0.001 then
      player:set_walk_speed(nf.math.clamp(speed, 0.05, 1))
    end
  end
end)

local function report(player)
  local parts = {}
  for _, buff in ipairs(player:buffs()) do
    parts[#parts + 1] = ("%s <gray>(%ds)"):format(buff.name or buff.id, (buff.ticks or 0) // 20)
  end
  local stats = player:stats()
  player:send_message(
    ("<gold>Buffs:</gold> %s\n<gold>Stats:</gold> <gray>defense <white>%d</white>, move <white>%+.1f"):format(
      #parts > 0 and table.concat(parts, "<gray>, ") or "<gray>none",
      stats.defense or 0,
      stats.move or 0
    )
  )
end

expo.booth({
  id = "buffs",
  title = "Buffs & stats",
  summary = "Timed effects of your own adding to named stats",
  icon = "minecraft:potion",
  lines = {
    "<gold>Buffs</gold> live in buffs/<id>.json.",
    "",
    "<white>Ironskin</white> <gray>+8 defense for up to 5 minutes; it cures Frail.",
    "<white>Frail</white> <gray>-4 defense and -0.2 move (you slow down); Ironskin blocks it,",
    "<gray>and taking it again adds to its time (`stacking`).",
    "",
    "<gray>A stat is just a number by name, the sum of every buff and modifier:",
    "<gray>scripts decide what it does. Centities have buffs and stats too.",
  },
  build = function(booth)
    booth:kiosk(5, 2, "<gray>Ironskin (30s)", function(player)
      player:add_buff("ironskin", 600)
      report(player)
    end)
    booth:kiosk(5, 5, "<red>Frail (15s)", function(player)
      if not player:add_buff("frail", 300) then
        player:send_message("<gray>Ironskin blocks Frail.")
      end
      report(player)
    end)
    booth:kiosk(5, 8, "<yellow>My stats", function(player)
      report(player)
    end)
    booth:kiosk(5, 11, "<white>Clear buffs", function(player)
      player:remove_buff("ironskin")
      player:remove_buff("frail")
      report(player)
    end)
    booth:caption(
      5,
      6.5,
      "<white>Try Frail, then Ironskin\n<gray>Watch your speed change with the `move` stat.",
      3.4
    )
  end,
})
