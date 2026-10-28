local this = this --[[@as ProjectItem]]

-- The ruby's one script. It runs once for the item, not once per stack, and
-- hears what players do with any ruby, wherever it came from: the shop's
-- slot, a recipe, or nf.items.create("ruby") in another script.

-- Right-clicking with a ruby in hand.
this:on("use", function(event)
  event.player:send_message("<red>The ruby glows in your hand.")
end)

-- Picking one up. A stack's own data (event.item.data) is the stack's alone:
-- a ruby found in the wild could carry where it was found.
this:on("pickup", function(event)
  event.player:send_actionbar("<red>+" .. event.item.count .. " ruby")
end)
