-- Ridges raised over the file's hills, capped with cobblestone where they're high and cold,
-- and boulders (from the rocks module) scattered on the ground: every part of the stage API.
---@type Terrain
local terrain = ...
local rocks = require("rocks")

local ridges = terrain.noise("ridges")
local lumps = terrain.noise("rocks")

---@type TerrainStages
local stages = {}

-- A ridge's crest is up to 14 blocks over the file's ground; a lump of the 3D noise adds to it.
function stages.height(x, z, height)
  local ridge = math.max(0, ridges:at(x, z))
  local crest = ridge * ridge * 14
  return height + crest + lumps:at(x, height, z) * 1.5
end

function stages.terrain(chunk)
  for x = chunk:min_x(), chunk:min_x() + 15 do
    for z = chunk:min_z(), chunk:min_z() + 15 do
      local top = terrain.height(x, z)
      if top > terrain.sea_level() + 6 and terrain.area(x, z) == "cold" then
        chunk:fill(x, top - 2, z, x, top, z, "minecraft:cobblestone")
      end
    end
  end
end

function stages.decorate(chunk)
  for _ = 1, 3 do
    local x = chunk:min_x() + math.random(0, 15)
    local z = chunk:min_z() + math.random(0, 15)
    local top = terrain.height(x, z)
    if chunk:block(x, top, z) ~= "minecraft:air" and terrain.biome(x, z) == "minecraft:plains" then
      rocks.boulder(chunk, x, top + 1, z, math.random(1, 2))
    end
  end
  -- One glowing block in each chunk, deep down, where the seed says.
  chunk:set(chunk:min_x() + math.random(0, 15), terrain.min_y() + 8, chunk:min_z() + math.random(0, 15), "minecraft:glowstone")
end

return stages
