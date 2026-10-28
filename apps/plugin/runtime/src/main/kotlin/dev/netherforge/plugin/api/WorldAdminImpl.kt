package dev.netherforge.plugin.api

import dev.netherforge.format.dimensiontype.DimensionTypeKind
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.terrain.TerrainKind
import dev.netherforge.plugin.binding.BorderDamage
import dev.netherforge.plugin.binding.BorderSizeOptions
import dev.netherforge.plugin.binding.BorderWarning
import dev.netherforge.plugin.binding.Caller
import dev.netherforge.plugin.binding.LocationOrVec3
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.NfStructuresApi
import dev.netherforge.plugin.binding.WorldBorderApi
import dev.netherforge.plugin.binding.WorldCreateOptions
import dev.netherforge.plugin.binding.place
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.BorderOwner
import dev.netherforge.plugin.platform.BorderState
import dev.netherforge.plugin.platform.WorldSettings
import dev.netherforge.plugin.session.ProjectSession
import java.util.UUID

/*
 * Managing worlds from Lua: `nf.worlds.create`/`load` (with `NfWorldsImpl`),
 * `World`'s unload, border and structure methods, `WorldBorder`,
 * `nf.structures`, and a player's own border. The rules (which worlds a
 * project may unload, where structures come from) are `ManagedWorlds` and
 * `StructureStore`'s.
 */

internal fun createWorld(session: ProjectSession, name: String, options: WorldCreateOptions?): LuaHandle.World? {
    val environment = options?.environment ?: "normal"
    // One of the project's terrains (named as the calling package names it), or else a vanilla world type.
    val terrain = options?.terrain?.let { projectTerrain(session, it) }
    if (terrain != null && options?.generator != null) {
        throw LuaApiException(
            "a world has a generator (one of the game's world types) or a terrain (one of the project's), not both: leave out one"
        )
    }
    if (terrain != null && environment != "normal") {
        throw LuaApiException(
            "a terrain of the project makes a normal world, not a $environment: leave out environment, or use a vanilla generator"
        )
    }
    val settings = WorldSettings(
        generator = options?.generator ?: "normal",
        environment = environment,
        seed = options?.seed,
        structures = options?.structures ?: true,
        keepSpawnLoaded = options?.keepSpawnLoaded ?: false,
        terrain = terrain,
        dimensionType = options?.dimensionType?.let { projectDimensionType(session, it) }
    )
    return if (session.managedWorlds.create(name, settings)) LuaHandle.World(name) else null
}

/** The id of the project terrain [reference] names, as the generators are named: an error naming what there is when it names none. */
private fun projectTerrain(session: ProjectSession, reference: String): String {
    val id = session.names.resource(TerrainKind, reference)
    if (!session.worldGenerators.has(id)) {
        val known = session.worldGenerators.ids().filter { session.names.usable(TerrainKind, it) }
        throw LuaApiException(
            "\"$reference\" isn't one of the project's terrains" +
                if (known.isEmpty()) " (it has none)" else " (it has: ${known.joinToString()})"
        )
    }
    return id
}

/**
 * The server's key of the project dimension type [reference] names (named as the calling package names it): an error
 * naming what there is when it names none, or one with errors (it isn't in the start-up datapack).
 */
private fun projectDimensionType(session: ProjectSession, reference: String): String {
    val id = session.names.resource(DimensionTypeKind, reference)
    val snapshot = session.snapshot
    if (id !in snapshot.running(DimensionTypeKind)) {
        val all = snapshot.everywhere(DimensionTypeKind)
        val known = snapshot.running(DimensionTypeKind).keys.filter { session.names.usable(DimensionTypeKind, it) }.sorted()
        throw LuaApiException(
            if (id in all) {
                "dimension type \"$reference\" has errors (see its file), so the server can't have it"
            } else {
                "\"$reference\" isn't one of the project's dimension types" +
                    if (known.isEmpty()) " (it has none)" else " (it has: ${known.joinToString()})"
            }
        )
    }
    return requireNotNull(DimensionTypeKind.keyOf(id, snapshot.namespace)) { "a running dimension's name \"$id\" is a reference" }
}

internal fun loadWorld(session: ProjectSession, name: String): LuaHandle.World? =
    if (session.managedWorlds.load(name)) LuaHandle.World(name) else null

