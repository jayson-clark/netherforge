package dev.netherforge.plugin.pathing

import dev.netherforge.format.math.Vec3
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A way found: [points] are where the walker's feet go, the first where it
 * starts (on the ground, for a walk), the last where the path ends. [complete]
 * when it ends at the goal; otherwise it ends at the closest place the search
 * reached. [expanded] is how many places it looked at.
 */
class PathResult(val points: List<Vec3>, val complete: Boolean, val expanded: Int)

/**
 * How far a search may go: no further than [range] blocks from its start
 * (horizontally for a walk, in any direction for a flight), and no more than
 * [nodes] places expanded, so a script can't stall the tick with a maze. Past
 * either it answers the closest place it reached.
 */
class SearchLimits(val range: Double, val nodes: Int = NODE_LIMIT)

/** How many places one search may expand: `NODE_LIMIT` in `spec/constants.ts`. */
const val NODE_LIMIT = 4096

/** How many grid points ahead smoothing looks for a straight line to. */
private const val LOOKAHEAD = 16

/** How far apart smoothing tests a straight line, in blocks. */
private const val SAMPLE = 0.25

/**
 * The A* both searches share: nodes in flat arrays, a binary heap of open
 * ones, the grid coordinates each was made at (relative to the start's cell),
 * and the closest one to the goal seen so far. What a node's neighbours are,
 * and what counts as arriving, is the search's own: [GroundSearch] walks
 * columns, [AirSearch] cells.
 */
internal class AStar(private val goal: Vec3) {
    var size = 0
        private set
    var x = DoubleArray(INITIAL)
    var y = DoubleArray(INITIAL)
    var z = DoubleArray(INITIAL)
    var g = DoubleArray(INITIAL)
    var h = DoubleArray(INITIAL)
    var parent = IntArray(INITIAL)
    var closed = BooleanArray(INITIAL)
    var ix = IntArray(INITIAL)
    var iy = IntArray(INITIAL)
    var iz = IntArray(INITIAL)
    private val index = HashMap<Long, Int>()

    private var heap = IntArray(INITIAL)
    private var heapF = DoubleArray(INITIAL)
    private var heapSize = 0

    /** The closest node to the goal yet: least [h], then least [g]. */
    var best = -1
        private set

    fun add(px: Double, py: Double, pz: Double, cx: Int, cy: Int, cz: Int, cost: Double, from: Int): Int {
        if (size == x.size) grow()
        val n = size++
        x[n] = px
        y[n] = py
        z[n] = pz
        ix[n] = cx
        iy[n] = cy
        iz[n] = cz
        g[n] = cost
        h[n] = distance(px, py, pz, goal.x, goal.y, goal.z)
        parent[n] = from
        closed[n] = false
        push(n)
        return n
    }

    /** Reaches the grid point [key] from [from] at [cost] more, if that's new or cheaper than before. */
    fun relax(key: Long, px: Double, py: Double, pz: Double, cx: Int, cy: Int, cz: Int, from: Int, cost: Double) {
        val through = g[from] + cost
        val known = index[key]
        if (known == null) {
            index[key] = add(px, py, pz, cx, cy, cz, through, from)
            return
        }
        if (closed[known] || through >= g[known] - 1e-9) return
        g[known] = through
        parent[known] = from
        push(known)
    }

    /** The next open node, cheapest first, closed as it's handed out; -1 when there's none. */
    fun pop(): Int {
        while (heapSize > 0) {
            val n = heap[0]
            heapSize--
            if (heapSize > 0) {
                heap[0] = heap[heapSize]
                heapF[0] = heapF[heapSize]
                down(0)
            }
            if (closed[n]) continue
            closed[n] = true
            if (best < 0 || h[n] < h[best] - 1e-9 || (abs(h[n] - h[best]) <= 1e-9 && g[n] < g[best])) best = n
            return n
        }
        return -1
    }

