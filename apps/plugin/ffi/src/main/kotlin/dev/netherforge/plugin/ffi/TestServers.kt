package dev.netherforge.plugin.ffi

import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlayerRef
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.file.Path
import java.util.UUID

/**
 * The fake servers a script test run makes, as the Rust runtime's test runner asks for them (`crates/ffi/src/testing.rs`):
 * one per test, so nothing a test does is there for the next. Written by hand on each side, the methods by number in
 * [dispatch]'s order.
 */
interface TestServers {
    /** A fresh fake server: its platform, and its id in the calls below. */
    fun start(): Pair<Platform, Long>

    /** Server [id]'s own part of a tick, after the runtime's: its scheduler, then the world's entities. */
    fun afterTick(id: Long)

    /** Joins a fake player named [name] to server [id], the join events raised: the one online by that name if there is one. */
    fun join(id: Long, name: String): PlayerRef?

    /** The stack [player]'s game was last sent for [slot] of their inventory (of their open window, with [menu]; their cursor without a slot). */
    fun shown(id: Long, player: UUID, slot: Int?, menu: Boolean): ItemData?

    /** Server [id] is done with. */
    fun close(id: Long)

    /** One line of the run's report. */
    fun print(line: String)

    companion object {
        internal fun dispatch(o: TestServers, method: Int, r: Reader, w: Writer) {
            when (method) {
                0 -> {
                    val (platform, id) = o.start()
                    putPlatform(w, platform)
                    w.long(id)
                }
                1 -> o.afterTick(r.long())
                2 -> {
                    val id = r.long()
                    val player = o.join(id, r.string())
                    w.opt(player) { putPlayerRef(w, it) }
                }
                3 -> {
                    val id = r.long()
                    val player = r.uuid()
                    val slot = r.opt { r.int() }
                    val stack = o.shown(id, player, slot, r.bool())
                    w.opt(stack) { putItemData(w, it) }
                }
                4 -> o.close(r.long())
                5 -> o.print(r.string())
                else -> throw IllegalArgumentException("TestServers has no method $method")
            }
        }
    }
}

/** Runs a project's script tests on the Rust runtime ([Native]'s library): the process's exit code. */
object NativeTests {
    private val run by lazy {
        Native.function(
            "nf_test_run",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
        )
    }

    /**
     * Runs [project]'s tests on the servers [servers] makes (the editor's [packageCache] for git packages), those whose
     * "file: name" contains [filter]; the report as JSON lines or text, and JUnit XML into [junit]. [scratch] is a
     * folder the run may keep its servers' files in. 0 when every test passed, 1 when one failed, 2 when none could run.
     */
    fun run(
        servers: TestServers,
        project: Path,
        packageCache: Path?,
        filter: String?,
        json: Boolean,
        junit: Path?,
        scratch: Path,
        version: String
    ): Int {
        val w = Writer()
        w.java(Objects.export(servers, TestServers::dispatch))
        putPath(w, project)
        w.opt(packageCache) { putPath(w, it) }
        w.opt(filter) { w.string(it) }
        w.bool(json)
        w.opt(junit) { putPath(w, it) }
        putPath(w, scratch)
        w.string(version)
        val out = java.lang.foreign.Arena.ofConfined().use { arena ->
            val args = arena.allocate(maxOf(1L, w.size.toLong()))
            MemorySegment.copy(w.bytes, 0, args, ValueLayout.JAVA_BYTE, 0, w.size)
            val len = arena.allocate(ValueLayout.JAVA_LONG)
            val at = run.invokeExact(args, w.size.toLong(), len) as MemorySegment
            at.reinterpret(len.get(ValueLayout.JAVA_LONG, 0)).toArray(ValueLayout.JAVA_BYTE)
        }
        val r = Reader(out)
        if (r.byte().toInt() != 0) throw r.error()
        return r.int()
    }
}
