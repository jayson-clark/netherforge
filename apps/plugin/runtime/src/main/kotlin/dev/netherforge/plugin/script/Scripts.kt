package dev.netherforge.plugin.script

import dev.netherforge.format.project.PackagePaths
import dev.netherforge.format.script.ScriptDef
import dev.netherforge.plugin.binding.EventType
import dev.netherforge.plugin.binding.Events
import dev.netherforge.plugin.binding.LuaEvent
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.TickEvent
import dev.netherforge.plugin.binding.partOf
import dev.netherforge.plugin.lua.CallResult
import dev.netherforge.plugin.lua.EventStage
import dev.netherforge.plugin.lua.LuaCodec
import dev.netherforge.plugin.lua.LuaHost
import dev.netherforge.plugin.lua.LuaRef
import dev.netherforge.plugin.lua.LuaTypeMismatch
import dev.netherforge.plugin.lua.SandboxLimits
import dev.netherforge.plugin.lua.ScriptFailure
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.TickPhase

/**
 * Every scope on the project's Lua state, and what they've registered with
 * Kotlin: timers. Event subscriptions live in the prelude's
 * event core; this side raises events into it ([emit]) and keeps a count of
 * who listens to what ([listening]), so work nobody listens to is skipped.
 *
 * Two kinds of failure, two rules:
 *
 *  - **A script's body failing takes its scope out of service.** Its setup
 *    didn't finish, so it's reported through [onFailure] and the scope is
 *    disabled (its subscriptions, timers and whatever its services hold, its
 *    commands among them, go) until its resource
 *    is reloaded.
 *  - **A handler, timer or command failing is reported** through [onError],
 *    and the script keeps running. A subscription or a repeating timer that
 *    fails [MAX_ERRORS] times in a row is cancelled; a success resets the
 *    count. A command stays registered, and whoever typed it is told.
 *
 * One session's: its Lua state is attached once and closed with it ([detach]).
 */
