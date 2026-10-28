package dev.netherforge.plugin.async
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.lua.CallResult
import dev.netherforge.plugin.lua.WrongThread
import org.junit.jupiter.api.Timeout
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Threads: the Lua state answers only the thread that made it, the one
 * worker pool (its lanes keep order, scripts can't queue work without bound),
 * and the completion queue dropping what's no longer wanted.
 */
@Timeout(30)
class ThreadingTest {
    @Test
    fun `calling into Lua from another thread is a clear exception, and the state goes on working`() {
        TestServer(mapOf("modules/m/init.lua" to "log('started')")).use { server ->
            val host = server.runtime.session.scripts.host!!
            val scope = server.runtime.session.scripts.scopes().single()
            val failures = Collections.synchronizedList(mutableListOf<Throwable>())
            thread(name = "not the main thread") {
                for (call in listOf<() -> Unit>(
                    { host.runFile(scope.budget, scope.id, "modules/m/other.lua", "log('never')") },
                    { host.collectGarbage() },
                    { host.takeCosts(false) },
                    { host.close() }
                )) {
                    failures += runCatching(call).exceptionOrNull() ?: AssertionError("no exception")
                }
            }.join()
            assertEquals(4, failures.size)
            for (failure in failures) assertIs<WrongThread>(failure)
            assertEquals(
                "LuaHost.run_file was called on thread \"not the main thread\", but this Lua state belongs to thread " +
                    "\"${Thread.currentThread().name}\": Lua can't be used from two threads. Hand the result to the main thread " +
                    "(AsyncWork, Completions) and call Lua there.",
                failures.first().message
            )
            // Nothing reached the state: it runs on as before.
            assertIs<CallResult.Ok>(host.runFile(scope.budget, scope.id, "modules/m/other.lua", "log('still here')"))
            assertEquals(listOf("started", "still here"), server.logs)
        }
    }

    @Test
    fun `a lane runs its work one at a time, in order, on the pool`() {
        Workers(threads = 4).use { workers ->
            val lane = workers.lane("test")
            val seen = Collections.synchronizedList(mutableListOf<Int>())
            val running = java.util.concurrent.atomic.AtomicInteger()
            repeat(50) { k ->
                lane.execute {
                    check(running.incrementAndGet() == 1) { "two at once" }
                    Thread.sleep(1)
                    seen += k
                    running.decrementAndGet()
                }
            }
            assertTrue(workers.idle(10_000))
            assertEquals((0 until 50).toList(), seen)
        }
    }

    @Test
    fun `scripts' work is bounded, and a failure is the stage's`() {
        Workers(threads = 1, maxScriptWork = 2).use { workers ->
            val release = CountDownLatch(1)
            val first = workers.submit { release.await(10, TimeUnit.SECONDS) }
            val second = workers.submit { "second" }
            val third = workers.submit { "third" }
            val refused = assertFailsWith<ExecutionException> { third.get(1, TimeUnit.SECONDS) }
            assertIs<WorkFailed>(refused.cause)
            assertEquals("the server already has 2 pieces of scripts' work waiting; try again later", refused.cause!!.message)
            release.countDown()
            assertEquals(true, first.get(10, TimeUnit.SECONDS))
            assertEquals("second", second.get(10, TimeUnit.SECONDS))
            // Room again once those are done.
            assertEquals("again", workers.submit { "again" }.get(10, TimeUnit.SECONDS))
            val failing: CompletableFuture<Unit> = workers.submit { throw WorkFailed("disk full") }
            assertEquals("disk full", assertFailsWith<ExecutionException> { failing.get(10, TimeUnit.SECONDS) }.cause!!.message)
        }
    }

    @Test
    fun `completions run for their session only, and not for a released scope`() {
        TestServer(mapOf("modules/m/init.lua" to "-- nothing")).use { server ->
            val session = server.runtime.session
            val scope = session.scripts.scopes().single()
            val ran = mutableListOf<String>()
            val completions: Completions = server.runtime.completions
            completions.executor(session.generation, null).execute { ran += "session's" }
            completions.executor(session.generation, scope).execute { ran += "scope's" }
            completions.executor(session.generation - 1, null).execute { ran += "an ended session's" }
            // One queued from another thread lands the same way.
            thread { completions.executor(session.generation, null).execute { ran += "from a worker" } }.join()
            server.tick()
            assertEquals(listOf("session's", "scope's", "from a worker"), ran)

            completions.executor(session.generation, scope).execute { ran += "too late" }
            server.write("modules/m/init.lua", "-- changed")
            server.reload("modules/m/init.lua")
            server.tick()
            assertEquals(3, ran.size)
            assertEquals(0, completions.size)
        }
    }

    @Test
    fun `a drain carries on with its own chain, and leaves what a worker finishes meanwhile for the next`() {
        val completions = Completions()
        val ran = mutableListOf<String>()
        completions.executor(1, null).execute {
            ran += "first"
            // The next stage, queued by the draining thread: carried on with now.
            completions.executor(1, null).execute { ran += "its next stage" }
            // A worker finishing during the drain, however quickly: it waits for the next drain.
            thread { completions.executor(1, null).execute { ran += "worker's" } }.join()
        }
        completions.drain(1) { it.run.run() }
        assertEquals(listOf("first", "its next stage"), ran)
        completions.drain(1) { it.run.run() }
        assertEquals(listOf("first", "its next stage", "worker's"), ran)
    }

    @Test
    fun `work started in a drain lands in the next one, however quickly it finished`() {
        val completions = Completions()
        val mainThread = completions.executor(1, null)
        val ran = mutableListOf<String>()
        Workers(threads = 1).use { workers ->
            mainThread.execute {
                ran += "started"
                val work = workers.submit(completeOn = mainThread) { "done" }
                // Finished before its next step is added, as a quick worker's can be.
                assertTrue(workers.idle(10_000))
                work.thenAcceptAsync({ ran += it }, mainThread)
            }
            completions.drain(1) { it.run.run() }
            assertEquals(listOf("started"), ran)
            completions.drain(1) { it.run.run() }
            assertEquals(listOf("started", "done"), ran)
        }
    }
}
