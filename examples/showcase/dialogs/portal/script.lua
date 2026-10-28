local this = this --[[@as Dialog]]

-- A confirmation dialog: two buttons, yes and no. Which world the portal leads to comes from
-- whoever opened it, as the opening's `context` (modules/expo's portals), handed back on every press.
local expo = require("expo")

this:button("go"):on("press", function(event)
  expo.travel(event.player, event.context)
end)

this:button("stay"):on("press", function(event)
  event.player:send_actionbar("<gray>Maybe later.")
end)
