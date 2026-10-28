package dev.netherforge.plugin.http

/**
 * `http:` in `config.yml`, read on dev servers too: the server owner's limits
 * on `nf.http.request`. Public addresses only unless [allowPrivateAddresses]
 * (a service on this machine or network: loopback, private, link-local,
 * shared and unique-local addresses); a request or response body larger than
 * its limit fails; a request that takes longer than [timeoutSeconds]
 * (redirects included) fails; and each package may start [requestsPerMinute]
 * requests a minute.
 */
data class HttpConfig(
    val allowPrivateAddresses: Boolean = false,
    val maxRequestBytes: Int = 1024 * 1024,
    val maxResponseBytes: Int = 4 * 1024 * 1024,
    val timeoutSeconds: Int = 15,
    val requestsPerMinute: Int = 60,
    /** What `nf.http.stream` and `nf.http.websocket` may hold open and move. */
    val connections: ConnectionLimits = ConnectionLimits()
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
