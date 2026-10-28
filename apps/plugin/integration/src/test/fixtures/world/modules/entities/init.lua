nf.commands.register("it-entities", function()
  local world = nf.worlds.default()
  local base = vec3(210.5, -62, 210.5)
  local causes = {}
  local watching = world:on("entity_spawn", function(event)
    causes[#causes + 1] = event.cause
  end)
  local zombie = world:spawn_entity("minecraft:zombie", base, {
    custom_name = "<red>Bob",
    tags = { "it" },
    data = { home = base, kills = 2 },
  })
  watching:cancel()
  zombie:set_ai(false)
  zombie:set_invulnerable(false)
  local added = zombie:add_effect("minecraft:slowness", 200, { amplifier = 1 })
  zombie:set_equipment("head", { kind = "minecraft:diamond_helmet" })
  local causes = {}
  zombie:on("damage", function(event)
    causes[#causes + 1] = event.cause
    event.amount = event.amount / 2
  end)
  zombie:damage(4)
  local data = zombie:data()
  data.kills = data.kills + 1
  local chest_at = base + vec3(3, 0, 0)
  world:set_block(chest_at, "minecraft:chest")
  local chest = world:block(chest_at):inventory()
  chest:add_item({ kind = "minecraft:gold_nugget", count = 10, data = { coin = true, by = zombie } })
  chest:add_item({ kind = "minecraft:gold_nugget", count = 4 })
  local coins = chest:count_item({ data = { coin = true } })
  local taken = chest:remove_item({ kind = "minecraft:gold_nugget", data = { coin = true } }, 4)
  local cart = world:spawn_entity("minecraft:chest_minecart", base + vec3(-3, 0, 0))
  cart:inventory():add_item({ kind = "minecraft:bread", count = 3 })
  local bar = nf.bossbars.create({ text = "<red>IT", color = "red", progress = 0.5 })
  -- Half a block up: a spawned zombie is a baby one time in twenty, under a block tall.
  local hit = world:raycast(base + vec3(0, 0.5, -5), vec3(0, 0, 1), 10, { blocks = false })
  log(
    "entities",
    zombie:kind(),
    zombie:custom_name(),
    zombie:has_tag("it"),
    zombie:has_ai(),
    added and zombie:has_effect("minecraft:slowness"),
    zombie:equipment("head").kind,
    zombie:health() < 20,
    #causes > 0,
    tostring(data.home),
    data.kills,
    causes[1],
    chest:kind(),
    coins,
    taken,
    chest:count_item("minecraft:gold_nugget"),
    chest:item(0).data.by == zombie,
    cart:inventory():count_item("bread"),
    bar:exists(),
    hit ~= nil and hit.entity == zombie,
    #world:entities({ tag = "it" })
  )
  nf.server.run("save-all")
  bar:remove()
  cart:remove()
  zombie:remove()
  world:set_block(chest_at, "minecraft:air")
end)
