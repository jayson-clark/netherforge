package dev.netherforge.plugin.async

import dev.netherforge.plugin.script.Scope
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor

/**
 * What finished off the main thread, waiting to be carried on with on it:
 * one queue for the plugin's life, which the running session drains once a
 * tick, in [dev.netherforge.plugin.session.TickPhase.ASYNC] ([AsyncWork]).
 *
 * Every entry is tagged with the session that queued it (its generation) and
 * the scope it's for, null for the session's own (a service's next step, like
 * loading a copied world). An entry from a session that has ended is dropped
 * unrun, and so is one whose scope was released meanwhile: neither has
 * anything left to call into.
 */
class Completions {
    /** One thing to run on the main thread for session [generation], and [scope] when it's a script's. */
    class Entry(val generation: Int, val scope: Scope?, val run: Runnable)

    private val queue = ConcurrentLinkedQueue<Entry>()

    /** The thread running [drain], and what it queues for itself meanwhile (only that thread touches [chained]). */
    @Volatile
    private var draining: Thread? = null
    private val chained = ArrayDeque<Entry>()

    /**
     * Queues work for session [generation] (and [scope], when it's a script's); safe from any thread.
     * What the draining thread queues itself (the next stage of what it's carrying on with) is run
     * in that same drain; what any other thread queues waits for the next one.
     */
    fun executor(generation: Int, scope: Scope?): Executor = Executor {
        val entry = Entry(generation, scope, it)
        if (Thread.currentThread() === draining) chained.addLast(entry) else queue.add(entry)
    }

    /**
     * Runs, on the main thread, what was queued for session [generation]
     * before now, each through [run], and the steps it chains (a copy loaded,
     * then the script told) in the same drain. Work that finishes on another
     * thread while this runs waits for the next drain, whatever the speed of
     * that thread: when work lands depends on the tick it was finished before,
     * never on a race. Drops what was queued for another session or a
     * released scope.
     */
    fun drain(generation: Int, run: (Entry) -> Unit) {
        draining = Thread.currentThread()
        try {
            repeat(queue.size) {
                val entry = queue.poll() ?: return@repeat
                runWithChain(entry, generation, run)
            }
        } finally {
            draining = null
            chained.clear()
        }
    }

    private fun runWithChain(first: Entry, generation: Int, run: (Entry) -> Unit) {
        chained.addLast(first)
        while (true) {
            val entry = chained.removeFirstOrNull() ?: return
            if (entry.generation != generation) continue
            if (entry.scope?.released == true) continue
            run(entry)
        }
    }

    /** How many are waiting, for tests. */
    val size: Int get() = queue.size
}

/**
 * Work the runtime's own threads (the dev bridge's) hand to the main thread,
 * run at the start of every tick, before the session's phases: a request
 * that reloads the whole project replaces the session, so it must never run
 * inside one session's tick. It belongs to no session or scope: it's the
 * plugin's.
 */
class MainThread : Executor {
    private val queue = ConcurrentLinkedQueue<Runnable>()

    /** Queues [command] for the next tick; safe from any thread. */
    override fun execute(command: Runnable) {
        queue.add(command)
    }

    /** Runs what was queued before now, each on its own: one that throws is passed to [failed] and the rest still run. */
    fun run(failed: (Throwable) -> Unit) {
        repeat(queue.size) {
            val next = queue.poll() ?: return
            try {
                next.run()
            } catch (e: Exception) {
                failed(e)
            }
        }
    }
}
