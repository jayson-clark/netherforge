package dev.netherforge.plugin.block

import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.block.BlockCarrier
import dev.netherforge.format.block.BlockCarriers
import dev.netherforge.format.block.BlockFacing
import dev.netherforge.format.block.BlockFile
import dev.netherforge.format.block.BlockKind
import dev.netherforge.format.block.GameBlockFile
import dev.netherforge.format.block.GameBlockKind
import dev.netherforge.format.block.GameBlocks
import dev.netherforge.format.block.plan
import dev.netherforge.format.game.BlockState
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.project.Loaded
import dev.netherforge.format.project.ResourcePlace
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.plugin.RuntimeLog
import dev.netherforge.plugin.binding.BlockBreakEvent
import dev.netherforge.plugin.binding.BlockPosition
import dev.netherforge.plugin.binding.Events
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.ParticleOptions
import dev.netherforge.plugin.binding.ProjectBlockClickEvent
import dev.netherforge.plugin.binding.ProjectBlockPlaceEvent
import dev.netherforge.plugin.binding.ProjectBlockTickEvent
import dev.netherforge.plugin.binding.blockHandle
import dev.netherforge.plugin.centity.Centities
import dev.netherforge.plugin.item.Items
import dev.netherforge.plugin.loot.LootRoll
import dev.netherforge.plugin.loot.LootTables
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.BlockRef
import dev.netherforge.plugin.platform.BlockVector
import dev.netherforge.plugin.platform.ChunkGrid
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.PickAnswer
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.SoundPlay
import dev.netherforge.plugin.platform.WatchedEvent
import dev.netherforge.plugin.profile.TickParts
import dev.netherforge.plugin.project.Resource
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.ScopeOwner
import dev.netherforge.plugin.script.Scripts
import dev.netherforge.plugin.session.ReloadBatch
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.SessionProject
import dev.netherforge.plugin.session.TickPhase
import dev.netherforge.plugin.world.BlockData
import dev.netherforge.plugin.world.Effects
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.random.Random

/**
 * The project's blocks (`blocks/<id>/block.json`) and every one of them
 * placed in a world.
 *
 * **What a block is in the world.** A state of its carrier ([BlockCarriers]:
 * a note block's, tripwire's or a leaves block's, which the resource pack
 * draws as the block). Which block a state is can change
 * when the project's blocks do, so each chunk keeps a **legend** of the states
 * its blocks were placed as (state to block, a few entries a chunk, however
 * many blocks): when a chunk loads, every block is read from its state through
 * the legend and put in the state it has now, so adding or removing blocks
 * never turns a placed one into another, and a layer of ten thousand of them
 * costs no more to keep than one. Only a block a centity is drawn over keeps a
 * **record** of its own with its chunk, naming its instance (and a block the
 * project no longer has keeps one, so its state is free for another). A
 * carrier state the legend doesn't name (a chunk a world generator filled, a
 * paste) is adopted as the block that state is now. A position that stops
 * being a carrier (an explosion, a plugin, a script's `set_state`) is
 * forgotten, with its data and its centity, by the sweep (or at once, when the
 * runtime did it).
 *
 * **What it doesn't do.** Holding the carriers still, and the vanilla note
 * block's part (tuning, sounding, instruments), are the adapter's
 * ([BlockOps.freeze]); this service only holds blocks.
 *
 * **Adopting leaves.** A player's leaves near a log are in the very states
 * leaves blocks take, so a world that had leaves before the project had leaves
 * blocks holds them. Only a chunk generated while the project runs, or one of
 * a world the project generates itself, adopts leaves states nobody recorded:
 * elsewhere they stay the player's leaves (and a paste of leaves blocks there
 * isn't taken up).
 *
 * **The game's own blocks** (`game_blocks/`) the project gives rules to are mined
 * the same way when a rule changes how ([GameBlockFile.minedByServer]), and drop
 * the rule's loot table whoever breaks them: a player ([gameDrops]), an
 * explosion ([exploded]), the world ([worldBroke]) or a script ([breakNaturally]).
 * They're the game's blocks to the server in every other way, so nothing is
 * recorded of them.
 *
 * **Falling blocks** (`falls`) drop as the game's falling block entity when
 * what's under them goes ([blockUpdated]), and are taken up again as the same
 * block where it lands ([fallingLanded]).
 *
 * **For code that isn't a script** (world generation, W5.2): [stateOf] is the
 * note block state a block is held as (to put in chunk data), [place] sets a
 * block and everything with it in a loaded chunk, and a chunk that comes into
 * memory with carrier states already in it adopts them.
 */
