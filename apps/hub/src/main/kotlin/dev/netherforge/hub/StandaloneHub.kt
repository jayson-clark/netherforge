package dev.netherforge.hub

import dev.netherforge.network.AdminClient
import dev.netherforge.network.AdminClientsFile
import dev.netherforge.network.CredentialsFile
import dev.netherforge.network.FileMapStore
import dev.netherforge.network.Hub
import dev.netherforge.network.HubAdmin
import dev.netherforge.network.HubEvents
import dev.netherforge.network.HubLog
import dev.netherforge.network.HubServer
import dev.netherforge.network.HubSettings
import dev.netherforge.network.MapStore
import dev.netherforge.network.RemoteProxy
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The hub running on its own, in [folder]: `hub.properties`, `credentials.properties` and `admin.properties` there,
 * the maps in `maps.json` there or the database `store.url` names, and the proxy joining as a client. The credential
 * and admin files are read again when they change (the command line, or someone editing them), as
 * `/nfhub credentials reload` does on a proxy.
 */
class StandaloneHub(private val folder: Path, private val log: HubLog) {
    private var hub: Hub? = null
    private var server: HubServer? = null
    private var admin: HubAdmin? = null
    private var watcher: ScheduledExecutorService? = null
    private var metrics: com.sun.net.httpserver.HttpServer? = null

    @Volatile
    private var adminClients: List<AdminClient> = emptyList()

    /** The port the hub listens on, once started. */
    val port: Int get() = server?.port ?: -1

    val adminPort: Int get() = admin?.port ?: -1

    fun start() {
        val settings = HubSettings.read(folder.resolve(HubSettings.FILE)) { log.info(it) }
        val credentialsFile = CredentialsFile(folder.resolve(CredentialsFile.FILE))
        val adminFile = AdminClientsFile(folder.resolve(AdminClientsFile.FILE))
        val credentials = credentialsFile.read().map { it.credential(settings.roleShares) }
        val store: MapStore = settings.storeUrl?.let { PostgresMapStore(it, settings.storeUser, settings.storePassword) }
            ?: FileMapStore(folder.resolve("maps.json"))
        val hub = Hub(
            settings.secret,
            RemoteProxy(),
            log,
            settings.limits,
            store,
            credentials = credentials,
            groups = settings.groups,
            policies = settings.rolePolicies,
            keepsPacks = !settings.clearResourcePacks,
            events = settings.webhookUrl?.let { url -> HubEvents(url, settings.webhookSecret, log)::send } ?: {}
        )
        val server = HubServer(hub, InetSocketAddress(settings.bind, settings.port), log, settings.limits)
        server.start()
        this.hub = hub
        this.server = server
        log.info(
            "NetherForge hub listening on ${settings.bind}:${server.port}, with ${credentials.size} credentials; " +
                "maps in ${if (settings.storeUrl != null) "PostgreSQL" else folder.resolve("maps.json")}"
        )
        val adminPort = settings.adminPort
        if (adminPort != null) {
            adminClients = adminFile.read()
            admin = HubAdmin(hub, InetSocketAddress(settings.adminBind, adminPort), { adminClients }, log).also { it.start() }
            log.info("NetherForge hub's admin API listening on ${settings.adminBind}:${admin!!.port}, with ${adminClients.size} clients")
        }
        settings.metricsPort?.let { port ->
            metrics = dev.netherforge.network.Prometheus.serve(InetSocketAddress(settings.metricsBind, port)) { hub.metrics() }
            log.info("NetherForge hub's metrics at http://${settings.metricsBind}:$port/metrics")
        }
        // The files, read again when they change.
        var seen = stamps(credentialsFile.file, adminFile.file)
        watcher = Executors.newSingleThreadScheduledExecutor { Thread(it, "NetherForge hub files").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({
                try {
                    val now = stamps(credentialsFile.file, adminFile.file)
                    if (now != seen) {
                        seen = now
                        val fresh = HubSettings.read(folder.resolve(HubSettings.FILE)) {}
                        hub.setGroups(fresh.groups)
                        hub.setCredentials(credentialsFile.read().map { entry -> entry.credential(fresh.roleShares) })
                        val clients = adminFile.read()
                        for (gone in adminClients.map { c -> c.id } - clients.map { c -> c.id }.toSet()) hub.detach(gone)
                        adminClients = clients
                        log.info("NetherForge hub: read its credentials and admin clients again")
                    }
                } catch (e: Exception) {
                    log.warn("NetherForge hub: couldn't read its files again: ${e.message}")
                }
            }, WATCH_SECONDS, WATCH_SECONDS, TimeUnit.SECONDS)
        }
    }

    fun stop() {
        watcher?.shutdownNow()
        metrics?.stop(0)
        admin?.stop()
        server?.stop()
        admin = null
        server = null
        hub = null
    }

    private fun stamps(vararg files: Path): List<FileTime?> = files.map { if (Files.exists(it)) Files.getLastModifiedTime(it) else null }

    private companion object {
        const val WATCH_SECONDS = 2L
    }
}
