package dev.netherforge.plugin.store
import dev.netherforge.format.project.Names

/**
 * `databases:` in `config.yml`, read on dev servers too: the MySQL and
 * PostgreSQL servers the owner lets scripts reach, each under a name
 * (`nf.db("network")`). A script names a connection and never sees the host
 * or the password: they live here and nowhere a script, a project file or the
 * editor can read them.
 *
 * A connection that can't be used (a type that isn't one, a missing
 * database name, a host that isn't a host name) isn't dropped silently: it's in
 * [invalid] with the reason, which the server logs and a script that asks for
 * it is told (without the values).
 */
data class DatabasesConfig(
    val connections: Map<String, ConnectionConfig> = emptyMap(),
    /** The name of every connection that couldn't be read, and why. */
    val invalid: Map<String, String> = emptyMap()
) {
    companion object {
        /** [section] is the `databases:` map as the adapter read it (see [RuntimeConfig.read]). */
        fun read(section: Map<String, Any?>): DatabasesConfig {
            val connections = LinkedHashMap<String, ConnectionConfig>()
            val invalid = LinkedHashMap<String, String>()
            for ((name, value) in section) {
                if (!Names.isId(name)) {
                    invalid[name] = "a connection's name is ${Names.ID_RULE}"
                    continue
                }
                @Suppress("UNCHECKED_CAST")
                val fields = value as? Map<String, Any?>
                if (fields == null) {
                    invalid[name] = "it isn't a section (type, host, database, username, password and packages go under it)"
                    continue
                }
                when (val read = connection(fields)) {
                    is Read.Ok -> connections[name] = read.connection
                    is Read.Bad -> invalid[name] = read.why
                }
            }
            return DatabasesConfig(connections, invalid)
        }

        private sealed interface Read {
            data class Ok(val connection: ConnectionConfig) : Read

            data class Bad(val why: String) : Read
        }

        private fun connection(fields: Map<String, Any?>): Read {
            val typeName = fields.text("type") ?: return Read.Bad("`type` is missing: mysql or postgres")
            val type = DatabaseType.entries.firstOrNull { it.id == typeName.lowercase() }
                ?: return Read.Bad("`type` is \"$typeName\", which isn't mysql or postgres")
            val host = fields.text("host") ?: "localhost"
            if (!HOST.matches(host)) return Read.Bad("`host` isn't a host name or address")
            val port = when (val raw = fields["port"]) {
                null -> type.defaultPort
                is Number -> raw.toInt()
                is String -> raw.trim().toIntOrNull()
                else -> null
            }
            if (port == null || port !in 1..65535) return Read.Bad("`port` isn't a port number")
            val database = fields.text("database") ?: return Read.Bad("`database` is missing: the name of the database on the server")
            if (!DATABASE.matches(
                    database
                )
            ) {
                return Read.Bad("`database` has characters a database name doesn't (letters, digits, _, $, - and .)")
            }
            val poolSize = when (val raw = fields["pool-size"]) {
                null -> DEFAULT_POOL_SIZE
                is Number -> raw.toInt()
                is String -> raw.trim().toIntOrNull() ?: return Read.Bad("`pool-size` isn't a number")
                else -> return Read.Bad("`pool-size` isn't a number")
            }.coerceIn(1, MAX_POOL_SIZE)
            val packages = when (val raw = fields["packages"]) {
                null -> emptySet()
                is List<*> -> raw.map { it?.toString().orEmpty() }.toSet()
                else -> return Read.Bad("`packages` isn't a list of package namespaces")
            }
            packages.firstOrNull {
                !Names.isNamespace(it)
            }?.let { return Read.Bad("`packages` has \"$it\", which isn't a package's namespace") }
            val properties = when (val raw = fields["properties"]) {
                null -> emptyMap()
                is Map<*, *> -> raw.entries.associate { (key, value) -> key.toString() to value.toString() }
                else -> return Read.Bad("`properties` isn't a section of driver properties")
            }
            return Read.Ok(
                ConnectionConfig(
                    type,
                    host,
                    port,
                    database,
                    username = fields.text("username").orEmpty(),
                    password = fields["password"]?.toString().orEmpty(),
                    poolSize,
                    packages,
                    properties
                )
            )
        }

        private fun Map<String, Any?>.text(key: String): String? = this[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        /** What may stand in a URL's host and path without being able to add driver settings to it (`?`, `&`, `;`, `/`, `@`). */
        private val HOST = Regex("^[A-Za-z0-9._\\-]{1,253}$|^\\[[0-9A-Fa-f:.]{2,45}]$")
        private val DATABASE = Regex("^[A-Za-z0-9_$.\\-]{1,128}$")

        const val DEFAULT_POOL_SIZE = 4
        const val MAX_POOL_SIZE = 32
    }
}

/** The server types a connection can be. */
enum class DatabaseType(val id: String, val defaultPort: Int, val driver: String, val scheme: String) {
    MYSQL("mysql", 3306, "com.mysql.cj.jdbc.Driver", "mysql"),
    POSTGRES("postgres", 5432, "org.postgresql.Driver", "postgresql")
}

/**
 * One `databases:` entry. [packages] are the namespaces of the packages
 * whose scripts may open it: the owner's own list, apart from the packages'
 * `requires.db`, so a package can't give itself a database it wasn't granted.
 * [properties] are extra driver properties (`sslMode: REQUIRED`).
 */
data class ConnectionConfig(
    val type: DatabaseType,
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    val password: String,
    val poolSize: Int = DatabasesConfig.DEFAULT_POOL_SIZE,
    val packages: Set<String> = emptySet(),
    val properties: Map<String, String> = emptyMap()
) {
    /** The JDBC URL. [read] only lets through a host and database name that can't add settings to it. */
    val jdbcUrl: String get() = "jdbc:${type.scheme}://$host:$port/$database"

    /** The password is never in a log line or a stack trace. */
    override fun toString() = "${type.id} connection to $host:$port/$database as $username"
}
