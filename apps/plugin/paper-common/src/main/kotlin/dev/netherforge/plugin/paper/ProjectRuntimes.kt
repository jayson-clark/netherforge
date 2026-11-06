package dev.netherforge.plugin.paper

import dev.netherforge.plugin.NetherForgeRuntime
import dev.netherforge.plugin.RuntimeConfig
import dev.netherforge.plugin.ServerAddress
import dev.netherforge.plugin.platform.Platform
import org.bukkit.plugin.java.JavaPlugin
import java.util.ServiceLoader

/**
 * The runtime the plugin runs a project with, as [NetherForgePlugin] drives it: built as the
 * plugin enables, [enable]d once the server has loaded, ticked, [disable]d.
 */
interface ProjectRuntime {
    fun enable()

    fun tick()

    fun disable()

    /** The generator `netherforge.json` names for world [world], or null. */
    fun defaultWorldGenerator(world: String): String?
}

/**
 * A runtime the plugin can run: the Kotlin one (in every jar), or another found as a service
 * (`META-INF/services`): the Rust runtime, which the 26.x adapters carry (`:plugin:ffi`).
 * `-Dnetherforge.runtime=<name>` or `runtime: <name>` in `config.yml` chooses; the Kotlin one runs
 * otherwise.
 */
interface ProjectRuntimes {
    val name: String

    /**
     * The runtime on [platform] (built when first asked for) for [plugin], with `config.yml`'s [settings]; null when
     * it can tell there's no project to run.
     */
    fun create(plugin: JavaPlugin, platform: () -> Platform, settings: Map<String, Any?>): ProjectRuntime?

    companion object {
        const val PROPERTY = "netherforge.runtime"
        const val KOTLIN = "kotlin"

        /** Which runtime the server's owner chose. */
        fun chosen(settings: Map<String, Any?>): String =
            (System.getProperty(PROPERTY) ?: settings["runtime"]?.toString())?.trim()?.lowercase()?.ifEmpty { null } ?: KOTLIN

        /** The runtime called [name]: the Kotlin one, or a service this jar carries. */
        fun named(name: String, loader: ClassLoader): ProjectRuntimes? = if (name == KOTLIN) {
            Kotlin
        } else {
            ServiceLoader.load(ProjectRuntimes::class.java, loader).firstOrNull { it.name == name }
        }
    }

    /** The Kotlin runtime. */
    object Kotlin : ProjectRuntimes {
        override val name = KOTLIN

        override fun create(plugin: JavaPlugin, platform: () -> Platform, settings: Map<String, Any?>): ProjectRuntime? {
            val config = RuntimeConfig.read(
                pluginFolder = plugin.dataFolder.toPath(),
                settings = settings,
                server = ServerAddress(plugin.server.worldContainer.toPath(), plugin.server.ip)
            ) ?: return null
            val runtime = NetherForgeRuntime(platform(), config)
            return object : ProjectRuntime {
                override fun enable() = runtime.enable()

                override fun tick() = runtime.tick()

                override fun disable() = runtime.disable()

                override fun defaultWorldGenerator(world: String) = runtime.defaultWorldGenerator(world)
            }
        }
    }
}
