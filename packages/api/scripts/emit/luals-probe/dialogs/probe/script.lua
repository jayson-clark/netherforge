local this = this --[[@as Dialog]]

this:on("press", function(event)
  print(event.player:name(), event.key, event.values.x, event.target:key())
end)
this:on("close", function(event)
  print(event.player:name(), event.dialog:id())
end)
this:button("ok"):on("press", function(event)
  print(event.key, event.dialog:id())
end)
this:button("ok"):once("press", function(event)
  print(event.player:name())
end)
nf.task(function()
  local p = nf.players.get("Notch")
  if p == nil then
    return
  end
  local answer = this:ask(p)
  if answer then
    print(answer.key, answer.values.name)
  end
  local pressed = nf.wait_for(this:button("ok"), "press")
  print(pressed.key)
end)
