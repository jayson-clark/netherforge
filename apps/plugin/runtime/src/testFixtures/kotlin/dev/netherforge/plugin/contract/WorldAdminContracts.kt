package dev.netherforge.plugin.contract

import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.terrain.TerrainBlock
import dev.netherforge.format.terrain.TerrainCompiler
import dev.netherforge.format.terrain.TerrainKind
import dev.netherforge.plugin.platform.BlockVector
import dev.netherforge.plugin.platform.BorderOps
import dev.netherforge.plugin.platform.BorderOwner
import dev.netherforge.plugin.platform.BorderState
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.ProjectGenerator
import dev.netherforge.plugin.platform.StructureOps
import dev.netherforge.plugin.platform.StructurePlacement
import dev.netherforge.plugin.platform.StructureTransform
import dev.netherforge.plugin.platform.WorldManagerOps
import dev.netherforge.plugin.platform.WorldSettings
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [WorldManagerOps]: making, loading, unloading and deleting worlds. */
abstract class WorldManagerOpsContract : PlatformContract() {
    private val manager: WorldManagerOps get() = platform.worldManager
    private val void = WorldSettings("void", "normal", seed = 1, structures = false, keepSpawnLoaded = false)

    @Test
    fun `the server's dimension types include the game's, by namespaced id`() {
        val types = main { manager.dimensionTypes() }
        assertTrue(types.containsAll(GAME_DIMENSION_TYPES), "$types")
        assertTrue(types.all { ':' in it }, "$types")
        assertFalse("minecraft:nf_no_such_type" in types)
    }

    @Test
    fun `a world made is loaded and saved, unloads, loads again and is deleted`() {
        val name = "nf_contract_${WORLDS.incrementAndGet()}"
        main {
            assertTrue(manager.isSaved(world))
            assertFalse(manager.isSaved(name))
            assertTrue(manager.create(name, void))
            assertTrue(platform.worlds.exists(name))
            assertTrue(manager.isSaved(name))
            val files = assertNotNull(manager.saveFiles(name))
            assertEquals("level.dat", files.level.name)
            assertTrue(files.level.isRegularFile())
            assertNull(manager.saveFiles(MISSING_WORLD))
            assertTrue(manager.unload(name, save = true))
            assertFalse(platform.worlds.exists(name))
            assertTrue(manager.isSaved(name), "still on disk")
            assertFalse(manager.unload(name, save = false), "not loaded")
            assertTrue(manager.load(name, "normal"))
            assertTrue(platform.worlds.exists(name))
            assertTrue(manager.unload(name, save = false))
        }
        // Each load and unload is heard, as the world's own load event and unload event.
        val loads = events.heard<GameEvent.World>("worldLoad").count { it.world == name }
        val unloads = events.heard<GameEvent.World>("worldUnload").count { it.world == name }
        assertEquals(2 to 2, loads to unloads)
        // Asked on the main thread, done off it, as the runtime's workers do.
        main { manager.delete(name) }.run()
        main {
            assertFalse(manager.isSaved(name), "its files gone")
            assertFalse(manager.load(name, "normal"), "nothing to load")
        }
    }

    @Test
    fun `a world with a player in it stays loaded`() {
        val name = "nf_contract_${WORLDS.incrementAndGet()}"
        main { assertTrue(manager.create(name, void)) }
        val player = join()
        main {
            assertTrue(platform.players.teleport(player.uuid, Location(name, 0.5, 64.0, 0.5)))
            assertFalse(manager.unload(name, save = false))
            assertTrue(platform.worlds.exists(name))
            assertTrue(platform.players.teleport(player.uuid, origin))
        }
        val changes = events.heard<GameEvent.PlayerChangeWorld>("playerChangeWorld").filter { it.player == player }
        assertEquals(listOf(world to name, name to world), changes.map { it.from to it.to })
        eventually("$name unloading") { manager.unload(name, save = false) }
        main { manager.delete(name) }.run()
        main { assertFalse(manager.isSaved(name)) }
    }