internal class CustomBlocks(
    private val platform: Platform,
    private val scripts: Scripts,
    private val log: RuntimeLog,
    private val readSource: (String) -> String?,
    /** Where a resource is now (in its group folders), by kind and id: where its script is read from. */
    private val placeOf: (KindSpec<*, *>, String) -> ResourcePlace,
    private val namespace: () -> String,
    private val ticks: () -> Long,
    private val items: () -> Items,
    private val loot: () -> LootTables,
    private val centities: () -> Centities,
    private val effects: () -> Effects,
    private val blockData: () -> BlockData,
    private val problemsChanged: () -> Unit,
    /** Where the profiler takes what each step of the tick costs (`world/blocks/sweep`). */
    private val parts: TickParts = TickParts.OFF
) : RuntimeService {
    override val name get() = "blocks"

    /** A chunk that comes into memory may hold blocks of the project's: only a project that has blocks asks for the (costly to watch) event. */
    override val needsWatched get() = if (usable) setOf(WatchedEvent.CHUNK_LOAD) else emptySet()

    /** A running block: its file, and the scope of its script. */
    private class Def(val id: String, val file: BlockFile) {
        var scope: Scope? = null
    }

    /** What the server remembers of a block in a chunk (saved with it): [id] is the block's name as the project knows it. */
    @Serializable
    private class Record(val id: String, val centity: String? = null, val facing: String? = null)

    /**
     * A block in a loaded chunk. [kept] is whether it's the one [ChunkBlocks.kept] has; one that isn't is made
     * afresh from its name each time it's looked up.
     */
    private class Placed(var name: String, var centity: UUID?, var next: Long, val kept: Boolean = true, var facing: BlockFacing? = null) {
        /** Whether a block that needn't keep a record has one anyway (its table is kept by it). */
        var pinned = false
    }

    /**
     * The project's blocks in a loaded chunk: every one's name (its [number]) where it is, and a [Placed] only for
     * the few with more to them, which is what lets a chunk a world generator filled with them cost kilobytes.
     */
    private class ChunkBlocks {
        val names = ChunkGrid()

        /** The blocks that tick, are drawn over by a centity or keep a record for their table. */
        val kept = HashMap<BlockVector, Placed>()

        fun isEmpty() = names.isEmpty()
    }

    /** Every name a block of the project's has had this session, by its number less one. */
    private val names = ArrayList<String>()
    private val numbers = HashMap<String, Int>()

    private fun number(name: String): Int = numbers.getOrPut(name) {
        names += name
        names.size
    }

    /** Whether a block keeps a record of its own: one a centity is drawn over, which names the instance. */
    private fun recorded(name: String): Boolean = defs[name]?.file?.centity != null

    private fun ticks(name: String): Boolean = defs[name]?.file?.tick != null

    /** Whether a block needs a [Placed] of its own to be tracked: a timer, a centity or a pin. */
    private fun keeps(placed: Placed): Boolean = placed.pinned || placed.centity != null || recorded(placed.name) || ticks(placed.name)

    /** Tracks [placed] at [at] in [blocks], kept if it [keeps] anything. */
    private fun put(blocks: ChunkBlocks, at: BlockVector, placed: Placed) {
        blocks.names[at.x and 15, at.y, at.z and 15] = number(placed.name)
        if (keeps(placed)) blocks.kept[at] = placed else blocks.kept.remove(at)
    }

    /** Stops tracking the block at [at] in [blocks]: what it was, or null when there was none. */
    private fun take(blocks: ChunkBlocks, at: BlockVector): Placed? {
        val number = blocks.names.set(at.x and 15, at.y, at.z and 15, 0)
        if (number == 0) return null
        return blocks.kept.remove(at) ?: Placed(names[number - 1], null, 0, kept = false)
    }

    /** The block tracked at [at] in [blocks], or null. */
    private fun get(blocks: ChunkBlocks, at: BlockVector): Placed? {
        val number = blocks.names[at.x and 15, at.y, at.z and 15]
        if (number == 0) return null
        return blocks.kept[at] ?: Placed(names[number - 1], null, 0, kept = false)
    }

    /**
     * What a chunk's legend says a state is: a block's name, and for a block that turns the way it faced in it
     * (`chair#east`), so a block whose states move still faces the way it did.
     */
    private fun legendValue(name: String, facing: BlockFacing?) = if (facing == null) name else "$name#${facing.id}"

    private fun legendName(value: String) = value.substringBefore('#')

    private fun legendFacing(value: String) = value.substringAfter('#', "").takeIf { it.isNotEmpty() }?.let(BlockFacing::of)

    private fun readLegend(json: String?): Map<String, String> =
        json?.let { runCatching { Json.decodeFromString<Map<String, String>>(it) }.getOrNull() }.orEmpty()

    /** Writes a chunk's legend when it's changed (a chunk written to is saved again). */
    private fun writeLegend(key: ChunkKey, before: String?, legend: Map<String, String>) {
        val json = if (legend.isEmpty()) null else Json.encodeToString<Map<String, String>>(LinkedHashMap(legend.toSortedMap()))
        if (json != before) writeLegend(key.world, key.x, key.z, json)
    }

    private data class ChunkKey(val world: String, val x: Int, val z: Int)

    private var defs: Map<String, Def> = emptyMap()
    private var plan: BlockCarriers.Plan? = null

    /** Whether the server holds every carrier the plan uses still: only then are the plan's states safe to hold blocks in. */
    private var held = false

    /** The worlds the project generates itself (a terrain in `netherforge.json`), where leaves states are adopted. */
    private var generatedWorlds: Set<String> = emptySet()

    /** Whether the project has blocks the server can hold. */
    val usable: Boolean get() = held && plan != null

    private var byState: Map<String, String> = emptyMap()

    /** The blocks of every loaded chunk this service has looked at. */
    private val chunks = HashMap<ChunkKey, ChunkBlocks>()

    /** The chunks holding a block that ticks. */
    private val ticking = LinkedHashSet<ChunkKey>()

    /** Chunks to check next, a few every tick. */
    private val sweepQueue = ArrayDeque<ChunkKey>()

    /** Each chunk's [fingerprint] the last time [sweep] reconciled it. */
    private val swept = HashMap<ChunkKey, Long>()

    private val miners = BlockMining(
        platform,
        namespace,
        { usable },
        { world, x, y, z -> placedAt(world, x, y, z)?.name },
        { name -> defs[name]?.file },
        { state -> gameRule(state)?.file }
    )

    private var problems: List<Problem> = emptyList()

    /** One of the project's rules for some of the game's blocks: [id] is its file's. */
    private class GameRule(val id: String, val file: GameBlockFile)

    /** The game's blocks the project has rules for, by block id. */
    private var gameRules: Map<String, GameRule> = emptyMap()

    /** A falling block of the project's on its way down: what it is, and where it was last seen. */
    private class Falling(val name: String, var world: String, var at: Vec3)

    /** The project's blocks falling now, by their falling block entity. */
    private val falling = HashMap<UUID, Falling>()

    /** Blocks to see about falling at the next tick, as the game looks a tick after a neighbour changes. */
    private val pendingFalls = LinkedHashSet<Pair<String, BlockVector>>()

    // ---- lifecycle ----------------------------------------------------------------

    override fun define(project: SessionProject) {
        defs = project.running(BlockKind).mapValues { (id, file) -> Def(id, file) }
        generatedWorlds = project.snapshot.manifest?.worlds.orEmpty().filterValues { it.terrain != null }.keys
        replan(project.everywhere(BlockKind), project.snapshot.namespace)
        defineGameRules(project.running(GameBlockKind))
        // Before any script runs: a module's body may place blocks.
        holdCarriers()
        watchWorld()
    }

    /** The game's blocks [files] give rules to, by block id, each tag resolved by the game the server runs. */
    private fun defineGameRules(files: Map<String, GameBlockFile>) {
        val owner = GameBlocks.resolve(files) { platform.game.tag(RegistryKey.BLOCK, it) }
        gameRules = owner.mapValues { (_, id) -> GameRule(id, files.getValue(id)) }
    }

    /** Tells the server which of the world's own breaks and changes this service wants to hear of. */
    private fun watchWorld() {
        val drops = gameRules.filterValues { it.file.drops != null }.keys
        val falls = if (usable) defs.values.filter { it.file.fallsOrDefault }.flatMap { statesOf(it.id) }.toSet() else emptySet()
        platform.blocks.watch(drops, falls)
    }

    /** The project's rule for the game's block [state] is a state of, or null. */
    private fun gameRule(state: String): GameRule? = if (gameRules.isEmpty()) null else gameRules[state.substringBefore('[')]

    /** Asks the server to hold still every carrier the plan uses: a carrier it won't is a problem, and [held] says whether all are. */
    private fun holdCarriers() {
        val plan = plan ?: return
        var all = true
        for (carrier in plan.carriers.ifEmpty { setOf(BlockCarrier.NOTE_BLOCK) }) {
            if (platform.blocks.freeze(carrier)) continue
            all = false
            val noun = CanonicalJson.serialName(carrier).replace('_', ' ')
            val message = "This server won't stop working $noun states out itself, so the project's blocks can't be held in them"
            log.error(message)
            problems = problems + ProblemCodes.RUNTIME_BLOCKS.at(BlockKind.folder, message)
        }
        held = all
    }

    override fun start() {
        for (def in defs.values) startScript(def)
        if (plan != null || gameRules.isNotEmpty()) for (player in platform.players.online()) miners.clearSlowed(player.uuid)
        if (usable) {
            for (world in platform.worlds.names()) {
                for ((x, z) in platform.worlds.loadedChunks(world)) reconcile(ChunkKey(world, x, z))
            }
        }
        // The states may have moved since the stacks players hold named them (a restart, a full reload).
        if (plan != null) items().refreshOnline()
    }

    override fun stop() {
        for (def in defs.values) stopScript(def)
        miners.stopAll()
        platform.blocks.watch(emptySet(), emptySet())
        falling.clear()
        pendingFalls.clear()
        // Before the tables are saved and let go (block data stops after this service).
        for ((key, blocks) in chunks) pinData(key, blocks)
        chunks.clear()
        ticking.clear()
        sweepQueue.clear()
        swept.clear()
        legends.clear()
    }

    override fun problems(): List<Problem> = problems

    // ---- states: what the pack and the world agree a block is -------------------------

    /** The note block state [name] (a block as the project names it) is held as; null for a block that has none. */
    fun stateOf(name: String): String? = plan?.stateOf(name)?.toString()

    /** The state [name] is held as facing [facing] (its one state for a block that doesn't turn), or null for a way it can't face. */
    fun stateOf(name: String, facing: BlockFacing?): String? = plan?.stateOf(name, facing)?.toString()

    /** Every state [name] is held in: one, or one per way it faces. */
    private fun statesOf(name: String): List<String> = plan?.uses.orEmpty().filter { it.name == name }.map { it.state.toString() }

    /** The ways [name] can face, none for a block that doesn't turn. */
    fun facingsOf(name: String): List<BlockFacing> = defs[name]?.file?.rotationOrDefault?.facings.orEmpty()

    /** The way the block at a position faces: from its state, so a block of the project's that turns; null otherwise. */
    fun facingAt(world: String, x: Int, y: Int, z: Int): BlockFacing? {
        placedAt(world, x, y, z) ?: return null
        val state = platform.blocks.get(world, x, y, z)?.state ?: return null
        return plan?.facingOf(state)
    }

    /**
     * Turns the block at a position to [facing], keeping everything else about it. False when it isn't one of the
     * project's blocks, its chunk isn't loaded, or it can't face that way (it doesn't turn, or not up or down).
     */
    fun turn(world: String, x: Int, y: Int, z: Int, facing: BlockFacing): Boolean {
        val placed = placedAt(world, x, y, z) ?: return false
        val state = stateOf(placed.name, facing)?.takeIf { facing in facingsOf(placed.name) } ?: return false
        if (!platform.blocks.set(world, x, y, z, state, false)) return false
        val key = ChunkKey(world, x shr 4, z shr 4)
        val at = BlockVector(x, y, z)
        val kept = chunks[key]?.kept?.get(at)
        if (kept != null) {
            kept.facing = facing
            if (recorded(placed.name) || kept.pinned) writeRecord(world, x, y, z, record(placed.name, kept.centity, facing))
            kept.centity?.let { id -> centities().find(id)?.let { centities().turn(it, yawOf(facing)) } }
        } else {
            noteLegend(key, placed.name, facing)
        }
        return true
    }

    /** A centity drawn over a block that faces [facing] is turned with it, about its own up: the model's y turn. */
    private fun yawOf(facing: BlockFacing?): Double = (facing?.y ?: 0).toDouble()

    /** The project block a placed note block state is, or null (a vanilla note block, a block of a state no block has). */
    fun nameOfState(state: String): String? = byState[state]?.takeIf { it in defs }

    /** Whether any block of the project's is placed in a chunk that's loaded: whether an explosion's blocks are worth looking at. */
    fun anyPlaced(): Boolean = chunks.isNotEmpty()

    /** Whether the project has a block called [name] that runs. */
    fun has(name: String): Boolean = name in defs

    /** [name]'s `properties` from its file (empty when it has none), or null when the project has no such block. */
    fun properties(name: String): Map<String, kotlinx.serialization.json.JsonElement>? = defs[name]?.let { it.file.properties.orEmpty() }

    fun ids(): List<String> = defs.keys.sorted()

    /** The block at a position, or null when it isn't one of the project's blocks (or its chunk hasn't been looked at). */
    fun at(world: String, x: Int, y: Int, z: Int): String? = placedAt(world, x, y, z)?.name

    private fun placedAt(world: String, x: Int, y: Int, z: Int): Placed? =
        chunks[ChunkKey(world, x shr 4, z shr 4)]?.let { get(it, BlockVector(x, y, z)) }

    /** Whether [placed] (from [placedAt]) is still the block at a position: the same one, or for one that isn't kept, one of its name. */
    private fun still(world: String, x: Int, y: Int, z: Int, placed: Placed): Boolean {
        val now = placedAt(world, x, y, z) ?: return false
        return now === placed || (!placed.kept && !now.kept && now.name == placed.name)
    }

    /** Every placed block of [name] in a loaded chunk, for what reloading it tells a script. */
    private fun countOf(name: String): Int {
        val number = numbers[name] ?: return 0
        var count = 0
        for (blocks in chunks.values) blocks.names.forEach { _, _, _, it -> if (it == number) count++ }
        return count
    }

    private fun replan(blocks: Map<String, Loaded<BlockFile, BlockFile>>, home: String) {
        val next = BlockKind.plan(blocks, home, platform.game)
        plan = next
        byState = next?.uses?.associate { it.state.toString() to it.name }.orEmpty()
        // The plan says what each state is: every chunk is read again under it.
        swept.clear()
        problems = buildList {
            if (blocks.isNotEmpty() && next == null) {
                add(
                    ProblemCodes.RUNTIME_BLOCKS.at(
                        BlockKind.folder,
                        "This server's game has none of the blocks to hold the project's blocks in"
                    )
                )
            }
            for (name in next?.unheld.orEmpty()) {
                val noun = CanonicalJson.serialName(blocks[name]?.value?.carrierOrDefault ?: BlockCarrier.NOTE_BLOCK).replace('_', ' ')
                add(
                    ProblemCodes.RUNTIME_BLOCKS.at(
                        placeOf(BlockKind, name).path,
                        "This server's game has no $noun states to hold this block in",
                        "$.carrier"
                    )
                )
            }
            for (name in next?.overflow.orEmpty()) {
                val carrier = blocks[name]?.value?.carrierOrDefault ?: BlockCarrier.NOTE_BLOCK
                val noun = CanonicalJson.serialName(carrier).replace('_', ' ')
                val capacity = next?.pools?.get(carrier)?.carriers?.size ?: 0
                add(
                    ProblemCodes.BLOCK_CARRIERS.at(
                        placeOf(BlockKind, name).path,
                        "The game has $capacity $noun states to hold blocks in, and the project has more $noun blocks than that",
                        "$"
                    )
                )
            }
        }
    }

    // ---- scripts ---------------------------------------------------------------

    private fun startScript(def: Def) {
        val script = def.file.script ?: return
        val path = placeOf(BlockKind, def.id).file(script.file)
        val source = readSource(path) ?: return
        def.scope = scripts.start(ScopeOwner.BlockScript(def.id, path), script, source).first
    }

    private fun stopScript(def: Def) {
        def.scope?.let(scripts::close)
        def.scope = null
    }

    override val reloads: Set<KindSpec<*, *>> get() = setOf(BlockKind, GameBlockKind)

    /**
     * `reattached` is how many placed blocks of it there are in loaded chunks: they run the new script at once. A game
     * block file's rules hold from the next block mined or broken.
     */
    override fun reload(kind: KindSpec<*, *>, ids: Set<String>, batch: ReloadBatch) {
        if (kind == GameBlockKind) {
            for (id in ids) batch.resource(Resource(GameBlockKind, id), batch.snapshot.running(GameBlockKind)[id]) { 0 }
            defineGameRules(batch.snapshot.running(GameBlockKind))
            // Whoever mines one of them mines it by the rules it has now.
            miners.stopGameTargets()
            watchWorld()
            return
        }
        for (id in ids) {
            batch.resource(Resource(BlockKind, id), batch.snapshot.running(BlockKind)[id]) { next ->
                reload(id, next)
            }
        }
        val before = byState
        replan(batch.snapshot.everywhere(BlockKind), batch.snapshot.namespace)
        // A carrier a block took up only now is held still from now on.
        holdCarriers()
        problemsChanged()
        if (usable) for (key in chunks.keys.toList()) reconcile(key)
        watchWorld()
        // Stacks whose canBreak or canPlaceOn names a block whose state moved break the wrong blocks until rewritten.
        if (byState != before) items().refreshOnline()
    }

    private fun reload(id: String, next: BlockFile?): Int {
        defs[id]?.let(::stopScript)
        if (next == null) {
            scripts.dropTarget(LuaHandle.ProjectBlock(id))
            defs = defs - id
            return 0
        }
        val def = Def(id, next)
        defs = defs + (id to def)
        startScript(def)
        for (blocks in chunks.values) for (placed in blocks.kept.values) if (placed.name == id) placed.next = ticks() + (next.tick ?: 0)
        return countOf(id)
    }

    // ---- chunks: what's placed in the world ---------------------------------------

    override fun chunkLoaded(world: String, chunkX: Int, chunkZ: Int, generated: Boolean) {
        if (usable) reconcile(ChunkKey(world, chunkX, chunkZ), generated)
    }

    override fun chunkUnloading(world: String, chunkX: Int, chunkZ: Int) {
        val key = ChunkKey(world, chunkX, chunkZ)
        chunks[key]?.let { pinData(key, it) }
        chunks.remove(key)
        ticking.remove(key)
    }

    override fun worldSaving(world: String) {
        for ((key, blocks) in chunks) if (key.world == world) pinData(key, blocks)
    }

    /**
     * A block known by its state alone that scripts gave a table keeps a record from now on: if something else takes
     * its place while its chunk isn't loaded, the record is how its table is found to go.
     */
    private fun pinData(key: ChunkKey, blocks: ChunkBlocks) {
        for (at in blockData().tablesIn(key.world, key.x, key.z)) {
            val placed = get(blocks, at) ?: continue
            if (placed.pinned || recorded(placed.name)) continue
            val facing = platform.blocks.get(key.world, at.x, at.y, at.z)?.state?.let { plan?.facingOf(it) }
            writeRecord(key.world, at.x, at.y, at.z, record(placed.name, null, facing))
            placed.facing = facing
            placed.pinned = true
            put(blocks, at, placed)
        }
    }

    /**
     * Brings what the server remembers of a chunk, what's in its blocks and
     * what the project's blocks are into agreement: each record's block is in
     * its state now, a record whose block has gone is forgotten, and a carrier
     * state with no record is adopted (a leaves state only in a chunk just
     * [generated], or in a world the project generates).
     */
    private fun reconcile(key: ChunkKey, generated: Boolean = false) {
        val plan = plan ?: return
        val records = parts.time("world/blocks/sweep/records") { platform.blocks.records(key.world, key.x, key.z) } ?: return
        val legendText = platform.blocks.legend(key.world, key.x, key.z)
        val legend = readLegend(legendText)
        // The states blocks are held in now, and those the legend says they were placed in.
        val states = (byState.keys + legend.keys).toList()
        val found = parts.time("world/blocks/sweep/find") { platform.blocks.find(key.world, key.x, key.z, states) } ?: return
        fun stateAt(at: BlockVector): String? = found[at.x and 15, at.y, at.z and 15].takeIf { it != 0 }?.let { states[it - 1] }
        val previous = chunks[key]
        val tracked = ChunkBlocks()
        val recorded = HashSet<BlockVector>()
        val adoptsLeaves = generated || key.world in generatedWorlds
        for (record in records) {
            val at = BlockVector(record.x, record.y, record.z)
            recorded += at
            val read = runCatching { Json.decodeFromString<Record>(record.json) }.getOrNull()
            if (read == null) {
                writeRecord(key.world, at.x, at.y, at.z, null)
                continue
            }
            val facing = read.facing?.let(BlockFacing::of)
            val expected = plan.stateOf(read.id, facing)?.toString()
            val state = stateAt(at)
            val centity = read.centity?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            when {
                // A block the project has no longer (or has with errors): it stays as it was, inert, until it's back.
                expected == null || read.id !in defs -> Unit
                state == expected -> keep(key, at, read.id, centity, tracked, facing)
                else -> {
                    val now = platform.blocks.get(key.world, at.x, at.y, at.z)?.state
                    val parsed = now?.let(BlockState::parse)
                    if (parsed != null && plan.isCarrier(parsed)) {
                        // The block's state moved with the project's blocks: put it right, quietly.
                        platform.blocks.set(key.world, at.x, at.y, at.z, expected, false)
                        keep(key, at, read.id, centity, tracked, facing)
                    } else {
                        // Whatever it was, it isn't there now: its table and its centity go.
                        gone(key.world, at, centity)
                        writeRecord(key.world, at.x, at.y, at.z, null)
                    }
                }
            }
        }
        // What each state found is: the block it was placed as, else (a generated chunk, a paste) the one it is now.
        val valueOf = states.map { state ->
            legend[state] ?: byState[state]?.takeIf { adoptsLeaves || plan.stateOf(it)?.let(::isLeaves) != true }
                ?.let { legendValue(it, plan.facingOf(state)) }
        }
        val nameOf = valueOf.map { it?.let(::legendName) }
        val facingOf = valueOf.map { it?.let(::legendFacing) }
        val expectedOf = nameOf.mapIndexed { i, name -> name?.let { plan.stateOf(it, facingOf[i])?.toString() } }
        found.forEach { x, y, z, i ->
            val name = nameOf[i - 1] ?: return@forEach
            val at = BlockVector(key.x * 16 + x, y, key.z * 16 + z)
            if (at in recorded) return@forEach
            if (name !in defs) {
                // A block the project has no longer (or has with errors): pinned by a record, inert, until it's back.
                writeRecord(key.world, at.x, at.y, at.z, record(name, null, facingOf[i - 1]))
                return@forEach
            }
            val expected = expectedOf[i - 1] ?: return@forEach
            // The block's state moved with the project's blocks: put it right, quietly.
            if (expected != states[i - 1]) platform.blocks.set(key.world, at.x, at.y, at.z, expected, false)
            if (recorded(name) || ticks(name)) {
                put(tracked, at, placed(key, at, name, null).also { it.facing = facingOf[i - 1] })
                if (recorded(name)) adopted(key.world, at, name, tracked)
            } else {
                tracked.names[x, y, z] = number(name)
            }
            // Taken up just now (a generator's, a paste): one that falls looks at what's under it.
            if (legend[states[i - 1]] == null && defs.getValue(name).file.fallsOrDefault) pendingFalls += key.world to at
        }
        // A block known by its state alone that something else replaced while its chunk was loaded: its table goes.
        previous?.names?.forEach { x, y, z, _ ->
            if (found[x, y, z] != 0) return@forEach
            val at = BlockVector(key.x * 16 + x, y, key.z * 16 + z)
            if (at !in recorded) gone(key.world, at, previous.kept[at]?.centity)
        }
        for (at in tracked.kept.keys.toList()) ensureCentity(key.world, at, tracked)
        writeLegend(key, legendText, legendOf(plan, tracked))
        if (tracked.isEmpty()) chunks.remove(key) else chunks[key] = tracked
        refreshTicking(key)
    }

    /**
     * A recorded block that's still there, tracked: one that needn't keep a record (one from before legends) and has
     * no table to keep loses it, and the legend has it from now on.
     */
    private fun keep(key: ChunkKey, at: BlockVector, name: String, centity: UUID?, tracked: ChunkBlocks, facing: BlockFacing? = null) {
        val placed = placed(key, at, name, centity)
        placed.facing = facing
        if (!recorded(name) && centity == null) {
            if (blockData().has(key.world, at.x, at.y, at.z)) {
                placed.pinned = true
            } else {
                writeRecord(key.world, at.x, at.y, at.z, null)
                placed.pinned = false
            }
        }
        put(tracked, at, placed)
    }

    /** The legend of a chunk's [tracked] blocks that keep no record: each one's state now, to its name. */
    private fun legendOf(plan: BlockCarriers.Plan, tracked: ChunkBlocks): Map<String, String> {
        val present = BooleanArray(names.size + 1)
        tracked.names.forEach { _, _, _, number -> present[number] = true }
        val legend = HashMap<String, String>()
        for (number in 1..names.size) {
            val name = names[number - 1]
            if (!present[number] || recorded(name)) continue
            for (use in plan.uses) if (use.name == name) legend[use.state.toString()] = legendValue(name, use.facing)
        }
        return legend
    }

    /** Whether [state] is a leaves block's: what's only adopted where it can't be a player's leaves. */
    private fun isLeaves(state: BlockState): Boolean = plan?.pools?.get(BlockCarrier.LEAVES)?.blocks?.containsKey(state.id) == true

    /** The block already tracked at [at] if it's still [name] (its timer runs on), else a new one, first ticked an interval from now. */
    private fun placed(key: ChunkKey, at: BlockVector, name: String, centity: UUID?): Placed {
        val known = chunks[key]?.kept?.get(at)?.takeIf { it.name == name }
        if (known != null) {
            known.centity = centity
            return known
        }
        return Placed(name, centity, ticks() + (defs[name]?.file?.tick ?: 0))
    }

    /** A carrier state nobody recorded: now a block of the project, as if it had been placed. */
    private fun adopted(world: String, at: BlockVector, name: String, tracked: ChunkBlocks) {
        val placed = tracked.kept.getValue(at)
        placed.centity = spawnCentity(world, at, defs[name] ?: return, placed.facing)
        writeRecord(world, at.x, at.y, at.z, record(name, placed.centity, placed.facing))
    }

    /** A block drawn by a centity whose instance is missing gets a new one. */
    private fun ensureCentity(world: String, at: BlockVector, tracked: ChunkBlocks) {
        val placed = tracked.kept[at] ?: return
        val def = defs[placed.name] ?: return
        if (def.file.centity == null) return
        val id = placed.centity
        if (id != null && (centities().find(id) != null || centities().inertRecords().any { it.id == id })) return
        placed.centity = spawnCentity(world, at, def, placed.facing)
        writeRecord(world, at.x, at.y, at.z, record(placed.name, placed.centity, placed.facing))
    }

    private fun record(name: String, centity: UUID?, facing: BlockFacing? = null) =
        Json.encodeToString(Record(name, centity?.toString(), facing?.id))

    private fun spawnCentity(world: String, at: BlockVector, def: Def, facing: BlockFacing? = null): UUID? {
        val reference = def.file.centity ?: return null
        val centity = reference.nameIn(namespace()) ?: return null
        val instance = centities().spawn(centity, Location(world, at.x + 0.5, at.y.toDouble(), at.z + 0.5), yawOf(facing))
        if (instance == null) log.warn("Block ${def.id}'s centity \"$reference\" couldn't be spawned at $world ${at.x} ${at.y} ${at.z}")
        return instance?.id
    }

    /** A block went: the centity drawn over it goes, and its data table. */
    private fun gone(world: String, at: BlockVector, centity: UUID?) {
        if (centity != null) {
            centities().find(centity)?.let(centities()::remove) ?: centities().removeInert(centity)
        }
        blockData().discard(world, at.x, at.y, at.z)
    }

    /** Whether the chunk at [key] holds a block that ticks: only kept blocks can. */
    private fun refreshTicking(key: ChunkKey) {
        if (chunks[key]?.kept?.values?.any { ticks(it.name) } == true) ticking += key else ticking -= key
    }

    // ---- placing and removing -------------------------------------------------------

    /**
     * Puts the project block [name] at a position: its state is set (neighbours
     * aren't told), what's recorded of it, its centity spawned. False when the
     * project has no such block, the game has no state left to hold it, or the
     * chunk isn't loaded. What was there is replaced, and a custom block that
     * was there is forgotten first. No script hears of it.
     */
    fun place(name: String, world: String, x: Int, y: Int, z: Int, facing: BlockFacing? = null): Boolean {
        if (!usable || name !in defs) return false
        val turned = facing?.takeIf { it in facingsOf(name) }
        val state = stateOf(name, turned) ?: return false
        if (!platform.blocks.set(world, x, y, z, state, false)) return false
        register(name, world, BlockVector(x, y, z), plan?.facingOf(state))
        return true
    }

    /** Takes [name] into the chunk's tracked blocks, with the record and centity; whatever custom block was there goes first. */
    private fun register(name: String, world: String, at: BlockVector, facing: BlockFacing? = null) {
        val def = defs.getValue(name)
        val key = ChunkKey(world, at.x shr 4, at.z shr 4)
        val blocks = chunks.getOrPut(key) { ChunkBlocks() }
        take(blocks, at)?.let { gone(world, at, it.centity) }
        val placed = Placed(name, null, ticks() + (def.file.tick ?: 0), facing = facing)
        placed.centity = spawnCentity(world, at, def, facing)
        put(blocks, at, placed)
        if (recorded(name)) {
            writeRecord(world, at.x, at.y, at.z, record(name, placed.centity, facing))
        } else {
            // Known by its state: the chunk's legend says which block that is (and a record of what was there goes).
            writeRecord(world, at.x, at.y, at.z, null)
            noteLegend(key, name, facing)
        }
        if (def.file.tick != null) ticking += key
        if (def.file.fallsOrDefault) pendingFalls += world to at
    }

    /** Says in the chunk's legend which block (and way) [name]'s state facing [facing] is, if it doesn't yet. */
    private fun noteLegend(key: ChunkKey, name: String, facing: BlockFacing?) {
        val state = stateOf(name, facing) ?: return
        val before = platform.blocks.legend(key.world, key.x, key.z)
        val legend = readLegend(before)
        val value = legendValue(name, facing)
        if (legend[state] != value) writeLegend(key, before, legend + (state to value))
    }

    /** Forgets what's recorded at a position (its block was removed, or replaced by something else). */
    private fun forget(world: String, at: BlockVector): Placed? {
        val key = ChunkKey(world, at.x shr 4, at.z shr 4)
        val blocks = chunks[key]
        val placed = blocks?.let { take(it, at) } ?: return null
        if (blocks.isEmpty()) {
            chunks.remove(key)
            ticking.remove(key)
        }
        gone(world, at, placed.centity)
        writeRecord(world, at.x, at.y, at.z, null)
        return placed
    }

    /**
     * What a script or command did to the block at a position: if it was one of
     * the project's and isn't its state any more, it's forgotten. [state] is
     * what's there now.
     */
    fun replaced(world: String, x: Int, y: Int, z: Int, state: String) {
        val placed = placedAt(world, x, y, z) ?: return
        if (state in statesOf(placed.name)) return
        forget(world, BlockVector(x, y, z))
    }

    /**
     * Breaks the block at a position as `break_naturally` does: its drops rolled for [tool], and gone. False when it
     * isn't one of the project's blocks, or one of the game's with drops of the project's (the game breaks the rest).
     */
    fun breakNaturally(world: String, x: Int, y: Int, z: Int, tool: ItemData?): Boolean {
        val placed = placedAt(world, x, y, z)
        if (placed == null) {
            val state = platform.blocks.get(world, x, y, z)?.state ?: return false
            val rule = gameRule(state)?.takeIf { it.file.drops != null } ?: return false
            val drops = if (miners.gameHarvests(rule.file, state, tool) ==
                false
            ) {
                emptyList()
            } else {
                rollGame(rule, world, x, y, z, null, tool)
            }
            platform.blocks.set(world, x, y, z, AIR, true)
            platform.blocks.showBroken(null, world, x, y, z, state)
            val centre = Vec3(x + 0.5, y + 0.5, z + 0.5)
            for (item in drops) platform.worldEntities.spawnItem(world, centre, item)
            return true
        }
        val def = defs[placed.name] ?: return false
        val drops = rollDrops(def, world, x, y, z, null, tool)
        remove(world, BlockVector(x, y, z), def, drops, true)
        return true
    }

    /** Removes a block: forgotten, its state set to air, with the sound and the particles of breaking, and [drops] dropped. */
    private fun remove(world: String, at: BlockVector, def: Def, drops: List<ItemData>, effects: Boolean) {
        val state = stateOf(def.id)
        forget(world, at)
        platform.blocks.set(world, at.x, at.y, at.z, AIR, true)
        if (effects) breakEffects(world, at, def, state)
        val centre = Vec3(at.x + 0.5, at.y + 0.5, at.z + 0.5)
        for (item in drops) platform.worldEntities.spawnItem(world, centre, item)
    }

    private fun breakEffects(world: String, at: BlockVector, def: Def, state: String?) {
        val centre = Vec3(at.x + 0.5, at.y + 0.5, at.z + 0.5)
        if (state != null) {
            runCatching {
                effects().spawnParticle(
                    world,
                    BLOCK_PARTICLE,
                    centre,
                    ParticleOptions(count = 24, spread = Vec3(0.25, 0.25, 0.25), blockState = state)
                )
            }
        }
        sound(world, centre, def.file.sounds?.destroy, def.file.breakSound)
    }

    /**
     * The sound of a block placed or broken, for everyone near it (the player's own game didn't play one, as the
     * server did it): [own], one of the project's, else the game's [game] as the game plays a block's.
     */
    private fun sound(world: String, at: Vec3, own: ResourceRef?, game: String) {
        if (own != null) {
            val id = runCatching { effects().soundId(own.text) }.getOrNull() ?: return
            platform.sounds.play(world, at, SoundPlay(id, "block", 1.0, 1.0))
        } else {
            platform.sounds.play(world, at, SoundPlay(game, "block", GAME_VOLUME, GAME_PITCH))
        }
    }

    /** What breaking the block at a position drops: its loot table rolled for the player and the tool, nothing for a tool it needs and isn't. */
    private fun rollDrops(def: Def, world: String, x: Int, y: Int, z: Int, player: UUID?, tool: ItemData?): List<ItemData> {
        if (def.file.requiresToolOrDefault && !miners.rightTool(def.file.tool, tool)) return emptyList()
        return roll(def.file.drops, "Block ${def.id}", world, x, y, z, player, tool)
    }

    /** A game block's rule's loot table rolled where it broke, for [player] and [tool] when someone broke it. */
    private fun rollGame(rule: GameRule, world: String, x: Int, y: Int, z: Int, player: UUID?, tool: ItemData?): List<ItemData> =
        roll(rule.file.drops, "Game block ${rule.id}", world, x, y, z, player, tool)

    private fun roll(
        drops: ResourceRef?,
        who: String,
        world: String,
        x: Int,
        y: Int,
        z: Int,
        player: UUID?,
        tool: ItemData?
    ): List<ItemData> {
        val table = drops?.nameIn(namespace()) ?: return emptyList()
        val at = Location(world, x + 0.5, y + 0.5, z + 0.5)
        return try {
            loot().roll(table, LootRoll(player = player, tool = tool, location = at), Random.nextLong())
        } catch (e: LuaApiException) {
            log.warn("$who's drops: ${e.message}")
            emptyList()
        }
    }

    /**
     * What a player breaking the game's block [block] drops, when the project has a rule for it: the rule's loot
     * table for their tool, nothing for a tool the rule needs and it isn't, or null for the game's own drops (no
     * rule, no table of the rule's, or creative, where nothing drops anyway). A tool the game's rule refuses gets
     * nothing either way.
     */
    fun gameDrops(player: PlayerRef, block: BlockRef): List<ItemData>? {
        val rule = gameRule(block.state) ?: return null
        if (platform.players.gameMode(player.uuid) == CREATIVE) return null
        val tool = platform.worldEntities.equipment(player.uuid, MAIN_HAND)
        val harvests = miners.gameHarvests(rule.file, block.state, tool)
        return when {
            harvests == false && (rule.file.drops != null || rule.file.requiresTool == true) -> emptyList()
            rule.file.drops == null -> null
            else -> rollGame(rule, block.world, block.x, block.y, block.z, player.uuid, tool)
        }
    }

    /**
     * The world broke the game's block [block] itself (what held it up went, a fluid, a piston, leaves decaying):
     * the rule's loot table, rolled with no player or tool, as the game drops a block nobody broke whatever it
     * needs; null for the game's own.
     */
    fun worldBroke(block: BlockRef): List<ItemData>? {
        val rule = gameRule(block.state)?.takeIf { it.file.drops != null } ?: return null
        return rollGame(rule, block.world, block.x, block.y, block.z, null, null)
    }

    // ---- what players do ---------------------------------------------------------

    /**
     * A player is breaking the block at [block]. True when it's one of the
     * project's: the break is the runtime's from here (the adapter cancels the
     * server's, which would drop a note block and play wood), so scripts hear it,
     * and unless they cancel it the block goes with its drops.
     */
    fun breaking(player: PlayerRef, block: BlockRef, experience: Int): Boolean {
        if (!usable) return false
        val at = BlockVector(block.x, block.y, block.z)
        val placed = placedAt(block.world, at.x, at.y, at.z) ?: return false
        val def = defs[placed.name] ?: return false
        val creative = platform.players.gameMode(player.uuid) == CREATIVE
        val tool = platform.worldEntities.equipment(player.uuid, MAIN_HAND)
        val rolled = if (creative) emptyList() else rollDrops(def, block.world, at.x, at.y, at.z, player.uuid, tool)
        val stages = items().heldStage(Events.PROJECTITEM_BREAK_BLOCK, player.uuid, MAIN_HAND) +
            listOf(
                Events.PROJECTBLOCK_BREAK to LuaHandle.ProjectBlock(def.id),
                Events.WORLD_BLOCK_BREAK to LuaHandle.World(block.world),
                Events.NF_BLOCK_BREAK to null
            )
        val drops = if (stages.any { (event, target) -> scripts.listening(target, event) }) {
            val event = BlockBreakEvent(
                LuaHandle.Player(player.uuid.toString()),
                blockHandle(block.world, at.x, at.y, at.z),
                block.state,
                rolled,
                experience.toLong()
            )
            if (scripts.emit(stages, event)) return true
            if (creative) emptyList() else event.drops
        } else {
            rolled
        }
        // A script may have replaced it meanwhile.
        if (!still(block.world, at.x, at.y, at.z, placed)) return true
        miners.stopMining(player.uuid)
        remove(block.world, at, def, drops, true)
        return true
    }

    /** A player is hitting the block at [start]'s position: [BlockMining.startedBreaking]. True cancels. */
    fun startedBreaking(start: GameEvent.BlockStartBreak): Boolean = miners.startedBreaking(start)

    /** A player let go of the block they were mining, or looked away. */
    fun stoppedBreaking(player: PlayerRef, block: BlockRef) = miners.stoppedBreaking(player, block)

    /**
     * A click on [block], with what [hand] held. True cancels it. A click on
     * one of the project's blocks is heard by its script; a right click with an
     * item that places a block puts it on the face that was clicked.
     */
    fun interact(player: PlayerRef, button: ClickButton, block: BlockRef?, face: String?, item: ItemData?, hand: String): Boolean {
        if (!usable || block == null || face == null) return false
        val here = placedAt(block.world, block.x, block.y, block.z)
        val def = here?.let { defs[it.name] }
        if (def != null) {
            val target = LuaHandle.ProjectBlock(def.id)
            if (scripts.listening(target, Events.PROJECTBLOCK_CLICK)) {
                val event = ProjectBlockClickEvent(
                    LuaHandle.Player(player.uuid.toString()),
                    LuaHandle.CustomBlock(block.world, customPosition(block)),
                    button.luaName,
                    face,
                    item,
                    hand
                )
                if (scripts.emit(Events.PROJECTBLOCK_CLICK, target, event)) return true
            }
        }
        if (button != ClickButton.RIGHT) return false
        val name = items().blockOf(item) ?: return false
        placeFromItem(player, name, block, face, item!!, hand)
        // A block-placing item places or is refused: it's never used as the item it is.
        return true
    }

    private fun customPosition(block: BlockRef) = BlockPosition.pack(block.x, block.y, block.z)

    /**
     * The way a block [player] places faces: toward them, as a furnace or a chair does. Up at them when they look
     * down on it and it can face up (down, looking up at it); else back along where they look, north, east, south
     * or west. Null for a block that doesn't turn.
     */
    private fun facingToward(player: UUID, name: String): BlockFacing? {
        val facings = facingsOf(name)
        if (facings.isEmpty()) return null
        val at = platform.players.location(player) ?: return BlockFacing.NORTH
        val pitch = at.pitch
        if (BlockFacing.UP in facings && pitch >= STEEP) return BlockFacing.UP
        if (BlockFacing.DOWN in facings && pitch <= -STEEP) return BlockFacing.DOWN
        // Where they look: yaw 0 is south, 90 west, 180 north, 270 east; the block faces back at them.
        val yaw = (at.yaw % 360 + 360) % 360
        return when (((yaw + 45) / 90).toInt() % 4) {
            0 -> BlockFacing.NORTH
            1 -> BlockFacing.EAST
            2 -> BlockFacing.SOUTH
            else -> BlockFacing.WEST
        }
    }

    private fun placeFromItem(player: PlayerRef, name: String, against: BlockRef, face: String, item: ItemData, hand: String) {
        val def = defs[name] ?: return
        val facing = facingToward(player.uuid, name)
        val state = stateOf(name, facing) ?: return
        val (dx, dy, dz) = offset(face)
        val x = against.x + dx
        val y = against.y + dy
        val z = against.z + dz
        val world = against.world
        val heights = platform.worlds.heights(world) ?: return
        if (y < heights.first || y >= heights.second) return
        val there = platform.blocks.get(world, x, y, z) ?: return
        if (!(there.air || there.liquid)) return
        if (!platform.blocks.canPlace(world, x, y, z, state)) return
        val target = LuaHandle.ProjectBlock(name)
        if (scripts.listening(target, Events.PROJECTBLOCK_PLACE)) {
            val event = ProjectBlockPlaceEvent(
                LuaHandle.Player(player.uuid.toString()),
                LuaHandle.CustomBlock(world, BlockPosition.pack(x, y, z)),
                item,
                facing?.id
            )
            if (scripts.emit(Events.PROJECTBLOCK_PLACE, target, event)) return
        }
        if (!platform.blocks.place(player.uuid, world, x, y, z, state, BlockVector(against.x, against.y, against.z), hand)) return
        register(name, world, BlockVector(x, y, z), facing)
        sound(world, Vec3(x + 0.5, y + 0.5, z + 0.5), def.file.sounds?.place, def.file.placeSound)
        if (platform.players.gameMode(player.uuid) != CREATIVE) consume(player.uuid, hand, item)
    }

    private fun consume(player: UUID, hand: String, item: ItemData) {
        val count = item.def.count ?: 1
        val left = if (count <= 1) null else item.copy(def = item.def.copy(count = count - 1))
        platform.worldEntities.setEquipment(player, hand, left)
    }

    private fun offset(face: String): Triple<Int, Int, Int> = when (face) {
        "up" -> Triple(0, 1, 0)
        "down" -> Triple(0, -1, 0)
        "north" -> Triple(0, 0, -1)
        "south" -> Triple(0, 0, 1)
        "east" -> Triple(1, 0, 0)
        else -> Triple(-1, 0, 0)
    }

    /**
     * A player picking [block] (the middle click). One of the project's blocks
     * picks the item that places it, not its carrier: one already in
     * [inventory] (any stack that places it), else in creative a new one, else
     * nothing. Null for any other block, which the game picks itself.
     */
    fun picking(player: PlayerRef, block: BlockRef, inventory: List<ItemData?>): PickAnswer? {
        if (!usable) return null
        val placed = placedAt(block.world, block.x, block.y, block.z) ?: return null
        val slot = inventory.indexOfFirst { items().blockOf(it) == placed.name }
        if (slot >= 0) return PickAnswer(slot, null)
        if (platform.players.gameMode(player.uuid) != CREATIVE) return PickAnswer(null, null)
        return PickAnswer(null, items().placing(placed.name)?.let { items().create(it) })
    }

    /** Pistons don't move a block that's held by its record. True cancels. */
    fun pushes(blocks: List<BlockRef>): Boolean = usable && blocks.any { placedAt(it.world, it.x, it.y, it.z) != null }

    /** Whether an explosion's blocks are worth looking at: any of the project's are placed, or it has drops for the game's. */
    fun explodable(): Boolean = anyPlaced() || gameRules.values.any { it.file.drops != null }

    /**
     * An explosion breaks [blocks]; the ones that are the project's, and the game's with drops of the project's, are
     * its own to break, with their drops (each with the chance [yield], as the game's are).
     */
    fun exploded(blocks: List<BlockRef>, yield: Double) {
        for (block in blocks) {
            val placed = if (usable) placedAt(block.world, block.x, block.y, block.z) else null
            if (placed == null) {
                val rule = gameRule(block.state)?.takeIf { it.file.drops != null } ?: continue
                val drops = if (Random.nextDouble() <
                    yield
                ) {
                    rollGame(rule, block.world, block.x, block.y, block.z, null, null)
                } else {
                    emptyList()
                }
                // Gone before the explosion gets to it, so the game drops nothing of its own.
                platform.blocks.set(block.world, block.x, block.y, block.z, AIR, true)
                val centre = Vec3(block.x + 0.5, block.y + 0.5, block.z + 0.5)
                for (item in drops) platform.worldEntities.spawnItem(block.world, centre, item)
                continue
            }
            val def = defs[placed.name] ?: continue
            val drops = if (Random.nextDouble() < yield) rollDrops(def, block.world, block.x, block.y, block.z, null, null) else emptyList()
            remove(block.world, BlockVector(block.x, block.y, block.z), def, drops, false)
        }
    }

    override fun playerJoined(player: PlayerRef) {
        // A modifier the server saved with the player from a session that ended mid-mine.
        if (plan != null || gameRules.isNotEmpty()) miners.clearSlowed(player.uuid)
    }

    override fun playerQuit(player: PlayerRef) {
        miners.forget(player.uuid)
    }

    // ---- falling -------------------------------------------------------------------

    /** Something beside [block] changed: one of the project's blocks that falls looks at what's under it next tick. */
    fun blockUpdated(block: BlockRef) {
        val placed = if (usable) placedAt(block.world, block.x, block.y, block.z) else null
        if (placed != null &&
            defs[placed.name]?.file?.fallsOrDefault == true
        ) {
            pendingFalls += block.world to BlockVector(block.x, block.y, block.z)
        }
    }

    /** A falling block of the project's lands as [block]: the block it was, placed there. */
    fun fallingLanded(entity: UUID, block: BlockRef) {
        val was = falling.remove(entity) ?: return
        if (was.name !in defs || block.state !in statesOf(was.name)) return
        register(was.name, block.world, BlockVector(block.x, block.y, block.z), plan?.facingOf(block.state))
    }

    /**
     * The blocks waiting to fall go down if nothing's under them, and a falling block that's gone without landing (on a
     * torch, a slab) drops its loot table where it was last seen, as sand drops itself.
     */
    private fun fall() {
        if (pendingFalls.isNotEmpty()) {
            val due = pendingFalls.toList()
            pendingFalls.clear()
            for ((world, at) in due) {
                val placed = placedAt(world, at.x, at.y, at.z) ?: continue
                val def = defs[placed.name]?.takeIf { it.file.fallsOrDefault } ?: continue
                val entity = platform.blocks.fall(world, at.x, at.y, at.z) ?: continue
                forget(world, at)
                falling[entity] = Falling(def.id, world, Vec3(at.x + 0.5, at.y.toDouble(), at.z + 0.5))
            }
        }
        for ((entity, was) in falling.entries.toList()) {
            val info = platform.worldEntities.info(entity)
            if (info != null) {
                was.world = info.location.world
                was.at = Vec3(info.location.x, info.location.y, info.location.z)
                continue
            }
            falling.remove(entity)
            val def = defs[was.name] ?: continue
            val x = kotlin.math.floor(was.at.x).toInt()
            val y = kotlin.math.floor(was.at.y).toInt()
            val z = kotlin.math.floor(was.at.z).toInt()
            // Whatever the tool rule says: nobody mined it, as the game drops sand that can't land.
            for (item in roll(def.file.drops, "Block ${def.id}", was.world, x, y, z, null, null)) {
                platform.worldEntities.spawnItem(was.world, was.at, item)
            }
        }
    }

    // ---- the tick ---------------------------------------------------------------

    override fun tick(phase: TickPhase) {
        if (phase != TickPhase.WORLD) return
        if (!usable && gameRules.isEmpty()) {
            // The project's blocks went (a reload): nobody mines one, or needs slowing.
            miners.stopAll()
            return
        }
        parts.time("world/blocks/mining") { miners.mine() }
        parts.time("world/blocks/looks") { miners.watchLooks() }
        if (!usable) return
        parts.time("world/blocks/falling") { fall() }
        parts.time("world/blocks/ticks") { tickBlocks() }
        parts.time("world/blocks/sweep") { sweep() }
    }

    /** Raises `tick` on each placed block due one, while its block's script listens. */
    private fun tickBlocks() {
        val now = ticks()
        for (key in ticking.toList()) {
            val blocks = chunks[key] ?: continue
            for ((at, placed) in blocks.kept.entries.toList()) {
                val interval = defs[placed.name]?.file?.tick ?: continue
                if (now < placed.next) continue
                placed.next = now + interval
                if (blocks.kept[at] !== placed) continue
                val target = LuaHandle.ProjectBlock(placed.name)
                if (!scripts.listening(target, Events.PROJECTBLOCK_TICK)) continue
                scripts.emit(
                    Events.PROJECTBLOCK_TICK,
                    target,
                    ProjectBlockTickEvent(LuaHandle.CustomBlock(key.world, BlockPosition.pack(at.x, at.y, at.z)))
                )
            }
        }
    }

    /**
     * Looks at a few chunks a tick, each every so often: a block something else took away, or a paste, is noticed
     * within seconds. A chunk whose blocks, records and legend are as they were the last time it was looked at
     * ([fingerprint]) is passed over: most never change, and reading one is the expensive part.
     */
    private fun sweep() {
        if (sweepQueue.isEmpty()) {
            sweepQueue.addAll(chunks.keys)
            swept.keys.retainAll(chunks.keys)
            legends.keys.retainAll(chunks.keys)
        }
        repeat(SWEEP_PER_TICK) {
            val key = sweepQueue.removeFirstOrNull() ?: return
            if (key !in chunks) return@repeat
            val before = fingerprint(key)
            if (before != null && swept[key] == before) return@repeat
            parts.time("world/blocks/sweep/reconcile") { reconcile(key) }
            // What it is now, after reconciling put it right.
            val after = fingerprint(key)
            if (after == null || key !in chunks) swept.remove(key) else swept[key] = after
        }
    }

    /**
     * What a chunk's custom blocks are, summed up: the blocks that could be any carrier state, and its legend. Its
     * records aren't read: only this writes them ([writeRecord]), which forgets the chunk was swept. Null when the
     * server can't tell (the chunk is then always read).
     */
    private fun fingerprint(key: ChunkKey): Long? {
        val legendText = parts.time("world/blocks/sweep/legend") { platform.blocks.legend(key.world, key.x, key.z) }
        val states = printStates(key, legendText)
        val blocks =
            parts.time("world/blocks/sweep/fingerprint") { platform.blocks.fingerprint(key.world, key.x, key.z, states) } ?: return null
        return blocks * 31 + legendText.hashCode()
    }

    /**
     * The carrier states a chunk's fingerprint watches: today's, and every one a legend has said a block was
     * placed in. More than any one chunk needs, which only makes it watch more, and the same list from chunk to
     * chunk, so the server works out what to look for once ([BlockOps.fingerprint] keeps it by the list).
     */
    private fun printStates(key: ChunkKey, legendText: String?): List<String> {
        val legend = legends[key]?.takeIf { it.first == legendText }?.second
            ?: readLegend(legendText).keys.also { legends[key] = legendText to it }
        val current = watched
        if (current != null && current.first === byState && current.second.containsAll(legend)) return current.third
        val states = LinkedHashSet(byState.keys).apply {
            current?.takeIf { it.first === byState }?.second?.let(::addAll)
            addAll(legend)
        }
        val list = states.toList()
        watched = Triple(byState, states, list)
        return list
    }

    /** What [printStates] watches now: under which states, as a set and as the list it hands out. */
    private var watched: Triple<Map<String, String>, Set<String>, List<String>>? = null

    /** Each swept chunk's legend, as text and the states it names, read again only when the text changes. */
    private val legends = HashMap<ChunkKey, Pair<String?, Set<String>>>()

    /** Stores a block's record, and forgets its chunk was swept: the sweep reads it again. */
    private fun writeRecord(world: String, x: Int, y: Int, z: Int, json: String?): Boolean {
        swept.remove(ChunkKey(world, x shr 4, z shr 4))
        return platform.blocks.setRecord(world, x, y, z, json)
    }

    /** Stores a chunk's legend, and forgets the chunk was swept. */
    private fun writeLegend(world: String, chunkX: Int, chunkZ: Int, json: String?): Boolean {
        swept.remove(ChunkKey(world, chunkX, chunkZ))
        return platform.blocks.setLegend(world, chunkX, chunkZ, json)
    }

    private companion object {
        const val AIR = "minecraft:air"

        const val CREATIVE = "creative"
        const val SPECTATOR = "spectator"
        const val MAIN_HAND = "main_hand"

        /** How the game plays a block's sound when it's placed or broken: (its volume + 1) / 2, its pitch × 0.8, for 1 and 1. */
        const val GAME_VOLUME = 1.0
        const val GAME_PITCH = 0.8

        /** The particle a breaking block throws up, which draws the block's own model's particle texture. */
        const val BLOCK_PARTICLE = "minecraft:block"

        /** How steeply a player looks down (or up) to place a block that turns every way facing up (or down): 45°. */
        const val STEEP = 45.0

        /** Chunks checked a tick: a tracked chunk is looked at again every `chunks / SWEEP_PER_TICK` ticks. */
        const val SWEEP_PER_TICK = 2
    }
}
