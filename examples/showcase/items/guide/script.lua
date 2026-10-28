local this = this --[[@as ProjectItem]]

-- The tour guide every visitor is handed: using it opens the expo's tour menu (modules/expo),
-- from any world, so nobody is ever lost in the hills or the sky.
local expo = require("expo")

this:on("use", function(event)
  event:cancel()
  expo.open_tour(event.player)
end)
