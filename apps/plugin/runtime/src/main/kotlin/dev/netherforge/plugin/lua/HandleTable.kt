package dev.netherforge.plugin.lua

import dev.netherforge.plugin.binding.LuaHandle

/**
 * Every handle Lua holds, by its key: the integer a script's handle is in Lua.
 * Lua never sees what a handle is made of (a UUID, a node's name), only this
 * key, so a script can't read or forge one; Kotlin finds the [LuaHandle]
 * here. One thing has one key whatever class it is (a zombie's handle is the
 * same entry as a `Mob`, a `Living` or an `Entity`), and its entry holds the
 * most specific class the runtime has said it is.
 *
 * An entry lives while Lua holds a table for it: the prelude's handle cache
 * is weak, and a collected table's key comes back through
 * [LuaHost.sweepHandles], which [release]s it. A key is never used twice, so
 * a stale one finds nothing rather than something else.
 */
class HandleTable {
    /** One handle and the class the Lua table for it was last given, if one was. */
    class Entry(val key: Long, var handle: LuaHandle) {
        /** The class whose metatable the Lua table has, or null when there's no table yet. */
        internal var shown: String? = null
    }

    private val byHandle = HashMap<LuaHandle, Entry>()

    /** Told of each handle Lua let go of: what's kept for it alone (an image's pixels) can go. */
    var onRelease: ((LuaHandle) -> Unit)? = null
    private val byKey = HashMap<Long, Entry>()
    private var next = 1L

    /** How many handles Lua holds. */
    val size: Int get() = byKey.size

    /**
     * The entry for [handle], made if there's none. A handle of a more specific
     * class than the entry's (a `Mob` for what was an `Entity`) becomes the
     * entry's: the class of a thing never changes, so a less specific one only
     * means whoever made it knew less.
     */
    fun intern(handle: LuaHandle): Entry {
        val entry = byHandle[handle]
        if (entry == null) {
            val made = Entry(next++, handle)
            byHandle[handle] = made
            byKey[made.key] = made
            return made
        }
        if (moreSpecific(handle, entry.handle)) {
            // Equal handles, so the map's key stays; the entry holds the better one.
            entry.handle = handle
        }
        return entry
    }

    /** The entry for [handle] if Lua holds one, without making one. */
    fun find(handle: LuaHandle): Entry? = byHandle[handle]

    /** The entry with [key], or null for a key Lua no longer holds (or never did). */
    operator fun get(key: Long): Entry? = byKey[key]

    /** Forgets [key]: Lua let go of its table. */
    fun release(key: Long) {
        val entry = byKey.remove(key) ?: return
        byHandle.remove(entry.handle)
        onRelease?.invoke(entry.handle)
    }

    private fun moreSpecific(candidate: LuaHandle, current: LuaHandle) =
        candidate.javaClass != current.javaClass && current.javaClass.isInstance(candidate)
}
