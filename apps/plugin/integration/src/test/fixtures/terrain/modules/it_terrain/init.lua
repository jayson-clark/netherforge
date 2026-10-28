-- Steps the terrain scenario runs from the console, each logging what it found.
local steps = {}

-- The world the project's generator makes: made once, found or loaded after.
local function world(seed)
  return nf.worlds.get("it_wg")
    or nf.worlds.load("it_wg")
    or nf.worlds.create("it_wg", { terrain = "ruby_hills", seed = seed })
end

-- make <seed>
function steps.make(seed)
  local made = world(seed)
  log("made", made:name(), tostring(made:is_managed()))
end

-- column <x> <z>: the top block of a column, and what it is.
function steps.column(x, z)
  local w = world(0)
  w:load_chunk(vec3(x, 0, z))
  local top = w:highest_block(vec3(x, 0, z))
  log("column", math.floor(top:position().y), top:kind())
end

-- block <x> <y> <z>: what is at a position, and which of the project's blocks it is (adopted from the state the generator wrote).
function steps.block(x, y, z)
  local w = world(0)
  w:load_chunk(vec3(x, y, z))
  local block = w:block(vec3(x, y, z))
  local custom = block:custom()
  log("block", block:kind(), custom and custom:id() or "none")
end

-- The centities the structures' markers became, once their chunks are loaded.
function steps.guards()
  log("guards", #nf.centities.all({ kind = "it_guard", world = world(0) }))
end

-- biome <x> <z>: the biome of a column's top block.
function steps.biome(x, z)
  local w = world(0)
  w:load_chunk(vec3(x, 0, z))
  log("biome", w:highest_block(vec3(x, 0, z)):biome())
end

-- listen: each chunk generated from now on, and the biome of its middle column's top block, read in the handler.
function steps.listen()
  local w = world(0)
  w:on("chunk_generated", function(event)
    local top = event.world:highest_block(vec3(event.x * 16 + 8, 0, event.z * 16 + 8))
    log("generated", event.x, event.z, top and top:biome() or "none")
  end)
  log("listening", w:name())
end

nf.commands.register("it-terrain", {
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

-- it-terrain-locate <x> <z> <biome>: the server's search for the nearest place of a biome, from a column.
nf.commands.register("it-terrain-locate", {
  arguments = {
    { name = "x", type = "integer" },
    { name = "z", type = "integer" },
    { name = "biome", type = "text" },
  },
}, function(event)
  local args = event.arguments
  world(0):locate_biome(args.biome, { near = vec3(args.x, 64, args.z) }, function(position, err)
    if position then
      log("located", math.floor(position.x), math.floor(position.z))
    else
      log("located", "none", err)
    end
  end)
end)
