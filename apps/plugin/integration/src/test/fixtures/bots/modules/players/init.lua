nf.commands.register(
  "illusions",
  { description = "Things only I see", players_only = true },
  function(event)
    local player = event.player
    local other = assert(nf.players.get("Other"))
    player:send_block_change(player:location().position + vec3(0, 3, 0), "minecraft:gold_block")
    player:send_equipment_change(other, "head", { kind = "minecraft:carved_pumpkin" })
    player:open_book({ "<b>Page one", "Page two" })
    player:set_game_mode("spectator")
    local watched = player:set_camera(other) and player:camera() == other
    local back = player:set_camera() and player:camera() == nil
    player:set_game_mode("survival")
    player:set_compass_target(player:world():location(vec3(100, 70, -20)))
    local distance = player:set_view_distance(6)
    local compass = player:compass_target().position
    player:send_message(
      ("illusions %s %s compass=%d,%d,%d distance=%s"):format(
        tostring(watched),
        tostring(back),
        compass.x,
        compass.y,
        compass.z,
        tostring(distance)
      )
    )
  end
)

nf.commands.register(
  "perms",
  { description = "What the project granted me", players_only = true },
  function(event)
    local player = event.player
    local before = player:has_permission("showcase.builder")
    player:unset_permission("showcase.builder")
    local unset = player:has_permission("showcase.builder")
    player:set_permission("showcase.builder", true)
    local denied = not pcall(player.set_permission, player, "minecraft.command.op", true)
    player:send_message(
      ("perms %s %s %s %s"):format(
        tostring(before),
        tostring(unset),
        tostring(player:has_permission("showcase.builder")),
        tostring(denied)
      )
    )
  end
)

nf.commands.register(
  "adv",
  { description = "Grant and revoke an advancement", players_only = true },
  function(event)
    local player = event.player
    local granted = player:grant_advancement("minecraft:story/mine_diamond")
    local has = player:has_advancement("minecraft:story/mine_diamond")
    local progress = player:advancement_progress("minecraft:story/mine_diamond")
    local revoked = player:revoke_advancement("minecraft:story/mine_diamond")
    player:send_message(
      ("adv %s %s %d %d %s %s"):format(
        tostring(granted),
        tostring(has),
        #progress.done,
        #progress.remaining,
        tostring(revoked),
        tostring(player:has_advancement("minecraft:story/mine_diamond"))
      )
    )
  end
)

nf.commands.register(
  "played",
  { description = "When people played", players_only = true },
  function(event)
    local player = event.player
    local first, seen = player:first_played(), player:last_seen()
    player:send_message(
      ("played %s %s %d"):format(
        tostring(first > 0 and first <= seen),
        tostring(math.abs(nf.server.unix_time() - seen) < 60000),
        #nf.players.known()
      )
    )
  end
)

nf.commands.register("moderate", {
  description = "Ban someone, after changing the server's settings and putting them back",
  arguments = { { name = "name", type = "word" } },
}, function(event)
  local target = assert(nf.players.get(event.arguments.name))
  local motd, most = nf.server.motd(), nf.server.max_players()
  nf.server.set_motd("<gold>Event tonight")
  nf.server.set_max_players(5)
  local changed = nf.server.motd() == "<gold>Event tonight" and nf.server.max_players() == 5
  nf.server.set_motd(motd)
  nf.server.set_max_players(most)
  target:set_whitelisted(true)
  local listed = target:is_whitelisted() and nf.players.whitelisted()[1] == target
  target:set_whitelisted(false)
  target:ban({ reason = "Testing bans", expires = nf.server.unix_time() + 3600000 })
  event.sender:send_message(
    ("moderate %s %s %s %s"):format(
      tostring(changed),
      tostring(listed),
      tostring(target:is_banned()),
      tostring(nf.players.banned()[1] == target)
    )
  )
end)

nf.commands.register("liftban", {
  description = "Lift someone's ban",
  arguments = { { name = "name", type = "word" } },
}, function(event)
  local target = assert(nf.players.get(event.arguments.name))
  event.sender:send_message(
    ("liftban %s %s"):format(tostring(target:unban()), tostring(target:is_banned()))
  )
end)
