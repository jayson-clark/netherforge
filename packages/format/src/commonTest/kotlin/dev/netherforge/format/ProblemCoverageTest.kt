package dev.netherforge.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every [ProblemCode] is produced by some test, so a rule can't be added (or lose its last case)
 * without anyone seeing it fire.
 *
 * The rule: a code is in some golden `expected.json` ([ProblemGoldens]: `testdata/invalid/` or
 * `testdata/packages/`), or it's in [testedElsewhere] with the test that produces it and why a golden
 * can't: it needs the game's data (goldens load without any) or something a project file can't hold.
 * The test named must exist and name the code (its string or its `ProblemCodes` constant). A code the
 * plugin reports while a project runs ([ProblemCodes.reportedAtRuntime]) is the runtime's own
 * `ProblemCoverageTest`'s to check. A code only [untested] may skip all that, and each one there is a
 * gap to close, not a place to park a code.
 */
class ProblemCoverageTest {

    /** Where a code that no golden produces is produced instead, and why there. */
    private class Elsewhere(val test: String, val why: String)

    private val format = "packages/format/src/commonTest/kotlin/dev/netherforge/format"

    /** [test] is its path under commonTest's `dev/netherforge/format/`, without `.kt`: `game/FeaturesTest`. */
    private fun unit(test: String, why: String) = Elsewhere("$format/$test.kt", why)

    private val gameData = "it needs the game's data, and goldens load without any"
    private val wasmFacts = "it needs what a host read of a module (WasmInfo), and goldens read no module"

    private val workspace = "it's a workspace file's, above the projects goldens load"

    private val testedElsewhere: Map<String, Elsewhere> = mapOf(
        "workspace.name" to unit("workspace/WorkspaceTest", workspace),
        "workspace.project-path" to unit("workspace/WorkspaceTest", workspace),
        "workspace.missing-project" to unit("workspace/WorkspaceTest", workspace),
        "workspace.empty-pattern" to unit("workspace/WorkspaceTest", workspace),
        "workspace.server-name" to unit("workspace/WorkspaceTest", workspace),
        "workspace.server-project" to unit("workspace/WorkspaceTest", workspace),
        "workspace.arrival" to unit("workspace/WorkspaceTest", workspace),
        "workspace.unknown-server" to unit("workspace/NetworkUsesTest", workspace),
        // The real feature table may gate nothing a project could rely on; FeaturesTest uses a table of its own.
        "project.feature" to unit("game/FeaturesTest", "it needs a feature the target lacks; the real table may have none"),
        "centity.unknown-block" to unit("centity/CentityTest", gameData),
        "centity.block-property" to unit("centity/CentityTest", gameData),
        "centity.unknown-item" to unit("centity/CentityTest", gameData),
        "item.unknown" to unit("particle/ParticleEffectValidatorTest", gameData),
        "item.unknown-enchantment" to unit("item/ItemRulesTest", gameData),
        "item.unknown-attribute" to unit("item/ItemRulesTest", gameData),
        "item.unknown-block" to unit("item/ItemRulesTest", gameData),
        "item.unknown-tooltip-component" to unit("item/ItemRulesTest", gameData),
        "item.attribute-amount" to unit("item/ItemRulesTest", "JSON can't hold a number that isn't finite; a script's item can"),
        "recipe.unknown-item" to unit("game/GameDataTest", gameData),
        "recipe.unknown-tag" to unit("game/GameDataTest", gameData),
        "loot.unknown-vanilla" to unit("loot/LootTest", gameData),
        "loot.unknown-tool" to unit("loot/LootTest", gameData),
        "loot.unknown-enchantment" to unit("loot/LootTest", gameData),
        "block.carriers" to unit("block/BlockTest", "it needs the game's note block states to run out of"),
        "game_block.unknown" to unit("block/GameBlockTest", gameData),
        "game_block.carrier" to unit("block/GameBlockTest", "it needs the game's note block states, which the carriers are"),
        "migration.name" to
            unit("migration/MigrationTest", "MigrationTest loads a project per rule and variant (the first number, gaps, names)"),
        "migration.duplicate" to unit("migration/MigrationTest", "MigrationTest loads a project per rule and variant"),
        "migration.gap" to unit("migration/MigrationTest", "MigrationTest loads a project per rule and variant"),
        "advancement.unknown-icon" to unit("advancement/AdvancementTest", gameData),
        "advancement.unknown-trigger" to unit("advancement/AdvancementTest", gameData),
        "structure.unknown-biome" to unit("world/StructureGenerationTest", gameData),
        "particle.unknown" to unit("particle/ParticleEffectValidatorTest", gameData),
        "particle.unsupported" to unit("particle/ParticleEffectValidatorTest", gameData),
        "particle.option" to unit("particle/ParticleEffectValidatorTest", gameData),
        "particle.unknown-block" to unit("particle/ParticleEffectValidatorTest", gameData),
        "particle.block-property" to unit("particle/ParticleEffectValidatorTest", gameData),
        "datapack.version" to unit("datapack/DatapackTest", "it needs the game's data pack format"),
        "datapack.registry" to unit("datapack/DatapackTest", gameData),
        "datapack.override" to unit("datapack/DatapackTest", gameData),
        "dimension-type.infiniburn-tag" to unit("dimensiontype/DimensionTypeTest", gameData),
        "cutscene.key-limit" to unit("cutscene/CutsceneTest", "it takes more than 4096 keys, too many for a golden file"),
        "resource_pack.image" to unit("resourcepack/ResourcePackTest", "it needs a picture's pixels, which goldens don't read"),
        "resource_pack.replace-size" to unit("resourcepack/HudTest", "it needs a picture's pixels, which goldens don't read"),
        "resource_pack.replace-shape" to unit("resourcepack/GameTexturesTest", "it needs a picture's pixels, which goldens don't read"),
        "resource_pack.picture-size" to unit("resourcepack/ResourcePackTest", "it needs a picture's size, which goldens don't read"),
        "resource_pack.too-many" to unit("resourcepack/ResourcePackTest", "it takes thousands of skins, too many for a golden file"),
        "resource_pack.too-many-glyphs" to unit(
            "resourcepack/ResourcePackTest",
            "it takes thousands of glyphs, too many for a golden file"
        ),
        "wasm.invalid" to unit("wasm/WasmTest", wasmFacts),
        "wasm.import" to unit("wasm/WasmTest", wasmFacts),
        "wasm.threads" to unit("wasm/WasmTest", wasmFacts),
        "wasm.memory" to unit("wasm/WasmTest", wasmFacts),
        "wasm.exports" to unit("wasm/WasmTest", wasmFacts),
        "screen_font.grid" to unit("screen/ScreenTest", "it needs a picture's size, which goldens are loaded without")
    )