    /** The feet positions from the start to [end]. */
    fun pathTo(end: Int): MutableList<Vec3> {
        val out = ArrayList<Vec3>()
        var at = end
        while (at >= 0) {
            out += Vec3(x[at], y[at], z[at])
            at = parent[at]
        }
        out.reverse()
        return out
    }

    private fun push(n: Int) {
        if (heapSize == heap.size) {
            heap = heap.copyOf(heapSize * 2)
            heapF = heapF.copyOf(heapSize * 2)
        }
        // A hair over h breaks ties towards the goal, so open ground doesn't fan out.
        val f = g[n] + h[n] * (1 + 1e-3)
        var at = heapSize++
        while (at > 0) {
            val up = (at - 1) / 2
            if (heapF[up] <= f) break
            heap[at] = heap[up]
            heapF[at] = heapF[up]
            at = up
        }
        heap[at] = n
        heapF[at] = f
    }

    private fun down(from: Int) {
        val n = heap[from]
        val f = heapF[from]
        var at = from
        while (true) {
            var child = at * 2 + 1
            if (child >= heapSize) break
            if (child + 1 < heapSize && heapF[child + 1] < heapF[child]) child++
            if (heapF[child] >= f) break
            heap[at] = heap[child]
            heapF[at] = heapF[child]
            at = child
        }
        heap[at] = n
        heapF[at] = f
    }

    private fun grow() {
        val next = x.size * 2
        x = x.copyOf(next)
        y = y.copyOf(next)
        z = z.copyOf(next)
        g = g.copyOf(next)
        h = h.copyOf(next)
        parent = parent.copyOf(next)
        closed = closed.copyOf(next)
        ix = ix.copyOf(next)
        iy = iy.copyOf(next)
        iz = iz.copyOf(next)
    }

    companion object {
        private const val INITIAL = 256

        /** Grid coordinates relative to the start's cell, packed: 16 bits each for x and z, 32 for the third. */
        fun key(cx: Int, cz: Int, third: Int): Long =
            ((cx + 0x8000).toLong() shl 48) or ((cz + 0x8000).toLong() shl 32) or (third.toLong() and 0xffffffffL)
    }
}

internal fun distance(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): Double {
    val dx = bx - ax
    val dy = by - ay
    val dz = bz - az
    return sqrt(dx * dx + dy * dy + dz * dz)
}

private fun horizontal(ax: Double, az: Double, bx: Double, bz: Double): Double {
    val dx = bx - ax
    val dz = bz - az
    return sqrt(dx * dx + dz * dz)
}

/** The cell along one horizontal axis whose centre is nearest [v], for a grid centred at `cell + offset`. */
private fun cellOf(v: Double, offset: Double): Int = floor(v - offset + 0.5).toInt()

/**
 * Drops grid points a straight line can skip: from each point kept, the
 * furthest of the next [LOOKAHEAD] that [straight] says it can reach directly.
 */
private fun smooth(points: List<Vec3>, straight: (Vec3, Vec3) -> Boolean): List<Vec3> {
    if (points.size <= 2) return points
    val out = ArrayList<Vec3>()
    out += points[0]
    val last = points.size - 1
    var from = 0
    while (from < last) {
        var to = from + 1
        var next = from + 2
        while (next <= last && next - from <= LOOKAHEAD && straight(points[from], points[next])) {
            to = next
            next++
        }
        out += points[to]
        from = to
    }
    return out
}

/**
 * Walking: a search over **ground only**. A node is a column of the grid
 * (see [Walker.offset]) and a height the walker can stand at in it; its
 * neighbours are the eight columns around, each at the one height reachable
 * from it, found by one [Space.floorAt] look up to [stepHeight] above and
 * [maxDrop] below. So a walk looks at eight places a step and never at the
 * air above or below, which is why it costs no more for flying existing
 * ([AirSearch] is separate and only a flight pays for three dimensions).
 * A diagonal needs the box round both footprints clear, so it never cuts a
 * corner; stepping up needs head room where it stands.
 */
object GroundSearch {
    /** Steps up cost a little more than flat ground, and drops a little, so a level way round wins over a climb. */
    private const val STEP_COST = 0.5
    private const val DROP_COST = 0.2

