local this = this --[[@as Menu]]

this:on("open", function(event)
  print(event.player:name(), event.menu:id(), event.context)
end)
this:on("close", function(event)
  print(event.player:name())
end)
this:on("click", function(event)
  print(event.player:name(), event.in_menu, event.index, event.click == "shift_left")
  if event.target then
    print(event.target:index())
  end
  if event.item then
    print(event.item.kind, event.item.count)
  end
end)
this:on("drag", function(event)
  print(#event.indices, event.cursor_item)
end)
this:slot(4):on("click", function(event)
  print(event.player:name(), event.in_menu)
end)
this:slot(4):once("click", function(event)
  event:cancel()
end)
this:set_item(0, { kind = "minecraft:diamond", count = 2, name = "<aqua>Gem", lore = { "shiny" } })
this:slot(1):set_item({ kind = "stone", data = { coin = true } })
