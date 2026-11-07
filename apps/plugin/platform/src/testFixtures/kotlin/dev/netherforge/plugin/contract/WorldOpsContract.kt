package dev.netherforge.plugin.contract

import dev.netherforge.format.game.Box
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.project.SpawnCategory
import dev.netherforge.plugin.platform.GameRuleType
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.WorldOps
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [WorldOps]: worlds by name, what they are, and what's in them. */
abstract class WorldOpsContract : PlatformContract() {
    private val worlds: WorldOps get() = platform.worlds

    @Test
    fun `worlds are known by name, the default first`() {
        main {
            assertTrue(worlds.defaultWorld() in worlds.names())
            assertEquals(world, worlds.defaultWorld())
            assertTrue(worlds.exists(world))
            assertFalse(worlds.exists(MISSING_WORLD))
            assertEquals("normal", worlds.environment(world))
            assertNull(worlds.environment(MISSING_WORLD))
            assertTrue(worlds.entitiesLoaded(origin))
        }
    }

    @Test
    fun `a world's spawn is where it was set`() {
        main {
            val before = assertNotNull(worlds.spawnLocation(world))
            assertEquals(world, before.world)
            afterwards { worlds.setSpawnLocation(world, before) }
            assertTrue(worlds.setSpawnLocation(world, Location(world, 3.0, origin.y, -2.0)))
            val after = assertNotNull(worlds.spawnLocation(world))
            assertEquals(Triple(3, block(origin).second, -2), block(after))
            assertNull(worlds.spawnLocation(MISSING_WORLD))
            assertFalse(worlds.setSpawnLocation(MISSING_WORLD, Location(MISSING_WORLD, 0.0, 0.0, 0.0)))
        }
    }

    @Test
    fun `time and weather read back as set`() {
        main {
            val time = assertNotNull(worlds.fullTime(world))
            afterwards { worlds.setFullTime(world, time) }
            assertTrue(worlds.setFullTime(world, 30_000))
            assertEquals(30_000, worlds.fullTime(world))
            afterwards { worlds.setWeather(world, "clear", null) }
            assertTrue(worlds.setWeather(world, "rain", 6000))
            assertEquals("rain", worlds.weather(world))
            assertTrue(worlds.setWeather(world, "thunder", 6000))
            assertEquals("thunder", worlds.weather(world))
            assertTrue(worlds.setWeather(world, "clear", null))
            assertEquals("clear", worlds.weather(world))
            assertNull(worlds.fullTime(MISSING_WORLD))
            assertNull(worlds.weather(MISSING_WORLD))
            // How a version turns the moon is its own (26.x keeps it on the world's clock): only the phase's range is promised.
            assertTrue(assertNotNull(worlds.moonPhase(world)) in 0..7)
            assertNull(worlds.moonPhase(MISSING_WORLD))
            assertFalse(worlds.setFullTime(MISSING_WORLD, 0))
            assertFalse(worlds.setWeather(MISSING_WORLD, "rain", null))
        }
    }

    @Test
    fun `the overworld's heights`() {
        main {
            assertEquals(-64 to 320, worlds.heights(world))
            assertNull(worlds.heights(MISSING_WORLD))
        }
    }

    @Test
    fun `game rules have types and read back as set`() {
        main {
            assertEquals(GameRuleType.BOOLEAN, worlds.gameRuleType(KEEP_INVENTORY))
            assertEquals(GameRuleType.INTEGER, worlds.gameRuleType(RANDOM_TICK_SPEED))
            assertNull(worlds.gameRuleType("minecraft:nf_no_such_rule"))
            val keep = assertNotNull(worlds.gameRule(world, KEEP_INVENTORY))
            val speed = assertNotNull(worlds.gameRule(world, RANDOM_TICK_SPEED))
            afterwards {
                worlds.setGameRule(world, KEEP_INVENTORY, keep)
                worlds.setGameRule(world, RANDOM_TICK_SPEED, speed)
            }
            assertEquals(false, keep)
            assertEquals(3, speed)
            assertTrue(worlds.setGameRule(world, KEEP_INVENTORY, true))
            assertTrue(worlds.setGameRule(world, RANDOM_TICK_SPEED, 7))
            assertEquals(true, worlds.gameRule(world, KEEP_INVENTORY))
            assertEquals(7, worlds.gameRule(world, RANDOM_TICK_SPEED))
            assertNull(worlds.gameRule(MISSING_WORLD, KEEP_INVENTORY))
            assertFalse(worlds.setGameRule(MISSING_WORLD, KEEP_INVENTORY, true))
        }
    }

