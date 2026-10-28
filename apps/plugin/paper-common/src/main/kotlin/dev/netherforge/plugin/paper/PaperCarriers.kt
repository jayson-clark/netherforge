package dev.netherforge.plugin.paper

import dev.netherforge.format.block.BlockCarrier
import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Leaves
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.LeavesDecayEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask

/**
 * Stopping the server working out the states of the blocks custom blocks are held in, per carrier
 * ([BlockCarrier]), each only once the project has a block in it: note blocks ([PaperNoteBlocks]), tripwire
 * ([PaperTripwires]) and leaves ([PaperLeaves]).
 */
class PaperCarriers(plugin: JavaPlugin, version: PaperVersion, val noteBlocks: PaperNoteBlocks) {
    val tripwires = PaperTripwires(plugin, version)
    val leaves = PaperLeaves(plugin)

    fun freeze(carrier: BlockCarrier): Boolean = when (carrier) {
        BlockCarrier.NOTE_BLOCK -> noteBlocks.freeze()
        BlockCarrier.TRIPWIRE -> tripwires.freeze()
        BlockCarrier.LEAVES -> leaves.freeze()
    }

    /** The plugin is being disabled: the server's settings and behaviour are its own again. */
    fun release() {
        noteBlocks.release()
        tripwires.release()
        leaves.release()
    }
}

/**
 * Tripwire held still (Paper's `disable-tripwire-updates`), for tripwire blocks: the server then places string in
 * its default state and never attaches, powers or disarms a tripwire, nor joins it to its neighbours, so a state is
 * what it was set to. String still goes down and breaks; it no longer trips hooks, which is what freezing costs
 * vanilla tripwire, and why it's off until the project has a tripwire block.
 */
class PaperTripwires(private val plugin: JavaPlugin, private val version: PaperVersion) {
    var frozen = false
        private set

    private var watch: BukkitTask? = null

    /** Turns the switch on and keeps it on (a `/paper reload` puts the file's back). False when this server can't. */
    fun freeze(): Boolean {
        if (!version.setTripwireUpdatesDisabled(true)) return false
        if (!frozen) {
            frozen = true
            watch = plugin.server.scheduler.runTaskTimer(
                plugin,
                Runnable { if (!version.tripwireUpdatesDisabled()) version.setTripwireUpdatesDisabled(true) },
                REASSERT,
                REASSERT
            )
        }
        return true
    }

    fun release() {
        watch?.cancel()
        watch = null
        if (frozen) version.setTripwireUpdatesDisabled(false)
        frozen = false
    }

    private companion object {
        /** Ticks between checks that the switch is still on. */
        const val REASSERT = 20L
    }
}

/**
 * Leaves held still for leaves blocks, which Paper has no switch for. Leaves blocks are held in persistent leaves
 * nearer than the farthest distance a leaf can be from a log, so:
 *
 * - the server mustn't work a persistent leaves block's distance out again (a player's, or a custom block). It does
 *   that in a tick the leaves schedule themselves when a neighbour changes shape, which no event can stop: so a
 *   block about to update its neighbours' shapes first has the persistent leaves beside it [guarded], and when one
 *   of those changes nothing but its distance (its tick working it out) it's put back, within the same change, so
 *   no player sees it. Natural leaves are never persistent, so they still work their distance out and decay when
 *   their tree goes;
 * - a player's leaves go in at the farthest distance (what a frozen leaf keeps), out of the blocks' states.
 *
 * Nothing is listened to until [freeze]: physics events are the server's busiest, and a project without leaves
 * blocks has no reason to hear them.
 */
class PaperLeaves(private val plugin: JavaPlugin) : Listener {
    var frozen = false
        private set

    /** Set while the runtime places a block itself (a custom block in a leaves state), which isn't a player's leaves. */
    var placing = false

    fun freeze(): Boolean {
        if (!frozen) {
            plugin.server.pluginManager.registerEvents(this, plugin)
            frozen = true
        }
        return true
    }

    fun release() {
        HandlerList.unregisterAll(this)
        guarded.clear()
        frozen = false
    }

    /** Persistent leaves beside a block that changed, as they were, and the tick they were seen in: their own tick may follow. */
    private val guarded = HashMap<Block, Guard>()

    private class Guard(val data: Leaves, val tick: Int)

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPhysics(event: BlockPhysicsEvent) {
        val block = event.block
        // A neighbour of a block that changed: leaves have nothing to do with it, and a persistent one wants nothing.
        if (event.sourceBlock != block) {
            if ((block.blockData as? Leaves)?.isPersistent == true) event.isCancelled = true
            return
        }
        // The block itself changed, and its neighbours' shapes are updated next.
        val now = Bukkit.getCurrentTick()
        if (guarded.size > PRUNE) guarded.values.removeIf { now - it.tick > GUARD_TICKS }
        val was = guarded.remove(block)
        val data = event.changedBlockData as? Leaves
        if (was != null && data != null && now - was.tick <= GUARD_TICKS && data.isPersistent && onlyDistance(was.data, data)) {
            // Its tick worked its distance out: what it was is put back before the change goes anywhere.
            block.setBlockData(was.data, false)
            event.isCancelled = true
            return
        }
        for (face in FACES) {
            val next = block.getRelative(face)
            val leaves = next.blockData as? Leaves ?: continue
            if (leaves.isPersistent) guarded[next] = Guard(leaves, now)
        }
    }

    private fun onlyDistance(was: Leaves, now: Leaves): Boolean {
        if (was.distance == now.distance) return false
        val moved = was.clone() as Leaves
        moved.distance = now.distance
        return moved == now
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (placing) return
        val block = event.blockPlaced
        val data = block.blockData as? Leaves ?: return
        if (!data.isPersistent || data.distance >= data.maximumDistance) return
        data.distance = data.maximumDistance
        block.setBlockData(data, false)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onDecay(event: LeavesDecayEvent) {
        if ((event.block.blockData as? Leaves)?.isPersistent == true) event.isCancelled = true
    }

    private companion object {
        val FACES = arrayOf(BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)

        /** How long a guard lasts: the leaves' tick comes one tick after their neighbour changed (in the same one, when it changed early in it). */
        const val GUARD_TICKS = 2

        /** Guards are only looked at for expiry once there are this many, so a quiet server keeps a handful. */
        const val PRUNE = 256
    }
}
