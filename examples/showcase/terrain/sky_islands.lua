-- The sky islands' one stage. The file makes islands floating round y 112 over ground at its
-- `base`, as any 3D terrain does; this hollows that ground away, so under the islands there is
-- nothing but sky, all the way down. The expo's terrain scripts booth sends players here.

---@type TerrainStages
local stages = {}

-- Below this, nothing is solid. The islands' band starts well above it (112 - 48 / 2).
local OPEN_BELOW = 72

-- `value` is the file's density at a point, in blocks: above 0 is solid.
function stages.density(_, y, _, value)
  if y < OPEN_BELOW then
    return math.min(value, -1)
  end
  return value
end

return stages
