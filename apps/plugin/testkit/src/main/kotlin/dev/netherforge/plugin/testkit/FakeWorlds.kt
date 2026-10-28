package dev.netherforge.plugin.testkit

import dev.netherforge.format.block.BlockCarrier
import dev.netherforge.format.game.BlockState
import dev.netherforge.format.game.Box
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.game.has
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.project.SpawnCategory
import dev.netherforge.plugin.platform.BiomeSearch
import dev.netherforge.plugin.platform.BlockHit
import dev.netherforge.plugin.platform.BlockOps
import dev.netherforge.plugin.platform.BlockRecord
import dev.netherforge.plugin.platform.BlockRef
import dev.netherforge.plugin.platform.BlockSnapshot
import dev.netherforge.plugin.platform.BlockVector
import dev.netherforge.plugin.platform.ChunkGrid
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.GameRuleType
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.SpawnSetup
import dev.netherforge.plugin.platform.WorldOps
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

// The fake server's worlds and their blocks.

/** A block position in the fake's world. */
data class BlockAt(val world: String, val x: Int, val y: Int, val z: Int)

class FakeWorlds(private val platform: FakePlatform) : WorldOps {
    /** Every block below this height is solid stone, everywhere. Null: no ground. */
    var groundBelow: Int? = 64

    /** Extra solid full blocks (stone), in every world. */
    val solid = mutableSetOf<Triple<Int, Int, Int>>()

    /** Blocks scripts (or tests) set, over the ground and [solid]. */
    val blocks = LinkedHashMap<BlockAt, String>()

    /** The canonical state at a block: what was set there, else stone in the ground or [solid], else air. */
    fun state(world: String, x: Int, y: Int, z: Int): String = blocks[BlockAt(world, x, y, z)]
        ?: if (groundBelow?.let { y < it } == true || Triple(x, y, z) in solid) "minecraft:stone" else "minecraft:air"

    /** A state's collision boxes in block units: the game's, else a cube for anything that isn't air. */
    fun shape(state: String): List<Box> = platform.game.collisionBoxes(BlockState.parse(state)!!)
        ?: if (state == "minecraft:air") emptyList() else FakePlatform.CUBE

    var worldNames = mutableListOf("world", "nether")

    override fun names() = worldNames.toList()

    override fun defaultWorld() = "world"

    override fun exists(world: String) = world in names()

    val spawns = mutableMapOf<String, Location>()

    override fun spawnLocation(world: String) = if (exists(world)) spawns[world] ?: Location(world, 0.0, 64.0, 0.0) else null

    /** Chunks whose entities aren't loaded, as (world, chunk x, chunk z). Everything else is. */
    val unloaded = mutableSetOf<Triple<String, Int, Int>>()

    fun chunkOf(at: Location) = Triple(at.world, floor(at.x).toInt() shr 4, floor(at.z).toInt() shr 4)

    override fun entitiesLoaded(at: Location) = chunkOf(at) !in unloaded

    override fun environment(world: String) = if (!exists(world)) {
        null
    } else if (world == "nether") {
        "nether"
    } else {
        "normal"
    }

    val fullTimes = mutableMapOf<String, Long>()

    override fun fullTime(world: String) = if (exists(world)) fullTimes[world] ?: 0L else null

    override fun setFullTime(world: String, ticks: Long): Boolean {
        if (!exists(world)) return false
        fullTimes[world] = ticks
        return true
    }

    /** Each world's weather, and how long it was set for. */
    val weathers = mutableMapOf<String, Pair<String, Int?>>()

    override fun weather(world: String) = if (exists(world)) weathers[world]?.first ?: "clear" else null

    /** A phase a day, from the day count, as the game turns the moon. */
    override fun moonPhase(world: String) = fullTime(world)?.let { Math.floorMod(it / DAY_TICKS, MOON_PHASES).toInt() }

