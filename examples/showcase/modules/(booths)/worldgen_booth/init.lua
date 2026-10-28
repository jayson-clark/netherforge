-- Booth: world generation. A world of the project's own, made by a terrain (terrain/ruby_hills.json)
-- with biomes of its own (biomes/), a datapack's features (datapacks/), structures that generate
-- (structures/ruined_shrine.json) and a dimension type (dimension_types/deep.json).
local expo = require("expo")

expo.booth({
  id = "worldgen",
  title = "Terrain, biomes & structures",
  summary = "A whole world from the project's files: the ruby hills",
  icon = "minecraft:grass_block",
  lines = {
    "<gold>The hills world</gold> <gray>is netherforge.json's `hills`: terrain/ruby_hills.json.",
    "",
    "<white>Terrain</white> <gray>noise for hills and peaks, density for ledges, layers, caves,",
    "<gray>ores (ruby ore, the project's own block), decorations (flowers, trees from",
    "<gray>structures/oak_tree.nbt), and areas picked by temperature and humidity.",
    "<white>Biomes</white> <gray>biomes/ruby_grove.json: colours, particles, music, spawns.",
    "<white>Datapack</white> <gray>datapacks/ruby_boulders: boulders the grove places.",
    "<white>Structures</white> <gray>ruined shrines generate in the grove, a keeper in each.",
    "<white>Dimension</white> <gray>dimension_types/deep.json: from y -128, 448 blocks tall.",
  },
  build = function(booth)
    booth:portal(4, 3, "<light_purple>Go to the hills", "hills")
    booth:caption(4, 3, "<white>Portal\n<gray>Your tour guide brings you back.", 3.2)
    booth:kiosk(4, 9, "<yellow>Take me to a ruby grove", function(player)
      local hills = nf.worlds.get("hills")
      if not hills then
        player:send_message("<red>The hills aren't on this server.")
        return
      end
      player:send_message("<gray>Looking for one (world:locate_biome)…")
      hills:locate_biome(
        "ruby_grove",
        { near = vec3(0, 80, 0), radius = 2000 },
        function(found, err)
          if not found then
            player:send_message("<red>None found: " .. tostring(err))
            return
          end
          hills:load_chunk(found)
          local top = hills:highest_block(found)
          local at = top and top:position() + vec3(0.5, 1, 0.5) or found
          player:teleport(hills:location(at))
          player:send_message(
            "<light_purple>The ruby grove. <gray>Look for ruined shrines and tortoises; /locate structure showcase:ruined_shrine finds a shrine."
          )
        end
      )
    end)
    booth:caption(
      4,
      9,
      "<white>Find a biome\n<gray>locate_biome searches the generator, not loaded chunks.",
      3.2
    )
  end,
})
