package dev.netherforge.plugin.contract

import dev.netherforge.format.block.BlockCarrier
import dev.netherforge.format.bridge.BotEvent
import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.platform.BlockOps
import dev.netherforge.plugin.platform.ParticleOps
import dev.netherforge.plugin.platform.ParticleSpawn
import dev.netherforge.plugin.platform.SoundOps
import dev.netherforge.plugin.platform.SoundPlay
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [BlockOps]: blocks by world and position, and the data scripts keep on them. */
abstract class BlockOpsContract : PlatformContract() {
    private val blocks: BlockOps get() = platform.blocks

    @Test
    fun `a block reads back as set`() {
        main {
            val spot = at(2, 3, -6)
            val (x, y, z) = block(spot)
            val air = assertNotNull(blocks.get(world, x, y, z))
            assertEquals("minecraft:air", air.state)
            assertTrue(air.air)
            assertFalse(air.solid)
            assertFalse(air.liquid)
            place(spot, "minecraft:stone")
            val stone = assertNotNull(blocks.get(world, x, y, z))
            assertEquals("minecraft:stone", stone.state)
            assertFalse(stone.air)
            assertTrue(stone.solid)
            place(spot, "minecraft:oak_slab[type=top,waterlogged=false]")
            assertEquals("minecraft:oak_slab[type=top,waterlogged=false]", blocks.get(world, x, y, z)?.state)
        }
    }

    @Test
    fun `setting a block with neighbours told also reads back`() {
        main {
            val spot = at(3, 3, -6)
            val (x, y, z) = block(spot)
            place(spot, "minecraft:air")
            assertTrue(blocks.set(world, x, y, z, "minecraft:gold_block", true))
            assertEquals("minecraft:gold_block", blocks.get(world, x, y, z)?.state)
        }
    }

    @Test
    fun `breaking a block leaves air, and air can't be broken`() {
        main {
            val spot = at(4, 3, -6)
            val (x, y, z) = block(spot)
            place(spot, "minecraft:stone")
            assertTrue(blocks.breakNaturally(world, x, y, z, null))
            assertEquals("minecraft:air", blocks.get(world, x, y, z)?.state)
            assertFalse(blocks.breakNaturally(world, x, y, z, null))
            place(spot, "minecraft:oak_planks")
            assertTrue(blocks.breakNaturally(world, x, y, z, item("minecraft:iron_sword")))
            assertEquals("minecraft:air", blocks.get(world, x, y, z)?.state)
        }
    }

    @Test
    fun `a position's data reads back as set, and clears`() {
        main {
            val (x, y, z) = block(at(5, 3, -6))
            afterwards { blocks.setData(world, x, y, z, null) }
            assertNull(blocks.data(world, x, y, z))
            assertTrue(blocks.setData(world, x, y, z, """{"a":1}"""))
            assertEquals("""{"a":1}""", blocks.data(world, x, y, z))
            assertNull(blocks.data(world, x + 1, y, z), "only that position")
            assertTrue(dev.netherforge.plugin.platform.BlockVector(x, y, z) in blocks.withData(world, x shr 4, z shr 4)!!)
            assertTrue(blocks.setData(world, x, y, z, null))
            assertNull(blocks.data(world, x, y, z))
            assertFalse(dev.netherforge.plugin.platform.BlockVector(x, y, z) in blocks.withData(world, x shr 4, z shr 4)!!)
        }
    }

    @Test
    fun `a custom block's record reads back with its chunk's, and clears`() {
        main {
            val (x, y, z) = block(at(6, 3, -6))
            afterwards { blocks.setRecord(world, x, y, z, null) }
            assertEquals(null, blocks.records(world, x shr 4, z shr 4)!!.firstOrNull { it.x == x && it.y == y && it.z == z })
            assertTrue(blocks.setRecord(world, x, y, z, """{"id":"ore"}"""))
            val record = blocks.records(world, x shr 4, z shr 4)!!.single { it.x == x && it.y == y && it.z == z }
            assertEquals("""{"id":"ore"}""", record.json)
            assertTrue(blocks.setRecord(world, x, y, z, null))
            assertTrue(blocks.records(world, x shr 4, z shr 4)!!.none { it.x == x && it.y == y && it.z == z })
        }
    }

