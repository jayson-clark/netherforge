local this = this --[[@as ProjectItem]]

-- Starlight candy is edible (item.json's `food`, on an item that isn't food in the game):
-- eating it lets you see in the dark for a minute, with the pack's own chime.
this:on("consume", function(event)
  event.player:add_effect("minecraft:night_vision", 60 * 20, { ambient = true })
  event.player:play_sound("ui/chime")
end)
