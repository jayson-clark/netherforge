package dev.netherforge.plugin.wasm

import com.dylibso.chicory.runtime.ChicoryInterruptedException
import dev.netherforge.plugin.async.WorkFailed
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The threads WebAssembly runs on, a session's: modules are computation, so they get threads of their own rather
 * than the runtime's [dev.netherforge.plugin.async.Workers] (whose work is files and sockets, and which a long call
 * would hold up).
 *
 * - [calls]: [count] threads for compiling, instantiating, calls and runs, so at most [count] cores are ever busy
 *   with them. Each instance's calls go through a
 *   [dev.netherforge.plugin.async.SerialExecutor] over it, one at a time.
 * - [programs]: a thread for each started program while it runs (it blocks reading its input), bounded by the
 *   owner's `max-processes-total`, which [WasmModules] holds to before starting one.
 * - The watchdog: [limited] interrupts the thread running a call that's taken too long. Chicory's interpreter and
 *   its compiled code both check for an interrupt and stop with [ChicoryInterruptedException].
 *
 * Every thread is a daemon at the lowest priority, so where the OS honours it the server's own threads come first.
 */
class WasmThreads(count: Int) : AutoCloseable {
    private val numbers = AtomicInteger()

    private fun thread(name: String) = { work: Runnable ->
        Thread(work, "NetherForge wasm $name ${numbers.incrementAndGet()}").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    private val unfinished = AtomicInteger()

    private val pool = ThreadPoolExecutor(count, count, IDLE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue(), thread("call"))
        .apply { allowCoreThreadTimeOut(true) }

    /** Calls, runs, instantiating and compiling. Throws [RejectedExecutionException] once [close]d. */
    val calls: Executor = Executor { command ->
        unfinished.incrementAndGet()
        try {
            pool.execute {
                try {
                    command.run()
                } finally {
                    unfinished.decrementAndGet()
                }
            }
        } catch (e: RejectedExecutionException) {
            unfinished.decrementAndGet()
            throw e
        }
    }

    /** One thread per running program. */
    val programs: ExecutorService =
        ThreadPoolExecutor(0, Int.MAX_VALUE, IDLE_SECONDS, TimeUnit.SECONDS, SynchronousQueue(), thread("program"))

    private val watchdog = ScheduledThreadPoolExecutor(1, thread("watchdog")).apply { removeOnCancelPolicy = true }

    /** Whether nothing given to [calls] is running or waiting. */
    val idle: Boolean get() = unfinished.get() == 0

    /**
     * Runs [work] on this thread, interrupting it after [seconds]: a module stopped that way fails saying [why]
     * (`wasm.max-call-seconds`). The thread's interrupt is cleared after, so a pool thread is never left interrupted.
     */
    fun <T> limited(seconds: Int, why: () -> String, work: () -> T): T {
        val thread = Thread.currentThread()
        val lock = Any()
        var done = false
        var timedOut = false
        val alarm = watchdog.schedule(
            {
                synchronized(lock) {
                    if (!done) {
                        timedOut = true
                        thread.interrupt()
                    }
                }
            },
            seconds.toLong(),
            TimeUnit.SECONDS
        )
        try {
            return work()
        } catch (e: ChicoryInterruptedException) {
            if (synchronized(lock) { timedOut }) throw WorkFailed(why())
            throw e
        } finally {
            synchronized(lock) { done = true }
            alarm.cancel(false)
            Thread.interrupted()
        }
    }

    override fun close() {
        // Running modules are interrupted (their scopes ended them already); nothing is waited for.
        pool.shutdownNow()
        programs.shutdownNow()
        watchdog.shutdownNow()
    }

    private companion object {
        const val IDLE_SECONDS = 30L
    }
}

/**
 * The memory WebAssembly may hold across the server (`wasm.max-memory-total-mb`), in 64 KiB pages. An instance or
 * program reserves the most it may grow to as it starts and gives it back when it ends, so what's running can never
 * together grow past the limit, whatever each does. From any thread.
 */
class WasmMemoryBudget(private val pages: Long) {
    private val reserved = AtomicLong()

    /** Reserves [want] pages; false (nothing reserved) when that would go over the limit. */
    fun reserve(want: Long): Boolean {
        while (true) {
            val now = reserved.get()
            if (now + want > pages) return false
            if (reserved.compareAndSet(now, now + want)) return true
        }
    }

    fun release(pages: Long) {
        reserved.addAndGet(-pages)
    }

    /** What's reserved now, in pages. */
    val used: Long get() = reserved.get()
}
