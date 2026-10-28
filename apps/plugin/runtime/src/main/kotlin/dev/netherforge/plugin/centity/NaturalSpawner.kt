package dev.netherforge.plugin.centity

import dev.netherforge.format.biome.BiomeKind
import dev.netherforge.format.centity.CompiledCentity
import dev.netherforge.format.centity.SpawnTime
import dev.netherforge.format.centity.SpawnWeather
import dev.netherforge.format.centity.SpawningDef
import dev.netherforge.format.game.BlockState
import dev.netherforge.format.game.GameData
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.project.Names
import dev.netherforge.plugin.RuntimeLog
import dev.netherforge.plugin.binding.CentityNaturalSpawnEvent
import dev.netherforge.plugin.binding.Events
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.LuaLocation
import dev.netherforge.plugin.lua.CallResult
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaFunction
import dev.netherforge.plugin.platform.BlockSnapshot
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.Scripts
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.TickPhase
import java.util.UUID
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/**
 * Centities that appear by themselves near players, from the `spawning` block
 * of their `centity.json` (see the format page).
 *
 * It runs in the world's tick phase, on the main thread, and keeps to a
 * budget however many players and centities there are: each player gets a
 * pass every [PASS_INTERVAL] ticks (staggered by player, so they don't all
 * land on one tick), at most [PLAYERS_PER_TICK] passes run in a tick, and a
 * pass looks at [ATTEMPTS] places. A place is a column 24 to 48 blocks from
 * the player in a loaded chunk, searched downward from near the player's own
 * height for somewhere to stand ([findSpot]): block reads are the cost, and
 * they're bounded by `PLAYERS_PER_TICK * ATTEMPTS * SEARCH`.
 *
 * Which centities may appear at a place is each rule's filter (world, the
 * world's time, moon and weather, height, light, biome, the block stood on),
 * its `when` condition (a script's, asked last: see [registerCondition]) and
 * its cap near this player; one is chosen by weight, then a group of them,
 * each member asked of `nf.on("centity_natural_spawn")` first, which may
 * refuse it or move it. What only depends on the world (its time, moon and
 * weather) is asked once a pass, before any block is read.
 * Natural instances are temporary (see [Instance.natural]); a pass over them
 * every [PASS_INTERVAL] ticks removes any with no player within its
 * `despawnDistance`.
 *
 * A service of its own, after [Centities]: it only asks it to spawn, keep and
 * remove, and holds nothing that outlives the session.
 */
