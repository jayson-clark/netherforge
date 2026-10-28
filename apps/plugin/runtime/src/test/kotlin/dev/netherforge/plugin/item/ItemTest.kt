package dev.netherforge.plugin.item
import dev.netherforge.format.item.EquipSlot
import dev.netherforge.format.item.EquipmentDef
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.item.ProjectItems
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.BlockRef
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.SpawnSetup
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Project items (`items/<id>/`): stacks of them, their one script, and stale stacks taking a new look. */
class ItemTest {
    private val ruby = """
        {
          "kind": "minecraft:paper",
          "name": "<red>Ruby",
          "lore": ["warm"],
          "itemModel": "ui/ruby",
          "script": { "file": "script.lua" }
        }
    """.trimIndent()

    private val pack = """{ "items": { "ruby": { "texture": "item/ruby.png" } } }"""

    private fun files(script: String = "", module: String = "", item: String = ruby) = mapOf(
        "items/ruby/item.json" to item,
        "items/ruby/script.lua" to script,
        "resource_packs/ui/pack.json" to pack,
        "resource_packs/ui/textures/item/ruby.png" to PNG,
        "modules/t/init.lua" to module
    )

    /** A server running [files] with Alex online before it starts. */
    private fun server(files: Map<String, Any>): TestServer = TestServer(files, start = false).also {
        it.player("Alex")
        it.start()
    }

    private val TestServer.alex get() = platform.players.byId.values.first()

    private fun TestServer.slots(ref: InventoryRef = InventoryRef.Player(alex.ref.uuid)) = platform.inventories.slots(ref)!!

    private fun stack(id: String, count: Int = 1) = ItemData(ItemDef(item = ResourceRef(id), count = count))

    @Test
    fun `only a project bow with drawWithoutAmmo draws without ammo`() {
        val server = server(
            files(item = """{ "kind": "minecraft:bow", "drawWithoutAmmo": true }""") +
                mapOf("items/plain/item.json" to """{ "kind": "minecraft:bow" }""")
        )
        assertTrue(server.runtime.events.drawsWithoutAmmo(stack("ruby")))
        assertFalse(server.runtime.events.drawsWithoutAmmo(stack("plain")), "a bow without the flag still needs ammo")
        assertFalse(server.runtime.events.drawsWithoutAmmo(ItemData(ItemDef(kind = "minecraft:bow"))), "a bow that isn't a project item")
    }

    @Test
    fun `scripts make stacks of a project item, and every item table can name one`() {
        val module = """
            ${LuaChecks.HELPERS}
            nf.commands.register("run", function()
              local made = nf.items.create("ruby")
              check("kind", made.kind, "minecraft:paper")
              check("item", made.item, "ruby")
              check("name", made.name, "<red>Ruby")
              check("model", made.item_model, "ui/ruby")
              check("count", made.count, 1)
              local more = nf.items.create("ruby", { count = 3, lore = { "50 coins" }, data = { found = vec3(1, 2, 3) } })
              check("override count", more.count, 3)
              check("override lore", more.lore[1], "50 coins")
              check("override data", more.data.found, vec3(1, 2, 3))
              check("override keeps name", more.name, "<red>Ruby")
              check("id", nf.items.id(more), "ruby")
              check("no id", nf.items.id({ kind = "minecraft:paper" }), nil)
              local item = nf.items.get("ruby")
              check("get", item and item:id(), "ruby")
              check("same handle", nf.items.get("ruby"), item)
              check("exists", item:exists(), true)
              check("missing", nf.items.get("ghost"), nil)
              check("all", #nf.items.all(), 1)
              check("handle create", item:create({ count = 2 }).count, 2)
              fails("unknown", function() nf.items.create("ghost") end, "there's no item \"ghost\"")
              fails("kind override", function() nf.items.create("ruby", { kind = "minecraft:stone" }) end, "overrides can't say kind")
              fails("typo override", function() nf.items.create("ruby", { cuont = 2 }) end, "items have no field \"cuont\"")

              local alex = nf.players.get("Alex")
              local inventory = alex:inventory()
              alex:give_item({ item = "ruby", count = 2 })
              local given = inventory:item(0)
              check("given kind", given.kind, "minecraft:paper")
              check("given name", given.name, "<red>Ruby")
              check("given item", given.item, "ruby")
              check("given count", given.count, 2)
              inventory:set_item(5, { kind = "minecraft:paper", item = "ruby", name = "<gold>Shiny" })
              check("own name", inventory:item(5).name, "<gold>Shiny")
              check("count by item", inventory:count_item({ item = "ruby" }), 3)
              check("plain paper isn't a ruby", inventory:count_item({ kind = "minecraft:paper", item = "ruby" }), 3)
              fails("unknown in a table", function() alex:give_item({ item = "ghost" }) end, "item.item: there's no item \"ghost\"")
              fails("wrong kind", function() alex:give_item({ kind = "minecraft:bread", item = "ruby" }) end, "is a minecraft:paper, not a minecraft:bread")
              fails("neither", function() alex:give_item({ count = 2 }) end, "an item needs a kind")
              log("done")
            end)
        """.trimIndent()
        server(files(module = module)).use { server ->
            server.platform.commands.runConsole("run")
            assertEquals(listOf("done"), server.output())
            val held = server.slots()[0]!!
            assertEquals(
                ProjectItems.hash(
                    ItemDef(kind = "minecraft:paper", name = "<red>Ruby", lore = listOf("warm"), itemModel = ResourceRef("ui/ruby"))
                ),
                held.look
            )
        }
    }

