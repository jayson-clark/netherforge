-- Teams, the player list and the line under name tags.
local red = nf.teams.create("red", {
  display_name = "<red>Red",
  prefix = "<red>[R] ",
  color = "red",
  friendly_fire = false,
  see_invisible_teammates = true,
  nametags = "hide_for_other_teams",
  collision = "push_own_team",
})
red:set_color("dark_red")
red:set_color()
red:set_nametags("never")
red:set_collision("push_other_teams")
red:set_friendly_fire(true)
red:set_can_see_invisible_teammates(false)
print(
  red:name(),
  red:prefix(),
  red:suffix(),
  red:display_name(),
  red:color(),
  red:nametags(),
  red:collision()
)
print(red:has_friendly_fire(), red:can_see_invisible_teammates(), red:exists())

for _, team in ipairs(nf.teams.all()) do
  print(team:name(), #team:members())
end
local blue = nf.teams.get("blue")
if blue then
  blue:remove()
end

nf.on("player_join", function(event)
  local player = event.player
  red:add_member(player)
  print(red:has_member(player), player:team() == red)
  player:set_tab_name("<gold>" .. player:name())
  player:set_tab_order(3)
  print(player:tab_name(), player:tab_order(), player:below_name())
  player:set_below_name("<red>10 hearts")
  for _, other in ipairs(nf.players.online()) do
    player:set_listed_for(other, false)
    print(player:is_listed_for(other))
  end
  for _, member in ipairs(red:members()) do
    local team = member:team()
    if team then
      print(team:name(), member:kind())
    end
  end
  red:remove_member(player)
end)
