-- Steps the main world generation scenario runs from the console, each logging what it found in the server's main world.
local steps = {}

local function world()
  return nf.worlds.default()
end

-- spawn: where the main world's spawn is.
function steps.spawn()
  local at = world():spawn_location().position
  log("spawn", math.floor(at.x), math.floor(at.y), math.floor(at.z))
end

-- column <x> <z>: the top block of a column, and what it is.
function steps.column(x, z)
  local w = world()
  w:load_chunk(vec3(x, 0, z))
  local top = w:highest_block(vec3(x, 0, z))
  log("column", math.floor(top:position().y), top:kind())
end

-- block <x> <y> <z>: what is at a position, and which of the project's blocks it is (adopted from the state the generator wrote).
function steps.block(x, y, z)
  local w = world()
  w:load_chunk(vec3(x, y, z))
  local block = w:block(vec3(x, y, z))
  local custom = block:custom()
  log("block", block:kind(), custom and custom:id() or "none")
end

nf.commands.register("it-mainworld", {
  arguments = {
    { name = "step", type = "word" },
    { name = "a", type = "integer", default = 0 },
    { name = "b", type = "integer", default = 0 },
    { name = "c", type = "integer", default = 0 },
  },
}, function(event)
  local args = event.arguments
  steps[args.step](args.a, args.b, args.c)
end)
