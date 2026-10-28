-- Managing worlds: making, loading, copying and unloading them, borders, and structures.
local arena = nf.worlds.get("arena_1")
  or nf.worlds.load("arena_1")
  or nf.worlds.create("arena_1", { generator = "void", environment = "nether", seed = 1, structures = false })
if arena then
  print(arena:is_managed())
  local border = arena:border()
  if border then
    border:set_center(vec3(0, 0, 0))
    border:set_size(200, { ticks = 20 * 30 })
    local size = border:size()
    local center = border:center()
    if size and center then
      print(size * 2, center.x)
    end
    local damage = border:damage()
    if damage then
      print(damage.amount, damage.buffer)
    end
    border:set_damage({ amount = 0.5 })
    local warning = border:warning()
    if warning then
      print(warning.distance, warning.ticks)
    end
    border:set_warning({ distance = 3, ticks = 100 })
    print(border:contains(vec3(1, 2, 3)), border:contains(arena:location(vec3(1, 2, 3))))
  end
  arena:save_structure("reset", vec3(0, 60, 0), vec3(10, 70, 10), { entities = true })
  arena:place_structure("reset", vec3(0, 60, 0), { rotation = 90, mirror = "left_right", integrity = 0.5, entities = false })
  print(arena:unload({ save = false, delete = true, move_players_to = nf.worlds.default() }))
end

local size = nf.structures.size("reset")
if size and nf.structures.exists("reset") then
  print(size.x + size.y + size.z)
end

nf.worlds.copy("arena", "arena_2", function(world, err)
  if world then
    print(world:name())
  else
    print("no copy: " .. (err or "?"))
  end
end)
nf.task(function()
  local copied, err = nf.worlds.copy("arena", "arena_3")
  if copied then
    print(copied:name())
  elseif err then
    print(err:upper())
  end
end)

local someone = nf.players.online()[1]
if someone then
  local own = someone:border()
  if own then
    own:set_size(16)
  end
  print(someone:has_own_border(), someone:reset_border())
end

-- Biomes: a block's, the nearest place of one (both forms), and new chunks on a world and on nf.
local overworld = nf.worlds.default()
local biome = overworld:block(vec3(0, 64, 0)):biome()
if biome then
  print(biome:upper())
end
overworld:locate_biome("minecraft:desert", { near = vec3(0, 64, 0), radius = 1000 }, function(position, err)
  if position then
    print(position.x + position.z)
  else
    print(err)
  end
end)
nf.task(function()
  local position, err = overworld:locate_biome("minecraft:desert")
  print(position and position.y, err)
end)
overworld:on("chunk_generated", function(event)
  print(event.world:name(), event.x * 16, event.z * 16)
end)
nf.on("chunk_generated", function(event)
  print(event.x + event.z)
end)
