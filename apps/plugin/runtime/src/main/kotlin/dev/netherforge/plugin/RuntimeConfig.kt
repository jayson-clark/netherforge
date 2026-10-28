package dev.netherforge.plugin

import dev.netherforge.format.bridge.Bridge
import dev.netherforge.plugin.bots.BotsConfig
import dev.netherforge.plugin.bridge.BridgeConfig
import dev.netherforge.plugin.http.ConnectionLimits
import dev.netherforge.plugin.http.HttpConfig
import dev.netherforge.plugin.lua.SandboxLimits
import dev.netherforge.plugin.network.NetworkConfig
import dev.netherforge.plugin.pack.PackConfig
import dev.netherforge.plugin.schedule.ScheduleConfig
import dev.netherforge.plugin.screen.ScreensConfig
import dev.netherforge.plugin.script.PerformanceConfig
import dev.netherforge.plugin.store.DatabasesConfig
import dev.netherforge.plugin.structure.StructuresConfig
import dev.netherforge.plugin.testing.TestHarness
import dev.netherforge.plugin.wasm.WasmConfig
import dev.netherforge.plugin.web.WebConfig
import java.nio.file.Path

/**
 * Where a runtime finds its project and keeps its state.
 *
 * Two shapes in practice ([read]). The editor's dev server is started with
 * `-Dnetherforge.project=<dir>` (and `-Dnetherforge.bridge.port`) and talks to
 * the editor over the bridge. A production server names its project in
 * `config.yml` and has no bridge. Both keep their state, script files
 * included, in the plugin's folder: the project is only ever read, and the
 * dev server's folder is the editor's, outside the project.
 */
