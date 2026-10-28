-- Quests: the project's own advancements (advancements/*.json), a tree under
-- the "Adventurer" tab. Joining completes its root; each /treasure roll meets
-- one more of treasure_hunter's three criteria; /quests says how far you are,
-- and so does the HUD: the ui pack's `hud` has a bar of three segments and a
-- line of text above it, which show each player's hunt.

-- The criteria treasure_hunter's file names, in the order rolls meet them.
local TREASURE_STEPS = { "first", "second", "third" }

-- What the HUD shows is the server's to remember, not saved: it's set again on joining.
local function show_hunt(player)
  local progress = player:advancement_progress("treasure_hunter")
  if not progress then
    return
  end
  local done, all = #progress.done, #progress.done + #progress.remaining
  local hud = player:hud()
  if done == all then
    hud:remove("ui/treasure")
    hud:remove("ui/treasure_label")
  else
    hud:fill_bar("ui/treasure", done, all)
    hud:set_text("ui/treasure_label", "<yellow>Treasure")
  end
end

nf.on("player_join", function(event)
  -- A bare id is one of the project's advancements: showcase:adventurer on the server.
  event.player:grant_advancement("adventurer")
  show_hunt(event.player)
end)

nf.on("player_complete_advancement", function(event)
  if event.advancement == "showcase:treasure_hunter" then
    local player = assert(event.player) -- players_only: never the console
    player:send_message("<gold>Treasure Hunter! Here's a diamond for the road.")
    player:inventory():add_item({ kind = "minecraft:diamond" })
    -- The diamonds advancement completes on finding one or being given one.
    player:grant_advancement("diamonds", "given")
  end
end)

-- Rolling for treasure (the rewards module's /treasure) meets the next step.
nf.on("rewards:rolled", function(event)
  local player = event.player
  local progress = player:advancement_progress("treasure_hunter")
  if not progress then
    return
  end
  for _, step in ipairs(TREASURE_STEPS) do
    local met = false
    for _, done in ipairs(progress.done) do
      met = met or done == step
    end
    if not met then
      player:grant_advancement("treasure_hunter", step)
      show_hunt(player)
      return
    end
  end
end)

nf.commands.register("quests", {
  description = "How far you are with the server's quests",
  players_only = true,
}, function(event)
  local player = assert(event.player) -- players_only: never the console
  local hunt = assert(player:advancement_progress("treasure_hunter"))
  player:send_message(
    ("<gold>Quests</gold>\n%s Adventurer\n<yellow>Treasure Hunter: %d of %d rolls"):format(
      player:has_advancement("adventurer") and "<green>✔" or "<gray>✘",
      #hunt.done,
      #hunt.done + #hunt.remaining
    )
  )
end)