/** `WorldBorder`: a world's border, or one player's own. */
internal class WorldBorderImpl(private val session: ProjectSession) : WorldBorderApi {
    private val borders get() = session.platform.borders

    private fun owner(self: LuaHandle.WorldBorder): BorderOwner? = when (self.owner) {
        WORLD -> BorderOwner.World(self.id)
        PLAYER -> runCatching { UUID.fromString(self.id) }.getOrNull()?.let { BorderOwner.Player(it) }
        else -> null
    }

    /** The owner and what the border is now, while it's there. */
    private fun live(self: LuaHandle.WorldBorder): Pair<BorderOwner, BorderState>? {
        val owner = owner(self) ?: return null
        return borders.get(owner)?.let { owner to it }
    }

    override fun center(self: LuaHandle.WorldBorder): Vec3? = live(self)?.second?.let { Vec3(it.centerX, 0.0, it.centerZ) }

    override fun setCenter(self: LuaHandle.WorldBorder, center: Vec3): Boolean {
        val limit = borders.maxCenter
        if (!center.x.isFinite() || !center.z.isFinite() || kotlin.math.abs(center.x) > limit || kotlin.math.abs(center.z) > limit) {
            throw LuaApiException("a border's centre can't be more than ${limit.toLong()} blocks out along x or z")
        }
        val (owner, _) = live(self) ?: return false
        return borders.setCenter(owner, center.x, center.z)
    }

    override fun size(self: LuaHandle.WorldBorder): Double? = live(self)?.second?.size

    override fun setSize(self: LuaHandle.WorldBorder, size: Double, options: BorderSizeOptions?): Boolean {
        val max = borders.maxSize
        if (size.isNaN() || size < 1.0 || size > max) throw LuaApiException("a border's size must be from 1 to ${max.toLong()}, not $size")
        val ticks = options?.ticks ?: 0
        if (ticks < 0) throw LuaApiException("ticks can't be negative")
        if (ticks > Int.MAX_VALUE) throw LuaApiException("ticks can be at most ${Int.MAX_VALUE}")
        val (owner, _) = live(self) ?: return false
        return borders.setSize(owner, size, ticks)
    }

    override fun damage(self: LuaHandle.WorldBorder): BorderDamage? =
        live(self)?.second?.let { BorderDamage(amount = it.damageAmount, buffer = it.damageBuffer) }

    override fun setDamage(self: LuaHandle.WorldBorder, damage: BorderDamage): Boolean {
        for ((name, value) in listOf("amount" to damage.amount, "buffer" to damage.buffer)) {
            if (value != null && (value.isNaN() || value < 0)) throw LuaApiException("damage.$name can't be negative")
        }
        val (owner, now) = live(self) ?: return false
        return borders.setDamage(owner, damage.amount ?: now.damageAmount, damage.buffer ?: now.damageBuffer)
    }

    override fun warning(self: LuaHandle.WorldBorder): BorderWarning? = live(self)?.second?.let {
        BorderWarning(distance = it.warningDistance.toLong(), ticks = it.warningTicks.toLong())
    }

    override fun setWarning(self: LuaHandle.WorldBorder, warning: BorderWarning): Boolean {
        for ((name, value) in listOf("distance" to warning.distance, "ticks" to warning.ticks)) {
            if (value != null &&
                value !in 0..Int.MAX_VALUE
            ) {
                throw LuaApiException("warning.$name must be from 0 to ${Int.MAX_VALUE}, not $value")
            }
        }
        val (owner, now) = live(self) ?: return false
        return borders.setWarning(owner, warning.distance?.toInt() ?: now.warningDistance, warning.ticks?.toInt() ?: now.warningTicks)
    }

    override fun contains(self: LuaHandle.WorldBorder, locationOrPosition: LocationOrVec3): Boolean {
        val (owner, _) = live(self) ?: return false
        val place = locationOrPosition.place
        val p = place.position
        return borders.contains(owner, place.world?.name, p.x, p.y, p.z)
    }

    companion object {
        const val WORLD = "world"
        const val PLAYER = "player"
    }
}

/** `nf.structures`. */
internal class NfStructuresImpl(private val session: ProjectSession) : NfStructuresApi {
    override fun exists(caller: Caller, id: String): Boolean = session.structures.exists(id)

    override fun size(caller: Caller, id: String): Vec3? =
        session.structures.size(id)?.let { Vec3(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) }
}