data class RuntimeConfig(
    /** The project directory (the folder holding `netherforge.json`), or a bundle's (holding `netherforge-bundle.json`). */
    val project: Path,
    /** The one directory `nf.file` reaches. */
    val dataDirectory: Path,
    /** Where the instance index lives: the server's plugin folder, beside the world. */
    val stateDirectory: Path,
    /** Set only on the editor's dev server. */
    val bridge: BridgeConfig? = null,
    /** How the resource pack reaches players. The default serves it on loopback, which is right for a dev server. */
    val pack: PackConfig = PackConfig(),
    /** When a script is slow enough to warn about. */
    val performance: PerformanceConfig = PerformanceConfig(),
    /** The time zone `nf.schedule` keeps time in. */
    val schedules: ScheduleConfig = ScheduleConfig(),
    /** The MySQL and PostgreSQL servers `nf.db("name")` may open: whoever runs the server names them, in `databases:`. */
    val databases: DatabasesConfig = DatabasesConfig(),
    /** What every call into a script is held to besides its budget: time, memory, the standard library's caps. */
    val sandbox: SandboxLimits = SandboxLimits(),
    /** What `nf.http.request` may reach and how much of it a package may do. */
    val http: HttpConfig = HttpConfig(),
    /** Whether `nf.web` routes are served, and the owner's limits on them. */
    val web: WebConfig = WebConfig(),
    /** Whether scripts may join bots (`nf.bots`), and how many. */
    val bots: BotsConfig = BotsConfig(),
    /** How fast `world:place_gradually` and `world:fill_gradually` go, and how many a package may have going. */
    val structures: StructuresConfig = StructuresConfig(),
    /** How much of screens' pictures each player is sent a tick. */
    val screens: ScreensConfig = ScreensConfig(),
    /** How WebAssembly modules (`nf.wasm`) run: threads, memory, time, and how many a package may keep. */
    val wasm: WasmConfig = WasmConfig(),
    /** Whether this server is part of a network (`nf.network`), how it reaches the hub, and what its packages may send. */
    val network: NetworkConfig = NetworkConfig(),
    /**
     * Whether the project's dependencies are found where `netherforge.json`'s
     * paths point: on the editor's dev server (and in tests). A production
     * server never resolves anything: it runs bundles `netherforge build`
     * wrote, and refuses a project with dependencies that isn't one.
     */
    val resolvesPackages: Boolean = true,
    /**
     * The editor's package cache (`-Dnetherforge.packages`), where a dev
     * server finds the checkouts of the git packages `netherforge.lock`
     * pins; null elsewhere.
     */
    val packageCache: Path? = null,
    /** Set only when the script test runner runs the project: what `nf.test` moves (the fake server's clock, its players). Never on a server. */
    val testing: TestHarness? = null,
    /**
     * How fast the wall clock `nf.schedule` and `nf.server.unix_time()` read runs: 1 always, except on the
     * integration test's dev servers ([WALL_CLOCK_RATE_PROPERTY]).
     */
    val wallClockRate: Double = 1.0
) {
    companion object {
        /**
         * Test-only, and read only on a dev server: `-Dnetherforge.test.wall-clock-rate=60` runs the wall clock 60
         * times as fast from start-up, so the integration test sees a cron schedule fire on the server's tick within
         * a second rather than at the next real minute. The editor never sets it.
         */
        const val WALL_CLOCK_RATE_PROPERTY = "netherforge.test.wall-clock-rate"

        /** What the adapter logs when [read] finds no project to run. */
        const val NO_PROJECT =
            "No project to run. Set `project:` in plugins/NetherForge/config.yml to a NetherForge project folder and restart."

        /** The port the plugin serves the resource pack on when `resource-pack.port` isn't set. */
        const val DEFAULT_PACK_PORT = 8163

        /**
         * The configuration a server runs with, or null when there's no
         * project to run.
         *
         * [settings] is the plugin's `config.yml` as plain values, which the
         * adapter reads and hands over without understanding them: a section
         * is a `Map<String, Any?>`, a list a `List`, a scalar as YAML gives it.
         * Every adapter reads the file alike because only this reads it.
         *
         * The editor's dev server is started with `-Dnetherforge.project=<dir>`
         * (and `-Dnetherforge.bridge.port`), read from [properties] and [env];
         * it ignores `project:` and `resource-pack:`. A production server
         * names its project (or the bundle `netherforge build` wrote) in
         * `project:`, relative to [server]'s folder, and resolves no packages.
         */
        fun read(
            pluginFolder: Path,
            settings: Map<String, Any?>,
            server: ServerAddress,
            properties: (String) -> String? = System::getProperty,
            env: (String) -> String? = System::getenv
        ): RuntimeConfig? {
            val performance = performance(settings.section("performance"))
            val schedules = ScheduleConfig(settings.section("schedules").text("time-zone"))
            val http = http(settings.section("http"))
            val web = web(settings.section("web"))
            val databases = DatabasesConfig.read(settings.section("databases"))
            val structures = structures(settings.section("structures"))
            val screens = screens(settings.section("screens"))
            val wasm = wasm(settings.section("wasm"))
            val network = network(settings.section("network"))
            properties(Bridge.PROJECT_PROPERTY)?.takeIf { it.isNotBlank() }?.let { project ->
                val port = properties(Bridge.PORT_PROPERTY)?.toIntOrNull()
                return RuntimeConfig(
                    project = Path.of(project).toAbsolutePath().normalize(),
                    dataDirectory = pluginFolder.resolve("data"),
                    stateDirectory = pluginFolder,
                    bridge = port?.let { BridgeConfig(it, env(Bridge.TOKEN_ENV).orEmpty()) },
                    performance = performance,
                    schedules = schedules,
                    http = http,
                    web = web,
                    // Bots are always on where the project is being made: trying them is the point.
                    bots = bots(settings.section("bots")).copy(enabled = true),
                    databases = databases,
                    structures = structures,
                    screens = screens,
                    wasm = wasm,
                    network = devNetwork(network, properties, env),
                    packageCache = properties(Bridge.PACKAGE_CACHE_PROPERTY)?.takeIf {
                        it.isNotBlank()
                    }?.let { Path.of(it).toAbsolutePath().normalize() },
                    wallClockRate = properties(WALL_CLOCK_RATE_PROPERTY)?.toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() } ?: 1.0
                )
            }
            val project = settings.text("project") ?: return null
            return RuntimeConfig(
                project = server.directory.resolve(project).toAbsolutePath().normalize(),
                dataDirectory = pluginFolder.resolve("data"),
                stateDirectory = pluginFolder,
                pack = pack(settings.section("resource-pack"), server),
                performance = performance,
                schedules = schedules,
                http = http,
                web = web,
                bots = bots(settings.section("bots")),
                databases = databases,
                structures = structures,
                screens = screens,
                wasm = wasm,
                network = network,
                resolvesPackages = false
            )
        }

        /**
         * `resource-pack:`, for a production server. By default the plugin
         * serves the pack itself on every interface; players reach it at
         * `public-url`, or at this server's address when that's empty.
         */
        private fun pack(section: Map<String, Any?>, server: ServerAddress): PackConfig {
            val port = section.int("port") ?: DEFAULT_PACK_PORT
            val host = server.ip.takeIf { it.isNotBlank() && it != "0.0.0.0" } ?: "localhost"
            return PackConfig(
                enabled = section.boolean("enabled") ?: true,
                bind = section.text("bind") ?: "0.0.0.0",
                port = port,
                publicUrl = section.text("public-url") ?: "http://$host:$port",
                externalUrl = section.text("external-url"),
                required = section.boolean("required") ?: false,
                prompt = section.text("prompt")
            )
        }

        /** `http:`: what `nf.http.request` may reach and do. Read on dev servers too; a number that isn't a positive one is the default. */
        private fun http(section: Map<String, Any?>): HttpConfig {
            val defaults = HttpConfig()
            return HttpConfig(
                allowPrivateAddresses = section.boolean("allow-private-addresses") ?: defaults.allowPrivateAddresses,
                maxRequestBytes = section.int("max-request-bytes")?.takeIf { it > 0 } ?: defaults.maxRequestBytes,
                maxResponseBytes = section.int("max-response-bytes")?.takeIf { it > 0 } ?: defaults.maxResponseBytes,
                timeoutSeconds = section.int("timeout-seconds")?.takeIf { it > 0 } ?: defaults.timeoutSeconds,
                requestsPerMinute = section.int("requests-per-minute")?.takeIf { it > 0 } ?: defaults.requestsPerMinute,
                connections = connections(section.section("connections"))
            )
        }

        /** `http.connections:`: what `nf.http.stream` and `nf.http.websocket` may hold open and move. A number that isn't a positive one is the default. */
        private fun connections(section: Map<String, Any?>): ConnectionLimits {
            val defaults = ConnectionLimits()
            fun positive(key: String, default: Int) = section.int(key)?.takeIf { it > 0 } ?: default
            return ConnectionLimits(
                maxPerPackage = positive("max-per-package", defaults.maxPerPackage),
                maxTotal = positive("max-total", defaults.maxTotal),
                maxMessageBytes = positive("max-message-bytes", defaults.maxMessageBytes),
                bytesPerMinute = positive("bytes-per-minute", defaults.bytesPerMinute),
                idleSeconds = positive("idle-seconds", defaults.idleSeconds),
                maxQueuedMessages = positive("max-queued-messages", defaults.maxQueuedMessages),
                messagesPerTick = positive("messages-per-tick", defaults.messagesPerTick)
            )
        }

        /** `web:`: whether `nf.web` routes are served, and their limits. Read on dev servers too; a number that isn't a positive one is the default. */
        private fun web(section: Map<String, Any?>): WebConfig {
            val defaults = WebConfig()
            fun positive(key: String, default: Int) = section.int(key)?.takeIf { it > 0 } ?: default
            return WebConfig(
                enabled = section.boolean("enabled") ?: defaults.enabled,
                publicUrl = section.text("public-url")?.trimEnd('/'),
                trustProxyHeaders = section.boolean("trust-proxy-headers") ?: defaults.trustProxyHeaders,
                maxBodyBytes = positive("max-body-bytes", defaults.maxBodyBytes),
                maxResponseBytes = positive("max-response-bytes", defaults.maxResponseBytes),
                requestsPerMinutePerIp = positive("requests-per-minute-per-ip", defaults.requestsPerMinutePerIp),
                maxPending = positive("max-pending", defaults.maxPending),
                handlerTimeoutSeconds = positive("handler-timeout-seconds", defaults.handlerTimeoutSeconds)
            )
        }

        /** `bots:`: whether scripts may join bots, and how many at once. A number that isn't a positive one is the default. */
        private fun bots(section: Map<String, Any?>): BotsConfig {
            val defaults = BotsConfig()
            return BotsConfig(
                enabled = section.boolean("enabled") ?: defaults.enabled,
                max = section.int("max")?.takeIf { it > 0 } ?: defaults.max
            )
        }

        /** `structures:`: how fast gradual placements and fills go. A number that isn't a positive one is the default. */
        private fun structures(section: Map<String, Any?>): StructuresConfig {
            val defaults = StructuresConfig()
            fun positive(key: String, default: Int) = section.int(key)?.takeIf { it > 0 } ?: default
            return StructuresConfig(
                blocksPerTick = positive("blocks-per-tick", defaults.blocksPerTick),
                maxJobsPerPackage = positive("max-jobs-per-package", defaults.maxJobsPerPackage)
            )
        }

        /** `screens:`: how many bytes of screens' pictures a player is sent a tick. Read on dev servers too. */
        private fun screens(section: Map<String, Any?>): ScreensConfig {
            val defaults = ScreensConfig()
            return ScreensConfig(
                bytesPerTick = (
                    section.int(
                        "bytes-per-tick"
                    ) ?: defaults.bytesPerTick
                    ).coerceIn(ScreensConfig.MIN_BYTES_PER_TICK, ScreensConfig.MAX_BYTES_PER_TICK)
            )
        }

        /** `wasm:`: how WebAssembly modules run. Read on dev servers too; a value that isn't one (zero, negative) is the default. */
        private fun wasm(section: Map<String, Any?>): WasmConfig {
            val defaults = WasmConfig()
            fun positive(key: String, default: Int) = section.int(key)?.takeIf { it > 0 } ?: default
            return WasmConfig(
                threads = positive("threads", defaults.threads),
                compile = section.boolean("compile") ?: defaults.compile,
                maxModuleMb = positive("max-module-mb", defaults.maxModuleMb),
                maxMemoryMb = positive("max-memory-mb", defaults.maxMemoryMb),
                maxMemoryTotalMb = positive("max-memory-total-mb", defaults.maxMemoryTotalMb),
                maxCallSeconds = positive("max-call-seconds", defaults.maxCallSeconds),
                maxQueuedCallsPerPackage = positive("max-queued-calls-per-package", defaults.maxQueuedCallsPerPackage),
                maxInstancesPerPackage = positive("max-instances-per-package", defaults.maxInstancesPerPackage),
                maxProcessesPerPackage = positive("max-processes-per-package", defaults.maxProcessesPerPackage),
                maxProcessesTotal = positive("max-processes-total", defaults.maxProcessesTotal),
                maxOutputBytes = positive("max-output-bytes", defaults.maxOutputBytes),
                // At least a character's four bytes, so a long line is cut between characters.
                maxLineBytes = positive("max-line-bytes", defaults.maxLineBytes).coerceAtLeast(4),
                maxQueuedLines = positive("max-queued-lines", defaults.maxQueuedLines),
                linesPerTick = positive("lines-per-tick", defaults.linesPerTick)
            )
        }

        /**
         * `network:`: whether this server is part of a network and how it reaches the hub. Read on dev servers too; a
         * number that isn't a positive one is the default. A `hub` that isn't `host:port` leaves the default address,
         * which the server then can't reach, saying so.
         */
        private fun network(section: Map<String, Any?>): NetworkConfig {
            val defaults = NetworkConfig()
            fun positive(key: String, default: Int) = section.int(key)?.takeIf { it > 0 } ?: default
            val hub = NetworkConfig.hub(section.text("hub"))
            return NetworkConfig(
                enabled = section.boolean("enabled") ?: defaults.enabled,
                host = hub?.first ?: defaults.host,
                port = hub?.second ?: defaults.port,
                serverName = section.text("server-name")?.trim(),
                secret = section.text("secret")?.trim(),
                messagesPerMinute = positive("messages-per-minute", defaults.messagesPerMinute),
                requestTimeoutSeconds = positive("request-timeout-seconds", defaults.requestTimeoutSeconds),
                messagesPerTick = positive("messages-per-tick", defaults.messagesPerTick),
                maxQueued = positive("max-queued-messages", defaults.maxQueued)
            )
        }

        /**
         * A dev server the editor put in its dev network: the hub, this server's name and the secret come from the
         * editor ([Bridge.NETWORK_HUB_PROPERTY]) and replace `config.yml`'s; the owner's limits stay.
         */
        private fun devNetwork(network: NetworkConfig, properties: (String) -> String?, env: (String) -> String?): NetworkConfig {
            val hub = NetworkConfig.hub(properties(Bridge.NETWORK_HUB_PROPERTY)) ?: return network
            return network.copy(
                enabled = true,
                host = hub.first,
                port = hub.second,
                serverName = properties(Bridge.NETWORK_SERVER_PROPERTY)?.trim(),
                secret = env(Bridge.NETWORK_SECRET_ENV)?.trim()
            )
        }

        /** `performance:`: when a script is slow enough to warn about. Read on dev servers too. */
        private fun performance(section: Map<String, Any?>): PerformanceConfig {
            val defaults = PerformanceConfig()
            return PerformanceConfig(
                warnMillis = (section.double("warn-ms") ?: defaults.warnMillis).coerceAtLeast(0.0),
                warnTicks = (section.int("warn-ticks") ?: defaults.warnTicks).coerceIn(1, 100)
            )
        }

        @Suppress("UNCHECKED_CAST")
        private fun Map<String, Any?>.section(key: String): Map<String, Any?> = this[key] as? Map<String, Any?> ?: emptyMap()

        private fun Map<String, Any?>.text(key: String): String? = this[key]?.toString()?.takeIf { it.isNotBlank() }

        private fun Map<String, Any?>.int(key: String): Int? = when (val value = this[key]) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }

        private fun Map<String, Any?>.double(key: String): Double? = when (val value = this[key]) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }

        private fun Map<String, Any?>.boolean(key: String): Boolean? = when (val value = this[key]) {
            is Boolean -> value
            is String -> value.trim().lowercase().toBooleanStrictOrNull()
            else -> null
        }
    }
}

/**
 * Where the server runs: its folder (what `project:` is relative to) and the
 * address it's bound to (`server-ip`, empty for every interface).
 */
data class ServerAddress(val directory: Path, val ip: String)
