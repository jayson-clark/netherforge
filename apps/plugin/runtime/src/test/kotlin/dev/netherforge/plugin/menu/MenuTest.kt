package dev.netherforge.plugin.menu
import dev.netherforge.format.bridge.ScriptError
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.ref.ResourceKey
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.format.resourcepack.PackFonts
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.testkit.FakePlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Menu windows and their script, through real Lua against the fake platform. */
class MenuTest {

    private fun menu(extra: String = "", script: String = "-- nothing") = mapOf(
        "menus/menu/menu.json" to """
            {
              "title": "<gold>Menu",
              "rows": 1,
              "script": { "file": "script.lua" },
              "slots": {
                "0": { "item": { "kind": "minecraft:bread", "count": 4, "name": "<yellow>Bread" } },
                "4": { "item": { "kind": "minecraft:diamond", "data": { "price": 5 } } }
              }$extra
            }
        """,
        "menus/menu/script.lua" to script,
        "modules/m/init.lua" to """
            nf.commands.register("menu", function(ctx) ctx.player:open_menu("menu") end)
        """
    )

    @Test
    fun `the shop example opens per player, fills from the file, and its script answers clicks`() {
        TestServer(TestServer.example("showcase")).use { server ->
            val alex = server.player("Alex")
            val sam = server.player("Sam")
            server.platform.commands.run(alex, "shop")
            server.platform.commands.run(sam, "shop")
            val windows = server.platform.menus
            val a = windows.of(alex)!!
            val b = windows.of(sam)!!
            assertTrue(a !== b, "a shop that isn't shared is a window per player")
            assertEquals(27, a.spec.size)

            // The skin is drawn in front of the title, in the pack's gui font and in white:
            // move to the window's edge, draw the 176-wide picture (advance 177), come back.
            val shop = server.runtime.session.snapshot.compiledResourcePacks.getValue("ui").skins.getValue("shop")
            assertEquals(177, shop.advance, "read from the PNG: every column of the 176-wide art is opaque")
            val prefix = PackFonts.offset(-8) + shop.char + PackFonts.offset(8 - 177)
            assertEquals("<white><font:showcase:ui/gui>$prefix</font></white><dark_gray>Village shop", a.title)

            // Prices are written in the file with the coin glyph's tag; the adapter's MiniMessage resolves it.
            assertEquals(listOf("<gray><glyph:ui/coin> 2"), a.slots[11]!!.def.lore)
            assertEquals(server.runtime.packs.glyph(ResourceKey("showcase", "ui/coin")), server.platform.glyphs("ui/coin"))
            assertEquals(ResourceRef("ui/ruby"), a.slots[13]!!.def.itemModel)
            // Slot 12 sells the library's gem (examples/library, a package the showcase depends on), in the library's look.
            assertEquals(ResourceRef("library:gem"), a.slots[12]!!.def.item)
            assertEquals(ResourceRef("library:gems/gem"), a.slots[12]!!.def.itemModel)
            assertEquals("<aqua>Gem", a.slots[12]!!.def.name)

            // The ruby's slot handler takes its click; a locked window cancels the rest.
            assertTrue(windows.click(alex, 13))
            assertEquals("<red>Rubies are sold out.", alex.messages.last())
            assertTrue(windows.click(alex, 11), "locked: the click would have taken the bread")
            assertEquals(4, a.slots[11]!!.def.count)
            assertFalse(windows.click(alex, 3, top = false, moves = false), "rearranging their own bag is theirs")

            // Slot 15 closes it; the window goes the next tick.
            assertTrue(windows.click(alex, 15))
            assertNull(windows.of(alex))
            server.tick()
            assertEquals(1, server.runtime.session.menus.all().count { !it.definition.shared }, "only Sam's window is left")
        }
    }

