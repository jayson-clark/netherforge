local this = this --[[@as ProjectItem]]

-- The charm's one script: it runs once for the item, not once per stack, and
-- hears what players do with any charm, wherever they got it.
this:on("use", function(event)
  event.player:send_actionbar("<green>You feel lucky.")
end)
