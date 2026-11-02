package dev.netherforge.proxy

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The proxy's own metrics: labelled with its name, logins and moves by reason, and its link to the hub. */
class ProxyMetricsTest {
    @Test
    fun `logins, refusals, moves and the hub's link are counted, each labelled with the proxy`() {
        val metrics = ProxyMetrics("velocity-2")
        assertFalse(metrics.text(0, emptyMap()).contains("hub_connected"), "a proxy running its own hub has no link to report")

        metrics.loggedIn()
        metrics.loggedIn()
        metrics.refused(ProxyMetrics.LoginRefusal.BANNED)
        metrics.refused(ProxyMetrics.LoginRefusal.INITIAL_SERVER)
        metrics.refused(ProxyMetrics.LoginRefusal.NO_SERVER)
        metrics.refused(ProxyMetrics.LoginRefusal.NO_SERVER)
        metrics.moved()
        metrics.moveFailed("server_refused")
        metrics.hubJoined()
        metrics.hubLost()
        metrics.hubJoined()
        val text = metrics.text(3, mapOf("config" to 2, "runtime" to 5))
        for (
        line in listOf(
            "netherforge_proxy_players{proxy=\"velocity-2\"} 3",
            "netherforge_proxy_servers{proxy=\"velocity-2\",source=\"runtime\"} 5",
            "netherforge_proxy_logins_total{proxy=\"velocity-2\"} 2",
            "netherforge_proxy_logins_refused_total{proxy=\"velocity-2\",reason=\"banned\"} 1",
            "netherforge_proxy_logins_refused_total{proxy=\"velocity-2\",reason=\"initial_server\"} 1",
            "netherforge_proxy_logins_refused_total{proxy=\"velocity-2\",reason=\"no_server\"} 2",
            "netherforge_proxy_logins_refused_total{proxy=\"velocity-2\",reason=\"draining\"} 0",
            "netherforge_proxy_moves_total{proxy=\"velocity-2\"} 1",
            "netherforge_proxy_move_failures_total{proxy=\"velocity-2\",reason=\"server_refused\"} 1",
            "netherforge_proxy_hub_connected{proxy=\"velocity-2\"} 1",
            "netherforge_proxy_hub_reconnects_total{proxy=\"velocity-2\"} 1"
        )
        ) {
            assertTrue(text.lines().contains(line), "missing $line in\n$text")
        }
        assertTrue(text.contains("# TYPE netherforge_proxy_logins_total counter"))
    }
}
