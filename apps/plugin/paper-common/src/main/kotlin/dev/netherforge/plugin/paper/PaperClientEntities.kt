package dev.netherforge.plugin.paper

import com.destroystokyo.paper.event.server.ServerTickEndEvent
import dev.netherforge.plugin.platform.ClientEntityOps
import dev.netherforge.plugin.platform.EntityTag
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.StructureMarker
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.plugin.Plugin
import java.util.UUID
import kotlin.math.floor
import kotlin.math.sqrt
import org.bukkit.Location as BukkitLocation

/**
 * [ClientEntityOps] on Paper: entities made as [PaperEntities] makes them, but with `World.createEntity`, so they're
 * never added to their world. The server doesn't tick them, its tracker never sees them, and no other player costs
 * anything for them; their viewers' clients are sent them as packets ([ClientEntityPackets]).
 *
 * **Who has what.** A viewer's client is sent an entity once the chunk it stands in has been sent to them
 * (`Player.isChunkSent`, looked at once a tick for each chunk a viewer has entities in: a [Watch]), and forgets it
 * when that chunk is unloaded for them ([onChunkUnload]), or everything when they leave, join, respawn or change
 * worlds ([forgetAll]): the client drops entities with their chunk and its whole world on a respawn, so it's sent
 * them again when the chunk comes back. Nothing here loads a chunk.
 *
 * **When.** Changes are sent once a tick, at its end ([flush]): what changed about an entity
 * ([ClientEntityPackets.changes], so several setters in a tick are one update), its move, its riders, then what
 * a viewer's client should newly have, every entity's spawn before any riders so both ends are known.
 *
 * **Riding.** Only between these: a rider's place is its vehicle's, and only the vehicle's moves are sent (the
 * client carries its riders along).
 *
 * **Clicks.** A click on one of these is, to the server, on an entity it doesn't know (Paper's
 * `PlayerUseUnknownEntityEvent`): [clicked] says whose it is when it's an interaction the player's client has and
 * is within reach of.
 */
