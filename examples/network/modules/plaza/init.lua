-- Each server's plaza: a round floor at spawn in the server's colours, two portals to the other
-- servers (west and east), and boards (text over the plaza, kept up to date by whoever made them).
--
-- Every server builds its own plaza when it starts, from what part it plays: the same code, three
-- places. Walking into a portal is the travel module's business; this module only says where the
-- portals are and which one a position is in.

local net = require("net")

local plaza = {}

local world = nf.worlds.default()
plaza.world = world

-- The meadow's grass is at y 64: people stand at 65.
local FLOOR = 64
plaza.Y = FLOOR + 1
plaza.RADIUS = 15

--- Where everyone arrives: the middle, facing north (yaw 180), where each plaza's board stands.
plaza.SPAWN = world:location(vec3(0.5, plaza.Y, 0.5), 180, 0)

--- How each part's plaza looks.
local LOOKS = {
  lobby = {
    floor = "minecraft:smooth_quartz",
    edge = "minecraft:cyan_terracotta",
    frame = "minecraft:cyan_concrete",
    dust = "#40e0e0",
  },
  arena = {
    floor = "minecraft:smooth_sandstone",
    edge = "minecraft:red_terracotta",
    frame = "minecraft:red_concrete",
    dust = "#ff4040",
  },
  market = {
    floor = "minecraft:spruce_planks",
    edge = "minecraft:yellow_terracotta",
    frame = "minecraft:gold_block",
    dust = "#ffc040",
  },
}

--- Where each plaza's portals lead: one west, one east, to the other two parts.
local LEADS = {
  lobby = { west = "market", east = "arena" },
  arena = { west = "lobby", east = "market" },
  market = { west = "arena", east = "lobby" },
}

-- A portal is a frame in the plane x = PORTAL_X[side], 5 wide (z -2 to 2) and 6 tall, open 3 wide
-- and 4 tall in the middle.
local PORTAL_X = { west = -13, east = 13 }

---@class Portal
---@field side "west"|"east"
---@field to "lobby"|"arena"|"market" The part it leads to.
---@field x integer The frame's x.
---@field front Location Where to stand in front of it, facing the plaza.
---@field center Vec3 The middle of its opening, where a walker aims.

--- This plaza's portals, by side.
---@type table<string, Portal>
plaza.portals = {}
for side, x in pairs(PORTAL_X) do
  local inward = x < 0 and 1 or -1
  plaza.portals[side] = {
    side = side,
    to = LEADS[net.role()][side],
    x = x,
    -- Facing east (yaw -90) from the west portal, west (yaw 90) from the east one.
    front = world:location(vec3(x + inward * 2 + 0.5, plaza.Y, 0.5), inward > 0 and -90 or 90, 0),
    center = vec3(x + 0.5, plaza.Y, 0.5),
  }
end

--- The portal that leads to `role`, or nil.
---@param role string
---@return Portal?
function plaza.portal_to(role)
  for _, portal in pairs(plaza.portals) do
    if portal.to == role then
      return portal
    end
  end
  return nil
end

--- The portal whose opening `position` is in, or nil.
---@param position Vec3
---@return Portal?
function plaza.portal_at(position)
  local block = position:floor()
  for _, portal in pairs(plaza.portals) do
    if
      block.x == portal.x
      and block.z >= -1
      and block.z <= 1
      and block.y >= plaza.Y
      and block.y <= plaza.Y + 3
    then
      return portal
    end
  end
  return nil
end

--- A random spot on the plaza, at least `from` and at most `to` blocks from the middle.
---@param random Random
---@param from number?
---@param to number?
---@return Vec3
function plaza.random_spot(random, from, to)
  local angle = random:number(0, math.pi * 2)
  local distance = random:number(from or 2, to or 10)
  return vec3(math.cos(angle) * distance + 0.5, plaza.Y, math.sin(angle) * distance + 0.5)
end

---@class Board
---@field centity Centity?
local Board = {}
Board.__index = Board

--- Shows `text` (MiniMessage, lines joined with "\n") on the board.
---@param text string
function Board:set(text)
  local node = self.centity and self.centity:exists() and self.centity:node("text")
  if node then
    node:set_display_text(text)
  end
end

--- A board: text standing over the plaza, `scale` times a normal text display's size. It's
--- temporary, so it goes when its script unloads and comes back with the next build.
---@param position Vec3
---@param scale number?
---@return Board
function plaza.board(position, scale)
  local centity = nf.centities.spawn("board", position, { temporary = true })
  local node = centity and centity:node("text")
  if node and scale then
    node:set_scale(vec3(scale, scale, scale))
  end
  return setmetatable({ centity = centity }, Board)
end

local function build_portal(portal, look)
  local x = portal.x
  local frame = LOOKS[portal.to].frame
  world:fill_blocks(vec3(x, plaza.Y, -2), vec3(x, plaza.Y + 5, 2), frame)
  world:fill_blocks(vec3(x, plaza.Y, -1), vec3(x, plaza.Y + 3, 1), "minecraft:air")
  -- A step of the floor through it, so walking in is walking on.
  world:fill_blocks(vec3(x, FLOOR, -1), vec3(x, FLOOR, 1), look.floor)
  local label = plaza.board(vec3(x + 0.5, plaza.Y + 6.3, 0.5), 1.4)
  label:set(net.TITLES[portal.to] .. "\n<gray>walk in, or /go " .. portal.to)
end

--- Builds this server's plaza: the floor, its edge, lights and the portals. Run as the server
--- starts; building it again is harmless.
function plaza.build()
  local look = LOOKS[net.role()]
  local r = plaza.RADIUS
  -- The meadow is flat and bare, so there's nothing to clear: the floor goes straight in.
  -- A row at a time: the disc in the edge's block, then a disc one smaller in the floor's.
  for _, disc in ipairs({ { r, look.edge }, { r - 1, look.floor } }) do
    local radius, block = disc[1], disc[2]
    for x = -radius, radius do
      local reach = math.floor(math.sqrt(radius * radius - x * x))
      world:fill_blocks(vec3(x, FLOOR, -reach), vec3(x, FLOOR, reach), block)
    end
  end
  for angle = 0, 359, 30 do
    local a = math.rad(angle)
    world:set_block(
      vec3(math.floor(math.cos(a) * (r - 3)), FLOOR, math.floor(math.sin(a) * (r - 3))),
      "minecraft:sea_lantern"
    )
  end
  for _, portal in pairs(plaza.portals) do
    build_portal(portal, look)
  end
  world:set_spawn_location(plaza.SPAWN)
  world:set_time_of_day(6000)
end

-- The portals shimmer in the colour of where they lead, and it's always midday.
nf.every(10, function()
  for _, portal in pairs(plaza.portals) do
    world:spawn_particle("minecraft:dust", portal.center + vec3(0, 1.5, 0), {
      count = 12,
      spread = vec3(0.1, 0.9, 0.7),
      color = LOOKS[portal.to].dust,
      size = 1.2,
    })
  end
end)
nf.every(20 * 60, function()
  world:set_time_of_day(6000)
end)

return plaza