    @Test
    fun `a chunk's legend reads back, apart from its blocks' records, and clears`() {
        main {
            val (x, y, z) = block(at(6, 3, -6))
            val cx = x shr 4
            val cz = z shr 4
            afterwards {
                blocks.setLegend(world, cx, cz, null)
                blocks.setRecord(world, x, y, z, null)
            }
            assertTrue(blocks.setLegend(world, cx, cz, null))
            assertNull(blocks.legend(world, cx, cz))
            assertTrue(blocks.setLegend(world, cx, cz, """{"a":"ore"}"""))
            assertEquals("""{"a":"ore"}""", blocks.legend(world, cx, cz))
            // It isn't one of the chunk's records, and a record isn't it.
            assertTrue(blocks.records(world, cx, cz)!!.none { it.json == """{"a":"ore"}""" })
            assertTrue(blocks.setRecord(world, x, y, z, """{"id":"ore"}"""))
            assertEquals("""{"a":"ore"}""", blocks.legend(world, cx, cz))
            assertTrue(blocks.setLegend(world, cx, cz, null))
            assertNull(blocks.legend(world, cx, cz))
        }
    }

    @Test
    fun `the blocks of some states in a chunk are found, with which state each is`() {
        main {
            val zombie = "minecraft:note_block[instrument=zombie,note=3,powered=false]"
            val skeleton = "minecraft:note_block[instrument=skeleton,note=7,powered=true]"
            val one = block(at(7, 3, -6))
            val two = block(at(8, 3, -6))
            place(at(7, 3, -6), zombie)
            place(at(8, 3, -6), skeleton)
            val found = blocks.find(world, one.first shr 4, one.third shr 4, listOf(zombie, skeleton))!!
            assertEquals(1, found[one.first and 15, one.second, one.third and 15])
            assertEquals(2, found[two.first and 15, two.second, two.third and 15])
            assertEquals(2, found.size)
            val other = blocks.find(
                world,
                one.first shr 4,
                one.third shr 4,
                listOf("minecraft:note_block[instrument=creeper,note=1,powered=false]")
            )!!
            assertTrue(other.isEmpty())
            val only = blocks.find(world, one.first shr 4, one.third shr 4, listOf(skeleton))!!
            assertEquals(0, only[one.first and 15, one.second, one.third and 15])
            assertEquals(1, only[two.first and 15, two.second, two.third and 15])
            assertEquals(1, only.size)
        }
    }

    @Test
    fun `how hard a state is and how a tool mines it`() {
        main {
            val stone = assertNotNull(blocks.hardness("minecraft:stone"))
            val note = assertNotNull(blocks.hardness("minecraft:note_block[instrument=zombie,note=3,powered=false]"))
            assertTrue(stone > note && note > 0, "stone $stone, a note block $note")
            assertNull(blocks.hardness("minecraft:no_such_block"))
            val pickaxe = item("minecraft:iron_pickaxe")
            assertNear(1.0, blocks.breakSpeed(null, "minecraft:stone"))
            assertTrue(blocks.breakSpeed(pickaxe, "minecraft:stone")!! > 1.0)
            assertNear(1.0, blocks.breakSpeed(pickaxe, "minecraft:oak_planks"))
            assertNull(blocks.breakSpeed(null, "minecraft:no_such_block"))
        }
    }

