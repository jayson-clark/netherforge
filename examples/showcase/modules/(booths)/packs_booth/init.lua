-- Booth: resource packs. resource_packs/ui/pack.json makes one Minecraft resource pack, sent to
-- every player on join: item models, block textures and models, menu skins, glyphs in text,
-- tooltip frames, equipment looks, sounds and the HUD.
local expo = require("expo")

expo.booth({
  id = "packs",
  title = "Resource packs & HUD",
  summary = "Models, skins, glyphs, tooltips, equipment, sounds and the HUD",
  icon = "minecraft:painting",
  lines = {
    "<gold>Resource packs</gold>: resource_packs/<pack>/pack.json and its pictures.",
    "",
    "<gray>NetherForge builds one pack for the project, serves it, and sends it",
    "<gray>on join (again only when it changed). In it: <white>items</white> <gray>and <white>blocks</white>",
    "<gray>(textures or models), <white>skins</white> <gray>(menu backgrounds as title glyphs),",
    "<white>glyphs</white> <gray>like <glyph:ui/coin> <glyph:ui/spark> <glyph:ui/wisp> in any text, <white>tooltips</white>,",
    "<white>equipment</white> <gray>(the crown on the stand), <white>sounds</white> <gray>and the <white>HUD</white>: bars,",
    "<gray>icons and text around the hotbar, filled per player by scripts.",
  },
  build = function(booth)
    booth:kiosk(3, 2, "<yellow>Fill the HUD bar", function(player)
      local hud = player:hud()
      local filled = ((player:data().packs_hud or 0) + 1) % 4
      player:data().packs_hud = filled
      hud:fill_bar("ui/treasure", filled, 3)
      hud:set_text("ui/treasure_label", ("<yellow>HUD demo <white>%d/3"):format(filled))
    end)
    booth:caption(
      3,
      2,
      "<white>HUD\n<gray>A 3-segment bar and a line of text over your hotbar.",
      3.2
    )

    local sounds = { "ui/chime", "ui/hum", "ui/infuse", "ui/crystal_break" }
    for index, sound in ipairs(sounds) do
      booth:kiosk(6, 1 + index * 2, "<yellow>" .. sound, function(player)
        player:play_sound(sound)
      end)
    end
    booth:caption(
      6,
      6,
      "<white>Sounds\n<gray>.ogg files with subtitles, volume and pitch in pack.json.",
      3.4
    )

    local stand = booth:spawn_entity("minecraft:armor_stand", 8.5, 1, 11) --[[@as Living?]]
    if stand then
      stand:set_equipment("head", nf.items.create("crown"))
      stand:set_equipment("main_hand", nf.items.create("ruby"))
    end
    booth:caption(
      8.5,
      11,
      "<white>Equipment look\n<gray>The crown is paper, drawn on the head by the pack.",
      3.2
    )
  end,
})
