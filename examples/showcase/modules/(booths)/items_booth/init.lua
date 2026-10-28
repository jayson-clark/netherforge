-- Booth: items. Project items (items/<id>/item.json) are item kinds of the project's own: the
-- server can always tell their stacks from any other of the same Minecraft kind, and each has a
-- script that hears what players do with any of them.
local expo = require("expo")

local GALLERY = {
  { id = "ruby", note = "use and pickup handlers; custom model; tooltip style" },
  { id = "ruby_wand", note = "a hold item: charge it, release to cast" },
  { id = "crown", note = "equipment: drawn on your head by the pack" },
  { id = "circlet", note = "attribute modifiers: +2 armour when worn" },
  { id = "candy", note = "food on a non-food kind; consume handler" },
  { id = "glow_pickaxe", note = "enchanted, made at a smithing table" },
  { id = "lantern", note = "a stack's own data changes what it does" },
  { id = "ruby_pick", note = "canBreak/canPlaceOn for adventure mode" },
}

expo.booth({
  id = "items",
  title = "Items",
  summary = "Item kinds of your own: models, tooltips, food, equipment, scripts",
  icon = "minecraft:paper",
  lines = {
    "<gold>Items</gold> live in items/<id>/item.json, on any Minecraft kind.",
    "",
    "<gray>A name and lore in MiniMessage, a model and tooltip frame from the pack,",
    "<gray>rarity, enchantments, food, equipment, attribute modifiers, cooldowns,",
    "<gray>adventure-mode rules and a `hold` use with no end.",
    "",
    "<gray>Its script runs once for the item and hears every stack: use, pickup,",
    "<gray>consume. A stack carries its own saved data (kindle the lantern at the",
    "<gray>altar in the blocks booth to see one stack differ from another).",
  },
  build = function(booth)
    -- Each item turning on show: an item display node showing the project item's own look.
    for index, entry in ipairs(GALLERY) do
      local v = 1.5 + (index - 1) * 1.3
      local shown = booth:spawn("display_item", 8, 1.5, v)
      local node = shown and shown:node("item")
      if node then
        node:set_display_item(nf.items.create(entry.id))
      end
      booth:caption(8, v, ("<white>%s\n<gray>%s"):format(entry.id, entry.note), 2.9, 90)
    end
    booth:kiosk(3, 4, "<yellow>Take one of each", function(player)
      for _, entry in ipairs(GALLERY) do
        player:give_item(nf.items.create(entry.id))
      end
      player:give_item({ kind = "minecraft:golden_helmet" })
      player:send_message(
        "<gray>Hover over each to see its tooltip. Try the wand (hold right click), eat the candy, wear the crown."
      )
    end)
    booth:kiosk(3, 8, "<yellow>Item gallery menu", function(player)
      local template = nf.menus.create({ rows = 1, title = "<dark_gray>Click to take" })
      local menu = player:open_menu(template)
      if not menu then
        return
      end
      for index, entry in ipairs(GALLERY) do
        menu:set_item(index - 1, nf.items.create(entry.id))
      end
      template:on("click", function(event)
        local entry = event.in_menu and event.index and GALLERY[event.index + 1]
        if entry then
          event.player:give_item(nf.items.create(entry.id))
        end
      end)
    end)
  end,
})
