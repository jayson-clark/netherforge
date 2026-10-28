package dev.netherforge.plugin.datapack

import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.datapack.StartupDatapack
import dev.netherforge.format.project.ProjectManifest
import dev.netherforge.plugin.RuntimeLog
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.project.ProjectFiles

/**
 * Whether the server must restart to run the project as its files say: what
 * it learns only as it starts (the start-up datapack, [StartupDatapack]) is
 * built again from the files and compared with what it loaded. It lives as
 * long as the plugin, since what the server started with does.
 *
 * The datapack is built from the files alone, never with game data (the
 * adapter's start-up has none), so this loads the project again without it
 * rather than reusing the session's snapshot: an advancement only the game
 * data finds wrong is in the datapack all the same, and comparing against
 * the session's would ask for a restart that changes nothing.
 *
 * When the server refused the project's datapacks the last time and started
 * without them ([dev.netherforge.plugin.platform.DatapackOps.refused]), it's
 * built as the adapter built it then (without them while they're the same), and
 * the refusal is a problem of its own (`runtime.datapack`).
 */
class StartupDatapackCheck(private val platform: Platform, private val source: ProjectFiles, private val log: RuntimeLog) {
    /** Whether the last [check] found the server out of date. */
    var stale: Boolean = false
        private set

    /**
     * Builds the datapack from the project's files as they are and answers
     * whether it differs from what the server started with. A change is
     * logged once (a warning: the server runs what it started with until it
     * restarts).
     */
    fun check(): Boolean {
        val format = platform.datapacks.format
        if (format == null) {
            stale = false
            return false
        }
        val now = StartupDatapackFiles.forStart(
            source.load(null).snapshot,
            format,
            platform.datapacks::textJson,
            platform.datapacks.mainWorld,
            source::readBytes,
            platform.datapacks.refused,
            platform.datapacks.game
        )
        val was = stale
        stale = !StartupDatapackFiles.same(now.files, platform.datapacks.started)
        if (stale && !was) log.warn(MESSAGE)
        return stale
    }

    /** What the server refused as it started, as problems at the files it named: worked out once, as it doesn't change until a restart. */
    private val refusal: List<Problem> by lazy {
        val refused = platform.datapacks.refused ?: return@lazy emptyList()
        val format = platform.datapacks.format ?: return@lazy emptyList()
        refused.problems(source.load(null).snapshot, format)
    }

    /** `runtime.restart` while the server is out of date, and `runtime.datapack` while it runs without the project's datapacks. */
    fun problems(): List<Problem> =
        refusal + if (stale) listOf(ProblemCodes.RUNTIME_RESTART.at(ProjectManifest.FILE_NAME, MESSAGE)) else emptyList()

    private companion object {
        val MESSAGE =
            "${StartupDatapack.kinds.joinToString(", ") {
                it.folder
            }.replaceFirstChar(Char::uppercase)} changed since the server started: " +
                "it runs the ones it started with until it restarts"
    }
}
