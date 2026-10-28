package dev.netherforge.plugin

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The plugin refuses to enable on Folia; the check itself is all the runtime can test without one. */
class FoliaTest {
    @Test
    fun `Folia is told by its regionised server class`() {
        assertTrue(Folia.detected { it == "io.papermc.paper.threadedregions.RegionizedServer" })
        assertFalse(Folia.detected { false })
        // This JVM isn't Folia.
        assertFalse(Folia.detected())
    }
}
