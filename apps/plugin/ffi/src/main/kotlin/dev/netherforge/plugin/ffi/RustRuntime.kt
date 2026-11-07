package dev.netherforge.plugin.ffi

import dev.netherforge.plugin.platform.Platform
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * The runtime as the plugin runs it: the library loaded from the jar, and the project started on the server's
 * platform once the server has loaded ([NativeRuntime]).
 */
class RustRuntime(
    private val pluginFolder: Path,
    private val serverFolder: Path,
    private val serverIp: String,
    /** `config.yml`'s values. */
    private val settings: Map<String, Any?>,
    /** The server's platform, built when first asked for. */
    private val platform: () -> Platform
) {
    private var runtime: NativeRuntime? = null

    /** Starts the project: false when there's none to run. */
    fun enable(): Boolean {
        Native.load(library(pluginFolder))
        val native = NativeRuntime(platform())
        val started = native.start(pluginFolder = pluginFolder, serverFolder = serverFolder, serverIp = serverIp, settings = settings)
        if (started) runtime = native
        return started
    }

    fun tick() {
        runtime?.tick()
    }

    fun disable() {
        runtime?.stop()
        runtime = null
    }

    /** Asked before [enable], as the server loads its main world: the generators are published then. */
    fun defaultWorldGenerator(world: String): String? {
        Native.load(library(pluginFolder))
        return NativeRuntime(platform()).defaultGenerator(
            world = world,
            pluginFolder = pluginFolder,
            serverFolder = serverFolder,
            serverIp = serverIp,
            settings = settings
        )
    }

    companion object {
        /** What the plugin logs when there's no project to run. */
        const val NO_PROJECT =
            "No project to run. Set `project:` in plugins/NetherForge/config.yml to a NetherForge project folder and restart."

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
            val bytes = RustRuntime::class.java.classLoader.getResourceAsStream(resource())?.use { it.readBytes() }
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
