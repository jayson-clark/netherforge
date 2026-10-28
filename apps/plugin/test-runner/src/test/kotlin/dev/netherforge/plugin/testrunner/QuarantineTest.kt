package dev.netherforge.plugin.testrunner

import dev.netherforge.plugin.Quarantined
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every quarantined test here links the issue that tracks its fix ([Quarantined]). */
class QuarantineTest {
    @Test
    fun `every quarantined test links its issue`() {
        assertEquals(emptyList(), Quarantined.problems("dev.netherforge.plugin.testrunner"))
    }
}