    @Test
    fun `spawn limits and intervals read back as set, per category, and a negative one goes back to the server's`() {
        main {
            for (category in SpawnCategory.entries) {
                val limit = assertNotNull(worlds.spawnLimit(world, category))
                val interval = assertNotNull(worlds.spawnInterval(world, category))
                afterwards {
                    worlds.setSpawnLimit(world, category, -1)
                    worlds.setSpawnInterval(world, category, -1)
                }
                assertTrue(worlds.setSpawnLimit(world, category, limit + 3))
                assertTrue(worlds.setSpawnInterval(world, category, interval + 5))
                assertEquals(limit + 3, worlds.spawnLimit(world, category))
                assertEquals(interval + 5, worlds.spawnInterval(world, category))
                assertTrue(worlds.setSpawnLimit(world, category, 0))
                assertEquals(0, worlds.spawnLimit(world, category))
                assertTrue(worlds.setSpawnLimit(world, category, -1))
                assertTrue(worlds.setSpawnInterval(world, category, -1))
                assertEquals(limit, worlds.spawnLimit(world, category))
                assertEquals(interval, worlds.spawnInterval(world, category))
            }
            assertNull(worlds.spawnLimit(MISSING_WORLD, SpawnCategory.MONSTER))
            assertNull(worlds.spawnInterval(MISSING_WORLD, SpawnCategory.MONSTER))
            assertFalse(worlds.setSpawnLimit(MISSING_WORLD, SpawnCategory.MONSTER, 1))
            assertFalse(worlds.setSpawnInterval(MISSING_WORLD, SpawnCategory.MONSTER, 1))
        }
    }

    @Test
    fun `chunks around the origin are loaded, and a far one isn't`() {
        val (x, z) = block(origin).let { (x, _, z) -> (x shr 4) to (z shr 4) }
        val (farX, farZ) = server.unloadedChunk()
        main {
            assertTrue(worlds.isChunkLoaded(world, x, z))
            assertTrue(worlds.loadChunk(world, x, z))
            assertTrue((x to z) in worlds.loadedChunks(world))
            assertFalse((farX to farZ) in worlds.loadedChunks(world))
            assertEquals(emptyList(), worlds.loadedChunks(MISSING_WORLD))
            assertFalse(worlds.isChunkLoaded(world, farX, farZ))
            assertNull(worlds.highestBlockY(world, farX * 16, farZ * 16))
            assertFalse(worlds.entitiesLoaded(Location(world, farX * 16.0, origin.y, farZ * 16.0)))
            assertFalse(worlds.isChunkLoaded(MISSING_WORLD, x, z))
            assertFalse(worlds.loadChunk(MISSING_WORLD, x, z))
        }
    }

    @Test
    fun `a held chunk stays loaded, its entities too, until it's released as often as it was held`() {
        // Beside the far chunk, which other suites need unloaded: nobody is near this one either.
        val (x, z) = server.unloadedChunk().let { (farX, farZ) -> (farX + 4) to farZ }
        val at = Location(world, x * 16.0 + 8, origin.y, z * 16.0 + 8)
        main {
            assertTrue(worlds.holdChunk(world, x, z))
            assertTrue(worlds.holdChunk(world, x, z))
            assertTrue(worlds.isChunkLoaded(world, x, z), "held is loaded at once")
        }
        ticks(20)
        main {
            assertTrue(worlds.isChunkLoaded(world, x, z), "a chunk loaded only for a call would be gone by now")
            worlds.releaseChunk(world, x, z)
        }
        ticks(20)
        main { assertTrue(worlds.isChunkLoaded(world, x, z), "held twice, released once") }
        eventually("the held chunk's entities loaded") { worlds.entitiesLoaded(at) }
        main {
            worlds.releaseChunk(world, x, z)
            // Released more often than held: nothing to let go of.
            worlds.releaseChunk(world, x, z)
            assertFalse(worlds.holdChunk(MISSING_WORLD, x, z))
            worlds.releaseChunk(MISSING_WORLD, x, z)
        }
    }

    @Test
    fun `the highest block of a column is the top one that isn't air`() {
        main {
            val top = at(-3, 6, 2)
            place(top, "minecraft:stone")
            val (x, y, z) = block(top)
            assertEquals(y, worlds.highestBlockY(world, x, z))
            assertEquals(block(origin).second - 1, worlds.highestBlockY(world, x + 1, z))
        }
    }

