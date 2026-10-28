-- /flyby: a camera swings over the area around you (cutscenes/flyby.json), then
-- puts you back where you were. The cutscene's coordinates are measured from
-- where you stand, so it works anywhere; it's skippable, so sneaking ends it early.

nf.commands.register("flyby", {
  description = "Watch a camera swing over the area around you",
  players_only = true,
}, function(event)
  local player = assert(event.player) -- players_only: never the console
  local scene = nf.cutscenes.play(player, "flyby", { origin = player:location().position })
  if not scene then
    return
  end

  -- A cue with an event is how the file tells a script a moment came.
  scene:on("cue", function(cue)
    if cue.cue == "turn" then
      cue.player:play_sound("minecraft:block.note_block.chime")
    end
  end)

  -- By now the player has been put back: game mode, position and all.
  scene:on("end", function(done)
    if done.reason == "skipped" then
      done.player:send_message("<gray>Skipped.")
    else
      done.player:send_message("<green>That was the village.")
    end
  end)
end)
