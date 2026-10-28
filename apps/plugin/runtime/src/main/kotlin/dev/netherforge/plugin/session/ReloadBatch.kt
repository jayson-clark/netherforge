package dev.netherforge.plugin.session

import dev.netherforge.format.Problem
import dev.netherforge.format.Severity
import dev.netherforge.format.bridge.PackBuild
import dev.netherforge.format.bridge.ReloadedResource
import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.project.PackagePaths
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.plugin.project.Resource

/**
 * One reload of resources: what's left to reload, by kind, what was, and the
 * helpers every kind's handler ([RuntimeService.reload]) reports with. The
 * session takes the kinds in format's registry order (`Kinds.all`), so a
 * handler may [add] resources of a later kind that depend on what it
 * reloaded (a module adds what required it).
 */
class ReloadBatch internal constructor(
    /** The project as it reads now: what each resource reloads onto. */
    val snapshot: ProjectSnapshot,
    private val session: ProjectSession
) {
    private val pending = LinkedHashMap<KindSpec<*, *>, LinkedHashSet<String>>()
    private val done = HashSet<KindSpec<*, *>>()
    private val reloaded = HashSet<Resource>()

    /** Every resource's outcome, in the order they reloaded. */
    val results = mutableListOf<ReloadedResource>()

    /** Adds [resource] to reload in its kind's turn, which mustn't have passed. */
    fun add(resource: Resource) {
        check(resource.kind !in done) { "${resource.key} is reloaded before ${resource.kind.id}s are" }
        pending.getOrPut(resource.kind) { LinkedHashSet() } += resource.id
    }

    /** The ids of [kind] to reload now: its turn. */
    internal fun take(kind: KindSpec<*, *>): Set<String> {
        done += kind
        return pending.remove(kind).orEmpty()
    }

    /** False when [resource] has already reloaded in this batch: one that required another, both ways round, reloads once. */
    fun first(resource: Resource): Boolean = reloaded.add(resource)

    /** Whether [resource]'s main file is one of the project's files now, in whichever group folder it is. */
    fun exists(resource: Resource): Boolean = session.projectFiles.any { Resource.mainOf(it) == resource }

    /** The problems in [resource]'s files, among [among] (the project's as it reads now, by default). */
    fun problemsUnder(resource: Resource, among: List<Problem> = session.projectProblems): List<Problem> =
        among.filter { resource.owns(it.file) }

    /** Runs [block], and answers the script failures it caused, as problems. */
    fun failures(block: () -> Unit): List<Problem> = session.failures.collect(block)

    /**
     * Reloads one resource with a main file: [next] is its new
     * version, null when the file has errors or is gone. [reload] puts it in
     * place (null: the resource was deleted) and returns how much kept running
     * on it. A file with errors leaves the last good version running until
     * it's fixed.
     */
    fun <T : Any> resource(resource: Resource, next: T?, reload: (T?) -> Int) {
        val exists = exists(resource)
        var kept = 0
        val failures = failures {
            kept = when {
                next != null -> reload(next)
                exists -> 0
                else -> reload(null)
            }
        }
        if (next == null && exists) session.log.warn("${resource.key} has errors; it keeps running the last good version")
        report(
            resource,
            ok = (next != null || !exists) && failures.isEmpty(),
            reattached = kept,
            problems =
            problemsUnder(resource) + failures
        )
    }

    /** A resource that's only data scripts read when they use it: nothing runs on it, so nothing restarts. */
    fun data(resource: Resource) {
        val found = problemsUnder(resource)
        report(resource, ok = found.none { it.severity == Severity.ERROR }, problems = found)
    }

    /**
     * Adds [resource]'s outcome, named `{ package, kind, id }` as the bridge
     * names it: the project's resources are its own package's, a
     * dependency's (`library:gem`, as the runtime names it) its.
     */
    fun report(resource: Resource, ok: Boolean, reattached: Int = 0, problems: List<Problem> = emptyList(), pack: PackBuild? = null) {
        val (pkg, id) = PackagePaths.split(resource.id)
        results += ReloadedResource(pkg ?: session.namespace, resource.kind.id, id, ok, reattached, problems, pack)
    }
}

/**
 * Script failures while a reload runs, so the reload can report them for
 * the resource it reloaded. A whole-package reload collects across two
 * sessions (the old one's `unload` handlers, the new one's bodies), so this
 * belongs to the runtime and every session reports into it.
 */
class Failures {
    private var collecting: MutableList<Problem>? = null

    /** A script failed (its body, while its resource loads): kept when a reload is collecting. */
    fun add(problem: Problem) {
        collecting?.add(problem)
    }

    /** Runs [block], answering the failures it caused; an outer [collect] sees them too. */
    fun collect(block: () -> Unit): List<Problem> {
        val outer = collecting
        val mine = mutableListOf<Problem>()
        collecting = mine
        try {
            block()
        } finally {
            collecting = outer
        }
        outer?.addAll(mine)
        return mine
    }
}