    @Test
    fun `a copy's file work runs off the main thread, failing for a template that isn't there and leaving nothing`() {
        val template = server.directory().resolve("missing_template")
        val work = main { manager.copy(template, "nf_contract_copy") }
        assertFailsWith<IOException> { work.run() }
        main {
            assertFalse(manager.isSaved("nf_contract_copy"))
            assertFalse(manager.load("nf_contract_copy", "normal"))
            assertFalse(platform.worlds.exists("nf_contract_copy"))
        }
    }

    @Test
    fun `a world made with a published project generator is made, and loads again with it`() {
        val name = "nf_contract_${WORLDS.incrementAndGet()}"
        val generator = flatGenerator()
        main {
            manager.publishGenerators(mapOf("contract_flat" to generator))
            val settings =
                WorldSettings("normal", "normal", seed = 7, structures = false, keepSpawnLoaded = false, terrain = "contract_flat")
            assertTrue(manager.create(name, settings))
            assertTrue(platform.worlds.exists(name))
            assertTrue(manager.unload(name, save = true))
            assertTrue(manager.load(name, "normal", "contract_flat"))
            assertTrue(platform.worlds.exists(name))
            // Published again: the same id, a generator of another height. A world made already is none the worse.
            manager.publishGenerators(mapOf("contract_flat" to flatGenerator(base = 80)))
            assertTrue(platform.worlds.exists(name))
            assertTrue(manager.unload(name, save = false))
        }
        main { manager.delete(name) }.run()
        main { manager.publishGenerators(emptyMap()) }
    }

    private companion object {
        val WORLDS = AtomicInteger()

        /** The dimension types every supported Minecraft has. */
        val GAME_DIMENSION_TYPES = setOf("minecraft:overworld", "minecraft:overworld_caves", "minecraft:the_nether", "minecraft:the_end")

        /** A generator of flat ground at [base], of stone under grass, with the ore of a custom block's state. */
        fun flatGenerator(base: Int = 64): ProjectGenerator {
            val text = """{ "terrain": { "base": $base }, "layers": [{ "block": "minecraft:grass_block" }],
                "ores": { "ruby": { "customBlock": "ruby_ore", "veins": 4, "minY": 0, "maxY": 40 } } }"""
            val file = (TerrainKind.parse(text, "terrain/contract_flat.json") as CanonicalJson.Parsed.Ok).value
            val terrain = TerrainCompiler.compile(file)
            val states = terrain.palette.map {
                when (it) {
                    is TerrainBlock.Vanilla -> it.state
                    is TerrainBlock.Custom -> "minecraft:note_block[instrument=custom_head,note=1,powered=true]"
                }
            }
            return ProjectGenerator(terrain, states, terrain.biomes.associateWith { it })
        }
    }
}

/** [BorderOps]: world borders, and borders players see alone. */
abstract class BorderOpsContract : PlatformContract() {
    private val borders: BorderOps get() = platform.borders

    @Test
    fun `a world's border is the game's default until changed, and reads back as set`() {
        main {
            val owner = BorderOwner.World(world)
            val before = assertNotNull(borders.get(owner))
            afterwards {
                borders.setCenter(owner, before.centerX, before.centerZ)
                borders.setSize(owner, before.size, 0)
                borders.setDamage(owner, before.damageAmount, before.damageBuffer)
                borders.setWarning(owner, before.warningDistance, before.warningTicks)
            }
            assertEquals(BorderState(0.0, 0.0, borders.maxSize, 0.2, 5.0, 5, 300), before)
            assertEquals(59_999_968.0, borders.maxSize)
            assertEquals(29_999_984.0, borders.maxCenter)
            assertTrue(borders.setCenter(owner, 100.0, -50.0))
            assertTrue(borders.setSize(owner, 64.0, 0))
            assertTrue(borders.setDamage(owner, 1.5, 2.0))
            assertTrue(borders.setWarning(owner, 8, 200))
            assertEquals(BorderState(100.0, -50.0, 64.0, 1.5, 2.0, 8, 200), borders.get(owner))
            assertTrue(borders.contains(owner, world, 100.0, 70.0, -50.0))
            assertTrue(borders.contains(owner, null, 131.0, 0.0, -50.0))
            assertFalse(borders.contains(owner, null, 133.0, 0.0, -50.0))
            assertFalse(borders.contains(owner, "nf_elsewhere", 100.0, 70.0, -50.0), "another world's point")
            val missing = BorderOwner.World(MISSING_WORLD)
            assertNull(borders.get(missing))
            assertFalse(borders.setSize(missing, 10.0, 0))
            assertFalse(borders.contains(missing, null, 0.0, 0.0, 0.0))
        }
    }

