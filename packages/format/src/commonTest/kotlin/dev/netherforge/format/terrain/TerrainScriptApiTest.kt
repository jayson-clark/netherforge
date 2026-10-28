package dev.netherforge.format.terrain

import dev.netherforge.format.TestFiles
import dev.netherforge.format.json.CanonicalJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Lua a terrain's script sees ([TerrainScriptGlue]) is exactly what its spec says
 * (`packages/api/src/terrain.ts`, as `pnpm generate` writes it to `packages/api/generated/terrain.json`): the
 * spec is what the LuaLS stubs and the reference page are made of, so a function only one of them has is a bug.
 */
class TerrainScriptApiTest {
    private val spec = CanonicalJson.json.parseToJsonElement(TestFiles.read("packages/api/generated/terrain.json")!!).jsonObject

    private fun functions(name: String): Set<String> =
        spec["classes"]!!.jsonArray.map { it.jsonObject }.single { it.name == name }["functions"]!!.jsonArray
            .map { it.jsonObject.name }.toSet()

    private val JsonObject.name: String get() = this["name"]!!.jsonPrimitive.content

    /** The functions the glue defines on [table] (`function Chunk.fill(`). */
    private fun defined(table: String): Set<String> =
        Regex("""^function $table\.(\w+)\(""", RegexOption.MULTILINE).findAll(TerrainScriptGlue.SOURCE).map { it.groupValues[1] }.toSet()

    @Test
    fun theGlueHasExactlyTheSpecsFunctions() {
        assertEquals(functions("Terrain"), defined("terrain"))
        assertEquals(functions("Noise"), defined("Noise"))
        assertEquals(functions("Chunk"), defined("Chunk"))
        assertEquals(functions("Plan"), defined("Plan"))
        assertEquals(functions("Structure"), defined("Structure"))
        assertEquals(functions("Scatter"), defined("Scatter"))
    }

    @Test
    fun theStagesAreTheSpecs() {
        val stages = spec["shapes"]!!.jsonArray.map { it.jsonObject }.single { it.name == "TerrainStages" }["fields"]!!.jsonArray
            .map { it.jsonObject.name }.toSet()
        val glue = Regex("""local STAGES =\s*\{([^}]*)\}""").find(TerrainScriptGlue.SOURCE)!!.groupValues[1]
        assertEquals(stages, Regex("""(\w+) = true""").findAll(glue).map { it.groupValues[1] }.toSet())
    }

    @Test
    fun whatTheSandboxRemovesIsTheSpecs() {
        val removed = spec["removed"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        // The script's environment is built from a list of what it keeps; everything else of Lua's is gone.
        val kept = Regex("""for _, name in ipairs\(\{([^}]*)\}\) do\s+env\[name\]""").find(TerrainScriptGlue.SOURCE)!!
            .groupValues[1].split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }.toSet()
        for (name in removed.filter { '.' !in it }) assertEquals(false, name in kept, "$name is kept")
        assertEquals(true, "env.math.randomseed = nil" in TerrainScriptGlue.SOURCE && "env.string.dump = nil" in TerrainScriptGlue.SOURCE)
    }
}
