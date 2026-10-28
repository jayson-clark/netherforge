local steps = {}

local function show(label, world)
  log(
    label,
    world:spawn_limit("monster"),
    world:spawn_interval("monster"),
    world:spawn_limit("animal"),
    world:spawn_limit("water_ambient")
  )
end

function steps.read()
  show("rates", nf.worlds.default())
end

function steps.change()
  local world = nf.worlds.default()
  log("changed", world:set_spawn_limit("monster", 5), world:set_spawn_interval("monster", 7))
  show("rates", world)
  log("reset", world:set_spawn_limit("animal", -1), world:spawn_limit("animal"))
end

function steps.create()
  local world = nf.worlds.create("it_spawn", { generator = "void" })
  show("created", world)
  -- The server folder outlives a run, so the next one must be able to create it again.
  world:unload({ delete = true })
end

nf.commands.register(
  "it-spawn",
  { arguments = { { name = "step", type = "word" } } },
  function(event)
    steps[event.arguments.step]()
  end
)
