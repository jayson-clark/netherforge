-- Steps the biome scenario runs from the console, each logging what it found.
local steps = {}

-- What the generator itself makes, as opposed to what decorates it.
local ground = {
  ["minecraft:air"] = true,
  ["minecraft:grass_block"] = true,
  ["minecraft:dirt"] = true,
  ["minecraft:stone"] = true,
  ["minecraft:water"] = true,
  ["minecraft:sand"] = true,
}

-- The world netherforge.json makes with the example's generator.
local function world()
  return nf.worlds.get("it_grove") or nf.worlds.load("it_grove")
end

-- made: whether the world is there.
function steps.made()
  log("made", tostring(world() ~= nil))
end

-- A tree's crown or trunk: what a column is looked under for the ground.
local function tree(kind)
  return kind:sub(-7) == "_leaves" or kind:sub(-4) == "_log" or kind:sub(-5) == "_wood"
end

-- plants <chunk x> <chunk z>: what's on the ground of a chunk, by kind: in every other column, its highest block (a
-- tree's leaves) and what stands just above it (grass and flowers aren't counted as the highest), and under a tree
-- the same of the first block beneath it. Boulders are placed before trees grow: whether one ends up under a crown
-- depends on the neighbouring chunks' trees too, and on whether those chunks were decorated yet, which is the
-- server's timing, so a column is looked under its tree rather than only at its top.
function steps.plants(cx, cz)
  local w = world()
  w:load_chunk(vec3(cx * 16, 64, cz * 16))
  local counts = {}
  local function count(kind)
    if not ground[kind] then
      counts[kind] = (counts[kind] or 0) + 1
    end
  end
  for x = cx * 16, cx * 16 + 15, 2 do
    for z = cz * 16, cz * 16 + 15, 2 do
      local top = w:highest_block(vec3(x, 0, z))
      local y = math.floor(top:position().y)
      count(top:kind())
      count(w:block(vec3(x, y + 1, z)):kind())
      if tree(top:kind()) then
        repeat
          y = y - 1
          local kind = w:block(vec3(x, y, z)):kind()
        until (kind ~= "minecraft:air" and not tree(kind)) or y <= w:min_height()
        count(w:block(vec3(x, y, z)):kind())
        count(w:block(vec3(x, y + 1, z)):kind())
      end
    end
  end
  local kinds = {}
  for kind, n in pairs(counts) do
    kinds[#kinds + 1] = kind .. "=" .. n
  end
  table.sort(kinds)
  log("plants", table.concat(kinds, ","))
end

nf.commands.register("it-grove", {
  arguments = {
    { name = "step", type = "word" },
    { name = "a", type = "integer", default = 0 },
    { name = "b", type = "integer", default = 0 },
  },
}, function(event)
  local args = event.arguments
  steps[args.step](args.a, args.b)
end)
