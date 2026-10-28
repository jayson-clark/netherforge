-- The expo: the main world's walk-through of everything NetherForge does. The main world is a flat
-- plaza (terrain/plaza.json); spawn is the hub, and an avenue runs south from it with a booth on
-- each side every few steps, one booth per feature.
--
-- This module owns the layout and what every booth shares: the floor and walls, the title over the
-- entrance and the panel on the back wall that explain the feature, captions beside each exhibit,
-- kiosks (buttons on pedestals), knowing which booth a player stands in, the tour menu, and the way
-- to the other worlds and back. Each booth's own module (modules/(booths)/...) says what it shows:
--
--   local booth = expo.booth({ id = "physics", title = ..., lines = { ... }, build = function(booth) ... end })
--
-- `build` runs once the world is ready, and again whenever the booth's module is saved: everything
-- the booth helpers spawned is removed and the booth is built afresh, so an edit shows at once.

local expo = {}

local world = nf.worlds.default()

--- The main world, where the expo stands.
expo.world = world

-- The plaza's ground is at y 64: players stand at 65.
local FLOOR = 64
-- The avenue runs along z, x from -AISLE to AISLE; booths sit either side of it.
local AISLE = 6
-- Where the first row of booths starts along z, and how far apart rows are.
local FIRST = 14
local ROW = 15
-- A booth's size: LENGTH along the avenue (z), DEPTH back from it (x).
local LENGTH = 13
local DEPTH = 11

--- Where everyone arrives: the hub, facing down the avenue (yaw 0 faces south, +z).
expo.SPAWN = world:location(vec3(0.5, FLOOR + 1, 0.5), 0, 0)

--- Every booth, in the order they stand along the avenue: odd places on the west side, even on the
--- east, two to a row. A booth's module fills its place; a place with no module stays empty.
expo.ORDER = {
  "centities",
  "physics",
  "motion",
  "blocks",
  "items",
  "recipes",
  "loot",
  "menus",
  "dialogs",
  "particles",
  "cutscenes",
  "screens",
  "cinema",
  "packs",
  "interface",
  "teams",
  "buffs",
  "mobs",
  "mannequins",
  "bots",
  "spawning",
  "advancements",
  "data",
  "settings",
  "packages",
  "web",
  "wasm",
  "worldgen",
  "terrain_scripts",
  "worlds",
  "environment",
  "integrations",
}

-- Each booth's floor, by place: concrete colours, round and round.
local COLOURS = {
  "light_blue",
  "lime",
  "orange",
  "magenta",
  "yellow",
  "cyan",
  "pink",
  "purple",
  "white",
  "blue",
  "green",
  "red",
}

local WALL = "minecraft:polished_blackstone_bricks"
-- Mannequins and other game entities the expo spawns carry this tag, so a rebuild finds them.
local TAG = "showcase.expo"

local booths = {} ---@type table<string, Booth>
local ready = false

local function place_of(id)
  for index, other in ipairs(expo.ORDER) do
    if other == id then
      return index
    end
  end
  return nil
end

-- ---- Booths ---------------------------------------------------------------------------------

--- One booth: its place on the avenue and what's been spawned in it. Positions in a booth are
--- given as (u, y, v): u blocks back from the avenue (0 is the front row, DEPTH - 1 the back
--- wall), y blocks above the floor (1 is where players stand) and v blocks along it (0 to
--- LENGTH - 1, the middle at 6). Whichever side it's on, the booth faces the avenue.
---@class Booth
---@field id string
---@field def table
---@field place integer
---@field west boolean
---@field z0 integer
---@field spawned table[]
---@field problem string? Why it didn't build, the last time it was built.
local Booth = {}
Booth.__index = Booth

expo.LENGTH, expo.DEPTH, expo.MIDDLE = LENGTH, DEPTH, 6

--- The block at (u, y, v), as a whole-number world position.
---@return Vec3
function Booth:cell(u, y, v)
  local x = self.west and (-(AISLE + 1) - u) or (AISLE + 1 + u)
  return vec3(x, FLOOR + y, self.z0 + v)
end

--- The yaw that faces out of the booth, at the avenue: what a sign in it should face.
---@return number
function Booth:yaw_out()
  return self.west and -90 or 90
