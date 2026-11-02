package dev.netherforge.format.migration

import dev.netherforge.format.project.Kinds
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.Projects
import dev.netherforge.format.testManifest
import kotlin.test.Test
import kotlin.test.assertEquals

/** `migrations/NNN_name.sql`: the names and the numbering, which is all format knows of them. */
class MigrationTest {
    private fun problems(vararg names: String): List<Pair<String, String>> {
        val files = names.associate { "migrations/$it" to "SELECT 1;" } + ("netherforge.json" to testManifest())
        return Projects.load(MapProjectSource(files)).problems.map { it.code!! to it.file }
    }

    @Test
    fun numbersRunFromOneWithNothingSkipped() {
        assertEquals(emptyList(), problems("001_init.sql", "002_rank.sql", "003_more_rows.sql"))
        assertEquals(emptyList(), problems())
    }

    @Test
    fun aGapIsReportedOnTheFileAfterIt() {
        assertEquals(listOf("migration.gap" to "migrations/003_late.sql"), problems("001_init.sql", "003_late.sql"))
        // The first has to be 001.
        assertEquals(listOf("migration.gap" to "migrations/002_init.sql"), problems("002_init.sql"))
    }

    @Test
    fun aNumberUsedTwiceIsReportedOnTheSecondFile() {
        assertEquals(
            listOf("migration.duplicate" to "migrations/001_other.sql"),
            problems("001_init.sql", "001_other.sql")
        )
    }

    @Test
    fun aFileNotNamedByTheRuleIsAnError() {
        assertEquals(listOf("migration.name" to "migrations/init.sql"), problems("init.sql"))
        assertEquals(listOf("migration.name" to "migrations/1_init.sql"), problems("1_init.sql"))
        // An id can't hold a capital or a hyphen, so those are not even resources.
        assertEquals(listOf("project.id" to "migrations/001_Init.sql"), problems("001_Init.sql"))
    }

    @Test
    fun otherFilesInTheFolderAreStrays() {
        assertEquals(listOf("project.stray-file" to "migrations/old/001_init.sql"), problems("old/001_init.sql"))
    }

    @Test
    fun aServersVersionsGoBesideEveryMigrationOrNone() {
        assertEquals(
            emptyList(),
            problems(
                "001_init.sql",
                "002_rank.sql",
                "postgres/001_init.sql",
                "postgres/002_rank.sql",
                "mysql/001_init.sql",
                "mysql/002_rank.sql"
            )
        )
        // A dialect with a version of one migration and not the next: reported on the one without.
        assertEquals(
            listOf("migration.dialects" to "migrations/002_rank.sql"),
            problems("001_init.sql", "002_rank.sql", "postgres/001_init.sql")
        )
        // A server's version with no SQLite one: reported on it (and the dialect's gap on the migration without one).
        assertEquals(
            setOf("migration.dialects" to "migrations/postgres/002_rank.sql", "migration.dialects" to "migrations/001_init.sql"),
            problems("001_init.sql", "postgres/002_rank.sql").toSet()
        )
        // A folder that isn't a dialect is still a stray.
        assertEquals(listOf("project.stray-file" to "migrations/oracle/001_init.sql"), problems("001_init.sql", "oracle/001_init.sql"))
        val file = MigrationKind.of("001_init", listOf("001_init.sql", "postgres/001_init.sql"))
        assertEquals(setOf("postgres"), file.dialects)
        assertEquals("migrations/(admin)/mysql/001_init.sql", MigrationKind.dialectPathOf("001_init", "(admin)", "mysql"))
    }

    @Test
    fun theNumberIsTheNameBeforeTheUnderscore() {
        assertEquals(1, MigrationKind.of("001_init", emptyList()).number)
        assertEquals(null, MigrationKind.of("init", emptyList()).number)
        assertEquals("migrations/001_init.sql", MigrationKind.pathOf("001_init"))
        assertEquals(MigrationKind, Kinds.inFolder("migrations"))
    }
}
