package dev.netherforge.plugin.http

/**
 * `http:` in `config.yml`, read on dev servers too: the server owner's limits
 * on `nf.http.request`. Public addresses only unless [allowPrivateAddresses]
 * (a service on this machine or network: loopback, private, link-local,
 * shared and unique-local addresses); a request or response body larger than
 * its limit fails; a request that takes longer than [timeoutSeconds]
 * (redirects included) fails; and each package may start [requestsPerMinute]
 * requests a minute. [allowPrivate] lifts the private-address refusal for one
 * package, one host and one port each, without opening the network to the rest.
 */
data class HttpConfig(
    val allowPrivateAddresses: Boolean = false,
    val maxRequestBytes: Int = 1024 * 1024,
    val maxResponseBytes: Int = 4 * 1024 * 1024,
    val timeoutSeconds: Int = 15,
    val requestsPerMinute: Int = 60,
    /** What `nf.http.stream` and `nf.http.websocket` may hold open and move. */
    val connections: ConnectionLimits = ConnectionLimits(),
    /** `http.allow-private`: the owner's allowances, each a package that may reach one private host and port. */
    val allowPrivate: List<PrivateAllowance> = emptyList()
) {
    companion object {
        /** Redirects one request follows before it fails. */
        const val MAX_REDIRECTS = 5
    }
}

/**
 * `http.connections:` in `config.yml`: the owner's limits on the connections
 * `nf.http.stream` and `nf.http.websocket` hold open (each also counts as a
 * request against `requests-per-minute` when it opens). [maxPerPackage] open
 * at once per package and [maxTotal] in all; one line, event or message (either
 * way) of at most [maxMessageBytes]; [bytesPerMinute] read from and written to
 * the network per package, counted on the wire; ended after [idleSeconds] with
 * nothing sent or received. While scripts haven't taken them, at most
 * [maxQueuedMessages] wait per connection, and the server stops reading until
 * they're taken; [messagesPerTick] are handed to scripts per connection a tick.
 */
data class ConnectionLimits(
    val maxPerPackage: Int = 4,
    val maxTotal: Int = 32,
    val maxMessageBytes: Int = 1024 * 1024,
    val bytesPerMinute: Int = 16 * 1024 * 1024,
    val idleSeconds: Int = 300,
    val maxQueuedMessages: Int = 1000,
    val messagesPerTick: Int = 100
)

/**
 * One entry of `http.allow-private` in `config.yml`: the package [namespace]
 * may connect to [host] (an address, or a name looked up when it's needed) at
 * [port] even though the address is private. The check stays on the address
 * connected to, so this lets that package reach exactly the addresses [host]
 * stands for, on that port, and nothing else that isn't public. It lifts only
 * the address refusal: the package still declares the host in `requires.http`.
 */
data class PrivateAllowance(
    val namespace: String,
    val host: String,
    val port: Int,
    /**
     * The package's requests to [host] and [port] a minute, counted apart from its `http.requests-per-minute` (null:
     * they count against that, as any other; 0: no limit).
     */
    val requestsPerMinute: Int? = null
) {
    override fun toString() = "$namespace → $host:$port" + requestsPerMinute?.let { " ($it a minute)" }.orEmpty()
}
