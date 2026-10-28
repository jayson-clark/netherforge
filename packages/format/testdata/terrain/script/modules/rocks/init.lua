-- Boulders, for a terrain's script to place.
local shapes = require("shapes")

local rocks = {}

--- A boulder of [size] around ([x], [y], [z]): mossy where a random roll says.
---@param chunk Chunk
function rocks.boulder(chunk, x, y, z, size)
  for _, offset in ipairs(shapes.ball(size)) do
    local block = math.random() < 0.3 and "minecraft:mossy_cobblestone" or "minecraft:cobblestone"
    chunk:set(x + offset[1], y + offset[2], z + offset[3], block)
  end
end

return rocks
