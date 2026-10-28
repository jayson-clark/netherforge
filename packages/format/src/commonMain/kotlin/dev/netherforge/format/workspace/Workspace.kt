package dev.netherforge.format.workspace

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.DocumentKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `netherforge-workspace.json`: several projects kept together, at the top of a folder that holds
 * them (a server network's repository: a project per server, packages they share). It's not a
 * project: it names them, and the dev network the editor runs from them. See
 * `docs/format/workspace.md`.
 */
@Serializable
data class WorkspaceFile(
    @SerialName("\$schema") val schema: String? = null,
    /** What the workspace is called, as the editor shows it. */
    val name: String,
    /**
     * Its projects and packages: folders relative to this file (`servers/hub`), or every project
     * directly in a folder (`servers/` and a `*`, [isPattern]). In the order the editor lists them.
     */
    val projects: List<String>,
    /** The dev network the editor runs: which project each server runs, and where players arrive. */
    val network: WorkspaceNetwork? = null
) {
    companion object {
        const val FILE_NAME = "netherforge-workspace.json"
        const val SCHEMA = ".netherforge/schema/workspace.schema.json"
    }
}

/** A workspace's dev network: its servers by name, as the proxy names them, and the one players arrive on. */
@Serializable
data class WorkspaceNetwork(
    /** The server players arrive on when they join: one of [servers]. */
    val arrival: String,
    /** Each server's name in the network → the project it runs (one of the workspace's, as [WorkspaceFile.projects] names it). */
    val servers: Map<String, String>
)

/** `netherforge-workspace.json`. */
object WorkspaceKind : DocumentKind<WorkspaceFile> {
    override val id = "workspace"
    override val serializer = WorkspaceFile.serializer()
    override val schemaRef = WorkspaceFile.SCHEMA

    // A project listed twice is listed once, where it first was: the order is the editor's list.
    override fun canonical(value: WorkspaceFile) = value.copy(
        schema = schemaRef,
        projects = value.projects.map(Workspaces::normalize).distinct()
    )
}

/** A workspace file as checked: its problems, and the project folders it names, expanded. */
data class WorkspaceCheck(val members: List<String>, val problems: List<dev.netherforge.format.Problem>)

object Workspaces {
    /** A server's name in a dev network: a folder name and a `velocity.toml` key, so narrower than a network's names. */
    private val SERVER_NAME = Regex("[a-z0-9_-]{1,32}")

    /** One folder name: no dots alone, no separators, nothing Windows refuses. */
    private val SEGMENT = Regex("[A-Za-z0-9_][A-Za-z0-9_. -]*")

    fun isServerName(name: String) = SERVER_NAME.matches(name)

    /** [pattern] with no trailing `/` and no `./` in front, as it's compared. */
    fun normalize(pattern: String): String = pattern.trim().removePrefix("./").trimEnd('/')

    /**
     * Whether [pattern] can name projects: a relative folder (`servers/hub`), or one followed by
     * `/` and a `*` for every project directly in it. Never `..`, absolute, or a backslash: a workspace
     * holds what's under it.
     */
    fun isPattern(pattern: String): Boolean {
        val parts = normalize(pattern).split('/')
        if (parts.isEmpty() || parts == listOf("")) return false
        val folders = if (parts.last() == "*") parts.dropLast(1) else parts
        if (folders.isEmpty() && parts.last() == "*") return true
        return folders.all { SEGMENT.matches(it) && !it.endsWith('.') && !it.endsWith(' ') }
    }

    /**
     * The project folders [patterns] name, in order, from [projects]: every folder under the
     * workspace (relative, `/`-separated) that holds a `netherforge.json`. A plain folder is named
     * whether it's a project or not (the check says so); a folder and `*` names the projects directly in it.
     */
    fun expand(patterns: List<String>, projects: Collection<String>): List<String> {
        val found = LinkedHashSet<String>()
        for (raw in patterns) {
            val pattern = normalize(raw)
            if (!isPattern(pattern)) continue
            if (pattern == "*" || pattern.endsWith("/*")) {
                val parent = pattern.removeSuffix("*").removeSuffix("/")
                projects.filter { folder ->
                    if (parent.isEmpty()) '/' !in folder else folder.startsWith("$parent/") && '/' !in folder.removePrefix("$parent/")
                }.sorted().forEach(found::add)
            } else {
                found += pattern
            }
        }
        return found.toList()
    }

    /**
     * Checks [workspace] against [projects] (as [expand] takes them) and answers its members: the
     * folders it names that are projects.
     */
    fun check(workspace: WorkspaceFile, projects: Collection<String>): WorkspaceCheck {
        val sink = ProblemSink(WorkspaceFile.FILE_NAME)
        if (workspace.name.isBlank()) sink.report(ProblemCodes.WORKSPACE_NAME, "The workspace has no name.", "$.name")
        val members = ArrayList<String>()
        for ((index, raw) in workspace.projects.withIndex()) {
            val path = "$.projects[$index]"
            val pattern = normalize(raw)
            when {
                !isPattern(pattern) -> sink.report(
                    ProblemCodes.WORKSPACE_PROJECT_PATH,
                    "\"$raw\" isn't a folder in the workspace: a relative path like servers/hub, or servers/* for every project in a folder.",
                    path
                )
                pattern == "*" || pattern.endsWith("/*") -> {
                    val named = expand(listOf(pattern), projects)
                    if (named.isEmpty()) {
                        sink.report(
                            ProblemCodes.WORKSPACE_EMPTY_PATTERN,
                            "No project is directly in ${pattern.removeSuffix("/*")}.",
                            path
                        )
                    }
                    members += named
                }
                pattern !in projects -> sink.report(
                    ProblemCodes.WORKSPACE_MISSING_PROJECT,
                    "$pattern isn't a project: it has no netherforge.json.",
                    path
                )
                else -> members += pattern
            }
        }
        val distinct = members.distinct()
        workspace.network?.let { network ->
            for ((name, project) in network.servers) {
                val path = CanonicalJson.childPath("$.network.servers", name)
                if (!isServerName(name)) {
                    sink.report(
                        ProblemCodes.WORKSPACE_SERVER_NAME,
                        "\"$name\" can't name a server: lowercase letters, digits, _ and - (at most 32).",
                        path
                    )
                }
                if (normalize(project) !in distinct) {
                    sink.report(
                        ProblemCodes.WORKSPACE_SERVER_PROJECT,
                        "Server \"$name\" runs $project, which isn't one of the workspace's projects.",
                        path
                    )
                }
            }
            if (network.arrival !in network.servers) {
                sink.report(
                    ProblemCodes.WORKSPACE_ARRIVAL,
                    "Players arrive on \"${network.arrival}\", which isn't one of the network's servers.",
                    "$.network.arrival"
                )
            }
        }
        return WorkspaceCheck(distinct, sink.problems)
    }
}
