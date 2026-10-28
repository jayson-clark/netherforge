package dev.netherforge.plugin.block
import dev.netherforge.format.item.ItemDef
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.BlockRef
import dev.netherforge.plugin.platform.DropsAnswer
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.testkit.BlockAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The game's own blocks with rules of the project's (`game_blocks/`) on the fake server: mined by the server for a
 * hardness and tool of the project's, dropping the project's loot table whoever breaks them, and a block the project
 * doesn't name left to the game. And the project's own blocks that fall like sand.
 */
class GameBlockTest {
    private val files = mapOf(
        // Stone mined ten times slower than the game's (15 to its 1.5), needing a pickaxe for the project's rubble.
        "game_blocks/stone.json" to
            """{ "blocks": ["minecraft:stone"], "hardness": 15, "tool": "pickaxe", "requiresTool": true, "drops": "rubble" }""",
        // Planks keep the game's mining, and drop the project's rubble.
        "game_blocks/planks.json" to """{ "blocks": ["#minecraft:planks"], "drops": "rubble" }""",
        "loot/rubble.json" to
            """{ "pools": { "main": { "entries": [{ "type": "item", "item": { "kind": "minecraft:gold_nugget" }, "count": 3 }] } } }"""
    )

    private fun server(extra: Map<String, Any> = emptyMap()) = TestServer(files + extra).also {
        it.platform.players.add("Alex")
        assertEquals(emptyList(), it.errors.map { error -> error.message })
        assertEquals(
            emptyList(),
            it.runtime.session.problems().filter { problem -> problem.severity == dev.netherforge.format.Severity.ERROR }
                .map { problem -> "${problem.file} ${problem.message}" }
        )
        // The fake game's own drops: a diamond for stone mined with a pickaxe (its tool), planks for planks.
        it.platform.blocks.gameDrops["minecraft:stone"] = listOf(ItemData(ItemDef(kind = "minecraft:diamond")))
        it.platform.blocks.gameDrops["minecraft:oak_planks"] = listOf(ItemData(ItemDef(kind = "minecraft:oak_planks")))
    }

    private val TestServer.alex get() = platform.players.byId.values.first()

    private fun TestServer.hold(item: ItemData?) = platform.worldEntities.setEquipment(alex.ref.uuid, "main_hand", item)

    private fun TestServer.set(x: Int, y: Int, z: Int, state: String) {
        platform.worlds.blocks[BlockAt("world", x, y, z)] = state
    }

    private fun TestServer.state(x: Int, y: Int, z: Int) = platform.worlds.state("world", x, y, z)

    private fun TestServer.dropped() = platform.worldEntities.list("world").filter { it.kind == "minecraft:item" }
        .mapNotNull { platform.worldEntities.item(it.id) }.map { "${it.def.count ?: 1} ${it.def.kind}" }

    private fun TestServer.modifiers() = platform.attributes.modifiers(alex.ref.uuid, "minecraft:block_break_speed").orEmpty()

    private fun ref(x: Int, state: String) = BlockRef("world", x, 64, 0, state.substringBefore('['), state)

    private val pickaxe = ItemData(ItemDef(kind = "minecraft:iron_pickaxe"))

    /** How many ticks the server takes to break the block at [x], mined from its first hit with what's held; null when it didn't. */
    private fun TestServer.mine(x: Int, state: String, within: Int): Int? {
        val held = platform.worldEntities.equipment(alex.ref.uuid, "main_hand")
        platform.raise.blockStartBreak(GameEvent.BlockStartBreak(alex.ref, ref(x, state), held, false))
        for (tick in 1..within) {
            tick(1)
            if (state(x, 64, 0) == "minecraft:air") return tick
        }
        return null
    }

    @Test
    fun `a game block with a high hardness takes that long to break, mined by the server`() {
        server().use { server ->
            server.hold(pickaxe)
            // A tick of the player's own mining of stone is 1/8 here; the rule's hardness makes it ten times slower.
            server.platform.blocks.breakProgresses[server.alex.ref.uuid] = 0.125
            server.set(0, 64, 0, "minecraft:stone")
            val ticks = server.mine(0, "minecraft:stone", 200)
            assertEquals(80, ticks)
            // The player's own game was slowed the whole time, and the cracks were the server's.
            assertTrue(server.platform.blocks.cracks.any { it.startsWith("Alex world 0 64 0 ") }, server.platform.blocks.cracks.toString())
            // The breaker sees it break, as everyone else does from the game's own break.
            assertEquals(listOf("Alex world 0 64 0 minecraft:stone"), server.platform.blocks.shownBroken)
            // With the pickaxe, the project's rubble, not the game's cobblestone.
            assertEquals(listOf("3 minecraft:gold_nugget"), server.dropped())
            server.tick(1)
            assertEquals(emptyList(), server.modifiers())

            // Looking at one slows the player before they hit it.
            server.set(1, 64, 0, "minecraft:stone")
            server.platform.blocks.targets[server.alex.ref.uuid] = BlockAt("world", 1, 64, 0)
            server.tick(1)
            assertEquals(1, server.modifiers().size)
        }
    }

