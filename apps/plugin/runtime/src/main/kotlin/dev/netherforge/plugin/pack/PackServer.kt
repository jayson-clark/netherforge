package dev.netherforge.plugin.pack

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The plugin's own HTTP server: the JDK's, on its own daemon threads,
 * serving the resource pack at `GET /<sha1>.zip`, and, when the server owner
 * turned web routes on, handing every other request to [web] (`nf.web`).
 *
 * It keeps the current build and the one before it of each pack it serves (the
 * project's, and each `onDemand` pack's), so a player who started downloading
 * just before a rebuild still gets their bytes. The hash in the
 * path means a cache in between can never hand a client stale content under
 * a current name.
 */
class PackServer private constructor(private val server: HttpServer, private val executor: ExecutorService) {
    /** Each pack's current build and the one before, by the pack's name (`""` for the project's). */
    @Volatile
    private var packs: Map<String, List<Pair<String, ByteArray>>> = emptyMap()

    /** Where requests that aren't for a pack go; null (`404`) while web routes are off. */
    @Volatile
    var web: ((HttpExchange) -> Unit)? = null

    val port: Int get() = server.address.port

    /** Runs [work] on the server's threads: a web answer is written there, never on the main thread. */
    fun execute(work: Runnable) = executor.execute(work)

    /** The host to put in a URL: the bound address, or loopback when bound to every interface. */
    val host: String get() = server.address.address.let { if (it.isAnyLocalAddress) "127.0.0.1" else it.hostAddress }

    /** Serves [bytes] as pack [name]'s current build (`""`: the project's), keeping the one before it. */
    fun serve(sha1: String, bytes: ByteArray, name: String = "") {
        val kept = packs[name].orEmpty()
        packs = packs + (name to (listOf(sha1 to bytes) + kept.filter { it.first != sha1 }.take(1)))
    }

    fun stop() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        val web = web
        if (web != null && !PACK.matches(exchange.requestURI.rawPath)) {
            // The gate closes the exchange once it's answered, maybe later and from another thread.
            web(exchange)
            return
        }
        exchange.use {
            val name = exchange.requestURI.path.removePrefix("/").removeSuffix(".zip")
            val bytes = packs.values.firstNotNullOfOrNull { builds -> builds.firstOrNull { it.first == name }?.second }
            val method = exchange.requestMethod
            when {
                method != "GET" && method != "HEAD" -> exchange.sendResponseHeaders(405, -1)
                bytes == null -> exchange.sendResponseHeaders(404, -1)
                else -> {
                    exchange.responseHeaders.add("Content-Type", "application/zip")
                    if (method == "HEAD") {
                        exchange.responseHeaders.add("Content-Length", bytes.size.toString())
                        exchange.sendResponseHeaders(200, -1)
                    } else {
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.write(bytes)
                    }
                }
            }
        }
    }

    companion object {
        /** A pack's path: `/` and a SHA-1 in hex, `.zip`. */
        private val PACK = Regex("^/[0-9a-f]{40}\\.zip$")

        /** Its threads: the pack's downloads and web requests (read, refused or queued for the main thread, and answered). */
        private const val THREADS = 4

        fun start(bind: String, port: Int): PackServer {
            val server = HttpServer.create(InetSocketAddress(bind, port), 0)
            val executor = Executors.newFixedThreadPool(THREADS) { task ->
                Thread(task, "NetherForge HTTP server").apply { isDaemon = true }
            }
            server.executor = executor
            val pack = PackServer(server, executor)
            server.createContext("/") { pack.handle(it) }
            server.start()
            return pack
        }
    }
}