class PaperClientEntities(plugin: Plugin, private val wire: ClientEntityPackets) :
    PaperEntities(plugin),
    ClientEntityOps,
    ClientEntityLookup,
    Listener {
    private class Client(val entity: Entity, var world: String, var x: Double, var y: Double, var z: Double) {
        val id: UUID = entity.uniqueId
        val networkId: Int = entity.entityId
        var viewers: Set<UUID> = emptySet()

        /** The viewers whose clients have it now. */
        val sentTo = HashSet<UUID>()
        var vehicle: Client? = null
        val riders = LinkedHashSet<Client>()

        /** Its data changed, it moved, its riders changed: what [flush] sends its viewers. */
        var dirty = false
        var moved = false
        var ridersChanged = false

        val spot: Spot get() = Spot(world, Chunk.getChunkKey(floor(x).toInt() shr 4, floor(z).toInt() shr 4))
    }

    /** A chunk of a world, by Paper's chunk key. */
    private data class Spot(val world: String, val chunk: Long)

    /** One viewer's entities in one chunk, and whether their client has that chunk ([shown]); [pending] when some of them may not have been sent. */
    private class Watch {
        val clients = LinkedHashSet<Client>()
        var shown = false
        var pending = false
    }

    private val clients = HashMap<UUID, Client>()
    private val byNetwork = HashMap<Int, Client>()
    private val watches = HashMap<UUID, HashMap<Spot, Watch>>()
    private val changed = LinkedHashSet<Client>()

    /** Network ids each viewer's client is to forget at the next [flush]. */
    private val forgotten = HashMap<UUID, MutableList<Int>>()

    // ---- what's made, and found ----------------------------------------------------

    override fun <T : Entity> make(at: BukkitLocation, type: Class<T>, setup: (T) -> Unit): T {
        val entity = at.world.createEntity(at, type)
        setup(entity)
        val client = Client(entity, at.world.name, at.x, at.y, at.z)
        wire.place(entity, at.x, at.y, at.z)
        clients[client.id] = client
        byNetwork[client.networkId] = client
        return entity
    }

    override fun entity(id: UUID): Entity? = clients[id]?.entity

    override fun changed(entity: Entity) {
        val client = clients[entity.uniqueId] ?: return
        client.dirty = true
        changed += client
    }

    override fun byNetworkId(id: Int): Entity? = byNetwork[id]?.entity

    override fun byId(id: UUID): Entity? = clients[id]?.entity

    // ---- who sees it -----------------------------------------------------------------

    override fun setViewers(id: UUID, viewers: Set<UUID>) {
        val client = clients[id] ?: return
        val before = client.viewers
        client.viewers = viewers.toSet()
        for (gone in before - client.viewers) unwatch(gone, client)
        for (added in client.viewers - before) watch(added, client)
    }

    override fun setHidden(player: UUID, entity: UUID, hidden: Boolean) {
        val client = clients[entity] ?: return
        setViewers(entity, if (hidden) client.viewers - player else client.viewers + player)
    }

    private fun watch(viewer: UUID, client: Client) {
        val watch = watches.getOrPut(viewer) { HashMap() }.getOrPut(client.spot) { Watch() }
        watch.clients += client
        watch.pending = true
    }

    /** [viewer] stops seeing [client] (it left them, or moved out of the chunk they watch): their client forgets it if it had it. */
    private fun unwatch(viewer: UUID, client: Client, spot: Spot = client.spot) {
        val spots = watches[viewer] ?: return
        val watch = spots[spot] ?: return
        watch.clients -= client
        if (watch.clients.isEmpty()) spots.remove(spot)
        if (spots.isEmpty()) watches.remove(viewer)
        if (client.sentTo.remove(viewer)) forgotten.getOrPut(viewer) { ArrayList() } += client.networkId
    }

    /**
     * [client] moved from [before] to [after]: [viewer] watches it there. Their client keeps it when it has that
     * chunk too (it moves it, no flicker), and forgets it otherwise, to get it again once that chunk is sent.
     */
    private fun rewatch(viewer: UUID, client: Client, before: Spot, after: Spot) {
        val spots = watches.getOrPut(viewer) { HashMap() }
        spots[before]?.let {
            it.clients -= client
            if (it.clients.isEmpty()) spots.remove(before)
        }
        val watch = spots.getOrPut(after) {
            Watch().also { fresh ->
                val player = Bukkit.getPlayer(viewer)
                fresh.shown = player != null && player.world.name == after.world && player.isChunkSent(after.chunk)
            }
        }
        watch.clients += client
        watch.pending = true
        if (!watch.shown && client.sentTo.remove(viewer)) forgotten.getOrPut(viewer) { ArrayList() } += client.networkId
    }

    // ---- riding and moving -------------------------------------------------------------

    override fun mount(vehicle: UUID, passenger: UUID): Boolean {
        val carrier = clients[vehicle] ?: return false
        val rider = clients[passenger] ?: return false
        if (rider.vehicle === carrier) return true
        if (rider === carrier || carries(rider, carrier)) return false
        getOff(rider)
        rider.vehicle = carrier
        carrier.riders += rider
        carrier.ridersChanged = true
        changed += carrier
        moveTo(rider, carrier.world, carrier.x, carrier.y, carrier.z)
        return true
    }

    /** Whether [client] carries [other], however far up. */
    private fun carries(client: Client, other: Client): Boolean = generateSequence(other.vehicle) { it.vehicle }.any { it === client }

    override fun dismount(passenger: UUID) {
        clients[passenger]?.let(::getOff)
    }

    private fun getOff(rider: Client) {
        val vehicle = rider.vehicle ?: return
        vehicle.riders -= rider
        vehicle.ridersChanged = true
        changed += vehicle
        rider.vehicle = null
    }

    override fun isLoaded(id: UUID): Boolean = id in clients

    override fun location(id: UUID): Location? = clients[id]?.let { Location(it.world, it.x, it.y, it.z) }

    override fun teleport(id: UUID, to: Location) {
        val client = clients[id] ?: return
        // A rider stays on, as on the server: it's where its vehicle is.
        if (client.vehicle != null) return
        moveTo(client, to.world, to.x, to.y, to.z)
        client.moved = true
        changed += client
    }

    /** Puts [client] and everything it carries at a place, watched in the chunk that's in. */
    private fun moveTo(client: Client, world: String, x: Double, y: Double, z: Double) {
        val before = client.spot
        client.world = world
        client.x = x
        client.y = y
        client.z = z
        wire.place(client.entity, x, y, z)
        val after = client.spot
        if (after != before) for (viewer in client.viewers) rewatch(viewer, client, before, after)
        for (rider in client.riders) moveTo(rider, world, x, y, z)
    }

    override fun remove(id: UUID): Boolean {
        val client = clients.remove(id) ?: return false
        byNetwork.remove(client.networkId)
        changed -= client
        getOff(client)
        for (rider in client.riders.toList()) getOff(rider)
        for (viewer in client.viewers) unwatch(viewer, client)
        return true
    }

    // ---- what never applies to these -----------------------------------------------------

    override fun setPersistent(id: UUID, persistent: Boolean) {}

    override fun loadedTagged(): Map<UUID, EntityTag> = emptyMap()

    override fun structureMarkers(): List<StructureMarker> = emptyList()

    // ---- clicks -----------------------------------------------------------------------

    /**
     * Whose interaction [player]'s client clicked by its network id: one of these that their client has, within
     * [REACH] of their eyes (the server checks that for its own entities; here nothing else would). Null otherwise.
     */
    fun clicked(player: Player, networkId: Int): UUID? {
        val client = byNetwork[networkId] ?: return null
        val box = client.entity as? Interaction ?: return null
        if (player.uniqueId !in client.sentTo || player.world.name != client.world) return null
        val eye = player.eyeLocation
        val half = box.interactionWidth / 2.0
        val dx = eye.x - eye.x.coerceIn(client.x - half, client.x + half)
        val dy = eye.y - eye.y.coerceIn(client.y, client.y + box.interactionHeight)
        val dz = eye.z - eye.z.coerceIn(client.z - half, client.z + half)
        return client.id.takeIf { sqrt(dx * dx + dy * dy + dz * dz) <= REACH }
    }

    // ---- what clients have -----------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkUnload(event: PlayerChunkUnloadEvent) {
        val watch = watches[event.player.uniqueId]?.get(Spot(event.world.name, event.chunk.chunkKey)) ?: return
        unshow(event.player.uniqueId, watch)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) = forgetAll(event.player.uniqueId)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) = forgetAll(event.player.uniqueId)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) = forgetAll(event.player.uniqueId)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChangedWorld(event: PlayerChangedWorldEvent) = forgetAll(event.player.uniqueId)

    @EventHandler
    fun onTickEnd(@Suppress("UNUSED_PARAMETER") event: ServerTickEndEvent) = flush()

    /** [viewer]'s client has nothing of these any more: it gets each again once its chunk is sent. */
    private fun forgetAll(viewer: UUID) {
        forgotten.remove(viewer)
        watches[viewer]?.values?.forEach { unshow(viewer, it) }
    }

    private fun unshow(viewer: UUID, watch: Watch) {
        watch.shown = false
        for (client in watch.clients) client.sentTo -= viewer
    }

    /** Sends every viewer what changed this tick, and what their client should newly have. */
    fun flush() {
        if (clients.isEmpty() && forgotten.isEmpty()) return
        val out = HashMap<UUID, MutableList<Any>>()
        fun to(viewer: UUID) = out.getOrPut(viewer) { ArrayList() }

        for ((viewer, ids) in forgotten) if (ids.isNotEmpty()) to(viewer) += wire.remove(ids.toIntArray())
        forgotten.clear()

        for (client in changed) {
            val changes = if (client.dirty) wire.changes(client.entity) else null
            val position = if (client.moved) wire.position(client.entity) else null
            val riders = if (client.ridersChanged) wire.riders(client.networkId, riderIds(client)) else null
            client.dirty = false
            client.moved = false
            client.ridersChanged = false
            for (viewer in client.sentTo) {
                val packets = to(viewer)
                changes?.let { packets += it }
                position?.let { packets += it }
                riders?.let { packets += it }
            }
        }
        changed.clear()

        for ((viewer, spots) in watches) {
            val player = Bukkit.getPlayer(viewer)
            for ((spot, watch) in spots) {
                val shown = player != null && player.world.name == spot.world && player.isChunkSent(spot.chunk)
                if (!shown) {
                    if (watch.shown) unshow(viewer, watch)
                    continue
                }
                if (watch.shown && !watch.pending) continue
                watch.shown = true
                watch.pending = false
                val fresh = watch.clients.filter { viewer !in it.sentTo }
                if (fresh.isEmpty()) continue
                val packets = to(viewer)
                for (client in fresh) {
                    packets.addAll(wire.spawn(client.entity))
                    client.sentTo += viewer
                }
                // Riders once both ends are known: a fresh vehicle's, and the vehicles fresh riders get on.
                val vehicles = LinkedHashSet<Client>()
                for (client in fresh) {
                    if (client.riders.isNotEmpty()) vehicles += client
                    client.vehicle?.takeIf { viewer in it.sentTo }?.let { vehicles += it }
                }
                for (vehicle in vehicles) packets += wire.riders(vehicle.networkId, riderIds(vehicle, viewer))
            }
        }

        for ((viewer, packets) in out) {
            if (packets.isEmpty()) continue
            val player = Bukkit.getPlayer(viewer) ?: continue
            for (batch in packets.chunked(BUNDLE)) wire.send(player, batch)
        }
    }

    /** What rides [vehicle]: everything, or only what [viewer]'s client has. */
    private fun riderIds(vehicle: Client, viewer: UUID? = null): IntArray =
        vehicle.riders.filter { viewer == null || viewer in it.sentTo }.map { it.networkId }.toIntArray()

    private companion object {
        /**
         * How far from a player's eyes a client entity's interaction can be clicked: the creative entity reach (5)
         * and the server's own slack for its entities (3).
         */
        const val REACH = 8.0

        /** Packets in one bundle: the client takes at most 4096. */
        const val BUNDLE = 4000
    }
}
