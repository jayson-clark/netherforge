package dev.netherforge.format.lua

import dev.netherforge.format.terrain.CAPS_LUA

/**
 * The caps on Lua's standard library (`src/commonMain/lua/caps.lua`): every function whose work isn't bounded by
 * its arguments' size (`string.rep`, the pattern functions, `string.format`, `table.concat`, the table shifts and
 * sorts) works out the most a call could cost first, and refuses one past the limits. One text for both sandboxes
 * that run a package's Lua: the plugin's (`LuaHost`) and the terrain scripts' (`terrain-glue.lua`, through
 * `host_caps`), on the server and in the editor's preview alike.
 */
object LuaCaps {
    const val CHUNK_NAME = "=nf/caps"

    /** The chunk's text. Run with the library's own functions, the limits, `spend` and `refuse` (see its header). */
    val SOURCE: String = CAPS_LUA
}
