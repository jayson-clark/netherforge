package dev.netherforge.plugin.pathing

import dev.netherforge.format.game.Box
import dev.netherforge.format.math.Vec3
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two searches alone, against a world of blocks in a map: ground solid
 * below y = 64 unless a test says otherwise, full cubes and any shapes a test
 * places, and columns that aren't loaded.
 */
class PathSearchTest {
    private class World : BlockShapes {
        var groundBelow: Int? = 64
        val cubes = HashSet<Triple<Int, Int, Int>>()
        val shapes = HashMap<Triple<Int, Int, Int>, List<Box>>()
        val holes = HashSet<Triple<Int, Int, Int>>()
        val unloaded = HashSet<Pair<Int, Int>>()

        /** Every block asked for: what a search costs. */
        var lookups = 0

        fun wall(x: IntRange, y: IntRange, z: IntRange) {
            for (bx in x) for (by in y) for (bz in z) cubes += Triple(bx, by, bz)
        }

        override fun boxes(x: Int, y: Int, z: Int): List<Box>? {
            lookups++
            if ((x to z) in unloaded) return null
            val at = Triple(x, y, z)
            shapes[at]?.let { local -> return local.map { Box(it.min + corner(at), it.max + corner(at)) } }
            val solid = at in cubes || (groundBelow?.let { y < it } == true && at !in holes)
            return if (solid) listOf(Box(corner(at), corner(at) + Vec3(1.0, 1.0, 1.0))) else emptyList()
        }

        private fun corner(at: Triple<Int, Int, Int>) = Vec3(at.first.toDouble(), at.second.toDouble(), at.third.toDouble())
    }

    private val player = Walker(0.6, 1.8)
    private val far = SearchLimits(48.0)

    private fun walk(
        world: World,
        from: Vec3,
        to: Vec3,
        walker: Walker = player,
        step: Double = 1.0,
        drop: Double = 3.0,
        limits: SearchLimits = far
    ) = GroundSearch.find(Space(world, walker), from, to, step, drop, 64.0, limits)

    private fun fly(world: World, from: Vec3, to: Vec3, walker: Walker = player, limits: SearchLimits = far) =
        AirSearch.find(Space(world, walker), from, to, limits)

    /** Walks [path] in short stretches and fails if the walker's box ever overlaps a block. */
    private fun assertNeverInside(world: World, walker: Walker, path: List<Vec3>, flying: Boolean = false) {
        val space = Space(world, walker)
        for ((a, b) in path.zipWithNext()) {
            val length = sqrt((b.x - a.x) * (b.x - a.x) + (b.z - a.z) * (b.z - a.z) + if (flying) (b.y - a.y) * (b.y - a.y) else 0.0)
            val steps = max(1, ceil(length / 0.05).toInt())
            for (i in 0..steps) {
                val s = i.toDouble() / steps
                val x = a.x + (b.x - a.x) * s
                val z = a.z + (b.z - a.z) * s
                // A walker goes along at the higher of the two heights (it steps up first, drops after).
                val y = if (flying) a.y + (b.y - a.y) * s else max(a.y, b.y)
                assertTrue(space.clearAt(x, z, y, y + walker.height), "inside a block at ($x, $y, $z) between $a and $b")
            }
        }
    }

    private fun Vec3.near(other: Vec3, within: Double = 1e-6) =
        sqrt((x - other.x) * (x - other.x) + (y - other.y) * (y - other.y) + (z - other.z) * (z - other.z)) <= within

    // ---- walking ------------------------------------------------------------------

    @Test
    fun `walks straight across open ground in one line`() {
        val world = World()
        val path = walk(world, Vec3(0.5, 64.0, 0.5), Vec3(10.5, 64.0, 3.5))
        assertTrue(path.complete)
        assertEquals(listOf(Vec3(0.5, 64.0, 0.5), Vec3(10.5, 64.0, 3.5)), path.points, "smoothed to a straight line")
    }

    @Test
    fun `walks round a wall without cutting its corners`() {
        val world = World()
        world.wall(5..5, 64..66, -4..4)
        val from = Vec3(0.5, 64.0, 0.5)
        val to = Vec3(10.5, 64.0, 0.5)
        val path = walk(world, from, to)
        assertTrue(path.complete)
        assertTrue(path.points.first().near(from) && path.points.last().near(to), "${path.points}")
        assertTrue(path.points.any { it.z > 4.5 || it.z < -3.5 }, "went round an end of the wall: ${path.points}")
        assertNeverInside(world, player, path.points)
        assertTrue(path.points.size <= 5, "smoothed, not a zigzag over the grid: ${path.points}")
    }