    override fun setWeather(world: String, weather: String, ticks: Int?): Boolean {
        if (!exists(world)) return false
        weathers[world] = weather to ticks
        return true
    }

    override fun setSpawnLocation(world: String, at: Location): Boolean {
        if (!exists(world)) return false
        spawns[world] = at
        return true
    }

    /** A world's heights: its dimension type's, as the datapack the fake server started with has it (the overworld's otherwise). */
    override fun heights(world: String) = if (exists(world)) platform.worldManager.heightsOf(world) else null

    /** The game rules the fake server has. */
    val ruleTypes = mapOf("minecraft:keep_inventory" to GameRuleType.BOOLEAN, "minecraft:random_tick_speed" to GameRuleType.INTEGER)
    val rules = mutableMapOf<Pair<String, String>, Any>()

    override fun gameRuleType(rule: String) = ruleTypes[rule]

    override fun gameRule(world: String, rule: String): Any? {
        if (!exists(world)) return null
        return rules[world to rule] ?: if (ruleTypes[rule] == GameRuleType.BOOLEAN) false else 3
    }

    override fun setGameRule(world: String, rule: String, value: Any): Boolean {
        if (!exists(world)) return false
        rules[world to rule] = value
        return true
    }

    /** The worlds' own spawn settings, which a negative value clears; what's not set is the server's, [serverSpawnLimit] and [serverSpawnInterval]. */
    val spawnLimits = mutableMapOf<Pair<String, SpawnCategory>, Int>()
    val spawnIntervals = mutableMapOf<Pair<String, SpawnCategory>, Int>()

    /** bukkit.yml's defaults. */
    fun serverSpawnLimit(category: SpawnCategory) = when (category) {
        SpawnCategory.MONSTER -> 70
        SpawnCategory.ANIMAL -> 10
        SpawnCategory.WATER_ANIMAL -> 5
        SpawnCategory.WATER_AMBIENT -> 20
        SpawnCategory.WATER_UNDERGROUND_CREATURE -> 5
        SpawnCategory.AMBIENT -> 15
        SpawnCategory.AXOLOTL -> 5
    }

    fun serverSpawnInterval(category: SpawnCategory) = if (category == SpawnCategory.ANIMAL) 400 else 1

    override fun spawnLimit(world: String, category: SpawnCategory) =
        if (exists(world)) spawnLimits[world to category] ?: serverSpawnLimit(category) else null

    override fun setSpawnLimit(world: String, category: SpawnCategory, limit: Int): Boolean {
        if (!exists(world)) return false
        if (limit < 0) spawnLimits.remove(world to category) else spawnLimits[world to category] = limit
        return true
    }

    override fun spawnInterval(world: String, category: SpawnCategory) =
        if (exists(world)) spawnIntervals[world to category] ?: serverSpawnInterval(category) else null

    override fun setSpawnInterval(world: String, category: SpawnCategory, ticks: Int): Boolean {
        if (!exists(world)) return false
        if (ticks < 0) spawnIntervals.remove(world to category) else spawnIntervals[world to category] = ticks
        return true
    }

    override fun isChunkLoaded(world: String, chunkX: Int, chunkZ: Int) = exists(world) && Triple(world, chunkX, chunkZ) !in unloaded

    /** Chunks something was put in (a record, a block): the fake's world is the same everywhere, so these are the ones that matter. */
    val touched = mutableSetOf<Triple<String, Int, Int>>()

    override fun loadedChunks(world: String): List<Pair<Int, Int>> {
        val chunks = touched.filter { it.first == world }.map { it.second to it.third } +
            blocks.keys.filter { it.world == world }.map { (it.x shr 4) to (it.z shr 4) } + listOf(0 to 0)
        return chunks.distinct().filter { isChunkLoaded(world, it.first, it.second) }
    }

    override fun loadChunk(world: String, chunkX: Int, chunkZ: Int): Boolean {
        if (!exists(world)) return false
        unloaded -= Triple(world, chunkX, chunkZ)
        return true
    }

