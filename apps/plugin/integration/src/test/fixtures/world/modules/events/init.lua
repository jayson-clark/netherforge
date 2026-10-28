nf.commands.register("it-events", function()
  local world = nf.worlds.default()
  local base = vec3(205.5, -62, 205.5)
  local pig = world:spawn_entity("minecraft:pig", base)
  pig:set_ai(false)
  local spared = pig:on("damage", function(event)
    event:cancel()
  end)
  pig:damage(100)
  local alive = pig:exists()
  spared:cancel()
  local before
  pig:on("death", function(event)
    before = #event.drops
  end)
  local watching = nf.on("entity_death", function(event)
    if event.entity == pig then
      event.drops = { { kind = "minecraft:diamond", count = 3 } }
      event.experience = 0
    end
  end)
  pig:damage(100)
  watching:cancel()
  local diamonds, others = 0, 0
  for _, dropped in ipairs(world:entities({ kind = "minecraft:item", near = base, radius = 4 })) do
    local stack = dropped:item()
    if stack and stack.kind == "minecraft:diamond" then
      diamonds = diamonds + stack.count
    else
      others = others + 1
    end
    dropped:remove()
  end
  log("events", alive, before ~= nil, diamonds, others)
end)
nf.commands.register("it-game-events", function()
  local world = nf.worlds.default()
  local base = vec3(205.5, -62, 205.5)
  local heard = {}
  local subscriptions = {
    world:on("weather_change", function(event)
      heard.weather = tostring(event.raining) .. "/" .. event.cause
    end),
    nf.on("lightning_strike", function(event)
      heard.lightning = event.cause
      event:cancel()
    end),
    nf.on("entity_mount", function(event)
      heard.mount = event.entity:kind() .. ">" .. event.vehicle:kind()
      event:cancel()
    end),
    nf.on("entity_change_effect", function(event)
      heard.effect = event.effect .. "/" .. event.action .. "/" .. event.cause
    end),
  }
  world:set_weather("rain")
  world:strike_lightning(base)
  local pig = world:spawn_entity("minecraft:pig", base)
  local zombie = world:spawn_entity("minecraft:zombie", base)
  zombie:set_ai(false)
  local rode = pig:add_passenger(zombie)
  pig:add_effect("minecraft:speed", 100)
  for _, subscription in ipairs(subscriptions) do
    subscription:cancel()
  end
  world:set_weather("clear")
  pig:remove()
  zombie:remove()
  log(
    "game events",
    tostring(heard.weather),
    tostring(heard.lightning),
    tostring(heard.mount),
    tostring(rode),
    tostring(heard.effect)
  )
end)
