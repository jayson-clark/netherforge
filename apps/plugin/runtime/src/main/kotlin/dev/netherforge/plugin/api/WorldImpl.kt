package dev.netherforge.plugin.api

import dev.netherforge.format.block.BlockKind
import dev.netherforge.format.centity.CentityKind
import dev.netherforge.format.centity.MoonPhase
import dev.netherforge.format.game.BlockState
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.game.has
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.project.SpawnCategory
import dev.netherforge.format.sky.SkyKind
import dev.netherforge.plugin.async.WorkFailed
import dev.netherforge.plugin.binding.BiomeSearchOptions
import dev.netherforge.plugin.binding.BlockApi
import dev.netherforge.plugin.binding.BlockBox
import dev.netherforge.plugin.binding.BlockBoxOptions
import dev.netherforge.plugin.binding.BlockPosition
import dev.netherforge.plugin.binding.BlockSetOptions
import dev.netherforge.plugin.binding.Caller
import dev.netherforge.plugin.binding.CentityFilter
import dev.netherforge.plugin.binding.EntityFilter
import dev.netherforge.plugin.binding.EntitySpawnOptions
import dev.netherforge.plugin.binding.ExplosionOptions
import dev.netherforge.plugin.binding.FloodOptions
import dev.netherforge.plugin.binding.FloodResult
import dev.netherforge.plugin.binding.GradualFillOptions
import dev.netherforge.plugin.binding.GradualPlaceOptions
import dev.netherforge.plugin.binding.InventoryKeys
import dev.netherforge.plugin.binding.LightningOptions
import dev.netherforge.plugin.binding.LocationOrVec3
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.LuaLocation
import dev.netherforge.plugin.binding.NfWorldsApi
import dev.netherforge.plugin.binding.ParticleOptions
import dev.netherforge.plugin.binding.QueryHit
import dev.netherforge.plugin.binding.QueryOptions
import dev.netherforge.plugin.binding.RaycastHit
import dev.netherforge.plugin.binding.RaycastOptions
import dev.netherforge.plugin.binding.SkyOptions
import dev.netherforge.plugin.binding.SoundOptions
import dev.netherforge.plugin.binding.StringOrIntegerOrBoolean
import dev.netherforge.plugin.binding.StructurePlaceOptions
import dev.netherforge.plugin.binding.StructureSaveOptions
import dev.netherforge.plugin.binding.SweepOptions
import dev.netherforge.plugin.binding.Vec3OrChoice
import dev.netherforge.plugin.binding.WeatherOptions
import dev.netherforge.plugin.binding.WorldApi
import dev.netherforge.plugin.binding.WorldCreateOptions
import dev.netherforge.plugin.binding.WorldUnloadOptions
import dev.netherforge.plugin.binding.entityHandle
import dev.netherforge.plugin.binding.place
import dev.netherforge.plugin.centity.Found
import dev.netherforge.plugin.centity.QueryFilter
import dev.netherforge.plugin.centity.QueryShape
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaValue
import dev.netherforge.plugin.platform.BlockSnapshot
import dev.netherforge.plugin.platform.BlockVector
import dev.netherforge.plugin.platform.BoxState
import dev.netherforge.plugin.platform.GameRuleType
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.SpawnSetup
import dev.netherforge.plugin.platform.StructurePlacement
import dev.netherforge.plugin.session.ProjectSession
import dev.netherforge.plugin.world.Daylight
import dev.netherforge.plugin.world.Flood
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** How many blocks one `world:fill_blocks` may change: `FILL_LIMIT` in the spec (`spec/worlds.ts`). */
internal const val FILL_LIMIT = 32768

/** How many blocks one `world:fill_gradually` may change: `FILL_GRADUALLY_LIMIT` in the spec (`spec/worlds.ts`). */
internal const val FILL_GRADUALLY_LIMIT = 16777216L

/** How far `world:locate_biome` may look: `BIOME_SEARCH_LIMIT` in the spec (`spec/worlds.ts`). */
internal const val BIOME_SEARCH_LIMIT = 6400

/**
 * The workers' lane biome searches run on, one at a time: a search can take seconds, and
 * on a lane of their own a script asking for many can't hold up other work (a database, HTTP).
 */
private const val BIOME_SEARCH_LANE = "biome search"