    /** How many times each chunk is held now ([holdChunk]), as (world, chunk x, chunk z). */
    val held = mutableMapOf<Triple<String, Int, Int>, Int>()

    override fun holdChunk(world: String, chunkX: Int, chunkZ: Int): Boolean {
        if (!loadChunk(world, chunkX, chunkZ)) return false
        held.merge(Triple(world, chunkX, chunkZ), 1, Int::plus)
        return true
    }

    override fun releaseChunk(world: String, chunkX: Int, chunkZ: Int) {
        val key = Triple(world, chunkX, chunkZ)
        val left = (held[key] ?: return) - 1
        if (left > 0) held[key] = left else held.remove(key)
    }

    override fun highestBlockY(world: String, x: Int, z: Int): Int? {
        if (!isChunkLoaded(world, x shr 4, z shr 4)) return null
        return (319 downTo -64).firstOrNull { state(world, x, it, z) != "minecraft:air" } ?: -64
    }

    /** Biomes tests set, over [defaultBiome]. */
    val biomes = LinkedHashMap<BlockAt, String>()
    var defaultBiome = "minecraft:plains"

    /** Light levels tests set (the brighter of block and sky light), over [defaultLight]. */
    val lights = LinkedHashMap<BlockAt, Int>()
    var defaultLight = 15

    override fun biome(world: String, x: Int, y: Int, z: Int): String? {
        if (!isChunkLoaded(world, x shr 4, z shr 4)) return null
        return biomes[BlockAt(world, x, y, z)] ?: defaultBiome
    }

    /** Biome searches made, as "world x y z radius biome", so a test can see what a script asked. */
    val biomeSearches = mutableListOf<String>()

    /**
     * The fake's generator puts [defaultBiome] everywhere but the blocks in [biomes]: a search finds the
     * default where it starts, else the set block of that biome nearest in columns (as the real search
     * goes round in squares), whether or not its chunk is loaded.
     */
    override fun biomeSearch(world: String, x: Int, y: Int, z: Int, radius: Int, biome: String): BiomeSearch? {
        if (!exists(world) || platform.game.has(RegistryKey.BIOME, biome) == false) return null
        biomeSearches += "$world $x $y $z $radius $biome"
        val found = if (biome == defaultBiome) {
            Vec3(x.toDouble(), y.toDouble(), z.toDouble())
        } else {
            biomes.entries
                .filter { (at, it) -> at.world == world && it == biome && abs(at.x - x) <= radius && abs(at.z - z) <= radius }
                .minByOrNull { (at, _) -> max(abs(at.x - x), abs(at.z - z)) }
                ?.key
                ?.let { Vec3(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) }
        }
        return BiomeSearch { found }
    }

    val explosions = mutableListOf<String>()

    override fun explode(world: String, position: Vec3, power: Double, fire: Boolean, breakBlocks: Boolean): Boolean {
        if (!exists(world)) return false
        explosions += "$world $position power=$power fire=$fire break=$breakBlocks"
        return true
    }

    val lightning = mutableListOf<String>()

    override fun strikeLightning(world: String, position: Vec3, effectOnly: Boolean): Boolean {
        if (!exists(world)) return false
        lightning += "$world $position effect_only=$effectOnly"
        return true
    }

    /** Walks the ray through the blocks it passes, nearest first, and tests each one's shape. */
    override fun raycastBlocks(world: String, origin: Vec3, direction: Vec3, maxDistance: Double, fluids: Boolean): BlockHit? {
        val seen = LinkedHashSet<Triple<Int, Int, Int>>()
        var t = 0.0
        while (t <= maxDistance + 1) {
            val p = origin + direction * t
            seen += Triple(floor(p.x).toInt(), floor(p.y).toInt(), floor(p.z).toInt())
            t += 0.05
        }
        for ((x, y, z) in seen) {
            if (!isChunkLoaded(world, x shr 4, z shr 4)) continue
            val boxes = shape(state(world, x, y, z)).map {
                Box(
                    it.min + Vec3(x.toDouble(), y.toDouble(), z.toDouble()),
                    it.max + Vec3(x.toDouble(), y.toDouble(), z.toDouble())
                )
            }
            val hit = boxes.mapNotNull { FakePlatform.slab(it, origin, direction, maxDistance) }.minByOrNull { it.first } ?: continue
            return BlockHit(x, y, z, origin + direction * hit.first, hit.second)
        }
        return null
    }

