nf.commands.register("it-look", function()
  for _, crate in ipairs(nf.centities.all({ kind = "crate" })) do
    local box = crate:node("box")
    log(
      "look",
      box:set_glow_color("#ff8800"),
      box:set_glowing(true),
      box:set_brightness({ block_light = 15, sky_light = 15 }),
      box:set_billboard("vertical"),
      box:set_interpolation_ticks(3),
      crate:set_view_range(128)
    )
  end
end)