    /** How close in each direction a column has to come to the goal to have arrived. */
    private const val ARRIVE_ACROSS = 0.75
    private const val ARRIVE_UP = 1.0

    /**
     * A way for [space]'s walker, feet at [start], to [goal] (feet), stepping up
     * to [stepHeight] and dropping up to [maxDrop]. It starts on the ground
     * under it, within [startDrop] below (it falls there first). No points when
     * there's no ground under it, or no way any closer to the goal than it is.
     */
    fun find(
        space: Space,
        start: Vec3,
        goal: Vec3,
        stepHeight: Double,
        maxDrop: Double,
        startDrop: Double,
        limits: SearchLimits
    ): PathResult {
        val walker = space.walker
        val offset = walker.offset
        val height = walker.height
        val ground = space.floorAt(start.x, start.z, start.y, stepHeight, startDrop)
        if (ground.isNaN()) return PathResult(emptyList(), false, 0)
        val baseX = cellOf(start.x, offset)
        val baseZ = cellOf(start.z, offset)
        val search = AStar(goal)
        search.add(start.x, ground, start.z, 0, 0, 0, 0.0, -1)
        var expanded = 0
        var arrived = -1
        while (true) {
            val n = search.pop()
            if (n < 0) break
            val px = search.x[n]
            val py = search.y[n]
            val pz = search.z[n]
            if (horizontal(px, pz, goal.x, goal.z) <= ARRIVE_ACROSS && abs(py - goal.y) <= ARRIVE_UP) {
                arrived = n
                break
            }
            if (expanded >= limits.nodes) break
            expanded++
            val first = n == 0
            for (dx in -1..1) {
                for (dz in -1..1) {
                    // The start isn't on the grid: its own column is a step too.
                    if (dx == 0 && dz == 0 && !first) continue
                    val cx = search.ix[n] + dx
                    val cz = search.iz[n] + dz
                    val qx = baseX + cx + offset
                    val qz = baseZ + cz + offset
                    if (horizontal(start.x, start.z, qx, qz) > limits.range) continue
                    val t = space.floorAt(qx, qz, py, stepHeight, maxDrop)
                    if (t.isNaN()) continue
                    val top = max(py, t)
                    if (!space.clearBetween(px, pz, qx, qz, top, top + height)) continue
                    if (t > py + Space.EPS && !space.clearAt(px, pz, py, t + height)) continue
                    val climb = if (t > py) STEP_COST * (t - py) else DROP_COST * (py - t)
                    val cost = distance(px, py, pz, qx, t, qz) + climb
                    search.relax(AStar.key(cx, cz, (t * 64).roundToInt()), qx, t, qz, cx, 0, cz, n, cost)
                }
            }
        }
        val end = if (arrived >= 0) arrived else search.best
        if (arrived < 0 && end == 0) return PathResult(emptyList(), false, expanded)
        val points = search.pathTo(end)
        if (arrived >= 0) {
            // The goal itself, when it's a step from where the search arrived.
            val last = points.last()
            val t = space.floorAt(goal.x, goal.z, last.y, stepHeight, maxDrop)
            if (!t.isNaN() && (abs(goal.x - last.x) > 1e-9 || abs(goal.z - last.z) > 1e-9)) {
                val top = max(last.y, t)
                val fits = space.clearBetween(last.x, last.z, goal.x, goal.z, top, top + height) &&
                    (t <= last.y + Space.EPS || space.clearAt(last.x, last.z, last.y, t + height))
                if (fits) points += Vec3(goal.x, t, goal.z)
            }
        }
        return PathResult(smooth(points) { a, b -> straight(space, a, b) }, arrived >= 0, expanded)
    }