    @Test
    fun `a player's own border starts as their world's and changes alone`() {
        val player = join()
        main {
            val own = BorderOwner.Player(player.uuid)
            afterwards { borders.reset(player.uuid) }
            assertNull(borders.get(own))
            assertFalse(borders.reset(player.uuid), "has none")
            assertTrue(borders.personal(player.uuid))
            assertTrue(borders.personal(player.uuid), "already has one")
            assertEquals(borders.get(BorderOwner.World(world)), borders.get(own))
            assertTrue(borders.setSize(own, 16.0, 0))
            assertTrue(borders.setCenter(own, 5.0, 5.0))
            assertEquals(16.0, borders.get(own)?.size)
            assertEquals(borders.maxSize, borders.get(BorderOwner.World(world))?.size, "the world's is unchanged")
            assertTrue(borders.contains(own, null, 5.0, 0.0, 12.0))
            assertFalse(borders.contains(own, null, 5.0, 0.0, 14.0))
            assertTrue(borders.reset(player.uuid))
            assertNull(borders.get(own))
            assertFalse(borders.personal(UUID.randomUUID()))
            assertNull(borders.get(BorderOwner.Player(UUID.randomUUID())))
        }
    }

    @Test
    fun `a player's own border is forgotten when they leave`() {
        val player = join()
        main { assertTrue(borders.personal(player.uuid)) }
        leave(player)
        main { assertNull(borders.get(BorderOwner.Player(player.uuid))) }
    }
}

/** [StructureOps]: structures saved to files and placed from them. */
abstract class StructureOpsContract : PlatformContract() {
    private val structures: StructureOps get() = platform.structures
    private val placement = StructurePlacement(0, "none", 1.0, entities = false)

