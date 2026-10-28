local steps = {}

function steps.prepare()
  local world = nf.worlds.default()
  world:load_chunk(vec3(3, -60, 3))
  world:set_block(vec3(3, -60, 3), "minecraft:diamond_block")
  world:set_block(vec3(4, -59, 4), "minecraft:gold_block")
  world:set_block(vec3(4, -60, 3), "minecraft:oak_stairs[facing=east,half=top]")
  local lobby = nf.worlds.create("it_lobby", { generator = "void", structures = false })
  lobby:load_chunk(vec3(8, 70, 8))
  lobby:set_block(vec3(8, 70, 8), "minecraft:emerald_block")
  log("prepared", lobby:name(), lobby:block(vec3(8, 70, 8)):kind())
end

function steps.place()
  local lobby = nf.worlds.get("it_lobby")
  lobby:load_chunk(vec3(20, 70, 20))
  local placed = lobby:place_structure("it_cube", vec3(20, 70, 20))
  log("placed", placed, lobby:block(vec3(20, 70, 20)):kind(), lobby:block(vec3(21, 71, 21)):kind())
end

function steps.copy()
  nf.task(function()
    local copy = nf.worlds.copy("it_captured", "it_from_map")
    copy:load_chunk(vec3(8, 70, 8))
    log("copied", copy:name(), copy:block(vec3(8, 70, 8)):kind())
  end)
end

nf.commands.register(
  "it-capture",
  { arguments = { { name = "step", type = "word" } } },
  function(event)
    steps[event.arguments.step]()
  end
)
