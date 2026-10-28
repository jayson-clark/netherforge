package dev.netherforge.plugin.integration

import dev.netherforge.plugin.integration.support.Adapter
import dev.netherforge.plugin.integration.support.PaperServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Platform contract suites against the adapter's PaperPlatform, on a
 * real headless Paper: the same suites the runtime's tests run against the
 * fake. They run inside the server (the contract source set, a plugin of its
 * own that loads with the NetherForge plugin's classes, its players the bots
 * plugin's: suites call the platform on the main thread and wait for ticks,
 * which only works from within), so this boots a server with those three,
 * waits for the contract plugin to write its results and stop it, and reports
 * each suite's test here.
 */
class ContractScenario {
    @TestFactory
    fun `the Platform contract suites hold on Paper`(): List<DynamicTest> {
        val server = PaperServer(onlineMode = true, maxPlayers = 20, viewDistance = 4)
        server.prepare(listOf(Adapter.plugin, Adapter.contract))
        val results = server.folder.resolve("contract-results.json")
        Files.deleteIfExists(results)
        Files.deleteIfExists(server.folder.resolve("contract-test.log"))
        val process = server.launch(listOf("-Dnetherforge.contract.results=$results"), "contract-test.log")
        try {
            assertTrue(process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES), "the contract server stopped within $TIMEOUT_MINUTES minutes")
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
        if (!results.exists()) fail("the contract plugin wrote no results; see ${server.folder.resolve("contract-test.log")}")
        // Every suite's test that runs on the fake is in here: the contract plugin adds those that didn't run as MISSING.
        val tests = Json.parseToJsonElement(results.readText()).jsonArray.map { it.jsonObject }
        return tests.map { test ->
            fun field(name: String) = test[name]?.jsonPrimitive?.content
            DynamicTest.dynamicTest("${field("suite")} > ${field("name")}") {
                when (field("status")) {
                    "SUCCESSFUL" -> Unit
                    // It says why it can't run on Paper (@OnlyOn): skipped, as it should be.
                    "ONLY_ON_OTHER" -> Assumptions.abort<Unit>(field("message").orEmpty())
                    // Failed, aborted, skipped without saying it's fake-only, or never run.
                    else -> fail("${field("status")}: ${field("message")}\n${field("trace").orEmpty()}")
                }
            }
        }
    }

    private companion object {
        const val TIMEOUT_MINUTES = 12L
    }
}
