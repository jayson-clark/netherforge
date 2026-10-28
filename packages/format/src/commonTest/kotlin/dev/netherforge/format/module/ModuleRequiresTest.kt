package dev.netherforge.format.module

import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.Projects
import dev.netherforge.format.testManifest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A module without `init.lua` is a library: `module.no-init` only while nothing requires it. */
class ModuleRequiresTest {
    private val manifest = "netherforge.json" to testManifest()

    private fun noInit(vararg files: Pair<String, String?>) =
        Projects.load(MapProjectSource(mapOf(manifest, *files))).problems.filter { it.code == "module.no-init" }.map { it.file }.sorted()

    @Test
    fun namesAreReadOffLiteralRequiresOutsideComments() {
        val text = """
            local a = require("combat")
            local b = require 'combat.damage'
            local c = require(name)
            -- local d = require("old")
            local e = require ( "loot.tables" ) -- require("also_old")
        """.trimIndent()
        assertEquals(listOf("combat", "combat.damage", "loot.tables"), ModuleRequires.names(text))
    }

    @Test
    fun aRequiredLibraryIsNotWarnedAbout() {
        assertEquals(
            listOf("modules/unused"),
            noInit(
                "modules/main/init.lua" to "local util = require(\"util.strings\")",
                "modules/util/strings.lua" to "return {}",
                "modules/(world)/noise/init.lua" to "return {}",
                "centities/tower/centity.json" to null,
                "terrain/hills.lua" to "local n = require(\"worldgen\")",
                "modules/worldgen/shape.lua" to "return {}",
                "modules/unused/a.lua" to "return {}"
            )
        )
    }

    @Test
    fun aModuleRequiringItselfOrAFileBesideItDoesNotCount() {
        assertEquals(
            listOf("modules/helpers", "modules/lib"),
            noInit(
                "modules/lib/a.lua" to "return require(\"lib.b\")",
                "modules/lib/b.lua" to "return {}",
                // `helpers` is the file beside the script, not the module.
                "centities/tower/centity.json" to null,
                "centities/tower/script.lua" to "local h = require(\"helpers\")",
                "centities/tower/helpers.lua" to "return {}",
                "modules/helpers/x.lua" to "return {}"
            )
        )
    }

    @Test
    fun anExportedModuleIsALibraryForOthers() {
        val exporting = "netherforge.json" to testManifest(""", "exports": { "modules": ["api"] }""")
        val problems = Projects.load(MapProjectSource(mapOf(exporting, "modules/api/a.lua" to "return {}"))).problems
        assertEquals(emptyList(), problems.filter { it.code == "module.no-init" })
    }
}
