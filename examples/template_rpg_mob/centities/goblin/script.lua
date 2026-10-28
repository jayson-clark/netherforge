local this = this --[[@as Centity]]

-- The goblin's script: one copy runs for every goblin, so `this` is that goblin.
-- Click it to hurt it; when its health is gone it drops loot and is removed.
--
-- A click is turned into a custom event of the goblin's own ("goblin:hit").
-- Everything else listens for that event, so tests (and other scripts) can
-- hit a goblin without clicking one.

local MAX_HEALTH = 10
local DAMAGE_PER_HIT = 3

-- The goblin's own saved table: its health survives a restart or a reload.
local data = assert(this:data())
data.health = data.health or MAX_HEALTH

local label = assert(this:node("label"))

local function show_health()
  label:set_display_text(("<green>Goblin <red>%d/%d"):format(math.max(data.health, 0), MAX_HEALTH))
end
show_health()

-- A left click is an attack (a right click does nothing).
this:on("click", function(event)
  if event.click == "left" then
    this:emit("goblin:hit", { player = event.player, damage = DAMAGE_PER_HIT })
  end
end)

this:on("goblin:hit", function(event)
  data.health = data.health - event.damage
  if data.health > 0 then
    this:play_animation("hurt")
    show_health()
    return
  end

  -- Dead: what it drops goes straight to whoever killed it (anything that
  -- doesn't fit falls at their feet). nf.loot.roll picks from loot/goblin_loot.json.
  local player = event.player
  for _, item in ipairs(nf.loot.roll("goblin_loot", { player = player })) do
    player:give_item(item)
  end
  -- Let the rest of the project know: modules/slayers counts kills.
  nf.emit("goblin:slain", { player = player })
  this:remove()
end)
