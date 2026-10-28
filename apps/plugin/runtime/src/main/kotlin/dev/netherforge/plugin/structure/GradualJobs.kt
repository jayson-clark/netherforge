package dev.netherforge.plugin.structure
import dev.netherforge.plugin.async.WorkFailed
import dev.netherforge.plugin.platform.BlockVector
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.StructurePlacement
import dev.netherforge.plugin.platform.StructurePlacer
import dev.netherforge.plugin.platform.StructureTransform
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.TickPhase
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.math.max
import kotlin.math.min

/**
 * `world:place_gradually` and `world:fill_gradually`: work on the world spread over ticks, a scope's own.
 *
 * Every job going shares the owner's `structures.blocks-per-tick` each tick ([StructuresConfig]), handed out in
 * turn (the first to be served moves round every tick) and in equal shares, each job never past its own
 * `per_tick`, so two scripts can't double the load and one can't starve another. A package has at most
 * `structures.max-jobs-per-package` going: past that, a new one fails at once. A job ends when it's done (its
 * value is what it did), when the world goes or a part can't be read (it fails, saying why), or when its scope
 * is released or the session stops (it's dropped: nobody's left to hear). What it did stays.
 *
 * A placement goes through a build's parts one at a time, reading each (`StructureOps.placer`) only while it's
 * placed, so a big build is never all in memory; entities go last, after every block (an item frame needs its
 * wall), from a second read of only the parts that hold any.
 */
