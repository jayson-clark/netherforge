-- Entities (a zombie is a Mob, which is a Living, which is an Entity), players (a Player is a
-- Living too), dropped items, inventories, boss bars and sidebars.
local world = nf.worlds.default()

-- Entities.
local zombie = world:spawn_entity("minecraft:zombie", vec3(0, 64, 0), {
  custom_name = "<red>Bob",
  tags = { "boss" },
  data = { level = 3 },
  velocity = vec3.up,
}) --[[@as Mob?]]
if zombie then
  print(zombie:id(), zombie:kind(), zombie:exists(), zombie:is_living(), zombie:is_mob(), zombie:is_player())
  print(zombie:position().y, zombie:yaw(), zombie:direction():length())
  zombie:teleport(world:location(vec3(1, 64, 1), 90, 0))
  zombie:teleport(vec3(2, 64, 2))
  zombie:set_custom_name("<gold>Bob")
  zombie:add_tag("boss")
  zombie:set_glowing(true)
  zombie:add_effect("minecraft:speed", 200, { amplifier = 1, particles = false })
  for _, effect in ipairs(zombie:effects()) do
    print(effect.effect, effect.ticks, effect.amplifier)
  end
  zombie:set_equipment("head", { kind = "minecraft:diamond_helmet" })
  local helmet = zombie:equipment("head")
  if helmet then
    print(helmet.kind, helmet.count)
  end
  local zd = zombie:data()
  if zd then
    zd.level = (zd.level or 0) + 1
  end
  zombie:on("damage", function(event)
    if event.cause == "fall" then
      event:cancel()
    end
    event.amount = event.amount / 2
    if event.attacker then
      print(event.attacker:kind())
    end
  end)
  zombie:once("death", function(event)
    event.experience = 10
    print(event.entity:max_health(), event.killer and event.killer:kind())
  end)
  -- What only a mob has: a target, walking, goals.
  zombie:set_target()
  print(zombie:has_ai(), zombie:target(), zombie:has_path())
  zombie:move_to(vec3(5, 64, 5), { speed = 1.2 })
  zombie:on("path_end", function(event)
    print(event.reached, event.entity:path_target())
  end)
  zombie:on("target", function(event)
    if event.target then
      event:cancel()
    end
  end)
  zombie:add_goal("guard", {
    priority = 1,
    controls = { "move" },
    start = function(mob)
      mob:stop_pathing()
    end,
  })
  zombie:on("interact", function(event)
    print(event.player:name(), event.entity:kind(), event.hand)
  end)
  local looked = zombie:target_entity(10)
  if looked and looked:is_player() then
    print(looked:name())
  end
  local pack = zombie:inventory()
  if pack then
    print(pack:size())
  end
end
for _, entity in ipairs(world:entities({ kind = "minecraft:cow", near = vec3.zero, radius = 16 })) do
  if entity:is_living() then
    local cow = entity --[[@as Living]]
    print(cow:id(), cow:health(), cow:max_health())
  end
end
local dropped = world:spawn_item(vec3(0, 65, 0), { kind = "minecraft:diamond", count = 3 })
if dropped then
  dropped:set_pickup_delay(40)
  print(dropped:item(), dropped:position())
end
world:on("entity_spawn", function(event)
  print(event.entity:kind(), event.cause)
end)
nf.on("entity_damage", function(event)
  print(event.entity:id(), event.amount)
end)
nf.on("entity_death", function(event)
  print(event.entity:id(), event.entity:max_health(), event.experience)
end)
nf.on("player_interact_entity", function(event)
  print(event.player:name(), event.entity:kind())
end)

-- Players, with what they inherit from Living and Entity.
for _, player in ipairs(nf.players.online()) do
  print(player:name(), player:id(), player:kind(), player:position().x, player:health())
  player:set_glowing(true)
  player:teleport(vec3(0, 80, 0))
  player:send_title("<gold>Hi", "there", { fade_in = 5, stay = 40, fade_out = 5 })
  player:give_item({ kind = "minecraft:bread", count = 2 })
  player:set_off_hand_item({ kind = "minecraft:shield" })
  local off_hand = player:off_hand_item()
  print(off_hand and off_hand.kind, player:is_operator(), player:game_mode())
  player:set_game_mode("creative")
  local held = player:held_item()
  if held then
    print(held.kind)
  end
  player:on("chat", function(event)
    print(event.player:name(), event.message)
  end)
  player:on("damage", function(event)
    print(event.amount, event.cause)
  end)
  player:once("interact", function(event)
    print(event.click, event.block and event.block:kind())
  end)
  player:on("join", function(event)
    print(event.first_join)
  end)

  -- Inventories.
  local inventory = player:inventory()
  if inventory then
    inventory:set_item(0, { kind = "minecraft:stone", count = 64 })
    print(inventory:add_item({ kind = "minecraft:dirt" }))
    print(inventory:count_item("minecraft:stone"), inventory:has_item("minecraft:stone", 10))
    print(inventory:remove_item({ kind = "minecraft:paper", name = "<gold>Ticket" }, 1))
    print(inventory:first_slot("minecraft:dirt"))
    for index, item in pairs(inventory:items()) do
      print(index + 1, item.kind)
    end
    for _, viewer in ipairs(inventory:viewers()) do
      print(viewer:name())
    end
    local holder = inventory:holder()
    if holder then
      print(holder:exists())
    end
  end
  local chest = player:ender_chest()
  if chest then
    player:open_inventory(chest)
  end

  -- Sidebar.
  local sidebar = player:sidebar()
  sidebar:set_title("<gold>Stats")
  sidebar:set_lines({ "Coins: 3", "", "<gray>play.example" })
  print(#sidebar:lines(), sidebar:title(), sidebar:is_visible())
  sidebar:set_visible(false)
  sidebar:clear()

  -- Boss bars.
  for _, bar in ipairs(player:bossbars()) do
    print(bar:text())
  end
end

local bar = nf.bossbars.create({ text = "<red>Boss", color = "red", style = "notched_10", progress = 0.5 })
local someone = nf.players.get("Notch")
if someone then
  bar:show_to(someone)
  bar:hide_from(someone)
end
bar:set_progress(bar:progress() - 0.1)
bar:set_color("blue")
bar:set_style("progress")
print(bar:exists(), bar:color(), bar:style(), #bar:viewers())
bar:remove()

-- Waiting on any evented handle.
nf.task(function()
  if zombie then
    local hurt = nf.wait_for(zombie, "damage", function(event)
      return event.amount > 1
    end)
    print(hurt.amount, hurt.cause)
  end
  if someone then
    local said = nf.wait_for(someone, "chat")
    print(said.message)
  end
  local spawned = nf.wait_for(world, "entity_spawn")
  print(spawned.cause)
end)
