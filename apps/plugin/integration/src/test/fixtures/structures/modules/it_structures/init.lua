local steps = {}

-- A small ruin in the main world: a floor of bricks and a marker asking for a guard.
function steps.prepare()
  local world = nf.worlds.default()
  world:load_chunk(vec3(0, -60, 0))
  for x = 0, 2 do
    for z = 0, 2 do
      world:set_block(vec3(x, -60, z), "minecraft:stone_bricks")
    end
  end
  local marker = world:spawn_entity(
    "minecraft:marker",
    vec3(1.5, -59, 1.5),
    { tags = { "nf.centity.it_guard" } }
  )
  log("prepared", marker ~= nil)
end

-- A world the game generates structures in, loaded around its spawn so the ruin generates.
function steps.generate()
  local world = nf.worlds.get("it_gen")
    or nf.worlds.load("it_gen")
    or nf.worlds.create("it_gen", { seed = 7 })
  for cx = -4, 4 do
    for cz = -4, 4 do
      world:load_chunk(vec3(cx * 16, 64, cz * 16))
    end
  end
  log("generated", world:name())
end

-- The markers turn into centities on the tick after their chunk loads, so the count waits a few ticks (server
-- ticks, however long a loaded machine takes over them) after whatever loaded chunks last.
function steps.count()
  nf.after(5, function()
    local world = nf.worlds.get("it_gen")
    log("guards", #nf.centities.all({ kind = "it_guard", world = world }))
  end)
end

nf.commands.register(
  "it-structures",
  { arguments = { { name = "step", type = "word" } } },
  function(event)
    steps[event.arguments.step]()
  end
)
