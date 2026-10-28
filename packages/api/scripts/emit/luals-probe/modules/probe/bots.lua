-- Bots and mannequins: a Bot is a Player, a Mannequin a Living.
nf.task(function()
  local bot, err = nf.bots.join("Sparring", vec3(0.5, 64, 0.5), { skin = { texture = "abc" } })
  if not bot then
    print(err)
    return
  end
  print(bot:name(), bot:health(), bot:is_sneaking())
  local at = bot:walk_to(vec3(8, 64, 0), { sprint = true, timeout = 100 })
  if at then
    print(at.x)
  end
  bot:look_at(vec3(1, 65, 1))
  bot:set_sneaking(true)
  bot:use_block(vec3(2, 63, 2), "up", true)
  local broken, why = bot:break_block(vec3(2, 63, 2))
  print(broken, why)
  bot:on("death", function(event)
    print(event.player:name())
    bot:respawn()
  end)
  for _, other in ipairs(nf.bots.all()) do
    other:leave()
  end
end)

local guide = nf.worlds.default():spawn_entity("minecraft:mannequin", vec3(0.5, 64, 0.5)) --[[@as Mannequin?]]
if guide then
  guide:set_skin("Notch")
  guide:set_skin({ texture = "abc", signature = "def" })
  local skin = guide:skin()
  if type(skin) == "table" then
    print(skin.texture)
  end
  guide:set_description("<gray>Talk to me")
  guide:set_pose("crouching")
  guide:set_skin_parts({ cape = false })
  print(guide:skin_parts().hat, guide:main_hand(), guide:health())
  guide:on("interact", function(event)
    print(event.player:name())
  end)
end