    override fun collisionBoxes(
        world: String,
        minX: Double,
        minY: Double,
        minZ: Double,
        maxX: Double,
        maxY: Double,
        maxZ: Double
    ): List<Box> {
        val out = mutableListOf<Box>()
        for (x in floor(minX).toInt()..floor(maxX).toInt()) {
            for (z in floor(minZ).toInt()..floor(maxZ).toInt()) {
                // As on Paper: blocks in chunks that aren't loaded (or a world that isn't there) are left out.
                if (!isChunkLoaded(world, x shr 4, z shr 4)) continue
                for (y in floor(minY).toInt()..floor(maxY).toInt()) {
                    val corner = Vec3(x.toDouble(), y.toDouble(), z.toDouble())
                    for (box in shape(state(world, x, y, z))) out += Box(box.min + corner, box.max + corner)
                }
            }
        }
        return out
    }
}

class FakeBlocks(private val platform: FakePlatform) : BlockOps {
    /** Every change, as `"<world> x y z <state>"` with ` (no update)` when neighbours weren't told. */
    val changes = mutableListOf<String>()

    /** Each block position's stored JSON. */
    val data = LinkedHashMap<BlockAt, String>()

    private fun loaded(world: String, x: Int, z: Int) = platform.worlds.isChunkLoaded(world, x shr 4, z shr 4)

    override fun get(world: String, x: Int, y: Int, z: Int): BlockSnapshot? {
        if (!loaded(world, x, z)) return null
        val state = platform.worlds.state(world, x, y, z)
        val liquid = state.substringBefore('[') == "minecraft:water" || state.substringBefore('[') == "minecraft:lava"
        return BlockSnapshot(
            state,
            state == "minecraft:air",
            // As the server says: water and lava are never solid.
            !liquid && platform.worlds.shape(state).isNotEmpty(),
            liquid,
            platform.worlds.lights[BlockAt(world, x, y, z)] ?: platform.worlds.defaultLight,
            15
        )
    }

    override fun set(world: String, x: Int, y: Int, z: Int, state: String, update: Boolean): Boolean {
        if (!loaded(world, x, z)) return false
        platform.worlds.blocks[BlockAt(world, x, y, z)] = state
        changes += "$world $x $y $z $state" + if (update) "" else " (no update)"
        return true
    }

    val broken = mutableListOf<String>()

    override fun breakNaturally(world: String, x: Int, y: Int, z: Int, tool: ItemData?): Boolean {
        if (!loaded(world, x, z) || platform.worlds.state(world, x, y, z) == "minecraft:air") return false
        broken += "$world $x $y $z ${platform.worlds.state(world, x, y, z)}" + (tool?.let { " with ${it.def.kind}" } ?: "")
        platform.worlds.blocks[BlockAt(world, x, y, z)] = "minecraft:air"
        return true
    }

    override fun data(world: String, x: Int, y: Int, z: Int): String? = data[BlockAt(world, x, y, z)]

    override fun setData(world: String, x: Int, y: Int, z: Int, json: String?): Boolean {
        if (!loaded(world, x, z)) return false
        if (json == null) data.remove(BlockAt(world, x, y, z)) else data[BlockAt(world, x, y, z)] = json
        return true
    }

