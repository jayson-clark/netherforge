package dev.netherforge.format

import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.format.project.Projects
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.fail

/**
 * The golden cases whose `expected.json` is the exact list of problems loading them produces:
 * `testdata/invalid/<case>/` (one project, loaded without game data) and `testdata/packages/<case>/`
 * (several projects side by side, `app` loaded with the others as its packages). A rule that needs
 * the game's data is a unit test's, which `ProblemCoverageTest` lists.
 *
 * Every case is checked and every failure collected, so one run shows them all (see [failAll]).
 */
object ProblemGoldens {
    const val INVALID = "packages/format/testdata/invalid"
    const val PACKAGES = "packages/format/testdata/packages"
    const val EXPECTED = "expected.json"

    private val serializer = ListSerializer(Problem.serializer())

    /** Every `expected.json` of both kinds of case, by its path. */
    fun expectedFiles(): List<String> = TestFiles.dirs(INVALID).map { "$INVALID/$it/$EXPECTED" } +
        TestFiles.dirs(PACKAGES).map { "$PACKAGES/$it/$EXPECTED" }

    /** The problems an `expected.json` lists. */
    fun expected(path: String): List<Problem> =
        CanonicalJson.json.decodeFromString(serializer, TestFiles.read(path) ?: fail("$path doesn't exist"))

    /** Loads `testdata/invalid/<case>`. */
    fun loadInvalid(case: String): Case {
        val base = "$INVALID/$case"
        val files = TestFiles.list(base).filter { it != EXPECTED }
        return Case(base, Projects.load(MapProjectSource(files.associateWith { TestFiles.read("$base/$it") })))
    }

    /** Loads `testdata/packages/<case>`'s `app` and its packages. */
    fun loadPackages(case: String): Case = Case("$PACKAGES/$case", loadTestProject("$PACKAGES/$case", "app"))

    /** One loaded case: its folder and what loading it made. */
    class Case(val base: String, val snapshot: ProjectSnapshot) {
        /**
         * What's wrong with this case, empty when nothing is: its problems against `expected.json`
         * (which updating goldens rewrites instead), a code that isn't in [ProblemCodes] or has
         * another severity, and an invalid case that reports nothing.
         */
        fun failures(mustHaveProblems: Boolean): List<String> {
            val out = mutableListOf<String>()
            val problems = snapshot.problems
            val actual = CanonicalJson.write(serializer, problems)
            val path = "$base/$EXPECTED"
            val expected = TestFiles.read(path)
            if (actual != expected) {
                if (TestFiles.updateGolden) {
                    TestFiles.write(path, actual)
                } else {
                    out += "$path: problems differ. Run with UPDATE_GOLDEN=1 to accept.\n--- actual\n$actual--- expected\n$expected"
                }
            }
            if (mustHaveProblems && problems.isEmpty()) out += "$base reports no problems; an invalid case should"
            for (problem in problems) {
                val code = problem.code?.let(ProblemCodes::byCode)
                if (code == null) {
                    out += "$base: \"${problem.code}\" isn't in ProblemCodes"
                } else if (code.severity != problem.severity) {
                    out += "$base: ${code.code} is a ${code.severity} in ProblemCodes but was reported as a ${problem.severity}"
                }
            }
            return out
        }
    }

    /** Fails once with every case's failures, when there are any. */
    fun failAll(what: String, total: Int, failures: List<String>) {
        if (failures.isEmpty()) return
        fail("${failures.size} problem(s) across $total $what:\n\n" + failures.joinToString("\n\n"))
    }
}