    @Test
    fun `requiresTool drops nothing with the wrong tool, and the right one gets the project's drops`() {
        server().use { server ->
            server.set(0, 64, 0, "minecraft:stone")
            assertEquals(
                DropsAnswer(emptyList(), 0),
                server.runtime.events.blockBreak(server.alex.ref, ref(0, "minecraft:stone"), { error("the game's drops") }, 0)
            )
            server.hold(pickaxe)
            val answer = server.runtime.events.blockBreak(server.alex.ref, ref(0, "minecraft:stone"), { error("the game's drops") }, 0)
            assertEquals(listOf("minecraft:gold_nugget"), answer?.drops?.map { it.def.kind })
            // A bare hand mines the game's stone at 1/20 a pickaxe's pace (speed 1 to 6, and 100 to 30 without the tool
            // it needs): ten times slower again with the rule's hardness, 1600 ticks.
            server.hold(null)
            server.platform.blocks.breakProgresses[server.alex.ref.uuid] = 0.125 / 20
            server.set(1, 64, 0, "minecraft:stone")
            assertNull(server.mine(1, "minecraft:stone", 1599))
            server.tick(1)
            assertEquals("minecraft:air", server.state(1, 64, 0))
        }
    }

    @Test
    fun `drops replace the game's for whoever breaks it, and a block the project doesn't name is the game's`() {
        server(
            mapOf(
                "modules/u/init.lua" to """nf.worlds.default():block(vec3(4, 64, 0)):break_naturally({ kind = "minecraft:iron_pickaxe" })"""
            )
        ).use { server ->
            // A player: the game's drops are what the rule says, planks' mining left to the player's own game.
            val planks = ref(2, "minecraft:oak_planks")
            server.set(2, 64, 0, "minecraft:oak_planks")
            val answer = server.runtime.events.blockBreak(server.alex.ref, planks, { error("the game's drops") }, 0)
            assertEquals(listOf("minecraft:gold_nugget"), answer?.drops?.map { it.def.kind })
            server.platform.raise.blockStartBreak(GameEvent.BlockStartBreak(server.alex.ref, planks, null, false))
            assertEquals(emptyList(), server.modifiers())

            // The world: what held it up went, a fluid, a piston, leaves decaying.
            assertEquals(listOf("minecraft:gold_nugget"), server.runtime.events.worldBroke(planks)?.map { it.def.kind })
            assertEquals(setOf("minecraft:stone", "minecraft:oak_planks"), server.platform.blocks.watchedDrops)

            // An explosion: gone before the game gets to it, with the project's drops.
            server.set(3, 64, 0, "minecraft:stone")
            val explosion = GameEvent.Explode(null, null, null, Location("world", 3.0, 64.0, 0.0), {
                listOf(ref(3, "minecraft:stone"))
            }, true, 1.0)
            assertFalse(server.platform.raise.entityExplode(explosion))
            assertEquals("minecraft:air", server.state(3, 64, 0))
            assertEquals(listOf("3 minecraft:gold_nugget"), server.dropped())

            // A script's break_naturally, with the tool it names.
            server.set(4, 64, 0, "minecraft:stone")
            assertTrue(server.reload("modules/u/init.lua").resources.single().ok)
            assertEquals("minecraft:air", server.state(4, 64, 0))
            assertEquals(listOf("3 minecraft:gold_nugget", "3 minecraft:gold_nugget"), server.dropped())

            // Dirt: no rule. Its drops are the game's, the player mines it, and an explosion leaves it to the game.
            val dirt = ref(5, "minecraft:dirt")
            server.set(5, 64, 0, "minecraft:dirt")
            assertEquals(DropsAnswer(null, 0), server.runtime.events.blockBreak(server.alex.ref, dirt, { emptyList() }, 0))
            assertNull(server.runtime.events.worldBroke(dirt))
            server.platform.raise.blockStartBreak(GameEvent.BlockStartBreak(server.alex.ref, dirt, null, false))
            assertEquals(emptyList(), server.modifiers())
            server.platform.raise.entityExplode(
                GameEvent.Explode(null, null, null, Location("world", 5.0, 64.0, 0.0), { listOf(dirt) }, true, 1.0)
            )
            assertEquals("minecraft:dirt", server.state(5, 64, 0))
            // In creative nothing drops, as always: the game's own (none) stand.
            server.platform.players.byId.getValue(server.alex.ref.uuid).gameMode = "creative"
            assertEquals(
                DropsAnswer(null, 0),
                server.runtime.events.blockBreak(server.alex.ref, ref(6, "minecraft:stone"), { emptyList() }, 0)
            )
        }
    }

