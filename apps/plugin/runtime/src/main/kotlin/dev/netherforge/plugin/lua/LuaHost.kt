package dev.netherforge.plugin.lua

import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.binding.LuaEvent
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.LuaLocation
import dev.netherforge.plugin.platform.ItemData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import party.iroiro.luajava.JFunction
import party.iroiro.luajava.Lua
import party.iroiro.luajava.LuaException
import party.iroiro.luajava.lua54.Lua54
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * One Lua 5.4 state running a whole project: every module and every node
 * script of every centity instance.
 *
 * One state rather than one per script is what makes `require` give the
 * module that's running rather than a copy, and it's cheap: a scope is an
 * environment table, not an interpreter. Isolation between scopes is the
 * prelude's job (separate globals, read-only shared libraries); limits are
 * per call in, through the prelude's budget frames, and on the standard
 * library's functions, through its caps ([SandboxLimits]).
 *
 * Primitives are registered into a table only the prelude holds. Calls back
 * into Lua from inside a primitive (a spawn running the new centity's
 * script, a removal firing `remove` handlers) go through the thread that
 * called the primitive, which may be a script's coroutine: using the main
 * thread's stack while a coroutine runs would corrupt it.
 *
 * Holds native memory; must be [close]d, and closing is deferred while a call
 * is still on the stack (a script that triggers a full reload).
 *
 * Only the thread that made it (the server's main thread) may use it: every
 * entry throws [WrongThread] for any other, rather than letting a second
 * thread into the native state, which would crash the JVM.
 */
class LuaHost(
    primitives: Map<String, Primitive>,
    /** How items cross, which takes the runtime (the server's items, the project's). */
    val marshal: LuaMarshal = LuaMarshal.NONE,
    /** Nanoseconds from any fixed point, for timing each call in and its time limit: `System.nanoTime`, or a test's own. */
    clock: () -> Long = System::nanoTime,
    /** What the prelude's hook and the standard library's caps hold every call in to. */
    limits: SandboxLimits = SandboxLimits()
) : AutoCloseable {
    /** Which host this is, counting from 1 in this JVM: what every [LuaRef] it keeps carries. */
    val generation: Int = GENERATIONS.incrementAndGet()

    /**
     * The thread that made this state, the only one that may use it: the
     * server's main thread. Lua isn't thread-safe, and a native call from a
     * second thread corrupts the state or kills the JVM, so every entry checks
     * ([owned]) and throws [WrongThread] instead.
     */
    private val owner: Thread = Thread.currentThread()

    private val main: Lua = Lua54()
    private var current: Lua = main
    private val hostRef: Int

    /** [TAIL_CALL]'s function: whether a tail call hides who called the running primitive ([callerHiddenByTailCall]). */
    private val tailCallRef: Int

    /** The prelude's table that says what a genuine typed value is: metatable → kind. */
    private val kindsRef: Int

    /** Each kind's name by its number in `kinds` (from 1). */
    private val kindNames: Array<String>

    /**
     * Every handle Lua holds, by the key the Lua table stands for. The prelude
     * keeps each table's key (`ids`, weak) and each key's table (`cache`, weak),
     * so two of the same thing are the same table; a table it lets go of comes
     * back through [sweepHandles].
     */
    val handles = HandleTable()
    private val idsRef: Int
    private val cacheRef: Int

    /** The prelude's `spelled`: table → the package (namespace) Kotlin spelled it for. */
    private val spelledRef: Int

    /** The genuine metatables of a `Vec3` and a `Location`: Kotlin builds the values it hands over ([pushVec3]). */
    private val vec3MtRef: Int
    private val locationMtRef: Int

    /** The handle classes a saved table may keep, each with its tag (`Entity` → `entity`): the JSON codec's `saved_handles`. */
    private val savedHandles: Map<String, String>

    /** Plain data read straight off the stack, as the codec would write it. */
    private val plain by lazy { PlainJson(this, savedHandles) }

    /** Each handle class's metatable, by name (`host.metatables`), each kept on first use. */
    private val metatablesRef: Int
    private val metatables = HashMap<String, Int>()

    /** Option table fields this server's Minecraft is too old for. */
    var gates: LuaGates = LuaGates.NONE
    private var depth = 0
    private var closeWanted = false
    private var closed = false

    /**
     * The scope whose code called the primitive running now, when it said so
     * (an `nf.*` function: `nf` is each scope's own, so this is the code's
     * scope even past a tail call); null otherwise, and reset for every
     * primitive call. What a bare id the call passes is resolved against,
     * before [callingFile].
     */
    var callerScope: Int? = null

    /** True while any call into this state hasn't returned yet. */
    val busy: Boolean get() = depth > 0

    /** Throws [WrongThread] unless the caller is the thread that owns this state. Every entry calls it first. */
    private fun owned(entry: String) {
        val caller = Thread.currentThread()
        if (caller !== owner) throw WrongThread(entry, owner, caller)
    }

    init {
        main.openLibraries()
        // Before the prelude takes `debug` away: the one function that needs the real getinfo
        // and isn't the prelude's (whose copy is a local). Kept in the registry, which no
        // script can reach.
        main.load(direct(TAIL_CALL), "=nf/tail")
        main.pCall(0, 1)
        tailCallRef = main.ref()
        // `clock`: the prelude's budget frames time every call in with it. `handles.*`: the
        // prelude's own handles (a `Task`, a world by name in saved data), a handle's key
        // values (for saving one, and printing it), and finding out a handle's class.
        val all = primitives + mapOf(
            "clock" to Primitive { lua ->
                lua.push(clock())
                1
            },
            "handles.new" to Primitive { lua ->
                val key = (2..lua.top).map { if (lua.isInteger(it)) lua.toInteger(it) else lua.toString(it)!! }
                pushHandle(lua, marshal.refine(LuaHandle.build(lua.toString(1)!!, key)))
                1
            },
            "handles.key" to Primitive { lua ->
                val key = handleAt(lua, 1).keyValues
                for (value in key) pushValue(lua, value)
                key.size
            },
            "handles.refine" to Primitive { lua ->
                lua.push(refineAt(lua, 1))
                1
            },
            // (fields, from, to): the event being raised, spelled for another package's handlers.
            "events.respell" to Primitive { lua -> respell(lua, 1, lua.toString(2)!!, lua.toString(3)!!) }
        )
        main.createTable(0, all.size)
        for ((name, primitive) in all) {
            main.push(
                JFunction { lua ->
                    val previous = current
                    val previousCaller = callerScope
                    val previousReading = reading
                    val previousAsked = callingFileAsked
                    val previousAnswer = callingFileAnswer
                    val previousIn = inPrimitive
                    current = lua
                    callerScope = null
                    reading = null
                    callingFileAsked = false
                    inPrimitive = true
                    try {
                        primitive.call(lua)
                    } finally {
                        current = previous
                        callerScope = previousCaller
                        reading = previousReading
                        callingFileAsked = previousAsked
                        callingFileAnswer = previousAnswer
                        inPrimitive = previousIn
                    }
                }
            )
            main.setField(-2, name)
        }
        // prelude(primitives, bindings, caps, limits, modules): the entry loads its modules, which
        // run the generated bindings with the core and wrap the standard library with the caps.
        main.load(direct(PRELUDE), "=nf")
        main.insert(-2)
        main.load(direct(BINDINGS), "=nf/bindings")
        main.load(direct(CAPS), "=nf/caps")
        main.createTable(0, 7)
        for ((name, value) in listOf(
            "hook_every" to limits.hookEvery,
            "deadline_every" to limits.deadlineEvery,
            "memory_every" to limits.memoryEvery,
            "deadline_ms" to limits.deadlineMillis,
            "memory_mb" to limits.memoryMegabytes,
            "max_string" to limits.maxStringBytes
        )) {
            main.push(value.toLong())
            main.setField(-2, name)
        }
        main.push(limits.maxWork)
        main.setField(-2, "max_work")
        main.createTable(0, MODULES.size)
        for ((name, source) in MODULES) {
            main.load(direct(source), "=nf:$name")
            main.setField(-2, name)
        }
        main.pCall(5, 1)
        main.getField(-1, "kinds")
        kindsRef = main.ref()
        main.getField(-1, "kind_names")
        kindNames = Array(main.rawLength(-1) + 1) { k ->
            if (k == 0) "" else main.rawGetI(-1, k).let { main.toString(-1)!!.also { main.pop(1) } }
        }
        main.pop(1)
        main.getField(-1, "ids")
        idsRef = main.ref()
        main.getField(-1, "cache")
        cacheRef = main.ref()
        main.getField(-1, "metatables")
        metatablesRef = main.ref()
        main.getField(-1, "spelled")
        spelledRef = main.ref()
        main.getField(-1, "vec3_mt")
        vec3MtRef = main.ref()
        main.getField(-1, "location_mt")
        locationMtRef = main.ref()
        main.getField(-1, "saved_handles")
        savedHandles = buildMap {
            main.pushNil()
            while (main.next(-2) != 0) {
                put(main.toString(-2)!!, main.toString(-1)!!)
                main.pop(1)
            }
        }
        main.pop(1)
        hostRef = main.ref()
    }

    /** Notes that the table at [index] (one Kotlin just pushed) is spelled for package [namespace]. */
    fun markSpelled(lua: Lua, index: Int, namespace: String) {
        owned("markSpelled")
        val at = if (index < 0) lua.top + index + 1 else index
        lua.refGet(spelledRef)
        lua.pushValue(at)
        lua.push(namespace)
        lua.rawSet(-3)
        lua.pop(1)
    }

    /** The package the table at [index] was spelled for when Kotlin pushed it ([markSpelled]); null for one a script made. */
    fun spelledFor(lua: Lua, index: Int): String? {
        owned("spelledFor")
        val at = if (index < 0) lua.top + index + 1 else index
        lua.refGet(spelledRef)
        lua.pushValue(at)
        lua.rawGet(-2)
        val namespace = if (lua.isString(-1)) lua.toString(-1) else null
        lua.pop(2)
        return namespace
    }

    // ---- typed values -------------------------------------------------------------

    /**
     * What the value at [index] is when it's one of the API's own (a handle's
     * class, `Vec3`, `Location`), told by its metatable, which a script can't
     * reach or fake; null for anything else.
     */
    fun kindAt(lua: Lua, index: Int): String? {
        owned("kindAt")
        if (lua.type(index) != Lua.LuaType.TABLE) return null
        val at = if (index < 0) lua.top + index + 1 else index
        if (lua.getMetatable(at) == 0) return null
        lua.refGet(kindsRef)
        lua.insert(-2)
        lua.rawGet(-2)
        // Not one of ours: nil, which reads as 0.
        val kind = lua.toInteger(-1).toInt()
        lua.pop(2)
        return if (kind == 0) null else kindNames[kind]
    }

    /** The handle at [index] (a genuine one: [kindAt] said so). */
    fun handleAt(lua: Lua, index: Int): LuaHandle = entryAt(lua, index).handle

    /** The handle whose key in the handle table is [key]: what `self_of` hands a method. */
    fun handle(key: Long): LuaHandle {
        owned("handle")
        return handles[key]?.handle ?: throw LuaApiException("that handle is no longer known")
    }

    private fun entryAt(lua: Lua, index: Int): HandleTable.Entry {
        owned("handleAt")
        val at = if (index < 0) lua.top + index + 1 else index
        lua.refGet(idsRef)
        lua.pushValue(at)
        lua.rawGet(-2)
        val key = if (lua.isInteger(-1)) lua.toInteger(-1) else 0
        lua.pop(2)
        return handles[key] ?: throw LuaApiException("that handle is no longer known")
    }

    /**
     * Asks the runtime what the handle at [index] is now (`LuaMarshal.refine`):
     * one handed out as an `Entity` while its entity was unloaded becomes the
     * `Mob` it is once it's back. Whether its class changed.
     */
    fun refineAt(lua: Lua, index: Int): Boolean {
        owned("refineAt")
        val at = if (index < 0) lua.top + index + 1 else index
        val entry = entryAt(lua, at)
        val before = entry.handle.luaClass
        handles.intern(marshal.refine(entry.handle))
        if (entry.handle.luaClass == before) return false
        showClass(lua, at, entry)
        return true
    }

    /** Gives the handle table at [index] the metatable of [entry]'s class. */
    private fun showClass(lua: Lua, index: Int, entry: HandleTable.Entry) {
        val luaClass = entry.handle.luaClass
        val ref = metatables.getOrPut(luaClass) {
            lua.refGet(metatablesRef)
            lua.getField(-1, luaClass)
            lua.remove(-2)
            lua.ref()
        }
        lua.refGet(ref)
        lua.setMetatable(if (index < 0) index - 1 else index)
        entry.shown = luaClass
    }

    /** A full collection now, as the hook makes past the memory limit; then [sweepHandles] lets go of what it freed. */
    fun collectGarbage() {
        owned("collectGarbage")
        if (!closed) main.gc()
    }

    /**
     * Forgets the handles whose tables Lua has collected (the prelude's `__gc`
     * notes each key, and `host.take_released` hands over those still not
     * back in the cache). Once a tick.
     */
    fun sweepHandles() {
        owned("sweepHandles")
        if (closed) return
        val lua = current
        val top = lua.top
        depth++
        try {
            lua.refGet(hostRef)
            lua.getField(-1, "take_released")
            lua.remove(-2)
            lua.pCall(0, 1)
            val count = lua.rawLength(-1)
            for (k in 1..count) {
                lua.rawGetI(-1, k)
                handles.release(lua.toInteger(-1))
                lua.pop(1)
            }
        } catch (e: LuaException) {
            return
        } finally {
            lua.setTop(top)
            depth--
            if (depth == 0 && closeWanted) close()
        }
    }

    /** The genuine `Vec3` at [index]: its numbers are its array part. */
    fun vec3At(lua: Lua, index: Int): Vec3 {
        owned("vec3At")
        val at = if (index < 0) lua.top + index + 1 else index
        lua.rawGetI(at, 1)
        lua.rawGetI(at, 2)
        lua.rawGetI(at, 3)
        val vector = Vec3(lua.toNumber(-3), lua.toNumber(-2), lua.toNumber(-1))
        lua.pop(3)
        return vector
    }

    /** The genuine `Location` at [index]: world (a `World` handle), position, and a facing that may be nil. */
    fun locationAt(lua: Lua, index: Int): LuaLocation {
        owned("locationAt")
        val at = if (index < 0) lua.top + index + 1 else index
        lua.rawGetI(at, 1)
        val world = handleAt(lua, -1) as LuaHandle.World
        lua.rawGetI(at, 2)
        val position = vec3At(lua, -1)
        lua.rawGetI(at, 3)
        lua.rawGetI(at, 4)
        val yaw = if (lua.isNumber(-2)) lua.toNumber(-2) else null
        val pitch = if (lua.isNumber(-1)) lua.toNumber(-1) else null
        lua.pop(4)
        return LuaLocation(world, position, yaw, pitch)
    }

    /**
     * A scope's environment, with [self] as its `this` (the centity, menu
     * window, dialog or project item whose script it is; null for a module).
     * Its handlers run with [budget].
     */
    fun newEnv(scope: Int, budget: Int, self: LuaHandle?, namespace: String) {
        owned("newEnv")
        callHost("new_env", 0) {
            push(scope.toLong())
            push(budget.toLong())
            if (self == null) pushNil() else pushHandle(this, self)
            push(namespace)
            4
        }
    }

    /** Forgets a scope: its environment, every subscription it made and its tasks. */
    fun dropEnv(scope: Int) {
        owned("dropEnv")
        if (closed) return
        callHost("drop_env", 0) {
            push(scope.toLong())
            1
        }
    }

    /** Cancels every subscription [scope] made and ends its tasks, leaving its environment: it failed. */
    fun dropSubscriptions(scope: Int) {
        owned("dropSubscriptions")
        if (closed) return
        callHost("drop_subscriptions", 0) {
            push(scope.toLong())
            1
        }
    }

    /** Cancels every subscription on handles whose things are gone, and ends the tasks waiting on them. */
    fun dropTargets(targets: Collection<LuaHandle>) {
        owned("dropTargets")
        // A handle Lua holds no table for has nothing listening to it.
        val held = targets.filter { handles.find(it) != null }
        if (closed || held.isEmpty()) return
        callHost("drop_targets", 0) {
            pushValue(this, held)
            1
        }
    }

    /**
     * The scope whose code is running now (the innermost budget frame's), or null when no script's is: who a
     * handle method that makes something belongs to, as a handle's `:on` does.
     */
    fun runningScope(): Int? {
        owned("runningScope")
        if (closed) return null
        val lua = current
        val top = lua.top
        depth++
        try {
            lua.refGet(hostRef)
            lua.getField(-1, "running_scope")
            lua.remove(-2)
            lua.pCall(0, 1)
            return if (lua.isNumber(-1)) lua.toInteger(-1).toInt() else null
        } catch (e: LuaException) {
            return null
        } finally {
            lua.setTop(top)
            depth--
            if (depth == 0 && closeWanted) close()
        }
    }

    /** Fires [scope]'s own `nf.on("unload")` handlers. */
    fun unload(scope: Int) {
        owned("unload")
        if (closed) return
        callHost("unload", 0) {
            push(scope.toLong())
            1
        }
    }

    /**
     * Raises an event along [stages] with [payload] as its fields (see the
     * prelude's `host.emit`), then reads its [writable] fields back into the
     * payload, each by its field's codec. [only]: the package whose handlers
     * alone hear it (a package's `setting_changed`), or null for everyone's.
     * Null when the Lua state is closed or the event core itself failed;
     * handlers' own errors are reported from inside.
     */
    fun emit(
        stages: List<EventStage>,
        payload: LuaEvent,
        precancelled: Boolean,
        writable: List<String>,
        home: String,
        only: String? = null
    ): EmitOutcome? {
        owned("emit")
        if (closed) return null
        val lua = current
        val top = lua.top
        val raising = Emitting(payload, writable)
        emitting.addLast(raising)
        depth++
        try {
            lua.refGet(hostRef)
            lua.getField(-1, "emit")
            lua.remove(-2)
            pushValue(lua, stages.map { stage -> mapOf("event" to stage.event, "target" to stage.target) })
            // Spelled for the project's package first; the prelude has it spelled again for each other package's handlers.
            readingAs(home) { payload.push(LuaCall(this, lua)) }
            lua.push(precancelled)
            pushValue(lua, writable)
            lua.push(home)
            if (only == null) lua.pushNil() else lua.push(only)
            lua.pCall(6, 2 + writable.size)
            val call = LuaCall(this, lua)
            readingAs(lua.toString(top + 2) ?: home) {
                writable.forEachIndexed { k, field ->
                    // Read back even if nobody assigned it: a handler may have changed it in place (an item in a list),
                    // which nothing checked. What can't be read is left as it was.
                    try {
                        payload.write(field, call, top + 3 + k)
                    } catch (e: LuaApiException) {
                        raising.problems += e.message ?: "event.$field: unreadable"
                    }
                }
            }
            return EmitOutcome(lua.toBoolean(top + 1), raising.problems)
        } catch (e: LuaException) {
            return null
        } finally {
            emitting.removeLast()
            lua.setTop(top)
            depth--
            if (depth == 0 && closeWanted) close()
        }
    }

    /** An event being raised ([emit]), innermost last: its payload and its writable fields, and what couldn't be read back. */
    private class Emitting(val payload: LuaEvent, val writable: List<String>) {
        val problems = mutableListOf<String>()
    }

    private val emitting = ArrayDeque<Emitting>()

    /**
     * The innermost event's fields (the table at [index], spelled for package
     * [from]) spelled again for package [to]: its writable fields read back
     * into its payload as [from] wrote them, then the payload pushed as [to]
     * reads it. What the prelude asks before the first handler of another
     * package's, so each package reads the ids in it as its own code writes
     * them, and what one assigned carries on to the next.
     */
    private fun respell(lua: Lua, index: Int, from: String, to: String): Int {
        val raising = emitting.lastOrNull() ?: throw LuaApiException("no event is being raised")
        val call = LuaCall(this, lua)
        readingAs(from) {
            for (field in raising.writable) {
                lua.getField(index, field)
                try {
                    raising.payload.write(field, call, lua.top)
                } catch (e: LuaApiException) {
                    raising.problems += e.message ?: "event.$field: unreadable"
                } finally {
                    lua.pop(1)
                }
            }
        }
        readingAs(to) { raising.payload.push(call) }
        return 1
    }

    /**
     * The package (its namespace) the values Kotlin pushes or reads now are
     * spelled for: a call's arguments as the scope it calls reads them, an
     * event's payload as the package whose handlers get it next reads it.
     * Null otherwise, and inside every primitive call: then it's the calling
     * code's package.
     */
    var reading: String? = null
        private set

    /** Runs [block] with [reading] set to [namespace]. */
    inline fun <T> readingAs(namespace: String?, block: () -> T): T {
        val previous = reading
        setReading(namespace)
        try {
            return block()
        } finally {
            setReading(previous)
        }
    }

    @PublishedApi
    internal fun setReading(namespace: String?) {
        reading = namespace
    }

    /** Compiles [source] as [path] into [scope]'s globals and runs it. */
    fun runFile(budget: Int, scope: Int, path: String, source: String): CallResult = callHost("run_file") {
        push(budget.toLong())
        push(scope.toLong())
        push(path)
        push(source)
        4
    }

    /** Runs a module file through the require cache. */
    fun start(budget: Int, scope: Int, path: String): CallResult = callHost("start") {
        push(budget.toLong())
        push(scope.toLong())
        push(path)
        3
    }

    /** Calls a function stored with [ref], as [scope]'s code, whose package is [namespace]: its [args] are spelled for it. */
    fun invoke(budget: Int, scope: Int, ref: LuaRef, args: List<Any?> = emptyList(), namespace: String? = null): CallResult =
        callHost("invoke") { invokeArgs(budget, scope, ref, args, namespace) }

    /**
     * [invoke], timed by the profiler as a call of [kind] (`command`,
     * `complete`), and its first result read by [result] (named [where] in a
     * mistake) rather than as a plain value: a value it can't read is a
     * [LuaTypeMismatch], which the caller words for the script. Without a
     * [result], the value comes back as [invoke] gives it.
     */
    fun <T> invoke(
        budget: Int,
        scope: Int,
        ref: LuaRef,
        args: List<Any?>,
        namespace: String?,
        kind: String,
        result: LuaCodec<T>? = null,
        where: String = "result"
    ): CallResult = callHost("invoke_as", value = result?.let { codec -> { lua, index -> codec.read(LuaCall(this, lua), index, where) } }) {
        push(kind)
        1 + invokeArgs(budget, scope, ref, args, namespace)
    }

    private fun Lua.invokeArgs(budget: Int, scope: Int, ref: LuaRef, args: List<Any?>, namespace: String?): Int {
        push(budget.toLong())
        push(scope.toLong())
        refGet(slot(ref))
        readingAs(namespace) { for (arg in args) pushValue(this, arg) }
        return 3 + args.size
    }

    /**
     * The time each scope's code took since the last call, and how many calls
     * in that was, by scope id: only scopes that ran. [rotate] starts a new
     * window for [hotSpot]'s slowest calls.
     */
    fun takeCosts(rotate: Boolean): Map<Int, ScopeCost> = lines("take_costs", {
        push(rotate)
        1
    }) { (scope, nanos, calls) ->
        scope.toInt() to ScopeCost(nanos, calls.toInt())
    }

    /**
     * The scope the hook blamed for scripts going over their memory limit
     * (the one holding the most, which wasn't the one running), and the bytes
     * it holds; null when there's none to stop. Taken once a tick.
     */
    fun takeBlamed(): Pair<Int, Long>? {
        owned("takeBlamed")
        if (closed) return null
        val lua = current
        val top = lua.top
        depth++
        try {
            lua.refGet(hostRef)
            lua.getField(-1, "take_blamed")
            lua.remove(-2)
            lua.pCall(0, 2)
            if (!lua.isNumber(top + 1)) return null
            return lua.toInteger(top + 1).toInt() to lua.toInteger(top + 2)
        } catch (e: LuaException) {
            return null
        } finally {
            lua.setTop(top)
            depth--
            if (depth == 0 && closeWanted) close()
        }
    }

    /** About how many bytes each scope holds, by scope id: the prelude's census, which walks everything scripts reach. */
    fun memory(): Map<Int, Long> = lines("memory", { 0 }) { (scope, bytes) -> scope.toInt() to bytes }

    /** Subscriptions and tasks by scope id, for scopes that have any. */
    fun scopeCounts(): Map<Int, Pair<Int, Int>> = lines("scope_counts", { 0 }) { (scope, subscriptions, tasks) ->
        scope.toInt() to (subscriptions.toInt() to tasks.toInt())
    }

    /** The file and line (null for a script's body) of the function behind [scope]'s slowest recent call; null when it isn't project code. */
    fun hotSpot(scope: Int): Pair<String, Int?>? {
        owned("hotSpot")
        if (closed) return null
        val lua = current
        val top = lua.top
        depth++
        try {
            lua.refGet(hostRef)
            lua.getField(-1, "hot_spot")
            lua.remove(-2)
            lua.push(scope.toLong())
            lua.pCall(1, 2)
            val file = if (lua.isString(top + 1)) lua.toString(top + 1) else return null
            return file!! to (if (lua.isNumber(top + 2)) lua.toInteger(top + 2).toInt() else null)
        } catch (e: LuaException) {
            return null
        } finally {
            lua.setTop(top)
            depth--
            if (depth == 0 && closeWanted) close()
        }
    }

    /** The scopes with a live handler for [event] on `nf` (`nf.on`, `nf.once`). */
    fun scopesListening(event: String): Set<Int> = lines("scopes_listening", {
        push(event)
        1
    }) { (scope) -> scope.toInt() to Unit }.keys

    /** Calls a host function answering one line per entry of numbers, and reads each with [entry]. */
    private inline fun <K, V> lines(name: String, args: Lua.() -> Int, entry: (List<Long>) -> Pair<K, V>): Map<K, V> {
        owned(name)
        if (closed) return emptyMap()
        val lua = current
        val top = lua.top
        depth++
        try {
            lua.refGet(hostRef)
            lua.getField(-1, name)
            lua.remove(-2)
            lua.pCall(lua.args(), 1)
            val text = lua.toString(top + 1).orEmpty()
            if (text.isEmpty()) return emptyMap()
            return text.split('\n').associate { line -> entry(line.split(' ').map(String::toLong)) }
        } catch (e: LuaException) {
            return emptyMap()
        } finally {
            lua.setTop(top)
            depth--
            if (depth == 0 && closeWanted) close()
        }
    }

    /**
     * The project file whose code is running now: the innermost script frame
     * on the running thread's stack, at its project path (a package's at its
     * package path, `library:modules/bank/init.lua`). Null when no script's
     * code is on the stack (Kotlin reading an event's fields back). What a
     * bare id a script passes the API is resolved against. Asked once per
     * primitive call: the stack under it doesn't change while it runs (a
     * call back into Lua returns to it), so a primitive pushing many items
     * walks it once ([callingFileAsked]).
     */
    fun callingFile(): String? {
        owned("callingFile")
        if (closed) return null
        if (callingFileAsked) return callingFileAnswer
        return walkToCallingFile().also {
            callingFileAnswer = it
            callingFileAsked = inPrimitive
        }
    }

    /**
     * Whether a Lua tail call sits between the running primitive and the
     * frame [callingFile] finds. Lua 5.4 reuses a tail-calling function's
     * frame (`return player:ban()`), so the code that made the call is gone
     * from the stack and [callingFile] names whoever called *it*: a library
     * function the project called would pass for the project. A check that
     * grants by package (`Requirements`) asks this and doesn't trust the walk
     * when it's true.
     */
    fun callerHiddenByTailCall(): Boolean {
        owned("callerHiddenByTailCall")
        if (closed) return false
        val lua = current
        val top = lua.top
        depth++
        try {
            lua.refGet(tailCallRef)
            lua.pCall(0, 1)
            return lua.toBoolean(-1)
        } catch (e: LuaException) {
            // Can't tell (the hook stopped it): as good as hidden.
            return true
        } finally {
            lua.setTop(top)
            depth--
            if (depth == 0 && closeWanted) close()
        }
    }

    /** Whether [callingFile] has answered during the primitive call running now, and what. */
    private var callingFileAsked = false
    private var callingFileAnswer: String? = null

    /** Whether a primitive is running (its answers are kept only then). */
    private var inPrimitive = false

    private fun walkToCallingFile(): String? {
        val lua = current
        val top = lua.top
        depth++
        try {
            lua.refGet(hostRef)
            lua.getField(-1, "calling_file")
            lua.remove(-2)
            lua.pCall(0, 1)
            return if (lua.isString(-1)) lua.toString(-1) else null
        } catch (e: LuaException) {
            return null
        } finally {
            lua.setTop(top)
            depth--
            if (depth == 0 && closeWanted) close()
        }
    }

    /**
     * Turns the profiler's bookkeeping on or off: while it's on, every call
     * in's own time is also kept per scope and per function it ran, for
     * [takeProfile]. Off forgets what wasn't taken.
     */
    fun setProfiling(on: Boolean) {
        if (closed) return
        callHost("profile", 0) {
            push(on)
            1
        }
    }

    /** What the profiler kept since the last call: each scope's own time per function it ran. */
    fun takeProfile(): List<CallCost> {
        if (closed) return emptyList()
        val lua = current
        val top = lua.top
        depth++
        try {
            lua.refGet(hostRef)
            lua.getField(-1, "take_profile")
            lua.remove(-2)
            lua.pCall(0, 1)
            val text = lua.toString(top + 1).orEmpty()
            if (text.isEmpty()) return emptyList()
            return text.split('\n').map { line ->
                val f = line.split('\t')
                CallCost(
                    scope = f[0].toInt(),
                    kind = f[1],
                    file = f[2].ifEmpty { null },
                    line = f[3].toInt().takeIf { it > 0 },
                    nanos = f[4].toLong(),
                    calls = f[5].toInt(),
                    max = f[6].toLong()
                )
            }
        } catch (e: LuaException) {
            return emptyList()
        } finally {
            lua.setTop(top)
            depth--
            if (depth == 0 && closeWanted) close()
        }
    }

    /**
     * The debugger's state in Lua (`prelude/debugger.lua`): attached, with
     * [breakpoints] (each project path's lines), stopping on uncaught errors
     * when [errors], and when [pause], stopping at the next line any script
     * runs. Stops call the `debug.wait` primitive (see `debug/Debugger.kt`).
     */
    fun debugConfigure(breakpoints: Map<String, Set<Int>>, errors: Boolean, pause: Boolean) {
        if (closed) return
        val lines = breakpoints.flatMap { (file, lines) -> lines.sorted().map { "$file\t$it" } }.joinToString("\n")
        callHost("debug_configure", 0) {
            push(lines)
            push(errors)
            push(pause)
            3
        }
    }

    /** Forgets the debugger: no breakpoints, no step under way, no line hook. */
    fun debugDetach() {
        if (closed) return
        callHost("debug_detach", 0) { 0 }
    }

    fun forget(prefix: String) {
        owned("forget")
        if (closed) return
        callHost("forget", 0) {
            push(prefix)
            1
        }
    }

    /**
     * Runs [block], a stretch of the server's own ticks run from inside a script's call (`nf.test.advance`),
     * with that call's frame taken off (`host.detach`): what runs in it is charged to budgets of its own, as on a
     * server. The frame is put back after, its time limit moved on by the time it was off.
     */
    fun <T> detached(block: () -> T): T {
        owned("detached")
        val lua = current
        val top = lua.top
        lua.refGet(hostRef)
        lua.getField(-1, "detach")
        lua.remove(-2)
        lua.pCall(0, 2)
        val from = lua.toInteger(-1)
        lua.pop(1)
        val frame = lua.ref()
        lua.setTop(top)
        try {
            return block()
        } finally {
            if (!closed) {
                val at = current
                at.refGet(hostRef)
                at.getField(-1, "reattach")
                at.remove(-2)
                at.refGet(frame)
                at.push(from)
                at.pCall(2, 0)
                at.unref(frame)
            }
        }
    }

    /** Keeps the value at [index] of [lua]'s stack alive in the registry; [unref] releases it. */
    fun ref(lua: Lua, index: Int): LuaRef {
        owned("ref")
        lua.pushValue(index)
        return LuaRef.of(generation, lua.ref())
    }

    /** Lets go of a value [ref] kept. Nothing to do once the state is closed: the registry went with it. */
    fun unref(ref: LuaRef) {
        owned("unref")
        val slot = slot(ref)
        if (!closed) main.unref(slot)
    }

    /** [ref]'s registry slot, when this host kept it. */
    private fun slot(ref: LuaRef): Int {
        if (ref.generation != generation) throw StaleLuaRef(ref, generation)
        return ref.slot
    }

    // ---- the one Lua↔JSON codec ----------------------------------------------------

    /**
     * The value at [index] as JSON text, by the prelude's one Lua↔JSON codec
     * (`host.json_encode`, which `nf.json`, saved data, item data and the
     * tables `nf.menus.create` and friends take all go through): typed values
     * tagged, problem paths starting at [root] (`data`, `item.data`), each
     * saying what can't be [verb]. The cost is the running call's.
     */
    fun encodeData(lua: Lua, index: Int, root: String, verb: String = SAVED, skip: Set<String> = emptySet()): EncodedData {
        owned("encodeData")
        val absolute = if (index < 0) lua.top + index + 1 else index
        return encode(lua, root, verb, skip) { pushValue(absolute) }
    }

    /** A kept value (a `data()` table) encoded for saving; nothing is charged to any script. */
    fun encodeData(value: LuaRef, root: String): EncodedData {
        owned("encodeData")
        val slot = slot(value)
        return encode(current, root, SAVED, emptySet()) { refGet(slot) }
    }

    /**
     * The value at [index] as JSON; anything in it JSON can't hold is a
     * [LuaApiException] naming where, from [root]. A table's keys in [skip]
     * are left out.
     */
    fun json(lua: Lua, index: Int, root: String, skip: Set<String> = emptySet()): JsonElement {
        owned("json")
        // Plain data is read here, as the codec would write it, costing the script nothing ([plainJson]).
        val at = if (index < 0) lua.top + index + 1 else index
        val plain = if (lua.type(at) == Lua.LuaType.TABLE) {
            plain.table(lua, at, 0, exact = true, skip = skip)
        } else {
            plain.value(lua, at, 0, exact = true)
        }
        if (plain != null) return plain
        val encoded = encodeData(lua, at, root, "written as JSON", skip)
        encoded.problems.firstOrNull()?.let { throw LuaApiException(it) }
        return Json.parseToJsonElement(requireNotNull(encoded.text))
    }

    /**
     * The table at [index] as JSON, as [encodeData] reads it (a problem, from
     * [root], is an error), at a fraction of the cost: the codec is Lua, and
     * every instruction of it is the calling script's. The table is read here
     * when it's plain data ([plainJson]), its field [kept] (an item's `data`,
     * kept as text) exactly as the codec writes it; a [kept] with more in it
     * goes through the codec alone, and a table with anything else in it goes
     * through the codec whole, which says what's wrong as it always has.
     */
    fun fieldsJson(lua: Lua, index: Int, root: String, kept: String): JsonElement {
        owned("fieldsJson")
        val at = if (index < 0) lua.top + index + 1 else index
        val fields = plain.fields(lua, at, 0, exact = false, skip = setOf(kept)) ?: return saved(lua, at, root)
        lua.getField(at, kept)
        try {
            if (lua.isNil(-1)) return JsonObject(fields)
            val value = plain.value(lua, -1, 1, exact = true) ?: saved(lua, -1, "$root.$kept")
            return JsonObject(fields + (kept to value))
        } finally {
            lua.pop(1)
        }
    }

    /** The value at [index] as JSON by the codec; anything in it that can't be saved is an error naming where. */
    private fun saved(lua: Lua, index: Int, root: String): JsonElement {
        val encoded = encodeData(lua, index, root)
        encoded.problems.firstOrNull()?.let { throw LuaApiException(it) }
        return Json.parseToJsonElement(requireNotNull(encoded.text))
    }

    private inline fun encode(lua: Lua, root: String, verb: String, skip: Set<String>, push: Lua.() -> Unit): EncodedData {
        val top = lua.top
        depth++
        try {
            lua.refGet(hostRef)
            lua.getField(-1, "json_encode")
            lua.remove(-2)
            lua.push()
            lua.push(root)
            lua.push(verb)
            if (skip.isEmpty()) lua.pushNil() else pushValue(lua, skip.associateWith { true })
            lua.pCall(4, 2)
            val text = if (lua.isString(top + 1)) lua.toString(top + 1) else null
            val problems = lua.toString(top + 2).orEmpty().split('\n').filter { it.isNotEmpty() }
            return EncodedData(text, problems)
        } finally {
            lua.setTop(top)
            depth--
            if (depth == 0 && closeWanted) close()
        }
    }

    /** A new table holding saved [json] (null: an empty table), its values typed again, kept until [unref]. */
    fun keepData(json: JsonElement?): LuaRef {
        owned("keepData")
        val lua = current
        if (json == null) lua.createTable(0, 0) else pushJson(lua, json)
        return LuaRef.of(generation, lua.ref())
    }

    /**
     * [element] as Lua, by the same codec the other way (`host.json_decode`):
     * tagged values typed again. JSON without a key starting with "$" has
     * nothing for the codec to do, so it's made here, costing the script nothing.
     */
    private fun pushJson(lua: Lua, element: JsonElement) {
        if (!PlainJson.tagged(element)) return PlainJson.push(lua, element)
        lua.refGet(hostRef)
        lua.getField(-1, "json_decode")
        lua.remove(-2)
        PlainJson.push(lua, element)
        lua.pCall(1, 1)
    }

    /**
     * The parameter names of the function at [path] (as [keys] walks it), and
     * whether it also takes `...`; null when there's no function there. For
     * the conformance test.
     */
    fun params(path: List<String>, scope: Int? = null): Pair<List<String>, Boolean>? {
        owned("params")
        val lua = current
        val top = lua.top
        try {
            lua.refGet(hostRef)
            lua.getField(-1, "params")
            lua.refGet(hostRef)
            if (scope != null) {
                lua.getField(-1, "env")
                lua.push(scope.toLong())
                lua.pCall(1, 1)
            }
            for (key in path) {
                if (!lua.isTable(-1)) return null
                lua.getField(-1, key)
            }
            // host.params(fn): move the function down past what walked to it.
            val fn = lua.top
            lua.pushValue(top + 2)
            lua.pushValue(fn)
            lua.pCall(1, 2)
            if (lua.isNil(-2)) return null
            val names = lua.toString(-2)!!.split(',').filter { it.isNotEmpty() }
            return names to lua.toBoolean(-1)
        } finally {
            lua.setTop(top)
        }
    }

    /**
     * Runs [block] with the value at [path] in [scope]'s globals on the stack
     * (at the index it's given), as a primitive would have it: for the
     * conformance test, which reads values scripts built with the codecs.
     */
    fun <T> at(path: List<String>, scope: Int, block: (LuaCall, Int) -> T): T {
        owned("at")
        val lua = current
        val top = lua.top
        try {
            lua.refGet(hostRef)
            lua.getField(-1, "env")
            lua.push(scope.toLong())
            lua.pCall(1, 1)
            for (key in path) {
                require(lua.isTable(-1)) { "nothing at ${path.joinToString(".")}" }
                lua.getField(-1, key)
            }
            return block(LuaCall(this, lua), lua.top)
        } finally {
            lua.setTop(top)
        }
    }

    /** Names defined in a table reached from the host: `env`, a scope's environment, or `classes.Node`. For the conformance test. */
    fun keys(path: List<String>, scope: Int? = null): List<String> {
        owned("keys")
        val lua = current
        val top = lua.top
        try {
            lua.refGet(hostRef)
            if (scope != null) {
                lua.getField(-1, "env")
                lua.push(scope.toLong())
                lua.pCall(1, 1)
            }
            for (key in path) {
                if (!lua.isTable(-1)) return emptyList()
                lua.getField(-1, key)
            }
            if (!lua.isTable(-1)) return emptyList()
            val keys = mutableListOf<String>()
            val table = lua.top
            // `pairs` first, as a script would see it: a scope's `nf` fills its namespaces then.
            lua.getGlobal("pairs")
            lua.pushValue(table)
            lua.pCall(1, 3)
            lua.pop(3)
            lua.pushNil()
            while (lua.next(table) != 0) {
                if (lua.isString(-2)) keys += lua.toString(-2)!!
                lua.pop(1)
            }
            return keys.sorted()
        } finally {
            lua.setTop(top)
        }
    }

    /**
     * Pushes a Kotlin value by what it is: null, Boolean, numbers, String,
     * List, Map (string or whole-number keys), [JsonElement] (by the JSON
     * codec, so tagged values come back typed), [LuaHandle], [Vec3],
     * [LuaLocation], [ItemData], a generated shape ([LuaShape]), a [LuaRef] or
     * [LuaValue] (the value itself) or a [JFunction]. What `any` returns and
     * callbacks' arguments go through; typed positions use their [LuaCodec].
     */
    fun pushValue(lua: Lua, value: Any?) {
        owned("pushValue")
        lua.checkStack(3)
        when (value) {
            null -> lua.pushNil()
            is Boolean -> lua.push(value)
            is Int -> lua.push(value.toLong())
            is Long -> lua.push(value)
            is Number -> lua.push(value.toDouble() as Number)
            is String -> lua.push(value)
            is JFunction -> lua.push(value)
            is LuaRef -> lua.refGet(slot(value))
            is LuaValue -> value.push(lua)
            is LuaHandle -> pushHandle(lua, value)
            is Vec3 -> pushVec3(lua, value)
            is LuaLocation -> pushLocation(lua, value)
            is ItemData -> marshal.pushItem(lua, value)
            is ResourceName -> lua.push(marshal.spell(value.name))
            is LuaShape -> value.push(LuaCall(this, lua))
            is Coded<*> -> value.push(LuaCall(this, lua))
            is JsonElement -> pushJson(lua, value)
            is List<*> -> {
                lua.createTable(value.size, 0)
                value.forEachIndexed { index, item ->
                    pushValue(lua, item)
                    lua.rawSetI(-2, index + 1)
                }
            }
            is Map<*, *> -> {
                lua.createTable(0, value.size)
                for ((key, item) in value) {
                    if (key is Long || key is Int) {
                        // `table<integer, V>`: integer keys stay integers.
                        lua.push((key as Number).toLong())
                        pushValue(lua, item)
                        lua.rawSet(-3)
                    } else {
                        pushValue(lua, item)
                        lua.setField(-2, key.toString())
                    }
                }
            }
            else -> throw IllegalArgumentException("can't pass ${value::class.simpleName} to Lua")
        }
    }

    /**
     * The table for [handle]: its entry's from the prelude's cache, or a new
     * one (empty, its class's metatable, noted in `ids` and the cache), so two
     * of the same thing are the same table. One the handle says more about
     * than its table shows (a `Mob` where the table is an `Entity`'s) gets its
     * class's metatable: the same table, with more methods.
     */
    private fun pushHandle(lua: Lua, handle: LuaHandle) {
        val entry = handles.intern(handle)
        lua.checkStack(4)
        lua.refGet(cacheRef)
        lua.push(entry.key)
        lua.rawGet(-2)
        if (lua.isNil(-1)) {
            lua.pop(1)
            lua.createTable(0, 0)
            showClass(lua, -1, entry)
            lua.refGet(idsRef)
            lua.pushValue(-2)
            lua.push(entry.key)
            lua.rawSet(-3)
            lua.pop(1)
            lua.push(entry.key)
            lua.pushValue(-2)
            lua.rawSet(-4)
        } else if (entry.shown != entry.handle.luaClass) {
            showClass(lua, -1, entry)
        }
        lua.remove(-2)
    }

    /** A `Vec3` as the prelude's `new_vec3` makes one: its numbers in the array part, its genuine metatable. */
    private fun pushVec3(lua: Lua, vector: Vec3) {
        lua.checkStack(3)
        lua.createTable(3, 0)
        lua.push(vector.x as Number)
        lua.rawSetI(-2, 1)
        lua.push(vector.y as Number)
        lua.rawSetI(-2, 2)
        lua.push(vector.z as Number)
        lua.rawSetI(-2, 3)
        lua.refGet(vec3MtRef)
        lua.setMetatable(-2)
    }

    /** A `Location` as the prelude's `new_location` makes one: the world's handle, the position, a facing that may be nil. */
    private fun pushLocation(lua: Lua, location: LuaLocation) {
        lua.checkStack(3)
        lua.createTable(4, 0)
        pushHandle(lua, location.world)
        lua.rawSetI(-2, 1)
        pushVec3(lua, location.position)
        lua.rawSetI(-2, 2)
        location.yaw?.let {
            lua.push(it as Number)
            lua.rawSetI(-2, 3)
        }
        location.pitch?.let {
            lua.push(it as Number)
            lua.rawSetI(-2, 4)
        }
        lua.refGet(locationMtRef)
        lua.setMetatable(-2)
    }

    /**
     * Calls a host function and reads its five results: status, value or
     * message, file, line, traceback. [results] is 0 for the host functions
     * that return nothing.
     */
    private inline fun callHost(
        name: String,
        results: Int = 5,
        noinline value: ((Lua, Int) -> Any?)? = null,
        args: Lua.() -> Int
    ): CallResult {
        owned(name)
        if (closed) return CallResult.Failed(ScriptFailure("the Lua state was closed", null, null, null))
        val lua = current
        val top = lua.top
        depth++
        try {
            lua.refGet(hostRef)
            lua.getField(-1, name)
            lua.remove(-2)
            val count = lua.args()
            lua.pCall(count, results)
            if (results == 0) return CallResult.Ok(null)
            return read(lua, top + 1, value)
        } catch (e: LuaException) {
            return CallResult.Failed(ScriptFailure(e.message ?: "Lua error", null, null, null))
        } finally {
            lua.setTop(top)
            depth--
            if (depth == 0 && closeWanted) close()
        }
    }

    private fun read(lua: Lua, at: Int, value: ((Lua, Int) -> Any?)?): CallResult = when (lua.toString(at)) {
        "ok" -> CallResult.Ok(
            when {
                value != null -> value(lua, at + 1)
                lua.isBoolean(at + 1) -> lua.toBoolean(at + 1)
                lua.isNumber(at + 1) && !lua.isString(at + 1) -> lua.toNumber(at + 1)
                lua.isString(at + 1) -> lua.toString(at + 1)
                else -> null
            }
        )
        else -> CallResult.Failed(
            ScriptFailure(
                message = lua.toString(at + 1) ?: "error",
                file = if (lua.isString(at + 2)) lua.toString(at + 2) else null,
                line = if (lua.isNumber(at + 3)) lua.toInteger(at + 3).toInt() else null,
                traceback = if (lua.isString(at + 4)) lua.toString(at + 4) else null
            )
        )
    }

    override fun close() {
        owned("close")
        if (closed) return
        if (depth > 0) {
            closeWanted = true
            return
        }
        closed = true
        main.close()
    }

    companion object {
        /** What saved data can't be, in a problem: `data.fn: a function can't be saved`. */
        const val SAVED = "saved"

        /** How deep [plainJson] goes before leaving a table to the codec, which saves no deeper (`MAX_DATA_DEPTH`). */

        private val GENERATIONS = AtomicInteger()

        private val PRELUDE: String = resource("prelude.lua")

        /**
         * [callerHiddenByTailCall]'s walk: from the primitive that asks (level 2: level 1 is
         * this function) out to the first project frame (`@` source), whether a frame on the
         * way was entered by a tail call, its caller's frame gone.
         */
        private const val TAIL_CALL = """
            local getinfo = debug.getinfo
            return function()
              for level = 2, 200 do
                local info = getinfo(level, "St")
                if info == nil or info.source:sub(1, 1) == "@" then
                  return false
                end
                if info.istailcall then
                  return true
                end
              end
              return false
            end
        """

        /**
         * The prelude's modules (`prelude/<name>.lua`, the generated `schema` among them), by
         * name: what the entry `require`s. A test holds this list to the folder.
         */
        val MODULE_NAMES: List<String> = listOf(
            "api", "args", "census", "checks", "debugger", "events", "gates", "guard", "handles", "handwritten", "host",
            "json", "sandbox", "schema", "scopes", "std", "tasks", "utilities", "values"
        )

        private val MODULES: Map<String, String> = MODULE_NAMES.associateWith { resource("prelude/$it.lua") }

        /** The standard library's caps, which the prelude puts in place of the library's unbounded functions. */
        private val CAPS: String = resource("caps.lua")

        /** Generated from packages/api (`pnpm generate`): the Lua half of the Kotlin-implemented API. */
        private val BINDINGS: String = resource("bindings.lua")

        private fun resource(name: String): String = LuaHost::class.java.getResourceAsStream("/dev/netherforge/plugin/lua/$name")
            ?.use { it.readBytes().decodeToString() }
            ?: error("$name missing from the runtime jar")

        /** luajava loads chunks from direct buffers. */
        fun direct(text: String): ByteBuffer {
            val bytes = text.encodeToByteArray()
            return ByteBuffer.allocateDirect(bytes.size).put(bytes).flip()
        }
    }
}
