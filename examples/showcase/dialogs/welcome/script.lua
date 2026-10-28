local this = this --[[@as Dialog]]

-- The Done button, with every input's answer in event.values.
this:button("done"):on("press", function(event)
  -- A text box's answer is a string (values holds numbers too, for sliders).
  local nickname = tostring(event.values.nickname or "")
  if nickname == "" then
    nickname = event.player:name()
  end
  -- Kept with the greeter's part of their saved table, for the shop to use.
  local data = event.player:data()
  data.greeter = data.greeter or {}
  data.greeter.nickname = nickname
  -- Welcomed players may build towers. The grant is kept, and given back
  -- every time they join.
  event.player:set_permission("showcase.builder", true)
  -- What they typed is escaped, so a nickname can't smuggle in MiniMessage tags.
  event.player:send_message("<green>Nice to meet you, " .. nf.text.escape(nickname) .. "!")
end)
