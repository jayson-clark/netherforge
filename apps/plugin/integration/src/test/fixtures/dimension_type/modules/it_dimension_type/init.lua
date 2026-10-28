-- Steps the dimension scenario runs from the console, each logging what it found.
local steps = {}

-- heights: the main world's build limits.
function steps.heights()
  local w = nf.worlds.default()
  log("heights", w:min_height(), w:max_height())
end

-- block <x> <y> <z>: what is at a position in the main world.
function steps.block(x, y, z)
  local w = nf.worlds.default()
  w:load_chunk(vec3(x, y, z))
  log("block", w:block(vec3(x, y, z)):kind())
end

-- mine: a world of the project's `deep` dimension type, its limits, blocks set at both ends of them, then deleted.
function steps.mine()
  local w = nf.worlds.create(
    "it_deep_mine",
    { dimension_type = "deep", generator = "void", structures = false }
  )
  w:load_chunk(vec3(0, 0, 0))
  local low = w:set_block(vec3(0, w:min_height(), 0), "minecraft:gold_block")
  local high = w:set_block(vec3(0, w:max_height() - 1, 0), "minecraft:gold_block")
  log(
    "mine",
    w:min_height(),
    w:max_height(),
    tostring(low),
    tostring(high),
    w:block(vec3(0, w:min_height(), 0)):kind()
  )
  w:unload({ delete = true })
end

nf.commands.register("it-dimension", {
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