    @Test
    fun `a rule saved again holds from the next block, and one taken out leaves the block to the game`() {
        server().use { server ->
            server.write("game_blocks/stone.json", """{ "blocks": ["minecraft:stone"], "hardness": 1.5 }""")
            assertTrue(server.reload("game_blocks/stone.json").resources.single().ok)
            server.hold(pickaxe)
            server.platform.blocks.breakProgresses[server.alex.ref.uuid] = 0.125
            server.set(0, 64, 0, "minecraft:stone")
            // The game's own hardness: eight ticks, as the player's own game would.
            assertEquals(8, server.mine(0, "minecraft:stone", 100))
            assertEquals(listOf("1 minecraft:diamond"), server.dropped())

            server.delete("game_blocks/stone.json")
            assertTrue(server.reload("game_blocks/stone.json").resources.single().ok)
            server.set(1, 64, 0, "minecraft:stone")
            server.platform.raise.blockStartBreak(GameEvent.BlockStartBreak(server.alex.ref, ref(1, "minecraft:stone"), pickaxe, false))
            assertEquals(emptyList(), server.modifiers())
            assertEquals(setOf("minecraft:oak_planks"), server.platform.blocks.watchedDrops)
        }
    }

    @Test
    fun `a project block that falls drops as a falling block, lands as itself, and breaks where it can't land`() {
        server(
            mapOf(
                "blocks/silt/block.json" to """{ "falls": true, "drops": "rubble" }""",
                "blocks/rock/block.json" to "{}"
            )
        ).use { server ->
            val custom = server.runtime.session.customBlocks
            val silt = custom.stateOf("silt")!!
            assertEquals(setOf(silt), server.platform.blocks.watchedFalling)
            // Placed over air: it falls at the next tick, as sand does.
            assertTrue(custom.place("silt", "world", 0, 66, 0))
            server.tick(1)
            val (entity, carried, from) = server.platform.blocks.fallen.single()
            assertEquals(silt, carried)
            assertEquals(BlockAt("world", 0, 66, 0), from)
            assertEquals("minecraft:air", server.state(0, 66, 0))
            assertNull(custom.at("world", 0, 66, 0))
            // It lands as itself, on the stone under it, and stays.
            server.set(0, 63, 0, "minecraft:stone")
            server.set(0, 64, 0, silt)
            server.runtime.events.fallingLanded(entity, BlockRef("world", 0, 64, 0, "minecraft:note_block", silt))
            assertEquals("silt", custom.at("world", 0, 64, 0))

            // One that doesn't fall stays where it's put.
            assertTrue(custom.place("rock", "world", 2, 66, 0))
            server.tick(1)
            assertEquals(1, server.platform.blocks.fallen.size)

            // On something solid it stays, until what's under it goes.
            server.set(4, 65, 0, "minecraft:stone")
            assertTrue(custom.place("silt", "world", 4, 66, 0))
            server.tick(1)
            assertEquals(1, server.platform.blocks.fallen.size)
            server.set(4, 65, 0, "minecraft:air")
            server.runtime.events.blockUpdated(BlockRef("world", 4, 66, 0, "minecraft:note_block", silt))
            server.tick(1)
            assertEquals(2, server.platform.blocks.fallen.size)
            // It breaks on the way down (a torch): its drops where it was last seen, as sand drops itself.
            server.platform.worldEntities.remove(server.platform.blocks.fallen.last().first)
            server.tick(1)
            assertEquals(listOf("3 minecraft:gold_nugget"), server.dropped())
        }
    }
}