    @Test
    fun `a biome is a namespaced id where the chunk is loaded, and nothing elsewhere`() {
        val (x, y, z) = block(origin)
        val (farX, farZ) = server.unloadedChunk()
        main {
            val biome = assertNotNull(worlds.biome(world, x, y, z))
            assertTrue(biome.matches(Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")), biome)
            assertNull(worlds.biome(world, farX * 16, y, farZ * 16))
            assertNull(worlds.biome(MISSING_WORLD, x, y, z))
        }
    }

    @Test
    fun `a biome search finds the biome a place has right there, runs off the main thread and loads nothing`() {
        val (x, y, z) = block(origin)
        val (farX, farZ) = server.unloadedChunk()
        val here = main { assertNotNull(worlds.biome(world, x, y, z)) }
        val elsewhere = assertNotNull(platform.game.registry(RegistryKey.BIOME)?.firstOrNull { it != here }, "the server has another biome")
        val (found, far, nowhere) = main {
            assertNull(worlds.biomeSearch(MISSING_WORLD, x, y, z, 64, here), "no world")
            assertNull(worlds.biomeSearch(world, x, y, z, 64, "$here.no_such_biome"), "no biome")
            Triple(
                assertNotNull(worlds.biomeSearch(world, x, y, z, 64, here)),
                assertNotNull(worlds.biomeSearch(world, farX * 16, y, farZ * 16, 64, here)),
                assertNotNull(worlds.biomeSearch(world, x, y, z, 64, elsewhere))
            )
        }
        // Here, on the suite's own thread, as the runtime's worker runs it.
        assertEquals(Vec3(x.toDouble(), y.toDouble(), z.toDouble()), found.run(), "where it starts is the first place it looks")
        assertEquals(Vec3(farX * 16.0, y.toDouble(), farZ * 16.0), far.run(), "found in a chunk that isn't loaded")
        assertNull(nowhere.run(), "the world's generator puts $elsewhere nowhere near")
        main { assertFalse(worlds.isChunkLoaded(world, farX, farZ), "searching loaded nothing") }
    }

    @Test
    fun `explosions and lightning happen in worlds that exist`() {
        main {
            val high = Vec3(origin.x, origin.y + 40, origin.z)
            assertTrue(worlds.explode(world, high, 1.0, fire = false, breakBlocks = false))
            assertTrue(worlds.strikeLightning(world, high, effectOnly = true))
            assertFalse(worlds.explode(MISSING_WORLD, high, 1.0, fire = false, breakBlocks = false))
            assertFalse(worlds.strikeLightning(MISSING_WORLD, high, effectOnly = true))
        }
    }

    @Test
    fun `a ray stops at the first block with a collision shape`() {
        main {
            val wall = at(5, 3)
            place(wall, "minecraft:stone")
            val (x, y, z) = block(wall)
            val from = Vec3(origin.x, y + 0.5, z + 0.5)
            val hit = assertNotNull(worlds.raycastBlocks(world, from, Vec3(1.0, 0.0, 0.0), 10.0, fluids = false))
            assertEquals(Triple(x, y, z), Triple(hit.x, hit.y, hit.z))
            assertNear(x.toDouble(), hit.position.x)
            assertEquals(Vec3(-1.0, 0.0, 0.0), hit.normal)
            assertNull(worlds.raycastBlocks(world, from, Vec3(1.0, 0.0, 0.0), 2.0, fluids = false), "out of reach")
            assertNull(worlds.raycastBlocks(world, from, Vec3(0.0, 1.0, 0.0), 10.0, fluids = false), "only air above")
            assertNull(worlds.raycastBlocks(MISSING_WORLD, from, Vec3(1.0, 0.0, 0.0), 10.0, fluids = false))
        }
    }

    @Test
    fun `collision boxes are each block's shape in world coordinates`() {
        main {
            val stone = at(-5, 4, -4)
            val slab = at(-4, 4, -4)
            place(stone, "minecraft:stone")
            place(slab, "minecraft:oak_slab[type=bottom,waterlogged=false]")
            val (x, y, z) = block(stone)
            val boxes = worlds.collisionBoxes(world, x + 0.1, y + 0.1, z + 0.1, x + 1.9, y + 0.9, z + 0.9).toSet()
            val corner = Vec3(x.toDouble(), y.toDouble(), z.toDouble())
            assertEquals(
                setOf(
                    Box(corner, corner + Vec3(1.0, 1.0, 1.0)),
                    Box(corner + Vec3(1.0, 0.0, 0.0), corner + Vec3(2.0, 0.5, 1.0))
                ),
                boxes
            )
            assertEquals(emptyList(), worlds.collisionBoxes(world, x + 0.1, y + 2.1, z + 0.1, x + 0.9, y + 2.9, z + 0.9), "air")
            assertEquals(emptyList(), worlds.collisionBoxes(MISSING_WORLD, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
        }
    }

    protected companion object {
        const val KEEP_INVENTORY = "minecraft:keep_inventory"
        const val RANDOM_TICK_SPEED = "minecraft:random_tick_speed"
    }
}
