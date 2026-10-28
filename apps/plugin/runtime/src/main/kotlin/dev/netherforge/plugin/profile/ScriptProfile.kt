package dev.netherforge.plugin.profile

import dev.netherforge.format.bridge.ScopeTime
import dev.netherforge.format.bridge.SourceRef
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.ScriptCosts
import dev.netherforge.plugin.script.Scripts
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.TickPhase

/**
 * The session's side of the [Profiler]: turns the Lua state's bookkeeping on
 * while the profiler is [Profiler.active] ([sync]), and hands it what scripts
 * cost, named: each scope's own time every tick (what [ScriptCosts] took),
 * and what each function cost whenever a batch ends ([drain]).
 *
 * Scopes that closed since the last drain are remembered by name until then,
 * since what they ran is still in the Lua state's tables.
 */
class ScriptProfile(
    private val scripts: Scripts,
    private val costs: ScriptCosts,
    private val profiler: Profiler,
    /** The server tick now. */
    private val ticks: () -> Long
) : RuntimeService {
    override val name get() = "profiler"

    /** Whether the Lua state keeps per-function times now. */
    private var on = false

    private val closed = HashMap<Int, Scope>()

    /** Turns the Lua state's bookkeeping on or off to match the profiler; the session calls it as every tick starts. */
    fun sync() {
        val host = scripts.host ?: return
        val wanted = profiler.active
        if (wanted == on) return
        host.setProfiling(wanted)
        on = wanted
        if (!wanted) closed.clear()
    }

    override fun start() {
        on = false
        sync()
    }

    override fun stop() {
        // A full reload: what this Lua state measured goes to the profiler before it closes.
        drain()
        on = false
    }

    override fun tick(phase: TickPhase) {
        if (phase != TickPhase.ACCOUNTS || !profiler.active) return
        // After ScriptCosts (registered before this), which took this tick's costs.
        profiler.scopes(
            costs.lastTick.mapNotNull { (id, cost) ->
                val scope = scripts.scope(id) ?: closed[id] ?: return@mapNotNull null
                ScopeTime(scope.title, cost.nanos, cost.calls)
            }
        )
        if (profiler.due(ticks())) drain()
    }

    override fun scopeReleased(scope: Scope) {
        if (on) closed[scope.id] = scope
    }

    /** Hands the profiler what every function cost since the last time. */
    fun drain() {
        val host = scripts.host
        if (host == null || !on) {
            closed.clear()
            return
        }
        profiler.calls(
            host.takeProfile().mapNotNull { call ->
                val scope = scripts.scope(call.scope) ?: closed[call.scope] ?: return@mapNotNull null
                Profiler.Call(
                    script = scope.owner.label,
                    scope = scope.title,
                    kind = call.kind,
                    source = call.file?.let { SourceRef(it, call.line) },
                    nanos = call.nanos,
                    calls = call.calls,
                    max = call.max
                )
            }
        )
        closed.clear()
    }
}
