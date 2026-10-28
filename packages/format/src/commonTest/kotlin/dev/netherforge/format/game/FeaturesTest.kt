package dev.netherforge.format.game

import dev.netherforge.format.ProblemSink
import dev.netherforge.format.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The feature table, through its test hook: a made-up table, since the real
 * one ([FeatureTable.CURRENT]) may gate nothing a test could rely on.
 */
class FeaturesTest {
    private val table = FeatureTable(
        listOf(
            Feature("pause_menu_dialogs", "26.2", "Dialogs in the pause menu"),
            Feature("new_thing", "26.3.1", "The new thing")
        )
    )

    @Test
    fun aTargetHasWhatArrivedInItOrBefore() {
        assertFalse(table.has("1.21.11", "pause_menu_dialogs"))
        assertFalse(table.has("26.1.2", "pause_menu_dialogs"))
        assertTrue(table.has("26.2", "pause_menu_dialogs"))
        assertTrue(table.has("26.3", "pause_menu_dialogs"))
        assertFalse(table.has("26.3", "new_thing"))
        assertTrue(table.has("26.3.1", "new_thing"))
    }

    @Test
    fun usingWhatTheTargetLacksIsAProblemNamingTheVersion() {
        val sink = ProblemSink("dialogs/menu/dialog.json")
        table.require("26.1.2", "pause_menu_dialogs", sink, "$.pauseMenu")
        table.require("26.2", "pause_menu_dialogs", sink, "$.pauseMenu")
        val problem = sink.problems.single()
        assertEquals("project.feature", problem.code)
        assertEquals(Severity.ERROR, problem.severity)
        assertEquals("Dialogs in the pause menu needs Minecraft 26.2; this project targets 26.1.2", problem.message)
    }

    @Test
    fun aTargetThatIsntAVersionIsntHeldAgainstAnything() {
        // The manifest reports that one itself.
        assertTrue(table.has(null, "new_thing"))
        assertTrue(table.has("twenty six", "new_thing"))
    }

    @Test
    fun anUnknownFeatureIsNetherForgesBug() {
        assertFailsWith<IllegalArgumentException> { table.has("26.3", "nope") }
        assertFailsWith<IllegalArgumentException> { FeatureTable(listOf(Feature("a", "26.2", "A"), Feature("a", "26.3", "A again"))) }
    }

    @Test
    fun theRealTableOnlyHoldsWhatArrivedAfterTheOldestSupportedVersion() {
        for (feature in FeatureTable.CURRENT.all) {
            assertTrue(feature.since > MinecraftVersion.of(MinecraftVersion.OLDEST_SUPPORTED), feature.id)
        }
    }
}
