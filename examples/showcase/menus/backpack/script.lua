local this = this --[[@as Menu]]

-- A backpack: nine slots bound to a storage of the player's own (`player:storage`), saved with
-- their inventory in the same write, so what they put in is there after a restart and can't be
-- duplicated. The last slot only takes gems, by a slot rule. The window stays locked; bound slots
-- let items through by themselves.

local context = this:context() or {}
local player = context.player --[[@as Player?]]
local storage = player and player:storage("backpack", 9)
if storage then
  for index = 0, 8 do
    this:slot(index):bind(storage, index)
  end
  this:slot(8):set_rule({ items = { "ruby", "library:gem" }, max = 16 })
end
