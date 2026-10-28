package dev.netherforge.format.terrain

/**
 * The Lua a terrain script's state runs before the script (`src/commonMain/lua/terrain-glue.lua`): the sandbox,
 * the budget and the API the script sees (`terrain`, chunks, noises, a seeded `math.random`, `require` of the
 * project's modules). It's one text for every platform ([dev.netherforge.format.lua.LuaPlatform]), so a script behaves the same on the server and in the
 * editor's preview: only the host functions it's given (`host_*`, the Kotlin of [TerrainScriptState]) and the
 * `nf_*` entry points Kotlin calls cross between them. Scripts never see either: they run in an environment of
 * their own, without `debug`, `load`, `io`, `os`, `collectgarbage` or `nf`.
 *
 * `packages/api/src/terrain.ts` is the API's spec, which its LuaLS stubs and reference page are generated from;
 * `TerrainScriptApiTest` holds this text to it.
 */
internal object TerrainScriptGlue {
    const val CHUNK_NAME = "=nf/terrain"

    /** `src/commonMain/lua/terrain-glue.lua`, which the build makes a Kotlin constant of (`generateLuaSources`). */
    val SOURCE: String = TERRAIN_GLUE_LUA
}
