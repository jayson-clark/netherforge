local this = this --[[@as ProjectItem]]

-- The gem's one script. It runs on any server that runs a project depending
-- on the library, and hears what players do with any gem, whichever
-- project's menu or script made it.

this:on("use", function(event)
  event.player:send_message("<aqua>The gem hums.")
end)
