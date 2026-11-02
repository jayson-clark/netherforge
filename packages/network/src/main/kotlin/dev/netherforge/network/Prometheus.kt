package dev.netherforge.network

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/** Metrics in Prometheus's text format: each metric's help and type once, then its samples with their labels. */
class Prometheus {
    private val out = StringBuilder()
    private val declared = HashSet<String>()

    /** A gauge (a value now) or a counter (only goes up), [name] in `netherforge_*`, with [labels]. */
    fun sample(name: String, help: String, value: Number, labels: Map<String, String> = emptyMap(), counter: Boolean = false): Prometheus {
        if (declared.add(name)) {
            out.append("# HELP ").append(name).append(' ').append(help).append('\n')
            out.append("# TYPE ").append(name).append(if (counter) " counter" else " gauge").append('\n')
        }
        out.append(name)
        if (labels.isNotEmpty()) {
            out.append('{')
            labels.entries.joinTo(out, ",") { (key, label) ->
                "$key=\"${label.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
            }
            out.append('}')
        }
        out.append(' ').append(if (value is Double) "%.6f".format(java.util.Locale.ROOT, value) else value.toString()).append('\n')
        return this
    }

    override fun toString() = out.toString()

    companion object {
        /** Serves what [text] says at `/metrics` on [bind]:[port], on a thread of its own; stop it with [HttpServer.stop]. */
        fun serve(bind: InetSocketAddress, text: () -> String): HttpServer {
            val server = HttpServer.create(bind, 8)
            server.executor = Executors.newSingleThreadExecutor { Thread(it, "NetherForge metrics").apply { isDaemon = true } }
            server.createContext("/metrics") { exchange ->
                exchange.use {
                    val body = text().toByteArray()
                    it.responseHeaders.add("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                    it.sendResponseHeaders(200, body.size.toLong())
                    it.responseBody.write(body)
                }
            }
            server.start()
            return server
        }
    }
}