    /**
     * Whether the walker can walk straight from [a] to [b] on level ground:
     * the same height, room all the way (each short stretch's box clear), and
     * held up at that height all the way, so it never walks off an edge.
     */
    fun straight(space: Space, a: Vec3, b: Vec3): Boolean {
        if (abs(a.y - b.y) > Space.EPS) return false
        val steps = max(1, ceil(horizontal(a.x, a.z, b.x, b.z) / SAMPLE).toInt())
        val top = a.y + space.walker.height
        var px = a.x
        var pz = a.z
        for (i in 1..steps) {
            val s = i.toDouble() / steps
            val sx = a.x + (b.x - a.x) * s
            val sz = a.z + (b.z - a.z) * s
            if (!space.clearBetween(px, pz, sx, sz, a.y, top)) return false
            if (!space.supported(sx, sz, a.y)) return false
            px = sx
            pz = sz
        }
        return true
    }
}

/**
 * Flying: a search through the air in three dimensions. A node is a cell of
 * the grid with the walker's feet at its bottom (whole blocks, so a two-high
 * walker fits a two-high hole); its neighbours are the 26 cells around, each
 * reachable when the box round the walker at both is clear. Nothing holds it
 * up and nothing has to.
 */
object AirSearch {
    /** How close a cell has to come to the goal to have arrived: any point is within this of some cell. */
    private const val ARRIVE = 1.0

    /** A way for [space]'s walker, feet at [start], to [goal] (feet) through the air. No points when there's no way any closer. */
    fun find(space: Space, start: Vec3, goal: Vec3, limits: SearchLimits): PathResult {
        val offset = space.walker.offset
        val baseX = cellOf(start.x, offset)
        val baseY = start.y.roundToInt()
        val baseZ = cellOf(start.z, offset)
        val search = AStar(goal)
        search.add(start.x, start.y, start.z, 0, 0, 0, 0.0, -1)
        var expanded = 0
        var arrived = -1
        while (true) {
            val n = search.pop()
            if (n < 0) break
            val px = search.x[n]
            val py = search.y[n]
            val pz = search.z[n]
            if (search.h[n] <= ARRIVE) {
                arrived = n
                break
            }
            if (expanded >= limits.nodes) break
            expanded++
            val first = n == 0
            for (dx in -1..1) {
                for (dy in -1..1) {
                    for (dz in -1..1) {
                        if (dx == 0 && dy == 0 && dz == 0 && !first) continue
                        val cx = search.ix[n] + dx
                        val cy = search.iy[n] + dy
                        val cz = search.iz[n] + dz
                        val qx = baseX + cx + offset
                        val qy = (baseY + cy).toDouble()
                        val qz = baseZ + cz + offset
                        if (distance(start.x, start.y, start.z, qx, qy, qz) > limits.range) continue
                        if (!space.clearFlying(px, py, pz, qx, qy, qz)) continue
                        val key = AStar.key(cx, cz, cy)
                        search.relax(key, qx, qy, qz, cx, cy, cz, n, distance(px, py, pz, qx, qy, qz))
                    }
                }
            }
        }
        val end = if (arrived >= 0) arrived else search.best
        if (arrived < 0 && end == 0) return PathResult(emptyList(), false, expanded)
        val points = search.pathTo(end)
        if (arrived >= 0) {
            val last = points.last()
            if (last != goal && straight(space, last, goal)) points += goal
        }
        return PathResult(smooth(points) { a, b -> straight(space, a, b) }, arrived >= 0, expanded)
    }

    /** Whether the walker can fly straight from [a] to [b]: each short stretch's box clear, so it never clips a corner. */
    fun straight(space: Space, a: Vec3, b: Vec3): Boolean {
        val steps = max(1, ceil(distance(a.x, a.y, a.z, b.x, b.y, b.z) / SAMPLE).toInt())
        var px = a.x
        var py = a.y
        var pz = a.z
        for (i in 1..steps) {
            val s = i.toDouble() / steps
            val sx = a.x + (b.x - a.x) * s
            val sy = a.y + (b.y - a.y) * s
            val sz = a.z + (b.z - a.z) * s
            if (!space.clearFlying(px, py, pz, sx, sy, sz)) return false
            px = sx
            py = sy
            pz = sz
        }
        return true
    }
}
