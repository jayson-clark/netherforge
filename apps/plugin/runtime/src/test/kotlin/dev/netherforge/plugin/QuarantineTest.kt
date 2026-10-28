package dev.netherforge.plugin

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every quarantined test here links the issue that tracks its fix ([Quarantined]). */
class QuarantineTest {
    @Test
    fun `every quarantined test links its issue`() {
        assertEquals(emptyList(), Quarantined.problems("dev.netherforge.plugin") { it == Examples::class.java })
    }

    @Test
    fun `a bad link and a bare tag are both found`() {
        assertEquals(
            listOf(
                "${Examples::class.java.name}.bare: tagged \"quarantine\" without @Quarantined(issue)",
                "${Examples::class.java.name}.linked: \"soon\" isn't an issue's URL"
            ),
            Quarantined.problems(listOf(Examples::class.java)).sorted()
        )
    }

    /** Not tests (no @Test): only what the check reads. */
    class Examples {
        @Quarantined("soon")
        fun linked() = Unit

        @Tag(Quarantined.TAG)
        fun bare() = Unit

        @Quarantined("https://github.com/jayson-clark/netherforge/issues/1")
        fun fine() = Unit
    }
}