    @Test
    fun `squeezes diagonally past a corner only when the box round both steps is clear`() {
        val world = World()
        // Two blocks touching at a corner, with a gap a walker can't fit through diagonally.
        world.wall(1..1, 64..65, 0..0)
        world.wall(0..0, 64..65, 1..1)
        val path = walk(world, Vec3(0.5, 64.0, 0.5), Vec3(1.5, 64.0, 1.5))
        // Boxed in on two sides: the only ways out of (0, 0) are away from the goal and round.
        assertTrue(path.complete)
        assertNeverInside(world, player, path.points)
        assertTrue(path.points.size > 2, "can't go straight through the corner: ${path.points}")
    }

    @Test
    fun `steps up a block, and not with a lower step height`() {
        val world = World()
        world.wall(4..12, 64..64, -3..3)
        val to = Vec3(8.5, 65.0, 0.5)
        val climbing = walk(world, Vec3(0.5, 64.0, 0.5), to)
        assertTrue(climbing.complete)
        assertEquals(65.0, climbing.points.last().y)
        assertNeverInside(world, player, climbing.points)

        val low = walk(world, Vec3(0.5, 64.0, 0.5), to, step = 0.5)
        assertFalse(low.complete, "can't get up a whole block")
        val last = low.points.last()
        assertEquals(64.0, last.y, "got as close as it could, beside the step: $last")
        assertTrue(last.x in 7.5..9.5 && (last.z > 3 || last.z < -2), "right beside the goal: $last")
    }