    @Test
    fun `a project item worn as equipment makes stacks that carry it, and scripts read and write it`() {
        val module = """
            ${LuaChecks.HELPERS}
            nf.commands.register("run", function()
              local made = nf.items.create("crown")
              check("asset", made.equipment.asset, "gear/ruby")
              check("slot", made.equipment.slot, "head")
              local own = nf.items.create("crown", { equipment = { asset = "gear/ruby", slot = "chest" } })
              check("override slot", own.equipment.slot, "chest")
              local plain = { kind = "minecraft:paper", equipment = { asset = "gear/ruby", slot = "legs" } }
              nf.players.get("Alex"):give_item(plain)
              check("round trip", nf.players.get("Alex"):inventory():item(0).equipment.slot, "legs")
              fails("no such look", function() nf.items.create("crown", { equipment = { asset = "gear/nope", slot = "head" } }) end, "no equipment look \"nope\"")
              fails("bad slot", function() nf.items.create("crown", { equipment = { asset = "gear/ruby", slot = "hand" } }) end, "slot")
              fails("no slot", function() nf.items.create("crown", { equipment = { asset = "gear/ruby" } }) end, "needs a slot")
              fails("typo", function() nf.items.create("crown", { equipment = { asset = "gear/ruby", slot = "head", sound = "x" } }) end, "no field \"sound\"")
              log("done")
            end)
        """.trimIndent()
        val files = mapOf(
            "items/crown/item.json" to """{ "kind": "minecraft:paper", "equipment": { "asset": "gear/ruby", "slot": "head" } }""",
            "resource_packs/gear/pack.json" to """{ "equipment": { "ruby": { "humanoid": "armor/ruby.png" } } }""",
            "resource_packs/gear/textures/armor/ruby.png" to PNG,
            "modules/t/init.lua" to module
        )
        server(files).use { server ->
            server.platform.commands.runConsole("run")
            assertEquals(listOf("done"), server.output())
            val held = server.slots()[0]!!
            assertEquals(EquipmentDef(ResourceRef("gear/ruby"), EquipSlot.LEGS), held.def.equipment)
        }
    }

    @Test
    fun `a menu slot naming a project item shows the item`() {
        val files = files() + mapOf(
            "menus/stall/menu.json" to """{ "shared": true, "slots": { "4": { "item": { "item": "ruby", "count": 2 } } } }"""
        )
        server(files).use { server ->
            val window = server.platform.menus.windows.values.single()
            val slot = window.slots[4]!!
            assertEquals("minecraft:paper", slot.def.kind)
            assertEquals("<red>Ruby", slot.def.name)
            assertEquals(2, slot.def.count)
            assertEquals(ResourceRef("ruby"), slot.def.item)
        }
    }