    @Test
    fun `whether a tool gets a block's drops, as the game counts it`() {
        main {
            val pickaxe = item("minecraft:iron_pickaxe")
            // Stone needs a pickaxe; planks need nothing in particular.
            assertEquals(false, blocks.harvests(null, "minecraft:stone"))
            assertEquals(true, blocks.harvests(pickaxe, "minecraft:stone"))
            assertEquals(true, blocks.harvests(null, "minecraft:oak_planks"))
            assertNull(blocks.harvests(null, "minecraft:no_such_block"))
        }
    }

    @Test
    fun `a block over air falls as a falling block, and one on the ground doesn't`() {
        main {
            val spot = at(6, 6, -6)
            val (x, y, z) = block(spot)
            place(at(6, 5, -6), "minecraft:air")
            place(spot, "minecraft:oak_planks")
            val falling = assertNotNull(blocks.fall(world, x, y, z))
            afterwards { platform.worldEntities.remove(falling) }
            assertEquals("minecraft:air", blocks.get(world, x, y, z)?.state)
            val grounded = at(7, 6, -6)
            val (gx, gy, gz) = block(grounded)
            place(at(7, 5, -6), "minecraft:stone")
            place(grounded, "minecraft:oak_planks")
            assertNull(blocks.fall(world, gx, gy, gz))
            assertEquals("minecraft:oak_planks", blocks.get(world, gx, gy, gz)?.state)
        }
    }

    @Test
    fun `a block can't be put where a player stands, and is placed for a player as they would place it`() {
        val player = join()
        main {
            val feet = block(origin)
            assertFalse(blocks.canPlace(world, feet.first, feet.second, feet.third, "minecraft:stone"))
            // String goes where anything stands: nothing collides with it.
            assertTrue(blocks.canPlace(world, feet.first, feet.second, feet.third, "minecraft:tripwire"))
            val free = block(at(4, 0, 4))
            assertTrue(blocks.canPlace(world, free.first, free.second, free.third, "minecraft:stone"))
            assertFalse(blocks.canPlace("nf_no_such_world", 0, 70, 0, "minecraft:stone"))
            place(at(4, -1, 4), "minecraft:stone")
            afterwards { blocks.set(world, free.first, free.second, free.third, "minecraft:air", false) }
            events.clear()
            val against = dev.netherforge.plugin.platform.BlockVector(free.first, free.second - 1, free.third)
            assertTrue(blocks.place(player.uuid, world, free.first, free.second, free.third, "minecraft:oak_planks", against, "main_hand"))
            assertEquals("minecraft:oak_planks", blocks.get(world, free.first, free.second, free.third)?.state)
            // The server's own event for it: a player placed a block.
            val heard = events.heard<dev.netherforge.plugin.platform.GameEvent.BlockPlace>("blockPlace").single { it.player == player }
            assertEquals("minecraft:oak_planks", heard.state)
            assertFalse(
                blocks.place(
                    java.util.UUID.randomUUID(),
                    world,
                    free.first,
                    free.second,
                    free.third + 1,
                    "minecraft:stone",
                    against,
                    "main_hand"
                )
            )
        }
    }

    @Test
    fun `the server is asked to leave note blocks alone, and agrees, again and again`() {
        main {
            for (carrier in BlockCarrier.entries) {
                assertTrue(blocks.freeze(carrier), "$carrier")
                assertTrue(blocks.freeze(carrier), "$carrier, again")
            }
        }
    }

    @Test
    fun `nothing in an unloaded chunk or a missing world`() {
        val (cx, cz) = server.unloadedChunk()
        main {
            val x = cx * 16 + 1
            val z = cz * 16 + 1
            assertNull(blocks.get(world, x, 70, z))
            assertFalse(blocks.set(world, x, 70, z, "minecraft:stone", false))
            assertFalse(blocks.breakNaturally(world, x, 70, z, null))
            assertFalse(blocks.setData(world, x, 70, z, "{}"))
            assertFalse(blocks.setRecord(world, x, 70, z, "{}"))
            assertNull(blocks.records(world, cx, cz))
            assertFalse(blocks.setLegend(world, cx, cz, "{}"))
            assertNull(blocks.legend(world, cx, cz))
            assertNull(blocks.find(world, cx, cz, listOf("minecraft:stone")))
            assertNull(blocks.withData(world, cx, cz))
            assertFalse(blocks.canPlace(world, x, 70, z, "minecraft:stone"))
            assertNull(blocks.get(MISSING_WORLD, 0, 70, 0))
            assertFalse(blocks.set(MISSING_WORLD, 0, 70, 0, "minecraft:stone", false))
        }
    }
}

