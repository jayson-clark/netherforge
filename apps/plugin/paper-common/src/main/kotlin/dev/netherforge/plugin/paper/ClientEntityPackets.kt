package dev.netherforge.plugin.paper

import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.UUID

/**
 * The packets a client entity ([PaperClientEntities]) is sent as: the server's own, made from an entity it made but
 * never added to a world. Past the API, so each adapter's `paper-internals` has them ([PaperVersion.clientEntities]).
 * Packets are opaque here: only [send] takes them.
 */
interface ClientEntityPackets {
    /** Puts [entity] at [x], [y], [z]: where it's sent at from now on, and what [position] says. */
    fun place(entity: Entity, x: Double, y: Double, z: Double)

    /** What tells a client of [entity]: its spawn where it is, with everything about it that isn't the default. */
    fun spawn(entity: Entity): List<Any>

    /** What changed about [entity] since this was last asked (which it forgets), or null when nothing did. */
    fun changes(entity: Entity): Any?

    /** Where [entity] is now, for a client that has it to move it there (easing as its teleport duration says). */
    fun position(entity: Entity): Any

    /** That [riders] (network ids) ride [vehicle], and nothing else does. */
    fun riders(vehicle: Int, riders: IntArray): Any

    /** That a client forgets [ids]. */
    fun remove(ids: IntArray): Any

    /** Sends [packets] to [player], in order, bundled so the client takes each bundle in one go. */
    fun send(player: Player, packets: List<Any>)
}

/** Client entities by id, for the bots: what a bot's client can click, though no world has it. */
interface ClientEntityLookup {
    fun byNetworkId(id: Int): Entity?

    fun byId(id: UUID): Entity?
}
