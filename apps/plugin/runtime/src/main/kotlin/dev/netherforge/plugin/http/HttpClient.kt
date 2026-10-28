package dev.netherforge.plugin.http

import dev.netherforge.plugin.session.RuntimeService

/**
 * The session's one [HttpFetcher] and the transport its open connections go
 * over, each made when the first request or connection needs it and closed
 * when the session stops, so a reload (a new session) leaves none of
 * OkHttp's threads, pooled connections or open sockets behind.
 *
 * In a script test run ([testing]) connections go to [TestConnections]
 * instead: the test plays their far ends, and nothing reaches the network.
 */
class HttpClient(private val testing: Boolean = false, private val limits: () -> HttpConfig) : RuntimeService {
    override val name = "http"

    private var made: HttpFetcher? = null
    private var transport: ConnectionTransport? = null

    /** The fetcher; made on first use, with the limits as they are then. */
    @get:Synchronized
    val fetcher: HttpFetcher get() = made ?: HttpFetcher(limits()).also { made = it }

    /** Where `nf.http.stream` and `nf.http.websocket` connect: made on first use. */
    @get:Synchronized
    val connections: ConnectionTransport
        get() = transport ?: (if (testing) TestConnections(limits) else OkHttpConnections({ fetcher }, limits)).also { transport = it }

    @Synchronized
    override fun stop() {
        transport?.close()
        transport = null
        made?.close()
        made = null
    }
}
