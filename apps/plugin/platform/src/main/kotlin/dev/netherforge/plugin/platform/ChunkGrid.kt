package dev.netherforge.plugin.platform

/**
 * A small number (1 to 65535) for some of one chunk's positions, kept a
 * section at a time: a chunk full of them costs a few kilobytes a section,
 * not an object a block. x and z are the chunk's own (0 to 15), y the world's.
 */
class ChunkGrid {
    private val sections = HashMap<Int, ShortArray>()

    /** How many positions have a number. */
    var size = 0
        private set

    fun isEmpty() = size == 0

    /** The number at a position, 0 where there's none. */
    operator fun get(x: Int, y: Int, z: Int): Int = sections[y shr 4]?.get(index(x, y, z))?.toInt()?.and(0xFFFF) ?: 0

    /** Sets the number at a position (0 clears it), and returns what was there. */
    operator fun set(x: Int, y: Int, z: Int, value: Int): Int {
        require(value in 0..0xFFFF) { "A grid holds 0 to 65535, not $value" }
        val section = sections[y shr 4] ?: if (value == 0) return 0 else ShortArray(SECTION).also { sections[y shr 4] = it }
        val i = index(x, y, z)
        val before = section[i].toInt() and 0xFFFF
        section[i] = value.toShort()
        if (before == 0 && value != 0) size++
        if (before != 0 && value == 0) size--
        return before
    }

    /** Each position with a number, and its number. */
    fun forEach(each: (x: Int, y: Int, z: Int, value: Int) -> Unit) {
        for ((sy, section) in sections) {
            for (i in 0 until SECTION) {
                val value = section[i].toInt() and 0xFFFF
                if (value != 0) each(i and 15, (sy shl 4) or (i shr 8), (i shr 4) and 15, value)
            }
        }
    }

    private fun index(x: Int, y: Int, z: Int) = ((y and 15) shl 8) or ((z and 15) shl 4) or (x and 15)

    private companion object {
        const val SECTION = 16 * 16 * 16
    }
}
