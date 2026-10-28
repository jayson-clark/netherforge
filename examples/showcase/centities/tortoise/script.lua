local this = this --[[@as Centity]]

-- A tortoise: it appears by itself in the hills world's ruby grove (centity.json's `spawning`),
-- ambles about with `move_to`, and a player who feeds it sweet berries makes a friend of it. A fed
-- tortoise is kept: a natural one would otherwise despawn when everyone leaves.

local FOOD = "minecraft:sweet_berries"

local data = assert(this:data())
local label = assert(this:node("label"))

local function show_friend()
  label:set_display_text(
    data.friend and ("<green>%s's tortoise"):format(data.friend)
      or "<green>Tortoise\n<gray>likes sweet berries"
  )
end
show_friend()

-- Every five seconds or so, perhaps a short walk: a few blocks off, slowly, legs going.
this:on("tick", function()
  if this:has_path() or math.random() > 0.35 then
    return
  end
  local here = this:position()
  if
    here and this:move_to(here + vec3(math.random(-6, 6), 0, math.random(-6, 6)), { speed = 0.8 })
  then
    this:play_animation("walk")
  end
end, { every = 100 })

this:on("path_end", function()
  this:stop_animation("walk")
end)

-- A right click holds out what's in your hand, as a custom event of its own, so a test can feed
-- one without clicking.
this:on("click", function(event)
  if event.click == "right" then
    this:emit("tortoise:feed", { player = event.player })
  end
end)

this:on("tortoise:feed", function(event)
  local player = event.player --[[@as Player]]
  local held = player:held_item()
  if not held or held.kind ~= FOOD then
    player:send_actionbar("<gray>The tortoise sniffs your hand. <green>It likes sweet berries.")
    return
  end
  held.count = held.count - 1
  player:set_held_item(held.count > 0 and held or nil)
  if this:is_natural() then
    this:keep()
  end
  data.friend = player:name()
  this:play_animation("munch")
  show_friend()
end)