    @Test
    fun `a structure saved is placed elsewhere as it was`() {
        val file = server.directory().resolve("contract.nbt")
        main {
            place(at(-8, 3, -8), "minecraft:stone")
            place(at(-7, 3, -8), "minecraft:gold_block")
            place(at(-8, 4, -8), "minecraft:oak_planks")
            val (x, y, z) = block(at(-8, 3, -8))
            assertTrue(structures.save(file, world, BlockVector(x, y, z), BlockVector(x + 1, y + 1, z), entities = false))
            assertTrue(file.isRegularFile())
            assertEquals(BlockVector(2, 2, 1), structures.size(file))
            for ((dx, dy) in listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)) place(at(-8 + dx, 3 + dy, -12), "minecraft:air")
            val (px, py, pz) = block(at(-8, 3, -12))
            assertTrue(structures.place(file, world, BlockVector(px, py, pz), placement))
            assertEquals("minecraft:stone", platform.blocks.get(world, px, py, pz)?.state)
            assertEquals("minecraft:gold_block", platform.blocks.get(world, px + 1, py, pz)?.state)
            assertEquals("minecraft:oak_planks", platform.blocks.get(world, px, py + 1, pz)?.state)
            assertEquals("minecraft:air", platform.blocks.get(world, px + 1, py + 1, pz)?.state)
            assertFalse(structures.place(file, MISSING_WORLD, BlockVector(0, 0, 0), placement))
            assertFalse(structures.save(file, MISSING_WORLD, BlockVector(0, 0, 0), BlockVector(0, 0, 0), entities = false))
        }
    }

    @Test
    fun `a structure's blocks are read as a generator places them`() {
        val file = server.directory().resolve("template.nbt")
        main {
            place(at(-14, 3, -8), "minecraft:oak_log[axis=x]")
            place(at(-13, 3, -8), "minecraft:air")
            place(at(-14, 4, -8), "minecraft:gold_block")
            place(at(-13, 4, -8), "minecraft:air")
            val (x, y, z) = block(at(-14, 3, -8))
            assertTrue(structures.save(file, world, BlockVector(x, y, z), BlockVector(x + 1, y + 1, z), entities = false))
            val template = assertNotNull(structures.template(file))
            assertEquals(listOf(2, 2, 1), listOf(template.sizeX, template.sizeY, template.sizeZ))
            // Each block's position and its state in full (air may be listed or not: a generator never places it).
            val blocks = (0 until template.blocks.size / 4).associate { i ->
                val b = template.blocks
                listOf(b[i * 4], b[i * 4 + 1], b[i * 4 + 2]) to template.palette[b[i * 4 + 3]]
            }.filterValues { it != "minecraft:air" }
            assertEquals(mapOf(listOf(0, 0, 0) to "minecraft:oak_log[axis=x]", listOf(0, 1, 0) to "minecraft:gold_block"), blocks)
            assertNull(structures.template(server.directory().resolve("missing.nbt")))
        }
    }

    @Test
    fun `a file is read once and kept until forgotten`() {
        val file = server.directory().resolve("cached.nbt")
        main {
            place(at(-10, 3, -8), "minecraft:stone")
            val (x, y, z) = block(at(-10, 3, -8))
            assertTrue(structures.save(file, world, BlockVector(x, y, z), BlockVector(x, y, z), entities = false))
            assertEquals(BlockVector(1, 1, 1), structures.size(file))
            assertTrue(structures.save(file, world, BlockVector(x, y, z), BlockVector(x + 2, y, z), entities = false))
            assertEquals(BlockVector(1, 1, 1), structures.size(file), "still the one read")
            structures.forget(file)
            assertEquals(BlockVector(3, 1, 1), structures.size(file))
        }
    }

    @Test
    fun `a placer puts a structure in a few blocks at a time, turned where the transform says`() {
        val file = server.directory().resolve("gradual.nbt")
        main {
            place(at(-20, 3, -8), "minecraft:stone")
            place(at(-19, 3, -8), "minecraft:gold_block")
            val (x, y, z) = block(at(-20, 3, -8))
            assertTrue(structures.save(file, world, BlockVector(x, y, z), BlockVector(x + 1, y, z), entities = false))
            val (px, py, pz) = block(at(-20, 3, -16))
            for (dz in 0..1) place(at(-20, 3, -16 + dz), "minecraft:air")
            // A quarter turn clockwise: what was along +x goes along +z.
            val transform = StructureTransform(BlockVector(px, py, pz), BlockVector(0, 0, 0), 90, "none")
            val placer =
                assertNotNull(structures.placer(file, world, transform, StructurePlacement(90, "none", 1.0, entities = true), seed = 1))
            assertEquals(BlockVector(2, 1, 1), placer.size)
            assertEquals(2, placer.remaining)
            assertEquals(1, placer.place(1))
            assertEquals(1, placer.remaining)
            assertEquals(1, placer.place(5))
            assertEquals(0, placer.remaining)
            assertEquals(2L, placer.placed)
            assertTrue(placer.entities())
            assertEquals("minecraft:stone", platform.blocks.get(world, px, py, pz)?.state)
            assertEquals("minecraft:gold_block", platform.blocks.get(world, px, py, pz + 1)?.state)
            assertNull(structures.placer(file, MISSING_WORLD, transform, placement, seed = 1))
            assertNull(structures.placer(server.directory().resolve("missing.nbt"), world, transform, placement, seed = 1))
        }
    }

    @Test
    fun `a file that isn't a structure has no size and places nothing`() {
        val missing = server.directory().resolve("missing.nbt")
        main {
            assertNull(structures.size(missing))
            assertFalse(structures.place(missing, world, BlockVector(0, 70, 0), placement))
        }
    }
}
