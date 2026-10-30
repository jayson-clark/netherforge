package dev.netherforge.plugin.testkit

import dev.netherforge.format.bridge.BotEvent
import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.PackOffer
import dev.netherforge.plugin.platform.ParticleOps
import dev.netherforge.plugin.platform.ParticleSpawn
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.ResourcePackOps
import dev.netherforge.plugin.platform.SoundOps
import dev.netherforge.plugin.platform.SoundPlay
import java.util.UUID

// Particles, sounds and resource packs: what the fake server sends players.

/**
 * Particles: every batch the runtime sends, and what reaches each viewer, as
 * Paper sends it: a particle the game has, to a viewer online in that world
 * and within 32 blocks of it (512 when forced).
 */
class FakeParticles(private val platform: FakePlatform) : ParticleOps {
    /** Every batch: its world, its spawns, and who it went to. */
    val sent = mutableListOf<Triple<String, List<ParticleSpawn>, List<PlayerRef>>>()

    override fun spawn(world: String, spawns: List<ParticleSpawn>, viewers: List<PlayerRef>) {
        sent += Triple(world, spawns, viewers)
        if (!platform.worlds.exists(world)) return
        for (viewer in viewers.mapNotNull { platform.players.byId[it.uuid] }.filter { it.location.world == world }) {
            for (spawn in spawns.filter { it.particle in platform.game.particles }) {
                val range = if (spawn.force) FORCED_RANGE else RANGE
                if (distance(viewer.location, spawn.position) > range) continue
                platform.bots.sent(viewer.ref.uuid) {
                    BotEvent.Particle(
                        it,
                        spawn.particle,
                        spawn.position.x,
                        spawn.position.y,
                        spawn.position.z,
                        spawn.count,
                        spawn.offset.x,
                        spawn.offset.y,
                        spawn.offset.z,
                        spawn.speed,
                        spawn.force
                    )
                }
            }
        }
    }

    /** How many times the runtime said to drop what's kept between spawns. */
    var forgets = 0

    override fun forget() {
        forgets++
    }

    private companion object {
        /** How far the server sends particles: Minecraft's `ServerLevel.sendParticles`. */
        const val RANGE = 32.0
        const val FORCED_RANGE = 512.0
    }
}

/**
 * Sounds: every one played, and what reaches each player, as Paper sends
 * it: a sound in a world to the players in it within 16 blocks (16 times
 * the volume, when that's more), one to a player where they are.
 */
class FakeSounds(private val platform: FakePlatform) : SoundOps {
    /** Every sound played, as `"<world or player> <sound> <category> <volume> <pitch>"`. */
    val played = mutableListOf<String>()
    val stopped = mutableListOf<String>()

    override fun play(world: String, position: Vec3, sound: SoundPlay): Boolean {
        if (!platform.worlds.exists(world)) return false
        played += "$world ${sound.sound} ${sound.category} ${sound.volume} ${sound.pitch}"
        val range = HEARD * maxOf(sound.volume, 1.0)
        for (player in platform.players.byId.values.filter { it.location.world == world && distance(it.location, position) < range }) {
            heard(player, sound, position)
        }
        return true
    }

    override fun playTo(player: UUID, sound: SoundPlay): Boolean {
        val who = platform.players.byId[player] ?: return false
        played += "${who.ref.name} ${sound.sound} ${sound.category} ${sound.volume} ${sound.pitch}"
        heard(who, sound, Vec3(who.location.x, who.location.y, who.location.z))
        return true
    }

    override fun stop(player: UUID, sound: String?): Boolean {
        val who = platform.players.byId[player] ?: return false
        stopped += "${who.ref.name} ${sound ?: "everything"}"
        platform.bots.sent(player) { BotEvent.StopSound(it, sound?.let(::namespaced)) }
        return true
    }

    private fun heard(player: FakePlayer, sound: SoundPlay, at: Vec3) = platform.bots.sent(player.ref.uuid) {
        BotEvent.Sound(it, namespaced(sound.sound), at.x, at.y, at.z, sound.volume, sound.pitch)
    }

    /** A sound's id as the client reads it: Minecraft's namespace when it has none. */
    private fun namespaced(sound: String) = if (':' in sound) sound else "minecraft:$sound"

    private companion object {
        /** How far a sound of volume 1 is heard: Minecraft's `SoundEvent.getRange`. */
        const val HEARD = 16.0
    }
}

class FakePacks(private val platform: FakePlatform) : ResourcePackOps {
    val sent = mutableListOf<Pair<UUID, PackOffer>>()

    /** Packs taken off players, by id. */
    val removed = mutableListOf<Pair<UUID, UUID>>()

    /** Offers [pack]; a player whose game answers ([FakePlayer.packAnswer]) is heard answering. */
    override fun remove(player: UUID, id: UUID): Boolean {
        platform.players.byId[player] ?: return false
        removed += player to id
        return true
    }

    override fun send(player: UUID, pack: PackOffer): Boolean {
        val who = platform.players.byId[player] ?: return false
        sent += player to pack
        platform.bots.offered(who, pack)
        who.packAnswer?.let { platform.events?.resourcePackStatus(player, pack.id, it) }
        return true
    }
}

private fun distance(from: Location, to: Vec3) =
    kotlin.math.sqrt((from.x - to.x) * (from.x - to.x) + (from.y - to.y) * (from.y - to.y) + (from.z - to.z) * (from.z - to.z))