    override fun withData(world: String, chunkX: Int, chunkZ: Int): List<BlockVector>? {
        if (!platform.worlds.isChunkLoaded(world, chunkX, chunkZ)) return null
        return data.keys.filter { it.world == world && it.x shr 4 == chunkX && it.z shr 4 == chunkZ }.map { BlockVector(it.x, it.y, it.z) }
    }

    /** Each custom block's record, saved with its chunk. */
    val records = LinkedHashMap<BlockAt, String>()

    override fun records(world: String, chunkX: Int, chunkZ: Int): List<BlockRecord>? {
        if (!platform.worlds.isChunkLoaded(world, chunkX, chunkZ)) return null
        return records.filterKeys { it.world == world && it.x shr 4 == chunkX && it.z shr 4 == chunkZ }
            .map { (at, json) -> BlockRecord(at.x, at.y, at.z, json) }
    }

    override fun setRecord(world: String, x: Int, y: Int, z: Int, json: String?): Boolean {
        if (!loaded(world, x, z)) return false
        platform.worlds.touched += Triple(world, x shr 4, z shr 4)
        if (json == null) records.remove(BlockAt(world, x, y, z)) else records[BlockAt(world, x, y, z)] = json
        return true
    }

    /** Each chunk's legend of its custom blocks, saved with it. */
    val legends = LinkedHashMap<Triple<String, Int, Int>, String>()

    override fun legend(world: String, chunkX: Int, chunkZ: Int): String? =
        if (platform.worlds.isChunkLoaded(world, chunkX, chunkZ)) legends[Triple(world, chunkX, chunkZ)] else null

    override fun setLegend(world: String, chunkX: Int, chunkZ: Int, json: String?): Boolean {
        if (!platform.worlds.isChunkLoaded(world, chunkX, chunkZ)) return false
        platform.worlds.touched += Triple(world, chunkX, chunkZ)
        if (json == null) legends.remove(Triple(world, chunkX, chunkZ)) else legends[Triple(world, chunkX, chunkZ)] = json
        return true
    }

    override fun find(world: String, chunkX: Int, chunkZ: Int, states: List<String>): ChunkGrid? {
        if (!platform.worlds.isChunkLoaded(world, chunkX, chunkZ)) return null
        finds++
        val found = ChunkGrid()
        for ((at, state) in platform.worlds.blocks) {
            if (at.world != world || at.x shr 4 != chunkX || at.z shr 4 != chunkZ) continue
            val i = states.indexOf(state)
            if (i >= 0) found[at.x and 15, at.y, at.z and 15] = i + 1
        }
        return found
    }

    // What find finds, summed up: equal when it would find the same.
    override fun fingerprint(world: String, chunkX: Int, chunkZ: Int, states: List<String>): Long? {
        if (!platform.worlds.isChunkLoaded(world, chunkX, chunkZ)) return null
        fingerprints++
        return platform.worlds.blocks.entries
            .filter { (at, state) -> at.world == world && at.x shr 4 == chunkX && at.z shr 4 == chunkZ && state in states }
            .map { (at, state) -> "${at.x},${at.y},${at.z}=$state" }
            .sorted()
            .hashCode()
            .toLong()
    }

    /** How many chunks were fingerprinted, and how many read in full ([find]), for a test. */
    var fingerprints = 0
    var finds = 0

    /** What a state's hardness is, by block id, where a test says; a few of the game's, else 1. */
    val hardnesses = mutableMapOf(
        "minecraft:stone" to 1.5,
        "minecraft:note_block" to 0.8,
        "minecraft:oak_planks" to 2.0,
        "minecraft:bedrock" to -1.0
    )

    override fun hardness(state: String): Double? {
        val id = state.substringBefore('[')
        return hardnesses[id] ?: if (FakePlatform.GAME.block(id) != null) 1.0 else null
    }

    /** How fast a tool mines a state, by the tool's kind (null: a bare hand) and the state's id; 1 where a test says nothing. */
    val breakSpeeds = mutableMapOf<Pair<String?, String>, Double>("minecraft:iron_pickaxe" to "minecraft:stone" to 6.0)

