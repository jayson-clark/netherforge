-- The wand is a `hold` item: a right click lasts until the player lets go,
-- with the arm raised as a trident's is, and they keep running while they
-- charge it. Letting go
-- is player_stop_using_item, and event.ticks is how long they held it.
nf.on("player_stop_using_item", function(event)
  if event.item.item ~= "ruby_wand" then
    return
  end
  local charge = math.min(event.ticks / 40, 1)
  event.player:send_actionbar(string.format("<red>Cast at %d%%", math.floor(charge * 100)))
end)
