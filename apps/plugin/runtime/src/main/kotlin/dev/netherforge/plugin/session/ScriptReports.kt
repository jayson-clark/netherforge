package dev.netherforge.plugin.session

import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.bridge.ScriptError
import dev.netherforge.format.bridge.SourceRef
import dev.netherforge.format.module.ModuleKind
import dev.netherforge.plugin.lua.ScriptFailure
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.ScriptCosts
import dev.netherforge.plugin.script.Scripts
import java.util.Locale

/**
 * What the session's scripts did wrong, as the console, the editor and the
 * problems list hear it: a body that failed, a handler that errors, a script
 * that's slow. A problem a script caused is kept until its scope closes (its
 * resource reloads).
 */
internal class ScriptReports(private val session: ProjectSession) : RuntimeService {
    override val name get() = "script reports"

    private val platform get() = session.platform
    private val log get() = session.log

    /** Problems scripts caused while running (a body that failed, a handler that errors), by scope. */
    private val scriptProblems = LinkedHashMap<Int, LinkedHashSet<Problem>>()

    /** When each distinct runtime error was last logged, and how many like it were held back since. */
    private val errorLog = HashMap<String, Pair<Long, Int>>()

    /** When each kind of slow-script warning (a script and where it's slow) was last logged, and how many scopes like it were held back since. */
    private val slowLog = HashMap<String, Pair<Long, Int>>()
    private val deferredLog = HashMap<Int, Long>()

    /** The warning each slow scope has in the problems, until it closes. */
    private val slowProblems = LinkedHashMap<Int, Problem>()

    /** A script's body failed: its scope is disabled until its resource reloads. */
    fun failed(scope: Scope, failure: ScriptFailure, context: String) {
        val file = failure.file?.let(session::projectPath)
        val source = file?.let { SourceRef(it, failure.line) }
        val message = "${scope.owner.label}: $context failed: ${failure.message}"
        val where = source?.let { " (${it.file}${it.line?.let { line -> ":$line" }.orEmpty()})" }.orEmpty()
        platform.log.warn("$message$where. It's disabled until it's reloaded.")
        log.send(ScriptError(message, source, failure.traceback))
        val problem = problemOf(scope, failure)
        session.failures.add(problem)
        add(scope, problem)
    }

    /**
     * A handler, timer or command failed and the script keeps running. Logged
     * with its file and line in the console and the editor, and added to the
     * problems; the same error again within [ERROR_QUIET_TICKS] is only
     * counted, and the next line says how many were held back. [gaveUp]: it
     * failed [Scripts.MAX_ERRORS] times in a row and was cancelled, which is
     * always said.
     */
    fun errored(scope: Scope, failure: ScriptFailure, context: String, gaveUp: Boolean) {
        val file = failure.file?.let(session::projectPath)
        val source = file?.let { SourceRef(it, failure.line) }
        val where = source?.let { " (${it.file}${it.line?.let { line -> ":$line" }.orEmpty()})" }.orEmpty()
        val key = "${scope.id}|$context|${failure.message}|$file|${failure.line}"
        val (last, held) = errorLog[key] ?: (Long.MIN_VALUE to 0)
        val ticks = session.ticks
        if (last == Long.MIN_VALUE || ticks - last >= ERROR_QUIET_TICKS) {
            errorLog[key] = ticks to 0
            val more = if (held > 0) " (and $held more like it)" else ""
            val message = "${scope.owner.label}: $context failed: ${failure.message}"
            platform.log.warn("$message$where$more")
            log.send(ScriptError(message + more, source, failure.traceback))
        } else {
            errorLog[key] = last to held + 1
        }
        if (gaveUp) {
            val message = "${scope.owner.label}: $context failed ${Scripts.MAX_ERRORS} times in a row, so it was cancelled$where"
            platform.log.warn(message)
            log.send(ScriptError(message, source, null))
        }
        add(scope, problemOf(scope, failure))
    }