/** A sky option's fade, in ticks: none below 0. */
internal fun fadeTicks(options: SkyOptions?): Int {
    val ticks = options?.fadeTicks ?: return 0
    if (ticks < 0) throw LuaApiException("options.fade_ticks can't be negative")
    return ticks.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/** The most blocks `world:blocks` reads in one call: 32 × 32 × 32, so one call can't stall a tick. */
private const val MAX_BOX = 32 * 32 * 32

/** `world:flood`'s limits: what it fills unless told, and the most it ever does. */
private const val FLOOD_DEFAULT = 4096L
private const val FLOOD_LIMIT = 32768L

/** The six neighbours that share a face: `world:flood`'s directions unless told. */
private val FLOOD_FACES = listOf(
    Vec3(1.0, 0.0, 0.0),
    Vec3(-1.0, 0.0, 0.0),
    Vec3(0.0, 1.0, 0.0),
    Vec3(0.0, -1.0, 0.0),
    Vec3(0.0, 0.0, 1.0),
    Vec3(0.0, 0.0, -1.0)
)

/** `nf.worlds`. */
internal class NfWorldsImpl(private val session: ProjectSession) : NfWorldsApi {
    private val worlds get() = session.platform.worlds

    override fun all(caller: Caller): List<LuaHandle.World> {
        val default = worlds.defaultWorld()
        return (listOf(default) + worlds.names().filter { it != default }).map { LuaHandle.World(it) }
    }

    override fun get(caller: Caller, name: String): LuaHandle.World? = name.takeIf { worlds.exists(it) }?.let { LuaHandle.World(it) }

    override fun default(caller: Caller): LuaHandle.World = LuaHandle.World(worlds.defaultWorld())

    override fun create(caller: Caller, name: String, options: WorldCreateOptions?): LuaHandle.World? = createWorld(session, name, options)

    override fun load(caller: Caller, name: String): LuaHandle.World? = loadWorld(session, name)

    override fun copy(caller: Caller, map: String, name: String): CompletionStage<LuaHandle.World> = session.managedWorlds.copy(map, name)
}

/**
 * `World`: a world by name. Everything answers nil or false once the world
 * has gone (unloaded); a mistake only the server can see (an unknown block
 * state, particle, sound or game rule) is a [LuaApiException].
 */
internal class WorldImpl(private val session: ProjectSession) : WorldApi {
    private val platform get() = session.platform
    private val worlds get() = platform.worlds

    /** The world's name, while the server has it. */
    private fun live(self: LuaHandle.World): String? = self.name.takeIf { worlds.exists(it) }

    override fun name(self: LuaHandle.World): String = self.name

    override fun exists(self: LuaHandle.World): Boolean = worlds.exists(self.name)

    override fun environment(self: LuaHandle.World): String? = live(self)?.let(worlds::environment)

    override fun timeOfDay(self: LuaHandle.World): Long? = live(self)?.let(worlds::fullTime)?.let { Math.floorMod(it, DAY) }

    override fun setTimeOfDay(self: LuaHandle.World, ticks: Long): Boolean {
        val world = live(self) ?: return false
        val full = worlds.fullTime(world) ?: return false
        return worlds.setFullTime(world, Math.floorDiv(full, DAY) * DAY + Math.floorMod(ticks, DAY))
    }

    override fun isNight(self: LuaHandle.World): Boolean =
        live(self)?.let(worlds::fullTime)?.let { Daylight.isNight(Daylight.timeOfDay(it)) } ?: false

    override fun isDay(self: LuaHandle.World): Boolean =
        live(self)?.let(worlds::fullTime)?.let { !Daylight.isNight(Daylight.timeOfDay(it)) } ?: false

    override fun dayCount(self: LuaHandle.World): Long? = live(self)?.let(worlds::fullTime)?.let { Math.floorDiv(it, DAY) }

    override fun weather(self: LuaHandle.World): String? = live(self)?.let(worlds::weather)

    override fun moonPhase(self: LuaHandle.World): String? =
        live(self)?.let(worlds::moonPhase)?.let { MoonPhase.entries.getOrNull(it) }?.let { CanonicalJson.serialName(it) }

    override fun setWeather(self: LuaHandle.World, weather: String, options: WeatherOptions?): Boolean {
        val ticks = options?.ticks
        if (ticks != null && ticks < 1) throw LuaApiException("ticks must be at least 1, not $ticks")
        val world = live(self) ?: return false
        return worlds.setWeather(world, weather, ticks?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt())
    }

    override fun spawnLocation(self: LuaHandle.World): LuaLocation? = live(self)?.let(worlds::spawnLocation)?.let(LuaLocation::of)

    override fun setSpawnLocation(self: LuaHandle.World, locationOrPosition: LocationOrVec3): Boolean {
        val place = locationOrPosition.place
        val target = place.world
        if (target != null && target.name != self.name) {
            throw LuaApiException("location_or_position is in ${target.name}, not ${self.name}")
        }
        val world = live(self) ?: return false
        return worlds.setSpawnLocation(world, place.resolve(world))
    }

    override fun minHeight(self: LuaHandle.World): Long? = live(self)?.let(worlds::heights)?.first?.toLong()

    override fun maxHeight(self: LuaHandle.World): Long? = live(self)?.let(worlds::heights)?.second?.toLong()

    override fun block(self: LuaHandle.World, position: Vec3): LuaHandle.Block? {
        val at = BlockPosition.of(position)
        return live(self)?.let { LuaHandle.Block(it, at.packed) }
    }

    override fun sky(self: LuaHandle.World): String? = live(self)?.let(session.skies::ofWorld)?.let(session.names::spell)

    override fun setSky(self: LuaHandle.World, sky: String, options: SkyOptions?): Boolean {
        val name = session.names.resource(SkyKind, sky)
        val world = live(self) ?: return false
        session.skies.setWorld(world, name, fadeTicks(options))
        return true
    }

    override fun clearSky(self: LuaHandle.World, options: SkyOptions?) {
        val world = live(self) ?: return
        session.skies.clearWorld(world, fadeTicks(options))
    }

    override fun blocks(self: LuaHandle.World, from: Vec3, to: Vec3, options: BlockBoxOptions?): BlockBox? {
        val a = BlockPosition.of(from)
        val b = BlockPosition.of(to)
        val x0 = minOf(a.x, b.x)
        val y0 = minOf(a.y, b.y)
        val z0 = minOf(a.z, b.z)
        val sizeX = maxOf(a.x, b.x) - x0 + 1
        val sizeY = maxOf(a.y, b.y) - y0 + 1
        val sizeZ = maxOf(a.z, b.z) - z0 + 1
        val volume = sizeX.toLong() * sizeY * sizeZ
        if (volume > MAX_BOX) {
            throw LuaApiException("a box of $sizeX × $sizeY × $sizeZ is $volume blocks, more than the $MAX_BOX world:blocks reads at once")
        }
        val world = live(self) ?: return null
        val read = session.platform.blocks.box(world, x0, y0, z0, x0 + sizeX - 1, y0 + sizeY - 1, z0 + sizeZ - 1) ?: return null
        // Each state's value is made once and every block of it shares it.
        val values = read.states.map { state ->
            when (options?.what ?: "kind") {
                "solid" -> StringOrIntegerOrBoolean.Boolean(state.solid)
                "class" -> StringOrIntegerOrBoolean.Integer(
                    if (state.liquid) {
                        1
                    } else if (state.solid) {
                        2
                    } else {
                        0
                    }
                )
                else -> StringOrIntegerOrBoolean.String(state.state.substringBefore('['))
            }
        }
        val cells = ArrayList<StringOrIntegerOrBoolean>(read.cells.size)
        for (index in read.cells) cells += values[index]
        if (options?.custom == true) {
            // Only the states the project's blocks are held in can be one: those blocks are asked which they are.
            val custom = session.customBlocks
            val carriers = BooleanArray(read.states.size) { custom.nameOfState(read.states[it].state) != null }
            for ((i, index) in read.cells.withIndex()) {
                if (!carriers[index]) continue
                val x = x0 + i % sizeX
                val z = z0 + (i / sizeX) % sizeZ
                val y = y0 + i / (sizeX * sizeZ)
                val name = custom.at(world, x, y, z) ?: continue
                cells[i] = StringOrIntegerOrBoolean.String(session.names.spell(name))
            }
        }
        return BlockBox(cells, sizeX.toLong(), sizeY.toLong(), sizeZ.toLong(), Vec3(x0.toDouble(), y0.toDouble(), z0.toDouble()))
    }

    override fun flood(self: LuaHandle.World, start: Vec3, options: FloodOptions): FloodResult? {
        val limit = options.limit ?: FLOOD_DEFAULT
        if (limit < 1 || limit > FLOOD_LIMIT) throw LuaApiException("bad argument 'options.limit' (1 to $FLOOD_LIMIT expected, got $limit)")
        val directions = (options.directions ?: FLOOD_FACES).mapIndexed { i, d ->
            val whole = listOf(d.x, d.y, d.z)
            if (whole.any { it != Math.rint(it) || it < -2 || it > 2 } || whole.all { it == 0.0 }) {
                throw LuaApiException(
                    "bad argument 'options.directions[${i + 1}]' (whole numbers from -2 to 2, not all 0, expected, got $d)"
                )
            }
            intArrayOf(d.x.toInt(), d.y.toInt(), d.z.toInt())
        }
        if (options.through.isEmpty()) throw LuaApiException("bad argument 'options.through' (at least one block expected)")
        val through = blockMatcher(options.through, "options.through")
        val except = options.except?.let { blockMatcher(it, "options.except") }
        val bounds = options.bounds?.let { b ->
            val lo = BlockPosition.of(Vec3(minOf(b.min.x, b.max.x), minOf(b.min.y, b.max.y), minOf(b.min.z, b.max.z)))
            val hi = BlockPosition.of(Vec3(maxOf(b.min.x, b.max.x), maxOf(b.min.y, b.max.y), maxOf(b.min.z, b.max.z)))
            intArrayOf(lo.x, lo.y, lo.z, hi.x, hi.y, hi.z)
        }
        val world = live(self) ?: return null
        val (bottom, top) = worlds.heights(world) ?: return null
        val custom = session.customBlocks
        val flood = Flood(
            platform.blocks,
            world,
            bottom,
            top,
            { state -> through(state) && except?.invoke(state) != true },
            { state -> custom.nameOfState(state.state)?.let(session.names::spell) ?: state.state.substringBefore('[') }
        )
        val at = BlockPosition.of(start)
        val result = flood.run(at.x, at.y, at.z, directions, limit.toInt(), bounds)
        fun positions(packed: LongArray) = packed.map {
            Vec3(Flood.unpackX(it).toDouble(), Flood.unpackY(it).toDouble(), Flood.unpackZ(it).toDouble())
        }
        val kinds = options.kinds == true
        return FloodResult(
            positions = positions(result.filled),
            edges = positions(result.edges),
            kinds = if (kinds) result.filledStates.map(flood::kind) else null,
            edgeKinds = if (kinds) result.edgeStates.map(flood::kind) else null,
            stopped = result.stopped?.luaName
        )
    }

    /**
     * A test of a block's state for each of [names], as a game block file names blocks: an id, a `#tag`, a
     * project block, or `solid`, `liquid` or `passable`. One that names nothing the server or the project has is
     * an error at [where].
     */
    private fun blockMatcher(names: List<String>, where: String): (BoxState) -> Boolean {
        val ids = HashSet<String>()
        val projectBlocks = HashSet<String>()
        var solid = false
        var liquid = false
        var passable = false
        for ((i, name) in names.withIndex()) {
            val at = "$where[${i + 1}]"
            when {
                name == "solid" -> solid = true
                name == "liquid" -> liquid = true
                name == "passable" -> passable = true
                name.startsWith("#") -> {
                    val tag = name.removePrefix("#")
                    val members = if (GameIds.isValid(tag)) platform.game.tag(RegistryKey.BLOCK, GameIds.normalize(tag)) else null
                    ids += members ?: throw LuaApiException("bad argument '$at' (\"$name\" isn't a block tag the server has)")
                }
                else -> {
                    val project = runCatching { session.names.resource(BlockKind, name) }.getOrNull()?.takeIf(session.customBlocks::has)
                    when {
                        project != null -> projectBlocks += project
                        GameIds.isValid(name) && platform.game.block(GameIds.normalize(name)) != null -> ids += GameIds.normalize(name)
                        else -> throw LuaApiException("bad argument '$at' (\"$name\" isn't a block, a #tag, solid, liquid or passable)")
                    }
                }
            }
        }
        val custom = session.customBlocks
        return { state ->
            (solid && state.solid) ||
                (liquid && state.liquid) ||
                (passable && !state.solid && !state.liquid) ||
                (ids.isNotEmpty() && state.state.substringBefore('[') in ids) ||
                (projectBlocks.isNotEmpty() && custom.nameOfState(state.state) in projectBlocks)
        }
    }

    override fun setBlock(self: LuaHandle.World, position: Vec3, state: String, options: BlockSetOptions?): Boolean {
        val at = BlockPosition.of(position)
        val canonical = session.effects.blockState(state, "state")
        val world = live(self) ?: return false
        return platform.blocks.set(world, at.x, at.y, at.z, canonical, options?.update != false).also {
            if (it) session.customBlocks.replaced(world, at.x, at.y, at.z, canonical)
        }
    }

    override fun fillBlocks(self: LuaHandle.World, from: Vec3, to: Vec3, state: String, options: BlockSetOptions?): Long {
        val a = BlockPosition.of(from)
        val b = BlockPosition.of(to)
        val canonical = session.effects.blockState(state, "state")
        val xs = min(a.x, b.x)..max(a.x, b.x)
        val ys = min(a.y, b.y)..max(a.y, b.y)
        val zs = min(a.z, b.z)..max(a.z, b.z)
        val volume = xs.count().toLong() * ys.count() * zs.count()
        if (volume > FILL_LIMIT) {
            throw LuaApiException(
                "that box is $volume blocks, and one fill_blocks changes at most $FILL_LIMIT: spread it over several ticks"
            )
        }
        val world = live(self) ?: return 0
        val update = options?.update != false
        val (bottom, top) = worlds.heights(world) ?: return 0
        var changed = 0L
        for (x in xs) {
            for (z in zs) {
                if (!worlds.isChunkLoaded(world, x shr 4, z shr 4)) continue
                for (y in ys) {
                    if (y < bottom || y >= top) continue
                    if (platform.blocks.set(world, x, y, z, canonical, update)) {
                        changed++
                        session.customBlocks.replaced(world, x, y, z, canonical)
                    }
                }
            }
        }
        return changed
    }

    override fun highestBlock(self: LuaHandle.World, position: Vec3): LuaHandle.Block? {
        val at = BlockPosition.of(position.copy(y = 0.0))
        val world = live(self) ?: return null
        val y = worlds.highestBlockY(world, at.x, at.z) ?: return null
        return LuaHandle.Block(world, BlockPosition.pack(at.x, y, at.z))
    }

    override fun isChunkLoaded(self: LuaHandle.World, position: Vec3): Boolean {
        val at = BlockPosition.of(position.copy(y = 0.0))
        val world = live(self) ?: return false
        return worlds.isChunkLoaded(world, at.x shr 4, at.z shr 4)
    }

    override fun loadChunk(self: LuaHandle.World, position: Vec3): Boolean {
        val at = BlockPosition.of(position.copy(y = 0.0))
        val world = live(self) ?: return false
        return worlds.loadChunk(world, at.x shr 4, at.z shr 4)
    }

    override fun locateBiome(self: LuaHandle.World, id: String, options: BiomeSearchOptions?): CompletionStage<Vec3> {
        val biome = biomeId(id)
        val radius = options?.radius ?: BIOME_SEARCH_LIMIT.toLong()
        if (radius !in 1..BIOME_SEARCH_LIMIT) throw LuaApiException("options.radius must be 1 to $BIOME_SEARCH_LIMIT, not $radius")
        val near = options?.near ?: live(self)?.let(worlds::spawnLocation)?.let { Vec3(it.x, it.y, it.z) }
        val search = near?.let {
            worlds.biomeSearch(self.name, floor(it.x).toInt(), floor(it.y).toInt(), floor(it.z).toInt(), radius.toInt(), biome)
        } ?: return CompletableFuture.failedFuture(WorkFailed("world \"${self.name}\" has gone"))
        return session.async.submit(session.async.workers.lane(BIOME_SEARCH_LANE)) {
            search.run() ?: throw WorkFailed("no $biome within $radius blocks")
        }
    }

    /** A biome a script names, as the server keys it (a bare id is the project's), once it's known to be one of the server's. */
    private fun biomeId(text: String): String {
        val id = session.names.biome(text)
        if (platform.game.has(RegistryKey.BIOME, id) == false) {
            throw LuaApiException(
                if (':' in text) {
                    "no biome \"$text\" on this server"
                } else {
                    "no biome \"$text\" in the project (a project biome is biomes/$text.json); the game's are written in full, \"minecraft:$text\""
                }
            )
        }
        return id
    }

    override fun players(self: LuaHandle.World): List<LuaHandle.Player> =
        platform.players.online().filter { platform.players.location(it.uuid)?.world == self.name }.map(::playerHandle)

    override fun centities(self: LuaHandle.World, filter: CentityFilter?): List<LuaHandle.Centity> {
        val world = filter?.world
        if (world != null && world != self) throw LuaApiException("filter.world is ${world.name}, not ${self.name}")
        return centitiesMatching(session, (filter ?: CentityFilter()).copy(world = self))
    }

    override fun spawnParticle(self: LuaHandle.World, particle: String, position: Vec3, options: ParticleOptions?): Boolean =
        session.effects.spawnParticle(self.name, particle, position, options)

    override fun playSound(self: LuaHandle.World, sound: String, position: Vec3, options: SoundOptions?): Boolean {
        val play = session.effects.sound(sound, options)
        val world = live(self) ?: return false
        return platform.sounds.play(world, position, play)
    }

    override fun explode(self: LuaHandle.World, position: Vec3, power: Double, options: ExplosionOptions?): Boolean {
        if (power < 0 || !power.isFinite()) throw LuaApiException("power can't be below 0")
        val world = live(self) ?: return false
        return worlds.explode(world, position, power, options?.fire == true, options?.breakBlocks != false)
    }

    override fun strikeLightning(self: LuaHandle.World, position: Vec3, options: LightningOptions?): Boolean {
        val world = live(self) ?: return false
        return worlds.strikeLightning(world, position, options?.effectOnly == true)
    }

    override fun raycast(self: LuaHandle.World, from: Vec3, direction: Vec3, maxDistance: Double, options: RaycastOptions?): RaycastHit? {
        val length = sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z)
        if (length == 0.0 || !length.isFinite()) throw LuaApiException("direction can't be zero")
        if (maxDistance < 0 || !maxDistance.isFinite()) throw LuaApiException("max_distance can't be below 0")
        val world = live(self) ?: return null
        val unit = Vec3(direction.x / length, direction.y / length, direction.z / length)
        return session.raycast(world, from, unit, maxDistance, options ?: RaycastOptions())
    }

    override fun overlapBox(self: LuaHandle.World, min: Vec3, max: Vec3, options: QueryOptions?): List<QueryHit> {
        finite(min, "min")
        finite(max, "max")
        val shape = QueryShape.Aabb(
            Vec3(min(min.x, max.x), min(min.y, max.y), min(min.z, max.z)),
            Vec3(max(min.x, max.x), max(min.y, max.y), max(min.z, max.z))
        )
        return session.query(self, shape, options?.let(session::queryFilter))
    }

    override fun overlapSphere(self: LuaHandle.World, center: Vec3, radius: Double, options: QueryOptions?): List<QueryHit> {
        finite(center, "center")
        nonNegative(radius, "radius")
        return session.query(self, QueryShape.Sphere(center, radius), options?.let(session::queryFilter))
    }

    override fun overlapCone(
        self: LuaHandle.World,
        origin: Vec3,
        direction: Vec3,
        length: Double,
        angle: Double,
        options: QueryOptions?
    ): List<QueryHit> {
        finite(origin, "origin")
        val axis = unit(direction, "direction")
        nonNegative(length, "length")
        if (!(angle in 0.0..180.0)) throw LuaApiException("bad argument 'angle' (0 to 180 expected, got $angle)")
        return session.query(self, QueryShape.Cone(origin, axis, length, angle), options?.let(session::queryFilter))
    }

    override fun sweep(self: LuaHandle.World, from: Vec3, to: Vec3, options: SweepOptions?): List<QueryHit> {
        finite(from, "from")
        finite(to, "to")
        val radius = options?.radius ?: 0.0
        nonNegative(radius, "options.radius")
        val filter = options?.let {
            session.queryFilter(QueryOptions(it.centities, it.players, it.entities, it.kinds, it.exclude, it.limit, it.lineOfSight))
        }
        val world = live(self) ?: return emptyList()
        val span = to - from
        val length = QueryShape.length(span)
        // A block stops the sphere's middle: nothing past it counts, and it's the last hit.
        val block = if (options?.blocks != false && length > 1e-9) {
            worlds.raycastBlocks(world, from, span * (1 / length), length, options?.fluids == true)
        } else {
            null
        }
        val end = block?.position ?: to
        val hits = session.query(self, QueryShape.Capsule(from, end, radius), filter).toMutableList()
        if (block != null && hits.size < (filter?.limit ?: Int.MAX_VALUE)) {
            hits += QueryHit(
                block = LuaHandle.Block(world, BlockPosition.pack(block.x, block.y, block.z)),
                position = block.position,
                distance = QueryShape.length(block.position - from),
                normal = block.normal
            )
        }
        return hits
    }

    override fun nearest(self: LuaHandle.World, position: Vec3, radius: Double, options: QueryOptions?): QueryHit? {
        finite(position, "position")
        nonNegative(radius, "radius")
        val filter = (options?.let(session::queryFilter) ?: QueryFilter()).copy(limit = 1)
        return session.query(self, QueryShape.Sphere(position, radius), filter).firstOrNull()
    }

    override fun entities(self: LuaHandle.World, filter: EntityFilter?): List<LuaHandle.Entity> {
        val near = filter?.near
        val radius = filter?.radius
        if ((near == null) != (radius == null)) throw LuaApiException("filter.near and filter.radius go together")
        val kind = filter?.kind?.let(GameIds::normalize)
        return platform.worldEntities.list(self.name)
            .filter { info ->
                val at = info.location
                (kind == null || info.kind == kind) &&
                    (filter?.tag == null || filter.tag in info.tags) &&
                    (filter?.living == null || info.living == filter.living) &&
                    (near == null || distance(Vec3(at.x, at.y, at.z), near) <= radius!!)
            }
            .map { session.entityHandle(it) }
    }

    override fun spawnEntity(
        self: LuaHandle.World,
        kind: String,
        locationOrPosition: LocationOrVec3,
        options: EntitySpawnOptions?
    ): LuaHandle.Entity? {
        val id = GameIds.normalize(kind)
        if (!GameIds.isValid(kind) || !platform.worldEntities.spawnable(id)) {
            throw LuaApiException("\"$kind\" isn't an entity this server can spawn")
        }
        val place = locationOrPosition.place
        val target = place.world
        if (target != null && target.name != self.name) {
            throw LuaApiException("location_or_position is in ${target.name}, not ${self.name}")
        }
        val tags = options?.tags.orEmpty()
        for (tag in tags) if (!TAG.matches(tag)) throw LuaApiException("\"$tag\" isn't a tag: letters, digits, _, -, . and +")
        val data = options?.data?.let { value ->
            val encoded = value.data("options.data")
            encoded.problems.firstOrNull()?.let { throw LuaApiException(it) }
            encoded.text?.takeIf { it.startsWith("{") }
                ?: throw LuaApiException("options.data must be a table of your own values, like { guard = true }")
        }?.takeIf { it != "{}" }
        val world = live(self) ?: return null
        val at = place.resolve(world)
        val setup = SpawnSetup(options?.customName, tags, data, options?.velocity)
        val entity = platform.worldEntities.spawn(id, at, setup) ?: return null
        return session.entityHandle(entity)
    }

    override fun spawnItem(self: LuaHandle.World, position: Vec3, item: ItemData): LuaHandle.DroppedItem? {
        val world = live(self) ?: return null
        val entity = platform.worldEntities.spawnItem(world, position, item) ?: return null
        return LuaHandle.DroppedItem(entity.toString())
    }

    override fun gameRule(self: LuaHandle.World, rule: String): Any? {
        val id = gameRuleId(rule)
        val world = live(self) ?: return null
        return when (val value = worlds.gameRule(world, id)) {
            is Int -> value.toLong()
            else -> value
        }
    }

    override fun setGameRule(self: LuaHandle.World, rule: String, value: LuaValue): Boolean {
        val id = gameRuleId(rule)
        val json = value.json() as? JsonPrimitive
        val typed: Any = when (worlds.gameRuleType(id)!!) {
            GameRuleType.BOOLEAN -> json?.takeIf { !it.isString }?.booleanOrNull
                ?: throw LuaApiException("game rule $rule takes a boolean")
            GameRuleType.INTEGER -> json?.takeIf { !it.isString }?.doubleOrNull?.takeIf {
                it == floor(it) &&
                    it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()
            }?.toInt()
                ?: throw LuaApiException("game rule $rule takes a whole number")
        }
        val world = live(self) ?: return false
        return worlds.setGameRule(world, id, typed)
    }

    override fun spawnLimit(self: LuaHandle.World, category: String): Long? =
        live(self)?.let { worlds.spawnLimit(it, spawnCategory(category)) }?.toLong()

    override fun setSpawnLimit(self: LuaHandle.World, category: String, limit: Long): Boolean {
        val world = live(self) ?: return false
        return worlds.setSpawnLimit(world, spawnCategory(category), limit.coerceIn(-1, Int.MAX_VALUE.toLong()).toInt())
    }

    override fun spawnInterval(self: LuaHandle.World, category: String): Long? =
        live(self)?.let { worlds.spawnInterval(it, spawnCategory(category)) }?.toLong()

    override fun setSpawnInterval(self: LuaHandle.World, category: String, ticks: Long): Boolean {
        val world = live(self) ?: return false
        return worlds.setSpawnInterval(world, spawnCategory(category), ticks.coerceIn(-1, Int.MAX_VALUE.toLong()).toInt())
    }

    /** The category a script named, already checked against the spec's choices. */
    private fun spawnCategory(id: String) = SpawnCategory.entries.first { it.id == id }

    override fun isManaged(self: LuaHandle.World): Boolean = session.managedWorlds.isManaged(self.name)

    override fun unload(self: LuaHandle.World, options: WorldUnloadOptions?): Boolean = session.managedWorlds.unload(
        self.name,
        save = options?.save != false,
        delete = options?.delete == true,
        moveTo = options?.movePlayersTo?.name
    )

    override fun border(self: LuaHandle.World): LuaHandle.WorldBorder? =
        live(self)?.let { LuaHandle.WorldBorder(WorldBorderImpl.WORLD, it) }

    override fun saveStructure(self: LuaHandle.World, id: String, from: Vec3, to: Vec3, options: StructureSaveOptions?): Boolean {
        val a = BlockPosition.of(from)
        val b = BlockPosition.of(to)
        return session.structures.save(id, self.name, a.vector(), b.vector(), options?.entities == true)
    }

    override fun placeStructure(self: LuaHandle.World, id: String, position: Vec3, options: StructurePlaceOptions?): Boolean =
        session.structures.place(
            id,
            self.name,
            BlockPosition.of(position).vector(),
            rotation = options?.rotation ?: 0,
            mirror = options?.mirror ?: "none",
            integrity = options?.integrity ?: 1.0,
            entities = options?.entities != false
        )

    override fun placeGradually(self: LuaHandle.World, id: String, position: Vec3, options: GradualPlaceOptions?): CompletionStage<Long> {
        val rotation = options?.rotation ?: 0
        val integrity = options?.integrity ?: 1.0
        if (rotation % 90 != 0L) throw LuaApiException("rotation must be a multiple of 90, not $rotation")
        if (integrity !in 0.0..1.0) throw LuaApiException("integrity must be between 0 and 1, not $integrity")
        val perTick = perTick(options?.perTick)
        val pieces = session.structures.pieces(id)
        val scope = callingScope("World:place_gradually")
        if (!worlds.exists(self.name)) return CompletableFuture.failedFuture(WorkFailed("world \"${self.name}\" has gone"))
        val placement = StructurePlacement(
            Math.floorMod(rotation, 360L).toInt(),
            options?.mirror ?: "none",
            integrity,
            options?.entities != false
        )
        return session.gradual.place(scope, self.name, pieces, BlockPosition.of(position).vector(), placement, perTick)
    }

    override fun fillGradually(
        self: LuaHandle.World,
        from: Vec3,
        to: Vec3,
        state: String,
        options: GradualFillOptions?
    ): CompletionStage<Long> {
        val a = BlockPosition.of(from)
        val b = BlockPosition.of(to)
        val canonical = session.effects.blockState(state, "state")
        val volume = (abs(a.x - b.x) + 1L) * (abs(a.y - b.y) + 1L) * (abs(a.z - b.z) + 1L)
        if (volume > FILL_GRADUALLY_LIMIT) {
            throw LuaApiException("that box is $volume blocks, and one fill_gradually changes at most $FILL_GRADUALLY_LIMIT")
        }
        val perTick = perTick(options?.perTick)
        val scope = callingScope("World:fill_gradually")
        if (!worlds.exists(self.name)) return CompletableFuture.failedFuture(WorkFailed("world \"${self.name}\" has gone"))
        return session.gradual.fill(
            scope,
            self.name,
            BlockVector(min(a.x, b.x), min(a.y, b.y), min(a.z, b.z)),
            BlockVector(max(a.x, b.x), max(a.y, b.y), max(a.z, b.z)),
            canonical,
            options?.update != false,
            perTick
        )
    }

    /** A gradual job's own `per_tick`, or null for its share. */
    private fun perTick(value: Long?): Int? {
        if (value == null) return null
        if (value < 1) throw LuaApiException("options.per_tick must be 1 or more, not $value")
        return value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** The scope whose code called [what]: the binding names it before the call (`marshal.caller`). */
    private fun callingScope(what: String) = session.scripts.host?.callerScope?.let(session.scripts::scope)
        ?: throw LuaApiException("$what can only be called by a script")

    private fun BlockPosition.vector() = BlockVector(x, y, z)

    /** A game rule's namespaced id, once the server is known to have it. */
    private fun gameRuleId(rule: String): String {
        val id = GameIds.normalize(rule)
        if (!GameIds.isValid(rule) || worlds.gameRuleType(id) == null) throw LuaApiException("no game rule \"$rule\" on this server")
        return id
    }

    private companion object {
        const val DAY = 24000L
        val TAG = Regex("[A-Za-z0-9_.+-]+")
    }
}

private fun distance(a: Vec3, b: Vec3): Double {
    val dx = a.x - b.x
    val dy = a.y - b.y
    val dz = a.z - b.z
    return sqrt(dx * dx + dy * dy + dz * dz)
}

/** A query's options in the runtime's terms: kinds as the project names centities and the game entity types. */
internal fun ProjectSession.queryFilter(options: QueryOptions): QueryFilter {
    val limit = options.limit
    if (limit != null && limit < 1) throw LuaApiException("bad argument 'options.limit' (at least 1 expected, got $limit)")
    val kinds = options.kinds?.flatMapTo(HashSet()) { kind ->
        buildList {
            if (GameIds.isValid(kind)) add(GameIds.normalize(kind))
            runCatching { names.resource(CentityKind, kind) }.getOrNull()?.let(::add)
        }
    }
    val exclude = options.exclude.orEmpty()
    return QueryFilter(
        centities = options.centities != false,
        players = options.players != false,
        entities = options.entities != false,
        kinds = kinds,
        excludeCentities = exclude.filterIsInstance<LuaHandle.Centity>().mapNotNullTo(HashSet()) {
            runCatching { UUID.fromString(it.id) }.getOrNull()
        },
        excludeEntities = exclude.filterIsInstance<LuaHandle.Entity>().mapNotNullTo(HashSet()) { it.uuidOrNull() },
        lineOfSight = options.lineOfSight == true,
        limit = limit?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: Int.MAX_VALUE
    )
}

/** What a spatial query in [self] finds as
 `world:overlap_*`, `sweep` and `nearest` hand it back; empty once the world has gone. */
internal fun ProjectSession.query(self: LuaHandle.World, shape: QueryShape, filter: QueryFilter?): List<QueryHit> {
    if (!platform.worlds.exists(self.name)) return emptyList()
    return centities.spatial.find(self.name, shape, filter ?: QueryFilter()).map { found ->
        when (found) {
            is Found.Centity -> {
                val id = found.instance.id.toString()
                QueryHit(
                    centity = LuaHandle.Centity(id),
                    node = LuaHandle.Node(id, found.instance.definition.nodes[found.node].name),
                    position = found.reach.position,
                    distance = found.reach.distance
                )
            }
            is Found.Entity -> QueryHit(entity = entityHandle(found.id), position = found.reach.position, distance = found.reach.distance)
        }
    }
}

private fun finite(v: Vec3, name: String) {
    if (!v.x.isFinite() || !v.y.isFinite() || !v.z.isFinite()) throw LuaApiException("bad argument '$name' (a finite position expected)")
}

private fun nonNegative(value: Double, name: String) {
    if (!(value >= 0) || !value.isFinite()) throw LuaApiException("bad argument '$name' (at least 0 expected, got $value)")
}

private fun unit(v: Vec3, name: String): Vec3 {
    val length = QueryShape.length(v)
    if (length == 0.0 || !length.isFinite()) throw LuaApiException("bad argument '$name' (it can't be zero)")
    return v * (1 / length)
}

/**
 * What a ray from [from] along the unit [direction] hits first within
 * [maxDistance], as `options` allow: a block, an entity (players included,
 * leaving out `ignore`) or a centity's hitbox. What `world:raycast` and an
 * entity's `target_*` answer.
 */
internal fun ProjectSession.raycast(world: String, from: Vec3, direction: Vec3, maxDistance: Double, options: RaycastOptions): RaycastHit? {
    var reach = maxDistance
    var best: RaycastHit? = null
    if (options.blocks != false) {
        platform.worlds.raycastBlocks(world, from, direction, maxDistance, options.fluids == true)?.let { block ->
            reach = distance(from, block.position)
            best = RaycastHit(
                position = block.position,
                normal = block.normal,
                distance = reach,
                block = LuaHandle.Block(world, BlockPosition.pack(block.x, block.y, block.z))
            )
        }
    }
    if (options.entities != false) {
        val ignore = options.ignore.orEmpty().mapNotNull { it.uuidOrNull() }.toSet()
        platform.worldEntities.raycast(world, from, direction, reach, ignore)?.let { hit ->
            val at = distance(from, hit.position)
            if (at < reach) {
                reach = at
                best = RaycastHit(position = hit.position, normal = hit.normal, distance = at, entity = entityHandle(hit.id))
            }
        }
    }
    if (options.centities != false) {
        centities.raycast(world, from, direction, reach)?.let { centity ->
            if (centity.distance < reach) {
                val id = centity.instance.id.toString()
                best = RaycastHit(
                    position = centity.position,
                    normal = centity.normal,
                    distance = centity.distance,
                    centity = LuaHandle.Centity(id),
                    node = LuaHandle.Node(id, centity.instance.definition.nodes[centity.index].name)
                )
            }
        }
    }
    return best
}

/**
 * `Block`: a position in a world, read live. Reads answer nil or false in a
 * chunk that isn't loaded (nothing here loads one); a block state the server
 * doesn't have is a [LuaApiException].
 */
internal class BlockImpl(private val session: ProjectSession) : BlockApi {
    private val blocks get() = session.platform.blocks

    private fun at(self: LuaHandle.Block) = BlockPosition.unpack(self.position)

    /** A block position is packed, as Minecraft packs one; a face is the neighbour it names. */
    override fun relative(self: LuaHandle.Block, offset: Vec3OrChoice): LuaHandle.Block {
        val at = at(self)
        val (dx, dy, dz) = when (offset) {
            is Vec3OrChoice.Vec3 -> Triple(floor(offset.value.x).toInt(), floor(offset.value.y).toInt(), floor(offset.value.z).toInt())
            is Vec3OrChoice.Choice -> FACES.getValue(offset.value)
        }
        return LuaHandle.Block(self.world, BlockPosition.pack(at.x + dx, at.y + dy, at.z + dz))
    }

    private fun snapshot(self: LuaHandle.Block): BlockSnapshot? {
        val at = at(self)
        return blocks.get(self.world, at.x, at.y, at.z)
    }

    private fun parsed(self: LuaHandle.Block): BlockState? = snapshot(self)?.let { BlockState.parse(it.state) }

    override fun world(self: LuaHandle.Block): LuaHandle.World = LuaHandle.World(self.world)

    override fun position(self: LuaHandle.Block): Vec3 = at(self).corner()

    override fun location(self: LuaHandle.Block): LuaLocation = LuaLocation(LuaHandle.World(self.world), at(self).corner())

    override fun kind(self: LuaHandle.Block): String? = parsed(self)?.id

    override fun state(self: LuaHandle.Block): String? = parsed(self)?.toString()

    override fun property(self: LuaHandle.Block, name: String): String? = parsed(self)?.properties?.get(name)

    override fun properties(self: LuaHandle.Block): Map<String, String>? = parsed(self)?.properties

    override fun isAir(self: LuaHandle.Block): Boolean = snapshot(self)?.air == true

    override fun isSolid(self: LuaHandle.Block): Boolean = snapshot(self)?.solid == true

    override fun isLiquid(self: LuaHandle.Block): Boolean = snapshot(self)?.liquid == true

    override fun setState(self: LuaHandle.Block, state: String, options: BlockSetOptions?): Boolean {
        val canonical = session.effects.blockState(state, "state")
        val at = at(self)
        return blocks.set(self.world, at.x, at.y, at.z, canonical, options?.update != false).also {
            if (it) session.customBlocks.replaced(self.world, at.x, at.y, at.z, canonical)
        }
    }

    override fun setProperty(self: LuaHandle.Block, name: String, value: String): Boolean {
        val current = parsed(self) ?: return false
        val values = session.platform.game.block(current.id)?.properties?.get(name)
            ?: throw LuaApiException("${current.id} has no property \"$name\"")
        if (value !in values) throw LuaApiException("${current.id}'s $name can't be \"$value\" (it can be ${values.joinToString(", ")})")
        val at = at(self)
        val state = BlockState(current.id, current.properties + (name to value)).toString()
        return blocks.set(self.world, at.x, at.y, at.z, state, true).also {
            if (it) session.customBlocks.replaced(self.world, at.x, at.y, at.z, state)
        }
    }

    override fun breakNaturally(self: LuaHandle.Block, tool: ItemData?): Boolean {
        val at = at(self)
        // One of the project's blocks drops what its loot table says, with its sound and its particles.
        if (session.customBlocks.breakNaturally(self.world, at.x, at.y, at.z, tool)) return true
        return blocks.breakNaturally(self.world, at.x, at.y, at.z, tool)
    }

    override fun custom(self: LuaHandle.Block): LuaHandle.CustomBlock? {
        val at = at(self)
        session.customBlocks.at(self.world, at.x, at.y, at.z) ?: return null
        return LuaHandle.CustomBlock(self.world, self.position)
    }

    override fun inventory(self: LuaHandle.Block): LuaHandle.Inventory? {
        val at = at(self)
        val ref = InventoryRef.Block(self.world, at.x, at.y, at.z)
        return ref.takeIf { session.platform.inventories.kind(it) != null }?.let(InventoryKeys::of)
    }

    override fun biome(self: LuaHandle.Block): String? {
        val at = at(self)
        return session.platform.worlds.biome(self.world, at.x, at.y, at.z)?.let(session.names::spellBiome)
    }

    override fun lightLevel(self: LuaHandle.Block): Long? = snapshot(self)?.light?.toLong()

    override fun skyLightLevel(self: LuaHandle.Block): Long? = snapshot(self)?.skyLight?.toLong()

    override fun data(self: LuaHandle.Block): Any? {
        val at = at(self)
        return session.blockData.table(self.world, at.x, at.y, at.z)
    }
}

/** The neighbour each face names, as `block:relative("up")` takes it. */
private val FACES = mapOf(
    "up" to Triple(0, 1, 0),
    "down" to Triple(0, -1, 0),
    "north" to Triple(0, 0, -1),
    "south" to Triple(0, 0, 1),
    "east" to Triple(1, 0, 0),
    "west" to Triple(-1, 0, 0)
)
