package dev.netherforge.plugin.platform

import dev.netherforge.format.block.BlockCarrier
import dev.netherforge.format.game.Box
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.particle.SpawnData
import dev.netherforge.format.project.SpawnCategory
import java.util.UUID

/*
 * The world beyond centities: blocks, particles and sounds. World-level facts
 * (time, weather, game rules, chunks, explosions, ray casts) are on
 * [WorldOps]. Blocks are addressed by world name and block coordinates.
 */

/** What a block is right now. [state] is the full canonical block state (`minecraft:oak_stairs[facing=east,…]`). */
data class BlockSnapshot(
    val state: String,
    val air: Boolean,
    /** Something players stand on and can't walk through. */
    val solid: Boolean,
    /** Water or lava. */
    val liquid: Boolean,
    /** The brighter of block light and sky light, 0 to 15. */
    val light: Int,
    val skyLight: Int
)

/**
 * Blocks. Nothing here ever loads a chunk: a block in a chunk that isn't
 * loaded (or a world that doesn't exist) reads as null and can't be changed.
 */
interface BlockOps {
    fun get(world: String, x: Int, y: Int, z: Int): BlockSnapshot?

    /** Sets a canonical block state the server has. [update]: neighbours react, as to a player placing it. */
    fun set(world: String, x: Int, y: Int, z: Int, state: String, update: Boolean): Boolean

    /** Breaks it with its drops for [tool] (none given: as by hand). False for air. */
    fun breakNaturally(world: String, x: Int, y: Int, z: Int, tool: ItemData?): Boolean

    /** The JSON a script keeps for this block position (`block:data()`), stored with the chunk; null when there's none. */
    fun data(world: String, x: Int, y: Int, z: Int): String?

    /** Stores (or with null, clears) a block position's JSON. False when its chunk isn't loaded. */
    fun setData(world: String, x: Int, y: Int, z: Int, json: String?): Boolean

    /** The positions in a loaded chunk with JSON stored, or null when the chunk isn't loaded. */
    fun withData(world: String, chunkX: Int, chunkZ: Int): List<BlockVector>?

    // ---- custom blocks (W4.1): what the runtime needs of the server to hold a block as a note block state ----

    /**
     * Every custom block the server remembers in a chunk (what [setRecord] stored
     * there, saved with the chunk), or null when the chunk isn't loaded.
     */
    fun records(world: String, chunkX: Int, chunkZ: Int): List<BlockRecord>?

    /** Stores (or with null, clears) the record of the custom block at a position, saved with its chunk. False when the chunk isn't loaded. */
    fun setRecord(world: String, x: Int, y: Int, z: Int, json: String?): Boolean

    /**
     * What the runtime keeps about a whole chunk's custom blocks (which carrier state each of them was placed as,
     * what [setLegend] stored, saved with the chunk), or null when there's none or the chunk isn't loaded.
     */
    fun legend(world: String, chunkX: Int, chunkZ: Int): String?

    /** Stores (or with null, clears) a chunk's [legend]. False when the chunk isn't loaded. */
    fun setLegend(world: String, chunkX: Int, chunkZ: Int, json: String?): Boolean

    /**
     * Where in a loaded chunk's blocks any of [states] (canonical) is, and
     * which of them is there (its index in [states] plus one), or null when
     * the chunk isn't loaded. Quick when the chunk has none of them, and
     * cheap however many it has.
     */
    fun find(world: String, chunkX: Int, chunkZ: Int, states: List<String>): ChunkGrid?

    /**
     * Every block from [x0], [y0], [z0] to [x1], [y1], [z1] (both included, the first the lowest), as an index into
     * the states the box holds: x fastest, then z, then y. Null when any of it is in a chunk that isn't loaded, or
     * the world isn't there. This reads block by block; a server reads the chunks' sections at once.
     */
    fun box(world: String, x0: Int, y0: Int, z0: Int, x1: Int, y1: Int, z1: Int): BlockBoxRead? {
        val states = ArrayList<BoxState>()
        val index = HashMap<BoxState, Int>()
        val cells = IntArray((x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1))
        var i = 0
        for (y in y0..y1) {
            for (z in z0..z1) {
                for (x in x0..x1) {
                    val block = get(world, x, y, z) ?: return null
                    val state = BoxState(block.state, block.solid, block.liquid)
                    cells[i++] = index.getOrPut(state) {
                        states += state
                        states.size - 1
                    }
                }
            }
        }
        return BlockBoxRead(states, cells)
    }

