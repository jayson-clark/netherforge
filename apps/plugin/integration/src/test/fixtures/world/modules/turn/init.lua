nf.commands.register("it-turn", function()
  for _, crate in ipairs(nf.centities.all({ kind = "crate" })) do
    crate:set_yaw(90)
    local x = crate:node("box"):world_direction(vec3(1, 0, 0))
    log(("turned %.0f %s"):format(crate:yaw(), tostring((x - vec3.south):length() < 1e-6)))
  end
end)
