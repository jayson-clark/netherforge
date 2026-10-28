-- Booth: menus. Chest windows from menus/<id>/menu.json with a script each: a window per player
-- or one shared by all, locked or not, slots bound to a storage the player keeps, skinned titles,
-- and menus a script makes from a table.
local expo = require("expo")

expo.booth({
  id = "menus",
  title = "Menus",
  summary = "Chest windows with scripts: per player, shared, bound to storage",
  icon = "minecraft:barrel",
  lines = {
    "<gold>Menus</gold> live in menus/<id>/menu.json, with a script.",
    "",
    "<white>Shop</white> <gray>a window per player, its title drawn by a skin from the pack;",
    "<gray>a slot's handler cancels its click and stops it reaching the window's.",
    "<white>Board</white> <gray>`shared`: one window for everyone, lit lamps lit for all.",
    "<white>Backpack</white> <gray>slots bound to player:storage, saved with your inventory;",
    "<gray>the last slot only takes rubies and gems (a slot rule).",
    "<white>Altar</white> <gray>locked, but one slot lets items through (the glow lamp).",
    "<white>Script-made</white> <gray>nf.menus.create: the tour menu is one.",
  },
  build = function(booth)
    booth:kiosk(5, 2, "<yellow>Shop", function(player)
      local greeter = player:data().greeter or {}
      player:open_menu("shop", { context = { nickname = greeter.nickname } })
    end)
    booth:caption(
      5,
      2,
      '<white>Per-player window\n<gray>Skinned title; rubies are "sold out".',
      3.2
    )
    booth:kiosk(5, 5, "<yellow>Community board", function(player)
      player:open_menu("board")
    end)
    booth:caption(
      5,
      5,
      "<white>Shared window\n<gray>Open it with a friend: you see each other's clicks.",
      3.2
    )
    booth:kiosk(5, 8, "<yellow>Backpack", function(player)
      player:open_menu("backpack", { context = { player = player } })
    end)
    booth:caption(
      5,
      8,
      "<white>Storage slots\n<gray>What you leave here is there after a restart.",
      3.2
    )
    booth:kiosk(5, 11, "<yellow>Altar", function(player)
      player:open_menu("altar")
      player:give_item(nf.items.create("shard", { count = 6 }))
      player:give_item(nf.items.create("lantern"))
    end)
    booth:caption(5, 11, "<white>An open slot\n<gray>Set the lantern in the ring; shards pay.", 3.2)
  end,
})