    /**
     * A number that changes whenever the blocks of a loaded chunk that could be any of [states] change: the same
     * number means [find] would find the same, so the chunk needn't be read again. Much cheaper than [find]. Null
     * when the server can't tell that way, or the chunk isn't loaded: then anything may have changed.
     */
    fun fingerprint(world: String, chunkX: Int, chunkZ: Int, states: List<String>): Long? = null

    /** The block state [state]'s hardness: how long the game takes to mine it. Null for a state the server doesn't have. */
    fun hardness(state: String): Double?

    /**
     * How fast [tool] (null: a bare hand) mines [state], as the game counts it
     * before the player's own multipliers: 1 for a tool that isn't a sped-up
     * one for it, more for one that is (a pickaxe on stone). Null for a state
     * the server doesn't have.
     */
    fun breakSpeed(tool: ItemData?, state: String): Double?

    /**
     * What a tick of [player] (online) mining the block at the position adds to its progress (1 breaks it), as the
     * server counts it for the block that's there: their tool, enchantments, effects, attributes and footing. Null
     * when the player isn't online or the chunk isn't loaded.
     */
    fun breakProgress(player: UUID, world: String, x: Int, y: Int, z: Int): Double?

    /**
     * Draws the cracks of [player] mining the block at the position at [stage] (0 to 9; -1 takes them away) for
     * everyone near it, [player] too: what the game shows of a block being mined, when the server counts it.
     */
    fun showBreaking(player: UUID, world: String, x: Int, y: Int, z: Int, stage: Int)

    /**
     * [player] (online) breaks the block at the position as if they'd mined it: the server's own break, with its
     * `BlockBreakEvent` (which protection plugins may refuse, and which the runtime hears as [PlatformEvents.blockBreak]).
     * False when it wasn't broken.
     */
    fun breakAs(player: UUID, world: String, x: Int, y: Int, z: Int): Boolean

    /** The block [player] (online) is looking at within reach, or null for none. */
    fun targeted(player: UUID): BlockRef?

    /**
     * Whether a block of [state] fits at the position: nothing standing there that a block can't be put into, as for
     * a player placing one. A block with nothing to collide with (tripwire) goes where anything stands, as string does.
     */
    fun canPlace(world: String, x: Int, y: Int, z: Int, state: String): Boolean

    /**
     * Places [state] at the position for [player] (online) as they would
     * place it, against [against] with [hand]: the block is set and the
     * server's own `BlockPlaceEvent` is raised, so protection plugins may
     * refuse it, in which case what was there is put back. False when it
     * was refused or the chunk isn't loaded.
     */
    fun place(player: UUID, world: String, x: Int, y: Int, z: Int, state: String, against: BlockVector, hand: String): Boolean

    /**
     * Whether [tool] (null: a bare hand) gets [state]'s drops as the game counts it: true for a block that needs no
     * particular tool, or for the tool it needs (a pickaxe on stone). Null for a state the server doesn't have.
     */
    fun harvests(tool: ItemData?, state: String): Boolean?

    /**
     * Shows the block at the position breaking as [state] (its sound and particles, as the game shows a block broken):
     * to [player] (online) alone, or with null to everyone near it. The server's break for a player leaves that player
     * out, since their own game shows its own, which a block the server mined never did.
     */
    fun showBroken(player: UUID?, world: String, x: Int, y: Int, z: Int, state: String)

    /**
     * What the runtime wants to hear of blocks besides players breaking them: the world breaking one of [drops] (block
     * ids) itself, whose drops are then [PlatformEvents.worldBroke]'s to say (support lost, a fluid, a piston, leaves
     * decaying), and a neighbour of one of [falling] (canonical states) changing ([PlatformEvents.blockUpdated]), with
     * a falling block of any of them landing ([PlatformEvents.fallingLanded]). Each call replaces the last; nothing is
     * heard while both are empty.
     */
    fun watch(drops: Set<String>, falling: Set<String>)

    /**
     * Drops the block at the position as a falling block, as sand falls, when what's under it is something a block
     * falls into (air, a fluid, a replaceable block): the block is gone and the falling block entity's id is returned.
     * The entity lands as the same state, or drops nothing where it can't. Null when it doesn't fall.
     */
    fun fall(world: String, x: Int, y: Int, z: Int): UUID?

    /**
     * Stops the server working out the states of [carrier]'s blocks for itself, so custom blocks held in them stay
     * in the state they were set to. Note blocks (Paper's `disable-noteblock-updates`): no instrument from what's
     * above and below, `powered` from redstone, tuning or sounding; the adapter does the vanilla note blocks' part
     * itself from then on, in the default instrument's column. Tripwire (`disable-tripwire-updates`): string goes
     * down in its default state and nothing attaches, powers or disarms it. Leaves: a persistent leaves block's
     * distance isn't worked out again, and a player's leaves go down at the farthest distance; natural leaves still
     * decay. Idempotent, and it stays in force while the plugin is enabled. False when this server can't (then
     * nothing holds custom blocks in that carrier).
     */
    fun freeze(carrier: BlockCarrier): Boolean
}

