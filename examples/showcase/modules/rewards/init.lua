-- Rewards: the treasure loot table (loot/treasure.json), rolled for a player.
-- /treasure gives them a roll; /treasure chest fills the chest they stand on.

-- How many rolls, as the server's owner set it (netherforge.json's settings).
-- Read once, here: this module doesn't listen for setting_changed, so a change
-- restarts it, and /treasure is registered again with the new number.
local ROLLS = nf.config("treasure_rolls")

nf.commands.register("treasure", {
  description = "Roll the treasure loot table",
  players_only = true,
  arguments = {
    { name = "where", type = "choice", choices = { "hand", "chest" }, default = "hand" },
  },
}, function(event)
  local player = assert(event.player) -- players_only: never the console
  -- What they hold counts for the table's tool and enchantment conditions.
  local context = { player = player, tool = player:equipment("main_hand") }
  if event.arguments.where == "chest" then
    local location = assert(player:location())
    local below = location.world:block(location.position - vec3(0, 1, 0))
    local chest = below and below:inventory()
    if not chest then
      player:send_message("<red>Stand on a chest first.")
      return
    end
    local left = nf.loot.fill("treasure", chest, context) or 0
    player:send_message(
      ("<gold>The chest is filled%s."):format(left > 0 and (", " .. left .. " didn't fit") or "")
    )
    return
  end
  local items = {}
  for _ = 1, ROLLS do
    for _, item in ipairs(nf.loot.roll("treasure", context)) do
      items[#items + 1] = item
      player:inventory():add_item(item)
    end
  end
  player:send_message(("<gold>You found %d stacks of treasure."):format(#items))
  -- For whoever counts rolls: the quests module's Treasure Hunter.
  nf.emit("rewards:rolled", { player = player })
end)
