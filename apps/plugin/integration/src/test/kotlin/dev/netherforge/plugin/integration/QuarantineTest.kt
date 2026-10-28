package dev.netherforge.plugin.integration

import dev.netherforge.plugin.Quarantined
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every quarantined scenario or step links the issue that tracks its fix ([Quarantined]). */
class QuarantineTest {
    @Test
    fun `every quarantined scenario links its issue`() {
        assertEquals(emptyList(), Quarantined.problems("dev.netherforge.plugin.integration"))
    }
}
