-- Booth: worlds and structures. Scripts make worlds (void, flat, the game's or the project's
-- terrains), unload and delete the ones they made, set borders, and save and place structures.
local expo = require("expo")

local pockets = {} ---@type table<string, World> player id -> their pocket world

-- Leaving a pocket world deletes it: a project may delete the worlds it made.
nf.on("player_change_world", function(event)
  local id = event.player:id()
  local pocket = pockets[id]
  if pocket and event.from:name() == pocket:name() then
    pockets[id] = nil
    nf.after(20, function()
      pocket:unload({ delete = true })
    end)
  end
end)

expo.booth({
  id = "worlds",
  title = "Worlds & structures",
  summary = "Make, border and delete worlds; save and place structures",
  icon = "minecraft:structure_block",
  lines = {
    "<gold>Worlds</gold>: nf.worlds.create, world:unload({ delete = true }), borders.",
    "<gold>Structures</gold>: structures/<id>.nbt, world:place_structure, save_structure.",
    "",
    "<white>Pocket world</white> <gray>a void world made just for you, an island filled in",
    "<gray>gradually (fill_gradually), a tree placed from a structure file, a 48-block",
    "<gray>border. Leave it (your tour guide) and it's deleted.",
    "<white>Copy machine</white> <gray>saves the little build on the left as a structure and",
    "<gray>places copies of it, turned. <white>Lobby</white> <gray>a world the project manages.",
  },
  build = function(booth)
    -- A little build to copy.
    expo.world:fill_blocks(booth:cell(6, 1, 1), booth:cell(8, 1, 3), "minecraft:mossy_cobblestone")
    expo.world:set_block(booth:cell(7, 2, 2), "minecraft:lantern")
    booth:kiosk(4, 2, "<yellow>Copy machine", function(player)
      local a, b = booth:cell(6, 1, 1), booth:cell(8, 2, 3)
      if not expo.world:save_structure("expo_copy", a, b) then
        player:send_message("<red>Couldn't save it.")
        return
      end
      local target = booth:cell(6, 1, 5)
      local rotation = math.random(0, 3) * 90
      expo.world:fill_blocks(target, target + vec3(0, 2, 4), "minecraft:air")
      expo.world:place_structure(
        "expo_copy",
        booth.west and (target - vec3(2, 0, 0)) or target,
        { rotation = rotation }
      )
      player:send_message(
        ("<gray>Saved as a structure (in the server's data, not the project) and placed turned %d°."):format(
          rotation
        )
      )
    end)
    booth:caption(4, 2, "<white>Save & place\n<gray>save_structure, then place_structure.", 3.2)

    booth:kiosk(4, 7, "<yellow>Make me a pocket world", function(player)
      local name = "pocket_" .. player:name():lower()
      local pocket = nf.worlds.get(name)
        or nf.worlds.create(name, { generator = "void", structures = false })
      if not pocket then
        player:send_message("<red>The server couldn't make it.")
        return
      end
      pockets[player:id()] = pocket
      local centre = vec3(0, 64, 0)
      local border = pocket:border()
      if border then
        border:set_center(centre)
        border:set_size(48)
      end
      pocket:fill_blocks(centre - vec3(2, 1, 2), centre + vec3(2, -1, 2), "minecraft:grass_block")
      player:teleport(pocket:location(centre + vec3(0.5, 0, 0.5)))
      player:send_message(
        "<light_purple>Your own world. <gray>Watch the island grow; leave with your tour guide and it's deleted."
      )
      pocket:fill_gradually(
        centre - vec3(12, 1, 12),
        centre + vec3(12, -1, 12),
        "minecraft:grass_block",
        nil,
        function()
          pocket:place_structure("oak_tree", centre + vec3(4, 0, 4))
        end
      )
    end)
    booth:caption(
      4,
      7,
      "<white>A world of your own\n<gray>Void generator, gradual fill, a border, deleted after.",
      3.2
    )

    booth:portal(4, 11, "<light_purple>The lobby", "lobby")
    booth:caption(
      4,
      11,
      "<white>A managed world\n<gray>netherforge.json's managedWorlds: lobby.",
      3.2
    )
  end,
})
