package dev.netherforge.format.project

import kotlinx.serialization.Serializable

/**
 * Something a package's scripts can do only when its `netherforge.json`
 * declares it (`"requires"`, [ProjectRequires]): the capabilities "Trust" in
 * PLAN.md's decisions is about. Its [id] is how the API spec's `requires`
 * names it (`moderation`, `db`, `web`, `bots`, `wasm`, `network`, `http`, `plugin:vault`) and how the editor,
 * the server's log and `/nf requires` show it (`http:discord.com`).
 *
 * The one place a declaration is read: the runtime's check
 * (`Requirements.check`), the combined list the editor and the server show
 * ([combined]) and the messages saying what to declare ([declaration]) all
 * come from here. `packages/api/src/requirements.ts` mirrors [grantedBy] for
 * the editor's LuaLS stubs.
 */
sealed class Requirement {
    /** `moderation`, `db`, `http`, `http:discord.com`, `plugin:vault`. */
    abstract val id: String

    /** How a package declares it, as messages say it: `"requires": { "http": ["discord.com"] }`. */
    abstract val declaration: String

    /** Whether [requires] (a package's `requires`) declares it. */
    abstract fun grantedBy(requires: ProjectRequires?): Boolean

    override fun toString() = id

    /** Banning players, the whitelist, the message of the day and the most players allowed. */
    data object Moderation : Requirement() {
        override val id = "moderation"
        override val declaration = "\"requires\": { \"moderation\": true }"

        override fun grantedBy(requires: ProjectRequires?) = requires?.moderation == true
    }

    /** A database of the package's own (`nf.db`). */
    data object Db : Requirement() {
        override val id = "db"
        override val declaration = "\"requires\": { \"db\": true }"

        override fun grantedBy(requires: ProjectRequires?) = requires?.db == true
    }

    /** Answering web requests to the server under the package's own path (`nf.web`). */
    data object Web : Requirement() {
        override val id = "web"
        override val declaration = "\"requires\": { \"web\": true }"

        override fun grantedBy(requires: ProjectRequires?) = requires?.web == true
    }

    /** Joining fake players to the server and driving them (`nf.bots`), when the server owner allows bots. */
    data object Bots : Requirement() {
        override val id = "bots"
        override val declaration = "\"requires\": { \"bots\": true }"

        override fun grantedBy(requires: ProjectRequires?) = requires?.bots == true
    }

    /**
     * Running the package's WebAssembly modules (`nf.wasm`): heavy computation off the main thread, within the
     * server owner's limits on threads, memory and time.
     */
    data object Wasm : Requirement() {
        override val id = "wasm"
        override val declaration = "\"requires\": { \"wasm\": true }"

        override fun grantedBy(requires: ProjectRequires?) = requires?.wasm == true
    }

    /**
     * Talking to the other servers of the network the server is part of (`nf.network`): messages, requests, shared
     * maps, who's online where, and sending players to another server.
     */
    data object Network : Requirement() {
        override val id = "network"
        override val declaration = "\"requires\": { \"network\": true }"

        override fun grantedBy(requires: ProjectRequires?) = requires?.network == true
    }

    /**
     * HTTP requests to [host] (lowercase), or, with none, to any host the
     * package declares: what the spec's `requires: "http"` checks before the
     * function knows its URL. A request then checks its own host.
     */
    data class Http(val host: String? = null) : Requirement() {
        override val id = if (host == null) "http" else "http:$host"
        override val declaration = "\"requires\": { \"http\": [\"${host ?: "<host>"}\"] }"

        override fun grantedBy(requires: ProjectRequires?): Boolean {
            val hosts = requires?.http.orEmpty()
            return if (host == null) hosts.isNotEmpty() else hosts.any { allows(it, host) }
        }
    }

    /** Another plugin on the server, by its name in lowercase (`vault`). */
    data class Plugin(val name: String) : Requirement() {
        override val id = "plugin:$name"
        override val declaration = "\"requires\": { \"plugins\": [\"$name\"] }"

        override fun grantedBy(requires: ProjectRequires?) = name in requires?.plugins.orEmpty()
    }

    companion object {
        /** [id] as a requirement (`plugin:vault`), or null for text that isn't one. */
        fun parse(id: String): Requirement? = when {
            id == Moderation.id -> Moderation
            id == Db.id -> Db
            id == Web.id -> Web
            id == Bots.id -> Bots
            id == Wasm.id -> Wasm
            id == Network.id -> Network
            id == "http" -> Http()
            id.startsWith("http:") -> id.removePrefix("http:").takeIf(Names::isHostName)?.let(::Http)
            id.startsWith("plugin:") -> id.removePrefix("plugin:").takeIf(Names::isPluginName)?.let(::Plugin)
            else -> null
        }

        /**
         * Whether [pattern] (one of `requires.http`) allows a request to
         * [host]: the same name, or for `*.example.com` any name under
         * `example.com` (not `example.com` itself). Host names are compared
         * in lowercase, as DNS does.
         */
        fun allows(pattern: String, host: String): Boolean {
            val name = host.lowercase().removeSuffix(".")
            return if (pattern.startsWith("*.")) name.endsWith(pattern.substring(1)) else name == pattern
        }

        /**
         * What [requires] declares, one requirement each, in the order they're
         * shown: moderation, db, web, bots, wasm, network, each host, each plugin. A host or plugin name
         * that isn't one (a problem in the manifest) declares nothing.
         */
        fun declared(requires: ProjectRequires?): List<Requirement> = buildList {
            if (requires == null) return@buildList
            if (requires.moderation == true) add(Moderation)
            if (requires.db == true) add(Db)
            if (requires.web == true) add(Web)
            if (requires.bots == true) add(Bots)
            if (requires.wasm == true) add(Wasm)
            if (requires.network == true) add(Network)
            requires.http.orEmpty().filter(Names::isHttpHost).distinct().sorted().forEach { add(Http(it)) }
            requires.plugins.orEmpty().filter(Names::isPluginName).distinct().sorted().forEach { add(Plugin(it)) }
        }

        /**
         * Every requirement declared across [snapshot]'s whole tree (the
         * project and every package it depends on, theirs included), each
         * with the packages that declare it: what whoever runs the server
         * agrees to by running the project. In [declared]'s order; packages
         * by namespace, the project's own first.
         */
        fun combined(snapshot: ProjectSnapshot): List<RequirementUse> {
            val namespaces = listOf(snapshot.namespace) + snapshot.packages.keys.sorted()
            val by = LinkedHashMap<Requirement, MutableList<String>>()
            for (namespace in namespaces) {
                for (requirement in declared(snapshot.manifestOf(namespace)?.requires)) {
                    by.getOrPut(requirement, ::ArrayList) += namespace
                }
            }
            val order = { r: Requirement ->
                when (r) {
                    Moderation -> 0
                    Db -> 1
                    Web -> 2
                    Bots -> 3
                    Wasm -> 4
                    Network -> 5
                    is Http -> 6
                    is Plugin -> 7
                }
            }
            return by.entries.sortedWith(compareBy({ order(it.key) }, { it.key.id })).map { (requirement, packages) ->
                RequirementUse(requirement.id, packages)
            }
        }
    }
}

/** One requirement across a project's tree ([Requirement.combined]): its [Requirement.id] and the namespaces that declare it. */
@Serializable
data class RequirementUse(val requirement: String, val packages: List<String>)
