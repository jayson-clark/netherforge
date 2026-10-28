local this = this --[[@as Centity]]

-- A door on a hinge: an empty pivot node at the panel's edge, with the panel
-- and its knobs under it, so turning the hinge swings them.
--
-- `open` is a `hold` clip: it stays on its last pose (open) until something
-- else plays. A `once` clip would snap back to rest the moment it ended.
-- Closing stops `open` and plays `close`, which ends at rest anyway.
--
-- Click to open it; it closes by itself after three seconds, or at once if
-- you click it again. The waiting is a task, written top to bottom.
local AUTO_CLOSE = 60

local closer = nil

local function sound(id)
  local world, here = this:world(), this:position()
  if world and here then
    world:play_sound(id, here + vec3(0, 1, 0))
  end
end

local function is_open()
  return this:is_animation_playing("open")
end

local function close()
  if closer then
    closer:cancel()
    closer = nil
  end
  this:stop_animation("open")
  this:play_animation("close")
  sound("minecraft:block.wooden_door.close")
end

local function open()
  this:stop_animation("close")
  this:play_animation("open")
  sound("minecraft:block.wooden_door.open")
  local here = this:location()
  if here then
    nf.particles.play("shockwave", here)
  end
  closer = nf.task(function()
    nf.wait_for(this, "animation_end", function(event)
      return event.animation == "open"
    end)
    nf.wait(AUTO_CLOSE)
    closer = nil
    close()
  end)
end

-- Custom events, so a test can work the door without a player's click.
this:on("door:toggle", function()
  if is_open() then
    close()
  else
    open()
  end
end)

this:on("click", function()
  this:emit("door:toggle")
end)
