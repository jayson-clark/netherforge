package dev.netherforge.plugin.session

import dev.netherforge.format.project.Policy
import dev.netherforge.plugin.TestServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The server's policy over what projects require: refusing a project, degrading its calls, and lowering limits. */
class PolicyTest {
    private fun policy(text: String) = Policy.parse(text).getOrThrow()

    private val project = mapOf(
        TestServer.MANIFEST to TestServer.manifest(requires = """{ "moderation": true, "network": true }"""),
        "modules/t/init.lua" to """
            log("loaded")
            nf.commands.register("kick", function()
              log(select(2, pcall(function() nf.players.online()[1]:kick("bye") end)))
            end)
        """
    )

    @Test
    fun `a project the policy refuses doesn't load, and says which rule refused it`() {
        TestServer(project, policy = policy("""{ "requires": { "deny": ["moderation"] } }""")).use { server ->
            assertFalse("loaded" in server.logs)
            assertFalse(server.runtime.session.running)
            val problem = server.runtime.session.problems().single { it.code == "runtime.policy" }
            assertTrue(problem.message.contains("requires.deny lists \"moderation\""), problem.message)
        }
    }

    @Test
    fun `degraded, the project loads and what the policy refuses fails at the line`() {
        TestServer(project, policy = policy("""{ "requires": { "allow": ["network"], "refused": "degrade" } }""")).use { server ->
            assertTrue("loaded" in server.logs)
            assertEquals("warning", server.runtime.session.problems().single { it.code == "runtime.policy" }.severity.name.lowercase())
            server.platform.players.add("Ann")
            server.platform.commands.runConsole("kick")
            assertTrue(server.logs.last().contains("needs moderation, which the server's policy refuses"), server.logs.last())
        }
    }

    @Test
    fun `the policy's limits replace the server's where they're lower`() {
        TestServer(
            project,
            policy = policy("""{ "limits": { "script-memory-mb": 64, "instruction-budget": 1000, "network-messages-per-minute": 5 } }""")
        ).use { server ->
            val config = server.runtime.session.config
            assertEquals(64, config.sandbox.memoryMegabytes)
            assertEquals(1000, config.sandbox.maxBudget)
            assertEquals(5, config.network.messagesPerMinute)
            assertEquals(1000, server.runtime.session.scripts.maxBudget)
        }
    }
}