    /** Codes nothing tests yet, each with what it would take. Keep this short; every entry is a gap. */
    private val untested: Map<String, String> = emptyMap()

    private fun produced(): Set<String> =
        ProblemGoldens.expectedFiles().flatMap { path -> ProblemGoldens.expected(path).mapNotNull { it.code } }.toSet()

    @Test
    fun everyCodeIsProducedBySomeTest() {
        val produced = produced()
        val runtime = ProblemCodes.reportedAtRuntime.map { it.code }.toSet()
        val missing = ProblemCodes.all.map { it.code }
            .filter { it !in produced && it !in testedElsewhere && it !in untested && it !in runtime }
        assertEquals(
            emptyList(),
            missing,
            "no golden expected.json produces these codes: add a case under packages/format/testdata/invalid/ " +
                "(or, if a golden can't hold it, a unit test and an entry in testedElsewhere saying which and why)"
        )
    }

    @Test
    fun theListsHoldOnlyCodesNoGoldenProduces() {
        val produced = produced()
        for (code in testedElsewhere.keys + untested.keys) {
            assertTrue(ProblemCodes.byCode(code) != null, "\"$code\" isn't in ProblemCodes")
            assertTrue(code !in produced, "a golden produces \"$code\" now: take it out of ProblemCoverageTest's lists")
        }
        assertEquals(emptySet(), testedElsewhere.keys.intersect(untested.keys), "a code is either tested elsewhere or untested")
        val runtime = ProblemCodes.reportedAtRuntime.map { it.code }.toSet()
        assertEquals(emptySet(), (testedElsewhere.keys + untested.keys).intersect(runtime), "the runtime's ProblemCoverageTest has these")
    }

    @Test
    fun theTestsNamedProduceTheirCodes() {
        for ((code, where) in testedElsewhere) {
            assertTrue(where.why.isNotBlank(), "$code: say why a golden can't produce it")
            val text = TestFiles.read(where.test) ?: fail("$code: ${where.test} doesn't exist")
            val constant = code.uppercase().replace('.', '_').replace('-', '_')
            assertTrue(
                "\"$code\"" in text || Regex("\\b$constant\\b").containsMatchIn(text),
                "$code: ${where.test} doesn't name it, so it can't be what tests it"
            )
        }
    }
}
