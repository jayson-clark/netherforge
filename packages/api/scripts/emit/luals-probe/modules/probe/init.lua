local helper = require("probe.helper")
print(helper.double(2) + 1)

nf.on("tick", function(event)
  print(event.tick)
end, { every = 20 })
nf.on("player_join", function(event)
  print(event.player:name(), event.first_join)
  event.message = nil
end)
nf.on("player_quit", function(event)
  print(event.player:name())
end)
nf.on("player_chat", function(event)
  print(event.message:len())
end)
nf.on("player_interact", function(event)
  if event.block then
    print(event.block:kind())
  end
  print(event.click)
end)
nf.on("block_break", function(event)
  print(event.block:position().x, event.state, event.player:name())
end)
nf.once("block_place", function(event)
  print(event.state)
end)
nf.on("centity_click", function(event)
  print(event.player:name(), event.target:centity():id())
end)
nf.on("menu_open", function(event)
  print(event.menu:id())
end)
nf.on("menu_click", function(event)
  print(event.in_menu)
end)
nf.on("dialog_press", function(event)
  print(event.key)
end)
nf.on("mymod:thing", function(event)
  print(event.name)
end)

-- Tasks.
local task = nf.task(function(who)
  nf.wait(20)
  nf.wait_until(function()
    return true
  end, 100)
  local ev = nf.wait_for(nf, "player_join")
  print(ev.name, who)
end, "someone")
print(task:is_active())
task:cancel()
nf.after(20, function()
  print("later")
end):cancel()
nf.every(20, function()
  print("again")
end)

-- Commands.
nf.commands.register("probe", {
  description = "Probe",
  permission = "probe.use",
  aliases = { "pr" },
  arguments = {
    { name = "who", type = "player" },
    { name = "mode", type = "choice", choices = { "a", "b" }, default = "a" },
  },
  subcommands = {
    add = {
      arguments = { { name = "amount", type = "integer", min = 1 } },
      handler = function(event)
        print(event.arguments.amount, event.sender:name())
      end,
    },
  },
}, function(event)
  local who = event.arguments.who
  event.sender:send_message("hi " .. event.label .. event.input)
  if event.player then
    event.player:send_message("you")
  end
  print(who)
end)

-- Saved data.
local shop = nf.data("shop")
shop.sales = (shop.sales or 0) + 1
local p = nf.players.get("Notch")
if p then
  local pd = p:data()
  pd.coins = 3
  p:open_menu("shop", { context = { a = 1 } })
  p:open_dialog("welcome")
  p:spawn_particle("minecraft:flame", vec3(0, 64, 0), { count = 5, spread = vec3(1, 1, 1) })
  p:play_sound("minecraft:block.note_block.pling", { volume = 0.5, category = "ui" })
end

-- World and Block.
local world = nf.worlds.default()
local block = world:block(vec3(0, 64, 0))
if block then
  print(block:kind(), block:state(), block:property("facing"), block:is_air())
  block:set_state("minecraft:stone")
  block:relative("up"):set_state("minecraft:air", { update = false })
  local bd = block:data()
  if bd then
    bd.owner = "x"
  end
end
world:set_block(vec3(1, 64, 1), "minecraft:dirt")
world:fill_blocks(vec3(0, 0, 0), vec3(2, 2, 2), "minecraft:glass")
world:spawn_particle("minecraft:dust", vec3(0, 65, 0), { color = "#ff0000", size = 2 })
world:play_sound("minecraft:entity.generic.explode", vec3(0, 65, 0))
world:explode(vec3(0, 65, 0), 4, { fire = false, break_blocks = false })
world:set_weather("rain", { ticks = 200 })
print(vec3.zero:lerp(vec3.one, 0.5).x)
world:on("block_break", function(event)
  print(event.state)
end)
world:once("block_place", function(event)
  print(event.block:kind())
end)
local hit = world:raycast(vec3(0, 70, 0), vec3.down, 20, { fluids = true })
if hit then
  print(hit.position.y, hit.distance, hit.block and hit.block:kind())
end
local here = world:location(vec3(0, 64, 0), 90, 0)
print(here.position.x, here.yaw)
local spawned = nf.centities.spawn("tower", here)
if spawned then
  spawned:on("click", function(event)
    print(event.player:name())
  end)
end
for _, c in ipairs(nf.centities.all({ kind = "tower", near = vec3.zero, radius = 10 })) do
  print(c:id())
end

-- Files and JSON.
local file = nf.files.get("notes.txt")
if file then
  file:write(nf.json.encode({ a = 1 }))
  local text = file:read()
  if text then
    print(nf.json.decode(text))
  end
  for line in file:lines() do
    print(line)
  end
  for _, child in ipairs(file:children()) do
    print(child:name())
  end
end
print(nf.text.escape("<b>"), nf.text.strip("<b>x</b>"), nf.server.tick())
log("done", 1, true)

-- Menus and dialogs from a module.
local shared = nf.menus.shared("shop")
if shared then
  shared:on("click", function(event)
    print(event.index)
  end)
end
local dlg = nf.dialogs.get("welcome")
dlg:on("press", function(event)
  print(event.values)
end)
