package dev.netherforge.format.advancement

import dev.netherforge.format.ProblemSink
import dev.netherforge.format.TestFiles
import dev.netherforge.format.datapack.DatapackEntry
import dev.netherforge.format.datapack.StartupDatapack
import dev.netherforge.format.datapack.TextJson
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.loadTestProject
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.Projects
import dev.netherforge.format.testManifest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class AdvancementTest {
    /** Text as the plugin's serializer would hand it back, readably: the MiniMessage with each glyph tag's character. */
    private val text = TextJson { mini, glyph ->
        val drawn = Regex("<glyph:([^>]+)>").replace(mini) { match ->
            glyph(match.groupValues[1])?.let { "[U+${it.first().code.toString(16)}]" }
                ?: ""
        }
        buildJsonObject { put("mini", drawn) }
    }

    @Test
    fun theGameSaysWhichIconsAndTriggersThereAre() {
        val game = GameDataBundle(
            minecraft = "26.3",
            registries = mapOf(
                "minecraft:item" to listOf("minecraft:diamond"),
                "minecraft:trigger_type" to listOf("minecraft:tick")
            )
        )
        val file = AdvancementFile(
            display = AdvancementDisplay(icon = AdvancementIcon(kind = "emerald"), title = "x", background = "minecraft:block/stone"),
            criteria = mapOf("a" to AdvancementCriterion("minecraft:tick"), "b" to AdvancementCriterion("minecraft:nope"))
        )
        val sink = ProblemSink("advancements/x.json")
        AdvancementValidator.validate(file, sink, game)
        assertEquals(
            listOf(
                "advancement.unknown-icon" to "Minecraft 26.3 has no item \"minecraft:emerald\"",
                "advancement.unknown-trigger" to "Minecraft 26.3 has no trigger \"minecraft:nope\""
            ),
            sink.problems.map { it.code to it.message }
        )
        val known = ProblemSink("advancements/x.json")
        AdvancementValidator.validate(
            file.copy(
                display = file.display!!.copy(icon = AdvancementIcon(kind = "diamond")),
                criteria = mapOf(
                    "a" to AdvancementCriterion("minecraft:tick")
                )
            ),
            known,
            game
        )
        assertEquals(emptyList(), known.problems)
    }

    @Test
    fun aNewAdvancementIsATreesRootAScriptCompletes() {
        val files = AdvancementKind.template("quest")
        val snapshot = Projects.load(MapProjectSource(files + ("netherforge.json" to testManifest())))
        assertEquals(emptyList(), snapshot.problems)
        val datapack = StartupDatapack.build(snapshot, listOf(94, 1), text)
        assertEquals(listOf("data/test/advancement/quest.json", "pack.mcmeta"), datapack.keys.toList())
        val written = (datapack.getValue("data/test/advancement/quest.json") as DatapackEntry.Text).text
        assertTrue("\"trigger\": \"minecraft:impossible\"" in written, written)
        assertTrue("\"min_format\": [94, 1]" in (datapack.getValue("pack.mcmeta") as DatapackEntry.Text).text)
    }

    @Test
    fun aProjectWithoutAdvancementsHasNoDatapack() {
        val snapshot = Projects.load(MapProjectSource(mapOf("netherforge.json" to testManifest())))
        assertEquals(emptyMap(), StartupDatapack.build(snapshot, listOf(94, 1), text))
    }

    /**
     * The example's advancements as the server gets them, the library's in
     * its own namespace: `testdata/datapack/showcase.json` holds every file
     * (`UPDATE_GOLDEN=1` rewrites it). Built twice, it's the same, which is
     * what tells the server nothing changed.
     */
    @Test
    fun theExamplesDatapackIsTheGamesFormat() {
        val snapshot = loadTestProject("examples", "showcase")
        val datapack = StartupDatapack.build(snapshot, listOf(94, 1), text)
        assertEquals(datapack, StartupDatapack.build(loadTestProject("examples", "showcase"), listOf(94, 1), text))
        val actual = CanonicalJson.print(
            buildJsonObject {
                for ((path, entry) in datapack) {
                    val text = when (entry) {
                        is DatapackEntry.Text -> entry.text
                        // A project file copied in as it is: a datapack's, or a structure's template.
                        is DatapackEntry.Copy -> "copy of ${entry.projectPath}"
                    }
                    put(path, JsonPrimitive(text))
                }
            }
        ) + "\n"
        val golden = "packages/format/testdata/datapack/showcase.json"
        if (actual != TestFiles.read(golden)) {
            if (TestFiles.updateGolden) {
                TestFiles.write(
                    golden,
                    actual
                )
            } else {
                fail("$golden differs. Run with UPDATE_GOLDEN=1 to accept.\n$actual")
            }
        }
        assertTrue("data/library/advancement/gem_collector.json" in datapack)
    }
}