    override fun breakSpeed(tool: ItemData?, state: String): Double? {
        val id = state.substringBefore('[')
        if (FakePlatform.GAME.block(id) == null) return null
        return breakSpeeds[tool?.def?.kind to id] ?: 1.0
    }

    /** What a tick of mining adds to a player's progress on a block, by the player; 0.1 where a test says nothing. */
    val breakProgresses = mutableMapOf<UUID, Double>()

    override fun breakProgress(player: UUID, world: String, x: Int, y: Int, z: Int): Double? {
        if (player !in platform.players.byId || !loaded(world, x, z)) return null
        // The game multiplies a player's progress by their block_break_speed, as the server's own does.
        val speed = platform.attributes.value(player, "minecraft:block_break_speed") ?: 1.0
        return (breakProgresses[player] ?: 0.1) * speed
    }

    /** Every crack drawn, as `"<player name> <world> x y z <stage>"`. */
    val cracks = mutableListOf<String>()

    override fun showBreaking(player: UUID, world: String, x: Int, y: Int, z: Int, stage: Int) {
        val who = platform.players.byId[player] ?: return
        cracks += "${who.ref.name} $world $x $y $z $stage"
    }

    /** What a protection plugin would say to a player's breaking a block: refused, when it names the player. */
    val refusedBreakers = mutableSetOf<UUID>()

    override fun breakAs(player: UUID, world: String, x: Int, y: Int, z: Int): Boolean {
        val who = platform.players.byId[player]?.ref ?: return false
        if (!loaded(world, x, z) || player in refusedBreakers) return false
        val state = platform.worlds.state(world, x, y, z)
        if (state == "minecraft:air") return false
        // As the server's own break: the runtime hears it, and its null cancels the server's (the runtime broke it itself).
        val tool = platform.worldEntities.equipment(player, "main_hand")
        val own = { if (harvests(tool, state) == false) emptyList() else gameDrops[state.substringBefore('[')].orEmpty() }
        val answer = platform.events!!.blockBreak(who, BlockRef(world, x, y, z, state.substringBefore('['), state), own, 0) ?: return false
        platform.worlds.blocks[BlockAt(world, x, y, z)] = "minecraft:air"
        // What the runtime said drops, else the game's own, where the block was.
        val centre = Vec3(x + 0.5, y + 0.5, z + 0.5)
        for (item in answer.drops ?: own()) platform.worldEntities.spawnItem(world, centre, item)
        return true
    }

    /** What the fake game drops for a block by id, when it's broken with a tool that gets its drops; nothing where a test says nothing. */
    val gameDrops = mutableMapOf<String, List<ItemData>>()

    /** The block each player is looking at, where a test says. */
    val targets = mutableMapOf<UUID, BlockAt>()

    override fun targeted(player: UUID): BlockRef? {
        val at = targets[player] ?: return null
        val state = platform.worlds.state(at.world, at.x, at.y, at.z)
        return BlockRef(at.world, at.x, at.y, at.z, state.substringBefore('['), state)
    }

    /** Places a test says a block doesn't fit (something stands there). */
    val occupied = mutableSetOf<BlockAt>()

    override fun canPlace(world: String, x: Int, y: Int, z: Int, state: String): Boolean {
        if (!loaded(world, x, z) || BlockAt(world, x, y, z) in occupied) return false
        // Tripwire is the block in the fake game nothing collides with: it goes where anything stands.
        if (state.startsWith("minecraft:tripwire")) return true
        // A player's feet and head take two blocks, as their body does.
        return platform.players.byId.values.none { player ->
            val at = player.location
            at.world == world &&
                floor(at.x).toInt() == x &&
                floor(at.z).toInt() == z &&
                (floor(at.y).toInt() == y || floor(at.y).toInt() + 1 == y)
        }
    }

    /** What a protection plugin would say to a player's placing a block: refused, when it names the player. */
    val refusedPlacers = mutableSetOf<UUID>()