internal class GradualJobs(
    private val platform: Platform,
    private val config: () -> StructuresConfig,
    /** A placement put entities in: the centities their markers ask for can spawn ([StructureSpawns]). */
    private val placedWithEntities: () -> Unit,
    /** A fill set a block: custom blocks forget the one it replaced. */
    private val replaced: (world: String, x: Int, y: Int, z: Int, state: String) -> Unit
) : RuntimeService {
    override val name get() = "gradual placements"

    private val jobs = ArrayList<Job>()

    /** Which job is served first this tick. */
    private var turn = 0

    /** Jobs [scope] has going. */
    fun count(scope: Scope): Int = jobs.count { it.scope === scope }

    /**
     * Places [pieces] in [world] with what they make up's smallest corner at [origin], as [placement] says,
     * at most [perTick] blocks a tick (null: its share). Its value is how many blocks it placed.
     */
    fun place(
        scope: Scope,
        world: String,
        pieces: List<Piece>,
        origin: BlockVector,
        placement: StructurePlacement,
        perTick: Int?
    ): CompletionStage<Long> = start(PlaceJob(scope, perTick, world, pieces, origin, placement, seed = System.nanoTime()))

    /** Sets every block from [min] to [max] in [world] to [state] (already the server's spelling). Its value is how many it changed. */
    fun fill(
        scope: Scope,
        world: String,
        min: BlockVector,
        max: BlockVector,
        state: String,
        update: Boolean,
        perTick: Int?
    ): CompletionStage<Long> = start(FillJob(scope, perTick, world, min, max, state, update))

    private fun start(job: Job): CompletionStage<Long> {
        val limit = config().maxJobsPerPackage
        val going = jobs.count { it.scope.namespace == job.scope.namespace }
        if (going >= limit) {
            return CompletableFuture.failedFuture(
                WorkFailed(
                    "package \"${job.scope.namespace}\" already has $going gradual placements and fills going, the server's limit " +
                        "(structures.max-jobs-per-package in config.yml)"
                )
            )
        }
        jobs += job
        return job.result
    }

    override fun tick(phase: TickPhase) {
        if (phase != TickPhase.WORLD || jobs.isEmpty()) return
        val order = jobs.indices.map { jobs[(it + turn) % jobs.size] }
        turn = (turn + 1) % max(1, jobs.size)
        val used = IntArray(order.size)
        var budget = config().blocksPerTick
        while (budget > 0) {
            val wanting = order.indices.filter { !order[it].done && used[it] < order[it].perTick }
            if (wanting.isEmpty()) break
            val share = max(1, budget / wanting.size)
            var moved = false
            for (i in wanting) {
                if (budget <= 0) break
                val job = order[i]
                val went = job.run(min(min(share, job.perTick - used[i]), budget))
                used[i] += went
                budget -= went
                if (went > 0) moved = true
            }
            if (!moved) break
        }
        jobs.removeAll { it.done }
    }

    override fun scopeReleased(scope: Scope) {
        jobs.filter { it.scope === scope }.forEach { it.drop("its script stopped") }
        jobs.removeAll { it.done }
    }

    override fun stop() {
        jobs.forEach { it.drop("the server's project stopped") }
        jobs.clear()
    }

    override fun costs(): Map<String, (Scope) -> Int> = mapOf("gradual" to ::count)

    private abstract inner class Job(val scope: Scope, perTick: Int?) {
        val perTick: Int = perTick ?: Int.MAX_VALUE
        val result = CompletableFuture<Long>()
        var done = false
            private set

        /** Goes through up to [budget] blocks; answers how many it went through. Calls [finish] or [fail] when it's over. */
        protected abstract fun step(budget: Int): Int

        /** Lets go of what it holds. */
        protected open fun close() {}

        fun run(budget: Int): Int = try {
            step(budget)
        } catch (failure: Exception) {
            fail("it failed unexpectedly: ${failure.message ?: failure.javaClass.simpleName}")
            budget
        }

        protected fun finish(value: Long) {
            done = true
            close()
            result.complete(value)
        }

        protected fun fail(why: String) {
            done = true
            close()
            result.completeExceptionally(WorkFailed(why))
        }

        fun drop(why: String) {
            if (!done) fail(why)
        }
    }

    private inner class PlaceJob(
        scope: Scope,
        perTick: Int?,
        private val world: String,
        private val pieces: List<Piece>,
        private val origin: BlockVector,
        private val placement: StructurePlacement,
        private val seed: Long
    ) : Job(scope, perTick) {
        private var index = 0
        private var placer: StructurePlacer? = null
        private var placed = 0L

        /** Pieces whose entities go in once every block is: their indexes. */
        private val withEntities = ArrayList<Int>()
        private var entitiesDone = 0

        private fun open(index: Int): StructurePlacer? {
            val piece = pieces[index]
            val transform = StructureTransform(origin, piece.offset, placement.rotation, placement.mirror)
            val opened = platform.structures.placer(piece.file, world, transform, placement, seed + index)
            if (opened == null) {
                fail(if (!platform.worlds.exists(world)) gone() else "${piece.shown} can't be read as a structure")
                return null
            }
            piece.check(opened.size)?.let {
                fail(it)
                return null
            }
            return opened
        }

        private fun gone() = "the world \"$world\" has gone"

        override fun step(budget: Int): Int {
            var went = 0
            while (went < budget && !done) {
                if (index < pieces.size) {
                    val current = placer ?: open(index)?.also { placer = it } ?: return max(went, 1)
                    val moved = current.place(budget - went) ?: run {
                        fail(gone())
                        return max(went, 1)
                    }
                    went += moved
                    if (current.remaining == 0) {
                        placed += current.placed
                        if (placement.entities && current.hasEntities) withEntities += index
                        placer = null
                        index++
                    } else if (moved == 0) {
                        break
                    }
                } else if (entitiesDone < withEntities.size) {
                    // Read again for its entities alone: its blocks are long in.
                    val piece = open(withEntities[entitiesDone]) ?: return max(went, 1)
                    if (!piece.entities()) {
                        fail(gone())
                        return max(went, 1)
                    }
                    entitiesDone++
                    went += ENTITY_PASS_COST
                }
                if (index == pieces.size && entitiesDone == withEntities.size && !done) {
                    if (withEntities.isNotEmpty()) placedWithEntities()
                    finish(placed)
                }
            }
            return went
        }

        override fun close() {
            placer = null
        }
    }

    private inner class FillJob(
        scope: Scope,
        perTick: Int?,
        private val world: String,
        private val min: BlockVector,
        private val max: BlockVector,
        private val state: String,
        private val update: Boolean
    ) : Job(scope, perTick) {
        /** The chunk columns the box covers, in order: x fastest, then z. */
        private val columns: List<Pair<Int, Int>> = buildList {
            for (cz in (min.z shr 4)..(max.z shr 4)) for (cx in (min.x shr 4)..(max.x shr 4)) add(cx to cz)
        }
        private var column = 0

        /** How far into the current column it is, bottom layer first, x then z within a layer. */
        private var at = 0L
        private var changed = 0L

        override fun step(budget: Int): Int {
            if (!platform.worlds.exists(world)) {
                fail("the world \"$world\" has gone")
                return 1
            }
            val (bottom, top) = platform.worlds.heights(world) ?: run {
                fail("the world \"$world\" has gone")
                return 1
            }
            var went = 0
            while (went < budget && !done) {
                val (cx, cz) = columns[column]
                val x0 = max(min.x, cx shl 4)
                val x1 = min(max.x, (cx shl 4) + 15)
                val z0 = max(min.z, cz shl 4)
                val z1 = min(max.z, (cz shl 4) + 15)
                val width = x1 - x0 + 1
                val depth = z1 - z0 + 1
                val layer = width.toLong() * depth
                val volume = layer * (max.y - min.y + 1)
                if (at == 0L && !platform.worlds.isChunkLoaded(world, cx, cz) && !platform.worlds.loadChunk(world, cx, cz)) {
                    fail("the world \"$world\" has gone")
                    return max(went, 1)
                }
                val count = min((budget - went).toLong(), volume - at).toInt()
                for (i in 0 until count) {
                    val k = at + i
                    val y = min.y + (k / layer).toInt()
                    if (y < bottom || y >= top) continue
                    val inLayer = (k % layer).toInt()
                    val x = x0 + inLayer / depth
                    val z = z0 + inLayer % depth
                    if (platform.blocks.set(world, x, y, z, state, update)) {
                        changed++
                        replaced(world, x, y, z, state)
                    }
                }
                at += count
                went += count
                if (at == volume) {
                    column++
                    at = 0
                    if (column == columns.size) finish(changed)
                }
            }
            return went
        }
    }

    private companion object {
        /** What placing one part's entities counts as against the tick's blocks: a part's worth of reading. */
        const val ENTITY_PASS_COST = 1024
    }
}

/**
 * One file of what [GradualJobs.place] places: a structure's one, or a build's part at [offset] in the build.
 * [check] answers why the file's size is wrong for its place (a build's part that isn't the size its place in the
 * grid says), or null.
 */
internal class Piece(val file: Path, val shown: String, val offset: BlockVector, val check: (BlockVector) -> String? = { null })
