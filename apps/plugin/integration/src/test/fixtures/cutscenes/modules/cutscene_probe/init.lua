-- /probe plays the probe cutscene for whoever runs it, high above where they stand, and logs how it ends.
nf.commands.register(
  "probe",
  { description = "Play the probe cutscene", players_only = true },
  function(event)
    local player = assert(event.player)
    local here = player:location()
    local scene =
      assert(nf.cutscenes.play(player, "probe", { origin = here.position + vec3(0, 40, 0) }))
    scene:on("cue", function(cue)
      log("probe cue", cue.cue)
    end)
    scene:on("end", function(done)
      log("probe ended", done.reason, tostring(done.player:game_mode()))
    end)
    log("probe started", tostring(scene:length()))
  end
)

-- /probestop ends whatever the runner is watching.
nf.commands.register(
  "probestop",
  { description = "Stop the probe cutscene", players_only = true },
  function(event)
    log("probe stopped", tostring(nf.cutscenes.stop(assert(event.player))))
  end
)

-- /probewhere logs where the server has the runner, which while a cutscene plays is wherever its camera is.
nf.commands.register(
  "probewhere",
  { description = "Where the server has me", players_only = true },
  function(event)
    local at = assert(event.player):location().position
    log("probe at", ("%.1f"):format(at.x), ("%.1f"):format(at.y), ("%.1f"):format(at.z))
  end
)
