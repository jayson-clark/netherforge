-- The blocks of shapes, as offsets: required as "shapes" from beside it, in the rocks module.
local shapes = {}

function shapes.ball(radius)
  local out = {}
  for dx = -radius, radius do
    for dy = 0, radius do
      for dz = -radius, radius do
        if dx * dx + dy * dy + dz * dz <= radius * radius then
          out[#out + 1] = { dx, dy, dz }
        end
      end
    end
  end
  return out
end

return shapes