end

--- A point in the booth (u and v may be fractions: 2 is the middle of the third block back),
--- facing out of it.
---@return Location
function Booth:at(u, y, v)
  local x = self.west and (-(AISLE + 0.5) - u) or (AISLE + 1.5 + u)
  return world:location(vec3(x, FLOOR + y, self.z0 + v + 0.5), self:yaw_out(), 0)
end

--- The same point as a bare position.
---@return Vec3
function Booth:pos(u, y, v)
  return self:at(u, y, v).position
end

--- The way out of the booth, as a direction: `"east"` for a booth on the west side.
---@return "east"|"west"
function Booth:facing()
  return self.west and "east" or "west"
end

--- Where the tour menu drops a visitor: on the avenue in front of the booth, looking in.
---@return Location
function Booth:entrance()
  local front = self:at(-2, 1, 6)
  return world:location(front.position, self.west and 90 or -90, 0)
end

--- Whether a world position is inside the booth.
---@param position Vec3
---@return boolean
function Booth:contains(position)
  local low, high = self:cell(0, 0, 0), self:cell(DEPTH - 1, 0, LENGTH - 1)
  local x0, x1 = math.min(low.x, high.x), math.max(low.x, high.x) + 1
  return position.x >= x0 and position.x < x1 and position.z >= low.z and position.z < high.z + 1
end

