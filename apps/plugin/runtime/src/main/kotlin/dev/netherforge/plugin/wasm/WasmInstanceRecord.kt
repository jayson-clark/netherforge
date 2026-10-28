package dev.netherforge.plugin.wasm

import com.dylibso.chicory.runtime.Instance
import dev.netherforge.plugin.async.SerialExecutor
import dev.netherforge.plugin.binding.TestWasmStub
import java.util.concurrent.RejectedExecutionException

/**
 * One instance a script made with `nf.wasm.instantiate`, its [owner] scope's: a [Live] Chicory instance, or, in a
 * test run, a [StandIn] the test's functions answer for.
 *
 * [closed] is read from any thread: the main thread closes one (a script, its scope going), and so does a live
 * one's own call when it traps or runs out of time.
 */
sealed class WasmInstanceRecord(
    val number: Long,
    val owner: Int,
    val namespace: String,
    /** The module, as the server names it. */
    val key: String
) {
    @Volatile var closed = false
        protected set

    /** Closes it, giving back what it holds to [budget]. Once only. */
    abstract fun close(budget: WasmMemoryBudget)

    /**
     * A Chicory instance of [module]: the memory it holds against the server's budget ([pages]), and its [lane],
     * which runs its calls one at a time.
     */
    class Live(
        number: Long,
        owner: Int,
        namespace: String,
        key: String,
        val module: CompiledModule,
        running: CompiledModule.Running,
        private val pages: Long,
        threads: WasmThreads
    ) : WasmInstanceRecord(number, owner, namespace, key) {
        @Volatile private var held: CompiledModule.Running? = running

        val instance: Instance get() = checkNotNull(held) { "the instance is closed" }.instance

        val lane = SerialExecutor(threads.calls)

        /** The thread running a call in it now, to interrupt when it's closed; set and cleared under the record's lock. */
        private var running: Thread? = null

        /**
         * Runs [call] on this thread as the instance's call: [close] interrupts it while it runs, and never after (the
         * pool thread goes on to other instances' calls), an interrupt that came as it finished cleared.
         */
        fun <T> running(call: () -> T): T {
            synchronized(this) { running = Thread.currentThread() }
            try {
                return call()
            } finally {
                synchronized(this) { running = null }
                Thread.interrupted()
            }
        }

        /**
         * A call running now is interrupted, and once it has stopped (after it on the lane) what the instance holds is
         * let go of and its memory given back to [budget].
         */
        override fun close(budget: WasmMemoryBudget) {
            synchronized(this) {
                if (closed) return
                closed = true
                running?.takeIf { it !== Thread.currentThread() }?.interrupt()
            }
            val release = Runnable {
                held?.close()
                // The record stays (its module still answers); the instance and its memory go.
                budget.release(pages)
                held = null
            }
            try {
                lane.execute(release)
            } catch (_: RejectedExecutionException) {
                // The threads are gone (the session stopped): nothing runs in it any more.
                release.run()
            }
        }
    }

    /** What stands in for an instance of [key] in a test run, answered by [stub]'s functions. It holds nothing. */
    class StandIn(number: Long, owner: Int, namespace: String, key: String, val stub: TestWasmStub) :
        WasmInstanceRecord(number, owner, namespace, key) {
        override fun close(budget: WasmMemoryBudget) {
            closed = true
        }
    }
}
