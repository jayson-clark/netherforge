package dev.netherforge.format.block

import dev.netherforge.format.game.BlockInfo
import dev.netherforge.format.game.BlockState
import dev.netherforge.format.game.GameData
import dev.netherforge.format.game.RegistryKey

/**
 * How custom blocks are held in the world: as states of one of the game's own blocks, a **carrier**
 * ([BlockCarrier], `block.json`'s `carrier`), which the resource pack draws as the block's look. The server keeps
 * the game's block, the pack redraws that exact state, so a block has its own look, sounds and hardness and no
 * entity at all. Which carrier a block takes decides what the game does with it physically, since that's the
 * carrier's: a note block is a solid, opaque cube, tripwire has no collision at all, and leaves are a solid cube
 * the neighbours' faces show through.
 *
 * The server stops the game changing a carrier's states by itself (the plugin's adapter, per carrier in use), so a
 * state stays what it was set to. Each carrier keeps some of its states for the game's own use (**vanilla**: what a
 * player's note block, string or leaves are in) and gives the rest to blocks, rarest first, so a world that already
 * holds the game's block in odd states is the least likely to meet a custom block's:
 *
 * - **note block** ([NoteBlocks]): `instrument` × `note` × `powered`. Vanilla note blocks live in the default
 *   instrument's column (every note, powered or not), which the server tunes and plays as the game does; every other
 *   state is a block's, the last instruments the game lists first (only a mob head above gives those).
 * - **tripwire** ([Tripwires]): `attached`, `disarmed`, `powered` and the four sides. A frozen server places string in
 *   its default state and never powers, disarms or attaches it, so vanilla keeps every state that is neither powered
 *   nor disarmed (what the game's own traps and strings are in); blocks take the powered and disarmed ones, both
 *   before either, unattached before attached (an unattached tripwire is the taller one to point at).
 * - **leaves** ([Leaves]): `distance`, `persistent` and `waterlogged`, of each block the game's `leaves` tag lists.
 *   Natural leaves are never persistent, and a frozen server places a player's leaves at the farthest distance, so
 *   blocks take the persistent, dry states nearer than that.
 *
 * Blocks take a carrier's states in the order of their names ([plan]). A block's state can move when the project's
 * blocks change, so the server remembers which block each position is (not the state) and sets the state again when
 * its chunk loads.
 */
object BlockCarriers {
    /** The block whose states carry note-block blocks. */
    const val BLOCK = "minecraft:note_block"

    /** What the pack draws every note block state that no custom block uses as: the game's own look. */
    const val VANILLA_MODEL = "minecraft:block/note_block"

    const val INSTRUMENT = "instrument"
    const val NOTE = "note"
    const val POWERED = "powered"

    /** The game's tripwire: what string is once it's placed. */
    const val TRIPWIRE = "minecraft:tripwire"
    const val ATTACHED = "attached"
    const val DISARMED = "disarmed"
    private val SIDES = listOf("east", "north", "south", "west")

    /** The game's block tag of its leaves, each of which carries leaves blocks. */
    const val LEAVES_TAG = "minecraft:leaves"
    const val DISTANCE = "distance"
    const val PERSISTENT = "persistent"
    const val WATERLOGGED = "waterlogged"

    /**
     * The states one carrier holds blocks in, of the one or more game blocks it is ([blocks], each with every state
     * it has), split into the game's own ([isVanilla]) and the [carriers] blocks take, rarest first.
     */
    abstract class Pool internal constructor(val carrier: BlockCarrier) {
        /** Each of the game's blocks it takes states of, with every state that block has. */
        abstract val blocks: Map<String, List<BlockState>>

        /** The states custom blocks may take, rarest first. */
        abstract val carriers: List<BlockState>

        /** Whether [state] is one the game uses itself: a player's, which no custom block is ever in. */
        abstract fun isVanilla(state: BlockState): Boolean

        /** Every state of every block it takes. */
        val all: List<BlockState> get() = blocks.values.flatten()

        /** Whether [state] is one of the pool's: where a custom block could be. */
        fun isCarrier(state: BlockState): Boolean = state.id in blocks && !isVanilla(state)

        /**
         * The model the game draws every state of [block] with, when it's one model for all of them (a note block's,
         * a leaves block's: `minecraft:block/<name>`); null when its states draw differently, which only the client's
         * own blockstate file says (tripwire: see [VanillaBlockStates]).
         */
        open fun vanillaModel(block: String): String? = "${block.substringBefore(':')}:block/${block.substringAfter(':')}"
    }

    /** The note block's states. */
    class NoteBlocks internal constructor(val info: BlockInfo) : Pool(BlockCarrier.NOTE_BLOCK) {
        private val instruments = info.properties.getValue(INSTRUMENT)
        private val notes = info.properties.getValue(NOTE)
        private val powered = info.properties.getValue(POWERED)

        /** The instrument vanilla note blocks keep: the default one. */
        val vanillaInstrument: String = info.defaults[INSTRUMENT] ?: instruments.first()

        private fun state(instrument: String, note: String, powered: String) =
            BlockState(BLOCK, mapOf(INSTRUMENT to instrument, NOTE to note, POWERED to powered))

