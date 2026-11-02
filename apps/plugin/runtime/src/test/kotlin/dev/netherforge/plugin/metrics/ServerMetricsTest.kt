package dev.netherforge.plugin.metrics

import dev.netherforge.plugin.TestServer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertTrue

/** The server's Prometheus endpoint: what it counts, gathered once a second. */
class ServerMetricsTest {
    @Test
    fun `the endpoint says how the server keeps up, who's on it, and what each package costs`() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        TestServer(
            mapOf(
                TestServer.MANIFEST to TestServer.manifest(requires = """{ "network": true }"""),
                "modules/t/init.lua" to """nf.every(1, function() end)"""
            ),
            metrics = MetricsConfig(port = port)
        ).use { server ->
            server.platform.players.add("Ann")
            repeat(41) { server.tick() }
            val body = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/metrics")).build(),
                HttpResponse.BodyHandlers.ofString()
            ).body()
            for (line in listOf(
                "# TYPE netherforge_tps gauge",
                "netherforge_players 1",
                "netherforge_chunks{world=\"world\"}",
                "netherforge_state{state=\"ready\"} 1",
                "netherforge_state{state=\"draining\"} 0",
                "netherforge_scripts{package=\"test\"} 1",
                "netherforge_runtime_tick_milliseconds{stat=\"worst\"}",
                "# TYPE netherforge_network_connected gauge"
            )) {
                assertTrue(body.contains(line), "no $line in:\n$body")
            }
        }
    }
}
