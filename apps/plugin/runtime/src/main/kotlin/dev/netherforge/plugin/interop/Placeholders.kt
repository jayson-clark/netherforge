package dev.netherforge.plugin.interop

import dev.netherforge.plugin.RuntimeLog
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.lua.CallResult
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaFunction
import dev.netherforge.plugin.platform.PlaceholderResolver
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.Scripts
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.TickPhase
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The `%<namespace>_<key>%` placeholders scripts offer through PlaceholderAPI
 * (`nf.placeholders.register`): one expansion per namespace, owned by the
 * script that registered it and gone with it (a reload, the session ending).
 *
 * **Threads.** PlaceholderAPI asks from whatever thread the asking plugin is
 * on, and the Lua state belongs to the main thread alone. On the main thread
 * the script's callback runs and its answer is remembered per key and player;
 * on any other thread nothing of Lua is touched, and the placeholder answers
 * what the callback last gave for that key and player (null, so the
 * placeholder is left as written, before it ever has). A plugin that parses
 * the same placeholder from the main thread now and then, or a script that
 * parses it itself, keeps those answers fresh.
 *
 * A callback that asks for its own placeholder again (through
 * `nf.placeholders.parse`) isn't run a second time: that answer is null, so
 * it can't recurse. An error in the callback is reported like any handler's
 * (once in a while, with its file and line) and answers null.
 *
 * PlaceholderAPI may be reloaded or enabled after NetherForge. Each tick that
 * follows a plugin change, expansions it no longer has are offered again.
 */
internal class Placeholders(private val platform: Platform, private val scripts: Scripts, private val log: RuntimeLog) : RuntimeService {
    override val name get() = "placeholders"

    private class Expansion(val scope: Scope, val namespace: String, val callback: LuaFunction) {
        /** What the callback last answered, by player and key: what other threads are given. */
        val answers = ConcurrentHashMap<String, String>()

        /** The callback is running (main thread only): a request it makes itself isn't answered. */
        var running = false

        @Volatile
        var live = true
    }

    /** By namespace; touched on the main thread only. */
    private val expansions = LinkedHashMap<String, Expansion>()

    @Volatile
    private var mainThread: Thread? = null

    private var stale = false

    /**
     * Offers `%[namespace]_<key>%` for [scope]. A mistake is a
     * [LuaApiException]; [callback] is let go of when one is thrown.
     */
    fun register(scope: Scope, namespace: String, callback: LuaFunction, own: String = namespace) {
        try {
            if (!NAMESPACE.matches(namespace)) {
                throw LuaApiException(
                    "bad argument 'namespace' (\"$namespace\" can't be a placeholder namespace: lowercase letters and digits, " +
                        "starting with a letter, at most 32 characters, since PlaceholderAPI ends it at the first `_`)"
                )
            }
            // A package's own ([own], the calling package's namespace): it answers `%<its namespace>…_…%` and never
            // another plugin's (`player`, `server`, `luckperms`), which it could otherwise take before their
            // expansion loads. Spelled without `_`, where PlaceholderAPI ends a placeholder's namespace.
            val prefix = own.filter { it.isLetterOrDigit() }
            if (!namespace.startsWith(prefix)) {
                throw LuaApiException(
                    "bad argument 'namespace' (a package's placeholders start with its own namespace, \"$prefix\": " +
                        "\"$prefix\" or \"${prefix}stats\", not \"$namespace\")"
                )
            }
            expansions[namespace]?.let {
                throw LuaApiException("the placeholder namespace \"$namespace\" is already registered by ${it.scope.owner.label}")
            }
            mainThread = Thread.currentThread()
            val expansion = Expansion(scope, namespace, callback)
            if (!platform.placeholders.register(namespace, resolver(expansion))) {
                throw LuaApiException("the placeholder namespace \"$namespace\" is already used by another plugin")
            }
            expansions[namespace] = expansion
        } catch (e: LuaApiException) {
            scripts.unref(callback.ref)
            throw e
        }
    }

    private fun resolver(expansion: Expansion) = PlaceholderResolver { player, key ->
        val cacheKey = "${player ?: "-"}|$key"
        if (Thread.currentThread() !== mainThread) return@PlaceholderResolver expansion.answers[cacheKey]
        if (!expansion.live || expansion.running) return@PlaceholderResolver null
        expansion.running = true
        try {
            val answer = ask(expansion, player, key)
            if (answer != null) {
                if (expansion.answers.size >= MAX_ANSWERS) expansion.answers.clear()
                expansion.answers[cacheKey] = answer
            } else {
                expansion.answers.remove(cacheKey)
            }
            answer
        } finally {
            expansion.running = false
        }
    }

    private fun ask(expansion: Expansion, player: UUID?, key: String): String? {
        val handle = player?.let { LuaHandle.Player(it.toString()) }
        return when (val result = scripts.callKept(expansion.scope, expansion.callback.ref, listOf(key, handle))) {
            is CallResult.Ok -> text(result.value)
            is CallResult.Failed -> {
                scripts.handlerFailed(expansion.scope, "placeholder %${expansion.namespace}_$key%", result.failure, false)
                null
            }
            CallResult.Absent -> null
        }
    }

    /** What a callback returned as placeholder text: a string, a number (a whole one without `.0`) or a boolean; anything else is no value. */
    private fun text(value: Any?): String? = when (value) {
        is String -> value
        is Boolean -> value.toString()
        is Double -> if (value.isFinite() &&
            value == Math.rint(value) &&
            Math.abs(value) < WHOLE_LIMIT
        ) {
            value.toLong().toString()
        } else {
            value.toString()
        }
        is Number -> value.toString()
        else -> null
    }

    override fun pluginsChanged() {
        stale = true
    }

    override fun tick(phase: TickPhase) {
        if (phase != TickPhase.UPKEEP || !stale) return
        stale = false
        if (expansions.isEmpty() || !platform.plugins.isEnabled(PLUGIN)) return
        // PlaceholderAPI was reloaded or enabled again: it forgot what was registered with the one before.
        for (expansion in expansions.values) {
            if (platform.placeholders.isRegistered(expansion.namespace)) continue
            if (!platform.placeholders.register(expansion.namespace, resolver(expansion))) {
                log.warn(
                    "The placeholder namespace \"${expansion.namespace}\" is used by another plugin now, so %${expansion.namespace}_…% has no value"
                )
            }
        }
    }

    override fun scopeReleased(scope: Scope) {
        for (expansion in expansions.values.filter { it.scope == scope }) release(expansion)
    }

    override fun stop() {
        for (expansion in expansions.values.toList()) release(expansion)
    }

    private fun release(expansion: Expansion) {
        expansions.remove(expansion.namespace)
        expansion.live = false
        if (platform.plugins.isEnabled(PLUGIN)) platform.placeholders.unregister(expansion.namespace)
        scripts.unref(expansion.callback.ref)
    }

    override fun costs(): Map<String, (Scope) -> Int> = mapOf("placeholders" to { scope -> expansions.values.count { it.scope == scope } })

    private companion object {
        const val PLUGIN = "placeholderapi"
        const val MAX_ANSWERS = 4096
        const val WHOLE_LIMIT = 1e15
        val NAMESPACE = Regex("[a-z][a-z0-9]{0,31}")
    }
}
