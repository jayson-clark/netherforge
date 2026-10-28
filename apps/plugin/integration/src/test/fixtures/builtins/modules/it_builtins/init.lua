-- The engine's built-ins on a real server: a centity's body against the game's own collision
-- shapes, the spatial queries, a flood fill, a temporary centity and properties.
local steps = {}
local world = nf.worlds.default()
-- Lanes of its own in the chunk the paths fixture walks in (x and z 208 to 223, kept loaded round the
-- spawn), clear of that fixture's blocks (x 214 to 220, z 211 to 217), on the flat ground (its top at y = -62).
local X, Z = 208, 209

local function f(n)
  return string.format("%.3f", n)
end

local placed = {}
local function put(at, state)
  world:set_block(at, state)
  placed[#placed + 1] = at
end
local function clear()
  for _, at in ipairs(placed) do
    world:set_block(at, "minecraft:air")
  end
  placed = {}
end

-- It drops onto a slab (the game's half-block collision shape), walks along it into a wall two
-- blocks high, which stops it, and steps up a slab's height from the ground on the way.
function steps.body()
  for x = X + 2, X + 4 do
    put(vec3(x, -62, Z), "minecraft:smooth_stone_slab[type=bottom]")
  end
  put(vec3(X + 7, -62, Z), "minecraft:stone")
  put(vec3(X + 7, -61, Z), "minecraft:stone")
  local walker = nf.centities.spawn("it_walker", vec3(X + 2.5, -58, Z + 0.5))
  log("spawned", walker:has_body(), walker:body().width)
  local landed
  walker:on("land", function(event)
    landed = landed or walker:position()
    log("landed", f(landed.y), event.speed > 1)
  end)
  local bumped = false
  walker:on("bump", function(event)
    bumped = bumped or event.normal.x == -1
  end)
  nf.after(40, function()
    walker:set_walk_velocity(vec3(3, 0, 0))
    nf.after(60, function()
      local at = walker:position()
      local wall = walker:wall()
      log("walked", f(at.x), f(at.y), wall and wall.x, bumped, walker:is_on_ground())
      walker:remove()
      clear()
    end)
  end)
end

function steps.queries()
  local crate = nf.centities.spawn("it_walker", vec3(X + 1.5, -62, Z + 10.5))
  local zombie = world:spawn_entity(
    "minecraft:zombie",
    vec3(X + 4.5, -62, Z + 10.5),
    { tags = { "it_builtins" } }
  )
  zombie:set_ai(false)
  local hits = world:overlap_sphere(vec3(X + 1.5, -61.5, Z + 10.5), 5, { players = false })
  local sweep = world:sweep(
    vec3(X, -61.7, Z + 10.5),
    vec3(X + 6, -61.7, Z + 10.5),
    { radius = 0.1, players = false }
  )
  log(
    "queries",
    #hits,
    hits[1].centity == crate,
    hits[2] and hits[2].entity == zombie,
    sweep[1].centity == crate,
    sweep[2] and sweep[2].entity == zombie
  )
  local box = crate:bounds()
  log("bounds", f(box.min.y), f(box.max.y))
  crate:remove()
  zombie:remove()
end

-- A closed room of stone: 3 × 2 × 3 of air, filled through and walled in.
function steps.flood()
  local x0, z0 = X + 8, Z + 9
  for x = x0, x0 + 4 do
    for y = -62, -59 do
      for z = z0, z0 + 4 do
        local inside = x > x0 and x < x0 + 4 and y > -62 and y < -59 and z > z0 and z < z0 + 4
        put(vec3(x, y, z), inside and "minecraft:air" or "minecraft:stone")
      end
    end
  end
  local room =
    world:flood(vec3(x0 + 1.5, -61, z0 + 1.5), { through = { "passable" }, kinds = true })
  log("flood", #room.positions, room.stopped, #room.edges, room.kinds[1])
  clear()
end

function steps.temporary()
  local shot = nf.centities.spawn("it_walker", vec3(X + 0.5, -62, Z + 13.5), { temporary = true })
  local kept = nf.centities.spawn("it_walker", vec3(X + 2.5, -62, Z + 13.5))
  nf.data("it_builtins").kept = kept:id()
  log(
    "temporary",
    shot:is_temporary(),
    kept:is_temporary(),
    nf.centities.properties("it_walker").speed
  )
end

function steps.after_reload()
  local all = nf.centities.all({ kind = "it_walker" })
  local kept = nf.centities.get(nf.data("it_builtins").kept) ~= nil
  for _, c in ipairs(all) do
    c:remove()
  end
  log("after reload", #all, kept)
end

-- A rope strung between two points, and a tween and an additive animation on a real server's displays.
function steps.layers()
  local c =
    nf.centities.spawn("it_rope", vec3(X + 0.5, -60, Z + 4.5), { temporary = true, lifetime = 200 })
  c:node("chain"):rope_between(vec3(X, -58, Z + 4), vec3(X + 2, -58, Z + 4))
  local rig = c:node("rig")
  rig:set_scale(vec3(2, 2, 2))
  c:play_animation("squash")
  rig:tween({ translation = vec3(0, 1, 0), ticks = 4, easing = "linear" })
  nf.after(6, function()
    local first = c:node("chain[1]"):world_position()
    log(
      "layers",
      f(first.x),
      f(first.y),
      tostring(c:node("chain[5]"):is_visible()),
      f(rig:scale().y),
      f(rig:translation().y)
    )
  end)
end

-- A walk through a shut oak door in a wall: refused without open_doors, through and shut behind it with them.
function steps.doors()
  local z = Z + 8
  for _, dz in ipairs({ -6, -5, -4, -3, -2, -1, 1, 2, 3, 4, 5, 6 }) do
    put(vec3(X + 4, -62, z + dz), "minecraft:stone")
    put(vec3(X + 4, -61, z + dz), "minecraft:stone")
  end
  put(
    vec3(X + 4, -62, z),
    "minecraft:oak_door[facing=east,half=lower,hinge=left,open=false,powered=false]"
  )
  put(
    vec3(X + 4, -61, z),
    "minecraft:oak_door[facing=east,half=upper,hinge=left,open=false,powered=false]"
  )
  local c =
    nf.centities.spawn("it_rope", vec3(X + 1.5, -62, z + 0.5), { temporary = true, lifetime = 400 })
  local goal = vec3(X + 7.5, -62, z + 0.5)
  c:move_to(goal, { range = 6, width = 0.6 })
  local target = c:path_target()
  local shut = target == nil or target:distance(goal) > 1
  c:on("path_end", function(event)
    local door = world:block(vec3(X + 4, -62, z)):state()
    log(
      "doors",
      tostring(shut),
      tostring(event.reached),
      f(c:position().x),
      tostring(door:find("open=false") ~= nil)
    )
    clear()
  end)
  c:move_to(goal, { range = 6, width = 0.6, open_doors = true, speed = 4 })
end

-- netherforge.json's gameRules, set on the world when the project loaded.
function steps.rules()
  log("rules", tostring(world:game_rule("keep_inventory")))
end

-- A window whose first slot shows a storage's and takes one diamond at most, and whose second shows what the
-- player wears on their head and takes only what's worn there.
function steps.gear(event)
  local player = event.player
  player:inventory():clear()
  player:set_equipment("head", nil)
  player:give_item({ kind = "minecraft:diamond", count = 5 })
  player:give_item({ kind = "minecraft:iron_helmet" })
  local menu = player:open_menu("it_gear")
  local ring = menu:slot(0)
  ring:bind(player:storage("it_rings", 1), 0)
  ring:set_rule({ kinds = { "minecraft:diamond" }, max = 1 })
  local head = menu:slot(1)
  head:bind_equipment(player, "head")
  head:set_rule({ wearable = "head", max = 1 })
  log("gear", tostring(menu:slot(1):binding().equipment))
end

-- Where the player's diamonds are, as the server has them: inventory, cursor, storage.
function steps.count(event)
  local player = event.player
  local held = 0
  for _, stack in pairs(player:inventory():items()) do
    if stack.kind == "minecraft:diamond" then
      held = held + (stack.count or 1)
    end
  end
  local menu = player:menu()
  local cursor = menu and menu:cursor_item(player)
  local ring = player:storage("it_rings", 1):item(0)
  log("count", held, cursor and (cursor.count or 1) or 0, ring and (ring.count or 1) or 0)
end

function steps.worn(event)
  local head = event.player:equipment("head")
  local ring = event.player:storage("it_rings", 1):item(0)
  log(
    "worn",
    head and head.kind or "nil",
    ring and (ring.kind .. "x" .. (ring.count or 1)) or "nil"
  )
end

nf.commands.register(
  "it-builtins",
  { arguments = { { name = "step", type = "word" } } },
  function(event)
    steps[event.arguments.step](event)
  end
)
