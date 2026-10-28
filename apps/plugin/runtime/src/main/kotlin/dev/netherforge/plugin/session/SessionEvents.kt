package dev.netherforge.plugin.session

import dev.netherforge.plugin.binding.EventType
import dev.netherforge.plugin.binding.LuaEvent
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.LuaLocation
import dev.netherforge.plugin.binding.blockHandle
import dev.netherforge.plugin.platform.BlockRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.PlayerRef

// What raising the server's events to a session's scripts takes: the handles
// they're about and the paths they go along.

internal fun ProjectSession.handle(player: PlayerRef) = LuaHandle.Player(player.uuid.toString())

/** A player's event: their own handlers (`player:on`), then `nf`'s, with one event between them. */
internal fun <P : LuaEvent> ProjectSession.playerEvent(own: EventType<P>, nf: EventType<P>, player: PlayerRef, payload: P): Boolean =
    scripts.emit(listOf(own to handle(player), nf to null), payload)

/** Whether anything listens for a player's event, on that player or on `nf`: whether building its payload is worth it. */
internal fun ProjectSession.listening(own: EventType<*>, nf: EventType<*>, player: PlayerRef) =
    scripts.listening(handle(player), own) || scripts.listening(null, nf)

internal fun ProjectSession.block(block: BlockRef) = blockHandle(block.world, block.x, block.y, block.z)

/**
 * Whether a list of items a handler may have written differs from what it
 * was given. A stack with no count is one item, the same as Lua reads it back.
 */
@Suppress("UnusedReceiverParameter")
internal fun ProjectSession.changed(after: List<ItemData>, before: List<ItemData>): Boolean {
    fun ItemData.counted() = if (def.count == null) copy(def = def.copy(count = 1)) else this
    return after.map { it.counted() } != before.map { it.counted() }
}

/**
 * Where a location a script wrote points, on the server: [was] when it
 * names a world the server doesn't have, and [was]'s facing where it has none.
 */
internal fun ProjectSession.placed(location: LuaLocation, was: Location): Location {
    if (!platform.worlds.exists(location.world.name)) return was
    return Location(
        location.world.name,
        location.position.x,
        location.position.y,
        location.position.z,
        location.yaw ?: was.yaw,
        location.pitch ?: was.pitch
    )
}
