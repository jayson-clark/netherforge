-- Steps the blocks scenario runs from the console, each logging what it found.
local steps = {}

local function world()
  return nf.worlds.default()
end

-- The air block above the ground at (x, z).
local function spot(x, z)
  world():load_chunk(vec3(x, 0, z))
  return world():highest_block(vec3(x, 0, z)):relative("up")
end

-- The top block at (x, z): what's been placed there, or the ground.
local function top(x, z)
  world():load_chunk(vec3(x, 0, z))
  return world():highest_block(vec3(x, 0, z))
end

-- Every ruby ore broken by a player says what its loot table rolled for the tool, and the example's own script has had its say.
nf.blocks.get("ruby_ore"):on("break", function(event)
  local drops = event.drops
  -- The block's own script hears it after this module's handler, so what it made of them is read a tick later.
  nf.after(1, function()
    local first = drops[1]
    log("broke", #drops, first and first.item or "none", first and first.count or 0)
  end)
end)

function steps.place_ore(x, z)
  local block = spot(x, z)
  local placed = nf.blocks.get("ruby_ore"):place(block:location())
  placed:data().owner = "it"
  log("placed", placed:id(), block:kind())
end

function steps.describe(x, z)
  local block = top(x, z)
  local custom = block:custom()
  local data = block:data()
  log("describe", custom and custom:id() or "none", block:kind(), data and data.owner or "-")
end

-- How high the top block at (x, z) is, and what it is.
function steps.where(x, z)
  local block = top(x, z)
  log("where", math.floor(block:position().y), block:kind())
end

function steps.give()
  local player = nf.players.get("Tester")
  player:give_item(nf.items.create("ruby_ore", { count = 2 }))
  log("gave", nf.items.id(player:inventory():item(0)) or "nothing")
end

function steps.held()
  local item = nf.players.get("Tester"):inventory():item(0)
  log("held", item and nf.items.id(item) or "nothing", item and item.count or 0)
end

function steps.note(x, z)
  spot(x, z):set_state("minecraft:note_block")
  log("note", top(x, z):property("note"))
end

function steps.note_state(x, z)
  log("note_state", top(x, z):property("note"), top(x, z):property("instrument"))
end

function steps.lamp(x, z)
  local placed = nf.blocks.get("floating_lamp"):place(spot(x, z):location())
  log("lamp", placed:id(), #nf.centities.all({ kind = "lamp" }))
end

function steps.lamp_break(x, z)
  top(x, z):break_naturally()
  log("lamp gone", #nf.centities.all({ kind = "lamp" }), top(x, z):kind())
end

nf.commands.register("it-blocks", {
  arguments = {
    { name = "step", type = "word" },
    { name = "x", type = "integer" },
    { name = "z", type = "integer" },
  },
}, function(event)
  steps[event.arguments.step](event.arguments.x, event.arguments.z)
end)
