package dev.netherforge.plugin.paper.contract

import dev.netherforge.plugin.contract.ContractResult
import dev.netherforge.plugin.contract.ContractRun
import dev.netherforge.plugin.contract.ContractServer
import dev.netherforge.plugin.contract.ContractTarget
import dev.netherforge.plugin.contract.RecordingEvents
import dev.netherforge.plugin.paper.NetherForgePlugin
import dev.netherforge.plugin.paper.PaperPlatform
import dev.netherforge.plugin.platform.ItemLook
import dev.netherforge.plugin.platform.Location
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bukkit.Bukkit
import org.bukkit.GameRules
import org.bukkit.plugin.java.JavaPlugin
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/**
 * Runs the Platform contract suites inside this server, against a
 * [PaperPlatform] of its own made with the NetherForge plugin's own
 * [dev.netherforge.plugin.paper.PaperVersion]: the other half of each suite's
 * run against the fake (the runtime's tests). Only the integration test
 * loads it (`ContractScenario`), beside the NetherForge plugin (with no
 * project, so it runs nothing itself), whose classes it loads with, and
 * with `-Dnetherforge.contract.results=<file>`. It makes the
 * platform, keeps the world still (no time, weather, mob spawning or drops)
 * and the chunks round the origin loaded, runs every suite on a thread of its
 * own once the server is ticking, writes each test's result to that file as
 * JSON, and stops the server.
 */
class ContractPlugin : JavaPlugin() {
    override fun onEnable() {
        val results = Path.of(System.getProperty(RESULTS) ?: error("-D$RESULTS isn't set: only the integration test runs this plugin"))
        val netherForge = server.pluginManager.getPlugin("NetherForge") as NetherForgePlugin
        val platform = PaperPlatform(this, netherForge.version)
        val events = RecordingEvents()
        val looks = ConcurrentHashMap<String, ItemLook>()
        val states = ConcurrentHashMap<String, String>()
        platform.bind(events, { null }, ContractServer.lookup(looks), states::get) { ContractServer.NAMESPACE }

        val world = server.worlds.first()
        world.setGameRule(GameRules.ADVANCE_TIME, false)
        world.setGameRule(GameRules.ADVANCE_WEATHER, false)
        world.setGameRule(GameRules.SPAWN_MOBS, false)
        world.setGameRule(GameRules.MOB_DROPS, false)
        world.setGameRule(GameRules.BLOCK_DROPS, false)
        // Night, so zombies don't burn while a suite looks at them.
        world.time = 18_000
        world.setStorm(false)
        world.isThundering = false
        for (x in -CHUNKS..CHUNKS) for (z in -CHUNKS..CHUNKS) world.addPluginChunkTicket(x, z, this)
        val origin = Location(world.name, 0.5, world.getHighestBlockYAt(0, 0) + 1.0, 0.5)
        PaperContractServer.current =
            PaperContractServer(this, platform, events, looks, states, origin, dataFolder.toPath().resolve("files"))

        // Once the server ticks: the suites wait on ticks and hand their calls to this thread.
        server.scheduler.runTask(
            this,
            Runnable {
                Thread({ run(results, platform) }, "NetherForge contract suites").apply { isDaemon = true }.start()
            }
        )
    }

    private fun run(results: Path, platform: PaperPlatform) {
        Thread.currentThread().contextClassLoader = javaClass.classLoader
        val run = ContractRun(ContractTarget.PAPER) { message, failure -> logger.log(Level.WARNING, message, failure) }
        try {
            val request = LauncherDiscoveryRequestBuilder.request()
                .selectors(PaperContracts.ALL.map { DiscoverySelectors.selectClass(it) })
                // In @Order, so the suite that checks what all of them saw runs last.
                .configurationParameter("junit.jupiter.testclass.order.default", "org.junit.jupiter.api.ClassOrderer\$OrderAnnotation")
                // A test only on the fake (@OnlyOn) is disabled here.
                .configurationParameter(ContractTarget.PARAMETER, ContractTarget.PAPER.name)
                .build()
            LauncherFactory.create().execute(request, run)
            // Every suite's test that runs on the fake must have run here: what didn't is MISSING.
            run.finish()
        } catch (e: Throwable) {
            logger.log(Level.SEVERE, "The contract suites couldn't run", e)
            run.failed("launcher", "running the suites", e)
        }
        Files.createDirectories(results.toAbsolutePath().parent)
        Files.writeString(results, JsonArray(run.all.map(::json)).toString())
        logger.info("Contract suites: ${run.all.size} results written to $results")
        server.scheduler.runTask(
            this,
            Runnable {
                platform.bots.leaveAll()
                Bukkit.shutdown()
            }
        )
    }

    /** [result] as `ContractScenario` reads it back. */
    private fun json(result: ContractResult) = buildJsonObject {
        put("suite", result.suite)
        put("name", result.name)
        put("status", result.status)
        result.message?.let { put("message", it) }
        result.trace?.let { put("trace", it) }
    }

    private companion object {
        const val RESULTS = "netherforge.contract.results"

        /** Chunks kept loaded each way round the origin's. */
        const val CHUNKS = 2
    }
}