/**
 * [ParticleOps]: batches of spawns to listed viewers. What reaches a viewer is
 * what their client is sent (a bot keeps it): each spawn as given, to the
 * listed viewers only, within 32 blocks (512 forced), of the particles the
 * game has.
 */
abstract class ParticleOpsContract : PlatformContract() {
    private val particles: ParticleOps get() = platform.particles

    private fun spawn(particle: String, dx: Double, dy: Double, count: Int, offset: Vec3, speed: Double, force: Boolean) =
        ParticleSpawn(particle, Vec3(origin.x + dx, origin.y + dy, origin.z), count, offset, speed, null, force)

    private fun received(player: dev.netherforge.plugin.platform.PlayerRef, since: Int) =
        sentSince(player, since).filterIsInstance<BotEvent.Particle>()

    @Test
    fun `each spawn reaches the listed viewers as it was given, and nobody else`() {
        val viewer = join()
        val other = join(at(2))
        val flame = spawn("minecraft:flame", 0.0, 1.0, 4, Vec3(0.25, 0.5, 0.25), 0.125, false)
        val rod = spawn("minecraft:end_rod", 0.0, 2.0, 0, Vec3(0.0, 1.0, 0.0), 0.5, true)
        val since = mark(viewer)
        val otherSince = mark(other)
        main { particles.spawn(world, listOf(flame, rod), listOf(viewer)) }
        val got = awaitSent(viewer, since, "both spawns") { events -> events.filterIsInstance<BotEvent.Particle>().takeIf { it.size >= 2 } }
        assertEquals(
            listOf(
                listOf("minecraft:flame", origin.x, origin.y + 1, origin.z, 4, 0.25, 0.5, 0.25, 0.125, false),
                listOf("minecraft:end_rod", origin.x, origin.y + 2, origin.z, 0, 0.0, 1.0, 0.0, 0.5, true)
            ),
            got.map { listOf(it.particle, it.x, it.y, it.z, it.count, it.dx, it.dy, it.dz, it.speed, it.forced) }
        )
        settled()
        assertEquals(emptyList(), received(other, otherSince), "not listed")
    }

    @Test
    fun `nothing reaches a viewer out of range, of a particle the game lacks, or in a world that's gone`() {
        val viewer = join()
        val since = mark(viewer)
        main {
            particles.spawn(world, listOf(spawn("minecraft:nf_no_such_particle", 0.0, 1.0, 1, Vec3.ZERO, 0.0, false)), listOf(viewer))
            particles.spawn(world, listOf(spawn("minecraft:flame", 64.0, 1.0, 1, Vec3.ZERO, 0.0, false)), listOf(viewer))
            particles.spawn(MISSING_WORLD, listOf(spawn("minecraft:flame", 0.0, 1.0, 1, Vec3.ZERO, 0.0, false)), listOf(viewer))
            particles.spawn(world, listOf(spawn("minecraft:flame", 0.0, 1.0, 1, Vec3.ZERO, 0.0, false)), emptyList())
            // Forced, it reaches past the 32 blocks; and what the adapter keeps between spawns may go at any time.
            particles.forget()
            particles.spawn(world, listOf(spawn("minecraft:end_rod", 64.0, 1.0, 1, Vec3.ZERO, 0.0, true)), listOf(viewer))
        }
        awaitSent(viewer, since, "the forced spawn") { events -> events.filterIsInstance<BotEvent.Particle>().firstOrNull { it.forced } }
        settled()
        assertEquals(listOf("minecraft:end_rod"), received(viewer, since).map { it.particle }, "only the forced spawn")
    }
}

