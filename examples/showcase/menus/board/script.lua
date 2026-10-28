local this = this --[[@as Menu]]

-- A shared menu (menu.json's `shared`): one window for the whole server, so every player who
-- opens it sees the same nine lamps, and a lamp one player lights is lit for everyone looking.
-- Its script runs once, as the project loads, not once per player.

local OFF = { kind = "minecraft:gray_stained_glass_pane", name = "<gray>Click to light" }
local ON = { kind = "minecraft:glowstone", name = "<yellow>Lit" }

for index = 0, 8 do
  this:set_item(index, OFF)
end

this:on("click", function(event)
  if not event.in_menu then
    return
  end
  local lit = this:item(event.index)
  local on = lit ~= nil and lit.kind == ON.kind
  this:set_item(event.index, on and OFF or ON)
  event.player:play_sound("minecraft:ui.button.click")
end)
