-- What a player does, heard by a script on the real server: after /listen, each event below tells the player in
-- chat, and a few answer back (a stick can't be dropped, a death keeps the inventory, a respawn is moved).

local function say(player, ...)
  local words = {}
  for index = 1, select("#", ...) do
    words[#words + 1] = tostring(select(index, ...))
  end
  player:send_message(table.concat(words, " "))
end

local function kind(item)
  return item and item.kind
end

nf.commands.register(
  "listen",
  { description = "Hear what I do", players_only = true },
  function(event)
    local player = event.player
    local moved = false
    player:on("move", function(move)
      if not moved then
        moved = true
        say(player, "move", move.to.position.x > move.from.position.x)
      end
    end)
    player:on("teleport", function(teleport)
      say(player, "teleport", teleport.cause)
    end)
    player:on("swap_hands", function()
      say(player, "swap")
    end)
    player:on("drop_item", function(drop)
      if drop.item.kind == "minecraft:stick" then
        drop:cancel()
        say(player, "no dropping sticks")
      else
        say(player, "drop", drop.item.kind, drop.item.count or 1)
      end
    end)
    player:on("pickup_item", function(pickup)
      say(player, "pickup", pickup.item.kind, pickup.item.count or 1)
    end)
    player:on("interact", function(click)
      say(player, "click", click.click, click.block and click.block:kind(), click.face)
    end)
    player:on("use_item", function(use)
      say(player, "use", kind(use.item), use.hand)
    end)
    player:on("consume_item", function(eat)
      say(player, "eat", kind(eat.item))
    end)
    player:on("interact_entity", function(click)
      say(player, "interact", click.entity:kind(), click.hand)
    end)
    player:on("death", function(death)
      death.message = "<red>" .. player:name() .. " fell"
      death.keep_inventory = true
    end)
    player:on("respawn", function(respawn)
      respawn.location =
        respawn.location.world:location(vec3(3.5, respawn.location.position.y, 3.5))
      say(player, "respawn")
    end)
    local world = player:world()
    world:on("block_place", function(place)
      if place.player == player then
        say(player, "place", place.state)
      end
    end)
    world:on("block_break", function(broken)
      if broken.player == player then
        say(player, "break", broken.block:kind(), #broken.drops)
      end
    end)
    say(player, "listening")
  end
)

nf.commands.register(
  "hungry",
  { description = "Empty some of my stomach", players_only = true },
  function(event)
    event.player:set_food(10)
    say(event.player, "hungry")
  end
)
