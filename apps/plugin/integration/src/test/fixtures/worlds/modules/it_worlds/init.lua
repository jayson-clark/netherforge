local steps = {}

function steps.prepare()
  local world = nf.worlds.default()
  world:load_chunk(vec3(3, -60, 3))
  world:set_block(vec3(3, -60, 3), "minecraft:diamond_block")
  world:set_block(vec3(4, -59, 4), "minecraft:gold_block")
  local saved = world:save_structure("it_cube", vec3(4, -59, 4), vec3(3, -60, 3))
  local main = pcall(world.unload, world)
  log("prepared", saved, tostring(nf.structures.size("it_cube")), world:is_managed(), main)
end

function steps.copy()
  nf.task(function()
    local copy = nf.worlds.copy("it_arena", "it_copy")
    copy:load_chunk(vec3(3, -60, 3))
    log(
      "copied",
      copy:name(),
      copy:is_managed(),
      copy:block(vec3(3, -60, 3)):kind(),
      copy:block(vec3(4, -59, 4)):kind()
    )
  end)
  nf.worlds.copy("it_arena", "it_copy2", function(world)
    log("copied by callback", world and world:name())
  end)
end

function steps.void()
  local void = nf.worlds.create("it_void", { generator = "void", structures = false })
  void:load_chunk(vec3(10, 70, 10))
  local empty = void:block(vec3(0, -60, 0)):kind()
  local placed = void:place_structure("it_house", vec3(10, 70, 10))
  local turned =
    void:place_structure("it_house", vec3(30, 70, 30), { rotation = 90, mirror = "left_right" })
  local border = void:border()
  border:set_center(vec3(5, 64, 5))
  border:set_size(30)
  border:set_damage({ amount = 1, buffer = 2 })
  border:set_warning({ distance = 4, ticks = 40 })
  log(
    "void",
    empty,
    placed,
    turned,
    void:block(vec3(10, 70, 10)):kind(),
    void:block(vec3(11, 71, 11)):kind(),
    void:block(vec3(11, 70, 10)):kind()
  )
  log(
    "border",
    border:size(),
    tostring(border:center()),
    border:damage().amount,
    border:damage().buffer,
    border:warning().distance,
    border:warning().ticks,
    border:contains(vec3(19, 64, 19)),
    border:contains(vec3(21, 64, 5))
  )
  log("shrinking", border:set_size(10, { ticks = 200 }))
end

function steps.unload()
  local copy = nf.worlds.get("it_copy")
  local deleted = copy:unload({ delete = true })
  local void = nf.worlds.get("it_void")
  local saved = void:unload()
  log(
    "unloaded",
    deleted,
    copy:exists(),
    copy:is_managed(),
    saved,
    void:exists(),
    void:is_managed()
  )
  local back = nf.worlds.load("it_void")
  back:load_chunk(vec3(10, 70, 10))
  log(
    "loaded back",
    back:name(),
    back:block(vec3(10, 70, 10)):kind(),
    tostring(back:border():center())
  )
end

nf.commands.register(
  "it-worlds",
  { arguments = { { name = "step", type = "word" } } },
  function(event)
    steps[event.arguments.step]()
  end
)
