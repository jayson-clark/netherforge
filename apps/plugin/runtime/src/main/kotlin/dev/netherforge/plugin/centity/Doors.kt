package dev.netherforge.plugin.centity

import dev.netherforge.format.game.Box
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.pathing.BlockShapes
import dev.netherforge.plugin.platform.Platform

/** One block of a door (or a fence gate) a walk opened. */
internal data class Doorway(val x: Int, val y: Int, val z: Int) {
    /** Whether [feet] stand in this door's column of blocks (a door's two blocks are each a doorway). */
    fun holds(feet: Vec3): Boolean = kotlin.math.floor(feet.x).toInt() == x && kotlin.math.floor(feet.z).toInt() == z
}

/**
 * The game's wooden doors and fence gates for walks that go through them (`move_to`'s `open_doors`): a search
 * sees them as open, and a walk opens each as it comes within reach and shuts it again once it's through.
 * Iron doors (which need redstone) aren't among them.
 */
internal class Doors(private val platform: Platform) {
    private val kinds: Set<String> by lazy {
        DOOR_TAGS.flatMap { platform.game.tag(RegistryKey.BLOCK, it).orEmpty() }.toSet()
    }

    /** The state at a block when it's one of the doors a walk can open. */
    private fun doorAt(world: String, x: Int, y: Int, z: Int): String? =
        platform.blocks.get(world, x, y, z)?.state?.takeIf { it.substringBefore('[') in kinds }

    /** [shapes] with every door seen as open: what a search for a walk that opens them goes through. */
    fun through(world: String, shapes: BlockShapes): BlockShapes = object : BlockShapes {
        override fun boxes(x: Int, y: Int, z: Int): List<Box>? {
            val found = shapes.boxes(x, y, z) ?: return null
            return if (found.isNotEmpty() && doorAt(world, x, y, z) != null) emptyList() else found
        }
    }

    /** Opens the shut doors on [walk]'s way within reach of its feet, and shuts the ones it [opened] and has left behind. */
    fun pass(world: String, walk: Walk, opened: MutableList<Doorway>) {
        val feet = walk.feet
        // Along its path from its feet, a little at a time (a straight stretch is only its two ends), as far as it reaches.
        var at = feet
        var left = REACH
        var next = walk.next
        val seen = HashSet<Doorway>()
        while (left > 0) {
            for (dy in 0 until DOOR_HEIGHT) {
                val cell = Doorway(kotlin.math.floor(at.x).toInt(), kotlin.math.floor(at.y).toInt() + dy, kotlin.math.floor(at.z).toInt())
                if (seen.add(cell)) open(world, opened, cell)
            }
            if (next >= walk.points.size) break
            val to = walk.points[next]
            val d = distance(at, to)
            if (d <= STEP) {
                at = to
                next++
                left -= d
            } else {
                at = Vec3(at.x + (to.x - at.x) * STEP / d, at.y + (to.y - at.y) * STEP / d, at.z + (to.z - at.z) * STEP / d)
                left -= STEP
            }
        }
        shut(world, opened) { distance(Vec3(it.x + 0.5, it.y.toDouble(), it.z + 0.5), feet) > LEAVE }
    }

    private fun open(world: String, opened: MutableList<Doorway>, cell: Doorway) {
        val state = doorAt(world, cell.x, cell.y, cell.z) ?: return
        if (!state.contains("open=false")) return
        if (platform.blocks.set(world, cell.x, cell.y, cell.z, state.replace("open=false", "open=true"), false)) opened += cell
    }

    /** Shuts the doors in [opened] that are [done] with, and forgets them (one shut or broken since is just forgotten). */
    fun shut(world: String, opened: MutableList<Doorway>, done: (Doorway) -> Boolean) {
        val iterator = opened.iterator()
        while (iterator.hasNext()) {
            val door = iterator.next()
            if (!done(door)) continue
            iterator.remove()
            val state = doorAt(world, door.x, door.y, door.z) ?: continue
            if (state.contains(
                    "open=true"
                )
            ) {
                platform.blocks.set(world, door.x, door.y, door.z, state.replace("open=true", "open=false"), false)
            }
        }
    }

    private fun distance(a: Vec3, b: Vec3): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    private companion object {
        val DOOR_TAGS = listOf("minecraft:wooden_doors", "minecraft:fence_gates")

        /** How far apart the places along its path it looks at are, in blocks. */
        const val STEP = 0.25

        /** How near its feet a door on its way is opened, in blocks. */
        const val REACH = 1.6

        /** How far it has gone from a door it opened before shutting it, in blocks. */
        const val LEAVE = 2.0

        /** A door's two blocks, and a gate's one. */
        const val DOOR_HEIGHT = 2
    }
}
