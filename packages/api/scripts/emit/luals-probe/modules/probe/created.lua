-- What scripts make: particle effects, menu templates, dialogs from tables; and text widths.
local world = nf.worlds.default()
local here = world:location(vec3(0, 64, 0), 90, 0)

-- Particle effects.
local someone = nf.players.online()[1]
local effect = nf.particles.play("sparkle", here, {
  scale = 2,
  loop = true,
  viewers = someone and { someone } or nil,
})
if effect then
  print(effect:kind(), effect:is_active())
  local at = effect:location()
  if at then
    print(at.position.x, at.yaw)
  end
  effect:teleport(vec3(1, 64, 1))
  effect:teleport(here)
  if someone then
    effect:follow(someone, vec3(0, 2, 0))
    nf.particles.play("halo", vec3.zero, { follow = someone, offset = vec3.up })
  end
  local zombie = world:spawn_entity("minecraft:zombie", vec3(0, 64, 0))
  if zombie then
    effect:follow(zombie)
  end
  local tower = nf.centities.spawn("tower", here)
  local top = tower and tower:node("top")
  if tower and top then
    effect:follow(tower)
    effect:follow(top)
  end
  effect:on("end", function(event)
    print(event.effect:kind(), event.reason)
  end)
  effect:once("end", function(event)
    print(event.reason == "target_gone")
  end)
  -- Waiting on an effect.
  nf.task(function()
    local ended = nf.wait_for(effect, "end")
    print(ended.reason)
  end)
  effect:stop()
end

-- Cutscenes.
if someone then
  local scene = nf.cutscenes.play(someone, "intro", { origin = here, skippable = true })
  nf.cutscenes.play(someone, "intro", { origin = vec3(0, 64, 0) })
  if scene then
    print(scene:kind(), scene:length(), scene:is_active(), scene:time())
    local watcher = scene:player()
    if watcher then
      print(watcher:name())
    end
    scene:on("cue", function(event)
      print(event.cue, event.player:name(), event.cutscene:kind())
    end)
    scene:once("end", function(event)
      print(event.reason == "player_left", event.cutscene:kind())
    end)
    nf.task(function()
      local ended = nf.wait_for(scene, "end")
      print(ended.reason)
    end)
    scene:stop()
  end
  local current = nf.cutscenes.current(someone)
  if current then
    print(current:kind())
  end
  print(nf.cutscenes.stop(someone))
end

-- Menu templates.
local template = nf.menus.create({
  rows = 1,
  title = "<red>Are you sure?",
  slots = {
    [3] = { kind = "minecraft:lime_wool", name = "<green>Yes" },
  },
})
print(template:id(), template:exists(), template:size(), template:rows(), template:is_locked())
print(template:title(), template:skin(), #template:windows())
local yes = template:item(3)
if yes then
  print(yes.kind)
end
template:on("click", function(event)
  print(event.menu:id(), event.index)
  event:uncancel()
end)
template:on("drag", function(event)
  print(#event.indices)
end)
template:on("open", function(event)
  print(event.player:name())
end)
if someone then
  local window = someone:open_menu(template)
  if window then
    print(window:template() == template)
  end
  someone:open_menu("shop")
end

-- Dialogs from tables.
local rename = nf.dialogs.create({
  type = "confirmation",
  title = "Rename your pet",
  inputs = { { type = "text", key = "name", label = "Name", max_length = 16 } },
  buttons = { { key = "ok", label = "Rename" }, { key = "cancel", label = "Cancel" } },
})
rename:button("ok"):on("press", function(event)
  print(event.values.name)
end)
if someone then
  someone:open_dialog(rename)
  someone:open_dialog("welcome")
end

-- Text widths.
local width = nf.text.width("<bold>Shop")
if width then
  print(width + 1)
end

-- Waiting on a template.
nf.task(function()
  local clicked = nf.wait_for(template, "click", function(event)
    return event.index == 3
  end)
  print(clicked.index)
end)

template:remove()