    @Test
    fun `a click goes from the slot to the menu to nf, and the menu opens and closes`() {
        val script = """
            log("body " .. this:kind())
            this:on("open", function(event) log("open " .. event.player:name() .. " " .. event.name) end)
            this:on("click", function(event)
              log("window click " .. tostring(event.index) .. " " .. event.click .. " " .. tostring(event.target and event.target:index()))
            end)
            this:on("close", function(event) log("close " .. event.player:name()) end)
            this:slot(4):on("click", function(event)
              log("slot click " .. event.item.kind .. " " .. tostring(event.in_menu) .. " " .. tostring(event.current == event.target))
              if event.click == "right" then
                event:cancel()
                event:stop()
              end
            end)
            nf.on("unload", function() log("unload") end)
        """
        TestServer(
            menu(script = script) + (
                "modules/watch/init.lua" to """
                    nf.on("menu_open", function(event) log("nf open " .. event.menu:kind()) end)
                    nf.on("menu_click", function(event) log("nf click " .. tostring(event.index) .. " " .. tostring(event.cancelled)) end)
                    nf.on("menu_close", function(event) log("nf close " .. event.name) end)
                """
                )
        ).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "menu")
            val windows = server.platform.menus
            assertFalse(windows.click(alex, 0, moves = false), "an unlocked click nobody cancels goes ahead")
            windows.click(alex, 4, click = "left", moves = false)
            assertTrue(windows.click(alex, 4, click = "right", moves = false))
            pressEscape(server, alex)
            server.tick()
            assertEquals(
                listOf(
                    "body menu",
                    "open Alex open",
                    "nf open menu",
                    "window click 0 left 0",
                    "nf click 0 false",
                    "slot click minecraft:diamond true true",
                    "window click 4 left 4",
                    "nf click 4 false",
                    "slot click minecraft:diamond true true",
                    "close Alex",
                    "nf close menu_close",
                    "unload"
                ),
                server.logs
            )
            assertTrue(server.runtime.session.menus.all().isEmpty())
        }
    }

    @Test
    fun `a locked menu starts moving clicks and drags cancelled, and a handler may let one through`() {
        val script = """
            this:on("click", function(event)
              log("click", event.index, tostring(event.cancelled))
              if event.index == 0 then
                event:uncancel()
              end
            end)
            this:on("drag", function(event)
              log("drag", #event.indices, tostring(event.indices[1]), table.concat(event.own_indices, ","), event.click,
                event.cursor_item.kind, tostring(event.cancelled))
            end)
        """
        TestServer(menu(extra = ", \"locked\": true", script = script)).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "menu")
            val windows = server.platform.menus
            assertFalse(windows.click(alex, 0), "uncancelled by the handler")
            assertTrue(windows.click(alex, 1), "locked: cancelled")
            val bread = ItemData(ItemDef("minecraft:bread", count = 2))
            assertTrue(windows.drag(alex, listOf(2, 3), bread))
            assertFalse(windows.drag(alex, emptyList(), bread, ownSlots = listOf(9, 10), click = "right"), "only their own inventory")
            assertEquals(
                listOf(
                    "click\t0\ttrue",
                    "click\t1\ttrue",
                    "drag\t2\t2\t\tleft\tminecraft:bread\ttrue",
                    "drag\t0\tnil\t9,10\tright\tminecraft:bread\tfalse"
                ),
                server.logs
            )
        }
    }

    @Test
    fun `handlers on a window go with it, whoever registered them`() {
        TestServer(
            menu() + (
                "modules/m/init.lua" to """
                    local subscription
                    nf.commands.register("menu", function(ctx)
                      local menu = ctx.player:open_menu("menu")
                      subscription = menu:slot(0):on("click", function() log("module heard a click") end)
                    end)
                    nf.commands.register("check", function(ctx)
                      log("active", subscription:is_active())
                    end)
                """
                )
        ).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "menu")
            server.platform.menus.click(alex, 0, moves = false)
            server.platform.commands.run(alex, "check")
            pressEscape(server, alex)
            server.tick()
            server.platform.commands.run(alex, "check")
            assertEquals(listOf("module heard a click", "active\ttrue", "active\tfalse"), server.logs)
        }
    }

    @Test
    fun `items round-trip through Lua, carrying what the fields can't say`() {
        val script = """
            do
              local bread = this:item(0)
              assert(bread.kind == "minecraft:bread" and bread.count == 4 and bread.name == "<yellow>Bread")
              bread.count = 3
              this:set_item(0, bread)
              assert(this:add_item({ kind = "minecraft:bread", count = 70, name = "<yellow>Bread" }) == 0)
              this:set_item(8, { kind = "gold_ingot", glint = true, lore = {}, hide_tooltip = false })
              local n = 0
              for s, item in pairs(this:items()) do n = n + 1 end
              log("items " .. n .. " size " .. this:size())
            end
        """
        TestServer(menu(script = script)).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "menu")
            val window = server.platform.menus.of(alex)!!
            assertEquals(listOf("items 4 size 9"), server.logs)
            // 3 + 70: the stack topped up to 64 first, the rest into the first empty slot.
            assertEquals(64, window.slots[0]!!.def.count)
            assertEquals(9, window.slots[1]!!.def.count)
            assertEquals("<yellow>Bread", window.slots[1]!!.def.name)
            assertEquals(true, window.slots[8]!!.def.glint)
            assertEquals(emptyList(), window.slots[8]!!.def.lore)
        }
    }

    @Test
    fun `an item's raw data survives a read, change and write`() {
        TestServer(menu()).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "menu")
            val uuid = server.platform.menus.viewing(alex.ref.uuid)!!
            server.platform.menus.setItem(uuid, 2, ItemData(ItemDef("minecraft:written_book", count = 1), raw = "PAGES"))
            server.write(
                "modules/m/init.lua",
                """
                nf.commands.register("menu", function(ctx) ctx.player:open_menu("menu") end)
                nf.commands.register("touch", function(ctx)
                  local window = ctx.player:menu()
                  local book = window:item(2)
                  book.name = "<gold>Diary"
                  window:set_item(2, book)
                end)
                """
            )
            server.reload("modules/m/init.lua")
            server.platform.commands.run(alex, "touch")
            val book = server.platform.menus.item(uuid, 2)!!
            assertEquals("PAGES", book.raw)
            assertEquals("<gold>Diary", book.def.name)
        }
    }

    @Test
    fun `a misspelled item field, an unknown item and an out-of-range slot are errors at the script's line`() {
        for ((line, message) in listOf(
            "this:set_item(0, { kind = \"minecraft:bread\", cuont = 2 })" to "items have no field \"cuont\"",
            "this:set_item(0, { kind = \"minecraft:breadd\" })" to "has no item \"minecraft:breadd\"",
            "this:set_item(0, { id = \"minecraft:bread\" })" to "items have no field \"id\"",
            "this:set_item(0, { kind = \"minecraft:bread\", data = { f = function() end } })" to "item.data.f: a function can't be saved",
            "this:set_item(0, { kind = \"minecraft:bread\", data = \"coin\" })" to "item.data must be a table",
            "this:item(9)" to "slot 9 is outside this window",
            "this:set_item(0, { kind = \"minecraft:paper\", item_model = \"ui/nope\" })" to "there's no resource pack \"ui\"",
            "nf.menus.shared(\"menuu\")" to "no menu \"menuu\" in this project (did you mean \"menu\"?)"
        )) {
            TestServer(menu(script = "do\n  $line\nend")).use { server ->
                server.platform.commands.run(server.player(), "menu")
                val error = server.errors.single()
                assertTrue(message in error.message, "${error.message} should say $message")
                assertEquals("menus/menu/script.lua", error.source?.file)
                assertEquals(2, error.source?.line)
            }
        }
    }

    @Test
    fun `a shared menu is one window for everyone, made at load`() {
        TestServer(
            menu(
                extra = ", \"shared\": true",
                script = "log('made')"
            ) +
                (
                    "modules/m/init.lua" to
                        "nf.commands.register('menu', function(ctx) ctx.player:open_menu('menu') end)\nlog(tostring(nf.menus.shared('menu'):id()))"
                    )
        ).use { server ->
            assertEquals(listOf("menu", "made"), server.logs, "the window is there before any script runs")
            val alex = server.player("Alex")
            val sam = server.player("Sam")
            server.platform.commands.run(alex, "menu")
            server.platform.commands.run(sam, "menu")
            assertTrue(server.platform.menus.of(alex) === server.platform.menus.of(sam))
            pressEscape(server, alex)
            server.tick()
            assertNotNull(server.runtime.session.menus.window("menu"), "a shared window outlives its viewers")
        }
    }

    @Test
    fun `a menu hands out slots, empties one slot or all, and closes for one viewer or all`() {
        TestServer(
            menu(extra = ", \"shared\": true") +
                (
                    "modules/m/init.lua" to """
                        nf.commands.register("menu", function(ctx) ctx.player:open_menu("menu") end)
                        nf.commands.register("probe", function(ctx)
                          local menu = ctx.player:menu()
                          local slot = menu:slot(4)
                          log(menu:is_shared(), menu:is_locked(), slot:index(), slot:menu() == menu, slot == menu:slot(4))
                          log(slot:item().kind, slot:set_item({ kind = "minecraft:paper" }), menu:item(4).kind)
                          log(slot:set_item(nil), tostring(slot:item()), menu:set_item(0, nil), tostring(menu:item(0)))
                          menu:set_item(1, { kind = "minecraft:bread" })
                          log(menu:clear(), next(menu:items()) == nil)
                          log(pcall(menu.slot, menu, 9))
                        end)
                        nf.commands.register("close", function(ctx)
                          local menu = nf.menus.shared("menu")
                          log(menu:close_for(ctx.player), menu:close_for(ctx.player), #menu:viewers(), menu:close_all(), #menu:viewers())
                          log(ctx.player:close_menu())
                        end)
                    """
                    )
        ).use { server ->
            val alex = server.player("Alex")
            val sam = server.player("Sam")
            server.platform.commands.run(alex, "menu")
            server.platform.commands.run(sam, "menu")
            server.platform.commands.run(alex, "probe")
            assertEquals(emptyList(), server.errors.map { it.message })
            server.platform.commands.run(alex, "close")
            assertEquals(
                listOf(
                    "true\ttrue\t4\ttrue\ttrue",
                    "minecraft:diamond\ttrue\tminecraft:paper",
                    "true\tnil\ttrue\tnil",
                    "true\ttrue",
                    "false\tslot 9 is outside this window: it has 9 slots, 0 to 8",
                    "true\tfalse\t1\ttrue\t0",
                    "false"
                ),
                server.logs
            )
        }
    }

    @Test
    fun `close_menu closes only a menu of ours`() {
        TestServer(menu()).use { server ->
            val alex = server.player("Alex")
            server.write(
                "modules/m/init.lua",
                """
                nf.commands.register("menu", function(ctx) ctx.player:open_menu("menu") end)
                nf.commands.register("shut", function(ctx) log(ctx.player:close_menu(), tostring(ctx.player:menu())) end)
                """
            )
            server.reload("modules/m/init.lua")
            server.platform.commands.run(alex, "shut")
            server.platform.commands.run(alex, "menu")
            server.platform.commands.run(alex, "shut")
            assertEquals(listOf("false\tnil", "true\tnil"), server.logs)
        }
    }

    @Test
    fun `reloading keeps a shared window's contents and viewers but takes the file's edits`() {
        TestServer(menu(extra = ", \"shared\": true")).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "menu")
            val uuid = server.platform.menus.viewing(alex.ref.uuid)!!
            // A player took bread; a script put gold in slot 7.
            server.platform.menus.setItem(uuid, 0, ItemData(ItemDef("minecraft:bread", count = 1)))
            server.platform.menus.setItem(uuid, 7, ItemData(ItemDef("minecraft:gold_ingot", count = 2)))

            server.write(
                "menus/menu/menu.json",
                """
                {
                  "title": "<gold>Menu",
                  "rows": 1,
                  "shared": true,
                  "script": { "file": "script.lua" },
                  "slots": {
                    "0": { "item": { "kind": "minecraft:bread", "count": 4, "name": "<yellow>Bread" } },
                    "4": { "item": { "kind": "minecraft:paper" } }
                  }
                }
                """
            )
            val result = server.reload("menus/menu/menu.json").resources.single()
            assertEquals("menu:menu", result.label)
            assertTrue(result.ok)
            assertEquals(1, result.reattached)
            val window = server.platform.menus.windows.getValue(uuid)
            assertEquals(listOf(alex.ref.uuid), window.viewers.toList(), "still open, same window")
            assertEquals(1, window.slots[0]!!.def.count, "bread untouched by the file keeps what players did")
            assertEquals("minecraft:gold_ingot", window.slots[7]!!.def.kind)
            assertEquals("minecraft:paper", window.slots[4]!!.def.kind, "the slot the file changed takes the new item")

            // Two rows now: the shape changed, so it's rebuilt from the file and reopened.
            server.write("menus/menu/menu.json", """{ "rows": 2, "shared": true }""")
            assertEquals(0, server.reload("menus/menu/").resources.single().reattached)
            val rebuilt = server.platform.menus.of(alex)!!
            assertEquals(18, rebuilt.spec.size)
            assertTrue(rebuilt.slots.all { it == null })

            server.delete("menus/menu/menu.json")
            server.reload("menus/menu/menu.json")
            assertNull(server.platform.menus.of(alex), "a deleted menu's windows close")
        }
    }

    @Test
    fun `a menu with errors keeps its last good version running`() {
        TestServer(menu(script = "this:on('click', function() log('v1') end)")).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "menu")
            server.write("menus/menu/menu.json", """{ "rows": 9 }""")
            val result = server.reload("menus/menu/menu.json").resources.single()
            assertFalse(result.ok)
            assertEquals("menu.rows", result.problems.single().code)
            server.platform.menus.click(alex, 0, moves = false)
            assertEquals(listOf("v1"), server.logs)
        }
    }

    @Test
    fun `reloading a module a menu's script required restarts it too`() {
        TestServer(
            menu(script = "local m = require('m.util')\nlog(m.word)", extra = ", \"shared\": true") +
                ("modules/m/util.lua" to "return { word = 'one' }")
        ).use { server ->
            server.write("modules/m/util.lua", "return { word = 'two' }")
            val result = server.reload("modules/m/util.lua")
            assertEquals(listOf("module:m", "menu:menu"), result.resources.map { it.label })
            assertEquals(listOf("one", "two"), server.logs)
            assertTrue(server.errors.none { it is ScriptError && "budget" in it.message })
        }
    }

    @Test
    fun `an item's script data comes from the file and goes wherever the item goes`() {
        val script = """
            do
              log("from the file", this:item(4).data.price)
              this:set_item(1, { kind = "minecraft:gold_ingot", data = { coin = true, worth = 3, tags = { "a", "b" } } })
              local coin = this:item(1)
              log("coin", tostring(coin.data.coin), coin.data.worth, coin.data.tags[2], tostring(coin.raw))
              coin.count = 2
              this:set_item(1, coin)
              log("kept", tostring(this:item(1).data.coin), this:item(1).count)
              coin.data = {}
              this:set_item(1, coin)
              log("cleared", tostring(this:item(1).data))
              log("none", tostring(this:item(0).data))
            end
        """
        TestServer(menu(script = script)).use { server ->
            server.platform.commands.run(server.player(), "menu")
            assertEquals(emptyList(), server.errors.map { it.message })
            assertEquals(listOf("from the file\t5", "coin\ttrue\t3\tb\tnil", "kept\ttrue\t2", "cleared\tnil", "none\tnil"), server.logs)
        }
    }

    private fun pressEscape(server: TestServer, player: FakePlayer) {
        server.platform.menus.closeAny(player.ref.uuid)
    }

    @Test
    fun `rows, several items at once, filling, the cursor and a refresh`() {
        val script = """
            do
              log("rows", this:rows(), this:size())
              log(this:set_items({ [1] = { kind = "minecraft:paper" }, [2] = { kind = "minecraft:gold_ingot", count = 3 } }))
              log(select(2, pcall(this.set_items, this, { [1] = { kind = "minecraft:paper" }, [9] = { kind = "minecraft:paper" } })):match("slot 9") ~= nil)
              log(select(2, pcall(this.set_items, this, { [3] = { kind = "minecraft:nope" } })):match("items%[3%]") ~= nil)
              log(this:item(3) == nil, "left alone after a mistake")
              log(this:fill({ kind = "minecraft:barrier", hide_tooltip = true }, { 3, 5 }))
              log(this:item(3).kind, this:item(4).kind, tostring(this:item(6)))
              log(this:fill({ kind = "minecraft:paper" }))
              log(this:item(6).kind, this:item(0).kind)
              log(select(2, pcall(this.fill, this, { kind = "minecraft:paper" }, { 12 })):match("slot 12") ~= nil)
            end
            this:on("click", function(event)
              log("cursor", tostring(this:cursor_item(event.player)))
              log(this:set_cursor_item(event.player, { kind = "minecraft:diamond", count = 2 }))
              log("cursor", this:cursor_item(event.player).kind, this:cursor_item(event.player).count)
              log(this:refresh())
            end)
        """
        TestServer(menu(script = script)).use { server ->
            val alex = server.player("Alex")
            val sam = server.player("Sam")
            server.platform.commands.run(alex, "menu")
            server.platform.menus.click(alex, 0)
            val window = server.platform.menus.of(alex)!!
            assertEquals(
                listOf(
                    "rows\t1\t9",
                    "true",
                    "true",
                    "true",
                    "true\tleft alone after a mistake",
                    "true",
                    "minecraft:barrier\tminecraft:diamond\tnil",
                    "true",
                    "minecraft:paper\tminecraft:bread",
                    "true",
                    "cursor\tnil",
                    "true",
                    "cursor\tminecraft:diamond\t2",
                    "true"
                ),
                server.logs
            )
            assertEquals(1, window.refreshes)
            // Only a viewer has a cursor here.
            val menu = server.runtime.session.menus.windowOf(server.platform.menus.viewing(alex.ref.uuid)!!)!!
            assertFalse(server.runtime.session.menus.setCursor(menu, sam.ref.uuid, null))
        }
    }

    @Test
    fun `an attribute or enchantment the server doesn't have is an error at the script's line`() {
        val script = """
            nf.commands.register("give", function(event)
              event.player:give_item({ kind = "minecraft:paper", enchantments = { sharpness = 1, ["minecraft:sharpnes"] = 2 } })
            end)
            nf.commands.register("modify", function(event)
              local ok, err = pcall(function()
                event.player:give_item({
                  kind = "minecraft:paper",
                  attribute_modifiers = { { attribute = "attack_damag", amount = 1, operation = "add_value" } },
                })
              end)
              log(err)
              log(event.player:give_item({ kind = "minecraft:paper", enchantments = { unbreaking = 1 } }))
            end)
        """
        TestServer(mapOf("modules/m/init.lua" to script)).use { server ->
            val alex = server.player()
            server.platform.commands.run(alex, "give")
            server.platform.commands.run(alex, "modify")
            val error = server.errors.single()
            assertEquals("module m: /give failed: item: Minecraft 26.3 has no enchantment \"minecraft:sharpnes\"", error.message)
            assertEquals(2, error.source?.line)
            assertEquals(listOf("modules/m/init.lua:6: item: Minecraft 26.3 has no attribute \"minecraft:attack_damag\"", "0"), server.logs)
        }
    }

    @Test
    fun `item components round-trip through Lua`() {
        val script = """
            do
              this:set_item(1, {
                kind = "minecraft:paper",
                max_stack_size = 16,
                rarity = "epic",
                attribute_modifiers = {
                  { attribute = "minecraft:attack_damage", amount = 6, operation = "add_value", slot = "main_hand" },
                  { id = "shop:speed", attribute = "movement_speed", amount = 0.1, operation = "add_multiplied_total" },
                },
                can_break = { "minecraft:stone" },
                can_place_on = { "minecraft:glass" },
                food = { nutrition = 4, saturation = 2.5, can_always_eat = true },
                cooldown = { seconds = 2, group = "shop:pearls" },
              })
              local item = this:item(1)
              log(item.max_stack_size, item.rarity, #item.attribute_modifiers, item.attribute_modifiers[1].slot, item.attribute_modifiers[2].id)
              log(item.can_break[1], item.can_place_on[1], item.food.nutrition, item.food.saturation, item.food.can_always_eat, item.cooldown.seconds, item.cooldown.group)
            end
        """
        TestServer(menu(script = script)).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "menu")
            assertEquals(
                listOf("16\tepic\t2\tmain_hand\tshop:speed", "minecraft:stone\tminecraft:glass\t4\t2.5\ttrue\t2.0\tshop:pearls"),
                server.logs
            )
            val def = server.platform.menus.of(alex)!!.slots[1]!!.def
            assertEquals(16, def.maxStackSize)
            assertEquals(dev.netherforge.format.item.AttributeOperation.ADD_VALUE, def.attributeModifiers!![0].operation)
            assertEquals(2.0, def.cooldown!!.seconds)
        }
    }

    @Test
    fun `a hold and its use effects read back as they were written`() {
        val script = """
            do
              this:set_item(0, { kind = "minecraft:stick", hold = true })
              this:set_item(1, { kind = "minecraft:stick", hold = true, use_effects = { can_sprint = false } })
              this:set_item(2, { kind = "minecraft:bread", use_effects = { speed_multiplier = 1, can_sprint = true } })
              local held, slowed, bread = this:item(0), this:item(1), this:item(2)
              log(held.hold, held.use_effects)
              log(slowed.hold, slowed.use_effects.can_sprint, slowed.use_effects.speed_multiplier)
              log(bread.hold, bread.use_effects.speed_multiplier, bread.use_effects.can_sprint)
            end
        """
        TestServer(menu(script = script)).use { server ->
            server.platform.commands.run(server.player("Alex"), "menu")
            assertEquals(listOf("true\tnil", "true\tfalse\tnil", "nil\t1.0\ttrue"), server.logs)
        }
    }

    @Test
    fun `a hold with an animation reads back as the table it was, and keeps a hold's use effects`() {
        val script = """
            do
              this:set_item(0, { kind = "minecraft:stick", hold = { animation = "trident" } })
              this:set_item(1, { kind = "minecraft:stick", hold = { animation = "bow" }, use_effects = { can_sprint = false } })
              this:set_item(2, { kind = "minecraft:stick", hold = { animation = "none" } })
              this:set_item(3, { kind = "minecraft:stick", hold = {} })
              local trident, bow, none, empty = this:item(0), this:item(1), this:item(2), this:item(3)
              log(type(trident.hold), trident.hold.animation, trident.use_effects)
              log(bow.hold.animation, bow.use_effects.can_sprint, bow.use_effects.speed_multiplier)
              log(none.hold, empty.hold)
            end
        """
        TestServer(menu(script = script)).use { server ->
            server.platform.commands.run(server.player("Alex"), "menu")
            assertEquals(listOf("table\ttrident\tnil", "bow\tfalse\tnil", "true\ttrue"), server.logs)
            val slots = server.platform.menus.of(server.player("Alex"))!!.slots
            assertEquals(
                dev.netherforge.format.item.HoldDef(animation = dev.netherforge.format.item.UseAnimation("trident")),
                slots[0]!!.def.hold
            )
            assertEquals(dev.netherforge.format.item.UseEffectsDef(canSprint = false), slots[1]!!.def.useEffects)
        }
    }

    @Test
    fun `bad item components are errors`() {
        fun modifier(fields: String) =
            "{ kind = \"minecraft:paper\", attribute_modifiers = { { attribute = \"minecraft:armor\", $fields } } }"
        for ((line, message) in listOf(
            // Named in Lua's spelling, not the file's (maxStackSize).
            "{ kind = \"minecraft:paper\", max_stack_size = 100 }" to "max_stack_size must be between 1 and 99",
            "{ kind = \"minecraft:paper\", max_stack_size = 2, damage = 1 }" to "durability can't stack: max_stack_size must be 1",
            "{ kind = \"minecraft:paper\", count = 20, max_stack_size = 16 }" to "count can't be more than max_stack_size",
            "{ kind = \"minecraft:paper\", item_model = \"ruby\" }" to "item_model: \"ruby\" isn't a reference to an item model",
            "{ kind = \"minecraft:bread\", food = { nutrition = 2, saturation = 1, eat_seconds = 0 } }" to
                "eat_seconds must be more than 0",
            "{ kind = \"minecraft:paper\", rarity = \"legendary\" }" to "item.rarity must be one of",
            modifier("amount = 1, operation = \"add\"") to "operation must be one of",
            modifier("operation = \"add_value\"") to "needs an amount",
            modifier("amount = 1, operation = \"add_value\", slots = \"x\"") to "has no field \"slots\"",
            "{ kind = \"minecraft:paper\", can_break = { \"minecraft:obsidian\" } }" to "has no block \"minecraft:obsidian\"",
            "{ kind = \"minecraft:paper\", food = { nutrition = 2 } }" to "needs a saturation",
            "{ kind = \"minecraft:paper\", cooldown = { seconds = 0 } }" to "more than 0",
            "{ kind = \"minecraft:stick\", hold = true, food = { nutrition = 1, saturation = 0 } }" to "can't be eaten too",
            "{ kind = \"minecraft:stick\", hold = { animation = \"bow\" }, food = { nutrition = 1, saturation = 0 } }" to
                "can't be eaten too",
            "{ kind = \"minecraft:stick\", hold = { animation = \"tridnet\" } }" to
                "\"tridnet\" isn't a use animation: did you mean \"trident\"?",
            "{ kind = \"minecraft:stick\", hold = { pose = \"bow\" } }" to "has no field \"pose\"",
            "{ kind = \"minecraft:stick\", hold = \"bow\" }" to "item.hold must be a boolean or a table",
            "{ kind = \"minecraft:stick\", use_effects = { speed_multiplier = 2 } }" to "speed_multiplier must be from 0 to 1",
            "{ kind = \"minecraft:stick\", use_effects = { sprint = true } }" to "has no field \"sprint\""
        )) {
            TestServer(menu(script = "do\n  this:set_item(0, $line)\nend")).use { server ->
                server.platform.commands.run(server.player(), "menu")
                val error = server.errors.single()
                assertTrue(message in error.message, "${error.message} should say $message")
            }
        }
    }

    @Test
    fun `a dispenser's centred title moves the skin back, measured with the default font`() {
        val extra = mapOf(
            "menus/drop/menu.json" to """{ "type": "dispenser", "title": "AB", "skin": "ui/shop", "shared": true }""",
            "menus/bare/menu.json" to """{ "type": "dispenser", "skin": "ui/shop", "shared": true }"""
        )
        val font = mapOf("fonts/default.json" to """{ "minecraft": "26.3", "advances": { "65": 6, "66": 6 } }""")
        fun prefixOf(server: TestServer, shift: Int): String {
            val shop = server.runtime.session.snapshot.compiledResourcePacks.getValue("ui").skins.getValue("shop")
            return "<white><font:showcase:ui/gui>${PackFonts.offset(
                -8 + shift
            ) + shop.char + PackFonts.offset(8 - shift - 177)}</font></white>"
        }
        TestServer(TestServer.example("showcase") + extra + font).use { server ->
            val drop = server.platform.menus.windows.getValue(server.runtime.session.menus.window("drop")!!.uuid)
            // "AB" is 12 wide: the game starts it at (176 - 12) / 2 = 82, so the skin moves back 74.
            assertEquals(prefixOf(server, -74) + "AB", drop.title)
            val bare = server.platform.menus.windows.getValue(server.runtime.session.menus.window("bare")!!.uuid)
            assertEquals(prefixOf(server, -80), bare.title, "no words: centred on nothing, no font needed")
            assertTrue(server.runtime.currentProblems().none { it.code == "font.needed" })
        }
        TestServer(TestServer.example("showcase") + extra).use { server ->
            val drop = server.platform.menus.windows.getValue(server.runtime.session.menus.window("drop")!!.uuid)
            assertEquals(prefixOf(server, 0) + "AB", drop.title, "without the font, left where it falls")
            assertEquals(
                listOf("menus/drop/menu.json"),
                server.runtime.currentProblems().filter {
                    it.code == "font.needed"
                }.map { it.file }
            )
        }
    }
}
