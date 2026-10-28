-- Booth: packages. The showcase depends on another project, examples/library (netherforge.json's
-- dependencies, pinned in netherforge.lock), and uses what it exports: a module, an item, a loot
-- table and an advancement to hang its own under.
local expo = require("expo")
-- "library:" names the package; it exports `greetings`, so this works (its `phrases` it doesn't).
local greetings = require("library:greetings")

expo.booth({
  id = "packages",
  title = "Packages",
  summary = "Projects that use other projects' modules, items and tables",
  icon = "minecraft:chest_minecart",
  lines = {
    "<gold>Packages</gold>: netherforge.json's dependencies, locked in netherforge.lock.",
    "",
    "<gray>examples/library is a project too. Its `exports` say what others may use:",
    '<white>modules</white> <gray>require("library:greetings")',
    "<white>items</white> <gray>library:gem, in the shop and the treasure table",
    "<white>loot</white> <gray>library:gems, rolled from loot/treasure.json",
    "<white>advancements</white> <gray>Gem Hoarder hangs under library:gem_collector",
    "",
    "<gray>A package has its own namespace, settings, requirements and storage.",
  },
  build = function(booth)
    booth:kiosk(4, 3, "<yellow>Greet me (library)", function(player)
      player:send_message(greetings.hello(player:name()))
    end)
    booth:caption(
      4,
      3,
      "<white>A package's module\n<gray>Its words follow the library's own `style` setting.",
      3.2
    )
    booth:kiosk(4, 9, "<yellow>A library gem", function(player)
      player:give_item(nf.items.create("library:gem"))
      player:grant_advancement("gem_hoarder")
      player:send_message("<aqua>A gem from the library. <gray>Its own script hears you use it.")
    end)
    booth:caption(4, 9, "<white>A package's item\n<gray>Gem Hoarder, under the library's tab.", 3.2)
  end,
})
