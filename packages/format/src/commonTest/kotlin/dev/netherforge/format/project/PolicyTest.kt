package dev.netherforge.format.project

import dev.netherforge.format.Severity
import dev.netherforge.format.testManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolicyTest {
    private fun policy(text: String) = Policy.parse(text).getOrThrow()

    @Test
    fun aPolicyRefusesKindsItDeniesOrDoesNotAllowAndHostsItDoesNotList() {
        val strict = policy(
            """{ "requires": { "allow": ["network", "db", "http"], "deny": ["moderation"], "http": ["api.example.com", "*.cdn.example.com"] } }"""
        )
        assertNull(strict.refuses("network"))
        assertEquals("requires.deny lists \"moderation\"", strict.refuses("moderation"))
        assertEquals("requires.allow doesn't list \"plugins\"", strict.refuses("plugin:vault"))
        assertNull(strict.refuses("http:api.example.com"))
        assertNull(strict.refuses("http:img.cdn.example.com"))
        assertEquals("requires.http doesn't list \"evil.com\"", strict.refuses("http:evil.com"))
        assertTrue(strict.stops)
    }

    @Test
    fun aPolicyThatIsNotOneSaysWhy() {
        assertTrue(Policy.parse("""{ "requires": { "deny": ["teleport"] } }""").exceptionOrNull()!!.message!!.contains("teleport"))
        assertTrue(Policy.parse("""{ "requires": { "refused": "ignore" } }""").isFailure)
        assertTrue(Policy.parse("not json").isFailure)
    }

    @Test
    fun theProjectsRefusedRequirementsAreProblemsOnTheManifestsThatDeclareThem() {
        val app = testManifest(""", "requires": { "moderation": true, "http": ["discord.com"], "network": true }""")
        val snapshot = Projects.load(MapProjectSource(mapOf("netherforge.json" to app)))
        val refusing = policy("""{ "requires": { "deny": ["moderation"], "http": ["api.example.com"] } }""")
        val problems = refusing.check(snapshot)
        assertEquals(2, problems.size)
        assertTrue(problems.all { it.code == "runtime.policy" && it.severity == Severity.ERROR && it.file == "netherforge.json" })
        assertTrue(problems.first().message.contains("moderation"))
        val degrading = policy("""{ "requires": { "deny": ["moderation"], "refused": "degrade" } }""")
        assertEquals(Severity.WARNING, degrading.check(snapshot).single().severity)
    }

    @Test
    fun twoPoliciesTogetherAreTheStricterOfEach() {
        val server =
            policy("""{ "requires": { "allow": ["network", "db"], "refused": "degrade" }, "limits": { "script-memory-mb": 256 } }""")
        val network =
            policy(
                """{ "requires": { "allow": ["network", "http"], "deny": ["db"] }, "limits": { "script-memory-mb": 128, "instruction-budget": 1000 } }"""
            )
        val both = server and network
        assertEquals(listOf("network"), both.requires.allow)
        assertEquals(listOf("db"), both.requires.deny)
        assertTrue(both.stops)
        assertEquals(PolicyLimits(scriptMemoryMb = 128, instructionBudget = 1000), both.limits)
    }
}
