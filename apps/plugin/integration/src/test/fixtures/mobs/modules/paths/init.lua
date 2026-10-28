nf.commands.register("it-paths", function()
  local world = nf.worlds.default()
  local wall = {}
  for z = 211, 217 do
    for y = -62, -60 do
      wall[#wall + 1] = vec3(217, y, z)
    end
  end
  local step = vec3(220, -62, 214)
  for _, at in ipairs(wall) do
    world:set_block(at, "minecraft:stone")
  end
  world:set_block(step, "minecraft:stone")
  local function clear()
    for _, at in ipairs(wall) do
      world:set_block(at, "minecraft:air")
    end
    world:set_block(step, "minecraft:air")
  end

  local box = nf.centities.spawn("it_box", vec3(214, -62, 214))
  local function feet()
    return box:to_world(vec3(0.5, 0, 0.5))
  end
  local target = vec3(220.5, -61, 214.5)
  local back = vec3(214.5, -58, 214.5)
  local through = false
  box:on("tick", function()
    local at = feet()
    -- Its block may never overlap the wall's.
    if at.y < -59 and math.abs(at.x - 217.5) < 0.999 and at.z > 210.501 and at.z < 217.499 then
      through = true
    end
  end)
  box:once("path_end", function(event)
    log("centity walked", event.reached, (feet() - target):length() < 0.1, not through)
    nf.after(1, function()
      local off = box:move_to(back, { fly = true, speed = 8 })
      box:once("path_end", function(flown)
        log("centity flew", off, flown.reached, (feet() - back):length() < 0.1, not through)
        box:remove()
        clear()
      end)
    end)
  end)
  local off = box:move_to(target, { speed = 6 })
  log("centity walking", off, box:has_path(), box:path_target() == target)
end)
