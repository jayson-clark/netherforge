package dev.netherforge.plugin.ffi

import dev.netherforge.plugin.RuntimeConfig
import dev.netherforge.plugin.paper.ProjectRuntime
import dev.netherforge.plugin.paper.ProjectRuntimes
import dev.netherforge.plugin.platform.Platform
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * The Rust runtime as the plugin runs it (`-Dnetherforge.runtime=rust`): the library loaded, and the
 * project started on the server's platform once the server has loaded ([NativeRuntime]).
 */
class RustRuntimes : ProjectRuntimes {
    override val name = "rust"

    override fun create(plugin: JavaPlugin, platform: () -> Platform, settings: Map<String, Any?>): ProjectRuntime =
        object : ProjectRuntime {
            private var runtime: NativeRuntime? = null

            override fun enable() {
                Native.load(library(plugin.dataFolder.toPath()))
                val native = NativeRuntime(platform())
                val started = native.start(
                    pluginFolder = plugin.dataFolder.toPath(),
                    serverFolder = plugin.server.worldContainer.toPath(),
                    serverIp = plugin.server.ip,
                    settings = settings
                )
                if (started) runtime = native else plugin.logger.warning(RuntimeConfig.NO_PROJECT)
            }

            override fun tick() {
                runtime?.tick()
            }

            override fun disable() {
                runtime?.stop()
                runtime = null
            }

            /** Asked before [enable], as the server loads its main world: the generators are published then. */
            override fun defaultWorldGenerator(world: String): String? {
                Native.load(library(plugin.dataFolder.toPath()))
                return NativeRuntime(platform()).defaultGenerator(
                    world = world,
                    pluginFolder = plugin.dataFolder.toPath(),
                    serverFolder = plugin.server.worldContainer.toPath(),
                    serverIp = plugin.server.ip,
                    settings = settings
                )
            }
        }

    companion object {
        /** A library built elsewhere (a dev build's `target/`), instead of the jar's. */
        const val LIBRARY_PROPERTY = "netherforge.ffi.library"

        /** Where the jar keeps the library for this machine: `native/<os>-<arch>/<file>`. */
        fun resource(): String = "META-INF/netherforge/native/${platform()}/${fileName()}"

        fun platform(): String {
            val os = System.getProperty("os.name").lowercase()
            val name = when {
                os.contains("mac") -> "macos"
                os.contains("win") -> "windows"
                else -> "linux"
            }
            val arch = when (val a = System.getProperty("os.arch").lowercase()) {
                "amd64", "x86_64" -> "x86_64"
                "aarch64", "arm64" -> "aarch64"
                else -> a
            }
            return "$name-$arch"
        }

        fun fileName(): String {
            val os = System.getProperty("os.name").lowercase()
            return when {
                os.contains("mac") -> "libnetherforge_ffi.dylib"
                os.contains("win") -> "netherforge_ffi.dll"
                else -> "libnetherforge_ffi.so"
            }
        }

        /**
         * The library to load: [LIBRARY_PROPERTY]'s, or the jar's, copied into the plugin's folder under its hash
         * (a library can't be loaded from inside a jar, and one that's loaded can't be replaced on every OS).
         */
        fun library(pluginFolder: Path): Path {
            System.getProperty(LIBRARY_PROPERTY)?.let { return Path.of(it) }
            val bytes = RustRuntimes::class.java.classLoader.getResourceAsStream(resource())?.use { it.readBytes() }
                ?: throw IllegalStateException(
                    "This NetherForge jar has no Rust runtime for ${platform()} (${resource()}); set -D$LIBRARY_PROPERTY to one"
                )
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
            val file = pluginFolder.resolve("native").resolve("$hash-${fileName()}")
            if (!Files.exists(file)) {
                Files.createDirectories(file.parent)
                val temp = Files.createTempFile(file.parent, "netherforge", ".part")
                Files.write(temp, bytes)
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE)
            }
            return file
        }
    }
}
