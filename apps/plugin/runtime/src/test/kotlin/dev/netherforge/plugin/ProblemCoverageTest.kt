package dev.netherforge.plugin

import dev.netherforge.format.ProblemCodes
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every code the plugin reports while a project runs ([ProblemCodes.reportedAtRuntime]) is produced by one of
 * this module's tests, so one can't lose its last case unseen. format's own `ProblemCoverageTest` holds every
 * other code to a golden or a unit test there; these are the ones no project load reports.
 *
 * The test named for a code must exist and name the code (its string or its `ProblemCodes` constant).
 */
class ProblemCoverageTest {
    private val tests = "apps/plugin/runtime/src/test/kotlin/dev/netherforge/plugin"

    /** Each code, and the test (under [tests], without `.kt`) that produces it. */
    private val producedBy: Map<String, String> = mapOf(
        "runtime.wasm" to "wasm/WasmModulesTest",
        "terrain.script-failed" to "terrain/TerrainTest",
        "migration.failed" to "store/PackageDatabaseTest",
        "video.stale" to "screen/ScreenTest",
        "runtime.minecraft-unsupported" to "session/ReloadTest",
        "runtime.unbundled" to "project/PackagesTest",
        "runtime.bundle-hash" to "project/PackagesTest",
        "runtime.plugin-missing" to "interop/InteropTest",
        "runtime.web-disabled" to "web/WebRoutesTest",
        "runtime.network-disabled" to "network/NetworkTest",
        "runtime.bots-disabled" to "bots/BotTest",
        "runtime.blocks" to "block/BlockTest",
        "runtime.terrain" to "terrain/TerrainTest",
        "runtime.dimension-type" to "world/DimensionTypeTest",
        "runtime.restart" to "advancement/AdvancementTest",
        "runtime.datapack" to "datapack/DatapackTest",
        "settings.file" to "settings/SettingsTest",
        "settings.value" to "settings/SettingsTest",
        "settings.unknown" to "settings/SettingsTest",
        "script.error" to "script/EventTest",
        "script.slow" to "script/ScriptCostsTest",
        "particles.budget" to "particle/ParticleEffectTest",
        "runtime.pack-format" to "pack/PackTest"
    )

    @Test
    fun everyRuntimeCodeHasATest() {
        assertEquals(ProblemCodes.reportedAtRuntime.map { it.code }.sorted(), producedBy.keys.sorted())
    }

    @Test
    fun theTestsNamedProduceTheirCodes() {
        for ((code, test) in producedBy) {
            val file = TestServer.repo().resolve("$tests/$test.kt")
            assertTrue(file.exists(), "$code: $test doesn't exist")
            val text = file.readText()
            val constant = code.uppercase().replace('.', '_').replace('-', '_')
            assertTrue(
                "\"$code\"" in text || Regex("\\b$constant\\b").containsMatchIn(text),
                "$code: $test doesn't name it, so it can't be what tests it"
            )
        }
    }
}
