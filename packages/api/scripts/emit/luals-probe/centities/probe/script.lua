local this = this --[[@as Centity]]

this:on("spawn", function(event)
  print(event.centity:id())
end)
this:on("remove", function(event)
  print(event.centity:id())
end)
this:on("tick", function(event)
  print(event.tick + 1)
end, { every = 20 })
this:on("click", function(event)
  print(event.player:name(), event.target:name(), event.click == "left")
  local hit = event.hit_position
  if hit then
    print(hit.x)
  end
  event:stop()
  event:cancel()
end)
this:on("animation_start", function(event)
  print(event.animation:upper())
end)
this:once("animation_end", function(event)
  print(event.animation, event.centity:id())
end)
this:on("chunk_load", function(event)
  print(event.centity:id())
end)
this:on("shop:bought", function(event)
  print(event.name)
end)
this:emit("shop:bought", { price = 3 })

local node = assert(this:node("root"))
node:on("click", function(event)
  print(event.player:name(), event.target:name())
end)
node:on("collide", function(event)
  print(event.node:name(), event.speed * 2, event.hit_position.y, event.hit_normal:length())
  if event.other then
    print(event.other:name())
  end
end)
node:on("wake", function(event)
  print(event.node:name())
end)
node:once("sleep", function(event)
  print(event.node:name())
end)

-- Vec3 maths, both ways round.
local a = vec3(1, 2, 3)
local b = vec3.up
local c = a + b
local d = a - b
local e = -a
local f = a * 2
local g = 2 * a
local h = a * b
local i = a / 2
local j = (a + b) * 0.5 + g:normalized() * 3
local k = 0.5 * (a - b)
local n = a:dot(b) + a:length() + a:distance(b)
local x, y, z = a:unpack()
print(c.x, d.y, e.z, f.x, g.y, h.z, i.x, j.y, k.z, n + x + y + z, a == b, tostring(a))
---@type Vec3
local typed = g
local _ = typed

local data = this:data()
if data then
  data.count = (data.count or 0) + 1
end
local loc = this:location()
if loc then
  print(loc.world:name(), loc.position.x, loc.yaw)
  local moved = loc:offset(vec3(0, 1, 0)):with_position(vec3.zero)
  this:teleport(moved)
  this:teleport(vec3(1, 2, 3))
end
this:play_animation("spin", { speed = 2, loop = true })

-- Custom events carry their payload's fields.
this:on("shop:sold", function(event)
  print(event.price + 1, event.name)
end)
nf.on("shop:sold", function(event)
  print(event.price)
end)

-- A task waits on a typed event.
nf.task(function()
  local click = nf.wait_for(this, "click", function(ev)
    return ev.click == "left"
  end)
  click.player:send_message("clicked")
  local hit = nf.wait_for(node, "collide")
  print(hit.speed)
  local custom = nf.wait_for(this, "shop:sold")
  print(custom.price)
  local joined = nf.wait_for(nf, "player_join")
  print(joined.first_join)
end)
