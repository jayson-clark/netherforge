local bar = nf.bossbars.create({ text = "<red>Wave 2", color = "red", progress = 0.5 })
local red = nf.teams.create("red", {
  prefix = "<red>[R] ",
  suffix = " *",
  color = "red",
  friendly_fire = false,
  nametags = "hide_for_other_teams",
  collision = "push_own_team",
})

nf.commands.register(
  "jointeam",
  { description = "Join the red team", players_only = true },
  function(event)
    local player = event.player
    red:add_member(player)
    player:set_tab_name("<gold>Star " .. player:name())
    player:set_tab_order(7)
    player:set_below_name("<red>12 hearts")
    for _, other in ipairs(nf.players.online()) do
      if other ~= player then
        player:set_listed_for(other, false)
      end
    end
    player:send_message(
      "team "
        .. tostring(player:team() == red)
        .. " "
        .. nf.text.escape(tostring(player:tab_name()))
    )
  end
)

nf.commands.register(
  "screen",
  { description = "Fill the screen", players_only = true },
  function(event)
    local player = event.player
    player:send_title("<gold>Big title", "<gray>small print")
    player:send_actionbar("<aqua>Action!")
    player:sidebar():set_title("<yellow>Coins")
    player:sidebar():set_lines({ "<gold>Gold: 10", "<gray>Silver: 3" })
    bar:show_to(player)
  end
)

nf.commands.register(
  "hidetowers",
  { description = "Hide every tower from me", players_only = true },
  function(event)
    for _, tower in ipairs(nf.centities.all({ kind = "tower" })) do
      tower:hide_from(event.player)
    end
  end
)

nf.on("player_chat", function(event)
  event.player:send_message("You said: " .. nf.text.escape(event.message))
end)

nf.on("player_sneak", function(event)
  event.player:send_message("sneaking " .. tostring(event.sneaking))
end)

nf.commands.register("ruby", { description = "Two rubies", players_only = true }, function(event)
  event.player:give_item(nf.items.create("ruby", { count = 2, data = { found = "it" } }))
end)

nf.on("player_craft", function(event)
  event.player:send_message("crafted " .. tostring(event.recipe))
end)

nf.commands.register(
  "closeinv",
  { description = "Close what's open", players_only = true },
  function(event)
    event.player:close_inventory()
  end
)

nf.commands.register(
  "learn",
  { description = "Learn the ruby sword", players_only = true },
  function(event)
    local learned = event.player:discover_recipe("ruby_sword")
    event.player:send_message(
      ("learned %s %s"):format(
        tostring(learned),
        tostring(event.player:has_discovered_recipe("ruby_sword"))
      )
    )
  end
)

nf.commands.register(
  "rubycheck",
  { description = "What the held ruby says", players_only = true },
  function(event)
    local held = event.player:held_item()
    event.player:send_message(
      ("held %s x%d found=%s"):format(
        tostring(held and nf.items.id(held)),
        held and held.count or 0,
        tostring(held and held.data and held.data.found)
      )
    )
  end
)
