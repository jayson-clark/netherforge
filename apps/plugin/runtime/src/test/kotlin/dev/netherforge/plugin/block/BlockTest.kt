package dev.netherforge.plugin.block
import dev.netherforge.format.block.BlockCarrier
import dev.netherforge.format.block.BlockFacing
import dev.netherforge.format.game.BlockState
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.BlockRef
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.PickAnswer
import dev.netherforge.plugin.testkit.BlockAt
import dev.netherforge.plugin.testkit.FakePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The project's blocks (W4.1) on the fake server: held as note block states with a record, placed by an item or
 * a script, clicked, mined (by the server, the player's own game slowed), broken for their loot table's drops, ticking, drawn by
 * centities, found again when a chunk loads, and moved when the project's blocks change.
 */
class BlockTest {
    private val ore = """
        { "model": "ui/ore", "hardness": 3, "tool": "pickaxe", "requiresTool": true, "drops": "ore_loot", "tick": 2,
          "sounds": { "place": "ui/thud", "break": "ui/crumble" }, "script": { "file": "script.lua" } }
    """.trimIndent()

    private val files = mapOf(
        "resource_packs/ui/pack.json" to """{ "blocks": { "ore": { "texture": "ore.png" } } }""",
        "resource_packs/ui/textures/ore.png" to ByteArray(0),
        "resource_packs/ui/sounds/thud.ogg" to ByteArray(0),
        "resource_packs/ui/sounds/crumble.ogg" to ByteArray(0),
        "loot/ore_loot.json" to
            """{ "pools": { "main": { "entries": [{ "type": "item", "item": { "kind": "minecraft:diamond" }, "count": 2 }] } } }""",
        "centities/lamp/centity.json" to
            """{ "nodes": { "light": { "display": { "type": "block", "block": "minecraft:stone" } } } }""",
        "blocks/ore/block.json" to ore,
        "blocks/ore/script.lua" to """
            local this = this --[[@as ProjectBlock]]
            this:on("place", function(event) log("place " .. this:id() .. " " .. tostring(event.player ~= nil)) end)
            this:on("click", function(event) log("click " .. event.click .. " " .. event.face) end)
            this:on("break", function(event)
              log("break " .. #event.drops .. " " .. tostring(event.drops[1] and event.drops[1].count))
              for _, item in ipairs(event.drops) do item.count = item.count * 2 end
            end)
            this:on("tick", function(event) log("tick " .. tostring(event.block:data().age or 0)); event.block:data().age = (event.block:data().age or 0) + 1 end)
        """.trimIndent(),
        "blocks/lamp/block.json" to """{ "centity": "lamp" }""",
        "items/ore_item/item.json" to """{ "kind": "minecraft:paper", "block": "ore" }"""
    )

    private val ground = BlockRef("world", 0, 63, 0, "minecraft:stone", "minecraft:stone")

    private fun server(extra: Map<String, Any> = emptyMap()) = TestServer(files + extra).also {
        it.platform.players.add("Alex")
        assertEquals(emptyList(), it.errors.map { error -> error.message })
        assertEquals(
            emptyList(),
            it.runtime.session.problems().filter { problem ->
                problem.severity ==
                    dev.netherforge.format.Severity.ERROR
            }.map { problem -> "${problem.file} ${problem.message}" }
        )
    }

    private val TestServer.alex get() = platform.players.byId.values.first()

    private fun TestServer.state(x: Int, y: Int, z: Int) = platform.worlds.state("world", x, y, z)

    private fun TestServer.custom() = runtime.session.customBlocks

    private fun TestServer.hold(item: ItemData?) = platform.worldEntities.setEquipment(alex.ref.uuid, "main_hand", item)

    private fun TestServer.dropped() = platform.worldEntities.list("world").filter { it.kind == "minecraft:item" }
        .mapNotNull { platform.worldEntities.item(it.id) }

    private val oreItem get() = ItemData(ItemDef(kind = "minecraft:paper", item = ResourceRef("ore_item"), count = 3))

    @Test
    fun `the server is told to leave note blocks alone, and each block has a state of its own`() {
        server().use { server ->
            assertTrue(server.platform.blocks.frozen)
            val ore = BlockState.parse(assertNotNull(server.custom().stateOf("ore")))!!
            val lamp = BlockState.parse(assertNotNull(server.custom().stateOf("lamp")))!!
            // In name order, from the rarest states: the game's last instrument, powered, the first notes.
            assertEquals("custom_head", lamp.properties["instrument"])
            assertEquals(mapOf("instrument" to "custom_head", "note" to "1", "powered" to "true"), ore.properties)
            assertEquals("minecraft:note_block", ore.id)
            assertEquals("lamp", server.custom().nameOfState(lamp.toString()))
        }
    }

    @Test
    fun `a script places one, which is a note block to the server and a custom block to scripts`() {
        server(
            mapOf(
                "modules/t/init.lua" to """
                    local world = nf.worlds.default()
                    local at = world:block(vec3(4, 64, 4))
                    local placed = nf.blocks.get("ore"):place(at:location())
                    log("placed " .. tostring(placed ~= nil) .. " " .. placed:id() .. " " .. placed:project():id())
                    log("kind " .. at:kind() .. " custom " .. tostring(at:custom() ~= nil) .. " " .. tostring(world:block(vec3(5, 64, 4)):custom()))
                    placed:data().owner = "alex"
                    log("data " .. at:data().owner)
                    local lamp = nf.blocks.get("lamp"):place(world:block(vec3(6, 64, 4)):location())
                    log("lamp " .. tostring(lamp:id()) .. " " .. #nf.centities.all())
                    log("all " .. #nf.blocks.all() .. " " .. tostring(nf.blocks.get("nothing")))
                """.trimIndent()
            )
        ).use { server ->
            assertEquals(emptyList(), server.errors.map { it.message })
            val logs = server.logs
            assertEquals("placed true ore ore", logs[0])
            assertTrue(logs[1].startsWith("kind minecraft:note_block custom true nil"), logs[1])
            assertEquals("data alex", logs[2])
            assertEquals("lamp lamp 1", logs[3])
            assertEquals("all 2 nil", logs[4])
            assertEquals(server.custom().stateOf("ore"), server.state(4, 64, 4))
            // What the server remembers: the lamp's record, naming its centity instance; the ore is known by its state,
            // which the chunk's legend says is the ore.
            val records = server.platform.blocks.records.values.toList()
            assertEquals(1, records.size, records.toString())
            assertTrue(records.single().startsWith("""{"id":"lamp","centity":"""), records.toString())
            val legend = server.platform.blocks.legends[Triple("world", 0, 0)]!!
            assertTrue(""""${server.custom().stateOf("ore")}":"ore"""" in legend, legend)
            val instance = server.runtime.session.centities.all().single()
            assertEquals(6.5, instance.anchor.x)
            assertEquals(64.0, instance.anchor.y)
        }
    }

    @Test
    fun `an item places its block against the face clicked, is used up, and a script may refuse it`() {
        server().use { server ->
            server.hold(oreItem)
            val placed = server.runtime.events.playerInteract(
                server.alex.ref,
                ClickButton.RIGHT,
                ground.copy(x = 5),
                "up",
                oreItem,
                "main_hand"
            )
            // Cancelled: the item is never used as the item it is.
            assertTrue(placed)
            assertEquals(server.custom().stateOf("ore"), server.state(5, 64, 0))
            assertEquals(2, server.platform.worldEntities.equipment(server.alex.ref.uuid, "main_hand")?.def?.count)
            assertEquals(listOf("place ore true"), server.logs)
            assertEquals(1, server.platform.blocks.placed.size)
            assertTrue("Alex world 5 64 0" in server.platform.blocks.placed.single(), server.platform.blocks.placed.single())
            assertEquals("ore", server.custom().at("world", 5, 64, 0))
            // A sound of the block's own, the project's pack's.
            assertTrue(server.platform.sounds.played.any { it.contains("test:ui/thud") }, server.platform.sounds.played.toString())

            // Something standing there, or a protection plugin, keeps it from being placed, and keeps the item.
            server.platform.blocks.occupied += BlockAt("world", 6, 64, 0)
            val blocked = BlockRef("world", 6, 63, 0, "minecraft:stone", "minecraft:stone")
            assertTrue(server.runtime.events.playerInteract(server.alex.ref, ClickButton.RIGHT, blocked, "up", oreItem, "main_hand"))
            assertEquals("minecraft:air", server.state(6, 64, 0))
            server.platform.blocks.refusedPlacers += server.alex.ref.uuid
            val other = BlockRef("world", 7, 63, 0, "minecraft:stone", "minecraft:stone")
            assertTrue(server.runtime.events.playerInteract(server.alex.ref, ClickButton.RIGHT, other, "up", oreItem, "main_hand"))
            assertEquals("minecraft:air", server.state(7, 64, 0))
            assertEquals(2, server.platform.worldEntities.equipment(server.alex.ref.uuid, "main_hand")?.def?.count)

            // Creative players keep the item.
            server.platform.blocks.refusedPlacers.clear()
            server.platform.players.byId.getValue(server.alex.ref.uuid).gameMode = "creative"
            val third = BlockRef("world", 8, 63, 0, "minecraft:stone", "minecraft:stone")
            server.runtime.events.playerInteract(server.alex.ref, ClickButton.RIGHT, third, "up", oreItem, "main_hand")
            assertEquals("ore", server.custom().at("world", 8, 64, 0))
            assertEquals(2, server.platform.worldEntities.equipment(server.alex.ref.uuid, "main_hand")?.def?.count)
        }
    }

    @Test
    fun `clicking one is heard by its script after the player's events, and a click can be cancelled`() {
        server().use { server ->
            server.custom().place("ore", "world", 4, 64, 4)
            val block = BlockRef("world", 4, 64, 4, "minecraft:note_block", server.state(4, 64, 4))
            assertFalse(server.runtime.events.playerInteract(server.alex.ref, ClickButton.LEFT, block, "north", null, "main_hand"))
            assertFalse(server.runtime.events.playerInteract(server.alex.ref, ClickButton.RIGHT, block, "up", null, "main_hand"))
            assertEquals(listOf("click left north", "click right up"), server.logs)
        }
    }

    @Test
    fun `breaking rolls the loot table for the tool, scripts change it, and the block goes with its sound`() {
        server().use { server ->
            val state = server.custom().stateOf("ore")!!
            fun place(x: Int) = assertTrue(server.custom().place("ore", "world", x, 64, 0)).also { server.platform.sounds.played.clear() }
            fun ref(x: Int) = BlockRef("world", x, 64, 0, "minecraft:note_block", state)

            // Bare handed, a block that needs a pickaxe drops nothing, but it still breaks.
            place(0)
            assertNull(server.runtime.events.blockBreak(server.alex.ref, ref(0), { emptyList() }, 0))
            assertEquals("minecraft:air", server.state(0, 64, 0))
            assertEquals(emptyList(), server.dropped())
            assertNull(server.custom().at("world", 0, 64, 0))
            assertEquals(emptyList(), server.platform.blocks.records.keys.toList())
            assertEquals(listOf("break 0 nil"), server.logs)
            assertTrue(server.platform.sounds.played.single().contains("crumble"), server.platform.sounds.played.toString())
            // Particles of the block's own state, which the pack draws as its model.
            assertEquals(
                state,
                (server.platform.particles.sent.last().second.single().data as dev.netherforge.format.particle.BlockData).state
            )

            // With the pickaxe the roll's drops are the script's to change: doubled.
            place(1)
            server.hold(ItemData(ItemDef(kind = "minecraft:iron_sword")))
            server.platform.blocks.breakSpeeds["minecraft:iron_sword" to "minecraft:stone"] = 6.0
            assertNull(server.runtime.events.blockBreak(server.alex.ref, ref(1), { emptyList() }, 0))
            assertEquals(listOf(4), server.dropped().map { it.def.count })
            assertEquals("minecraft:diamond", server.dropped().single().def.kind)

            // Nothing drops in creative, and a script that cancels keeps the block.
            place(2)
            server.platform.players.byId.getValue(server.alex.ref.uuid).gameMode = "creative"
            assertNull(server.runtime.events.blockBreak(server.alex.ref, ref(2), { emptyList() }, 0))
            assertEquals("minecraft:air", server.state(2, 64, 0))
            assertEquals(1, server.dropped().size)
        }
    }

    @Test
    fun `the server mines one, slowing the player's own game, showing the cracks and breaking it as theirs`() {
        server().use { server ->
            val state = server.custom().stateOf("ore")!!
            server.custom().place("ore", "world", 0, 64, 0)
            val id = server.alex.ref.uuid
            fun modifiers() = server.platform.attributes.modifiers(id, "minecraft:block_break_speed").orEmpty()
            fun start(block: BlockRef, item: ItemData? = null, instant: Boolean = false) =
                GameEvent.BlockStartBreak(server.alex.ref, block, item, instant).also { server.platform.raise.blockStartBreak(it) }
            val mined = BlockRef("world", 0, 64, 0, "minecraft:note_block", state)
            val cracks = server.platform.blocks.cracks

            // Looking at one slows them before they hit it, so their game can't finish it first; looking away doesn't.
            server.platform.blocks.targets[id] = BlockAt("world", 0, 64, 0)
            server.tick(1)
            assertEquals(1e-6 - 1, modifiers().single().amount, 1e-12)
            assertEquals("test:mining", modifiers().single().id)
            server.platform.blocks.targets.remove(id)
            server.tick(1)
            assertEquals(emptyList(), modifiers())

            // A tick of the note block is 1/8 here; with the sword (the right tool: 6 on stone) the ore is 1.6 times that.
            val sword = ItemData(ItemDef(kind = "minecraft:iron_sword"))
            server.platform.blocks.breakSpeeds["minecraft:iron_sword" to "minecraft:stone"] = 6.0
            server.platform.blocks.breakProgresses[id] = 0.125
            server.hold(sword)
            start(mined, sword)
            assertEquals(1, modifiers().size)
            server.tick(3)
            assertEquals(state, server.state(0, 64, 0))
            assertTrue(cracks.isNotEmpty() && cracks.all { it.startsWith("Alex world 0 64 0 ") }, cracks.toString())
            val stages = cracks.map { it.substringAfterLast(' ').toInt() }
            assertEquals(stages.sorted(), stages)
            assertTrue(stages.last() in 3..5, stages.toString())
            // Then it breaks, as the player's: the script hears it and doubles the drops, the cracks go, and so does the slowing.
            server.tick(3)
            assertEquals("minecraft:air", server.state(0, 64, 0))
            assertEquals("-1", cracks.last().substringAfterLast(' '))
            assertTrue(server.logs.contains("break 1 2"), server.logs.toString())
            assertEquals(listOf(4), server.dropped().map { it.def.count })
            server.tick(1)
            assertEquals(emptyList(), modifiers())

            // Letting go takes the cracks away, and it stays.
            server.custom().place("ore", "world", 0, 64, 0)
            start(mined, sword)
            server.tick(2)
            cracks.clear()
            server.runtime.events.blockStopBreak(server.alex.ref, mined)
            assertEquals(listOf("Alex world 0 64 0 -1"), cracks)
            server.tick(10)
            assertEquals(state, server.state(0, 64, 0))

            // A protection plugin refusing the break: it stays, and they mine on from nothing.
            server.platform.blocks.refusedBreakers += id
            start(mined, sword)
            server.tick(6)
            assertEquals(state, server.state(0, 64, 0))
            server.platform.blocks.refusedBreakers.clear()
            server.tick(6)
            assertEquals("minecraft:air", server.state(0, 64, 0))

            // Another block: the player mines as they would.
            server.custom().place("ore", "world", 0, 64, 0)
            start(mined, sword)
            assertEquals(1, modifiers().size)
            start(BlockRef("world", 5, 63, 5, "minecraft:stone", "minecraft:stone"))
            assertEquals(emptyList(), modifiers())

            // A block that breaks at once says so, and the server breaks it on that hit.
            server.write("blocks/ore/block.json", ore.replace("\"hardness\": 3", "\"hardness\": 0"))
            assertTrue(server.reload("blocks/ore/block.json").resources.single().ok)
            val instant = start(BlockRef("world", 0, 64, 0, "minecraft:note_block", server.custom().stateOf("ore")!!))
            assertTrue(instant.instant)
            assertEquals(emptyList(), modifiers())
        }
    }

    @Test
    fun `a block without sounds of its own sounds like the game's blocks of its tool, or of its sound`() {
        val extra = mapOf(
            "blocks/log/block.json" to """{ "tool": "axe" }""",
            "blocks/pane/block.json" to """{ "tool": "axe", "sound": "glass" }""",
            "items/log_item/item.json" to """{ "kind": "minecraft:paper", "block": "log" }"""
        )
        server(extra).use { server ->
            val played = server.platform.sounds.played
            val logItem = ItemData(ItemDef(kind = "minecraft:paper", item = ResourceRef("log_item"), count = 2))
            server.hold(logItem)
            val floor = BlockRef("world", 3, 63, 0, "minecraft:stone", "minecraft:stone")
            assertTrue(server.runtime.events.playerInteract(server.alex.ref, ClickButton.RIGHT, floor, "up", logItem, "main_hand"))
            assertEquals("log", server.custom().at("world", 3, 64, 0))
            assertEquals(listOf("world minecraft:block.wood.place block 1.0 0.8"), played)
            played.clear()
            val log = BlockRef("world", 3, 64, 0, "minecraft:note_block", server.state(3, 64, 0))
            assertNull(server.runtime.events.blockBreak(server.alex.ref, log, { emptyList() }, 0))
            assertEquals(listOf("world minecraft:block.wood.break block 1.0 0.8"), played)

            played.clear()
            server.custom().place("pane", "world", 1, 64, 0)
            val pane = BlockRef("world", 1, 64, 0, "minecraft:note_block", server.state(1, 64, 0))
            assertNull(server.runtime.events.blockBreak(server.alex.ref, pane, { emptyList() }, 0))
            assertEquals(listOf("world minecraft:block.glass.break block 1.0 0.8"), played)
        }
    }

    @Test
    fun `picking one brings the item that places it to hand, not its carrier`() {
        server().use { server ->
            server.custom().place("ore", "world", 0, 64, 0)
            val block = BlockRef("world", 0, 64, 0, "minecraft:note_block", server.state(0, 64, 0))
            fun pick(inventory: List<ItemData?>) = server.runtime.events.pickBlock(server.alex.ref, block, inventory)
            val empty = List<ItemData?>(36) { null }

            // One held anywhere (whatever else it carries) is the one picked.
            assertEquals(PickAnswer(20, null), pick(empty.toMutableList().also { it[20] = oreItem }))
            // None held: nothing in survival, a new one in creative.
            assertEquals(PickAnswer(null, null), pick(empty))
            server.platform.players.byId.getValue(server.alex.ref.uuid).gameMode = "creative"
            val made = pick(empty)?.item
            assertEquals("ore_item", made?.def?.item?.text)
            assertEquals(1, made?.def?.count)
            // Any other block is the game's to pick.
            assertNull(server.runtime.events.pickBlock(server.alex.ref, ground, empty))
        }
    }

    @Test
    fun `a block nobody can break by hand can't be started in survival`() {
        server(mapOf("blocks/bedrock/block.json" to """{ "hardness": -1 }""")).use { server ->
            server.custom().place("bedrock", "world", 0, 64, 0)
            val state = server.custom().stateOf("bedrock")!!
            val start = GameEvent.BlockStartBreak(server.alex.ref, BlockRef("world", 0, 64, 0, "minecraft:note_block", state), null, false)
            assertTrue(server.runtime.events.game.blockStartBreak(start))
            server.platform.players.byId.getValue(server.alex.ref.uuid).gameMode = "creative"
            assertFalse(server.runtime.events.game.blockStartBreak(start))
        }
    }

    @Test
    fun `world blocks reads a box in one call, as kinds, solids or classes, the project's own by id`() {
        server(
            mapOf(
                "modules/t/init.lua" to """
                    local world = nf.worlds.default()
                    world:set_block(vec3(0, 70, 0), "minecraft:stone")
                    world:set_block(vec3(1, 70, 0), "minecraft:water")
                    world:set_block(vec3(1, 71, 0), "minecraft:air")
                    nf.blocks.get("ore"):place(world:block(vec3(0, 71, 0)):location())
                    nf.commands.register("box", function()
                      -- Corners in any order, any point in their blocks.
                      local box = world:blocks(vec3(1.9, 71.5, 0.2), vec3(0, 70, 0))
                      log(box.size_x, box.size_y, box.size_z, tostring(box.corner), #box.cells)
                      log(box.cells[1], box.cells[2], box.cells[3], box.cells[4])
                      local classes = world:blocks(vec3(0, 70, 0), vec3(1, 71, 0), { what = "class" })
                      log(classes.cells[1], classes.cells[2], classes.cells[4])
                      local solid = world:blocks(vec3(0, 70, 0), vec3(1, 71, 0), { what = "solid" })
                      log(solid.cells[1], solid.cells[2], solid.cells[4])
                      local custom = world:blocks(vec3(0, 70, 0), vec3(1, 71, 0), { what = "class", custom = true })
                      log(custom.cells[1], custom.cells[3])
                      local ok, err = pcall(world.blocks, world, vec3(0, 0, 0), vec3(32, 31, 31))
                      log(ok, err:find("more than the 32768", 1, true) ~= nil)
                      log(tostring(world:blocks(vec3(80, 64, 80), vec3(81, 64, 81))))
                    end)
                """.trimIndent()
            )
        ).use { server ->
            server.platform.worlds.unloaded += Triple("world", 5, 5)
            server.platform.commands.runConsole("box")
            assertEquals(emptyList(), server.errors.map { it.message })
            assertEquals(
                listOf(
                    "2\t2\t1\tvec3(0, 70, 0)\t4",
                    // x fastest, then z, then y: (0,70,0), (1,70,0), then (0,71,0), (1,71,0).
                    "minecraft:stone\tminecraft:water\tminecraft:note_block\tminecraft:air",
                    "2\t1\t0",
                    "true\tfalse\tfalse",
                    "2\tore",
                    "false\ttrue",
                    "nil"
                ),
                server.logs
            )
        }
    }

    @Test
    fun `the sweep passes over a chunk nothing changed, and notices a block something else took away`() {
        server(
            mapOf(
                "modules/t/init.lua" to """
                    nf.blocks.get("ore"):place(nf.worlds.default():block(vec3(4, 64, 4)):location())
                    nf.commands.register("there", function()
                      log("there " .. tostring(nf.worlds.default():block(vec3(4, 64, 4)):custom() ~= nil))
                    end)
                """.trimIndent()
            )
        ).use { server ->
            val blocks = server.platform.blocks
            // A sweep reads the chunk once, then only sums it up while nothing changes.
            server.tick(20)
            val finds = blocks.finds
            server.tick(200)
            assertEquals(finds, blocks.finds, "an unchanged chunk isn't read again")
            assertTrue(blocks.fingerprints > 0)
            // Something other than NetherForge (a paste, another plugin) takes it away: the sweep notices.
            server.platform.worlds.blocks[dev.netherforge.plugin.testkit.BlockAt("world", 4, 64, 4)] = "minecraft:stone"
            server.tick(200)
            assertTrue(blocks.finds > finds, "a changed chunk is read again")
            server.platform.commands.runConsole("there")
            assertEquals("there false", server.logs.last())
        }
    }

    @Test
    fun `a ticking block hears tick every interval, with its own data`() {
        server().use { server ->
            server.custom().place("ore", "world", 0, 64, 0)
            server.tick(6)
            assertEquals(emptyList(), server.errors.map { it.message })
            assertEquals(listOf("tick 0", "tick 1", "tick 2"), server.logs.filter { it.startsWith("tick") })
        }
    }

    @Test
    fun `a block drawn by a centity spawns it with the block and takes it away`() {
        server().use { server ->
            assertTrue(server.custom().place("lamp", "world", 8, 64, 8))
            val instance = server.runtime.session.centities.all().single()
            assertEquals(8.5, instance.anchor.x)
            assertEquals(8.5, instance.anchor.z)
            // Mining one drops nothing (no loot table) and the lamp goes with it.
            val state = server.custom().stateOf("lamp")!!
            assertNull(
                server.runtime.events.blockBreak(server.alex.ref, BlockRef("world", 8, 64, 8, "minecraft:note_block", state), {
                    emptyList()
                }, 0)
            )
            assertEquals(emptyList(), server.runtime.session.centities.all())
            // Replaced by a script: the same.
            server.custom().place("lamp", "world", 9, 64, 8)
            assertEquals(1, server.runtime.session.centities.all().size)
            server.custom().replaced("world", 9, 64, 8, "minecraft:stone")
            assertEquals(0, server.runtime.session.centities.all().size)
            assertNull(server.custom().at("world", 9, 64, 8))
        }
    }

    @Test
    fun `a chunk coming into memory brings back what was recorded and adopts carrier states nobody recorded`() {
        server().use { server ->
            val state = server.custom().stateOf("ore")!!
            // A generator wrote a block into the chunk's data; nothing recorded it.
            server.platform.worlds.blocks[BlockAt("world", 40, 70, 40)] = state
            assertNull(server.custom().at("world", 40, 70, 40))
            server.platform.raise.chunkLoad(GameEvent.ChunkLoad("world", 2, 2, true))
            assertEquals("ore", server.custom().at("world", 40, 70, 40))
            // Known by its state from now on: the chunk's legend, no record of its own.
            assertEquals("""{"$state":"ore"}""", server.platform.blocks.legends[Triple("world", 2, 2)])
            assertTrue(server.platform.blocks.records.none { (at, _) -> at.x == 40 })

            // It's one of the chunk's, so it ticks, and goes when the chunk does.
            server.runtime.events.game.chunkUnload(GameEvent.Chunk("world", 2, 2))
            assertNull(server.custom().at("world", 40, 70, 40))
            server.platform.raise.chunkLoad(GameEvent.ChunkLoad("world", 2, 2, false))
            assertEquals("ore", server.custom().at("world", 40, 70, 40))

            // Something else took its place meanwhile: it's forgotten when the chunk next loads, with its data.
            server.platform.blocks.data[BlockAt("world", 40, 70, 40)] = """{"age":4}"""
            server.platform.worlds.blocks[BlockAt("world", 40, 70, 40)] = "minecraft:stone"
            server.runtime.events.game.chunkUnload(GameEvent.Chunk("world", 2, 2))
            server.platform.raise.chunkLoad(GameEvent.ChunkLoad("world", 2, 2, false))
            assertNull(server.custom().at("world", 40, 70, 40))
            assertNull(server.platform.blocks.records.keys.firstOrNull { it.x == 40 })
            assertNull(server.platform.blocks.data[BlockAt("world", 40, 70, 40)])
        }
    }

    @Test
    fun `a block that turns faces who places it, a script turns it, and it keeps facing when its states move`() {
        val chair = mapOf(
            "blocks/chair/block.json" to """{ "model": "ui/ore", "rotation": "horizontal" }""",
            "items/chair_item/item.json" to """{ "kind": "minecraft:paper", "block": "chair" }""",
            "modules/t/init.lua" to """
                nf.commands.register("chair", function()
                  local world = nf.worlds.default()
                  local placed = nf.blocks.get("chair"):place(world:block(vec3(2, 64, 2)):location(), { facing = "east" })
                  log("placed " .. placed:facing())
                  log("turned " .. tostring(placed:set_facing("south")) .. " " .. placed:facing())
                  log("up " .. tostring(placed:set_facing("up")) .. " " .. placed:facing())
                  local ok, err = pcall(function()
                    nf.blocks.get("chair"):place(world:block(vec3(3, 64, 2)):location(), { facing = "up" })
                  end)
                  log("bad " .. tostring(ok) .. " " .. tostring(err:find("can't face up", 1, true) ~= nil))
                  log("plain " .. tostring(nf.blocks.get("ore"):place(world:block(vec3(4, 64, 2)):location()):facing()))
                end)
            """.trimIndent()
        )
        server(chair).use { server ->
            server.platform.commands.runConsole("chair")
            assertEquals(emptyList(), server.errors.map { it.message })
            assertEquals(listOf("placed east", "turned true south", "up false south", "bad false true", "plain nil"), server.logs)
            assertEquals(server.custom().stateOf("chair", BlockFacing.SOUTH), server.state(2, 64, 2))

            // A player looking north places it facing south, at them.
            val chairItem = ItemData(ItemDef(kind = "minecraft:paper", item = ResourceRef("chair_item"), count = 2))
            server.hold(chairItem)
            server.platform.players.byId.getValue(server.alex.ref.uuid).location =
                Location("world", 5.5, 64.0, 9.5, yaw = 180.0, pitch = 10.0)
            server.runtime.events.playerInteract(
                server.alex.ref,
                ClickButton.RIGHT,
                ground.copy(x = 5, z = 5),
                "up",
                chairItem,
                "main_hand"
            )
            assertEquals(server.custom().stateOf("chair", BlockFacing.SOUTH), server.state(5, 64, 5))

            // A block named before it takes the first states: the chairs move to theirs and still face the way they did.
            server.write("blocks/aaa/block.json", "{}")
            assertTrue(server.reload("blocks/aaa/block.json").resources.single().ok)
            assertEquals(server.custom().stateOf("chair", BlockFacing.SOUTH), server.state(2, 64, 2))
            assertEquals(server.custom().stateOf("chair", BlockFacing.SOUTH), server.state(5, 64, 5))
            assertEquals(BlockFacing.SOUTH, server.custom().facingAt("world", 5, 64, 5))
        }
    }

    @Test
    fun `the project's blocks changing moves the states, and what was placed follows`() {
        server().use { server ->
            server.custom().place("ore", "world", 0, 64, 0)
            val before = server.custom().stateOf("ore")!!
            assertEquals(before, server.state(0, 64, 0))
            // A block named before it takes the first state; the ore takes the next.
            server.write("blocks/amethyst/block.json", "{}")
            assertTrue(server.reload("blocks/amethyst/block.json").resources.single().ok)
            val after = server.custom().stateOf("ore")!!
            assertTrue(before != after)
            assertEquals(after, server.state(0, 64, 0))
            assertEquals("ore", server.custom().at("world", 0, 64, 0))

            // One taken away: the ones after it move back, and a block that has gone from the project stays as it is, inert.
            server.delete("blocks/ore/block.json")
            server.delete("blocks/ore/script.lua")
            assertTrue(server.reload("blocks/ore/block.json").resources.single().ok)
            assertNull(server.custom().at("world", 0, 64, 0))
            assertEquals(after, server.state(0, 64, 0))
            assertEquals(1, server.platform.blocks.records.size)
        }
    }

    @Test
    fun `a chunk full of generated blocks keeps one legend entry and no records`() {
        server().use { server ->
            val state = server.custom().stateOf("ore")!!
            for (x in 32..47) for (z in 32..47) for (y in 0..9) server.platform.worlds.blocks[BlockAt("world", x, y, z)] = state
            server.platform.raise.chunkLoad(GameEvent.ChunkLoad("world", 2, 2, true))
            assertEquals("ore", server.custom().at("world", 40, 5, 40))
            assertEquals(emptyMap(), server.platform.blocks.records.toMap())
            assertEquals("""{"$state":"ore"}""", server.platform.blocks.legends[Triple("world", 2, 2)])
        }
    }

    @Test
    fun `blocks in a chunk that wasn't loaded follow their states when it is`() {
        server().use { server ->
            server.custom().place("ore", "world", 40, 64, 40)
            val before = server.custom().stateOf("ore")!!
            server.runtime.events.game.chunkUnload(GameEvent.Chunk("world", 2, 2))
            server.platform.worlds.unloaded += Triple("world", 2, 2)
            // A block named first moves every state on one, while the chunk isn't loaded: the ore's is the lamp's now.
            server.write("blocks/amber/block.json", "{}")
            assertTrue(server.reload("blocks/amber/block.json").resources.single().ok)
            val after = server.custom().stateOf("ore")!!
            assertEquals(before, server.custom().stateOf("lamp"))
            server.platform.worlds.loadChunk("world", 2, 2)
            server.platform.raise.chunkLoad(GameEvent.ChunkLoad("world", 2, 2, false))
            // Still the ore, in the ore's state now: the legend said what it was placed as.
            assertEquals("ore", server.custom().at("world", 40, 64, 40))
            assertEquals(after, server.state(40, 64, 40))
            assertEquals("""{"$after":"ore"}""", server.platform.blocks.legends[Triple("world", 2, 2)])
        }
    }

    @Test
    fun `a block with a table keeps a record once its chunk unloads, and an old record of a plain block goes`() {
        server().use { server ->
            server.custom().place("ore", "world", 40, 64, 40)
            server.platform.blocks.data[BlockAt("world", 40, 64, 40)] = """{"age":1}"""
            server.custom().place("ore", "world", 41, 64, 40)
            server.runtime.events.game.chunkUnload(GameEvent.Chunk("world", 2, 2))
            assertEquals(listOf(40), server.platform.blocks.records.keys.map { it.x })
            // A record from before legends, of a block with no table: it goes when the chunk loads.
            server.platform.blocks.records[BlockAt("world", 42, 64, 40)] = """{"id":"ore"}"""
            server.platform.worlds.blocks[BlockAt("world", 42, 64, 40)] = server.custom().stateOf("ore")!!
            server.platform.raise.chunkLoad(GameEvent.ChunkLoad("world", 2, 2, false))
            assertEquals("ore", server.custom().at("world", 42, 64, 40))
            assertEquals(listOf(40), server.platform.blocks.records.keys.map { it.x })
        }
    }

    @Test
    fun `pistons don't push them, explosions break them for their drops, and break_naturally does`() {
        server(
            mapOf(
                "modules/t/init.lua" to """
                    local world = nf.worlds.default()
                    nf.blocks.get("ore"):place(world:block(vec3(0, 64, 0)):location())
                    nf.blocks.get("ore"):place(world:block(vec3(1, 64, 0)):location())
                """.trimIndent()
            )
        ).use { server ->
            val state = server.custom().stateOf("ore")!!
            val ref = { x: Int -> BlockRef("world", x, 64, 0, "minecraft:note_block", state) }
            assertTrue(server.platform.raise.pistonExtend(GameEvent.Piston(ref(9), "up", false, listOf(ref(0)))))
            assertFalse(
                server.platform.raise.pistonExtend(
                    GameEvent.Piston(ref(9), "up", false, listOf(BlockRef("world", 7, 64, 0, "minecraft:stone", "minecraft:stone")))
                )
            )

            val explosion = GameEvent.Explode(null, null, null, dev.netherforge.plugin.platform.Location("world", 0.0, 64.0, 0.0), {
                listOf(ref(0))
            }, true, 1.0)
            assertFalse(server.platform.raise.entityExplode(explosion))
            assertEquals("minecraft:air", server.state(0, 64, 0))
            assertNull(server.custom().at("world", 0, 64, 0))
            // No tool: the requiresTool block drops nothing; the explosion has none either.
            assertEquals(emptyList(), server.dropped())

            // A script breaks one as a player would, with a tool it names.
            server.write(
                "modules/u/init.lua",
                """nf.worlds.default():block(vec3(1, 64, 0)):break_naturally({ kind = "minecraft:iron_sword" })"""
            )
            server.platform.blocks.breakSpeeds["minecraft:iron_sword" to "minecraft:stone"] = 6.0
            server.reload("modules/u/init.lua")
            assertEquals("minecraft:air", server.state(1, 64, 0))
            assertEquals(listOf(2), server.dropped().map { it.def.count })
        }
    }

    @Test
    fun `without the server's agreement the project's blocks can't be held, and it says so`() {
        val platform = FakePlatform().also { it.blocks.refuseFreeze = true }
        TestServer(files, platform = platform).use { server ->
            assertFalse(server.custom().usable)
            assertFalse(server.custom().place("ore", "world", 0, 64, 0))
            assertTrue(server.runtime.session.problems().any { it.code == "runtime.blocks" })
        }
    }

    private val carrierFiles = files + mapOf(
        "blocks/rose/block.json" to """{ "carrier": "tripwire" }""",
        "blocks/glass/block.json" to """{ "carrier": "leaves" }""",
        "items/rose_item/item.json" to """{ "kind": "minecraft:paper", "block": "rose" }""",
        "items/glass_item/item.json" to """{ "kind": "minecraft:paper", "block": "glass" }"""
    )

    @Test
    fun `each carrier a block is held in is held still, and its blocks take that carrier's states`() {
        server(
            carrierFiles - "blocks/rose/block.json" - "items/rose_item/item.json" - "blocks/glass/block.json" - "items/glass_item/item.json"
        ).use { server ->
            assertEquals(setOf(BlockCarrier.NOTE_BLOCK), server.platform.blocks.frozenCarriers, "only what the project uses")
        }
        server(carrierFiles).use { server ->
            assertEquals(setOf(BlockCarrier.NOTE_BLOCK, BlockCarrier.TRIPWIRE, BlockCarrier.LEAVES), server.platform.blocks.frozenCarriers)
            val rose = BlockState.parse(assertNotNull(server.custom().stateOf("rose")))!!
            assertEquals("minecraft:tripwire", rose.id)
            assertEquals("true", rose.properties["disarmed"])
            val glass = BlockState.parse(assertNotNull(server.custom().stateOf("glass")))!!
            assertEquals("minecraft:birch_leaves", glass.id)
            assertEquals(mapOf("distance" to "1", "persistent" to "true", "waterlogged" to "false"), glass.properties)
            // The note blocks' states are where they were: other carriers take none of theirs.
            assertEquals("minecraft:note_block", BlockState.parse(server.custom().stateOf("ore")!!)!!.id)

            // An item places one as it places any block, and breaking it is the project's: its drops, no string.
            val roseItem = ItemData(ItemDef(kind = "minecraft:paper", item = ResourceRef("rose_item"), count = 2))
            server.hold(roseItem)
            assertTrue(server.runtime.events.playerInteract(server.alex.ref, ClickButton.RIGHT, ground, "up", roseItem, "main_hand"))
            assertEquals(server.custom().stateOf("rose"), server.state(0, 64, 0))
            assertEquals("rose", server.custom().at("world", 0, 64, 0))
            assertTrue(server.breaks(server.alex, BlockRef("world", 0, 64, 0, server.state(0, 64, 0)!!, "minecraft:tripwire")))
            assertEquals("minecraft:air", server.state(0, 64, 0))
        }
    }

    @Test
    fun `leaves states are adopted only in a chunk just generated or a world the project generates, never a player's leaves`() {
        server(carrierFiles).use { server ->
            val glass = server.custom().stateOf("glass")!!
            // A player's hedge near a log, from before the project had leaves blocks: the very state a leaves block takes.
            server.platform.worlds.blocks[BlockAt("world", 40, 70, 40)] = glass
            server.platform.raise.chunkLoad(GameEvent.ChunkLoad("world", 2, 2, false))
            assertNull(server.custom().at("world", 40, 70, 40), "an old chunk's leaves are the player's")
            // The same state in a chunk the server just generated is a generator's block.
            server.platform.worlds.blocks[BlockAt("world", 72, 70, 40)] = glass
            server.platform.raise.chunkLoad(GameEvent.ChunkLoad("world", 4, 2, true))
            assertEquals("glass", server.custom().at("world", 72, 70, 40))
            // Tripwire and note blocks are adopted anywhere, as before.
            server.platform.worlds.blocks[BlockAt("world", 104, 70, 40)] = server.custom().stateOf("rose")!!
            server.platform.raise.chunkLoad(GameEvent.ChunkLoad("world", 6, 2, false))
            assertEquals("rose", server.custom().at("world", 104, 70, 40))
        }
    }
}
