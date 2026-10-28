package dev.netherforge.plugin

import dev.netherforge.format.bridge.Bridge
import dev.netherforge.plugin.http.HttpConfig
import dev.netherforge.plugin.network.NetworkConfig
import dev.netherforge.plugin.pack.PackConfig
import dev.netherforge.plugin.schedule.ScheduleConfig
import dev.netherforge.plugin.script.PerformanceConfig
import dev.netherforge.plugin.wasm.WasmConfig
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimeConfigTest {
    private val plugin = Path.of("/data/servers/0123456789abcdef/plugins/NetherForge").toAbsolutePath()
    private val project = Path.of("/work/project").toAbsolutePath()
    private val server = ServerAddress(Path.of("/srv/minecraft").toAbsolutePath(), "")

    private fun read(settings: Map<String, Any?>, properties: Map<String, String> = emptyMap(), address: ServerAddress = server) =
        RuntimeConfig.read(plugin, settings, address, properties::get) { if (it == Bridge.TOKEN_ENV) "t" else null }

    @Test
    fun `a dev server keeps its state in its own folder, never in the project`() {
        val cache = Path.of("/data/packages").toAbsolutePath()
        val properties = mapOf(
            Bridge.PROJECT_PROPERTY to project.toString(),
            Bridge.PORT_PROPERTY to "4100",
            Bridge.PACKAGE_CACHE_PROPERTY to cache.toString()
        )
        val config = read(mapOf("project" to "elsewhere"), properties)!!
        assertEquals(cache, config.packageCache)
        assertEquals(project, config.project)
        assertEquals(plugin.resolve("data"), config.dataDirectory)
        assertEquals(plugin, config.stateDirectory)
        assertFalse(config.dataDirectory.startsWith(project))
        assertEquals(4100, config.bridge?.port)
        assertEquals("t", config.bridge?.token)
        assertTrue(config.resolvesPackages)
        // The dev server serves its pack on loopback, whatever config.yml says.
        assertEquals(PackConfig(), config.pack)
    }

    @Test
    fun `without the editor's property or a project in config yml there's nothing to run`() {
        assertNull(read(emptyMap()))
        assertNull(read(mapOf("project" to "  ")))
    }

    @Test
    fun `a production server's project is relative to the server's folder, and its pack is served on every interface`() {
        val config = read(mapOf("project" to "projects/shop"))!!
        assertEquals(server.directory.resolve("projects/shop"), config.project)
        assertNull(config.bridge)
        // It runs a bundle `netherforge build` wrote: it never resolves packages itself.
        assertFalse(config.resolvesPackages)
        assertEquals(
            PackConfig(enabled = true, bind = "0.0.0.0", port = RuntimeConfig.DEFAULT_PACK_PORT, publicUrl = "http://localhost:8163"),
            config.pack
        )
        assertEquals(PerformanceConfig(), config.performance)
    }

    @Test
    fun `resource-pack and performance are read from their sections`() {
        val settings = mapOf(
            "project" to "/abs/project",
            "resource-pack" to mapOf(
                "enabled" to false,
                "bind" to "10.0.0.2",
                "port" to 9000,
                "public-url" to "",
                "external-url" to "https://cdn.example.com/pack.zip",
                "required" to "true",
                "prompt" to "<gold>Please"
            ),
            "performance" to mapOf("warn-ms" to 2.5, "warn-ticks" to 500)
        )
        val config = read(settings, address = server.copy(ip = "203.0.113.7"))!!
        assertEquals(Path.of("/abs/project").toAbsolutePath(), config.project)
        assertEquals(
            PackConfig(
                enabled = false,
                bind = "10.0.0.2",
                port = 9000,
                publicUrl = "http://203.0.113.7:9000",
                externalUrl = "https://cdn.example.com/pack.zip",
                required = true,
                prompt = "<gold>Please"
            ),
            config.pack
        )
        assertEquals(PerformanceConfig(warnMillis = 2.5, warnTicks = 100), config.performance)
    }

    @Test
    fun `values of the wrong kind fall back to their defaults`() {
        val settings = mapOf(
            "project" to "p",
            "resource-pack" to "not a section",
            "performance" to mapOf("warn-ms" to -3, "warn-ticks" to "lots")
        )
        val config = read(settings)!!
        assertEquals(RuntimeConfig.DEFAULT_PACK_PORT, config.pack.port)
        assertEquals(PerformanceConfig(warnMillis = 0.0, warnTicks = PerformanceConfig().warnTicks), config.performance)
    }

    @Test
    fun `a dev server reads performance from config yml too`() {
        val properties = mapOf(Bridge.PROJECT_PROPERTY to project.toString())
        val config = read(mapOf("performance" to mapOf("warn-ms" to 0)), properties)!!
        assertEquals(0.0, config.performance.warnMillis)
        assertNull(config.bridge)
    }

    @Test
    fun `the schedules time zone is read on a production and a dev server, and empty means the server's own`() {
        assertEquals(
            ScheduleConfig("Europe/Paris"),
            read(mapOf("project" to "p", "schedules" to mapOf("time-zone" to "Europe/Paris")))!!.schedules
        )
        assertEquals(ScheduleConfig(null), read(mapOf("project" to "p", "schedules" to mapOf("time-zone" to "")))!!.schedules)
        assertEquals(ScheduleConfig(null), read(mapOf("project" to "p"))!!.schedules)
        val properties = mapOf(Bridge.PROJECT_PROPERTY to project.toString())
        assertEquals(ScheduleConfig("+02:00"), read(mapOf("schedules" to mapOf("time-zone" to "+02:00")), properties)!!.schedules)
    }

    @Test
    fun `the http section is read on a production and a dev server, and a bad number is the default`() {
        val section = mapOf(
            "allow-private-addresses" to true,
            "max-request-bytes" to 10,
            "max-response-bytes" to "20",
            "timeout-seconds" to 3,
            "requests-per-minute" to 0
        )
        val expected = HttpConfig(true, 10, 20, 3, HttpConfig().requestsPerMinute)
        assertEquals(expected, read(mapOf("project" to "p", "http" to section))!!.http)
        val properties = mapOf(Bridge.PROJECT_PROPERTY to project.toString())
        assertEquals(expected, read(mapOf("http" to section), properties)!!.http)
        assertEquals(HttpConfig(), read(mapOf("project" to "p"))!!.http)
        assertFalse(HttpConfig().allowPrivateAddresses)
    }

    @Test
    fun `the wasm section is read on a production and a dev server, and what isn't a positive number is the default`() {
        val section = mapOf("threads" to 4, "compile" to false, "max-memory-mb" to "64", "max-call-seconds" to 0, "lines-per-tick" to -3)
        val expected = WasmConfig(threads = 4, compile = false, maxMemoryMb = 64)
        assertEquals(expected, read(mapOf("project" to "p", "wasm" to section))!!.wasm)
        val properties = mapOf(Bridge.PROJECT_PROPERTY to project.toString())
        assertEquals(expected, read(mapOf("wasm" to section), properties)!!.wasm)
        assertEquals(WasmConfig(), read(mapOf("project" to "p"))!!.wasm)
    }

    @Test
    fun `the network section is read on a production and a dev server, and says what keeps it from joining`() {
        val section = mapOf(
            "enabled" to true,
            "hub" to "proxy.internal:25600",
            "server-name" to " lobby ",
            "secret" to "s3cret",
            "messages-per-minute" to "600",
            "request-timeout-seconds" to 0
        )
        val expected = NetworkConfig(
            enabled = true,
            host = "proxy.internal",
            port = 25600,
            serverName = "lobby",
            secret = "s3cret",
            messagesPerMinute = 600
        )
        assertEquals(expected, read(mapOf("project" to "p", "network" to section))!!.network)
        val properties = mapOf(Bridge.PROJECT_PROPERTY to project.toString())
        assertEquals(expected, read(mapOf("network" to section), properties)!!.network)
        assertEquals(NetworkConfig(), read(mapOf("project" to "p"))!!.network)
        assertNull(expected.problem())

        assertEquals("10.0.0.5" to 25590, NetworkConfig.hub("10.0.0.5"))
        assertEquals("::1" to 25601, NetworkConfig.hub("[::1]:25601"))
        assertEquals("fd00::5" to 25590, NetworkConfig.hub("fd00::5"))
        assertNull(NetworkConfig.hub("proxy:port"))
        assertTrue(NetworkConfig(enabled = true, secret = "x").problem()!!.contains("network.server-name is empty"))
        assertTrue(NetworkConfig(enabled = true, serverName = "lobby").problem()!!.contains("network.secret is empty"))
        assertTrue(NetworkConfig(enabled = true, serverName = "a b", secret = "x").problem()!!.contains("isn't a server name"))
    }

    @Test
    fun `the editor puts a dev server in its dev network, keeping the owner's limits`() {
        val section = mapOf("hub" to "proxy.internal:25600", "server-name" to "x", "messages-per-minute" to 600)
        val properties = mapOf(
            Bridge.PROJECT_PROPERTY to project.toString(),
            Bridge.NETWORK_HUB_PROPERTY to "127.0.0.1:40123",
            Bridge.NETWORK_SERVER_PROPERTY to "lobby"
        )
        val env = mapOf(Bridge.TOKEN_ENV to "t", Bridge.NETWORK_SECRET_ENV to "hub-secret")
        val network = RuntimeConfig.read(plugin, mapOf("network" to section), server, properties::get, env::get)!!.network
        assertEquals(
            NetworkConfig(
                enabled = true,
                host = "127.0.0.1",
                port = 40123,
                serverName = "lobby",
                secret = "hub-secret",
                messagesPerMinute = 600
            ),
            network
        )
        assertNull(network.problem())
        // A production server never reads them.
        assertEquals(false, read(mapOf("project" to "p"), properties - Bridge.PROJECT_PROPERTY)!!.network.enabled)
    }
}
