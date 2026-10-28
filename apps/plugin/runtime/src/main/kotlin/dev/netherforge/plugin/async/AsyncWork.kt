package dev.netherforge.plugin.async

import dev.netherforge.plugin.RuntimeLog
import dev.netherforge.plugin.lua.Coded
import dev.netherforge.plugin.lua.LuaCodec
import dev.netherforge.plugin.lua.LuaRef
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.Scripts
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.TickPhase
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor

/**
 * The session's side of async work: how a result that arrives off the main
 * thread gets back to it, and to the script that waited.
 *
 * An async API function (`async` in the spec) starts its work and hands back
 * a `CompletionStage`; the generated binding gives it to [await] with the
 * waker the prelude made (the callback, or the running task's). When the
 * stage completes, on whatever thread, the delivery is queued on
 * [Completions], tagged with this session and the scope, and runs in this
 * session's [TickPhase.ASYNC]: the waker is called, as the scope's code, with
 * `value, nil` or `nil, err`. A wait is cancelled (its waker let go of, nothing
 * delivered) when its scope is released, when its task ends (`Task:cancel`,
 * through the `async.cancel` primitive), and when the session stops. The
 * work itself isn't stopped: what it did to the world stands.
 *
 * Services carry on with their own work on [mainThread] the same way: on the
 * main thread in [TickPhase.ASYNC], while this session runs, and never once it
 * has ended.
 */
class AsyncWork internal constructor(
    /** This session's generation, which every entry it queues carries. */
    private val generation: Int,
    private val completions: Completions,
    /** The runtime's pool, where services start the work. */
    val workers: Workers,
    private val scripts: Scripts,
    private val log: RuntimeLog
) : RuntimeService {
    override val name get() = "async work"

    /** Where a service carries on once its work is done: the main thread, in [TickPhase.ASYNC], while this session runs. */
    val mainThread: Executor = completions.executor(generation, null)

    /**
     * Runs [work] on a worker ([on]: the pool or one of its lanes), as
     * [Workers.submit] does, with the stage completed on [mainThread]: what's
     * added to it runs in the [TickPhase.ASYNC] after the work finished, never
     * sooner because the work was quick.
     */
    fun <T> submit(on: Executor = workers, work: () -> T): CompletableFuture<T> = workers.submit(on, mainThread, work)

    private val waiting = LinkedHashMap<Int, Pending<*>>()
    private var nextId = 1

    /**
     * Calls [waker] as [scope]'s code with [result]'s `value, err` once it's
     * done, on the main thread in a later [TickPhase.ASYNC] (never during the
     * call that started it). The wait's id, for [cancel].
     */
    fun <T> await(what: String, scope: Scope, result: CompletionStage<T>, waker: LuaRef, codec: LuaCodec<T>): Int {
        val pending = Pending(nextId++, scope, waker, codec, what)
        waiting[pending.id] = pending
        result.whenCompleteAsync({ value, failure -> deliver(pending, value, failure) }, completions.executor(generation, scope))
        return pending.id
    }

    private fun <T> deliver(pending: Pending<T>, value: T?, failure: Throwable?) {
        // Cancelled meanwhile: its waker was let go of then.
        if (waiting.remove(pending.id) == null) return
        val args = if (failure == null) {
            @Suppress("UNCHECKED_CAST")
            listOf(Coded(value as T, pending.codec), null)
        } else {
            listOf(null, explain(pending, failure))
        }
        scripts.callBack(pending.scope, pending.waker, args, "${pending.what} callback")
    }

    /** What a script is told went wrong: a [WorkFailed]'s words; anything else is a bug, logged, and only said to have happened. */
    private fun explain(pending: Pending<*>, failure: Throwable): String {
        val cause = if (failure is CompletionException) failure.cause ?: failure else failure
        if (cause is WorkFailed) return cause.message.orEmpty()
        log.error("${pending.what} failed in ${pending.scope.owner.label}", cause)
        return "it failed unexpectedly (the server's log says why)"
    }

    /** Stops waiting [id] (its task ended): its waker is let go of, and nothing is delivered. Nothing when it's done already. */
    fun cancel(id: Int) {
        waiting.remove(id)?.let { scripts.unref(it.waker) }
    }

    /** How many waits [scope] has, for `/nf scripts`. */
    fun waits(scope: Scope): Int = waiting.values.count { it.scope === scope }

    override fun tick(phase: TickPhase) {
        if (phase != TickPhase.ASYNC) return
        completions.drain(generation) { entry ->
            try {
                entry.run.run()
            } catch (e: Exception) {
                log.error("NetherForge couldn't carry on with work that finished off the main thread", e)
            }
        }
    }

    override fun scopeReleased(scope: Scope) {
        val gone = waiting.values.filter { it.scope === scope }
        for (pending in gone) cancel(pending.id)
    }

    override fun stop() {
        for (pending in waiting.values) scripts.unref(pending.waker)
        waiting.clear()
    }

    override fun costs(): Map<String, (Scope) -> Int> = mapOf("waits" to ::waits)
}
