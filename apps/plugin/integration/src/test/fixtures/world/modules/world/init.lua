nf.commands.register("it-world", function()
  local world = nf.worlds.default()
  local spot = vec3(200, -62, 200)
  local set = world:set_block(spot, "minecraft:gold_block")
  local block = world:block(spot)
  local down = world:raycast(vec3(200.5, -50, 200.5), vec3.down, 30)
  local box = nf.centities.spawn("it_box", vec3(205, -62, 200))
  local across = world:raycast(vec3(201.5, -61.5, 200.5), vec3(1, 0, 0), 10)
  local particles = world:spawn_particle(
    "minecraft:dust",
    spot + vec3(0.5, 1.5, 0.5),
    { count = 5, color = "#ff8800" }
  ) and world:spawn_particle("minecraft:block", spot, { block_state = "minecraft:stone" })
  local sound = world:play_sound("minecraft:block.note_block.pling", spot, { pitch = 1.5 })
  world:set_time_of_day(6000)
  world:set_weather("rain", { ticks = 200 })
  log(
    "world",
    set,
    block:kind(),
    world:highest_block(spot + vec3(0, 50, 0)) == block,
    down and down.block == block,
    down and tostring(down.position),
    across and across.centity == box,
    across and tostring(across.normal),
    particles,
    sound,
    world:time_of_day(),
    world:weather()
  )
  world:set_weather("clear")
  box:remove()
end)