    /** Every block a player placed through [place]: the player, the state, where, and against what. */
    val placed = mutableListOf<String>()

    override fun place(player: UUID, world: String, x: Int, y: Int, z: Int, state: String, against: BlockVector, hand: String): Boolean {
        if (!loaded(world, x, z) || player in refusedPlacers) return false
        val who = platform.players.byId[player]?.ref ?: return false
        val at = BlockAt(world, x, y, z)
        val before = platform.worlds.blocks[at]
        platform.worlds.blocks[at] = state
        val clicked = platform.worlds.state(world, against.x, against.y, against.z)
        // As the server's own event: scripts hear a player placed it, and may refuse it, which puts back what was there.
        val refused = platform.raise.blockPlace(
            GameEvent.BlockPlace(
                who,
                BlockRef(world, x, y, z, state.substringBefore('['), state),
                state,
                BlockRef(world, against.x, against.y, against.z, clicked.substringBefore('['), clicked)
            )
        )
        if (refused) {
            if (before == null) platform.worlds.blocks.remove(at) else platform.worlds.blocks[at] = before
            return false
        }
        changes += "$world $x $y $z $state (no update)"
        placed += "${who.name} $world $x $y $z $state against ${against.x} ${against.y} ${against.z} $hand"
        return true
    }

    /** Which block ids need a particular tool for their drops in the fake game, and the tools that get them. */
    val harvestTools = mutableMapOf("minecraft:stone" to setOf("minecraft:iron_pickaxe"))

    override fun harvests(tool: ItemData?, state: String): Boolean? {
        val id = state.substringBefore('[')
        if (FakePlatform.GAME.block(id) == null) return null
        val tools = harvestTools[id] ?: return true
        return tool?.def?.kind in tools
    }

    /** Every break shown, as `"<player name or everyone> <world> x y z <state>"`. */
    val shownBroken = mutableListOf<String>()

    override fun showBroken(player: UUID?, world: String, x: Int, y: Int, z: Int, state: String) {
        val who = player?.let { platform.players.byId[it]?.ref?.name ?: return } ?: "everyone"
        shownBroken += "$who $world $x $y $z $state"
    }

    /** What the runtime last asked to hear of: the block ids the world's breaks of are its, and the states that fall. */
    var watchedDrops: Set<String> = emptySet()
    var watchedFalling: Set<String> = emptySet()

    override fun watch(drops: Set<String>, falling: Set<String>) {
        watchedDrops = drops
        watchedFalling = falling
    }

    /** The falling blocks [fall] made: each entity, the state it carries and where it fell from. */
    val fallen = mutableListOf<Triple<UUID, String, BlockAt>>()

    override fun fall(world: String, x: Int, y: Int, z: Int): UUID? {
        if (!loaded(world, x, z)) return null
        val state = platform.worlds.state(world, x, y, z)
        val below = platform.worlds.state(world, x, y - 1, z)
        if (state == "minecraft:air" || below != "minecraft:air") return null
        val id = platform.worldEntities.spawn("minecraft:falling_block", Location(world, x + 0.5, y.toDouble(), z + 0.5), SpawnSetup())
            ?: return null
        platform.worlds.blocks[BlockAt(world, x, y, z)] = "minecraft:air"
        fallen += Triple(id, state, BlockAt(world, x, y, z))
        return id
    }

    /** The carriers the runtime asked the server to hold still, and whether the server agrees to. */
    val frozenCarriers = mutableSetOf<BlockCarrier>()
    var refuseFreeze = false

    /** Whether note blocks are held still. */
    val frozen: Boolean get() = BlockCarrier.NOTE_BLOCK in frozenCarriers

    override fun freeze(carrier: BlockCarrier): Boolean {
        if (refuseFreeze) return false
        frozenCarriers += carrier
        return true
    }
}

private const val DAY_TICKS = 24_000L
private const val MOON_PHASES = 8L
