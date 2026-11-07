package dev.netherforge.plugin.paper

import dev.netherforge.format.datapack.GameDatapack
import dev.netherforge.format.datapack.StartupDatapack
import dev.netherforge.plugin.datapack.DatapackRefusal
import dev.netherforge.plugin.datapack.StartupDatapackFiles
import dev.netherforge.plugin.ffi.Native
import dev.netherforge.plugin.ffi.NativeRuntime
import dev.netherforge.plugin.ffi.RustRuntime
import dev.netherforge.plugin.platform.DatapackOps
import dev.netherforge.plugin.platform.PlatformLog
import io.papermc.paper.datapack.Datapack
import io.papermc.paper.plugin.bootstrap.BootstrapContext
import io.papermc.paper.plugin.bootstrap.PluginBootstrap
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import net.kyori.adventure.text.Component
import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * What NetherForge does before the server loads its worlds: hands it the
 * start-up datapack ([StartupDatapack]: the project's advancements, dialogs and structures), built
 * from the project's files in the plugin's folder (`datapack/`) and found
 * through Paper's datapack discovery, so the server loads it with its own.
 * Nothing else of the runtime exists yet; the plugin itself starts later, as
 * any plugin does, and reads what was built here through [PaperDatapacks].
 *
 * Built by the Rust runtime from the files alone: there's no game data this
 * early, and the runtime's restart check builds it the same way (`reload.rs`'s
 * `for_start`).
 */
class NetherForgeBootstrap : PluginBootstrap {
    override fun bootstrap(context: BootstrapContext) {
        context.lifecycleManager.registerEventHandler(LifecycleEvents.DATAPACK_DISCOVERY) { event ->
            val start = try {
                build(context)
            } catch (e: Exception) {
                context.logger.error(
                    "NetherForge couldn't build the project's start-up datapack; the project's advancements, dialogs and structures won't be on the server",
                    e
                )
                null
            }
            val files = start?.files.orEmpty()
            PaperDatapacks.built = files
            PaperDatapacks.refused = start?.refused
            start?.refused?.let { refused ->
                // The game's report has an entry per file it refused (a sky's twins are hundreds): its first lines.
                val lines = refused.report.lines()
                val more = if (lines.size > REPORT_LINES) "\n... and ${lines.size - REPORT_LINES} more lines" else ""
                context.logger.warn(
                    "The server refused the project's start-up datapack the last time it started, so it starts without what it " +
                        "named (its datapacks or skies) until they change. What it said:\n" +
                        lines.take(REPORT_LINES).joinToString("\n") + more
                )
            }
            // Only the server knows whether the game reads a datapack's worldgen or a twin biome: if it refuses the pack, it says why here.
            if (start != null && start.refused == null && start.canLeaveOut) PaperDatapacks.watch(context.dataDirectory, start.hash)
            if (files.isEmpty()) return@registerEventHandler
            val folder = context.dataDirectory.resolve(FOLDER)
            StartupDatapackFiles.write(folder, files)
            event.registrar().discoverPack(folder, StartupDatapack.ID) {
                it.title(Component.text("NetherForge")).autoEnableOnServerStart(true).position(true, Datapack.Position.TOP)
            }
        }
    }

    /**
     * The datapack's files for the project `config.yml` (or the editor) names, and whether they leave its datapacks
     * out (the server refused them last time: [DatapackRefusal]); null when there's no project or nothing for one.
     * The Rust runtime builds it ([NativeRuntime.startupDatapack]), on a platform that knows only the datapacks.
     */
    private fun build(context: BootstrapContext): Built? {
        val folder = context.dataDirectory
        val file = folder.resolve("config.yml")
        val settings = if (Files.isRegularFile(file)) YamlConfiguration.loadConfiguration(file.toFile()).plain() else emptyMap()
        if (ServerVersionFile.dataPackFormat == null) {
            context.logger.warn(
                "The server doesn't say which data pack format it reads, so NetherForge can't give it the project's start-up datapack"
            )
            return null
        }
        val main = mainWorld(Path.of(""))
        PaperDatapacks.mainWorld = main
        val refusal = DatapackRefusal.read(folder)
        val datapacks = object : DatapackOps {
            override val started: Map<String, ByteArray> get() = emptyMap()
            override val refused: DatapackRefusal? get() = refusal
            override val format: List<Int>? get() = ServerVersionFile.dataPackFormat
            override val mainWorld: String get() = main
            override val game: GameDatapack get() = PaperGameDatapack.read

            override fun textJson(text: String, glyph: (reference: String) -> String?): String = PaperText.json(text, glyph)
        }
        val log = object : PlatformLog {
            override fun info(message: String) = context.logger.info(message)

            override fun warn(message: String) = context.logger.warn(message)

            override fun error(message: String, cause: Throwable?) = context.logger.error(message, cause)
        }
        Native.load(RustRuntime.library(folder))
        // The server's folder is where it was started: what `project:` in config.yml is relative to.
        val start = NativeRuntime(NativeRuntime.bootstrap(log, datapacks))
            .startupDatapack(folder, Path.of("").toAbsolutePath(), settings) ?: return null
        if (start.files.isNotEmpty()) context.logger.info("NetherForge's start-up datapack: ${start.files.size - 1} file(s)")
        return Built(start.files, start.hash, refusal.takeIf { start.leftOut }, start.canLeaveOut)
    }

