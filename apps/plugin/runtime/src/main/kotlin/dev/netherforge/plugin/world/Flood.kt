package dev.netherforge.plugin.world

import dev.netherforge.plugin.platform.BlockBoxRead
import dev.netherforge.plugin.platform.BlockOps
import dev.netherforge.plugin.platform.BoxState

/**
 * `world:flood`: a breadth-first fill through the blocks [passes] lets through, reading the world a
 * [BRICK]-sided box at a time ([BlockOps.box]), so a fill of thousands of blocks is a few reads. Every
 * question about a block is asked once per state of it ([kindOf] and [passes] are cached by state).
 */
internal class Flood(
    private val blocks: BlockOps,
    private val world: String,
    /** The world's lowest block and the one past its highest: the fill can't leave them. */
    private val bottom: Int,
    private val top: Int,
    /** Whether a block of [BoxState] is one to fill through. */
    private val passes: (BoxState) -> Boolean,
    /** What a block of a state is, for `kinds`. */
    private val kindOf: (BoxState) -> String
) {
    /** Why a fill stopped short, or null when it filled all it reaches. */
    enum class Stop(val luaName: String) { LIMIT("limit"), UNLOADED("unloaded"), BOUNDS("bounds") }

    /** What a fill found: blocks as packed positions ([pack]), in fill order. */
    class Result(
        val filled: LongArray,
        val filledStates: List<BoxState>,
        val edges: LongArray,
        val edgeStates: List<BoxState>,
        val stopped: Stop?
    )

    private class Brick(val read: BlockBoxRead?)

    private val bricks = HashMap<Long, Brick>()
    private val passing = HashMap<BoxState, Boolean>()

    /** The block at a position: its state, or null when its chunk isn't loaded (or it's past the world's top or bottom). */
    private fun state(x: Int, y: Int, z: Int): BoxState? {
        if (y < bottom || y >= top) return null
        val bx = Math.floorDiv(x, BRICK)
        val by = Math.floorDiv(y, BRICK)
        val bz = Math.floorDiv(z, BRICK)
        val brick = bricks.getOrPut(pack(bx, by, bz)) {
            val y0 = maxOf(by * BRICK, bottom)
            val y1 = minOf(by * BRICK + BRICK, top) - 1
            Brick(blocks.box(world, bx * BRICK, y0, bz * BRICK, bx * BRICK + BRICK - 1, y1, bz * BRICK + BRICK - 1))
        }
        val read = brick.read ?: return null
        val y0 = maxOf(by * BRICK, bottom)
        val lx = x - bx * BRICK
        val ly = y - y0
        val lz = z - bz * BRICK
        return read.states[read.cells[lx + BRICK * (lz + BRICK * ly)]]
    }

    private fun fills(state: BoxState) = passing.getOrPut(state) { passes(state) }

    /**
     * Fills from ([x], [y], [z]) along [directions] (offsets), at most [limit] blocks, never outside
     * [bounds] (min x, y, z, max x, y, z, both included) when given.
     */
    fun run(x: Int, y: Int, z: Int, directions: List<IntArray>, limit: Int, bounds: IntArray?): Result {
        val filled = LongArrayList()
        val filledStates = ArrayList<BoxState>()
        val edges = LongArrayList()
        val edgeStates = ArrayList<BoxState>()
        val seen = HashSet<Long>()
        var stopped: Stop? = null
        val start = state(x, y, z)
        if (start == null) return Result(LongArray(0), emptyList(), LongArray(0), emptyList(), Stop.UNLOADED)
        seen += pack(x, y, z)
        if (!fills(start)) {
            return Result(LongArray(0), emptyList(), longArrayOf(pack(x, y, z)), listOf(start), null)
        }
        filled += pack(x, y, z)
        filledStates += start
        var head = 0
        while (head < filled.size) {
            val at = filled[head++]
            val cx = unpackX(at)
            val cy = unpackY(at)
            val cz = unpackZ(at)
            for (d in directions) {
                val nx = cx + d[0]
                val ny = cy + d[1]
                val nz = cz + d[2]
                val key = pack(nx, ny, nz)
                if (!seen.add(key)) continue
                if (bounds != null &&
                    (nx < bounds[0] || ny < bounds[1] || nz < bounds[2] || nx > bounds[3] || ny > bounds[4] || nz > bounds[5])
                ) {
                    stopped = stopped ?: Stop.BOUNDS
                    continue
                }
                val state = state(nx, ny, nz)
                if (state == null) {
                    stopped = Stop.UNLOADED
                    continue
                }
                if (!fills(state)) {
                    edges += key
                    edgeStates += state
                    continue
                }
                if (filled.size >= limit) {
                    // One more it would have filled: there's more past the limit.
                    stopped = Stop.LIMIT
                    seen -= key
                    continue
                }
                filled += key
                filledStates += state
            }
        }
        return Result(filled.toArray(), filledStates, edges.toArray(), edgeStates, stopped)
    }

    fun kind(state: BoxState) = kindOf(state)

    /** A growable list of longs, so a big fill doesn't box every position. */
    private class LongArrayList {
        private var items = LongArray(64)
        var size = 0
            private set

        operator fun plusAssign(value: Long) {
            if (size == items.size) items = items.copyOf(size * 2)
            items[size++] = value
        }

        operator fun get(index: Int) = items[index]

        fun toArray(): LongArray = items.copyOf(size)
    }

    companion object {
        /** Blocks a side of each read: a chunk's width, so a brick is never half loaded. */
        const val BRICK = 16

        /** 26 bits each for x and z, 12 for y: a world's ±33 million blocks across and 4,096 high. */
        fun pack(x: Int, y: Int, z: Int): Long =
            ((x.toLong() and 0x3FFFFFF) shl 38) or ((z.toLong() and 0x3FFFFFF) shl 12) or (y.toLong() and 0xFFF)

        fun unpackX(p: Long): Int = ((p shr 38).toInt() shl 6) shr 6

        fun unpackZ(p: Long): Int = (((p shr 12) and 0x3FFFFFF).toInt() shl 6) shr 6

        fun unpackY(p: Long): Int = ((p and 0xFFF).toInt() shl 20) shr 20
    }
}
