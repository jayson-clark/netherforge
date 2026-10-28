package dev.netherforge.plugin.terrain
import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.project.PackagePaths
import dev.netherforge.format.terrain.TerrainScriptFailure
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * What the terrains' scripts failed at. A script fails on the server's chunk threads (format's
 * `TerrainScripts.failed`, called as often as a call fails: the same script fails the same way at the same place
 * every time), and is said on the main thread ([drain]): each different failure of a generator once, a warning in
 * the log and a `terrain.script-failed` problem at the file and line it names, until the generator is published
 * again ([restart]). What failed was the file's own result, so nothing else is needed.
 *
 * A generator keeps at most [MAX_PER_GENERATOR] failures: a script whose message says where it failed fails
 * differently in every chunk, and its first few say what's wrong.
 */
internal class TerrainScriptFailures {
    private class Heard(val generation: Int, val id: String, val failure: TerrainScriptFailure)

    @Volatile private var generation = 0
    private val seen = ConcurrentHashMap<String, MutableSet<String>>()
    private val queue = ConcurrentLinkedQueue<Heard>()
    private val found = LinkedHashMap<String, MutableList<Problem>>()

    /**
     * What generator [id]'s scripts are told to call when they fail, for the generators published now: from any
     * thread, and only queues. A package's generator (`acme:hills`) names its package's files.
     */
    fun reporter(id: String): (TerrainScriptFailure) -> Unit {
        val at = generation
        return { failure ->
            val heard = seen.computeIfAbsent(id) { ConcurrentHashMap.newKeySet() }
            if (at == generation && heard.size < MAX_PER_GENERATOR && heard.add("${failure.stage}\n${failure.message}")) {
                queue.add(Heard(at, id, failure))
            }
        }
    }

    /** The generators are being published again: what their scripts failed at is forgotten. Main thread. */
    fun restart() {
        generation++
        seen.clear()
        queue.clear()
        found.clear()
    }

    /** Says what was heard since the last call, once each ([say] logs it); true when there's something new. Main thread. */
    fun drain(say: (String) -> Unit): Boolean {
        var changed = false
        while (true) {
            val heard = queue.poll() ?: break
            if (heard.generation != generation) continue
            add(heard.id, heard.failure, say)
            changed = true
        }
        return changed
    }

    /** A failure found on the main thread (the check as a generator is published), said at once. */
    fun add(id: String, failure: TerrainScriptFailure, say: (String) -> Unit) {
        val heard = seen.computeIfAbsent(id) { ConcurrentHashMap.newKeySet() }
        heard.add("${failure.stage}\n${failure.message}")
        val (file, line) = failure.location
        val namespace = id.substringBefore(':', "").ifEmpty { null }
        val path = namespace?.let { PackagePaths.of(it, file) } ?: file
        val message = if (failure.stage == "load") {
            "The script didn't load, so only the file's own stages run: ${failure.message}"
        } else {
            "The script's ${failure.stage} stage failed, so the file's own result stands there: ${failure.message}"
        }
        say("terrain $id: $message")
        found.getOrPut(id) { mutableListOf() } += ProblemCodes.TERRAIN_SCRIPT_FAILED.at(path, message, line = line)
    }

    fun problems(): List<Problem> = found.values.flatten()

    companion object {
        const val MAX_PER_GENERATOR = 8
    }
}