        override val blocks = mapOf(BLOCK to instruments.flatMap { i -> notes.flatMap { n -> powered.map { p -> state(i, n, p) } } })

        override val carriers: List<BlockState> = instruments.reversed()
            .filter { it != vanillaInstrument }
            .flatMap { i -> powered.sortedBy { it != "true" }.flatMap { p -> notes.map { n -> state(i, n, p) } } }

        override fun isVanilla(state: BlockState): Boolean = state.id == BLOCK && state.properties[INSTRUMENT] == vanillaInstrument

        override fun vanillaModel(block: String): String = VANILLA_MODEL
    }

    /** Tripwire's states. */
    class Tripwires internal constructor(info: BlockInfo) : Pool(BlockCarrier.TRIPWIRE) {
        private val names = info.properties.keys.sorted()

        override val blocks = mapOf(TRIPWIRE to every(names, info).map { BlockState(TRIPWIRE, it) })

        override val carriers: List<BlockState> = blocks.getValue(TRIPWIRE).filterNot(::isVanilla).sortedWith(
            compareBy<BlockState>(
                // Powered and disarmed, then disarmed, then powered: the rarer a vanilla tripwire is in it, the sooner.
                {
                    if (it.on(DISARMED) && it.on(POWERED)) {
                        0
                    } else if (it.on(DISARMED)) {
                        1
                    } else {
                        2
                    }
                },
                { it.on(ATTACHED) },
                { state -> SIDES.joinToString("") { if (state.on(it)) "1" else "0" } }
            )
        )

        override fun isVanilla(state: BlockState): Boolean = state.id == TRIPWIRE && !state.on(DISARMED) && !state.on(POWERED)

        /** Tripwire draws a model per shape: the client's own file says which (`client/vanilla.json`). */
        override fun vanillaModel(block: String): String? = null
    }

    /** The states of each of the game's leaves. */
    class Leaves internal constructor(types: Map<String, BlockInfo>) : Pool(BlockCarrier.LEAVES) {
        override val blocks = types.entries.sortedBy { it.key }.associate { (id, info) ->
            id to every(info.properties.keys.sorted(), info).map { BlockState(id, it) }
        }

        /** The farthest a leaves block can be from a log: what a frozen server places a player's leaves at, and keeps. */
        private val farthest: Map<String, String> = types.mapValues { (_, info) ->
            info.properties.getValue(DISTANCE).maxBy { it.toIntOrNull() ?: 0 }
        }

        override val carriers: List<BlockState> = blocks.values.flatMap { states ->
            states.filterNot(::isVanilla).sortedBy { it.properties[DISTANCE]?.toIntOrNull() ?: 0 }
        }

        override fun isVanilla(state: BlockState): Boolean {
            val far = farthest[state.id] ?: return false
            return !state.on(PERSISTENT) || state.on(WATERLOGGED) || state.properties[DISTANCE] == far
        }
    }

    private fun BlockState.on(property: String) = properties[property] == "true"

    /** Every combination of [names]' values. */
    private fun every(names: List<String>, info: BlockInfo): List<Map<String, String>> = names.fold(listOf(emptyMap())) { states, name ->
        states.flatMap { state -> info.properties.getValue(name).map { state + (name to it) } }
    }

    /** The states [carrier] holds blocks in, in [game]; null when it has none (no game data, or a game without the blocks). */
    fun pool(game: GameData?, carrier: BlockCarrier = BlockCarrier.NOTE_BLOCK): Pool? {
        game ?: return null
        return when (carrier) {
            BlockCarrier.NOTE_BLOCK -> {
                val info = game.block(BLOCK) ?: return null
                if (listOf(INSTRUMENT, NOTE, POWERED).any { info.properties[it].isNullOrEmpty() }) return null
                NoteBlocks(info)
            }
            BlockCarrier.TRIPWIRE -> {
                val info = game.block(TRIPWIRE) ?: return null
                if (listOf(DISARMED, POWERED).any { info.properties[it]?.toSet() != setOf("true", "false") }) return null
                Tripwires(info)
            }
            BlockCarrier.LEAVES -> {
                val types = game.tag(RegistryKey.BLOCK, LEAVES_TAG).orEmpty().mapNotNull { id ->
                    val info = game.block(id) ?: return@mapNotNull null
                    val has = listOf(DISTANCE, PERSISTENT, WATERLOGGED).all { !info.properties[it].isNullOrEmpty() }
                    if (has) id to info else null
                }.toMap()
                if (types.isEmpty()) null else Leaves(types)
            }
        }
    }

    /**
     * One block's use of a carrier: [name] is the block as the project names
     * it, [pack] the pack its model is in (`ui`, or `library:ui` for a
     * package's) and [entry] its key there, both null for a block with no
     * model yet. [hidden] is a block drawn by a centity, which shows no cube
     * of its own.
     */
    class Use(
        val name: String,
        val state: BlockState,
        val pack: String?,
        val entry: String?,
        val hidden: Boolean,
        /** The way the block faces in this state, for a block that turns ([BlockFile.rotation]); null for one that doesn't. */
        val facing: BlockFacing? = null
    ) {
        val carrier: BlockCarrier get() = carrierOf(state)
    }

