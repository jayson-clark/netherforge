package dev.netherforge.format

import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.Contents
import dev.netherforge.format.project.DocumentKind
import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.project.Kinds
import dev.netherforge.format.project.LockFile
import dev.netherforge.format.project.LockKind
import dev.netherforge.format.ref.RefUse
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The shared fixtures. Runs identically on the JVM and in JS, which is how we
 * know the plugin and the editor read and write files the same way.
 *
 * `UPDATE_GOLDEN=1 pnpm test` rewrites the expectations; review the diff.
 */
class GoldenTest {

    /** Every example project loads clean and every JSON file in it is already canonical. */
    @Test
    fun examplesAreCanonicalAndValid() {
        val examples = TestFiles.dirs("examples")
        assertTrue(examples.isNotEmpty(), "no examples found")
        val covered = mutableSetOf<KindSpec<*, *>>()
        for (example in examples) {
            writeLock(example)
            val files = TestFiles.list("examples/$example")
            val snapshot = loadTestProject("examples", example)
            assertEquals(emptyList(), snapshot.problems, "examples/$example has problems")
            covered += Kinds.all.filter { snapshot[it].isNotEmpty() }

            for (path in files) {
                val kind = Kinds.classify(path)?.document?.let(Kinds::document) ?: continue
                val text = TestFiles.read("examples/$example/$path")!!
                val canonical = canonicalize(kind, text, path)
                if (canonical != text) {
                    if (TestFiles.updateGolden) {
                        TestFiles.write("examples/$example/$path", canonical)
                    } else {
                        fail(
                            "examples/$example/$path isn't in canonical form. Run with UPDATE_GOLDEN=1 to rewrite it.\n--- expected\n$canonical"
                        )
                    }
                }
            }
        }
        // Minecraft's own binary files aren't ours to ship, so only those kinds may be missing.
        val missing = Kinds.all.filter { it !in covered && it.contents != Contents.BINARY }.map { it.id }
        assertEquals(emptyList(), missing, "every kind has a resource in the examples")
    }

    /**
     * An example with dependencies has the `netherforge.lock` resolving them
     * gives (`examples/showcase` depends on `examples/library`): its versions,
     * paths and content hashes, as hashed on this platform.
     */
    @Test
    fun examplesAreLocked() {
        val locked = TestFiles.dirs("examples").filter { writeLock(it) != null }
        assertTrue("showcase" in locked, "examples/showcase depends on a package")
    }

    /**
     * The project templates (`examples/template_*`, what the editor's "Add template" puts into a
     * project) are packages meant for Copy into project: each exports every resource it has, so
     * the editor offers all of them, and it carries script tests (`*_test.lua`) the runner runs.
     */
    @Test
    fun templatesExportWhatTheyHaveAndHaveTests() {
        val templates = TestFiles.dirs("examples").filter { it.startsWith("template_") }
        assertEquals(listOf("template_minigame", "template_rpg_mob", "template_shop"), templates.sorted())
        for (template in templates) {
            val snapshot = loadTestProject("examples", template)
            assertEquals(template, snapshot.manifest?.namespace, "examples/$template is its own namespace")
            for (kind in Kinds.all) {
                val have = snapshot[kind].keys
                if (have.isEmpty()) continue
                val exported = snapshot.manifest?.exports?.get(kind.folder).orEmpty().toSet()
                assertEquals(have, exported, "examples/$template exports every ${kind.id} it has")
            }
            assertTrue(
                TestFiles.list("examples/$template").any { it.endsWith("_test.lua") },
                "examples/$template has script tests"
            )
        }
    }

    /** The lock [example]'s packages make, compared with (or, updating goldens, written over) its `netherforge.lock`. */
    private fun writeLock(example: String): LockFile? {
        val lock = loadTestProject("examples", example).lock()?.takeIf { it.packages.isNotEmpty() } ?: return null
        val path = "examples/$example/${LockFile.FILE_NAME}"
        val text = LockKind.write(lock)
        if (text != TestFiles.read(path)) {
            if (TestFiles.updateGolden) {
                TestFiles.write(path, text)
            } else {
                fail("$path isn't what its packages resolve to. Run with UPDATE_GOLDEN=1 to rewrite it.\n--- expected\n$text")
            }
        }
        return lock
    }

    /**
     * Every reference each example makes, as the walker finds it, is
     * `packages/format/testdata/references/<example>.json`: a new reference
     * that isn't marked `@Ref` (or a glyph outside `@MiniMessage` text) shows
     * up as missing there, and every one listed resolves (the example loads clean).
     */
    @Test
    fun everyReferenceInTheExamplesIsFound() {
        val serializer = ListSerializer(RefUse.serializer())
        for (example in TestFiles.dirs("examples")) {
            val snapshot = loadTestProject("examples", example)
            val actual = CanonicalJson.write(serializer, snapshot.references.uses)
            val path = "packages/format/testdata/references/$example.json"
            if (actual != TestFiles.read(path)) {
                if (TestFiles.updateGolden) {
                    TestFiles.write(path, actual)
                } else {
                    fail("$path: the references found differ. Run with UPDATE_GOLDEN=1 to accept.\n--- actual\n$actual")
                }
            }
        }
    }

    /**
     * Each case in `packages/format/testdata/invalid/<case>/` is a small project; its
     * `expected.json` lists the problems loading it must produce (see [ProblemGoldens]). Every
     * case is checked before the test fails, so one run reports all the cases that differ.
     */
    @Test
    fun invalidCasesReportExpectedProblems() {
        val cases = TestFiles.dirs(ProblemGoldens.INVALID)
        assertTrue(cases.isNotEmpty(), "no invalid cases found")
        val failures = cases.flatMap { ProblemGoldens.loadInvalid(it).failures(mustHaveProblems = true) }
        ProblemGoldens.failAll("invalid cases", cases.size, failures)
    }

    private fun <T> canonicalize(kind: DocumentKind<T>, text: String, path: String): String = when (val parsed = kind.parse(text, path)) {
        is CanonicalJson.Parsed.Ok -> kind.write(parsed.value, path)
        is CanonicalJson.Parsed.Failed -> fail("$path doesn't parse: ${parsed.problem.message}")
    }
}
