package dev.netherforge.plugin.paper

import org.bukkit.Bukkit
import org.bukkit.Instrument
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.NoteBlock
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.event.block.NotePlayEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask

/**
 * The vanilla note blocks' part, which the plugin plays itself once it has
 * stopped the server working note blocks out (Paper's
 * `disable-noteblock-updates`): custom blocks are held as note block states,
 * and the server mustn't change a state it was given.
 *
 * With that switch on, the server places every note block in its default
 * state, never changes its instrument or `powered`, doesn't tune it and
 * doesn't play it from redstone. So **a player's note block is one in the
 * default instrument's column** (any note, powered or not: what the server
 * places, and what tuning sets), and this does for it what the game does:
 *
 * - **tuning**: a right click moves its note up one (back to the lowest after
 *   the highest) and sounds it, except where the game wouldn't: sneaking with
 *   something in hand, or a head placed on top;
 * - **playing**: a hit sounds it (the server does that itself, except under a
 *   block, which only a mob head's own instrument works under: then this
 *   does), and a redstone signal arriving sounds it, `powered` kept as the
 *   game keeps it;
 * - **instruments**: worked out from the blocks above and below each time it
 *   sounds ([PaperVersion.noteInstrument]), since the state's is always the
 *   default.
 *
 * Every other state is a custom block's: it never sounds, can't be tuned, and a
 * right click on it doesn't use the block, so what's in hand is placed against
 * it as against any other. Nothing here runs until [freeze] has been asked
 * for, so a server whose project has no blocks keeps its note blocks as the game has them.
 */
class PaperNoteBlocks(private val plugin: JavaPlugin, private val version: PaperVersion) : Listener {
    /** Whether the server's own working out of note blocks is off, and this has taken it over. */
    var frozen = false
        private set

    private var watch: BukkitTask? = null

    /** The note blocks a neighbour changed this tick, each looked at once. */
    private val looking = HashSet<Block>()

    /** The instrument a player's note block is in, whatever the server places one as: the default state's. */
    private val vanilla: Instrument by lazy { (Material.NOTE_BLOCK.createBlockData() as NoteBlock).instrument }

    /** What the game's tag says may be put on top of a note block without tuning it: the heads. */
    private val heads: Tag<Material>? by lazy {
        Bukkit.getTag(Tag.REGISTRY_ITEMS, NamespacedKey.minecraft("noteblock_top_instruments"), Material::class.java)
    }

    /**
     * Stops the server working note blocks out, and keeps it stopped (a
     * `/paper reload` would put the file's setting back). False when this
     * server can't.
     */
    fun freeze(): Boolean {
        if (!version.setNoteBlockUpdatesDisabled(true)) return false
        if (!frozen) {
            frozen = true
            watch = plugin.server.scheduler.runTaskTimer(
                plugin,
                Runnable { if (!version.noteBlockUpdatesDisabled()) version.setNoteBlockUpdatesDisabled(true) },
                REASSERT,
                REASSERT
            )
        }
        return true
    }

    /** The plugin is being disabled: the server's setting is its own again. */
    fun release() {
        watch?.cancel()
        watch = null
        if (frozen) version.setNoteBlockUpdatesDisabled(false)
        frozen = false
    }

    private fun isPlayers(data: NoteBlock) = data.instrument == vanilla

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (!frozen) return
        val block = event.clickedBlock ?: return
        if (block.type != Material.NOTE_BLOCK) return
        val data = block.blockData as NoteBlock
        if (!isPlayers(data)) {
            // A custom block's state: it isn't used (no tuning, no sound). The server would still hand every right click
            // that isn't sneaking to the block, so what's in hand is put to use on it here, as it is on any block.
            if (event.action == Action.RIGHT_CLICK_BLOCK) putToUse(event, block)
            return
        }
        when (event.action) {
            Action.RIGHT_CLICK_BLOCK -> if (event.hand == EquipmentSlot.HAND) tune(event, block, data)
            // The game plays it itself, unless something is above it that its instrument doesn't work under.
            Action.LEFT_CLICK_BLOCK -> if (!block.getRelative(BlockFace.UP).type.isAir) version.playNote(block)
            else -> Unit
        }
    }

    private fun putToUse(event: PlayerInteractEvent, block: Block) {
        val player = event.player
        val hand = event.hand ?: return
        val held = if (hand == EquipmentSlot.OFF_HAND) player.inventory.itemInOffHand else player.inventory.itemInMainHand
        // Sneaking with something in hand skips the block already; with nothing, there's nothing to put to use.
        if (held.isEmpty || player.isSneaking) {
            event.setUseInteractedBlock(Event.Result.DENY)
            return
        }
        event.setUseInteractedBlock(Event.Result.DENY)
        event.setUseItemInHand(Event.Result.DENY)
        val point = event.interactionPoint?.toVector() ?: block.location.toVector().add(org.bukkit.util.Vector(0.5, 0.5, 0.5))
        version.useItemOn(player, hand, block, event.blockFace, point)
    }

    /** A right click with the main hand on a player's note block: the game's `useItemOn` then `useWithoutItem`. */
    private fun tune(event: PlayerInteractEvent, block: Block, data: NoteBlock) {
        val player = event.player
        val hands = player.inventory.itemInMainHand.isEmpty && player.inventory.itemInOffHand.isEmpty
        // Sneaking with something in hand is placing it, not using the block.
        if (player.isSneaking && !hands) return
        // A head on top is placed, not tuned.
        val held = player.inventory.itemInMainHand
        if (event.blockFace == BlockFace.UP && !held.isEmpty && heads?.isTagged(held.type) == true) return
        event.setUseInteractedBlock(Event.Result.DENY)
        event.setUseItemInHand(Event.Result.DENY)
        data.note = org.bukkit.Note((data.note.id + 1) % NOTES)
        block.setBlockData(data, false)
        version.playNote(block)
    }

    /** A player's note block sounds with the instrument of what's around it; a custom block's state never sounds. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onNote(event: NotePlayEvent) {
        if (!frozen) return
        val data = event.block.blockData as? NoteBlock ?: return
        if (isPlayers(data)) event.instrument = version.noteInstrument(event.block) else event.isCancelled = true
    }

    /** The redstone's part, which the server does only unwatched: a signal arriving sounds a player's note block, and `powered` follows it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPhysics(event: BlockPhysicsEvent) {
        if (!frozen || event.block.type != Material.NOTE_BLOCK) return
        val block = event.block
        if (!looking.add(block)) return
        // The block's own state is read when the server has finished with it.
        plugin.server.scheduler.runTask(
            plugin,
            Runnable {
                looking.remove(block)
                if (block.type != Material.NOTE_BLOCK) return@Runnable
                val data = block.blockData as NoteBlock
                if (!isPlayers(data)) return@Runnable
                val powered = block.isBlockIndirectlyPowered
                if (powered == data.isPowered) return@Runnable
                data.isPowered = powered
                block.setBlockData(data, false)
                if (powered) version.playNote(block)
            }
        )
    }

    private companion object {
        /** A note block has 25 notes: 0 to 24. */
        const val NOTES = 25

        /** How often the server's setting is looked at again, in ticks: a `/paper reload` puts it back. */
        const val REASSERT = 20L
    }
}
