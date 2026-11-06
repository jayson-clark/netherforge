package dev.netherforge.plugin.ffi

import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlatformEvents
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.file.Path

/**
 * The Rust runtime on [platform], as the plugin runs it: [start] reads `config.yml`'s values and
 * starts the project (Kotlin's `NetherForgeRuntime.enable`), the server calls the [events] it
 * answers with (bound into the platform too), and [stop] ends it. The library is [Native]'s.
 */
class NativeRuntime(private val platform: Platform) {
    /** The runtime's events, once started with a project to run. */
    var events: PlatformEvents? = null
        private set

    private val start by lazy {
        Native.function(
            "nf_start",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
        )
    }
    private val stop by lazy { Native.function("nf_stop", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)) }

    /**
     * Starts the project [settings] (`config.yml`'s values) and the JVM's [properties] and [environment] say runs,
     * from [pluginFolder] on the server in [serverFolder] at [serverIp]: false when there's none. A value that can't
     * be acted on is thrown as the runtime words it.
     */
    fun start(
        pluginFolder: Path,
        serverFolder: Path,
        serverIp: String,
        settings: Map<String, Any?>,
        properties: Map<String, String> = System.getProperties().stringPropertyNames().associateWith { System.getProperty(it) },
        environment: Map<String, String> = System.getenv()
    ): Boolean {
        val w = Writer()
        putPlatform(w, platform)
        putPath(w, pluginFolder)
        putPath(w, serverFolder)
        w.string(serverIp)
        w.dynamic(settings)
        w.map(properties, { w.string(it) }) { w.string(it) }
        w.map(environment, { w.string(it) }) { w.string(it) }
        events = answer { at, out -> start.invokeExact(at, w.size.toLong(), out) as MemorySegment }
            .let { bytes -> read(bytes, w) { r -> r.opt { takePlatformEvents(r) } } }
        return events != null
    }

    /** One server tick. */
    fun tick() {
        events?.tick()
    }

    /** Stops the project: its services, what it was writing, the dev bridge. */
    fun stop() {
        if (events == null) return
        events = null
        val out = java.lang.foreign.Arena.ofConfined().use { arena ->
            val len = arena.allocate(ValueLayout.JAVA_LONG)
            val at = stop.invokeExact(len) as MemorySegment
            at.reinterpret(len.get(ValueLayout.JAVA_LONG, 0)).toArray(ValueLayout.JAVA_BYTE)
        }
        val r = Reader(out)
        if (r.byte().toInt() != 0) throw r.error()
    }

    private fun answer(call: (MemorySegment, MemorySegment) -> MemorySegment): (Writer) -> ByteArray = { w ->
        java.lang.foreign.Arena.ofConfined().use { arena ->
            val args = arena.allocate(maxOf(1L, w.size.toLong()))
            MemorySegment.copy(w.bytes, 0, args, ValueLayout.JAVA_BYTE, 0, w.size)
            val len = arena.allocate(ValueLayout.JAVA_LONG)
            val at = call(args, len)
            at.reinterpret(len.get(ValueLayout.JAVA_LONG, 0)).toArray(ValueLayout.JAVA_BYTE)
        }
    }

    private fun <T> read(call: (Writer) -> ByteArray, w: Writer, take: (Reader) -> T): T {
        val r = Reader(call(w))
        if (r.byte().toInt() != 0) throw r.error()
        return take(r)
    }
}