class NaturalSpawner(
    private val platform: Platform,
    private val scripts: Scripts,
    private val log: RuntimeLog,
    private val centities: () -> Centities,
    /** A centity's name as the project spells it (`wisp`, or `acme:wisp`). */
    private val nameOf: (String) -> String,
    /** The project's namespace: what a project biome a `spawning` block names (`ruby_grove`) is on the server in. */
    private val home: () -> String,
    /** The one place chance comes from: a test gives it a seed. */
    internal var random: Random = Random.Default
) : RuntimeService {
    override val name get() = "natural spawning"

    private var ticks = 0L

    /** When each player's next pass is due, in [ticks]. */
    private val due = HashMap<UUID, Long>()

    /** What each centity's `spawning` block means, resolved once against the game and kept while the definition is the same. */
    private val rules = HashMap<String, Pair<CompiledCentity, Rule>>()

    /** A condition a script registered for `spawning.when`: [callback] is kept until [scope] goes. */
    private class Condition(val scope: Scope, val name: String, val callback: LuaFunction)

    /** Every live condition, by its full name (`<namespace>:<name>`). */
    private val conditions = HashMap<String, Condition>()

    /** Conditions a rule named that no script had registered, by `<centity> <condition>`: each logged once. */
    private val missing = HashSet<String>()

    /**
     * `nf.spawning.condition`: [name] in [scope]'s package, asked for every place a rule naming it fits. A
     * mistake is a [LuaApiException], and [callback] is let go of when one is thrown.
     */
    fun registerCondition(scope: Scope, name: String, callback: LuaFunction) {
        try {
            if (!Names.isId(name)) {
                throw LuaApiException("bad argument 'name' (\"$name\" can't name a spawn condition: ${Names.ID_RULE})")
            }
            val key = "${scope.namespace}:$name"
            conditions[key]?.let { throw LuaApiException("the spawn condition \"$name\" is already registered by ${it.scope.owner.label}") }
            conditions[key] = Condition(scope, name, callback)
        } catch (e: LuaApiException) {
            scripts.unref(callback.ref)
            throw e
        }
    }

    override fun scopeReleased(scope: Scope) {
        val gone = conditions.filterValues { it.scope == scope }
        for ((key, condition) in gone) {
            conditions.remove(key)
            scripts.unref(condition.callback.ref)
        }
    }

    override fun stop() {
        for (condition in conditions.values) scripts.unref(condition.callback.ref)
        conditions.clear()
    }

    override fun tick(phase: TickPhase) {
        if (phase != TickPhase.WORLD) return
        ticks++
        try {
            if (ticks % PASS_INTERVAL == 0L) despawn()
            spawnPass()
        } catch (e: Exception) {
            // The spawner's bug mustn't cost the tick anything else.
            log.error("Natural spawning failed", e)
        }
    }

    override fun playerQuit(player: PlayerRef) {
        due.remove(player.uuid)
    }

    // ---- spawning --------------------------------------------------------------

    private fun spawnPass() {
        val online = platform.players.online()
        if (online.isEmpty()) return
        val all = ruleList()
        if (all.isEmpty()) return
        var passes = 0
        var natural: List<Instance>? = null
        for (player in online) {
            if (passes >= PLAYERS_PER_TICK) break
            val at = due.getOrPut(player.uuid) { ticks + stagger(player.uuid) }
            if (at > ticks) continue
            due[player.uuid] = ticks + PASS_INTERVAL
            passes++
            val where = platform.players.location(player.uuid) ?: continue
            val sky = sky(where.world) ?: continue
            val here = all.filter { it.allowsWorld(where.world) && it.allowsSky(sky) }
            if (here.isEmpty()) continue
            val instances = natural ?: centities().naturals().also { natural = it }
            pass(player, where, here, instances)
        }
    }

    /** A player's offset into the pass interval, from who they are, so a crowd that joined together doesn't pass together. */
    private fun stagger(player: UUID): Long = Math.floorMod(player.hashCode(), PASS_INTERVAL.toInt()).toLong()

    private fun pass(player: PlayerRef, where: Location, candidates: List<Rule>, naturals: List<Instance>) {
        val room = candidates.associateWith { rule ->
            val near = naturals.count { it.centity == rule.id && near(it.anchor, where, rule.despawnDistance) }
            rule.cap - near
        }.filterValues { it > 0 }
        if (room.isEmpty()) return
        val remaining = HashMap(room)
        repeat(ATTEMPTS) {
            if (remaining.isEmpty()) return
            val spot = findSpot(where) ?: return@repeat
            val fits = remaining.keys.filter { it.fits(spot) && holds(it, spot, player) }
            if (fits.isEmpty()) return@repeat
            val rule = choose(fits)
            val wanted = rule.groupMin + random.nextInt(rule.groupMax - rule.groupMin + 1)
            val count = minOf(wanted, remaining.getValue(rule))
            var spawned = 0
            for (member in 0 until count) {
                // The first is where the place was found; the rest stand near it, wherever they fit.
                val place = if (member == 0) spot else around(spot, rule, player) ?: continue
                if (spawnOne(rule, place, player)) spawned++
            }
            if (spawned > 0) remaining[rule] = remaining.getValue(rule) - spawned
            remaining.entries.removeAll { it.value <= 0 }
        }
    }

    private fun choose(fits: List<Rule>): Rule {
        val total = fits.sumOf { it.weight }
        var pick = random.nextInt(total)
        for (rule in fits) {
            pick -= rule.weight
            if (pick < 0) return rule
        }
        return fits.last()
    }

    /** One member of a group: asks `centity_natural_spawn`, then spawns. False when a handler refused it or it couldn't be. */
    private fun spawnOne(rule: Rule, spot: Spot, player: PlayerRef): Boolean {
        var at = Location(spot.world, spot.x + 0.5, spot.y.toDouble(), spot.z + 0.5)
        var yaw = random.nextDouble(-180.0, 180.0)
        if (scripts.listening(null, Events.NF_CENTITY_NATURAL_SPAWN)) {
            val event = CentityNaturalSpawnEvent(
                centity = nameOf(rule.id),
                location = LuaLocation(LuaHandle.World(at.world), Vec3(at.x, at.y, at.z), yaw),
                player = LuaHandle.Player(player.uuid.toString())
            )
            if (scripts.emit(Events.NF_CENTITY_NATURAL_SPAWN, null, event)) return false
            val moved = event.location
            val world = moved.world.name
            if (!platform.worlds.exists(world)) return false
            at = Location(world, moved.position.x, moved.position.y, moved.position.z)
            yaw = moved.yaw ?: yaw
        }
        return centities().spawn(rule.id, at, yaw, natural = true) != null
    }

    // ---- finding a place -----------------------------------------------------------

    /** A place a centity could stand: its feet block, what's under it, and what's known of both. */
    internal class Spot(
        val world: String,
        val x: Int,
        val y: Int,
        val z: Int,
        val feet: BlockSnapshot,
        val below: BlockSnapshot,
        val biome: String?
    ) {
        val standingOn: String? get() = BlockState.parse(below.state)?.id
    }

    /** A column [MIN_DISTANCE]..[MAX_DISTANCE] blocks from [where], looked down from around its height. Null when none was found. */
    private fun findSpot(where: Location): Spot? {
        val angle = random.nextDouble(0.0, Math.PI * 2)
        val distance = random.nextDouble(SpawningDef.MIN_DISTANCE.toDouble(), SpawningDef.MAX_DISTANCE.toDouble())
        val x = floor(where.x + cos(angle) * distance).toInt()
        val z = floor(where.z + sin(angle) * distance).toInt()
        return spotIn(where.world, x, z, floor(where.y).toInt() + random.nextInt(-HEIGHT_REACH, HEIGHT_REACH + 1))
    }

    /** A spot within a few blocks of [spot], for the rest of a group. */
    private fun around(spot: Spot, rule: Rule, player: PlayerRef): Spot? {
        repeat(GROUP_TRIES) {
            val x = spot.x + random.nextInt(-GROUP_RADIUS, GROUP_RADIUS + 1)
            val z = spot.z + random.nextInt(-GROUP_RADIUS, GROUP_RADIUS + 1)
            val found = spotIn(spot.world, x, z, spot.y + random.nextInt(-1, 2))
            if (found != null && rule.fits(found) && holds(rule, found, player)) return found
        }
        return null
    }

    /** What a world's sky is now: what time, moon and weather rules are asked. Null for a world that's gone. */
    internal class Sky(val timeOfDay: Int, val moonPhase: Int, val weather: SpawnWeather)

    private fun sky(world: String): Sky? {
        val time = platform.worlds.fullTime(world) ?: return null
        val moon = platform.worlds.moonPhase(world) ?: return null
        val weather = when (platform.worlds.weather(world)) {
            "thunder" -> SpawnWeather.THUNDER
            "rain" -> SpawnWeather.RAIN
            else -> SpawnWeather.CLEAR
        }
        return Sky(Math.floorMod(time, SpawnTime.DAY_TICKS.toLong()).toInt(), moon, weather)
    }

    /**
     * Whether [rule]'s `when` condition holds at [spot]: true without one. A condition no script has
     * registered is false (logged once per centity), and so is one that fails or answers anything but true.
     */
    private fun holds(rule: Rule, spot: Spot, player: PlayerRef): Boolean {
        val key = rule.condition ?: return true
        val condition = conditions[key]
        if (condition == null) {
            if (missing.add("${rule.id} $key")) {
                log.warn(
                    "Centity ${nameOf(rule.id)} spawns only when \"${key.substringAfter(':')}\" holds, and no script has registered " +
                        "that condition (nf.spawning.condition in the $key's package), so it doesn't appear"
                )
            }
            return false
        }
        val check = mapOf(
            "centity" to nameOf(rule.id),
            "location" to LuaLocation(LuaHandle.World(spot.world), Vec3(spot.x + 0.5, spot.y.toDouble(), spot.z + 0.5)),
            "player" to LuaHandle.Player(player.uuid.toString())
        )
        return when (val result = scripts.callKept<Any?>(condition.scope, condition.callback.ref, listOf(check), "spawn condition")) {
            is CallResult.Ok -> result.value == true
            is CallResult.Failed -> {
                scripts.handlerFailed(condition.scope, "spawn condition \"${condition.name}\"", result.failure, false)
                false
            }
            CallResult.Absent -> false
        }
    }

    /** Searches down from [from] in one column for air over something solid: null when that isn't within [SEARCH] blocks or its chunk isn't loaded. */
    private fun spotIn(world: String, x: Int, z: Int, from: Int): Spot? {
        val (low, high) = platform.worlds.heights(world) ?: return null
        val blocks = platform.blocks
        var y = from.coerceIn(low + 1, high - 2)
        var feet = blocks.get(world, x, y, z) ?: return null
        // From inside the ground, up to the surface; from the air, down onto it.
        var steps = 0
        while (steps < SEARCH && (feet.solid || feet.liquid) && y < high - 2) {
            y++
            feet = blocks.get(world, x, y, z) ?: return null
            steps++
        }
        if (feet.solid || feet.liquid) return null
        var below = blocks.get(world, x, y - 1, z) ?: return null
        while (steps < SEARCH && !below.solid && !below.liquid && y - 1 > low) {
            y--
            feet = below
            below = blocks.get(world, x, y - 1, z) ?: return null
            steps++
        }
        if (!below.solid || below.liquid) return null
        // Room for its head: a centity is taller than nothing, and not inside a block.
        val head = blocks.get(world, x, y + 1, z) ?: return null
        if (head.solid || head.liquid) return null
        return Spot(world, x, y, z, feet, below, platform.worlds.biome(world, x, y, z))
    }

    // ---- despawning --------------------------------------------------------------

    /** Removes natural centities with no player within their `despawnDistance`, in their world. */
    private fun despawn() {
        val naturals = centities().naturals()
        if (naturals.isEmpty()) return
        val players = platform.players.online().mapNotNull { platform.players.location(it.uuid) }
        for (instance in naturals) {
            val distance = instance.definition.spawning?.despawnDistanceOrDefault ?: SpawningDef.DEFAULT_DESPAWN_DISTANCE
            if (players.none { near(instance.anchor, it, distance) }) centities().remove(instance)
        }
    }

    private fun near(a: Location, b: Location, distance: Int): Boolean {
        if (a.world != b.world) return false
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return dx * dx + dy * dy + dz * dz <= distance.toDouble() * distance
    }

    // ---- the rules -------------------------------------------------------------------

    /** Every centity that has a `spawning` block, in id order, its rule resolved. */
    private fun ruleList(): List<Rule> {
        val centities = centities()
        val ids = centities.definitionIds()
        val kept = HashSet<String>()
        val out = ArrayList<Rule>()
        for (id in ids) {
            val definition = centities.definition(id) ?: continue
            val spawning = definition.spawning ?: continue
            kept += id
            val cached = rules[id]
            val rule = if (cached != null && cached.first === definition) {
                cached.second
            } else {
                Rule.of(id, spawning, platform.game, home()).also {
                    rules[id] = definition to it
                    missing.removeAll { entry -> entry.startsWith("$id ") }
                }
            }
            out += rule
        }
        rules.keys.retainAll(kept)
        return out
    }

    /** One centity's `spawning` block with its ids and tags resolved against the game: what a place is asked. */
    internal class Rule(
        val id: String,
        private val worlds: Set<String>?,
        private val biomes: Set<String>?,
        private val blocks: Set<String>?,
        private val lightMin: Int,
        private val lightMax: Int,
        private val heightMin: Int?,
        private val heightMax: Int?,
        val weight: Int,
        val groupMin: Int,
        val groupMax: Int,
        val cap: Int,
        val despawnDistance: Int,
        private val time: SpawnTime? = null,
        private val moonPhases: Set<Int>? = null,
        private val weather: Set<SpawnWeather>? = null,
        /** The `when` condition's full name (`<namespace>:<name>`), or null for none. */
        val condition: String? = null
    ) {
        fun allowsWorld(world: String) = worlds == null || world in worlds

        /** Whether the world's time, moon and weather allow it: one answer for every place in the world. */
        fun allowsSky(sky: Sky): Boolean {
            if (time != null && sky.timeOfDay !in time) return false
            if (moonPhases != null && sky.moonPhase !in moonPhases) return false
            // A thunderstorm is rain too.
            if (weather != null && sky.weather !in weather && !(sky.weather == SpawnWeather.THUNDER && SpawnWeather.RAIN in weather)) {
                return false
            }
            return true
        }

        fun fits(spot: Spot): Boolean {
            if (!allowsWorld(spot.world)) return false
            if (heightMin != null && spot.y < heightMin) return false
            if (heightMax != null && spot.y > heightMax) return false
            if (spot.feet.light !in lightMin..lightMax) return false
            if (biomes != null && spot.biome !in biomes) return false
            if (blocks != null && spot.standingOn !in blocks) return false
            return true
        }

        companion object {
            /** [home] is the project's namespace: a project biome is `<namespace>:<id>` on the server, as the place says. */
            fun of(id: String, def: SpawningDef, game: GameData, home: String) = Rule(
                id = id,
                worlds = def.worlds?.takeIf { it.isNotEmpty() }?.toSet(),
                biomes = expand(def.biomes?.map { BiomeKind.keyOf(it, home) ?: it }, RegistryKey.BIOME, game),
                blocks = expand(def.blocks, RegistryKey.BLOCK, game),
                lightMin = def.lightMin,
                lightMax = def.lightMax,
                heightMin = def.height?.min,
                heightMax = def.height?.max,
                weight = def.weightOrDefault,
                groupMin = def.groupMin,
                groupMax = def.groupMax,
                cap = def.capOrDefault,
                despawnDistance = def.despawnDistanceOrDefault,
                time = def.time,
                moonPhases = def.moonPhase?.takeIf { it.isNotEmpty() }?.map { it.ordinal }?.toSet(),
                weather = def.weather?.takeIf { it.isNotEmpty() }?.toSet(),
                // A bare name is the centity's own package's: the project's, or `acme`'s for `acme:wisp`.
                condition = def.condition?.let { name -> if (':' in name) name else "${id.substringBefore(':', home)}:$name" }
            )

            /** Ids and `#tag`s as the set of ids they name; null (anything) for no list. A tag the game doesn't have names nothing. */
            private fun expand(list: List<String>?, registry: RegistryKey, game: GameData): Set<String>? {
                if (list.isNullOrEmpty()) return null
                val out = HashSet<String>()
                for (entry in list) {
                    if (entry.startsWith("#")) {
                        game.tag(registry, GameIds.normalize(entry.removePrefix("#")))?.let { out += it }
                    } else {
                        out += GameIds.normalize(entry)
                    }
                }
                return out
            }
        }
    }

    companion object {
        /** Ticks between one player's passes, and between sweeps for natural centities nobody is near. */
        const val PASS_INTERVAL = 20L

        /** Most players whose pass runs in one tick. */
        const val PLAYERS_PER_TICK = 2

        /** Places looked at in one pass. */
        const val ATTEMPTS = 4

        /** How far above or below a player's own height a column is searched from, and how far it's searched. */
        private const val HEIGHT_REACH = 12
        private const val SEARCH = 24

        private const val GROUP_RADIUS = 3
        private const val GROUP_TRIES = 3
    }
}
