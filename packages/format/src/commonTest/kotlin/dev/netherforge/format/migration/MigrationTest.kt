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
    fun theNumberIsTheNameBeforeTheUnderscore() {
        assertEquals(1, MigrationKind.of("001_init", emptyList()).number)
        assertEquals(null, MigrationKind.of("init", emptyList()).number)
        assertEquals("migrations/001_init.sql", MigrationKind.pathOf("001_init"))
        assertEquals(MigrationKind, Kinds.inFolder("migrations"))
    }
}
