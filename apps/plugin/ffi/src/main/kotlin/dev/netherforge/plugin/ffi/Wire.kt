package dev.netherforge.plugin.ffi

import java.util.UUID

/**
 * Where a call's arguments or answer are written, as the Rust side reads them (the rust-runtime
 * skill's "The FFM boundary" has the layout): little-endian numbers, UTF-8 strings after their
 * byte count, a byte before an optional value, a count before a list's items.
 */
class Writer {
    var bytes = ByteArray(256)
        private set
    var size = 0
        private set

    fun clear() {
        size = 0
    }

    private fun room(n: Int) {
        if (size + n > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + n))
    }

    fun byte(v: Byte) {
        room(1)
        bytes[size++] = v
    }

    fun bool(v: Boolean) = byte(if (v) 1 else 0)

    fun short(v: Short) {
        room(2)
        val n = v.toInt()
        bytes[size++] = n.toByte()
        bytes[size++] = (n shr 8).toByte()
    }

    fun int(v: Int) {
        room(4)
        for (k in 0 until 4) bytes[size++] = (v shr (8 * k)).toByte()
    }

    fun long(v: Long) {
        room(8)
        for (k in 0 until 8) bytes[size++] = (v shr (8 * k)).toByte()
    }

    fun float(v: Float) = int(v.toRawBits())

    fun double(v: Double) = long(v.toRawBits())

    /** A count, as a list or a string is preceded by. */
    fun count(n: Int) = int(n)

    fun raw(v: ByteArray) {
        room(v.size)
        v.copyInto(bytes, size)
        size += v.size
    }

    fun string(v: String) {
        val utf8 = v.toByteArray(Charsets.UTF_8)
        count(utf8.size)
        raw(utf8)
    }

    fun uuid(v: UUID) {
        long(v.mostSignificantBits)
        long(v.leastSignificantBits)
    }

    fun bytes(v: ByteArray) {
        count(v.size)
        raw(v)
    }

    fun ints(v: IntArray) {
        count(v.size)
        v.forEach(::int)
    }

    fun shorts(v: ShortArray) {
        count(v.size)
        v.forEach(::short)
    }

    fun bools(v: BooleanArray) {
        count(v.size)
        v.forEach(::bool)
    }

    inline fun <T : Any> opt(v: T?, put: (T) -> Unit) {
        if (v == null) {
            bool(false)
        } else {
            bool(true)
            put(v)
        }
    }

    inline fun <T> list(v: Collection<T>, put: (T) -> Unit) {
        count(v.size)
        for (x in v) put(x)
    }

    inline fun <K, V> map(v: Map<K, V>, key: (K) -> Unit, value: (V) -> Unit) {
        count(v.size)
        for ((k, x) in v) {
            key(k)
            value(x)
        }
    }

    inline fun <T> result(v: Result<T>, put: (T) -> Unit) {
        v.fold({
            bool(false)
            put(it)
        }, {
            byte(1)
            error(it)
        })
    }

    /** An error: its message, and its trace as its cause's text. */
    fun error(v: Throwable) {
        string(v.message ?: v.javaClass.name)
        opt(if (v is NativeException) v.causeText else v.stackTraceToString()) { string(it) }
    }

    /** A JSON-like value: a tag (0 null, 1 boolean, 2 number, 3 string, 4 list, 5 map) and what it holds. */
    fun dynamic(v: Any?) {
        when (v) {
            null -> byte(0)
            is Boolean -> {
                byte(1)
                bool(v)
            }
            is Number -> {
                byte(2)
                double(v.toDouble())
            }
            is String -> {
                byte(3)
                string(v)
            }
            is Collection<*> -> {
                byte(4)
                list(v) { dynamic(it) }
            }
            is Map<*, *> -> {
                byte(5)
                map(v, { string(it.toString()) }) { dynamic(it) }
            }
            else -> {
                byte(3)
                string(v.toString())
            }
        }
    }

    /** A reference to one of the JVM's objects ([Objects.export]). */
    fun java(id: Long) {
        byte(JAVA)
        long(id)
    }

    /** A reference to one of Rust's objects, going back. */
    fun rust(id: Long) {
        byte(RUST)
        long(id)
    }

    companion object {
        const val JAVA: Byte = 0
        const val RUST: Byte = 1
    }
}

