-- Milestones: Minecraft's own advancements, celebrated and rewarded.
-- Finding diamonds makes a player a VIP (the showcase.vip permission, which
-- netherforge.json's allow.permissions lets the project grant), and
-- /milestones shows how far they are.

-- The advancements /milestones lists, with what it calls them.
local MILESTONES = {
  { key = "minecraft:story/mine_stone", name = "Stone Age" },
  { key = "minecraft:story/smelt_iron", name = "Acquire Hardware" },
  { key = "minecraft:story/mine_diamond", name = "Diamonds!" },
  { key = "minecraft:nether/root", name = "Nether" },
}

nf.on("player_complete_advancement", function(event)
  if event.advancement ~= "minecraft:story/mine_diamond" then
    return
  end
  local player = assert(event.player) -- players_only: never the console
  -- Our own announcement, in place of the game's.
  event.message = "<aqua>" .. player:name() .. " struck diamonds and is now a VIP!"
  player:set_permission("showcase.vip", true)
end)

nf.commands.register("milestones", {
  description = "How far you are with the server's milestones",
  players_only = true,
}, function(event)
  local player = assert(event.player) -- players_only: never the console
  local lines = { "<gold>Milestones" }
  for _, milestone in ipairs(MILESTONES) do
    local done = player:has_advancement(milestone.key)
    lines[#lines + 1] = (done and "<green>✔ " or "<gray>✘ ") .. milestone.name
  end
  -- An advancement with many parts: how many biomes they've been to.
  local biomes = player:advancement_progress("minecraft:adventure/adventuring_time")
  if biomes then
    local seen, total = #biomes.done, #biomes.done + #biomes.remaining
    lines[#lines + 1] = ("<yellow>Biomes visited: %d of %d"):format(seen, total)
  end
  -- What the project granted, not has_permission: a node nothing declares
  -- belongs to operators on Paper, so every op would "have" it.
  if player:permissions()["showcase.vip"] then
    lines[#lines + 1] = "<aqua>You're a VIP."
  end
  player:send_message(table.concat(lines, "\n"))
end)
