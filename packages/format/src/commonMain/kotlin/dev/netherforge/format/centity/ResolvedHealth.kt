package dev.netherforge.format.centity

/** A [HealthDef] as it runs, every field filled in; [bar] null without one. */
data class ResolvedHealth(val max: Double, val removeOnDeath: Boolean, val bar: Bar?) {
    data class Bar(val segments: Int, val showTicks: Int, val offset: Double, val glyph: String, val filled: String, val empty: String)

    companion object {
        const val DEFAULT_SEGMENTS = 10
        const val MAX_SEGMENTS = 40
        const val DEFAULT_SHOW_TICKS = 100
        const val DEFAULT_OFFSET = 0.35
        const val DEFAULT_GLYPH = "▮"
        const val DEFAULT_FILLED = "<red>"
        const val DEFAULT_EMPTY = "<dark_gray>"

        /** The node the bar is drawn by: the file's own text node of this name, or one added for it. */
        const val BAR_NODE = "health_bar"

        fun of(def: HealthDef) = ResolvedHealth(
            max = def.max.coerceAtLeast(MIN_MAX),
            removeOnDeath = def.removeOnDeath ?: true,
            bar = def.bar?.let { bar ->
                Bar(
                    segments = (bar.segments ?: DEFAULT_SEGMENTS).coerceIn(1, MAX_SEGMENTS),
                    showTicks = (bar.showTicks ?: DEFAULT_SHOW_TICKS).coerceAtLeast(0),
                    offset = bar.offset ?: DEFAULT_OFFSET,
                    glyph = bar.glyph ?: DEFAULT_GLYPH,
                    filled = bar.filled ?: DEFAULT_FILLED,
                    empty = bar.empty ?: DEFAULT_EMPTY
                )
            }
        )

        private const val MIN_MAX = 1e-6
    }
}
