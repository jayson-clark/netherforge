package dev.netherforge.format.game

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink

/**
 * A game feature NetherForge uses that arrived after
 * [MinecraftVersion.OLDEST_SUPPORTED]: a project targeting an older version
 * can't use it. [id] is how validators and the Lua API's spec (`since`) name
 * it; [what] says it in a problem's words ("Dialogs with a pause-menu entry").
 */
data class Feature(val id: String, val since: MinecraftVersion, val what: String) {
    constructor(id: String, since: String, what: String) : this(id, MinecraftVersion.of(since), what)
}

/**
 * Which version each gated feature arrived in. The table is data: gating a
 * feature is a line in [CURRENT] (see the minecraft-versions skill). Code
 * asks a table it's handed, [CURRENT] by default, so a test can hand it one
 * of its own, since the real one may hold nothing a test could rely on.
 */
class FeatureTable(features: List<Feature>) {
    private val byId: Map<String, Feature> = features.associateBy { it.id }

    init {
        require(byId.size == features.size) { "Two features share an id: ${features.map { it.id }}" }
    }

    val all: List<Feature> = features.sortedBy { it.id }

    /** The feature [id], which must be in the table: an unknown id is a bug in NetherForge, not in a project. */
    operator fun get(id: String): Feature = byId[id] ?: throw IllegalArgumentException("No feature \"$id\" in the feature table")

    /** Whether a project targeting [target] can use feature [id]. A target that isn't a version isn't held against anything. */
    fun has(target: String?, id: String): Boolean {
        val version = target?.let(MinecraftVersion::parse) ?: return true
        return version >= get(id).since
    }

    /**
     * Reports using feature [id] at [path] when [target] lacks it: what a
     * validator calls where a file uses it.
     */
    fun require(target: String?, id: String, sink: ProblemSink, path: String? = null) {
        if (has(target, id)) return
        val feature = get(id)
        sink.report(
            ProblemCodes.PROJECT_FEATURE,
            "${feature.what} needs Minecraft ${feature.since}; this project targets $target",
            path
        )
    }

    companion object {
        /**
         * Every feature NetherForge uses that the oldest supported version
         * lacks. Where a version differs only in how (the world's files, a
         * packet), its adapter does it its own way and nothing is gated.
         */
        val CURRENT = FeatureTable(
            listOf(
                // A sky held through the night is a timeline on a world clock of its own: clocks are 26.1's.
                Feature("sky-night", "26.1", "A sky that doesn't darken at night")
            )
        )
    }
}
