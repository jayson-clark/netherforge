-- Owners' rooms, as Kosmoi's agent opens them through the control endpoint: each a kept room keyed by its owner
-- (player_alice, player_bob). Its world "home" is made the first time and marked with a block that says whose it is;
-- its nf.data counts how often it opened, and a placard stands in it saying whose it is (its centity:data()). Brought back
-- on another server (here, the same one after its files went), the world, the count and the placard are as they were:
-- the same instance, its table too. The main room sends players into rooms by key (`/it-owner send <key> <name>`).

local here = nf.rooms.current()

local function by_key(key)
  for _, room in ipairs(nf.rooms.all()) do
    if room:key() == key then
      return room
    end
  end
end

if here:is_main() then
  nf.commands.register("it-owner", {
    arguments = { { name = "key", type = "word" }, { name = "player", type = "word" } },
  }, function(event)
    local room = by_key(event.arguments.key)
    local player = nf.players.get(event.arguments.player)
    log(
      "send",
      event.arguments.key,
      event.arguments.player,
      tostring(room ~= nil and room:send(player))
    )
  end)
  return
end

local key = here:key()
local mark = key == "player_alice" and "minecraft:gold_block" or "minecraft:diamond_block"
local at = vec3(1, 64, 1)
local home = nf.worlds.get("home")
local made = home == nil
if made then
  home = nf.worlds.create("home", { generator = "flat", structures = false })
end
home:hold_chunk(at)
if made then
  home:set_block(at, mark)
  -- What the world keeps of itself beside its blocks: game rules, its clock, its border, its weather, its spawn.
  home:set_game_rule("keep_inventory", true)
  home:set_game_rule("advance_time", false)
  home:set_time_of_day(18000)
  local border = assert(home:border())
  border:set_center(vec3(8, 0, 8))
  border:set_size(123)
  home:set_weather("rain", { ticks = 1000000 })
  home:set_spawn_location(vec3(5, 70, 6))
end
local notes = nf.data("notes")
notes.opened = (notes.opened or 0) + 1
local placards = nf.centities.all({ kind = "placard", world = home })
if made then
  local placard = assert(nf.centities.spawn("placard", home:location(vec3(3, 64, 3))))
  placard:data().owner = key
  placards = { placard }
end
for _, placard in ipairs(placards) do
  log("placard", key, tostring(#placards), placard:data().owner)
end
log(
  "owner",
  key,
  home:name(),
  tostring(made),
  home:block(at):kind(),
  tostring(notes.opened),
  tostring(here:is_kept())
)

local border = assert(home:border())
local spawn = assert(home:spawn_location()).position
log(
  "kept",
  key,
  tostring(home:game_rule("keep_inventory")),
  tostring(home:game_rule("advance_time")),
  tostring(home:time_of_day()),
  tostring(border:size()),
  tostring(border:center().x) .. "," .. tostring(border:center().z),
  tostring(home:weather()),
  tostring(spawn.x) .. "," .. tostring(spawn.y) .. "," .. tostring(spawn.z)
)

nf.on("player_join", function(event)
  event.player:teleport(home:spawn_location())
  log("home", key, event.player:name())
end)

nf.on("room_close", function()
  log("closing", key)
end)
