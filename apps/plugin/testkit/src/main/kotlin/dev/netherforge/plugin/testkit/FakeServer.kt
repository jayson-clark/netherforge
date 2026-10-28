package dev.netherforge.plugin.testkit

import dev.netherforge.format.advancement.AdvancementKind
import dev.netherforge.format.datapack.GameDatapack
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.ref.ResourceKey
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.plugin.datapack.DatapackRefusal
import dev.netherforge.plugin.datapack.StartupDatapackFiles
import dev.netherforge.plugin.platform.DatapackOps
import dev.netherforge.plugin.platform.PauseOps
import dev.netherforge.plugin.platform.PerformanceOps
import dev.netherforge.plugin.platform.PlatformLog
import dev.netherforge.plugin.platform.TextOps
import dev.netherforge.plugin.project.ProjectFiles
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Path
import java.util.UUID

// The fake server's small parts: its scheduler, log, performance, text, start-up datapack and pause.

/** A paused server's holds, and what each player's action bar says (a bot's state shows it, as on Paper). */
class FakePause(private val platform: FakePlatform) : PauseOps {
    /** Every [hold]'s message, in order. Any thread may read it: a test watches it while the main thread is held. */
    val holds: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    val actionBars = java.util.concurrent.ConcurrentHashMap<UUID, String>()

    override fun hold(message: String) {
        holds += message
        for (player in platform.players.online()) actionBars[player.uuid] = message
    }
}

class FakePerformance : PerformanceOps {
    var ticksPerSecond = 20.0
    var tickMilliseconds = 12.5

    override fun ticksPerSecond() = ticksPerSecond

    override fun tickMilliseconds() = tickMilliseconds
}

/** MiniMessage's escaping, and a stripper that's good enough for tests: every tag goes, and an escaped one stays escaped, as MiniMessage's does. */
class FakeText : TextOps {
    override fun escape(text: String) = text.replace("\\", "\\\\").replace("<", "\\<")

    override fun strip(miniMessage: String) = miniMessage.replace(Regex("(?<!\\\\)<[^<>]*>"), "")
}

/**
 * The start-up datapack: [started] is what a test says the server
 * started with. Text is `{"text": …}` with the MiniMessage as written, a
 * glyph tag replaced by its character, which is what tests compare.
 */
class FakeDatapacks : DatapackOps {
    override var started: Map<String, ByteArray> = emptyMap()
    override var format: List<Int>? = listOf(94, 1)
    override var refused: DatapackRefusal? = null

    /** The refusal on record as the fake server starts: what the Paper adapter keeps in the plugin's folder. */
    var refusal: DatapackRefusal? = null

    /** The fake server's `level-name`: its main world, as its [FakeWorlds.defaultWorld]. */
    var level = "world"

    /** The main world the start-up datapack was built for: [level] once [bootstrap] has built one, as on Paper; null before. */
    override var mainWorld: String? = null

    /** The game's own data pack: one biome, the plains, as the game writes it, so a sky has a twin of it. */
    override var game: GameDatapack = GameDatapack(
        mapOf(
            ResourceKey("minecraft", "plains") to buildJsonObject {
                putJsonObject("attributes") { put("minecraft:visual/sky_color", "#78a7ff") }
                putJsonObject("effects") { put("water_color", "#3f76e4") }
                put("has_precipitation", true)
                put("temperature", 0.8)
                put("downfall", 0.4)
            }
        ),
        null
    )

    /** The project's advancements the fake server started with, by key: their criteria, and their requirement groups. */
    var advancements: Map<String, Pair<List<String>, List<List<String>>>> = emptyMap()

    /**
     * What the adapter does before the worlds load: builds the start-up
     * datapack from the project's files (as the Paper adapter builds it, with [StartupDatapackFiles])
     * and loads its advancements. Call it before the runtime enables, as a
     * server starting would.
     */
    fun bootstrap(project: Path, resolvesPackages: Boolean = true) {
        val source = ProjectFiles(project, resolvesPackages)
        val snapshot = source.load(null).snapshot
        mainWorld = format?.let { level }
        val start = format?.let { StartupDatapackFiles.forStart(snapshot, it, ::textJson, mainWorld, source::readBytes, refusal, game) }
        started = start?.files.orEmpty()
        refused = start?.refused
        advancements = if (started.isEmpty()) {
            emptyMap()
        } else {
            snapshot.running(AdvancementKind).entries.associate { (name, file) ->
                val key = ResourceRef(name).resolve(snapshot.namespace).toString()
                key to (file.criteria.keys.sorted() to (file.requirements ?: file.criteria.keys.sorted().map { listOf(it) }))
            }
        }
    }

    override fun textJson(text: String, glyph: (reference: String) -> String?): String {
        val drawn = Regex("<glyph:([^>]+)>").replace(text) { glyph(it.groupValues[1]).orEmpty() }
        return "{\"text\":${CanonicalJson.quote(drawn)}}"
    }
}

/** What the fake finishes on a later tick: run by [runPending], which `TestServer.tick` calls after the runtime's tick. */
class FakeScheduler {
    val pending = ArrayDeque<() -> Unit>()

    fun runOnMain(task: () -> Unit) {
        synchronized(pending) { pending.addLast(task) }
    }

    fun runPending() {
        while (true) {
            val task = synchronized(pending) { pending.removeFirstOrNull() } ?: return
            task()
        }
    }
}

class FakeLog : PlatformLog {
    val lines = mutableListOf<String>()

    override fun info(message: String) {
        lines += "INFO $message"
    }

    override fun warn(message: String) {
        lines += "WARN $message"
    }

    override fun error(message: String, cause: Throwable?) {
        lines += "ERROR $message${cause?.let { ": $it" }.orEmpty()}"
    }
}