/**
 * [SoundOps]: sounds in the world and to one player. What reaches a player is
 * what their client is sent: a world's sound by those in it within 16 blocks
 * (16 times the volume when that's more), one played to a player where they
 * are, and the stops.
 */
abstract class SoundOpsContract : PlatformContract() {
    private val sounds: SoundOps get() = platform.sounds
    private val click = SoundPlay("minecraft:ui.button.click", "master", 1.0, 1.0)

    private fun heard(player: dev.netherforge.plugin.platform.PlayerRef, since: Int) =
        sentSince(player, since).filterIsInstance<BotEvent.Sound>()

    @Test
    fun `sounds play in worlds that exist and to players online`() {
        val player = join()
        main {
            assertTrue(sounds.play(world, Vec3(origin.x, origin.y, origin.z), click))
            assertFalse(sounds.play(MISSING_WORLD, Vec3.ZERO, click))
            assertTrue(sounds.playTo(player.uuid, click))
            assertTrue(sounds.stop(player.uuid, "minecraft:ui.button.click"))
            assertTrue(sounds.stop(player.uuid, null))
            val offline = UUID.randomUUID()
            assertFalse(sounds.playTo(offline, click))
            assertFalse(sounds.stop(offline, null))
        }
    }

    @Test
    fun `a sound in the world is heard near it, as it was played, and not far off`() {
        val near = join(at(1))
        val far = join(at(36))
        val pling = SoundPlay("minecraft:block.note_block.pling", "block", 0.5, 1.5)
        val since = mark(near)
        val farSince = mark(far)
        main { assertTrue(sounds.play(world, Vec3(origin.x, origin.y, origin.z), pling)) }
        val sound = awaitSent(near, since, "the pling") { events ->
            events.filterIsInstance<BotEvent.Sound>().firstOrNull {
                it.sound ==
                    pling.sound
            }
        }
        assertEquals(listOf("minecraft:block.note_block.pling", 0.5, 1.5), listOf(sound.sound, sound.volume, sound.pitch))
        // The packet carries a position to an eighth of a block.
        assertNear(origin.x, sound.x, POSITION)
        assertNear(origin.y, sound.y, POSITION)
        assertNear(origin.z, sound.z, POSITION)
        settled()
        assertEquals(emptyList(), heard(far, farSince).filter { it.sound == pling.sound }, "36 blocks off")
    }

    @Test
    fun `a sound played to a player is heard where they are, by them alone, and stopping reaches their client`() {
        val player = join(at(2))
        val other = join()
        val since = mark(player)
        val otherSince = mark(other)
        main { assertTrue(sounds.playTo(player.uuid, click)) }
        val sound = awaitSent(player, since, "the click") { events ->
            events.filterIsInstance<BotEvent.Sound>().firstOrNull {
                it.sound ==
                    click.sound
            }
        }
        assertEquals(listOf("minecraft:ui.button.click", 1.0, 1.0), listOf(sound.sound, sound.volume, sound.pitch))
        assertNear(origin.x + 2, sound.x, POSITION, "where they are")
        main {
            assertTrue(sounds.stop(player.uuid, "minecraft:ui.button.click"))
            assertTrue(sounds.stop(player.uuid, null))
        }
        val stops =
            awaitSent(player, since, "both stops") { events -> events.filterIsInstance<BotEvent.StopSound>().takeIf { it.size >= 2 } }
        assertEquals(listOf("minecraft:ui.button.click", null), stops.map { it.sound })
        settled()
        assertEquals(emptyList(), heard(other, otherSince).filter { it.sound == click.sound }, "played to someone else")
    }

    private companion object {
        const val POSITION = 0.125
    }
}
