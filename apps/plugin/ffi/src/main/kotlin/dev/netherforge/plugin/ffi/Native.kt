package dev.netherforge.plugin.ffi

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.ref.Cleaner
import java.nio.file.Path

/**
 * The Rust runtime's library, loaded once ([load]), and the calls across: [call] runs a method of
 * one of Rust's objects; Rust calls the JVM's through [javaCall]. A call's arguments and answer are
 * flat buffers (the wire: [Writer], [Reader]). Nothing is thrown across: a Rust panic comes back
 * as a failed answer ([NativeException]), and a Kotlin exception goes to Rust as one.
 */
object Native {
    /** The wire's version these files were generated with; the library must say the same. */
    const val WIRE_VERSION = 1

    private val linker = Linker.nativeLinker()
    private lateinit var lookup: SymbolLookup
    private lateinit var nfCall: MethodHandle
    private lateinit var nfRelease: MethodHandle

    @Volatile
    private var loaded: Path? = null

    private val cleaner = Cleaner.create()

    private val CALL = FunctionDescriptor.of(
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_LONG,
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_LONG,
        ValueLayout.ADDRESS
    )

    /** Loads the library at [library] (once; the same path again is nothing) and hands it the JVM's entry points. */
    @Synchronized
    fun load(library: Path) {
        loaded?.let {
            check(it == library) { "the Rust runtime is already loaded from $it" }
            return
        }
        // An absolute path: a relative one (the plugin folder, `plugins/NetherForge`) doesn't open.
        lookup = SymbolLookup.libraryLookup(library.toAbsolutePath(), Arena.global())
        nfCall = linker.downcallHandle(symbol("nf_call"), CALL)
        nfRelease = linker.downcallHandle(symbol("nf_release"), FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG))
        val here = MethodHandles.lookup()
        val call = linker.upcallStub(
            here.findStatic(
                Native::class.java,
                "javaCall",
                MethodType.methodType(
                    MemorySegment::class.java,
                    Long::class.java,
                    Int::class.java,
                    MemorySegment::class.java,
                    Long::class.java,
                    MemorySegment::class.java
                )
            ),
            CALL,
            Arena.global()
        )
        val release = linker.upcallStub(
            here.findStatic(Native::class.java, "javaRelease", MethodType.methodType(Void.TYPE, Long::class.java)),
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG),
            Arena.global()
        )
        val init = linker.downcallHandle(
            symbol("nf_init"),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        )
        val version = init.invokeExact(call, release) as Int
        check(version == WIRE_VERSION) { "the Rust runtime at $library speaks wire $version, this plugin $WIRE_VERSION" }
        loaded = library
    }

    /** One of the library's functions. */
    fun symbol(name: String): MemorySegment = lookup.find(name).orElseThrow { IllegalStateException("the Rust runtime has no $name") }

    /** A handle on one of the library's functions, for the entry points the glue doesn't generate (the runtime's start). */
    fun function(name: String, descriptor: FunctionDescriptor): MethodHandle = linker.downcallHandle(symbol(name), descriptor)

    /** A thread's buffers: where arguments go to Rust and answers to Rust's calls are kept. */
    private class Scratch {
        val arena: Arena = Arena.ofShared()
        var args: MemorySegment = arena.allocate(4096)
        var answer: MemorySegment = arena.allocate(4096)
        val outLen: MemorySegment = arena.allocate(ValueLayout.JAVA_LONG)
        val writer = Writer()

        fun args(w: Writer): MemorySegment {
            if (args.byteSize() < w.size) args = arena.allocate(maxOf(args.byteSize() * 2, w.size.toLong()))
            MemorySegment.copy(w.bytes, 0, args, ValueLayout.JAVA_BYTE, 0, w.size)
            return args
        }

        fun answer(w: Writer): MemorySegment {
            if (answer.byteSize() < w.size) answer = arena.allocate(maxOf(answer.byteSize() * 2, w.size.toLong()))
            MemorySegment.copy(w.bytes, 0, answer, ValueLayout.JAVA_BYTE, 0, w.size)
            return answer
        }
    }

    private val scratch = ThreadLocal.withInitial { Scratch() }

    /**
     * Calls method [method] of Rust's object [id]: [put] writes the arguments, [take] reads the
     * answer. A panic on the Rust side is thrown here as a [NativeException].
     */
    fun <T> call(id: Long, method: Int, put: (Writer) -> Unit, take: (Reader) -> T): T {
        val s = scratch.get()
        // The thread's writer is free again once its bytes are copied out, so a call made while writing isn't possible.
        val w = Writer()
        put(w)
        val args = s.args(w)
        val at = nfCall.invokeExact(id, method, args, w.size.toLong(), s.outLen) as MemorySegment
        val len = s.outLen.get(ValueLayout.JAVA_LONG, 0)
        val bytes = at.reinterpret(len).toArray(ValueLayout.JAVA_BYTE)
        val r = Reader(bytes)
        if (r.byte().toInt() != 0) throw r.error()
        return take(r)
    }

    /** Lets Rust's object [id] go once [proxy] is collected. */
    fun track(proxy: Any, id: Long) {
        cleaner.register(proxy) { nfRelease.invokeExact(id) }
    }

    /** Rust calls method [method] of the JVM's object [id]: the answer's place, its length written to [outLen]. */
    @JvmStatic
    fun javaCall(id: Long, method: Int, args: MemorySegment, len: Long, outLen: MemorySegment): MemorySegment {
        val s = scratch.get()
        val w = Writer()
        try {
            val r = Reader(if (len == 0L) ByteArray(0) else args.reinterpret(len).toArray(ValueLayout.JAVA_BYTE))
            w.byte(0)
            Objects.call(id, method, r, w)
        } catch (e: Throwable) {
            w.clear()
            w.byte(1)
            w.error(e)
        }
        val answer = s.answer(w)
        outLen.reinterpret(8).set(ValueLayout.JAVA_LONG, 0, w.size.toLong())
        return answer
    }

    /** Rust let go of the JVM's object [id]. */
    @JvmStatic
    fun javaRelease(id: Long) = Objects.release(id)
}

/** Calls method [method] of a Rust proxy's object with no arguments, for tests of what a bad call does. */
internal fun RustPlatformEvents.callForTest(method: Int) {
    val field = RustPlatformEvents::class.java.getDeclaredField("rustId").apply { isAccessible = true }
    Native.call(field.getLong(this), method, {}) { }
}