    @Test
    fun `the item's one script hears what players do with any stack of it, before the player and nf`() {
        val script = """
            log("loaded", this:id())
            this:on("use", function(event) log("use", event.item.item, event.hand) end)
            this:on("interact", function(event) log("interact", event.click) end)
            this:on("consume", function(event) log("consume") event:cancel() end)
            this:on("drop", function(event) log("drop") event:stop() end)
            this:on("pickup", function(event) log("pickup", event.item.count) end)
            this:on("hit", function(event) log("hit", event.entity:kind()) event.amount = event.amount * 2 end)
            this:on("break_block", function(event) log("break", event.state) end)
            this:on("interact_entity", function(event) log("interact entity", event.hand) end)
        """.trimIndent()
        val module = """
            nf.on("player_use_item", function(event) log("nf use", tostring(event.cancelled)) end)
            nf.on("player_consume_item", function(event) log("nf consume", tostring(event.cancelled)) end)
            nf.on("player_drop_item", function(event) log("nf drop") end)
            nf.on("entity_damage", function(event) log("nf damage", event.amount) end)
            nf.on("block_break", function(event) log("nf break") end)
            nf.on("player_interact_entity", function(event) log("nf interact entity") end)
        """.trimIndent()
        server(files(script, module)).use { server ->
            val runtime = server.runtime
            val alex = server.alex
            val gem = server.platform.stack(stack("ruby"))!!
            val paper = server.platform.stack(ItemData(ItemDef("minecraft:paper")))!!
            assertFalse(runtime.events.playerUseItem(alex.ref, gem, "main_hand"))
            assertFalse(runtime.events.playerUseItem(alex.ref, paper, "main_hand"))
            runtime.events.playerInteract(alex.ref, ClickButton.LEFT, null, null, gem, "main_hand")
            assertTrue(runtime.events.playerConsumeItem(alex.ref, gem), "the item's handler cancelled it")
            assertFalse(runtime.events.playerDropItem(alex.ref, gem))
            val dropped = server.platform.worldEntities.spawnItem("world", Vec3(0.0, 64.0, 0.0), stack("ruby", 4))!!
            runtime.events.playerPickupItem(alex.ref, server.platform.worldEntities.item(dropped)!!, dropped)

            // What's held is looked up for a hit, a block broken and an entity clicked.
            server.slots()[0] = gem
            val zombie = server.platform.worldEntities.spawn("minecraft:zombie", Location("world", 2.0, 64.0, 0.0), SpawnSetup())!!
            assertEquals(6.0, runtime.events.entityDamaged(zombie, 3.0, "entity_attack", alex.ref.uuid))
            assertEquals(3.0, runtime.events.entityDamaged(zombie, 3.0, "fall", null), "not an attack with it")
            val stone = BlockRef("world", 1, 63, 2, "minecraft:stone", "minecraft:stone")
            server.breaks(alex, stone)
            server.platform.worldEntities.interact(alex, zombie)
            server.slots()[0] = paper
            assertEquals(3.0, runtime.events.entityDamaged(zombie, 3.0, "entity_attack", alex.ref.uuid), "paper isn't a ruby")

            assertEquals(
                listOf(
                    "loaded\truby",
                    "use\truby\tmain_hand",
                    "nf use\tfalse",
                    "nf use\tfalse",
                    "interact\tleft",
                    "consume",
                    "nf consume\ttrue",
                    "drop",
                    "pickup\t4",
                    "hit\tminecraft:zombie",
                    "nf damage\t6.0",
                    "nf damage\t3.0",
                    "break\tminecraft:stone",
                    "nf break",
                    "interact entity\tmain_hand",
                    "nf interact entity",
                    "nf damage\t3.0"
                ),
                server.logs
            )
            assertEquals(emptyList(), server.errors)
        }
    }

