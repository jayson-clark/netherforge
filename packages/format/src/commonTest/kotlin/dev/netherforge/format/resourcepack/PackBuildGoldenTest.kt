package dev.netherforge.format.resourcepack

import dev.netherforge.format.TestFiles
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.loadTestProject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.fail

/**
 * Every file of each example's built resource pack, as the server builds the one players get as they join (and each
 * pack sent on demand on its own), for resource pack format [75, 0]: `testdata/packs/<example>.json`
 * (`UPDATE_GOLDEN=1` rewrites them), which the Rust port's build is held to. A file is its text, a copied project
 * file `copy of <path>`, bytes of the build's own their SHA-256. Without game data there's no plan of the blocks'
 * states, as in every test.
 */
class PackBuildGoldenTest {
    @Test
    fun theExamplesPacksBuildAsTheGoldensSay() {
        val failures = mutableListOf<String>()
        for (example in TestFiles.dirs("examples")) {
            val snapshot = loadTestProject("examples", example)
            if (!PackLayout.needed(snapshot)) continue
            val description = snapshot.manifest?.name?.takeIf { it.isNotBlank() } ?: "NetherForge"
            val (separate, joined) = snapshot.compiledResourcePacks.values.partition { it.onDemand }
            val builds =
                mapOf("joined" to PackLayout.build(joined, FORMAT, description, null, snapshot.vanilla, VideoSound.of(snapshot)) { null }) +
                    separate.associate { pack ->
                        pack.key to
                            PackLayout.build(listOf(pack), FORMAT, pack.description ?: pack.name ?: pack.key) { null }
                    }
            val actual = CanonicalJson.print(
                JsonObject(builds.mapValues { (_, files) -> JsonObject(files.mapValues { (_, entry) -> JsonPrimitive(text(entry)) }) })
            ) + "\n"
            val path = "packages/format/testdata/packs/$example.json"
            if (actual == TestFiles.read(path)) continue
            if (TestFiles.updateGolden) TestFiles.write(path, actual) else failures += path
        }
        if (failures.isNotEmpty()) fail("${failures.joinToString()} differ. Run with UPDATE_GOLDEN=1 to accept.")
    }

    private fun text(entry: PackLayout.Entry): String = when (entry) {
        is PackLayout.Entry.Text -> entry.text
        is PackLayout.Entry.Copy -> "copy of ${entry.projectPath}"
        is PackLayout.Entry.Bytes -> "bytes ${TestFiles.sha256OfBytes(entry.bytes)}"
    }

    private companion object {
        val FORMAT = listOf(75, 0)
    }
}
