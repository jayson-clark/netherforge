local steps = {}

local function rates(world)
  return world:spawn_limit("monster"),
    world:spawn_interval("monster"),
    world:spawn_limit("animal"),
    world:spawn_limit("water_ambient")
end

local function show(label, world)
  log(label, rates(world))
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

-- In a task, a wait after making the world: the game prepares its spawn as it's made, which on a loaded machine takes
-- most of a call's time limit by itself (each wait gives the task a whole call again).
function steps.create()
  nf.task(function()
    local world = nf.worlds.create("it_spawn", { generator = "void" })
    nf.wait(1)
    local found = { rates(world) }
    -- Deleted before the log line the scenario waits for: the server folder outlives a run, so the next one must be
    -- able to create it again.
    world:unload({ delete = true })
    log("created", table.unpack(found))
  end)
end

nf.commands.register(
  "it-spawn",
  { arguments = { { name = "step", type = "word" } } },
  function(event)
    steps[event.arguments.step]()
  end
)
