package dev.netherforge.plugin.centity

import dev.netherforge.format.game.Box
import dev.netherforge.format.math.Matrix4
import dev.netherforge.plugin.physics.boundsOf
import kotlin.math.floor

/**
 * Where every instance's hitboxes are, by world cell, so a physics body asks
 * only the instances whose boxes come near it instead of every one on the
 * server.
 *
 * An instance is measured ([Obstacles]) and filed under each cell its boxes'
 * bounds cover. Whatever changes its boxes (pose, facing, visibility, a
 * display, its anchor) marks it dirty through [Instance.reshaped], and it's
 * measured and filed again before the next question. Cells are padded on both
 * filing and asking, so rounding between anchor-relative and world
 * coordinates can never leave out an instance whose boxes reach the region.
 */
internal class ObstacleGrid(private val boxesOf: (Instance, Int) -> List<Box>) {
    /**
     * One instance's shown hitbox boxes, relative to its anchor along the world's axes, in node order, the
     * node index each box is of ([nodes], parallel to [boxes]), and the box around them all (null with none).
     */
    class Obstacles(val boxes: List<Box>, val nodes: IntArray, val bounds: Box?)

    /** World → cell key → the instances filed there. */
    private val worlds = HashMap<String, HashMap<Long, ArrayList<Instance>>>()

    /** Instances too big to file by cell: always candidates. */
    private val oversized = ArrayList<Instance>()

    private val dirty = LinkedHashSet<Instance>()

    /** Insertion order of the instances, as [Centities] walks them: candidates come back in it. */
    private var nextOrder = 0L

    /** Stamps each instance with the question it was last collected for, so one in several cells comes back once. */
    private var query = 0L

    /** The stamp of the last [near]: an instance carrying it was among the candidates. */
    val lastQuery: Long get() = query

    fun add(instance: Instance) {
        instance.order = nextOrder++
        instance.grid = this
        dirty += instance
    }

    fun remove(instance: Instance) {
        unfile(instance)
        dirty -= instance
        instance.grid = null
    }

    fun clear() {
        worlds.clear()
        oversized.clear()
        dirty.clear()
    }

    fun markDirty(instance: Instance) {
        dirty += instance
    }

    /** [instance]'s boxes as they stand now, measured once per change. */
    fun of(instance: Instance): Obstacles = instance.obstacles ?: measure(instance).also { instance.obstacles = it }

    /**
     * Every instance in [world] whose boxes may reach the world-space region
     * [minX]..[maxZ], in insertion order. A superset: the caller still tests
     * each box.
     */
    fun near(
        world: String,
        minX: Double,
        minY: Double,
        minZ: Double,
        maxX: Double,
        maxY: Double,
        maxZ: Double,
        out: MutableList<Instance>
    ) {
        flush()
        val stamp = ++query
        fun collect(instance: Instance) {
            if (instance.gridStamp == stamp) return
            instance.gridStamp = stamp
            out += instance
        }
        for (instance in oversized) if (instance.anchor.world == world) collect(instance)
        val cells = worlds[world]
        if (cells != null) {
            val x0 = cell(minX - PAD)
            val y0 = cell(minY - PAD)
            val z0 = cell(minZ - PAD)
            val x1 = cell(maxX + PAD)
            val y1 = cell(maxY + PAD)
            val z1 = cell(maxZ + PAD)
            if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1) * (z1 - z0 + 1) > MAX_QUERY_CELLS) {
                // A region this big is quicker walked instance by instance.
                for (list in cells.values) for (instance in list) collect(instance)
            } else {
                for (x in x0..x1) for (y in y0..y1) for (z in z0..z1) cells[key(x, y, z)]?.forEach(::collect)
            }
        }
        out.sortBy { it.order }
    }

    private fun flush() {
        if (dirty.isEmpty()) return
        for (instance in dirty) {
            unfile(instance)
            file(instance)
        }
        dirty.clear()
    }

    private fun file(instance: Instance) {
        if (instance.removed) return
        val bounds = of(instance).bounds ?: return
        val a = instance.anchor
        val x0 = cell(a.x + bounds.min.x - PAD)
        val y0 = cell(a.y + bounds.min.y - PAD)
        val z0 = cell(a.z + bounds.min.z - PAD)
        val x1 = cell(a.x + bounds.max.x + PAD)
        val y1 = cell(a.y + bounds.max.y + PAD)
        val z1 = cell(a.z + bounds.max.z + PAD)
        instance.gridWorld = a.world
        if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1) * (z1 - z0 + 1) > MAX_FILED_CELLS) {
            oversized += instance
            instance.gridOversized = true
            return
        }
        val cells = worlds.getOrPut(a.world) { HashMap() }
        val keys = LongArray((x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1))
        var i = 0
        for (x in x0..x1) {
            for (y in y0..y1) {
                for (z in z0..z1) {
                    val key = key(x, y, z)
                    cells.getOrPut(key) { ArrayList(2) } += instance
                    keys[i++] = key
                }
            }
        }
        instance.gridCells = keys
    }

    private fun unfile(instance: Instance) {
        if (instance.gridOversized) {
            oversized.remove(instance)
            instance.gridOversized = false
        }
        val keys = instance.gridCells ?: return
        val cells = worlds[instance.gridWorld]
        if (cells != null) {
            for (key in keys) {
                val list = cells[key] ?: continue
                list.remove(instance)
                if (list.isEmpty()) cells.remove(key)
            }
        }
        instance.gridCells = null
    }

    private fun measure(instance: Instance): Obstacles {
        val world = instance.placed()
        val boxes = mutableListOf<Box>()
        val nodes = mutableListOf<Int>()
        for ((index, node) in instance.definition.nodes.withIndex()) {
            if (node.hitbox == null || !instance.shown(index)) continue
            for (box in boxesOf(instance, index)) {
                boxes += boundsOf(listOf(box), world[index])
                nodes += index
            }
        }
        return Obstacles(boxes, nodes.toIntArray(), if (boxes.isEmpty()) null else boundsOf(boxes, Matrix4()))
    }

    private companion object {
        /** Blocks per cell along each axis. */
        const val CELL = 4.0

        /** Padding on every filing and question, far above any rounding between the two frames. */
        const val PAD = 0.01

        /** Past this many cells an instance goes on the always-asked list instead. */
        const val MAX_FILED_CELLS = 512L

        /** Past this many cells a question walks every filed instance instead. */
        const val MAX_QUERY_CELLS = 512L

        fun cell(coordinate: Double): Int = floor(coordinate / CELL).toInt()

        /**
         * 21 bits per axis. Cells further apart than that (8 million blocks)
         * share a key, which only adds candidates the caller then rules out.
         */
        fun key(x: Int, y: Int, z: Int): Long = ((x.toLong() and MASK) shl 42) or ((y.toLong() and MASK) shl 21) or (z.toLong() and MASK)

        const val MASK = (1L shl 21) - 1
    }
}