/** A custom block the server remembers at a position: [json] is what the runtime stored with [BlockOps.setRecord]. */
data class BlockRecord(val x: Int, val y: Int, val z: Int, val json: String)

/** What [BlockOps.box] read: each block an index into [states], x fastest, then z, then y. */
class BlockBoxRead(val states: List<BoxState>, val cells: IntArray)

/** One state a box holds (canonical), and whether it's solid and whether it's water or lava, as [BlockSnapshot] says. */
data class BoxState(val state: String, val solid: Boolean, val liquid: Boolean)

/** What a ray cast through the world's blocks hit: the block, where, and the normal of the face. */
data class BlockHit(val x: Int, val y: Int, val z: Int, val position: Vec3, val normal: Vec3)

/** Which value a game rule takes. */
enum class GameRuleType { BOOLEAN, INTEGER }

/**
 * One particle spawn, in world space: what the game's spawn packet carries.
 * With [count] above 0 the game scatters that many around [position] with a
 * Gaussian [offset] and random [speed]; with [count] 0 it sends one moving
 * along [offset] at [speed]. [data] is the same `SpawnData` format's effect
 * sampler emits, so `world:spawn_particle` and particle effects share one
 * path to the client.
 */
data class ParticleSpawn(
    val particle: String,
    val position: Vec3,
    val count: Int,
    val offset: Vec3,
    val speed: Double,
    val data: SpawnData?,
    val force: Boolean
)

interface ParticleOps {
    /** One batch of spawns in one world, each sent to [viewers] only. */
    fun spawn(world: String, spawns: List<ParticleSpawn>, viewers: List<PlayerRef>)

    /**
     * Drops whatever the adapter keeps between spawns (Bukkit particles, block
     * data, item stacks): the project reloaded, and an item's look may come
     * from a pack that changed.
     */
    fun forget()
}

/** A sound to play: an id (a Minecraft sound, or a pack's as the game names it: `shop:ui/click`), the volume slider it follows, how loud and how high. */
data class SoundPlay(val sound: String, val category: String, val volume: Double, val pitch: Double)

interface SoundOps {
    /** Plays at a position in a world, for everyone near enough. False when the world doesn't exist. */
    fun play(world: String, position: Vec3, sound: SoundPlay): Boolean

    /** Plays for one player only, following them. False when they're offline. */
    fun playTo(player: UUID, sound: SoundPlay): Boolean

    /** Stops [sound] for one player, or every sound for null. False when they're offline. */
    fun stop(player: UUID, sound: String?): Boolean
}

interface WorldOps {
    fun names(): List<String>

    /** The world things go in when nobody says which: the server's first. */
    fun defaultWorld(): String

    fun exists(world: String): Boolean

    fun spawnLocation(world: String): Location?

    /**
     * Whether the entities of the chunk at [at] are loaded. While they are, an
     * entity of ours that should stand there and can't be reached is gone
     * (killed, removed by another plugin), not unloaded. Never loads anything.
     */
    fun entitiesLoaded(at: Location): Boolean

    /**
     * The collision boxes of every block overlapping the region from ([minX],
     * [minY], [minZ]) to ([maxX], [maxY], [maxZ]), in world coordinates: what a
     * physics body can land on. The live collision shape of each block (a body
     * lands on a fence where a player would and falls past a torch). Blocks in
     * chunks that aren't loaded are left out, never loaded for this.
     */
    fun collisionBoxes(world: String, minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double): List<Box>

    // The rest answers null (or false) for a world that doesn't exist.

    /** `normal`, `nether` or `end` (a custom dimension is `normal`). */
    fun environment(world: String): String?

    /** Ticks since the world began: the time of day is this modulo 24000, the day count this over 24000. */
    fun fullTime(world: String): Long?

    fun setFullTime(world: String, ticks: Long): Boolean

    /** `clear`, `rain` or `thunder`. */
    fun weather(world: String): String?

    /** Tonight's moon as the server works it out: 0 full, 1 waning gibbous, … 4 new, … 7 waxing gibbous. */
    fun moonPhase(world: String): Int?

    /** Changes the weather for [ticks], or for as long as the game picks when null. */
    fun setWeather(world: String, weather: String, ticks: Int?): Boolean

