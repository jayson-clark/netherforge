-- Steps the datapack scenario runs from the console, each logging what it found.
local steps = {}

-- A world of the game's usual kind: its terrain is the overworld's noise settings, which the fixture's datapack replaces.
local function world()
  return nf.worlds.get("it_dp")
    or nf.worlds.load("it_dp")
    or nf.worlds.create("it_dp", { generator = "normal", seed = 5, structures = false })
end

-- columns: for columns across a few chunks, the block at y 40, the block at y 100 and the highest block's y.
-- In a task, a wait after the world and after each chunk: making the world and generating nine chunks in one call ran
-- past a call's time limit on a loaded machine (each wait gives the task a whole call again). Held, not just loaded:
-- a chunk nobody's near unloads at the tick's end, and the reads are ticks later. Dropping the world lets go of them.
local function columns()
  local w = world()
  nf.wait(1)
  for cx = -1, 1 do
    for cz = -1, 1 do
      w:hold_chunk(vec3(cx * 16, 64, cz * 16))
      nf.wait(1)
    end
  end
  local found = {}
  for cx = -1, 1 do
    for cz = -1, 1 do
      local x, z = cx * 16 + 8, cz * 16 + 8
      local top = w:highest_block(vec3(x, 0, z))
      found[#found + 1] = w:block(vec3(x, 40, z)):kind()
        .. ","
        .. w:block(vec3(x, 100, z)):kind()
        .. ","
        .. math.floor(top:position().y)
    end
  end
  log("columns", table.concat(found, ";"))
end

function steps.columns()
  nf.task(columns)
end

-- drop: deletes the world, so nothing of it outlives the scenario.
function steps.drop()
  log("dropped", world():unload({ delete = true }))
end

nf.commands.register(
  "it-datapack",
  { arguments = { { name = "step", type = "word" } } },
  function(event)
    steps[event.arguments.step]()
  end
)