class Scripts internal constructor(
    private val onFailure: (Scope, ScriptFailure, String) -> Unit,
    /** A handler, timer or command failed: the scope, the failure, what failed, and whether it was cancelled for it. */
    private val onError: (Scope, ScriptFailure, String, Boolean) -> Unit,
    /** Something scripts did that's worth a line in the log but belongs to no one script: an unreadable item put into an event's list. */
    private val onWarning: (String) -> Unit = {},
    /**
     * A scope stopped for good, its own subscriptions gone: what else it made
     * (a boss bar, a menu template, a playing effect) goes now. Called once per scope.
     */
    private val onRelease: (Scope) -> Unit = {},
    /** The namespace of the project itself: the package of every scope whose resource isn't a dependency's. */
    private val home: () -> String = { "" },
    /** The server tick now, for timers and `nf.on("tick")`. */
    private val ticks: () -> Long = { 0 },
    /** The memory the Lua state may use, past which the scope the census blames is stopped ([stopBlamed]). */
    private val memoryMegabytes: Int = SandboxLimits().memoryMegabytes
) : RuntimeService {
    override val name get() = "scripts"

    var host: LuaHost? = null
        private set

    private var nextId = 1
    private val scopes = LinkedHashMap<Int, Scope>()
    private val timers = LinkedHashMap<Int, Timer>()

    /** Handlers per target (null for `nf`) and event, as the event core reports them. */
    private val listeners = HashMap<LuaHandle?, HashMap<String, Int>>()

    /**
     * When the handlers of an event on a target run, where every one of them
     * has a rate (`every`): pairs of rate and phase, a handler running on the
     * ticks where `(tick + phase) % rate == 0`. No entry: some handler runs
     * every time.
     */
    private val rates = HashMap<LuaHandle?, HashMap<String, LongArray>>()

    /** The current server tick, for timers. */
    var now: Long = 0
        private set

    private class Timer(val id: Int, val scope: Scope, val ref: LuaRef, var due: Long, val period: Long) {
        var errors = 0
    }

    fun attach(host: LuaHost) {
        check(this.host == null) { "a Lua state is already attached" }
        this.host = host
    }

    /** The tick's script steps: due timers, `nf.on("tick")`, and the handles Lua let go of. */
    override fun tick(phase: TickPhase) {
        when (phase) {
            TickPhase.TIMERS -> advance(ticks())
            TickPhase.EVENTS -> if (due(null, Events.NF_TICK, ticks())) emit(Events.NF_TICK, null, TickEvent(ticks()))
            TickPhase.ACCOUNTS -> {
                stopBlamed(memoryMegabytes)
                host?.sweepHandles()
            }
            else -> {}
        }
    }

    /** Closes every scope without unloading it, then the Lua state. Callers unload scopes first. */
    fun detach() {
        for (scope in scopes.values.toList()) close(scope, unload = false)
        host?.close()
        host = null
        listeners.clear()
        rates.clear()
        totals.clear()
        for (watch in watches) watch.update()
    }

    fun scope(id: Int): Scope? = scopes[id]

    /**
     * A scope stopped for good: it closed, or its body failed and it was
     * disabled. By then its own subscriptions are gone, so whatever
     * [onRelease] ends is heard only by other scopes' handlers.
     */
    private fun released(scope: Scope) {
        if (scope.released) return
        scope.released = true
        onRelease(scope)
    }

    fun scopes(): List<Scope> = scopes.values.toList()

    /**
     * Opens [owner]'s scope with [script]'s budget and runs its file, whose
     * text is [source] (the file is [ScopeOwner.file]). The scope, and whether
     * the body ran to the end.
     */
    fun start(owner: ScopeOwner, script: ScriptDef, source: String): Pair<Scope, Boolean> {
        val scope = open(owner, script.effectiveBudget)
        return scope to runFile(scope, requireNotNull(owner.file) { "${owner.label} has no file" }, source)
    }

    /** A new scope whose globals are what [owner]'s surface gets: `this`, the centity instance, menu window or dialog; nothing for a module. */
    fun open(owner: ScopeOwner, budget: Int): Scope {
        val host = requireNotNull(host) { "no Lua state" }
        val scope = Scope(nextId++, owner, budget, owner.resource?.let { PackagePaths.split(it.id).first } ?: home())
        scopes[scope.id] = scope
        val self = when (owner) {
            is ScopeOwner.Module -> null
            is ScopeOwner.CentityScript -> LuaHandle.Centity(owner.instance.toString())
            is ScopeOwner.MenuScript -> LuaHandle.Menu(owner.window)
            is ScopeOwner.DialogScript -> LuaHandle.Dialog(owner.dialog)
            is ScopeOwner.ItemScript -> LuaHandle.ProjectItem(owner.item)
            is ScopeOwner.BlockScript -> LuaHandle.ProjectBlock(owner.block)
            is ScopeOwner.ScreenScript -> LuaHandle.Screen(owner.screen, "")
            is ScopeOwner.TestScript -> null
        }
        host.newEnv(scope.id, budget, self, scope.namespace)
        return scope
    }

    /**
     * Ends a scope. When [unload] is set and it's still live, its own
     * `nf.on("unload")` handlers hear about it first. Every subscription it
     * made goes with it.
     */
    fun close(scope: Scope, unload: Boolean = true) {
        if (scope.state == Scope.State.CLOSED) return
        if (unload && scope.live) host?.unload(scope.id)
        release(scope)
        scope.state = Scope.State.CLOSED
        scopes.remove(scope.id)
        host?.dropEnv(scope.id)
        released(scope)
    }

    fun disable(scope: Scope) {
        if (!scope.live) return
        scope.state = Scope.State.DISABLED
        release(scope)
        host?.dropSubscriptions(scope.id)
        released(scope)
    }

    /**
     * Stops the scope the Lua state's hook blamed for scripts going over
     * their memory limit ([LuaHost.takeBlamed]: the one holding the most).
     * Reported like a failed body and disabled, and its globals and the
     * files it required are let go of, so what it held can be collected (a
     * scope whose body failed holding it is already disabled and reported,
     * and only lets go). Once a tick.
     */
    fun stopBlamed(limitMegabytes: Int) {
        val host = host ?: return
        val (id, bytes) = host.takeBlamed() ?: return
        val scope = scopes[id] ?: return
        if (scope.live) {
            val held = "it held about ${bytes / (1024 * 1024)} MB, the most of any script"
            val failure = ScriptFailure("$held, when scripts went over the $limitMegabytes MB they may use", null, null, null)
            onFailure(scope, failure, "using memory")
            disable(scope)
        }
        host.dropEnv(scope.id)
    }

    private fun release(scope: Scope) {
        for (id in scope.timers.toList()) cancel(id)
    }

    // ---- loading ------------------------------------------------------------

    /** A body that failed: reported, and the scope disabled. */
    private fun guard(scope: Scope, result: CallResult, context: String): CallResult {
        if (result is CallResult.Failed && scope.live) {
            onFailure(scope, result.failure, context)
            disable(scope)
        }
        return result
    }

    fun runFile(scope: Scope, path: String, source: String): Boolean {
        val host = host ?: return false
        if (!scope.live) return false
        return guard(scope, host.runFile(scope.budget, scope.id, path, source), "loading $path") !is CallResult.Failed
    }

    fun start(scope: Scope, path: String): Boolean {
        val host = host ?: return false
        if (!scope.live) return false
        return guard(scope, host.start(scope.budget, scope.id, path), "loading $path") !is CallResult.Failed
    }

    /** Calls a kept function as [scope]'s code. A failure is the caller's to report. */
    private fun call(scope: Scope, ref: LuaRef, args: List<Any?>): CallResult {
        val host = host ?: return CallResult.Absent
        if (!scope.live) return CallResult.Absent
        return host.invoke(scope.budget, scope.id, ref, args, scope.namespace)
    }

    /**
     * Calls a function [scope] kept for something that finished later (a
     * world copied off the main thread) as its code, once, then lets go of
     * it. A failure is reported as [what]. A scope that stopped meanwhile
     * isn't called: whoever kept the function must have let go of it then
     * ([onRelease]), since after a full reload the Lua state it was kept in
     * is gone (and [LuaRef] would refuse it).
     */
    fun callBack(scope: Scope, ref: LuaRef, args: List<Any?>, what: String) {
        if (scope.released) return
        val result = call(scope, ref, args)
        host?.unref(ref)
        if (result is CallResult.Failed) onError(scope, result.failure, what, false)
    }

    /**
     * Calls a function [scope] kept for the runtime to call again and again
     * (a mob goal's callback, a command's handler) with [args] as its code, in
     * a budget frame of its own. [CallResult.Absent] once the scope has
     * stopped. A failure is the caller's to report, with [handlerFailed].
     */
    fun callKept(scope: Scope, ref: LuaRef, args: List<Any?> = emptyList()): CallResult = call(scope, ref, args)

    /**
     * [callKept], for a call the profiler times as [kind] (`command`,
     * `complete`), its answer read by [result] when there is one
     * ([CallResult.Ok.value] is what it read): an answer it can't read is a
     * [LuaTypeMismatch] named [where].
     */
    fun <T> callKept(
        scope: Scope,
        ref: LuaRef,
        args: List<Any?>,
        kind: String,
        result: LuaCodec<T>? = null,
        where: String = "result"
    ): CallResult {
        val host = host ?: return CallResult.Absent
        if (!scope.live) return CallResult.Absent
        return host.invoke(scope.budget, scope.id, ref, args, scope.namespace, kind, result, where)
    }

    /** Lets go of a function kept with `host.ref` that will never be called. */
    fun unref(ref: LuaRef) {
        host?.unref(ref)
    }

    // ---- events ---------------------------------------------------------------

    /**
     * The event core's count of handlers for [event] on [target] (null for
     * `nf`), and their [rates] when every one has one: rate and phase pairs.
     */
    internal fun listeningChanged(target: LuaHandle?, event: String, count: Int, rated: LongArray? = null) {
        val before = listeners[target]?.get(event) ?: 0
        if (count > 0 && rated != null) {
            rates.getOrPut(target) { HashMap() }[event] = rated
        } else {
            rates[target]?.let { byEvent ->
                byEvent.remove(event)
                if (byEvent.isEmpty()) rates.remove(target)
            }
        }
        if (count > 0) {
            listeners.getOrPut(target) { HashMap() }[event] = count
        } else {
            val byEvent = listeners[target] ?: return
            byEvent.remove(event)
            if (byEvent.isEmpty()) listeners.remove(target)
        }
        // Counted for the class that declares the event (a Player's `heal` is a Living's), or the
        // target's own class for a custom one.
        val luaClass = target?.luaClass ?: NF
        val owner = Events.of(luaClass, event)?.owner ?: luaClass
        val total = (totals[owner to event] ?: 0) + count - before
        if (total > 0) totals[owner to event] = total else totals.remove(owner to event)
        for (watch in watches) watch.update()
        if (count != before) counts[event]?.forEach { it() }
    }

    /** What [counting] calls, by event name, whenever any target's count of handlers for it changes. */
    private val counts = HashMap<String, MutableList<() -> Unit>>()

    /**
     * Calls [changed] whenever a handler for any of [events] is added or goes, on any target: a script
     * listening, or one stopping (its scope unloading on a reload). Unlike [watch], it hears a script
     * replaced by another that listens just the same.
     */
    fun counting(vararg events: EventType<*>, changed: () -> Unit) {
        for (event in events) counts.getOrPut(event.luaName) { mutableListOf() } += changed
    }

    /** Handlers per declaring class (`"Living"`, `nf`) and event, over every target that has it. */
    private val totals = HashMap<Pair<String, String>, Int>()

    private inner class Watch(val events: List<EventType<*>>, val changed: (Boolean) -> Unit) {
        var listening = false

        fun update() {
            val now = events.any { (totals[it.owner to it.luaName] ?: 0) > 0 }
            if (now != listening) {
                listening = now
                changed(now)
            }
        }
    }

    private val watches = mutableListOf<Watch>()

    /**
     * Calls [changed] whenever whether anything listens for any of [events], on
     * any target, changes: so a server event costly to watch (a player moving)
     * is watched only while a script wants it.
     */
    fun watch(vararg events: EventType<*>, changed: (listening: Boolean) -> Unit) {
        watches += Watch(events.toList(), changed).also { it.update() }
    }

    /** Whether anything listens for [event] on [target] (null for `nf`). */
    fun listening(target: LuaHandle?, event: EventType<*>): Boolean = (listeners[target]?.get(event.luaName) ?: 0) > 0

    /**
     * Whether a handler for [event] on [target] runs on [tick]: something
     * listens, and one of them has no rate or is due on it. So a centity
     * whose handlers all run `every = 4` is called into once in four ticks,
     * not every tick to count.
     */
    fun due(target: LuaHandle?, event: EventType<*>, tick: Long): Boolean {
        if (!listening(target, event)) return false
        val rated = rates[target]?.get(event.luaName) ?: return true
        var at = 0
        while (at < rated.size) {
            if (Math.floorMod(tick + rated[at + 1], rated[at]) == 0L) return true
            at += 2
        }
        return false
    }

    /** A handler failed in the event core: reported; [gaveUp] when its subscription was cancelled for it. */
    internal fun handlerFailed(scope: Scope, what: String, failure: ScriptFailure, gaveUp: Boolean) {
        handlerFailures++
        onError(scope, failure, what, gaveUp)
    }

    /** How many handlers have failed so far: one that changes over an [emit] means a handler of it failed. */
    var handlerFailures = 0L
        private set

    /** Raises [event] on [target] (null for `nf`). Returns whether it ended cancelled. */
    fun <P : LuaEvent> emit(event: EventType<P>, target: LuaHandle?, payload: P, precancelled: Boolean = false): Boolean =
        emit(listOf(event to target), payload, precancelled)

    /** Raises [event] on `nf` for package [namespace]'s handlers alone (a package's `setting_changed`). */
    fun <P : LuaEvent> emitTo(namespace: String, event: EventType<P>, payload: P) {
        emit(listOf(event to null), payload, only = namespace)
    }

    /** The scopes with a live `nf.on` handler for [event]: who handles it themselves. */
    fun scopesListening(event: EventType<*>): Set<Int> =
        if (listening(null, event)) host?.scopesListening(event.luaName).orEmpty() else emptySet()

    /**
     * Raises an event along a path: the handlers of each stage's event on its
     * target in turn, until one calls `stop()`. Every stage shares [payload],
     * whose writable fields are read back into it afterwards. Returns whether
     * it ended cancelled ([precancelled] if nothing changed it).
     */
    fun <P : LuaEvent> emit(
        path: List<Pair<EventType<out P>, LuaHandle?>>,
        payload: P,
        precancelled: Boolean = false,
        only: String? = null
    ): Boolean {
        require(path.isNotEmpty()) { "an event needs somewhere to go" }
        val first = path.first().first
        require(!first.local) { "$first is only for the scope it's about" }
        val host = host ?: return precancelled
        // A stage whose target's class has an event of its own by that name (a Player's `death`
        // is `player_death`, not a Living's) isn't that target's: it's left out.
        val stages = path.filter { (event, target) -> target == null || Events.of(target.luaClass, event.luaName) === event }
        if (stages.isEmpty() || stages.none { (event, target) -> listening(target, event) }) return precancelled
        val outcome = host.emit(
            stages.map { (event, target) -> EventStage(event.luaName, target) },
            payload,
            precancelled,
            first.writable,
            home(),
            only
        ) ?: return precancelled
        for (problem in outcome.problems) onWarning("A $first handler left $problem; it's left as it was")
        return outcome.cancelled
    }

    /**
     * Something scripts may hold a handle to is gone: every subscription on it
     * goes, and on its parts (a centity's nodes, a window's slots, a dialog's
     * buttons), which only exist while it does.
     */
    fun dropTarget(target: LuaHandle) {
        val host = host ?: return
        host.dropTargets(listeners.keys.filter { it != null && (it == target || it.partOf() == target) }.filterNotNull())
    }

    /** Whether anything listens for any event on [target] itself: whether [dropTarget] has anything to do for it. */
    fun listenedTo(target: LuaHandle): Boolean = listeners[target]?.values?.any { it > 0 } == true

    // ---- timers ---------------------------------------------------------------

    fun schedule(scope: Scope, delay: Long, period: Long, ref: LuaRef): Int {
        val id = nextId++
        timers[id] = Timer(id, scope, ref, now + delay.coerceAtLeast(0), period)
        scope.timers += id
        return id
    }

    /** Whether timer [id] will still run. */
    fun isScheduled(id: Int): Boolean = id in timers

    fun cancel(id: Int) {
        val timer = timers.remove(id) ?: return
        timer.scope.timers -= id
        host?.unref(timer.ref)
    }

    /** Advances the clock to [tick] and runs every timer that's due. */
    fun advance(tick: Long) {
        now = tick
        for (timer in timers.values.filter { it.due <= tick }) {
            if (timer.id !in timers) continue
            if (timer.period > 0) {
                timer.due = tick + timer.period
                val result = call(timer.scope, timer.ref, emptyList())
                if (result is CallResult.Failed) {
                    timer.errors++
                    val gaveUp = timer.errors >= MAX_ERRORS
                    if (gaveUp) cancel(timer.id)
                    onError(timer.scope, result.failure, "timer", gaveUp)
                } else {
                    timer.errors = 0
                }
            } else {
                // Taken off before it runs so the callback may schedule again freely.
                timers.remove(timer.id)
                timer.scope.timers -= timer.id
                val result = call(timer.scope, timer.ref, emptyList())
                host?.unref(timer.ref)
                if (result is CallResult.Failed) onError(timer.scope, result.failure, "timer", false)
            }
        }
    }

    companion object {
        /** Failures in a row after which a subscription or a repeating timer is cancelled. */
        const val MAX_ERRORS = 20

        /** The key `nf.on` subscriptions are filed under. */
        private const val NF = "nf"

        /** Phases are taken below this, so `tick + phase` never comes near overflowing. */
        private const val PHASES = 1 shl 20

        /**
         * Where [handle]'s `every` handlers fall among the ticks: the thing it
         * belongs to (a node's centity), hashed, so every handler on one
         * instance agrees and a crowd of instances spreads over the ticks.
         */
        fun phaseOf(handle: LuaHandle): Long = Math.floorMod((handle.partOf() ?: handle).hashCode() * 0x9E3779B1.toInt(), PHASES).toLong()
    }
}
