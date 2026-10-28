package dev.netherforge.plugin.paper

import dev.netherforge.plugin.store.DatabaseType
import dev.netherforge.plugin.store.DatabasesConfig
import io.papermc.paper.plugin.loader.PluginClasspathBuilder
import io.papermc.paper.plugin.loader.PluginLoader
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver
import org.bukkit.configuration.file.YamlConfiguration
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.graph.Exclusion
import org.eclipse.aether.repository.RemoteRepository
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * What the plugin needs on its classpath besides its own jar, before it
 * loads (`loader:` in `paper-plugin.yml`).
 *
 * The runtime's store is SQLite through xerial's driver, which every
 * supported Paper bundles: the plugin uses the server's copy and ships none.
 * A server without it (a future Paper that stops bundling it) gets the same
 * driver from Maven Central through Paper's own library resolver, at the
 * version `netherforge-libraries.properties` names (the version catalog's,
 * written in at build time). Remote databases (`nf.db("name")`) need HikariCP
 * and the PostgreSQL driver, which Paper doesn't bundle, so they're fetched
 * the same way, but only when `config.yml`'s `databases:` names a connection
 * that needs them (the pool for any, the driver for a `postgres` one; MySQL's
 * driver is Paper's own). The loader runs before the plugin exists, so it
 * reads the plugin folder's `config.yml` itself, with Bukkit's own YAML
 * (as [NetherForgeBootstrap] does) and the runtime's one reader of the
 * section ([DatabasesConfig.read]). A connection added later needs a restart
 * for its libraries; the runtime says so when one is missing. What to fetch is
 * [Libraries]' decision.
 */
@Suppress("UnstableApiUsage")
class NetherForgeLoader : PluginLoader {
    override fun classloader(classpathBuilder: PluginClasspathBuilder) {
        val coordinates = Libraries.toFetch(javaClass.classLoader, Libraries.remoteTypes(classpathBuilder.context.dataDirectory))
        if (coordinates.isEmpty()) return
        val resolver = MavenLibraryResolver()
        resolver.addRepository(RemoteRepository.Builder("central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build())
        // The pool logs through SLF4J, which the server has: its own copy is the one to use, not a second beside it.
        val slf4j = Exclusion("org.slf4j", "slf4j-api", "*", "*")
        for (coordinate in coordinates) {
            resolver.addDependency(Dependency(DefaultArtifact(coordinate), null, null, listOf(slf4j)))
        }
        classpathBuilder.addLibrary(resolver)
    }
}

/**
 * What [NetherForgeLoader] must fetch, decided without Paper's types so it's
 * tested on its own: each library the plugin needs whose class [toFetch]'s class
 * loader doesn't already reach (the server bundles SQLite's driver), by the
 * coordinates the jar's `netherforge-libraries.properties` names. SQLite's
 * driver is always needed; the pool and PostgreSQL's driver only for the
 * connections `config.yml` names.
 */
internal object Libraries {
    /** xerial's driver, as Paper bundles it. */
    const val SQLITE_DRIVER = "org.sqlite.JDBC"

    const val FILE = "netherforge-libraries.properties"

    /** Each library's key in the properties file, and a class that says it's already there. */
    private val SQLITE = "sqlite-jdbc" to SQLITE_DRIVER
    private val POOL = "hikari" to "com.zaxxer.hikari.HikariDataSource"
    private val POSTGRES = "postgresql" to DatabaseType.POSTGRES.driver

    /** The kinds of server `config.yml`'s `databases:` connects to; none when there's no file or no connection. */
    fun remoteTypes(pluginFolder: Path): Set<DatabaseType> {
        val file = pluginFolder.resolve("config.yml")
        if (!Files.isRegularFile(file)) return emptySet()
        val section = YamlConfiguration.loadConfiguration(file.toFile()).getConfigurationSection("databases")?.plain().orEmpty()
        return DatabasesConfig.read(section).connections.values.mapTo(HashSet()) { it.type }
    }

    /** [remote]: the kinds of server the configuration connects to. */
    fun toFetch(classLoader: ClassLoader, remote: Set<DatabaseType> = emptySet()): List<String> {
        val needed = buildList {
            add(SQLITE)
            if (remote.isNotEmpty()) add(POOL)
            if (DatabaseType.POSTGRES in remote) add(POSTGRES)
        }
        val missing = needed.filterNot { (_, className) -> has(classLoader, className) }
        if (missing.isEmpty()) return emptyList()
        val libraries = Properties().apply {
            classLoader.getResourceAsStream(FILE)?.use(::load) ?: error("$FILE is missing from the plugin jar")
        }
        return missing.map { (key, _) -> libraries.getProperty(key) ?: error("$FILE names no $key") }
    }

    private fun has(classLoader: ClassLoader, className: String): Boolean = try {
        Class.forName(className, false, classLoader)
        true
    } catch (_: ClassNotFoundException) {
        false
    }
}
