package dev.netherforge.plugin.block

import dev.netherforge.format.block.BlockFile
import dev.netherforge.format.block.BlockTool
import dev.netherforge.format.block.GameBlockFile
import dev.netherforge.format.game.BlockState
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.item.AttributeOperation
import dev.netherforge.plugin.platform.AttributeModifierData
import dev.netherforge.plugin.platform.BlockRef
import dev.netherforge.plugin.platform.BlockVector
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlayerRef
import java.util.UUID

/**
 * How the project's blocks, and the game's blocks with rules that change their mining, are mined: by the server, not
 * the player's game. A player hitting one has their `block_break_speed` turned down so far ([slow]) that their game
 * never finishes (it would take the block away itself, and the note blocks around it), and [mine] counts their
 * progress each tick from what the server says the carrier would take with what they hold, times how much faster
 * the block is than its carrier ([miningFactor], [gameFactor]), shows the cracks, and breaks it at the end with
 * the server's own break. [CustomBlocks] owns the blocks; this holds who's mining what.
 */
internal class BlockMining(
    private val platform: Platform,
    private val namespace: () -> String,
    /** Whether the project's blocks are in play now (the server froze its note blocks). */
    private val usable: () -> Boolean,
    /** The name of the project's block placed at a position, or null. */
    private val placedName: (world: String, x: Int, y: Int, z: Int) -> String?,
    /** A project block's file, by name. */
    private val blockFile: (String) -> BlockFile?,
    /** The project's rules for one of the game's block states, when it has some. */
    private val gameRule: (String) -> GameBlockFile?
) {
    /**
     * A player mining one of the project's blocks, which the server counts ([mine]) and breaks for them: [factor]
     * is how much faster than its carrier the block is to mine with what they hold, [stage] the cracks shown.
     */
    private class Mining(val world: String, val at: BlockVector, val target: String, val factor: Double) {
        var progress = 0.0
        var stage = -1
    }

    /** Who's mining one of the project's blocks, and which. */
    private val mining = HashMap<UUID, Mining>()

    /** Players whose break speed this service turned down, so their own game never finishes mining one of its blocks. */
    private val slowed = HashSet<UUID>()

    private val toolStates = HashMap<BlockTool, String?>()

    /**
     * A player is hitting the block at [start]'s position: [start] says whether
     * it breaks at once, and may be cancelled (a block nobody can break by hand).
     * True cancels.
     *
     * The server mines the project's blocks, not the player's game: their
     * `block_break_speed` is turned down so far that their game never finishes
     * (it would take the block away itself, and the note blocks above and below
     * with it, until the server put them back), and [mine] counts their progress
     * each tick, shows the cracks and breaks it at the end.
     */
    fun startedBreaking(start: GameEvent.BlockStartBreak): Boolean {
        val player = start.player.uuid
        val name = if (usable()) placedName(start.block.world, start.block.x, start.block.y, start.block.z) else null
        val file = name?.let(blockFile)
        // A start, even on the same block, begins again from nothing (as the game's does when the tool changes).
        stopMining(player)
        if (name == null || file == null) return startedBreakingGame(start)
        val creative = platform.players.gameMode(player) == CREATIVE
        val hardness = file.hardnessOrDefault
        if (hardness == BlockFile.UNBREAKABLE && !creative) return true
        // The server breaks it on this hit: the player's game, slowed, doesn't.
        if (hardness == 0.0) start.instant = true
        if (creative || hardness == 0.0) return false
        // A carrier that breaks at once (tripwire) can't be mined slower: the player's game does it, as the server's.
        val factor = miningFactor(file, start.block.state, start.item)
        if (factor == null) {
            unslow(player)
            return false
        }
        slow(player)
        mining[player] = Mining(start.block.world, BlockVector(start.block.x, start.block.y, start.block.z), name, factor)
        return false
    }

    /**
     * [startedBreaking] for one of the game's blocks: one whose rule changes how it's mined is mined by the server, as
     * the project's are, with the game's own numbers for whatever the rule leaves out. A block the game breaks at once
     * (a flower) can't be made slower: the player's game breaks it on the hit.
     */
    private fun startedBreakingGame(start: GameEvent.BlockStartBreak): Boolean {
        val player = start.player.uuid
        val rule = gameRule(start.block.state)?.takeIf { it.minedByServer }
        if (rule == null) {
            unslow(player)
            return false
        }
        val creative = platform.players.gameMode(player) == CREATIVE
        val hardness = rule.hardness
        if (hardness == BlockFile.UNBREAKABLE && !creative) return true
        if (hardness == 0.0) start.instant = true
        if (creative || hardness == 0.0) return false
        val factor = gameFactor(rule, start.block.state, start.item)
        if (factor == null) {
            unslow(player)
            return false
        }
        slow(player)
        val at = BlockVector(start.block.x, start.block.y, start.block.z)
        mining[player] = Mining(start.block.world, at, GAME_TARGET + start.block.state.substringBefore('['), factor)
        return false
    }

    /** A player let go of the block they were mining, or looked away. */
    fun stoppedBreaking(player: PlayerRef, block: BlockRef) {
        val was = mining[player.uuid] ?: return
        if (was.world == block.world && was.at == BlockVector(block.x, block.y, block.z)) stopMining(player.uuid)
    }

    private fun modifierId() = "${namespace()}:mining"

    /** The player isn't mining one of the project's blocks any more: its cracks go. */
    fun stopMining(player: UUID) {
        val was = mining.remove(player) ?: return
        if (was.stage >= 0) platform.blocks.showBreaking(player, was.world, was.at.x, was.at.y, was.at.z, -1)
    }

    /** Turns [player]'s break speed down to [SLOWED] of what it is, if it isn't already. */
    private fun slow(player: UUID) {
        if (player in slowed) return
        val modifier = AttributeModifierData(modifierId(), SLOWED - 1.0, AttributeOperation.ADD_MULTIPLIED_TOTAL)
        if (platform.attributes.addModifier(player, BREAK_SPEED, modifier)) slowed += player
    }

    /** [player]'s speed is theirs again. */
    fun unslow(player: UUID) {
        if (slowed.remove(player)) platform.attributes.removeModifier(player, BREAK_SPEED, modifierId())
    }

    /** Takes the modifier off whether or not this session put it there: the server saves it with the player. */
    fun clearSlowed(player: UUID) {
        slowed -= player
        platform.attributes.removeModifier(player, BREAK_SPEED, modifierId())
    }

    /**
     * A tick of everyone mining one of the project's blocks: what the server
     * counts for the carrier with what they hold, their effects and footing
     * (less the slowing), times how much faster the block is than its carrier.
     * At 1 the block breaks as theirs, with the server's own break event.
     */
    fun mine() {
        for ((player, mined) in mining.entries.toList()) {
            val state = if (mined.target.startsWith(
                    GAME_TARGET
                )
            ) {
                platform.blocks.get(mined.world, mined.at.x, mined.at.y, mined.at.z)?.state
            } else {
                null
            }
            val now = targetAt(mined.world, mined.at.x, mined.at.y, mined.at.z, state)
            val mode = platform.players.gameMode(player)
            val step = platform.blocks.breakProgress(player, mined.world, mined.at.x, mined.at.y, mined.at.z)
            if (now != mined.target || mode == null || mode == CREATIVE || mode == SPECTATOR || step == null) {
                stopMining(player)
                continue
            }
            mined.progress += step / (if (player in slowed) SLOWED else 1.0) * mined.factor
            if (mined.progress < 1.0) {
                val stage = (mined.progress * CRACKS).toInt() - 1
                if (stage != mined.stage) {
                    mined.stage = stage
                    platform.blocks.showBreaking(player, mined.world, mined.at.x, mined.at.y, mined.at.z, stage)
                }
                continue
            }
            stopMining(player)
            val broke = platform.blocks.breakAs(player, mined.world, mined.at.x, mined.at.y, mined.at.z)
            // The server's break of one of the game's blocks shows everyone but the player, whose game never broke it.
            if (broke && state != null) platform.blocks.showBroken(player, mined.world, mined.at.x, mined.at.y, mined.at.z, state)
            // Refused (a protection plugin): they're still holding the button on it, and start over, as the game does.
            val after = if (state != null) platform.blocks.get(mined.world, mined.at.x, mined.at.y, mined.at.z)?.state else null
            val still = targetAt(mined.world, mined.at.x, mined.at.y, mined.at.z, after) == mined.target
            if (still) mining[player] = Mining(mined.world, mined.at, mined.target, mined.factor)
        }
    }

    /**
     * What's mined at a position, as [Mining.target] names it: the project's block's name, or for one of the game's
     * blocks with rules ([state] what's there now) [GAME_TARGET] and its id. Null for anything else.
     */
    private fun targetAt(world: String, x: Int, y: Int, z: Int, state: String?): String? {
        placedName(world, x, y, z)?.let { return it }
        if (state == null) return null
        return gameRule(state)?.takeIf { it.minedByServer }?.let { GAME_TARGET + state.substringBefore('[') }
    }

    /**
     * Slows everyone looking at one of the project's blocks before they hit it:
     * the change takes a moment to reach their game, which could finish a soft
     * block on the first hit meanwhile.
     */
    fun watchLooks() {
        for (player in platform.players.online()) {
            val id = player.uuid
            val mode = platform.players.gameMode(id)
            if (mode == CREATIVE || mode == SPECTATOR) {
                stopMining(id)
                unslow(id)
                continue
            }
            if (id in mining) continue
            val target = platform.blocks.targeted(id)
            val mined =
                target != null &&
                    (
                        (usable() && placedName(target.world, target.x, target.y, target.z) != null) ||
                            gameRule(target.state)?.minedByServer == true
                        ) &&
                    (platform.blocks.hardness(target.state) ?: 0.0) > 0
            if (mined) slow(id) else unslow(id)
        }
    }

    /**
     * How much faster than the carrier note block this block is to mine with
     * [tool], as the game counts it: the progress a tick of mining makes is the
     * tool's speed over the block's hardness (and 30, or 100 without the tool a
     * block needs), so what has to change is the ratio of the two blocks'.
     * Null when it can't be told (a game that doesn't say).
     */
    private fun miningFactor(file: BlockFile, carrier: String, tool: ItemData?): Double? {
        val blocks = platform.blocks
        val carrierHardness = blocks.hardness(carrier)?.takeIf { it > 0 } ?: return null
        val carrierSpeed = blocks.breakSpeed(tool, carrier) ?: return null
        val representative = file.tool?.let(::toolState)
        val speed = if (representative != null) blocks.breakSpeed(tool, representative) ?: 1.0 else 1.0
        val hardness = file.hardnessOrDefault.takeIf { it > 0 } ?: return null
        val harvests = !file.requiresToolOrDefault || rightTool(file.tool, tool)
        val vanilla = carrierSpeed / carrierHardness / HARVEST
        val custom = speed / hardness / (if (harvests) HARVEST else NO_HARVEST)
        return (custom / vanilla).coerceIn(MIN_FACTOR, MAX_FACTOR)
    }

    /**
     * How much faster than the game's own numbers the game's block [state] is to mine with [tool] under [file]'s
     * rules, as [miningFactor] works it out for a carrier: the game's progress a tick is its tool speed over its
     * hardness (and 30, or 100 without a tool that gets its drops), and the rule's is the same with its own numbers
     * where it has them. Null when it can't be told, or the game breaks the block at once or never.
     */
    private fun gameFactor(file: GameBlockFile, state: String, tool: ItemData?): Double? {
        val blocks = platform.blocks
        val gameHardness = blocks.hardness(state)?.takeIf { it > 0 } ?: return null
        val gameSpeed = blocks.breakSpeed(tool, state) ?: return null
        val gameHarvests = blocks.harvests(tool, state) ?: true
        val speed = if (file.tool != null) file.tool?.let(::toolState)?.let { blocks.breakSpeed(tool, it) } ?: 1.0 else gameSpeed
        val hardness = (file.hardness ?: gameHardness).takeIf { it > 0 } ?: return null
        val harvests = gameHarvests(file, state, tool) ?: gameHarvests
        val vanilla = gameSpeed / gameHardness / (if (gameHarvests) HARVEST else NO_HARVEST)
        val custom = speed / hardness / (if (harvests) HARVEST else NO_HARVEST)
        return (custom / vanilla).coerceIn(MIN_FACTOR, MAX_FACTOR)
    }

    /**
     * Whether [tool] is of the [kind] a block needs for its drops: one the game speeds up on the blocks of that tool
     * (a pickaxe on stone), which is how the game itself says what a tool is for.
     */
    fun rightTool(kind: BlockTool?, tool: ItemData?): Boolean {
        val representative = kind?.let(::toolState) ?: return false
        return (platform.blocks.breakSpeed(tool, representative) ?: 1.0) > 1.0
    }

    /** A block the game says [tool] mines (the first of its tag that takes time to mine), as the state the server is asked about; null when the game has none. */
    private fun toolState(tool: BlockTool): String? = toolStates.getOrPut(tool) {
        platform.game.tag(RegistryKey.BLOCK, tool.tag).orEmpty().sorted().firstNotNullOfOrNull { id ->
            val info = platform.game.block(id) ?: return@firstNotNullOfOrNull null
            BlockState(id).withDefaults(info).toString().takeIf { (platform.blocks.hardness(it) ?: 0.0) > 0 }
        }
    }

    /**
     * Whether [tool] gets the drops of [state], a game block with [file]'s rules: any tool without `requiresTool`
     * true or false to say otherwise, the game's own rule; with it, the rule's `tool`, or one the game mines the
     * block faster with. Null when the server can't say.
     */
    fun gameHarvests(file: GameBlockFile, state: String, tool: ItemData?): Boolean? = when (file.requiresTool) {
        false -> true
        true -> if (file.tool != null) rightTool(file.tool, tool) else platform.blocks.breakSpeed(tool, state)?.let { it > 1.0 }
        null -> platform.blocks.harvests(tool, state)
    }

    /** Nobody mines one of the project's blocks, or needs slowing, any more (they went with a reload). */
    fun stopAll() {
        for (player in mining.keys.toList()) stopMining(player)
        for (player in slowed.toList()) unslow(player)
    }

    /** Whoever mines one of the game's blocks stops: the rules it was mined by changed. */
    fun stopGameTargets() {
        for (player in mining.keys.toList()) if (mining[player]?.target?.startsWith(GAME_TARGET) == true) stopMining(player)
    }

    /** [player] left: what the server kept of their mining goes with them. */
    fun forget(player: UUID) {
        mining -= player
        slowed -= player
    }

    private companion object {
        /** What a [Mining.target] of one of the game's blocks starts with: no name of the project's block can. */
        const val GAME_TARGET = "#"
        const val CREATIVE = "creative"
        const val SPECTATOR = "spectator"

        /** The attribute the game scales how fast a player mines by (`block_break_speed`). */
        const val BREAK_SPEED = "minecraft:block_break_speed"

        /** What the game divides mining progress by: 30 with the tool a block needs, 100 without. */
        const val HARVEST = 30.0
        const val NO_HARVEST = 100.0

        /** The range the factor takes; a block mined a thousand times quicker than a note block is instant anyway. */
        const val MIN_FACTOR = 0.0001
        const val MAX_FACTOR = 1000.0

        /**
         * What a slowed player's break speed is multiplied by: their game would take hours to finish even a note
         * block with the fastest axe there is, and the server's count divides it out again.
         */
        const val SLOWED = 1e-6

        /** The cracks' stages a block's progress is shown in: the game's ten. */
        const val CRACKS = 10
    }
}
