package dev.netherforge.plugin.binding
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaCodec
import dev.netherforge.plugin.lua.LuaHost
import dev.netherforge.plugin.lua.LuaMarshal
import dev.netherforge.plugin.lua.LuaRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.Scripts
import kotlinx.serialization.json.JsonElement
import party.iroiro.luajava.Lua
import java.util.concurrent.CompletionStage

/**
 * What the generated primitives (`LuaPrimitives.kt`) need from the runtime
 * besides the codecs: the host whose state they run on, the calling script,
 * and items (checked against the server and the project's packs, which is
 * why they're the runtime's to cross, [LuaMarshal]).
 */
interface Marshal : LuaMarshal {
    /** The Lua state the primitives run on. */
    val host: LuaHost

    /** The script whose `nf` was called; its id is at [index]. */
    fun caller(lua: Lua, index: Int): Caller

    /**
     * Refuses the function [what] (`Player:ban`) unless the calling script's package has
     * declared [requirement] (`requires` in the spec), with an error saying what to declare.
     */
    fun requires(requirement: String, what: String)

    /** An `Item` table already read as JSON (one inside a `nf.dialogs.create` definition), checked the same way. */
    fun item(json: JsonElement): ItemData

    /**
     * Hands the async function [what]'s [result] to the session: once it's
     * done, [waker] (the script's callback, or the waiting task's) is called
     * as [scope]'s code with `value, err` on the main thread ([codec] crosses
     * the value). The wait's id, which the prelude keeps for `Task:cancel`.
     */
    fun <T> await(what: String, scope: Scope, result: CompletionStage<T>, waker: LuaRef, codec: LuaCodec<T>): Int
}

/**
 * The script calling a namespace function (`nf.spawn`). Every `nf` function
 * gets one, the way a handle method gets its handle; the scope is looked up
 * only when the function needs it, so a function that doesn't still works when
 * called from a script that has unloaded.
 */
class Caller(private val scripts: Scripts, val scopeId: Int) {
    val scope: Scope get() = scripts.scope(scopeId) ?: throw LuaApiException("this script has been unloaded")
}
