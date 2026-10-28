package dev.netherforge.plugin.paper.contract

import dev.netherforge.plugin.contract.ContractServer
import dev.netherforge.plugin.contract.RecordingEvents
import dev.netherforge.plugin.paper.PaperPlatform
import dev.netherforge.plugin.platform.ItemLook
import dev.netherforge.plugin.platform.Location
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * The real server the contract suites run against: [platform] is a
 * [PaperPlatform] of the contract plugin's own, bound to [events]. Suites run
 * on a thread of their own, so [main] hands each call to the server's main
 * thread and waits, and [ticks] waits for the server to tick.
 */
class PaperContractServer(
    private val plugin: Plugin,
    override val platform: PaperPlatform,
    override val events: RecordingEvents,
    override val itemLooks: MutableMap<String, ItemLook>,
    override val blockStates: MutableMap<String, String>,
    override val origin: Location,
    private val files: Path
) : ContractServer {
    override fun <T> main(block: () -> T): T {
        if (Bukkit.isPrimaryThread()) return block()
        // Not callSyncMethod: its future keeps only Exceptions, and a failed assertion is an Error.
        val result = CompletableFuture<T>()
        Bukkit.getScheduler().runTask(
            plugin,
            Runnable {
                try {
                    result.complete(block())
                } catch (e: Throwable) {
                    result.completeExceptionally(e)
                }
            }
        )
        try {
            return result.get(WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            // What the block threw (a failed assertion included), as if it had run here.
            throw e.cause ?: e
        }
    }

    override fun ticks(count: Int) {
        val ticked = CountDownLatch(1)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { ticked.countDown() }, count.toLong())
        check(ticked.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "the server didn't tick $count times within $WAIT_SECONDS seconds" }
    }

    /** A chunk a million blocks out: nothing near loads it, and the suites never do. */
    override fun unloadedChunk(): Pair<Int, Int> = FAR to FAR

    override fun directory(): Path = Files.createTempDirectory(Files.createDirectories(files), "contract")

    companion object {
        /** The server the suites' Paper subclasses run against, made when the plugin enables. */
        lateinit var current: PaperContractServer

        private const val WAIT_SECONDS = 60L
        private const val FAR = 62_500
    }
}