    @Test
    fun `a stale stack takes the item's new look when it's seen, and keeps what is its own`() {
        server(files()).use { server ->
            val alex = server.alex
            val old = ItemData(
                ItemDef(
                    kind = "minecraft:paper",
                    item = ResourceRef("ruby"),
                    count = 5,
                    name = "<red>Old ruby",
                    damage = 1,
                    data = mapOf("found" to JsonPrimitive("cave"))
                ),
                look = "0000000000000000"
            )
            val ghost = ItemData(ItemDef(kind = "minecraft:paper", item = ResourceRef("ghost"), name = "Boo"), look = "1")
            server.slots()[3] = old
            server.slots()[4] = ghost

            // Joining: their own inventory.
            server.platform.raise.playerJoin(GameEvent.PlayerJoin(alex.ref, false, null))
            val joined = server.slots()[3]!!
            assertEquals("<red>Ruby", joined.def.name)
            assertEquals(listOf("warm"), joined.def.lore)
            assertEquals(5, joined.def.count)
            assertEquals(1, joined.def.damage)
            assertEquals(mapOf("found" to JsonPrimitive("cave")), joined.def.data)
            assertEquals(server.runtime.session.items.look("ruby")!!.hash, joined.look)
            assertEquals(ghost, server.slots()[4], "an item the project doesn't have is left alone")

            // A reload of the item: everyone online.
            server.write("items/ruby/item.json", ruby.replace("<red>Ruby", "<dark_red>Ruby"))
            val result = server.reload("items/ruby/item.json").resources.single()
            assertEquals("item:ruby", result.label)
            assertTrue(result.ok)
            assertEquals(1, result.reattached)
            assertEquals("<dark_red>Ruby", server.slots()[3]!!.def.name)
            assertEquals(5, server.slots()[3]!!.def.count)

            // Opening a chest: what's in it.
            val chest = InventoryRef.Block("world", 4, 64, 4)
            server.platform.blocks.set("world", 4, 64, 4, "minecraft:chest", false)
            server.slots(chest)[0] = old
            server.platform.raise.playerOpenInventory(GameEvent.PlayerInventory(alex.ref, "chest", chest, null))
            assertEquals("<dark_red>Ruby", server.slots(chest)[0]!!.def.name)

            // Picking up: their inventory, a tick later.
            server.slots()[7] = old
            val dropped = server.platform.worldEntities.spawnItem("world", Vec3(0.0, 64.0, 0.0), stack("ruby"))!!
            server.runtime.events.playerPickupItem(alex.ref, server.platform.worldEntities.item(dropped)!!, dropped)
            assertEquals("<red>Old ruby", server.slots()[7]!!.def.name, "not until the next tick")
            server.tick()
            assertEquals("<dark_red>Ruby", server.slots()[7]!!.def.name)
        }
    }

    @Test
    fun `reloading an item restarts its script, and deleting it drops its handlers`() {
        val module = """
            nf.commands.register("check", function()
              local item = nf.items.get("ruby")
              log("check", tostring(item ~= nil), tostring(item and item:exists()))
            end)
        """.trimIndent()
        server(files("log(\"v1\")", module)).use { server ->
            server.write("items/ruby/script.lua", "log(\"v2\")\nthis:on(\"use\", function() log(\"used\") end)")
            assertTrue(server.reload("items/ruby/script.lua").resources.single().ok)
            server.runtime.events.playerUseItem(server.alex.ref, server.platform.stack(stack("ruby"))!!, "main_hand")
            server.write("items/ruby/item.json", """{ "kind": "not an id!" }""")
            val broken = server.reload("items/ruby/item.json").resources.single()
            assertFalse(broken.ok, "the last good version keeps running")
            server.platform.commands.runConsole("check")
            server.delete("items/ruby/item.json")
            server.delete("items/ruby/script.lua")
            server.reload("items/ruby/item.json")
            server.platform.commands.runConsole("check")
            server.runtime.events.playerUseItem(
                server.alex.ref,
                ItemData(ItemDef("minecraft:paper", item = ResourceRef("ruby"))),
                "main_hand"
            )
            assertEquals(listOf("v1", "v2", "used", "check\ttrue\ttrue", "check\tfalse\tnil"), server.logs)
            assertNull(server.runtime.session.items.look("ruby"))
        }
    }

    private companion object {
        /** A 1×1 PNG, for the pack's item texture. */
        val PNG: ByteArray = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        )
    }
}
