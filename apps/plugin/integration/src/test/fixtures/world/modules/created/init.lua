nf.commands.register("it-created", function()
  local template = nf.menus.create({
    type = "hopper",
    title = "Pick",
    slots = { [2] = { kind = "minecraft:emerald" } },
  })
  local dialog = nf.dialogs.create({
    type = "multi_action",
    title = "Made in Lua",
    inputs = { { type = "text", key = "name", max_length = 8 } },
    buttons = { { key = "ok" }, { key = "cancel" } },
  })
  local effect =
    nf.particles.play("sparkle", nf.worlds.default():location(vec3(200, -60, 200)), { loop = true })
  log(
    "created",
    template:size(),
    template:item(2).kind,
    dialog:exists(),
    table.concat(dialog:buttons(), ","),
    effect:kind()
  )
  nf.after(5, function()
    log("effect after", tostring(effect:is_active()), tostring(effect:stop()))
  end)
end)
