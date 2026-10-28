package dev.netherforge.plugin.session

import dev.netherforge.format.project.ProjectManifest
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.format.project.Requirement
import dev.netherforge.format.project.RequirementUse
import dev.netherforge.plugin.RuntimeLog
import dev.netherforge.plugin.lua.LuaApiException

/**
 * What each package has declared its scripts may do (`"requires"` in its
 * `netherforge.json`, format's [Requirement]), and the one check every
 * capability goes through: [check].
 *
 * **A call is held to the package whose code makes it** ([calling], the
 * session's `PackageNames.calling`), never to the project that depends on
 * it: a library's script needs `moderation` in the library's own manifest.
 * Refused, the call fails with a [LuaApiException] naming the call, the
 * requirement, the package and what to add to its manifest. When a Lua tail
 * call hid the code that made the call (`return player:ban()`, see
 * `PackageNames.callingHidden`), it could be any package's, so the call
 * passes only if every package in the tree declares the requirement.
 *
 * **Who calls it.** The generated primitive of every function whose spec has
 * `requires` (`moderation`, `http`, `db`, `plugin:<name>`) calls
 * `Marshal.requires` before it reads its arguments, which lands here. A
 * function that knows more than its spec can say checks the narrower
 * requirement itself, at the point it knows it: `nf.http.request` checks
 * `Requirement.Http(host)` for its URL's host (and each redirect's), so the
 * spec's `requires: "http"` only says the package declared some host.
 *
 * **Whoever runs the server sees the sum** ([combined]): the tree's
 * requirements, each with the packages that declare it, logged when the
 * project starts and listed by `/nf requires`. A manifest change restarts the
 * session, so this is read once per session.
 */
class Requirements(
    private val log: RuntimeLog,
    private val snapshot: () -> ProjectSnapshot,
    private val calling: () -> String,
    private val callingHidden: () -> Boolean
) : RuntimeService {
    override val name = "requirements"

    private var combined: List<RequirementUse> = emptyList()

    /** Every requirement across the project's tree, with who declares it. */
    fun combined(): List<RequirementUse> = combined

    override fun define(project: SessionProject) {
        combined = Requirement.combined(project.snapshot)
    }

    override fun start() {
        log.info(
            if (combined.isEmpty()) {
                "Requires nothing beyond what every project may do"
            } else {
                "Requires (by its packages' netherforge.json): " + combined.joinToString("; ") { use -> describe(use) }
            }
        )
    }

    /**
     * Refuses [what] (`Player:ban`, `nf.http.request`) unless the package
     * whose code is running has declared [requirement]: the one check every
     * capability goes through.
     */
    fun check(requirement: Requirement, what: String) = check(requirement, what, calling(), callingHidden(), snapshot())

    /**
     * [check] as the code running now would make it, for a thread that isn't
     * the script's (the one the calling package is known on): `nf.http.request`
     * checks a redirect's host on its worker.
     */
    fun held(what: String): (Requirement) -> Unit {
        val namespace = calling()
        val hidden = callingHidden()
        val snapshot = snapshot()
        return { requirement -> check(requirement, what, namespace, hidden, snapshot) }
    }

    private fun check(requirement: Requirement, what: String, namespace: String, hidden: Boolean, snapshot: ProjectSnapshot) {
        if (hidden) {
            everyoneOrRefuse(what, requirement.id, snapshot) { requirement.grantedBy(it?.requires) }
            return
        }
        if (requirement.grantedBy(snapshot.manifestOf(namespace)?.requires)) return
        throw LuaApiException(
            "$what needs ${requirement.id}, which package \"$namespace\" hasn't declared: " +
                "add ${requirement.declaration} to its ${ProjectManifest.FILE_NAME}"
        )
    }

    /**
     * Refuses [what] (`Player:set_permission`) unless the package whose code
     * is running lists [node] (or a node above it) under `allow.permissions`
     * in its own `netherforge.json`: the same model as [check], with the
     * grant in `allow` rather than `requires`.
     */
    fun checkPermission(node: String, what: String) {
        val snapshot = snapshot()
        if (callingHidden()) {
            everyoneOrRefuse(what, "permission \"$node\"", snapshot) { it?.allow?.allowsPermission(node) == true }
            return
        }
        val namespace = calling()
        val allow = snapshot.manifestOf(namespace)?.allow
        if (allow?.allowsPermission(node) == true) return
        val listed = allow?.permissions.orEmpty().sorted()
        throw LuaApiException(
            "$what needs permission \"$node\", which package \"$namespace\" hasn't allowed: " +
                "add it (or a node above it) to allow.permissions in its ${ProjectManifest.FILE_NAME}" +
                if (listed.isEmpty()) " (it lists none)" else " (it lists: ${listed.joinToString()})"
        )
    }

    /** What a tail call (the calling package is unknown) needs: every package in the tree grants [what]'s need. */
    private fun everyoneOrRefuse(what: String, need: String, snapshot: ProjectSnapshot, granted: (ProjectManifest?) -> Boolean) {
        val everyone = listOf(snapshot.namespace) + snapshot.packages.keys
        if (everyone.all { granted(snapshot.manifestOf(it)) }) return
        throw LuaApiException(
            "$what was called as a tail call (`return` and the call), so NetherForge can't tell which package's code " +
                "called it, and not every package declares $need: call it on a line of its own, or wrap it in parentheses"
        )
    }

    /** [check] for a requirement as the spec writes it (`moderation`, `plugin:vault`): what the generated bindings call. */
    fun check(requirement: String, what: String) =
        check(Requirement.parse(requirement) ?: error("\"$requirement\" isn't a requirement (the spec's requires)"), what)

    companion object {
        /** One requirement as the log and `/nf requires` say it: `http:discord.com (library, chat)`. */
        fun describe(use: RequirementUse) = "${use.requirement} (${use.packages.joinToString()})"
    }
}
