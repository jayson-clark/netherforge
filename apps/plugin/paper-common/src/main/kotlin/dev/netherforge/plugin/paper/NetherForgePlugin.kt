package dev.netherforge.plugin.paper

import dev.netherforge.plugin.Folia
import dev.netherforge.plugin.NetherForgeRuntime
import dev.netherforge.plugin.RuntimeConfig
import dev.netherforge.plugin.ServerAddress
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.ServerLoadEvent
import org.bukkit.generator.ChunkGenerator
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.util.logging.Level

/**
 * The NetherForge plugin, the same on every Paper version: builds the
 * platform (with this jar's [PaperVersion]), hands it to the runtime, and
 * ticks it. Everything else is the runtime's, reading `config.yml` included.
 *
 * Which project runs: `-Dnetherforge.project=<dir>` when the editor launches a
 * dev server (with the bridge on `-Dnetherforge.bridge.port`), otherwise
 * `project:` in `plugins/NetherForge/config.yml`.
 *
 * **Enabled before the worlds load** (`load: STARTUP`), because the server
 * asks an enabled plugin for a world's generator as it loads the world
 * (`bukkit.yml`'s `worlds.<name>.generator: NetherForge`, answered by
 * [getDefaultWorldGenerator]): that's how a project generator makes the main
 * world. Nothing else of the runtime runs that early: [onEnable] only builds
 * the platform and the runtime, and the runtime is enabled once the server
 * has loaded (`ServerLoadEvent`: every world loaded, every other plugin
 * enabled, Vault and PlaceholderAPI included), where a plugin enabled after
 * the worlds would have started it.
 */
class NetherForgePlugin :
    JavaPlugin(),
    Listener {
    /** This adapter's own: what its Minecraft version does differently. The contract suites' plugin makes its platform with it. */
    val version: PaperVersion by lazy { PaperVersion.load() }

    private var runtime: NetherForgeRuntime? = null
    private var platform: PaperPlatform? = null
    private var ticker: BukkitTask? = null

    /** Whether the runtime was enabled: from the server's load until the plugin is disabled. */
    private var started = false

    override fun onEnable() {
        // The server loaded its datapacks before enabling any plugin: the start-up datapack's among them.
        PaperDatapacks.loaded(dataFolder.toPath())
        if (Folia.detected()) {
            logger.severe(Folia.REFUSAL)
            server.pluginManager.disablePlugin(this)
            return
        }
        saveDefaultConfig()
        val config = RuntimeConfig.read(
            pluginFolder = dataFolder.toPath(),
            settings = config.plain(),
            server = ServerAddress(server.worldContainer.toPath(), server.ip)
        )
        if (config == null) {
            logger.warning(RuntimeConfig.NO_PROJECT)
            return
        }

        val platform = PaperPlatform(this, version)
        this.platform = platform
        runtime = NetherForgeRuntime(platform, config)
        server.pluginManager.registerEvents(this, this)
    }

    /** The server has loaded its worlds and every plugin: the runtime starts. */
    @EventHandler
    fun onServerLoad(event: ServerLoadEvent) {
        val runtime = runtime ?: return
        if (started) return
        started = true
        runtime.enable()
        ticker = server.scheduler.runTaskTimer(
            this,
            Runnable {
                try {
                    runtime.tick()
                } catch (e: Exception) {
                    logger.log(Level.SEVERE, "NetherForge tick failed", e)
                }
            },
            1L,
            1L
        )
    }

    /**
     * The server is about to load world [worldName] (its main world), which
     * `bukkit.yml` says NetherForge generates: with the generator
     * `netherforge.json`'s `worlds.<name>.generator` names. [id] (what follows
     * `NetherForge:` in `bukkit.yml`) doesn't choose it.
     */
    override fun getDefaultWorldGenerator(worldName: String, id: String?): ChunkGenerator? {
        if (id != null) {
            logger.warning(
                "bukkit.yml names \"$id\" after NetherForge for world \"$worldName\", which is ignored: netherforge.json says which " +
                    "generator a world has"
            )
        }
        val runtime = runtime ?: run {
            logger.severe("The server asks NetherForge to generate world \"$worldName\", but NetherForge has no project to run")
            return null
        }
        val generator = runtime.defaultWorldGenerator(worldName) ?: return null
        return platform?.worldManager?.startupGenerator(generator)
    }

    override fun onDisable() {
        ticker?.cancel()
        ticker = null
        if (started) runtime?.disable()
        runtime = null
        // After the runtime has stopped, as a real player's quit is (Paper disables plugins, then saves and removes players): what
        // stopping puts back (a cutscene's player) is back before a bot's data is saved as it leaves.
        if (started) platform?.bots?.leaveAll()
        started = false
        // The note block setup is the runtime's too: it is let go once nothing is left to use it.
        platform?.close()
        platform = null
    }
}

/** A YAML section as the plain values the runtime reads settings from: sections become maps. */
internal fun ConfigurationSection.plain(): Map<String, Any?> = getKeys(false).associateWith { key ->
    when (val value = get(key)) {
        is ConfigurationSection -> value.plain()
        else -> value
    }
}
