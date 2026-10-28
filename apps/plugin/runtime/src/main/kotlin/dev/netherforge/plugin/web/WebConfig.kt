package dev.netherforge.plugin.web

/**
 * `web:` in `config.yml`, read on dev servers too: whether `nf.web` routes
 * are served ([enabled]; off by default), on the resource pack's listener
 * (`resource-pack.bind` and `.port`), and the owner's limits on them.
 * [publicUrl] is what `nf.web.url` builds on (the listener's own address
 * when unset); [trustProxyHeaders] makes `X-Forwarded-For` the client's
 * address, for a server behind the owner's own reverse proxy. A request
 * body over [maxBodyBytes] is `413`; an address past
 * [requestsPerMinutePerIp] is `429`; past [maxPending] waiting for the main
 * thread is `503`; a handler that hasn't answered after
 * [handlerTimeoutSeconds] is `504`.
 */
data class WebConfig(
    val enabled: Boolean = false,
    val publicUrl: String? = null,
    val trustProxyHeaders: Boolean = false,
    val maxBodyBytes: Int = 64 * 1024,
    val maxResponseBytes: Int = 1024 * 1024,
    val requestsPerMinutePerIp: Int = 60,
    val maxPending: Int = 64,
    val handlerTimeoutSeconds: Int = 10
)