    fun setSpawnLocation(world: String, at: Location): Boolean

    /** The lowest block y, and one above the highest. */
    fun heights(world: String): Pair<Int, Int>?

    /** Which value the game rule with this namespaced id takes, or null when the server has no such rule. */
    fun gameRuleType(rule: String): GameRuleType?

    /** A Boolean or an Int, as [gameRuleType] says. */
    fun gameRule(world: String, rule: String): Any?

    /** [value] is a Boolean or an Int, as [gameRuleType] says. */
    fun setGameRule(world: String, rule: String, value: Any): Boolean

    /**
     * The mob cap per player for [category] in this world: its own setting, or the server's when it has none.
     * Null when the world doesn't exist.
     */
    fun spawnLimit(world: String, category: SpawnCategory): Int?

    /** Sets the world's own mob cap for [category]; a negative [limit] goes back to the server's. False when the world doesn't exist. */
    fun setSpawnLimit(world: String, category: SpawnCategory, limit: Int): Boolean

    /** Ticks between spawn attempts for [category] in this world: its own setting, or the server's. Null when the world doesn't exist. */
    fun spawnInterval(world: String, category: SpawnCategory): Int?

    /** Sets the world's own ticks between spawn attempts for [category]; a negative [ticks] goes back to the server's. False when the world doesn't exist. */
    fun setSpawnInterval(world: String, category: SpawnCategory, ticks: Int): Boolean

    /** Whether the chunk (in chunk coordinates) is loaded: whether its blocks can be read now. */
    fun isChunkLoaded(world: String, chunkX: Int, chunkZ: Int): Boolean

    /** Every chunk loaded in the world now, as (chunk x, chunk z); empty for a world that doesn't exist. */
    fun loadedChunks(world: String): List<Pair<Int, Int>>

    /** Loads (generating if need be) a chunk, now. */
    fun loadChunk(world: String, chunkX: Int, chunkZ: Int): Boolean

    /**
     * Keeps a chunk loaded, its entities too, until it's released as many times as it was held: loaded (generated
     * if need be) now if it isn't. [loadChunk] alone lasts the tick: the server unloads a chunk nothing holds. False
     * when the world doesn't exist, and nothing is held then.
     */
    fun holdChunk(world: String, chunkX: Int, chunkZ: Int): Boolean

    /** Lets go of one [holdChunk] of the chunk; the server unloads it as any other once nothing holds it. */
    fun releaseChunk(world: String, chunkX: Int, chunkZ: Int)

    /** The y of the highest block in a column that isn't air, or null when its chunk isn't loaded. */
    fun highestBlockY(world: String, x: Int, z: Int): Int?

    /** The biome at a block, namespaced (`minecraft:plains`); null when its chunk isn't loaded or the world doesn't exist. Never loads a chunk. */
    fun biome(world: String, x: Int, y: Int, z: Int): String?

    /**
     * A search for the nearest place within [radius] blocks across of ([x], [y], [z]) where the world's
     * generator puts [biome] (namespaced), as `/locate biome` does: out from there in a square spiral,
     * every [BiomeSearch.ACROSS] blocks across and [BiomeSearch.UP] up and down, asking the world's
     * biome source, so it never loads or generates a chunk. Made on the main thread: null when the world
     * doesn't exist or the server has no such biome.
     */
    fun biomeSearch(world: String, x: Int, y: Int, z: Int, radius: Int, biome: String): BiomeSearch?

    fun explode(world: String, position: Vec3, power: Double, fire: Boolean, breakBlocks: Boolean): Boolean

    fun strikeLightning(world: String, position: Vec3, effectOnly: Boolean): Boolean

    /**
     * The first block whose collision shape a ray hits within [maxDistance],
     * or null. [direction] is a unit vector. Water and lava are hit as solid
     * only with [fluids]. Blocks in chunks that aren't loaded aren't hit.
     */
    fun raycastBlocks(world: String, origin: Vec3, direction: Vec3, maxDistance: Double, fluids: Boolean): BlockHit?
}

/**
 * A biome search [WorldOps.biomeSearch] made: it reads only what the world's
 * generator reads while it generates chunks on the server's own worker
 * threads, so the runtime runs it off the main thread (it can take seconds
 * when the biome is far or nowhere).
 */
fun interface BiomeSearch {
    /** The block position it found the biome at, or null when there's none within the radius. */
    fun run(): Vec3?

    companion object {
        /** Blocks between the columns it looks at: `/locate biome`'s. */
        const val ACROSS = 32

        /** Blocks between the heights it looks at in a column. */
        const val UP = 64
    }
}
