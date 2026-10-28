-- What the advancement scenario asks of the project's advancements: whether a
-- player has each, and granting one of the library's (a package's, by ns:id).

local NAMES =
  { "adventurer", "treasure_hunter", "diamonds", "library:gem_collector", "gem_hoarder" }

nf.commands.register("advs", {
  description = "Which of the project's advancements you have",
  players_only = true,
}, function(event)
  local player = assert(event.player) -- players_only: never the console
  local parts = { "advs" }
  for _, name in ipairs(NAMES) do
    parts[#parts + 1] = name .. "=" .. tostring(player:has_advancement(name))
  end
  player:send_message(table.concat(parts, " "))
end)

nf.commands.register("gems", {
  description = "Completes the library's tab and the project's advancement under it",
  players_only = true,
}, function(event)
  local player = assert(event.player) -- players_only: never the console
  local collector = player:grant_advancement("library:gem_collector")
  local hoarder = player:grant_advancement("gem_hoarder", "hoarded")
  player:send_message(("gems %s %s"):format(tostring(collector), tostring(hoarder)))
end)
