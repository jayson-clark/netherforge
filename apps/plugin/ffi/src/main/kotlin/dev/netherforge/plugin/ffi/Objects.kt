package dev.netherforge.plugin.ffi

import java.util.IdentityHashMap

/**
 * The JVM's objects handed to Rust, by id: each with what runs a call to it by number. An object
 * handed over again gets the same id and is counted; Rust lets go of it once per time ([release]),
 * from any of its threads.
 */
object Objects {
    private class Exported(val target: Any, val dispatch: (Int, Reader, Writer) -> Unit) {
        var count = 0
    }

    private val lock = Any()
    private val byId = HashMap<Long, Exported>()
    private val ids = IdentityHashMap<Any, Long>()
    private var next = 1L

    /** Hands [target] over, called by [dispatch]: its id. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> export(target: T, dispatch: (T, Int, Reader, Writer) -> Unit): Long = synchronized(lock) {
        val id = ids.getOrPut(target) {
            val id = next++
            byId[id] = Exported(target) { method, r, w -> dispatch(target, method, r, w) }
            id
        }
        byId.getValue(id).count++
        id
    }

    /** Rust let go of [id] once. */
    fun release(id: Long) = synchronized(lock) {
        val e = byId[id] ?: return
        if (--e.count == 0) {
            byId.remove(id)
            ids.remove(e.target)
        }
    }

    /** What [id] is, for a reference coming back. */
    fun target(id: Long): Any = synchronized(lock) { byId[id]?.target } ?: throw IllegalStateException("the JVM has no object $id")

    /** Runs method [method] of object [id]. */
    fun call(id: Long, method: Int, r: Reader, w: Writer) {
        val e = synchronized(lock) { byId[id] } ?: throw IllegalStateException("the JVM has no object $id (Rust let go of it)")
        e.dispatch(method, r, w)
    }

    /** How many objects Rust holds (for tests). */
    fun count(): Int = synchronized(lock) { byId.size }
}