    /** What [build] worked out: the files, the whole datapack's hash, the refusal it left something out for, and whether one could. */
    private class Built(val files: Map<String, ByteArray>, val hash: String, val refused: DatapackRefusal?, val canLeaveOut: Boolean)

    internal companion object {
        /** In the plugin's folder: written at every start, never read but by the server. */
        const val FOLDER = "datapack"

        /** What the server calls its main world when `server.properties` doesn't say. */
        private const val DEFAULT_LEVEL = "world"

        /** How much of a refusal's report the start logs: the game logged the whole of it as it refused. */
        private const val REPORT_LINES = 20

        /**
         * The name of the world the server started in [folder] makes as its main world: `level-name` in its
         * `server.properties` (which the server has written by the time datapacks are discovered), `world` without one.
         * Nothing earlier than the worlds' loading says it through Paper's API.
         */
        fun mainWorld(folder: Path): String {
            val file = folder.resolve("server.properties")
            if (!Files.isRegularFile(file)) return DEFAULT_LEVEL
            val properties = Properties()
            Files.newBufferedReader(file).use(properties::load)
            return properties.getProperty("level-name")?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_LEVEL
        }
    }
}

/**
 * The start-up datapack on Paper: what [NetherForgeBootstrap] gave the
 * server, the data pack format from the server jar, and the server's own
 * text serializer.
 */
class PaperDatapacks : DatapackOps {
    override val started: Map<String, ByteArray> get() = built

    override val refused: DatapackRefusal? get() = Companion.refused

    override val format: List<Int>? get() = ServerVersionFile.dataPackFormat

    override val mainWorld: String? get() = Companion.mainWorld

    override val game: GameDatapack get() = PaperGameDatapack.read

    override fun textJson(text: String, glyph: (reference: String) -> String?): String = PaperText.json(text, glyph)

    companion object {
        /**
         * What the bootstrap built, for the plugin, which starts later in the
         * same class loader (Paper loads a plugin's bootstrapper and its main
         * class with one).
         */
        @Volatile
        internal var built: Map<String, ByteArray> = emptyMap()

        /** The refusal the bootstrap left the project's datapacks out for, if it did. */
        @Volatile
        internal var refused: DatapackRefusal? = null

        /** What hears the server's registry errors while it loads the datapacks, from the bootstrap until the plugin enables. */
        private var watching: AutoCloseable? = null

        /**
         * Keeps the server's own report in [folder] ([DatapackRefusal]) if it refuses the start-up datapack whose
         * [hash] it is: the registry errors it logs as it loads it, just before it gives up starting.
         */
        @Synchronized
        internal fun watch(folder: Path, hash: String) {
            if (watching != null) return
            watching = PaperVersion.load().watchRegistryErrors { report -> DatapackRefusal.write(folder, DatapackRefusal(hash, report)) }
        }

        /**
         * The server loaded its datapacks (a plugin enables only after): nothing more to hear, and a refusal on
         * record in [folder] is out of date unless it's the one the server started without.
         */
        @Synchronized
        internal fun loaded(folder: Path) {
            watching?.close()
            watching = null
            if (refused == null) DatapackRefusal.delete(folder)
        }

        /** The main world's name the bootstrap built the pack for; null when it built none. */
        @Volatile
        internal var mainWorld: String? = null
    }
}
