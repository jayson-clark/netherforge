package dev.netherforge.format.game

/**
 * A Minecraft release number: `1.21.11`, `26.3`, `26.3.1`.
 *
 * Compared part by part as integers, with missing parts reading as zero, so
 * `26.3` and `26.3.0` are equal and both sort after `1.21.11`. Pre-releases and
 * snapshots aren't targets a project can name.
 */
class MinecraftVersion private constructor(val parts: List<Int>) : Comparable<MinecraftVersion> {

    override fun compareTo(other: MinecraftVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val difference = parts.getOrElse(i) { 0 } - other.parts.getOrElse(i) { 0 }
            if (difference != 0) return difference
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is MinecraftVersion && compareTo(other) == 0

    override fun hashCode(): Int = parts.dropLastWhile { it == 0 }.hashCode()

    override fun toString(): String = parts.joinToString(".")

    companion object {
        private val PATTERN = Regex("""^\d+(\.\d+){1,2}$""")

        fun parse(text: String): MinecraftVersion? = if (PATTERN.matches(text.trim())) {
            MinecraftVersion(text.trim().split('.').map { it.toInt() })
        } else {
            null
        }

        fun of(text: String): MinecraftVersion = parse(text) ?: throw IllegalArgumentException("Not a Minecraft version: \"$text\"")

        /**
         * The oldest Minecraft version a NetherForge project may target: the
         * last 1.21.x release. What NetherForge uses that it lacks is in
         * [FeatureTable] (see the minecraft-versions skill).
         */
        const val OLDEST_SUPPORTED = "1.21.11"
    }
}
