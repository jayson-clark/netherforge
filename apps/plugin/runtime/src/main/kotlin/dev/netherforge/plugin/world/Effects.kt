package dev.netherforge.plugin.world

import dev.netherforge.format.game.BlockState
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.game.ParticleDataKind
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.game.has
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.particle.BlockData
import dev.netherforge.format.particle.ColorData
import dev.netherforge.format.particle.DustData
import dev.netherforge.format.particle.DustTransitionData
import dev.netherforge.format.particle.SpawnData
import dev.netherforge.plugin.binding.ParticleOptions
import dev.netherforge.plugin.binding.SoundOptions
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.ParticleSpawn
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.SoundPlay
import dev.netherforge.plugin.session.PackageNames
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.format.particle.ItemData as ItemSpawnData

/**
 * `spawn_particle` and `play_sound`: their ids and options checked against
 * the server (particles and their data kinds, the sound registry) and the
 * project (pack sounds), then handed to the platform. Particles go through
 * the same batched [dev.netherforge.plugin.platform.ParticleOps.spawn] that
 * particle effects use, with viewers worked out here.
 */
internal class Effects(
    private val platform: Platform,
    /** A reference as a pack sound, by the rule for looks ([dev.netherforge.plugin.session.PackageNames.packSound]). */
    private val packSound: (String) -> PackageNames.PackSound
) : RuntimeService {
    override val name get() = "particles and sounds"

    /**
     * Spawns [particle] at [position] in [world], seen by everyone near
     * enough (or by the option's viewers). False when the world doesn't exist.
     */
    fun spawnParticle(world: String, particle: String, position: Vec3, options: ParticleOptions?): Boolean {
        val spawn = particleSpawn(particle, position, options ?: ParticleOptions())
        return send(world, spawn, options?.viewers?.map { it.id }?.toSet())
    }

    /**
     * `player:spawn_particle`: [particle] for [player] alone, in their world
     * ([world], null when they're offline: then it's only checked, and false).
     */
    fun spawnParticleFor(player: PlayerRef?, world: String?, particle: String, position: Vec3, options: ParticleOptions?): Boolean {
        if (options?.viewers != null) {
            throw LuaApiException("options.viewers: player:spawn_particle is only for that player (world:spawn_particle takes viewers)")
        }
        val spawn = particleSpawn(particle, position, options ?: ParticleOptions())
        if (player == null || world == null) return false
        return send(world, spawn, setOf(player.uuid.toString()))
    }

    /** Sends [spawn] to the players in range of it, of [chosen] (UUIDs) when given. */
    private fun send(world: String, spawn: ParticleSpawn, chosen: Set<String>?): Boolean {
        if (!platform.worlds.exists(world)) return false
        val range = if (spawn.force) FORCED_RANGE else RANGE
        val position = spawn.position
        val viewers = platform.players.online().filter { player ->
            if (chosen != null && player.uuid.toString() !in chosen) return@filter false
            val at = platform.players.location(player.uuid) ?: return@filter false
            val dx = at.x - position.x
            val dy = at.y - position.y
            val dz = at.z - position.z
            at.world == world && dx * dx + dy * dy + dz * dz <= range * range
        }
        if (viewers.isNotEmpty()) platform.particles.spawn(world, listOf(spawn), viewers)
        return true
    }

    /** One spawn from a particle id and its options, every option checked against what the particle takes. */
    private fun particleSpawn(particle: String, position: Vec3, options: ParticleOptions): ParticleSpawn {
        if (!GameIds.isValid(particle)) throw LuaApiException("\"$particle\" isn't a particle id")
        val id = GameIds.normalize(particle)
        val game = platform.game
        val kind = game.particle(id)?.data ?: throw LuaApiException("no particle \"$id\" on this server")
        if (kind == ParticleDataKind.OTHER) throw LuaApiException("particle \"$id\" takes data NetherForge can't send")
        val given = buildList {
            if (options.color != null) add("color")
            if (options.toColor != null) add("to_color")
            if (options.size != null) add("size")
            if (options.blockState != null) add("block_state")
            if (options.item != null) add("item")
        }
        val takes = when (kind) {
            ParticleDataKind.DUST -> setOf("color", "size")
            ParticleDataKind.DUST_TRANSITION -> setOf("color", "to_color", "size")
            ParticleDataKind.COLOR -> setOf("color")
            ParticleDataKind.BLOCK -> setOf("block_state")
            ParticleDataKind.ITEM -> setOf("item")
            ParticleDataKind.NONE, ParticleDataKind.OTHER, null -> emptySet()
        }
        given.firstOrNull { it !in takes }?.let { throw LuaApiException("particle \"$id\" doesn't take options.$it") }
        val count = options.count ?: 1
        if (count !in 0..MAX_COUNT) throw LuaApiException("options.count must be 0 to $MAX_COUNT, not $count")
        val size = options.size ?: 1.0
        if (size !in 0.01..4.0) throw LuaApiException("options.size must be 0.01 to 4, not $size")
        val color = color(options.color, "color")
        val data: SpawnData? = when (kind) {
            ParticleDataKind.DUST -> DustData(color, size)
            ParticleDataKind.DUST_TRANSITION -> DustTransitionData(color, color(options.toColor, "to_color"), size)
            ParticleDataKind.COLOR -> ColorData(color)
            ParticleDataKind.BLOCK -> BlockData(
                blockState(options.blockState ?: throw LuaApiException("particle \"$id\" needs options.block_state"), "options.block_state")
            )
            ParticleDataKind.ITEM -> ItemSpawnData((options.item ?: throw LuaApiException("particle \"$id\" needs options.item")).def)
            else -> null
        }
        return ParticleSpawn(
            particle = id,
            position = position,
            count = count.toInt(),
            offset = options.spread ?: Vec3.ZERO,
            speed = options.speed ?: 0.0,
            data = data,
            force = options.force == true
        )
    }

    /** `#rrggbb` (either case) as `0xRRGGBB`; white when not given. */
    private fun color(text: String?, field: String): Int {
        if (text == null) return WHITE
        if (!COLOR.matches(text)) throw LuaApiException("options.$field must be \"#rrggbb\", not \"$text\"")
        return text.substring(1).toInt(16)
    }

    /** A block state the server has, canonical with its defaults filled in; anything else is an error naming [where]. */
    fun blockState(text: String, where: String): String {
        val state = BlockState.parse(text) ?: throw LuaApiException("$where: \"$text\" isn't a block state")
        val info = platform.game.block(state.id) ?: throw LuaApiException("$where: no block \"${state.id}\" on this server")
        for ((property, value) in state.properties) {
            val values = info.properties[property]
                ?: throw LuaApiException(
                    "$where: ${state.id} has no property \"$property\" (it has: ${info.properties.keys.joinToString(", ").ifEmpty {
                        "none"
                    }})"
                )
            if (value !in
                values
            ) {
                throw LuaApiException("$where: ${state.id}'s $property can't be \"$value\" (it can be ${values.joinToString(", ")})")
            }
        }
        return state.withDefaults(info).toString()
    }

    /**
     * What to play for [sound] and [options]: a pack sound the project's packs
     * have, or a sound the server has. Anything else is an error.
     */
    fun sound(sound: String, options: SoundOptions?): SoundPlay {
        val id = soundId(sound)
        val volume = options?.volume ?: 1.0
        if (volume < 0 || !volume.isFinite()) throw LuaApiException("options.volume can't be below 0")
        val pitch = options?.pitch ?: 1.0
        if (pitch !in 0.5..2.0) throw LuaApiException("options.pitch must be 0.5 to 2, not $pitch")
        return SoundPlay(id, options?.category ?: "master", volume, pitch)
    }

    /**
     * A sound id, namespaced, once it's known to name a sound: one of the
     * calling package's pack sounds, or one of any loaded package's, by
     * reference (`ui/menu/open`, the game's `shop:ui/menu/open`: a sound is a
     * look), or else one of the server's (`entity.pig.ambient` is
     * `minecraft:entity.pig.ambient`, and another plugin's pack may have
     * `other:boom`).
     */
    fun soundId(sound: String): String {
        val pack = packSound(sound)
        pack.key?.let { return it.toString() }
        if (!GameIds.isValid(sound)) throw LuaApiException("\"$sound\" isn't a sound id")
        val id = GameIds.normalize(sound)
        if (platform.game.has(RegistryKey.SOUND_EVENT, id) != false) return id
        // Shaped like a sound of a loaded package's pack: say what that pack lacks.
        pack.problem?.let { throw LuaApiException(it) }
        throw LuaApiException("no sound \"$id\" on this server or in the project's resource packs")
    }

    private companion object {
        /** How far the client draws particles that aren't forced, and those that are. */
        const val RANGE = 32.0
        const val FORCED_RANGE = 128.0

        /** Particles one spawn may scatter: past this a script's typo could flood every client. */
        const val MAX_COUNT = 10_000L
        const val WHITE = 0xFFFFFF
        val COLOR = Regex("^#[0-9a-fA-F]{6}$")
    }
}