    /**
     * Which state each block takes. [overflow] is the blocks there was no
     * state left for in their carrier, and [unheld] those whose carrier the
     * game doesn't have; neither takes any. Blocks whose model isn't shaped
     * like a reference are left out (validation says so).
     */
    class Plan(
        val pools: Map<BlockCarrier, Pool>,
        val home: String,
        val uses: List<Use>,
        val overflow: List<String>,
        val unheld: List<String> = emptyList()
    ) {
        private val byName = uses.groupBy { it.name }
        private val byState = uses.associateBy { it.state.toString() }

        /** The note block's states, for what only it has. */
        val pool: Pool? get() = pools[BlockCarrier.NOTE_BLOCK]

        /** The carriers some block is held in. */
        val carriers: Set<BlockCarrier> get() = uses.map { it.carrier }.toSet()

        /** The state [name] is held as (facing north, for one that turns), or null for a block that has none. */
        fun stateOf(name: String): BlockState? = byName[name]?.first()?.state

        /** The state [name] is held as facing [facing]; its one state for a block that doesn't turn, null for a way it can't face. */
        fun stateOf(name: String, facing: BlockFacing?): BlockState? {
            val held = byName[name] ?: return null
            if (held.first().facing == null) return held.first().state
            return held.firstOrNull { it.facing == (facing ?: BlockFacing.NORTH) }?.state
        }

        /** The block that [state] (canonical text) is the carrier of. */
        fun nameOf(state: String): String? = byState[state]?.name

        /** The way the block [state] (canonical text) is the carrier of faces in it; null for a block that doesn't turn. */
        fun facingOf(state: String): BlockFacing? = byState[state]?.facing

        /** Whether [state] is one a custom block could be in, of whichever carrier. */
        fun isCarrier(state: BlockState): Boolean = pools.values.any { it.isCarrier(state) }
    }

    /** The carrier [state] is a state of: the note block's unless it's tripwire's or a leaves block's. */
    private fun carrierOf(state: BlockState): BlockCarrier = when {
        state.id == TRIPWIRE -> BlockCarrier.TRIPWIRE
        state.id == BLOCK -> BlockCarrier.NOTE_BLOCK
        else -> BlockCarrier.LEAVES
    }

    /**
     * Gives every block in [blocks] (by the name the project knows it by:
     * `ruby_ore`, or `library:gem` for a package's) a state of its carrier's
     * pool, in name order. A block that doesn't run (one with an error in its
     * file) is a null: it keeps its place in the order so the others' states
     * don't move while it's being fixed, in the carrier [carriers] says its
     * file names (the note block's when it says none), and is drawn as the
     * game draws that state. A running block's references are written in full (a package's
     * block names `library:gems/gem`), so its model resolves in [home], the
     * project's namespace. Null without game data.
     */
    fun plan(
        blocks: Map<String, BlockFile?>,
        home: String,
        game: GameData?,
        carriers: Map<String, BlockCarrier> = emptyMap(),
        rotations: Map<String, BlockRotation> = emptyMap()
    ): Plan? {
        game ?: return null
        val pools = HashMap<BlockCarrier, Pool?>()
        val next = HashMap<BlockCarrier, Int>()
        val uses = mutableListOf<Use>()
        val overflow = mutableListOf<String>()
        val unheld = mutableListOf<String>()
        for (name in blocks.keys.sorted()) {
            val block = blocks.getValue(name)
            val carrier = block?.carrierOrDefault ?: carriers[name] ?: BlockCarrier.NOTE_BLOCK
            val pool = pools.getOrPut(carrier) { pool(game, carrier) }
            if (pool == null) {
                unheld += name
                continue
            }
            val model = block?.model?.resolve(home)
            if (block?.model != null && model == null) continue
            // A block that turns takes a state per way it faces, all or none of them.
            val facings = (block?.rotationOrDefault ?: rotations[name] ?: BlockRotation.NONE).facings
            val at = next[carrier] ?: 0
            val needed = maxOf(facings.size, 1)
            if (at + needed > pool.carriers.size) {
                overflow += name
                continue
            }
            next[carrier] = at + needed
            val pack = model?.let { if (it.namespace == home) it.pack else "${it.namespace}:${it.pack}" }
            if (facings.isEmpty()) {
                uses += Use(name, pool.carriers[at], pack, model?.key, block?.centity != null)
            } else {
                for ((i, facing) in facings.withIndex()) {
                    uses += Use(name, pool.carriers[at + i], pack, model?.key, block?.centity != null, facing)
                }
            }
        }
        // Nothing to hold any of them in (a game without the carriers' blocks): as no plan.
        val known = pools.filterValues { it != null }.mapValues { it.value!! }
        if (known.isEmpty() && blocks.isNotEmpty()) return null
        return Plan(known, home, uses, overflow, unheld)
    }

    /** How many blocks [carrier] has states for, or 0 without game data. */
    fun capacity(game: GameData?, carrier: BlockCarrier = BlockCarrier.NOTE_BLOCK): Int = pool(game, carrier)?.carriers?.size ?: 0
}
