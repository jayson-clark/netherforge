package dev.netherforge.plugin.integration.support

import dev.netherforge.format.bridge.Bridge
import dev.netherforge.format.bridge.HelloParams
import org.sqlite.SQLiteConfig
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A headless Paper of the adapter's version in [Adapter.server], with the
 * NetherForge plugin installed, the way the editor runs a dev
 * server: a flat world, the project and the bridge port as system properties,
 * the token in the environment. [prepare] starts from a fresh world;
 * [start] runs it once.
 */
class PaperServer(
    private val onlineMode: Boolean = false,
    private val maxPlayers: Int = 1,
    private val viewDistance: Int = 2,
    /** `level-seed`, when the main world's terrain matters; otherwise the server picks one. */
    private val levelSeed: Long? = null,
    /** Whether `bukkit.yml` asks NetherForge to generate the main world (`worlds.world.generator: NetherForge`), as the editor's does for a project that names one. */
    private val mainWorldGenerator: Boolean = false,
    /** Whether the game generates its structures (`generate-structures`): off, so worlds compare block for block. */
    private val structures: Boolean = false,
    /** More system properties for the server's JVM, given each time it starts: `-Dname=value`. */
    private val jvmProperties: List<String> = emptyList()
) {
    val folder: Path = Adapter.server

    /** A server folder that keeps Paper's downloads between runs but starts with a fresh world and plugins. */
    fun prepare(plugins: List<Path> = listOf(Adapter.plugin)) {
        Files.createDirectories(folder)
        for (stale in listOf(
            "world",
            "world_nether",
            "world_the_end",
            // What a failed run of the managed worlds scenario could leave: copies Paper hadn't imported yet, or
            // (before 26.1) worlds of their own.
            "it_copy",
            "it_copy2",
            "it_void",
            "it_lobby",
            "it_from_map",
            // Worlds the structure and natural spawning scenarios generate: a kept one has its chunks (and their
            // markers) already generated, so it would spawn nothing new.
            "it_gen",
            "it_wg",
            "it_grove",
            "it_spawn",
            "it_dp",
            // The dimension scenario's world of a deep type, should a failed run leave it.
            "it_deep_mine",
            // The worlds examples/showcase's netherforge.json makes (before 26.1, folders of their own): a kept one keeps
            // the dimension type it was first made with, which another scenario's project may not have given it.
            "hills",
            "sky",
            // What a failed run of the bot scenario could leave: a ban it never lifted.
            "banned-players.json",
            "whitelist.json",
            "plugins",
            "logs"
        )) {
            folder.resolve(stale).toFile().deleteRecursively()
        }
        Files.createDirectories(folder.resolve("plugins"))
        for (plugin in plugins) Files.copy(plugin, folder.resolve("plugins").resolve(plugin.fileName))
        // The slow-script warning is wall-clock time, so a loaded machine raises it in any scenario (as a script message
        // their "no script errors" checks see): it's off here, and the runtime's own tests cover it.
        Files.createDirectories(folder.resolve("plugins/NetherForge"))
        // Web routes are on, as a server owner turns them on: examples/showcase declares some, which would otherwise be a
        // warning (`runtime.web-disabled`) on a project every scenario expects to load clean.
        folder.resolve("plugins/NetherForge/config.yml").writeText("performance:\n  warn-ms: 0\nweb:\n  enabled: true\n")
        // examples/showcase's expo builds its booths over the main world's spawn, hands everyone a tour guide and shows them
        // a boss bar: what a server whose main world isn't the expo's plaza turns off, as these scenarios' worlds aren't.
        Files.createDirectories(folder.resolve("plugins/NetherForge/settings"))
        folder.resolve("plugins/NetherForge/settings/showcase.json").writeText("{ \"expo\": false }\n")
        // The server writes its defaults into a bukkit.yml that isn't there: a scenario's routing never outlives it.
        Files.deleteIfExists(folder.resolve("bukkit.yml"))
        if (mainWorldGenerator) folder.resolve("bukkit.yml").writeText("worlds:\n  world:\n    generator: NetherForge\n")
        // Bots type commands as fast as the test drives them: vanilla would kick one for spamming. Spigot's
        // exclusions match by prefix, so "/" exempts every command.
        folder.resolve("spigot.yml").writeText("commands:\n  spam-exclusions:\n  - /\n")
        folder.resolve("server.properties").writeText(
            """
            online-mode=$onlineMode
            white-list=false
            server-ip=127.0.0.1
            server-port=0
            level-type=minecraft\:flat
            generator-settings={"layers"\:[{"block"\:"minecraft\:bedrock","height"\:1},{"block"\:"minecraft\:grass_block","height"\:1}],"biome"\:"minecraft\:plains"}
            generate-structures=$structures
            spawn-protection=0
            view-distance=$viewDistance
            simulation-distance=$viewDistance
            max-players=$maxPlayers
            sync-chunk-writes=false
            """.trimIndent() + "\n" + (
                levelSeed?.let {
                    "level-seed=$it\n"
                } ?: ""
                )
        )
    }

    /** One run of the server: the editor's end of its bridge, and a clean [stop]. */
    inner class Running(val process: Process, val editor: Editor, val hello: HelloParams) {
        /** Stops the server through the bridge, as the editor does, and checks it shut down cleanly. */
        fun stop() {
            try {
                val stop = editor.tryRun("stop")
                assertTrue(stop.ok, stop.error)
                editor.next(120) { it === Editor.CLOSED }
                assertTrue(process.waitFor(2, TimeUnit.MINUTES), "server exited")
                assertEquals(0, process.exitValue())
            } finally {
                kill()
            }
        }

        /** Ends the process however it is: after a failure, so the next scenario finds the port and the world free. */
        fun kill() {
            if (process.isAlive) process.destroyForcibly().waitFor(30, TimeUnit.SECONDS)
        }
    }

    /** Starts the server with [project] and waits for the plugin's hello over the bridge; its output goes to [log] too. */
    fun start(project: Path, log: String): Running {
        ServerSocket(0).use { listener ->
            listener.soTimeout = TimeUnit.MINUTES.toMillis(8).toInt()
            val process = launch(
                listOf("-Dnetherforge.project=$project", "-Dnetherforge.bridge.port=${listener.localPort}") + jvmProperties,
                log
            )
            try {
                val editor = Editor(listener.accept())
                val hello = editor.next { true } as HelloParams
                assertEquals(TOKEN, hello.token)
                return Running(process, editor, hello)
            } catch (e: Throwable) {
                process.destroyForcibly()
                throw e
            }
        }
    }

    /**
     * Starts the server with [project] expecting it to give up starting by itself, before any plugin enables (it
     * refuses the datapacks it loads): its exit code. Its output goes to [log] too.
     */
    fun refuses(project: Path, log: String): Int {
        ServerSocket(0).use { listener ->
            val process =
                launch(listOf("-Dnetherforge.project=$project", "-Dnetherforge.bridge.port=${listener.localPort}") + jvmProperties, log)
            try {
                assertTrue(process.waitFor(5, TimeUnit.MINUTES), "the server gave up starting by itself")
                return process.exitValue()
            } finally {
                if (process.isAlive) process.destroyForcibly().waitFor(30, TimeUnit.SECONDS)
            }
        }
    }

    /** Starts the JVM with [properties], its output in [log] (and the test's own output). */
    fun launch(properties: List<String>, log: String): Process {
        // A free port for each start, not one for the scenario: a port let go is the OS's to hand out again (to the
        // bridge's listener, or a connection's own end), so a restart reusing it could find it taken.
        val settings = folder.resolve("server.properties")
        val port = ServerSocket(0).use { it.localPort }
        settings.writeText(settings.readText().replace(Regex("(?m)^server-port=.*$"), "server-port=$port"))
        val command = listOf(
            Adapter.java,
            "-Xmx2G",
            // The plugin loads Lua's native library; without this Java 25 prints a warning (the editor passes it too).
            "--enable-native-access=ALL-UNNAMED",
            // Test-only. The editor never does this: the user accepts the Mojang EULA in its UI.
            "-Dcom.mojang.eula.agree=true"
        ) + properties + listOf("-jar", Adapter.paper.toString(), "--nogui")
        val process = ProcessBuilder(command)
            .directory(folder.toFile())
            .redirectErrorStream(true)
            .apply { environment()[Bridge.TOKEN_ENV] = TOKEN }
            .start()
        val file = folder.resolve(log).toFile()
        Thread {
            java.io.FileWriter(file, true).buffered().use { out ->
                process.inputStream.bufferedReader().forEachLine { line ->
                    out.write(line)
                    out.newLine()
                    out.flush()
                    println("[paper ${Adapter.minecraft}] $line")
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        return process
    }

    data class SavedEntities(val displays: Map<String, String>, val hitboxes: Map<String, String>)

    /** The single instance's entities as the plugin's store recorded them. */
    fun entities(): SavedEntities {
        val rows = stored("SELECT role, node, entity FROM instance_entities")
        fun nodes(role: String) = rows.filter { it["role"] == role }.associate { it.getValue("node")!! to it.getValue("entity")!! }
        return SavedEntities(nodes("display"), nodes("hitbox"))
    }

    /**
     * The rows [sql] selects from the plugin's store (`plugins/NetherForge/netherforge.db`),
     * read beside the running server: the plugin keeps it in WAL mode, so a reader never waits for it.
     */
    fun stored(sql: String): List<Map<String, String?>> {
        val file = folder.resolve("plugins/NetherForge/netherforge.db")
        return SQLiteConfig().apply { setReadOnly(true) }.createConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    val columns = (1..rows.metaData.columnCount).map { rows.metaData.getColumnName(it) }
                    buildList { while (rows.next()) add(columns.associateWith { rows.getString(it) }) }
                }
            }
        }
    }

    companion object {
        const val TOKEN = "integration-test-token"
    }
}
