package dev.netherforge.plugin.lua
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The prelude is a set of modules (`prelude/<name>.lua`), each its own chunk, so Lua's limit of
 * 200 locals per function stays far off. These hold the list the host loads to the folders (the
 * hand-written modules, and the generated `schema`), and every module to half the limit.
 */
class PreludeModulesTest {
    private val runtime: File = File(assertNotNull(System.getenv("NETHERFORGE_REPO"), "NETHERFORGE_REPO"), "apps/plugin/runtime/src")
    private val lua = "dev/netherforge/plugin/lua"

    private fun luaFiles(dir: File): List<File> = dir.listFiles { file -> file.extension == "lua" }!!.sortedBy { it.name }

    private val modules: List<File> = luaFiles(File(runtime, "main/resources/$lua/prelude")) +
        luaFiles(File(runtime, "generated/lua/$lua/prelude"))

    @Test
    fun `the host loads exactly the modules in the folders`() {
        assertEquals(modules.map { it.nameWithoutExtension }.sorted(), LuaHost.MODULE_NAMES.sorted())
    }

    /** A module's top-level locals: the names a `local` at the start of a line declares (`local a, b = ...`, `local function f`). */
    private fun topLevelLocals(source: String): Int = source.lineSequence().sumOf { line ->
        when {
            line.startsWith("local function ") -> 1
            line.startsWith("local ") -> line.removePrefix("local ").substringBefore('=').split(',').count { it.isNotBlank() }
            else -> 0
        }
    }

    @Test
    fun `no module is near Lua's limit of 200 locals`() {
        val bindings = File(runtime, "generated/lua/$lua/bindings.lua")
        val counts = (modules + bindings).associate { it.name to topLevelLocals(it.readText()) }
        assertTrue(counts.size > 15, "only ${counts.size} files")
        val near = counts.filterValues { it > 100 }
        assertTrue(near.isEmpty(), "files with more than 100 top-level locals: $near")
    }
}