/** Where a call's arguments or answer are read from. A malformed buffer is a bug in the glue. */
class Reader(private val bytes: ByteArray, private var at: Int = 0, private val end: Int = bytes.size) {
    fun done() = at == end

    private fun need(n: Int) {
        if (at + n > end) throw IllegalStateException("the wire ended early: $n bytes wanted at $at of $end")
    }

    fun byte(): Byte {
        need(1)
        return bytes[at++]
    }

    fun bool() = byte().toInt() != 0

    fun short(): Short {
        need(2)
        val v = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        at += 2
        return v.toShort()
    }

    fun int(): Int {
        need(4)
        var v = 0
        for (k in 0 until 4) v = v or ((bytes[at + k].toInt() and 0xFF) shl (8 * k))
        at += 4
        return v
    }

    fun long(): Long {
        need(8)
        var v = 0L
        for (k in 0 until 8) v = v or ((bytes[at + k].toLong() and 0xFF) shl (8 * k))
        at += 8
        return v
    }

    fun float() = Float.fromBits(int())

    fun double() = Double.fromBits(long())

    fun count(): Int = int().also { if (it < 0) throw IllegalStateException("a count of $it") }

    fun raw(n: Int): ByteArray {
        need(n)
        return bytes.copyOfRange(at, at + n).also { at += n }
    }

    fun string(): String {
        val n = count()
        need(n)
        return String(bytes, at, n, Charsets.UTF_8).also { at += n }
    }

    fun uuid() = UUID(long(), long())

    fun bytes() = raw(count())

    fun ints() = IntArray(count()) { int() }

    fun shorts() = ShortArray(count()) { short() }

    fun bools() = BooleanArray(count()) { bool() }

    inline fun <T> opt(take: () -> T): T? = if (bool()) take() else null

    inline fun <T> list(take: () -> T): List<T> {
        val n = count()
        val out = ArrayList<T>(n)
        repeat(n) { out.add(take()) }
        return out
    }

    inline fun <K, V> map(key: () -> K, value: () -> V): Map<K, V> {
        val n = count()
        val out = LinkedHashMap<K, V>(n)
        repeat(n) {
            val k = key()
            out[k] = value()
        }
        return out
    }

    inline fun <T> result(take: () -> T): Result<T> = if (!bool()) Result.success(take()) else Result.failure(error())

    fun error(): NativeException {
        val message = string()
        return NativeException(message, opt { string() })
    }

    fun <T> entry(entries: List<T>, what: String): T {
        val n = int()
        return entries.getOrNull(n) ?: throw IllegalStateException("$what has no entry $n")
    }

    /** A JSON-like value ([Writer.dynamic]); a whole number in Int's range is an Int, as game rules want. */
    fun dynamic(): Any? = when (val tag = byte().toInt()) {
        0 -> null
        1 -> bool()
        2 -> double().let { if (it == Math.rint(it) && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE) it.toInt() else it }
        3 -> string()
        4 -> list { dynamic() }
        5 -> map({ string() }) { dynamic() }
        else -> throw IllegalStateException("a dynamic value has no tag $tag")
    }

    /** A reference to one of the JVM's own objects, coming back. */
    fun java(): Long {
        val side = byte()
        if (side != Writer.JAVA) throw IllegalStateException("a reference to the JVM's object came as side $side")
        return long()
    }

    /** A reference to one of Rust's objects: its id. */
    fun rust(): Long {
        val side = byte()
        if (side != Writer.RUST) throw IllegalStateException("a reference to Rust's object came as side $side")
        return long()
    }
}

/** What went wrong on the Rust side: its message, and maybe its cause's text. */
class NativeException(message: String, val causeText: String? = null) : RuntimeException(message)