--- Keeps hold of something the booth made, so the next rebuild removes it.
local function track(booth, thing)
  if thing then
    booth.spawned[#booth.spawned + 1] = thing
  end
  return thing
end

--- Spawns a centity in the booth, facing the avenue (or `yaw`). It's temporary: not saved, and
--- gone when the booth is rebuilt or the expo reloads.
---@param kind string
---@param yaw number?
---@return Centity?
function Booth:spawn(kind, u, y, v, yaw)
  local at = self:at(u, y, v)
  if yaw then
    at = world:location(at.position, yaw, 0)
  end
  return track(self, nf.centities.spawn(kind, at, { temporary = true }))
end

--- Spawns one of the game's entities in the booth, tagged so a rebuild takes it away again.
---@param kind string
---@return Entity?
function Booth:spawn_entity(kind, u, y, v, options)
  options = options or {}
  options.tags = { TAG }
  local entity = world:spawn_entity(kind, self:at(u, y, v), options)
  return track(self, entity)
end

--- Text on a sign of its own: `options.scale` (1 is a normal text display), `options.width` (pixels
--- before a line wraps), `options.turns` (it turns to face whoever reads it).
---@param text string MiniMessage.
---@return Centity?
function Booth:placard(u, y, v, text, options)
  options = options or {}
  local placard = self:spawn("placard", u, y, v)
  local node = placard and placard:node("text")
  if node then
    node:set_display_text(text)
    node:set_display_text_style({
      line_width = options.width or 240,
      alignment = options.alignment or "left",
    })
    if options.scale then
      node:set_scale(vec3(options.scale, options.scale, options.scale))
    end
    if options.turns then
      node:set_billboard("vertical")
    end
  end
  return placard
end

--- A caption beside an exhibit: what it is and how it's done, turning to face whoever reads it.
---@param text string MiniMessage: a line in white to say what it is, then grey lines on how.
---@param y number? How high, from the floor (default 2.4).
---@param width integer? Pixels before a line wraps (default 200: about three blocks at this size).
---@return Centity?
function Booth:caption(u, v, text, y, width)
  return self:placard(
    u,
    y or 2.4,
    v,
    text,
    { scale = 0.55, width = width or 200, turns = true, alignment = "center" }
  )
end

--- A kiosk: a button on a pedestal, with a label over it. Clicking it presses it and calls
--- `on_press(player, event)`.
---@param label string MiniMessage.
---@param on_press fun(player: Player, event: ClickEvent)
---@return Centity?
function Booth:kiosk(u, v, label, on_press)
  local kiosk = self:spawn("kiosk", u, 1, v)
  if not kiosk then
    return nil
  end
  assert(kiosk:node("label")):set_display_text(label)
  kiosk:on("click", function(event)
    kiosk:play_animation("press")
    event.player:play_sound("minecraft:block.stone_button.click_on", { volume = 0.5 })
    on_press(event.player, event)
  end)
  return kiosk
end

--- A kiosk that asks (the portal dialog) and then takes the player to another world: one of
--- `expo.DESTINATIONS`' names.
---@return Centity?
function Booth:portal(u, v, label, destination)
  return self:kiosk(u, v, label, function(player)
    local where = expo.DESTINATIONS[destination]
    player:open_dialog("portal", {
      context = destination,
      body = { where = "<gray>" .. (where and where.description or destination) },
    })
  end)
end

--- Hangs a screen on the booth's back wall, facing out, `width` maps wide centred on the booth (or
--- starting `from` blocks along it), its top `top` blocks over the floor.
---@param screen Screen
---@param top integer
---@param from integer?
---@return boolean
function Booth:hang(screen, top, from)
  local width = screen:width() // 128
  local first = from or (expo.MIDDLE - width // 2)
  -- Seen from the avenue, a west booth runs left to right as z goes down; an east one as it goes up.
  local left = self.west and (first + width - 1) or first
  return screen:place(self:at(DEPTH - 2, top, left), self:facing())
end

--- Removes everything the booth made.
function Booth:clear()
  for _, thing in ipairs(self.spawned) do
    if thing:exists() then
      thing:remove()
    end
  end
  self.spawned = {}
end

-- The booth's shell: floor, walls, lights, its title and its panel.
local function build_shell(booth)
  local def = booth.def
  local corner_a, corner_b = booth:cell(0, 0, 0), booth:cell(DEPTH - 1, 0, LENGTH - 1)
  for _, corner in ipairs({
    corner_a,
    corner_b,
    booth:cell(0, 0, LENGTH - 1),
    booth:cell(DEPTH - 1, 0, 0),
  }) do
    world:load_chunk(corner)
  end
  -- Empty the air above it (what a booth placed last time goes), then lay the floor.
  world:fill_blocks(booth:cell(0, 1, 0), booth:cell(DEPTH - 1, 9, LENGTH - 1), "minecraft:air")
  local colour = COLOURS[(booth.place - 1) % #COLOURS + 1]
  world:fill_blocks(corner_a, corner_b, "minecraft:" .. colour .. "_concrete")
  world:fill_blocks(booth:cell(0, 0, 0), booth:cell(0, 0, LENGTH - 1), "minecraft:smooth_quartz")
  -- The back wall, and low walls down the sides.
  world:fill_blocks(booth:cell(DEPTH - 1, 1, 0), booth:cell(DEPTH - 1, 5, LENGTH - 1), WALL)
  world:fill_blocks(booth:cell(1, 1, 0), booth:cell(DEPTH - 1, 1, 0), WALL)
  world:fill_blocks(booth:cell(1, 1, LENGTH - 1), booth:cell(DEPTH - 1, 1, LENGTH - 1), WALL)
  for _, spot in ipairs({ { 1, 1 }, { 1, LENGTH - 2 }, { DEPTH - 2, 1 }, { DEPTH - 2, LENGTH - 2 } }) do
    world:set_block(booth:cell(spot[1], 0, spot[2]), "minecraft:sea_lantern")
  end
  -- Its title over the entrance, and the panel on the back wall saying what the booth shows.
  booth:placard(
    0,
    4.6,
    6,
    ("<bold>%s</bold>"):format(def.title),
    { scale = 2, width = 300, alignment = "center" }
  )
  if def.summary then
    booth:placard(
      0,
      4.1,
      6,
      "<gray>" .. def.summary,
      { scale = 0.8, width = 360, alignment = "center" }
    )
  end
  if def.lines then
    booth:placard(DEPTH - 1.45, 2.2, 6, table.concat(def.lines, "\n"), { width = 340 })
  end
end

-- Builds a booth afresh. One that fails says so in the log and keeps what it got to; the rest of
-- the expo is built regardless. `booth.problem` keeps what went wrong, for the tests.
local function build(booth)
  booth:clear()
  local ok, problem = pcall(function()
    build_shell(booth)
    if booth.def.build then
      booth.def.build(booth)
    end
  end)
  booth.problem = not ok and tostring(problem) or nil
  if not ok then
    log("booth " .. booth.id .. " didn't build: " .. tostring(problem))
  end
end

--- Adds a booth (or, when its module is saved, builds it again). `def`: `id` (its place in
--- `expo.ORDER`), `title`, `summary` (one line under the title, and in the tour menu), `icon` (an
--- item kind for the tour menu), `lines` (the panel on the back wall) and `build(booth)`, which
--- puts its exhibits in.
---@param def table
---@return Booth
function expo.booth(def)
  local place = assert(place_of(def.id), "the expo has no place for booth " .. tostring(def.id))
  local booth = booths[def.id]
  if not booth then
    local row = (place - 1) // 2
    booth = setmetatable({
      id = def.id,
      place = place,
      west = place % 2 == 1,
      z0 = FIRST + row * ROW,
      spawned = {},
    }, Booth)
    booths[def.id] = booth
  end
  booth.def = def
  if ready then
    build(booth)
  end
  return booth
end

--- A booth by id, if its module has added it.
---@param id string
---@return Booth?
function expo.get(id)
  return booths[id]
end

--- Every booth added so far, in the avenue's order.
---@return Booth[]
function expo.all()
  local list = {}
  for _, id in ipairs(expo.ORDER) do
    if booths[id] then
      list[#list + 1] = booths[id]
    end
  end
  return list
end

--- The booth a world position is in, if any (only in the main world).
---@param location Location
---@return Booth?
function expo.booth_at(location)
  if location.world:name() ~= world:name() then
    return nil
  end
  local position = location.position
  local row = math.floor((position.z - FIRST) / ROW)
  if row < 0 then
    return nil
  end
  local side = position.x < 0 and 1 or 2
  local booth = booths[expo.ORDER[row * 2 + side] or ""]
  if booth and booth:contains(position) then
    return booth
  end
  return nil
end

-- ---- The hub and the avenue -----------------------------------------------------------------

local hub = { spawned = {} }

local AVENUE_END = FIRST + ((#expo.ORDER + 1) // 2) * ROW

local function build_hub()
  local last = AVENUE_END
  -- The avenue, with lights down both edges.
  world:fill_blocks(vec3(-AISLE, FLOOR, 8), vec3(AISLE, FLOOR, last), "minecraft:polished_andesite")
  world:fill_blocks(vec3(-1, FLOOR, 8), vec3(1, FLOOR, last), "minecraft:polished_diorite")
  for z = FIRST, last, 5 do
    world:set_block(vec3(-AISLE, FLOOR, z), "minecraft:sea_lantern")
    world:set_block(vec3(AISLE, FLOOR, z), "minecraft:sea_lantern")
  end
  -- The hub: a ring round spawn.
  world:fill_blocks(vec3(-10, FLOOR, -10), vec3(10, FLOOR, 8), "minecraft:smooth_stone")
  for angle = 0, 359, 6 do
    local r = math.rad(angle)
    world:set_block(
      vec3(math.floor(math.cos(r) * 7), FLOOR, math.floor(math.sin(r) * 7)),
      "minecraft:chiseled_stone_bricks"
    )
  end
  world:set_spawn_location(expo.SPAWN)

  for _, thing in ipairs(hub.spawned) do
    if thing:exists() then
      thing:remove()
    end
  end
  hub.spawned = {}
  for _, entity in ipairs(world:entities({ tag = TAG })) do
    entity:remove()
  end

  local function hub_placard(position, yaw, text, scale)
    local placard =
      nf.centities.spawn("placard", world:location(position, yaw, 0), { temporary = true })
    if placard then
      hub.spawned[#hub.spawned + 1] = placard
      local node = assert(placard:node("text"))
      node:set_display_text(text)
      node:set_display_text_style({ line_width = 320, alignment = "center" })
      node:set_scale(vec3(scale, scale, scale))
    end
  end
  -- Facing north, so whoever arrives looking down the avenue sees it over their shoulder... and
  -- one facing them, at the avenue's start.
  hub_placard(
    vec3(0.5, FLOOR + 4, 9.5),
    180,
    "<gold><bold>NetherForge Showcase</bold></gold>\n<gray>Walk down the avenue: every booth shows one part of the engine.",
    1.5
  )
  hub_placard(
    vec3(0.5, FLOOR + 2.2, 9.5),
    180,
    "<gray>Right-click your <gold>Tour guide</gold> compass for every booth and world,\nor type <white>/tour</white>. <white>/expo</white> brings you back here from anywhere.",
    0.8
  )

  -- The guide at the hub: a mannequin (a player-like figure that isn't a player) who opens the tour.
  local guide =
    world:spawn_entity("minecraft:mannequin", world:location(vec3(-3.5, FLOOR + 1, 4.5), 180, 0), {
      tags = { TAG },
      custom_name = "<gold>Guide",
    }) --[[@as Mannequin?]]
  if guide then
    guide:set_custom_name_visible(true)
    guide:set_description("<gray>Right-click for the tour")
    guide:set_immovable(true)
    guide:set_invulnerable(true)
    guide:on("interact", function(event)
      expo.open_tour(event.player)
    end)
  end
end

-- ---- Other worlds ---------------------------------------------------------------------------

--- The worlds the expo's portals and tour menu lead to, by name: each a world `netherforge.json`
--- makes, or the hub itself.
expo.DESTINATIONS = {
  hub = {
    title = "The hub",
    description = "Back to the start of the avenue.",
    icon = "minecraft:compass",
  },
  hills = {
    title = "The ruby hills",
    world = "hills",
    description = "A world made by terrain/ruby_hills.json: noise, layers, caves, ores, biomes (the project's ruby grove too), trees from a structure file, a datapack's boulders, ruined shrines that generate with a keeper inside, tortoises that spawn by themselves, and 448 blocks of height from dimension_types/deep.json.",
    icon = "minecraft:grass_block",
  },
  sky = {
    title = "The sky islands",
    world = "sky",
    description = "A world made by terrain/sky_islands.json and its script, terrain/sky_islands.lua, which hollows the ground away: islands float over nothing, under the purple sky of dimension_types/sky.json.",
    icon = "minecraft:amethyst_block",
  },
  lobby = {
    title = "The lobby",
    world = "lobby",
    description = "A world the project manages (netherforge.json's managedWorlds), with no monsters (its spawnLimits).",
    icon = "minecraft:oak_door",
  },
}

-- A place to stand in a world: the ground at the world's spawn, or near it.
local function landing(target)
  local spawn = target:spawn_location()
  local around = spawn and spawn.position or vec3(0, 100, 0)
  for radius = 0, 48, 8 do
    for _, offset in ipairs({
      vec3(0, 0, 0),
      vec3(radius, 0, 0),
      vec3(-radius, 0, 0),
      vec3(0, 0, radius),
      vec3(0, 0, -radius),
    }) do
      local at = around + offset
      target:load_chunk(at)
      local top = target:highest_block(at)
      if top and top:is_solid() then
        return target:location(top:position() + vec3(0.5, 1, 0.5), 0, 0)
      end
    end
  end
  -- Nothing solid near (the sky islands can be all air at spawn): a platform to stand on.
  local platform = vec3(math.floor(around.x), 120, math.floor(around.z))
  target:fill_blocks(platform - vec3(2, 0, 2), platform + vec3(2, 0, 2), "minecraft:calcite")
  return target:location(platform + vec3(0.5, 1, 0.5), 0, 0)
end

--- Takes a player to one of `expo.DESTINATIONS` by name, or to a booth by id.
---@param player Player
---@param destination string
---@return boolean
function expo.travel(player, destination)
  local booth = booths[destination]
  if booth then
    return player:teleport(booth:entrance())
  end
  local where = expo.DESTINATIONS[destination]
  if not where or not where.world then
    return player:teleport(expo.SPAWN)
  end
  local target = nf.worlds.get(where.world)
  if not target then
    player:send_message("<red>The " .. where.title .. " aren't on this server.")
    return false
  end
  local moved = player:teleport(landing(target))
  if moved then
    player:send_title(
      "<light_purple>" .. where.title,
      "<gray>Your tour guide brings you back",
      { stay = 50 }
    )
  end
  return moved
end

-- ---- Visitors -------------------------------------------------------------------------------

local inside = {} ---@type table<string, string|false> player id -> the booth they're in
local bars = {} ---@type table<string, BossBar>
local show_bar = nf.config("tour_bossbar")

-- netherforge.json's `expo`: whether the main world is the expo at all. A server whose main world
-- isn't the plaza (bukkit.yml doesn't hand it to NetherForge) turns it off, and the expo stays out
-- of its way: nothing built, no tour guide handed out, no boss bar or sidebar.
local active = nf.config("expo")
local open_expo, close_expo

nf.on("setting_changed", function(event)
  if event.setting == "expo" then
    active = event.value
    if active then
      open_expo()
    else
      close_expo()
    end
  elseif event.setting == "tour_bossbar" then
    show_bar = event.value
    for id, bar in pairs(bars) do
      bar:remove()
      bars[id] = nil
    end
  end
end)

--- The booths a player has been in, as a set of ids: kept in their saved table.
---@param player Player
---@return table<string, boolean>
function expo.seen(player)
  local data = player:data()
  data.expo = data.expo or {}
  data.expo.seen = data.expo.seen or {}
  return data.expo.seen
end

local function count_seen(player)
  local n = 0
  for _ in pairs(expo.seen(player)) do
    n = n + 1
  end
  return n
end

-- The boss bar and sidebar: where they are, and how much of the tour they've seen.
local function show_progress(player, booth)
  local seen, total = count_seen(player), #expo.ORDER
  local id = player:id()
  if show_bar then
    local bar = bars[id]
    if not bar or not bar:exists() then
      bar = nf.bossbars.create({ color = "yellow", style = "notched_10" })
      bars[id] = bar
      bar:show_to(player)
    end
    bar:set_text(booth and ("<gold>" .. booth.def.title) or "<gray>NetherForge Showcase")
    bar:set_progress(math.min(1, seen / total))
  end
  local lines = {
    booth and ("<white>" .. booth.def.title) or "<gray>On the avenue",
    "",
    ("<yellow>Booths seen: <white>%d/%d"):format(seen, total),
    "<gray>/tour  <dark_gray>every booth",
    "<gray>/expo  <dark_gray>back to the hub",
  }
  local sidebar = player:sidebar()
  sidebar:set_title("<gold><glyph:ui/coin> Showcase")
  sidebar:set_lines(lines)
end

--- A player stepped into a booth (or out, with `nil`).
---@param player Player
---@param booth Booth?
function expo.enter(player, booth)
  inside[player:id()] = booth and booth.id or false
  if booth then
    local seen = expo.seen(player)
    if not seen[booth.id] then
      seen[booth.id] = true
      -- Each booth is one criterion of advancements/tour.json; all of them complete it.
      player:grant_advancement("tour", booth.id)
    end
    player:send_actionbar(
      "<gold>"
        .. booth.def.title
        .. (booth.def.summary and (" <gray>· " .. booth.def.summary) or "")
    )
    nf.emit("expo:entered", { player = player, booth = booth.id })
  end
  show_progress(player, booth)
end

nf.on("player_move", function(event)
  if not active or nf.cutscenes.current(event.player) then
    return
  end
  local booth = expo.booth_at(event.to)
  local id = event.player:id()
  local was = inside[id]
  if (booth and booth.id or false) ~= was then
    expo.enter(event.player, booth)
  end
end)

nf.on("player_join", function(event)
  if not active then
    return
  end
  local player = event.player
  inside[player:id()] = false
  -- No teleport: the hub is the world's spawn (build_hub sets it), so someone new arrives there.
  local inventory = player:inventory()
  if inventory and not inventory:has_item({ item = "guide" }) then
    player:give_item(nf.items.create("guide"))
  end
  show_progress(player, nil)
end)

nf.on("player_quit", function(event)
  local id = event.player:id()
  inside[id] = nil
  if bars[id] then
    bars[id]:remove()
    bars[id] = nil
  end
end)

-- ---- The tour menu --------------------------------------------------------------------------

local HUB_SLOT, CLOSE_SLOT = 45, 53
local WORLD_SLOTS = { [47] = "hills", [48] = "sky", [50] = "lobby" }

-- A menu made by a script rather than a file: one template, a window of it per player.
local tour = nf.menus.create({ rows = 6, title = "<dark_gray>The tour: every booth" })

--- Opens the tour menu for a player: every booth (ticked when they've seen it), the worlds, and home.
---@param player Player
---@return Menu?
function expo.open_tour(player)
  local menu = player:open_menu(tour)
  if not menu then
    return nil
  end
  local seen = expo.seen(player)
  for index, id in ipairs(expo.ORDER) do
    local booth = booths[id]
    if booth then
      menu:set_item(index - 1, {
        kind = booth.def.icon or "minecraft:paper",
        name = (seen[id] and "<green>✔ " or "<white>") .. booth.def.title,
        lore = { "<gray>" .. (booth.def.summary or ""), "<dark_gray>Click to go there" },
        glint = seen[id] == true,
      })
    end
  end
  menu:set_item(
    HUB_SLOT,
    { kind = "minecraft:compass", name = "<gold>The hub", lore = { "<gray>Back to the start" } }
  )
  for slot, name in pairs(WORLD_SLOTS) do
    local where = expo.DESTINATIONS[name]
    menu:set_item(slot, {
      kind = where.icon,
      name = "<light_purple>" .. where.title,
      lore = { "<gray>Another world" },
    })
  end
  menu:set_item(CLOSE_SLOT, { kind = "minecraft:barrier", name = "<red>Close" })
  return menu
end

tour:on("click", function(event)
  if not event.in_menu or not event.index then
    return
  end
  local player = event.player
  local index = event.index
  if index == CLOSE_SLOT then
    player:close_menu()
    return
  end
  local destination = (index == HUB_SLOT and "hub") or WORLD_SLOTS[index] or expo.ORDER[index + 1]
  if destination and (booths[destination] or expo.DESTINATIONS[destination]) then
    player:close_menu()
    expo.travel(player, destination)
  end
end)

nf.commands.register("tour", {
  description = "Every booth of the expo, and the worlds beyond it",
  players_only = true,
}, function(event)
  expo.open_tour(assert(event.player))
end)

nf.commands.register("expo", {
  description = "Back to the expo's hub, or straight to a booth",
  players_only = true,
  arguments = {
    {
      name = "booth",
      type = "choice",
      choices = { "hub", table.unpack(expo.ORDER) },
      default = "hub",
    },
  },
}, function(event)
  expo.travel(assert(event.player), event.arguments.booth)
end)

-- ---- Building -------------------------------------------------------------------------------

-- The spawn chunks are loaded once the server is up; a second later is soon enough. A reload
-- (saving this file) builds everything again the same way. The work is a queue of steps (load
-- the avenue's chunks one at a time, then the hub, then each booth), each step waiting as many
-- ticks as the last one took 5 ms: all of it in one call runs past a call's time limit (loading
-- chunks on a new world is the slow part), and a step a tick is still over the slow-script limit.
-- `expo.building` is true until the last booth is up.
expo.building = true

local function run_steps(steps)
  local step = table.remove(steps, 1)
  if not step then
    expo.building = false
    return
  end
  local started = nf.server.unix_time()
  step()
  local took = nf.server.unix_time() - started
  nf.after(math.max(1, math.ceil(took / 5)), function()
    run_steps(steps)
  end)
end

-- Builds everything, a step at a time (see above).
function open_expo()
  if ready then
    return
  end
  ready = true
  local steps = {}
  for z = -16, AVENUE_END, 16 do
    steps[#steps + 1] = function()
      world:load_chunk(vec3(0, FLOOR, z))
    end
  end
  steps[#steps + 1] = build_hub
  for _, booth in ipairs(expo.all()) do
    steps[#steps + 1] = function()
      build(booth)
    end
  end
  run_steps(steps)
end

-- Takes away everything the expo spawned. The blocks it laid stay where they are.
function close_expo()
  ready = false
  for _, booth in ipairs(expo.all()) do
    booth:clear()
  end
  for _, thing in ipairs(hub.spawned) do
    if thing:exists() then
      thing:remove()
    end
  end
  hub.spawned = {}
  for _, entity in ipairs(world:entities({ tag = TAG })) do
    entity:remove()
  end
  for id, bar in pairs(bars) do
    bar:remove()
    bars[id] = nil
  end
  expo.building = false
end

nf.after(20, function()
  if active then
    open_expo()
  else
    expo.building = false
  end
end)

nf.on("unload", function()
  for _, entity in ipairs(world:entities({ tag = TAG })) do
    entity:remove()
  end
end)

return expo
