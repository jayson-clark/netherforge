-- Booth: mannequins. Player-like figures that aren't players: a skin, a line of text under the
-- name, a pose, equipment, and an `interact` event, for statues, shopkeepers and quest givers.
local expo = require("expo")

expo.booth({
  id = "mannequins",
  title = "Mannequins",
  summary = "Player-shaped figures with skins, poses and clicks",
  icon = "minecraft:player_head",
  lines = {
    '<gold>Mannequins</gold>: world:spawn_entity("minecraft:mannequin", ...).',
    "",
    "<gray>A mannequin looks like a player but isn't one: it's on no player list,",
    "<gray>counts towards nothing and costs no more than an armour stand.",
    "<gray>It takes a skin (a player's name or a texture), a description line,",
    "<gray>a pose, equipment, and turns to look at things.",
    "",
    "<gray>The guide at the hub is one. Right-click these.",
  },
  build = function(booth)
    local figures = {
      { v = 2.5, pose = "standing", skin = "Notch", say = "Standing, with Notch's skin" },
      { v = 6, pose = "crouching", say = "Crouching, holding a ruby" },
      { v = 9.5, pose = "sleeping", say = "Sleeping (try waking it)" },
    }
    for _, figure in ipairs(figures) do
      local mannequin = booth:spawn_entity("minecraft:mannequin", 6, 1, figure.v) --[[@as Mannequin?]]
      if mannequin then
        mannequin:set_pose(figure.pose)
        if figure.skin then
          mannequin:set_skin(figure.skin)
        else
          mannequin:set_equipment("main_hand", nf.items.create("ruby"))
          mannequin:set_equipment("head", nf.items.create("crown"))
        end
        mannequin:set_description("<gray>" .. figure.say)
        mannequin:set_immovable(true)
        mannequin:set_invulnerable(true)
        mannequin:on("interact", function(event)
          local at = event.player:eye_position()
          if at then
            mannequin:look_at(at)
          end
          if mannequin:pose() == "sleeping" then
            mannequin:set_pose("standing")
            nf.after(60, function()
              mannequin:set_pose("sleeping")
            end)
          end
          event.player:send_message("<gray>The mannequin heard your click (its `interact` event).")
        end)
      end
    end
    booth:caption(4, 6, "<white>Three mannequins\n<gray>Skins, poses, equipment, look_at.", 3.2)
  end,
})