    /**
     * A scope took more than `performance.warn-ms` a tick on average
     * ([ScriptCosts] warns about each scope at most once a minute). Logged
     * with where its slowest recent call is, sent to the editor, and kept as
     * a warning in the problems until the scope closes. Many scopes of one
     * script slow in the same place (every instance of a centity) are one
     * line a minute, which says how many more there were.
     */
    fun slow(slow: ScriptCosts.Slow) {
        val scope = slow.scope
        val file = slow.file?.let(session::projectPath) ?: scope.owner.file
        val source = file?.let { SourceRef(it, slow.line) }
        val where = source?.let { " (slowest: ${it.file}${it.line?.let { line -> ":$line" }.orEmpty()})" }.orEmpty()
        val limit = session.config.performance.warnMillis.toBigDecimal().stripTrailingZeros().toPlainString()
        val key = "${scope.owner.label}|$file|${slow.line}"
        val (last, held) = slowLog[key] ?: (Long.MIN_VALUE to 0)
        val ticks = session.ticks
        if (last == Long.MIN_VALUE || ticks - last >= ScriptCosts.QUIET_TICKS) {
            slowLog[key] = ticks to 0
            val more = if (held > 0) " (and $held more like it)" else ""
            val average = "%.1f".format(Locale.ROOT, slow.averageMillis)
            val message = "${scope.owner.label} is slow: $average ms a tick on average over the last ${slow.ticks} ticks, " +
                "over the $limit ms limit; see /nf scripts"
            platform.log.warn("$message$where$more")
            log.send(ScriptError(message + more, source, null))
        } else {
            slowLog[key] = last to held + 1
        }
        val problem = ProblemCodes.SCRIPT_SLOW.at(
            file ?: scope.module?.let { session.placeOf(ModuleKind, it).location } ?: ModuleKind.folder,
            "${scope.owner.label} takes more than $limit ms a tick; see /nf scripts",
            line = slow.line
        )
        if (slowProblems.put(scope.id, problem) != problem) session.problemsChanged()
    }

    /**
     * A scope's timers or event handlers were deferred to the next tick because its code ran more than
     * `performance.max-ms-per-tick` in one tick ([ScriptCosts] reports each scope at most once a minute). A sign
     * the script fans out across more timers or handlers than the server can run a tick.
     */
    fun deferred(scope: Scope) {
        val ticks = session.ticks
        val last = deferredLog[scope.id]
        if (last != null && ticks - last < ScriptCosts.QUIET_TICKS) return
        deferredLog[scope.id] = ticks
        val limit = session.config.performance.maxTickMillis.toBigDecimal().stripTrailingZeros().toPlainString()
        platform.log.warn(
            "${scope.owner.label} ran more than $limit ms in a tick, so its timers and handlers waited for the next; " +
                "it may schedule more than the server can run a tick (see /nf scripts)"
        )
    }

    private fun add(scope: Scope, problem: Problem) {
        if (scriptProblems.getOrPut(scope.id) { LinkedHashSet() }.add(problem)) session.problemsChanged()
    }

    private fun problemOf(scope: Scope, failure: ScriptFailure): Problem {
        val file =
            failure.file?.let(session::projectPath) ?: scope.owner.file ?: scope.module?.let { session.placeOf(ModuleKind, it).location }
                ?: ModuleKind.folder
        return ProblemCodes.SCRIPT_ERROR.at(file, failure.message, line = failure.line)
    }

    /** The problems of scripts still running: a scope's go when it closes. */
    override fun problems(): List<Problem> {
        val scripts = session.scripts
        scriptProblems.keys.retainAll { scripts.scope(it) != null }
        errorLog.keys.retainAll { scripts.scope(it.substringBefore('|').toInt()) != null }
        slowProblems.keys.retainAll { scripts.scope(it) != null }
        return scriptProblems.values.flatten() + slowProblems.values.distinct()
    }

    private companion object {
        /** How long the same runtime error stays quiet after it's logged: five seconds. */
        const val ERROR_QUIET_TICKS = 100L
    }
}