    @Test
    fun `steps up slabs half a block at a time`() {
        val world = World()
        val slab = listOf(Box(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.5, 1.0)))
        for (z in -2..2) world.shapes[Triple(3, 64, z)] = slab
        world.wall(4..8, 64..64, -2..2)
        val path = walk(world, Vec3(0.5, 64.0, 0.5), Vec3(6.5, 65.0, 0.5), step = 0.6)
        assertTrue(path.complete, "${path.points}")
        assertTrue(path.points.any { it.y == 64.5 }, "stood on the slab on the way: ${path.points}")
    }

    @Test
    fun `drops down a cliff, as far as it's allowed`() {
        val world = World()
        world.groundBelow = 61
        world.wall(-5..4, 61..63, -5..5)
        val to = Vec3(9.5, 61.0, 0.5)
        val down = walk(world, Vec3(0.5, 64.0, 0.5), to)
        assertTrue(down.complete)
        assertEquals(61.0, down.points.last().y)
        assertNeverInside(world, player, down.points)

        val careful = walk(world, Vec3(0.5, 64.0, 0.5), to, drop = 2.0)
        assertFalse(careful.complete, "a three-block drop is further than it will go")
    }

    @Test
    fun `starts from the ground under it`() {
        val world = World()
        val path = walk(world, Vec3(0.5, 70.0, 0.5), Vec3(5.5, 64.0, 0.5))
        assertTrue(path.complete)
        assertEquals(64.0, path.points.first().y, "falls to the ground first")

        world.groundBelow = null
        assertTrue(walk(world, Vec3(0.5, 70.0, 0.5), Vec3(5.5, 64.0, 0.5)).points.isEmpty(), "nothing under it at all")
    }

    @Test
    fun `boxed in, there's no way any closer`() {
        val world = World()
        world.wall(-1..1, 64..66, -1..1)
        world.cubes -= Triple(0, 64, 0)
        world.cubes -= Triple(0, 65, 0)
        world.cubes -= Triple(0, 66, 0)
        val path = walk(world, Vec3(0.5, 64.0, 0.5), Vec3(10.5, 64.0, 0.5))
        assertTrue(path.points.isEmpty())
        assertFalse(path.complete)
    }

    @Test
    fun `a goal across a gap it can't cross ends as close as it gets`() {
        val world = World()
        // A trench too deep to drop into and climb out of.
        for (x in 3..4) for (z in -60..60) for (y in 40..63) world.holes += Triple(x, y, z)
        val path = walk(world, Vec3(0.5, 64.0, 0.5), Vec3(8.5, 64.0, 0.5), limits = SearchLimits(12.0))
        assertFalse(path.complete)
        val last = path.points.last()
        assertTrue(last.x < 3 && last.x > 1.5, "stopped at the near edge: $last")
    }

    @Test
    fun `a search stays within its range and node limit`() {
        val world = World()
        world.wall(5..5, 64..66, -100..100)
        val small = walk(world, Vec3(0.5, 64.0, 0.5), Vec3(10.5, 64.0, 0.5), limits = SearchLimits(48.0, nodes = 50))
        assertFalse(small.complete)
        assertTrue(small.expanded <= 50, "${small.expanded}")
        assertTrue(small.points.isNotEmpty(), "still answers the closest place it reached")

        val near = walk(world, Vec3(0.5, 64.0, 0.5), Vec3(10.5, 64.0, 0.5), limits = SearchLimits(8.0))
        assertFalse(near.complete)
        assertTrue(near.points.all { sqrt((it.x - 0.5) * (it.x - 0.5) + (it.z - 0.5) * (it.z - 0.5)) <= 8.0 + 1e-9 })
    }

    @Test
    fun `chunks that aren't loaded are walls`() {
        val world = World()
        for (z in -60..60) world.unloaded += 5 to z
        val path = walk(world, Vec3(0.5, 64.0, 0.5), Vec3(10.5, 64.0, 0.5), limits = SearchLimits(16.0))
        assertFalse(path.complete)
        assertTrue(path.points.all { it.x < 5 - 0.3 }, "never set foot in an unloaded column: ${path.points}")
    }

    @Test
    fun `a two-wide walker fits a two-wide gap, a three-wide one doesn't`() {
        val world = World()
        world.wall(5..5, 64..66, -30..30)
        world.cubes -= Triple(5, 64, 0)
        world.cubes -= Triple(5, 65, 0)
        world.cubes -= Triple(5, 64, 1)
        world.cubes -= Triple(5, 65, 1)
        val two = Walker(2.0, 1.5)
        val through = walk(world, Vec3(1.0, 64.0, 1.0), Vec3(9.0, 64.0, 1.0), walker = two)
        assertTrue(through.complete, "${through.points}")
        assertNeverInside(world, two, through.points)
        val three = walk(world, Vec3(1.5, 64.0, 1.5), Vec3(9.5, 64.0, 1.5), walker = Walker(3.0, 1.5), limits = SearchLimits(12.0))
        assertFalse(three.complete)
    }

    @Test
    fun `a low ceiling stops a tall walker`() {
        val world = World()
        // A tunnel one and a half blocks high, the only way through a wall.
        world.wall(4..6, 64..67, -30..30)
        for (x in 4..6) {
            world.cubes -= Triple(x, 64, 0)
            world.shapes[Triple(x, 65, 0)] = listOf(Box(Vec3(0.0, 0.5, 0.0), Vec3(1.0, 1.0, 1.0)))
        }
        assertTrue(walk(world, Vec3(0.5, 64.0, 0.5), Vec3(9.5, 64.0, 0.5), walker = Walker(0.6, 1.4)).complete)
        assertFalse(walk(world, Vec3(0.5, 64.0, 0.5), Vec3(9.5, 64.0, 0.5), limits = SearchLimits(12.0)).complete)
    }

    // ---- flying -------------------------------------------------------------------

    @Test
    fun `flies straight through open air`() {
        val world = World()
        val path = fly(world, Vec3(0.5, 70.0, 0.5), Vec3(8.5, 75.0, 4.5))
        assertTrue(path.complete)
        assertEquals(listOf(Vec3(0.5, 70.0, 0.5), Vec3(8.5, 75.0, 4.5)), path.points)
    }

    @Test
    fun `flies through a window walking can't reach`() {
        val world = World()
        world.wall(5..5, 64..80, -20..20)
        world.cubes -= Triple(5, 70, 0)
        world.cubes -= Triple(5, 71, 0)
        val from = Vec3(0.5, 64.0, 0.5)
        val to = Vec3(9.5, 64.0, 0.5)
        assertFalse(walk(world, from, to, limits = SearchLimits(16.0)).complete)
        val flight = fly(world, from, to, limits = SearchLimits(16.0))
        assertTrue(flight.complete, "${flight.points}")
        assertTrue(flight.points.any { it.y >= 69.9 && it.x in 4.0..6.0 }, "through the window: ${flight.points}")
        assertNeverInside(world, player, flight.points, flying = true)
    }

    @Test
    fun `flying never clips a corner either`() {
        val world = World()
        world.groundBelow = null
        world.wall(2..2, 60..80, 0..0)
        val path = fly(world, Vec3(0.5, 70.0, -1.5), Vec3(4.5, 70.0, 1.5))
        assertTrue(path.complete)
        assertNeverInside(world, player, path.points, flying = true)
    }

    // ---- what each costs ----------------------------------------------------------

    @Test
    fun `walking looks at fewer blocks than flying the same way`() {
        val walkWorld = World()
        walkWorld.wall(6..6, 64..66, -4..4)
        val walking = walk(walkWorld, Vec3(0.5, 64.0, 0.5), Vec3(12.5, 64.0, 0.5))
        val flyWorld = World()
        flyWorld.wall(6..6, 64..66, -4..4)
        val flying = fly(flyWorld, Vec3(0.5, 64.0, 0.5), Vec3(12.5, 64.0, 0.5))
        assertTrue(walking.complete && flying.complete)
        println(
            "round a wall: walking expanded ${walking.expanded} places, ${walkWorld.lookups} block lookups; flying ${flying.expanded}, ${flyWorld.lookups}"
        )
        assertTrue(walkWorld.lookups < flyWorld.lookups, "walk ${walkWorld.lookups} vs fly ${flyWorld.lookups}")
    }
}
