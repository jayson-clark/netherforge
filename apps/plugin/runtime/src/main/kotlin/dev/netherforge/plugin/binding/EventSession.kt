package dev.netherforge.plugin.binding
import dev.netherforge.plugin.platform.BlockRef
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.StatusEffectData
import java.util.UUID

/**
 * What the generated dispatch ([GameEventDispatch]) asks of the session running now: whether
 * it runs scripts, who listens, raising an event along its path, and what each platform value
 * a payload holds is in Lua (and a writable one back again). The runtime implements it once
 * (`RuntimeGameEvents`), so every event's handles are made the same way.
 */
interface EventSession {
    /** Whether a project runs: while none does, every event goes through unchanged. */
    val running: Boolean

    /** Whether anything listens anywhere along [stages]: whether building the payload is worth it. */
    fun listening(stages: List<Pair<EventType<*>, LuaHandle?>>): Boolean

    /** Raises [payload] along [stages] (`Scripts.emit`); whether it ended cancelled. */
    fun <P : LuaEvent> emit(stages: List<Pair<EventType<out P>, LuaHandle?>>, payload: P): Boolean

    fun player(player: PlayerRef): LuaHandle.Player

    /** The entity [id], as the class it is (`Scripts.emit` leaves out a stage that isn't its). */
    fun entity(id: UUID): LuaHandle.Entity

    /** The entity [id], which the event says is living: as the class it is, or a `Living`. */
    fun living(id: UUID): LuaHandle.Living

    /** The entity [id], which the event says is a mob. */
    fun mob(id: UUID): LuaHandle.Mob

    fun block(block: BlockRef): LuaHandle.Block

    fun world(name: String): LuaHandle.World

    fun inventory(inventory: InventoryRef): LuaHandle.Inventory

    fun location(location: Location): LuaLocation

    fun statusEffect(effect: StatusEffectData): StatusEffect

    /** Where a location a handler wrote points on the server: [was] for a world it doesn't have, and [was]'s facing where it gives none. */
    fun placed(location: LuaLocation, was: Location): Location

    /** What a handler left in an item field: [before] itself when it's the same item (a stack with no count is one). */
    fun <T : ItemData?> item(after: T, before: T): T

    /** As [item], for a list of items. */
    fun items(after: List<ItemData>, before: List<ItemData>): List<ItemData>
}
