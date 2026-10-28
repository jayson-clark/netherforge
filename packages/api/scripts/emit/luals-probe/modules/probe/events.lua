-- The server events of section 17: payload fields typed, writable fields assigned with their types.
local world = nf.worlds.default()

nf.on("player_chat", function(event)
  print(event.message:upper(), event.format)
  event.message = "hi"
  event.format = "<gray><player>: <message>"
  event:cancel()
end)

nf.on("player_interact", function(event)
  print(event.click, event.hand, event.face, event.item and event.item.kind, event.block and event.block:kind())
end)

nf.on("player_move", function(event)
  print(event.from.position:distance(event.to.position), event.to.world:name())
  event:cancel()
end)

nf.on("player_teleport", function(event)
  print(event.cause, event.from.position.y)
  event.to = world:location(vec3(0, 64, 0), 90, 0)
end)

nf.on("player_change_world", function(event)
  print(event.from:name(), event.to:environment())
end)

nf.on("player_death", function(event)
  print(event.cause, event.killer and event.killer:kind(), #event.drops)
  event.message = nil
  event.keep_inventory = true
  event.drops = { { kind = "minecraft:bread", count = 2 } }
end)

nf.on("player_respawn", function(event)
  event.location = event.location:with_position(vec3(0, 70, 0))
end)

nf.on("player_drop_item", function(event)
  print(event.item.kind)
  event:cancel()
end)

nf.on("player_pickup_item", function(event)
  print(event.item.count, event.entity:id())
end)

nf.on("player_use_item", function(event)
  print(event.item.kind, event.hand)
end)

nf.on("player_consume_item", function(event)
  print(event.item.kind)
end)

nf.on("player_swap_hands", function(event)
  print(event.player:name())
  event:cancel()
end)

nf.on("player_sneak", function(event)
  print(event.sneaking and "down" or "up")
end)

nf.on("player_command", function(event)
  print(event.input:len())
end)

nf.on("block_break", function(event)
  print(#event.drops, event.experience)
  event.drops = {}
  event.experience = 0
end)

nf.on("block_place", function(event)
  print(event.state, event.against:kind())
end)

nf.on("entity_death", function(event)
  event.drops = { { kind = "minecraft:diamond" } }
end)

local player = nf.players.get("Alex")
if player then
  player:on("move", function(event)
    print(event.to.position.x)
  end)
  player:on("death", function(event)
    event.keep_inventory = true
  end)
  player:on("teleport", function(event)
    print(event.to.position.z)
  end)
  player:on("command", function(event)
    print(event.input)
  end)
end

return {}
