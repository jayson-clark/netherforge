package dev.netherforge.format.project

import dev.netherforge.format.Location
import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.json.CanonicalJson

/**
 * Where a dependency is to be found, as format asks a host for it: a
 * [Folder] beside the project, or a commit of a [Git] repository.
 */
sealed interface PackageRequest {
    /** A folder, [location] being relative to the root project's own, `..` resolved as far as it goes (`../economy`). */
    data class Folder(val location: String) : PackageRequest

    /**
     * A git repository at [url]: exactly [commit] when `netherforge.lock`
     * pins one (no network needed once it's in the cache), otherwise
     * whatever [rev] (a branch, tag or full commit; null for the default
     * branch) is now. The host answers with the commit it opened
     * ([OpenedPackage.commit]), whose files are the package: never a hook,
     * filter, submodule or link of the repository's.
     */
    data class Git(val url: String, val rev: String?, val commit: String?) : PackageRequest {
        /** One string per request, for hosts that answer requests by name (the JS exports). */
        val key: String get() = "$url ${rev ?: "-"} ${commit ?: "-"}"
    }
}

/** What a host found for a [PackageRequest]. */
sealed interface PackageLookup

/**
 * A package a host found: its files, their [PackageHash] when the host
 * computes one, and for a git package the [commit] it is (a bundle, which
 * answers by namespace, may not know).
 */
class OpenedPackage(val source: ProjectSource, val hash: String? = null, val commit: String? = null) : PackageLookup

/** Nothing there: no project at a folder ([reason] null), or a repository that couldn't be fetched ([reason] says why). */
class PackageMissing(val reason: String? = null) : PackageLookup

/**
 * Where a project's packages come from, for [Projects.load]: the folders its
 * dependencies' paths name and the git repositories they fetch from (the
 * editor, the CLI, a dev server from the cache), or the folders of a bundle
 * (a production server).
 */
interface PackageSources {
    /** The package a dependency named [namespace] asks for with [request]. */
    fun open(namespace: String, request: PackageRequest): PackageLookup

    /** Whether the project's `netherforge.lock` is checked against what resolves: a bundle has none. */
    val checksLock: Boolean get() = true

    companion object {
        /** Nowhere: every dependency is missing (a project loaded on its own, for a question about it alone). */
        val NONE: PackageSources = object : PackageSources {
            override fun open(namespace: String, request: PackageRequest): PackageLookup = PackageMissing()
        }
    }
}

/** One package the dependency tree resolved to. */
class ResolvedPackage internal constructor(
    val namespace: String,
    /**
     * Where its files are: a folder relative to the root project's
     * (`../economy`), or `git:<commit>` for a git package's checkout in the
     * cache ([Packages.gitLocation]).
     */
    val location: String,
    /** Where it came from, as `netherforge.lock` records it. */
    val origin: PackageSource,
    /** Its `netherforge.json`, or null when that doesn't read (its own problems say why). */
    val manifest: ProjectManifest?,
    val source: ProjectSource,
    /** Its [PackageHash], when the host computed one. */
    val hash: String?,
    /** The namespaces of what depends on it (the project's among them), in the order they were found. */
    val requiredBy: List<String>
)

/** What a project's dependencies resolved to: every package in the tree by namespace, and what's wrong with it. */
class Resolution internal constructor(val packages: Map<String, ResolvedPackage>, val problems: List<Problem>) {
    /**
     * The lock this resolution makes: each package's version, source and
     * hash. Null while one has no version or no hash to pin it by, or a git
     * package isn't what the lock pinned ([ProblemCodes.PACKAGE_HASH]: a
     * pin is never written over).
     */
    fun lock(): LockFile? = lockOf(packages.values, problems)

    companion object {
        internal val NONE = Resolution(emptyMap(), emptyList())

        internal fun lockOf(packages: Collection<ResolvedPackage>, problems: List<Problem>): LockFile? {
            if (problems.any { it.code == ProblemCodes.PACKAGE_HASH.code }) return null
            val locked = packages.associate {
                it.namespace to LockedPackage(it.manifest?.version ?: return null, it.origin, it.hash ?: return null)
            }
            return LockFile(LockFile.SCHEMA, locked)
        }
    }
}

/**
 * Resolving a project's dependencies: one package per namespace across the
 * whole tree, each found where its requirer's `netherforge.json` says (a
 * path relative to the requirer's folder, or a git repository), and what's
 * wrong on the way (a missing package, two packages with one namespace, a
 * loop, a git package that isn't what the lock pinned).
 */
object Packages {
    /** What starts a git package's [ResolvedPackage.location]: `git:<commit>`. */
    const val GIT_LOCATION_PREFIX = "git:"

    /** Where a git package's files are, as a location: `git:<commit>`, its checkout in the cache. */
    fun gitLocation(commit: String) = GIT_LOCATION_PREFIX + commit

    /** The commit a `git:<commit>` location names, or null for a folder's location (or one that isn't a commit). */
    fun commitOf(location: String): String? =
        location.removePrefix(GIT_LOCATION_PREFIX).takeIf { location.startsWith(GIT_LOCATION_PREFIX) && isCommit(it) }

    /**
     * Where a git package's checkout at [commit] is, relative to the package
     * cache (the editor's `<data>/packages`): `git/checkouts/<commit>`, a
     * folder of the commit's regular files and nothing else. The editor and
     * the CLI write it (fetching into `git/db/`, which is theirs), a dev
     * server reads it; it's checked against the lock's hash on every load.
     */
    fun gitCheckout(commit: String): String {
        require(isCommit(commit)) { "\"$commit\" isn't a commit" }
        return "git/checkouts/$commit"
    }

    private val COMMIT = Regex("^(?:[0-9a-f]{40}|[0-9a-f]{64})$")

    /** Whether [text] is a full commit id: 40 lowercase hex digits (SHA-1), or 64 (SHA-256). */
    fun isCommit(text: String): Boolean = COMMIT.matches(text)

    private val SCP_LIKE = Regex("^[A-Za-z0-9._-]+@[A-Za-z0-9.-]+:[^/].*$")

    /**
     * Whether [url] is a repository NetherForge fetches from: `https://`,
     * `ssh://`, `file://`, or scp-like `user@host:path`
     * (`git@github.com:acme/economy.git`). Never plain `http://` or `git://`
     * (anyone on the way could swap the code), git's `ext::` and other
     * transports that run commands, or anything git could read as an option
     * (a leading `-`).
     */
    fun isGitUrl(url: String): Boolean {
        if (url.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f } || url.startsWith("-")) return false
        val scheme = listOf("https://", "ssh://", "file://").firstOrNull { url.startsWith(it) }
        return if (scheme != null) url.length > scheme.length else SCP_LIKE.matches(url)
    }

    private val REV = Regex("^[A-Za-z0-9_][A-Za-z0-9_./+-]*$")

    /**
     * Whether [rev] can be a git dependency's `rev`: a branch or tag name
     * (`main`, `v1.2.0`, `release/1.x`) or a full commit. Names git refuses
     * (`..`, `//`, a part starting with `.`, a trailing `/`, `.` or `.lock`)
     * and anything git could read as an option or a refspec (a leading `-`,
     * a `:`) aren't.
     */
    fun isGitRev(rev: String): Boolean = REV.matches(rev) &&
        ".." !in rev &&
        "//" !in rev &&
        !rev.endsWith("/") &&
        !rev.endsWith(".") &&
        !rev.endsWith(".lock") &&
        rev.split('/').none { it.startsWith(".") }

    /**
     * Every package [root] (the project's manifest) depends on, its
     * dependencies' dependencies included, from [sources]. A git dependency
     * is asked for at the commit [lock] (the project's `netherforge.lock`)
     * pins for it while the lock says the same `git` and `rev`, and must
     * then hash to what the lock says. Problems are where each dependency is
     * declared (`netherforge.json`'s `$.dependencies.<name>`, in a package
     * at its package path).
     */
    fun resolve(root: ProjectManifest, sources: PackageSources, lock: LockFile? = null): Resolution {
        val problems = mutableListOf<Problem>()
        val resolved = LinkedHashMap<String, ResolvedPackage>()
        val requirers = HashMap<String, MutableList<String>>()
        val home = root.namespace
        // The packages whose dependencies are still to follow: (namespace, location, manifest), the project first.
        val queue = ArrayDeque(listOf(Triple(home, "", root)))
        while (queue.isNotEmpty()) {
            val (requirer, base, manifest) = queue.removeFirst()
            val file = manifestPath(requirer, home)
            for ((name, dependency) in manifest.dependencies.orEmpty().entries.sortedBy { it.key }) {
                val at = CanonicalJson.childPath("$.dependencies", name)
                val badName = when {
                    name == requirer -> "is this project's own namespace"
                    !Names.isNamespace(name) -> "isn't a namespace (${Names.NAMESPACE_RULE})"
                    name in Names.RESERVED_NAMESPACES -> "is reserved (for the game, the server or NetherForge), so no package has it"
                    else -> null
                }
                if (badName != null) {
                    problems += ProblemCodes.PACKAGE_NAME.at(file, "\"$name\" $badName", at)
                    continue
                }
                val request = request(dependency, base, lock?.packages?.get(name), file, at, problems) ?: continue
                if (name == home) {
                    // A package depending on the project being loaded: it's that project (a loop), or another of its namespace.
                    problems += ProblemCodes.PACKAGE_CYCLE.at(
                        file,
                        "\"$requirer\" depends on \"$home\", the project that uses it: a package can't depend on what depends on it",
                        at
                    )
                    continue
                }
                val existing = resolved[name]
                if (existing != null) {
                    if (sameRequest(existing.origin, request)) {
                        requirers.getValue(name) += requirer
                    } else {
                        val them = existing.requiredBy.joinToString(" and ") { "\"$it\"" }
                        problems += ProblemCodes.PACKAGE_CONFLICT.at(
                            file,
                            "Two packages are called \"$name\": ${describe(
                                request
                            )} (${versionOf(sources, name, request)}which \"$requirer\" depends on) " +
                                "and ${describe(
                                    existing.origin
                                )} (${existing.manifest?.version ?: "unreadable"}, which $them depends on). " +
                                "A server runs one package of each namespace.",
                            at,
                            existing.requiredBy.map { Location(manifestPath(it, home), CanonicalJson.childPath("$.dependencies", name)) }
                        )
                    }
                    continue
                }
                val opened = when (val lookup = sources.open(name, request)) {
                    is OpenedPackage -> lookup
                    is PackageMissing -> {
                        problems += when (request) {
                            is PackageRequest.Folder -> ProblemCodes.PACKAGE_MISSING.at(
                                file,
                                lookup.reason ?: "There's no project at ${request.location} (no ${ProjectManifest.FILE_NAME} there)",
                                at
                            )
                            is PackageRequest.Git -> ProblemCodes.PACKAGE_GIT.at(
                                file,
                                "Couldn't fetch ${describe(request)}: ${lookup.reason ?: "nothing answered for it"}",
                                at
                            )
                        }
                        continue
                    }
                }
                val (location, origin) = when (request) {
                    is PackageRequest.Folder -> request.location to PackageSource.Path(request.location)
                    is PackageRequest.Git -> {
                        // A bundle answers by namespace and may not know the commit; it never locks.
                        val commit = opened.commit ?: request.commit.orEmpty()
                        gitLocation(commit) to PackageSource.Git(request.url, request.rev, commit)
                    }
                }
                val pinned = lock?.packages?.get(name)?.hash
                if (request is PackageRequest.Git &&
                    request.commit != null &&
                    pinned != null &&
                    opened.hash != null &&
                    opened.hash != pinned
                ) {
                    problems += ProblemCodes.PACKAGE_HASH.at(
                        file,
                        "${describe(origin)} hashes to ${opened.hash}, not the $pinned ${LockFile.FILE_NAME} pins, so it isn't loaded: " +
                            "either its copy in the cache was changed (delete it, and it's fetched again) or the lock was " +
                            "(netherforge lock --update resolves it afresh)",
                        at
                    )
                    continue
                }
                val found = opened.source.read(ProjectManifest.FILE_NAME)?.let(::parseManifest)
                if (found != null && found.namespace != name) {
                    problems += ProblemCodes.PACKAGE_NAMESPACE.at(
                        file,
                        "The project at ${describe(origin)} is \"${found.namespace}\", not \"$name\": " +
                            "a dependency is named by the namespace of what it points at",
                        at
                    )
                    continue
                }
                val by = mutableListOf(requirer)
                requirers[name] = by
                resolved[name] = ResolvedPackage(name, location, origin, found, opened.source, opened.hash, by)
                if (found != null) queue += Triple(name, location, found)
            }
        }
        problems += cycles(home, resolved)
        return Resolution(resolved, problems)
    }

    /**
     * What [dependency] (declared in [file] at [at], by a package whose files
     * are at [base]) asks a host for, or null with the problem reported: one
     * source, well formed; a path relative to [base] (never from a git
     * package: its folders aren't anyone else's); a git repository, at the
     * commit [pin] says while it says the same `git` and `rev`.
     */
    private fun request(
        dependency: Dependency,
        base: String,
        pin: LockedPackage?,
        file: String,
        at: String,
        problems: MutableList<Problem>
    ): PackageRequest? {
        val path = dependency.path
        val git = dependency.git
        if ((path == null) == (git == null) || (path != null && dependency.rev != null)) {
            val why = when {
                path == null && git == null -> "This dependency says nowhere to find it: give it a \"path\" or a \"git\" repository"
                git != null -> "This dependency has both a \"path\" and a \"git\" repository: give it one"
                else -> "\"rev\" goes with a \"git\" repository, not a \"path\""
            }
            problems += ProblemCodes.PACKAGE_SOURCE.at(file, why, at)
            return null
        }
        if (path != null) {
            if (!isPath(path)) {
                problems += ProblemCodes.PACKAGE_PATH.at(
                    file,
                    "\"$path\" isn't a folder relative to this project's, with / between its parts (like ../economy)",
                    "$at.path"
                )
                return null
            }
            if (base.startsWith(GIT_LOCATION_PREFIX)) {
                problems += ProblemCodes.PACKAGE_GIT_PATH.at(
                    file,
                    "A package fetched from git can't depend on the folder \"$path\": it isn't there for whoever fetches the package. " +
                        "Depend on it by git too",
                    "$at.path"
                )
                return null
            }
            return PackageRequest.Folder(join(base, path))
        }
        val url = git!!
        if (!isGitUrl(url)) {
            problems += ProblemCodes.PACKAGE_GIT_URL.at(
                file,
                "\"$url\" isn't a repository NetherForge fetches from: https://…, ssh://…, user@host:path or file://…",
                "$at.git"
            )
            return null
        }
        val rev = dependency.rev
        if (rev != null && !isGitRev(rev)) {
            problems += ProblemCodes.PACKAGE_GIT_REV.at(file, "\"$rev\" isn't the name of a branch, a tag or a full commit", "$at.rev")
            return null
        }
        val locked = (pin?.source as? PackageSource.Git)?.takeIf { it.url == url && it.rev == rev && isCommit(it.commit) }
        return PackageRequest.Git(url, rev, locked?.commit)
    }

    /** Whether [origin] (a package already resolved) is what [request] asks for: one folder, or one repository at one `rev`. */
    private fun sameRequest(origin: PackageSource, request: PackageRequest): Boolean = when (request) {
        is PackageRequest.Folder -> origin == PackageSource.Path(request.location)
        is PackageRequest.Git -> origin is PackageSource.Git && origin.url == request.url && origin.rev == request.rev
    }

    /** "`<version>`, " of the folder [request] names, for a conflict's message; nothing for a repository, which isn't fetched for it. */
    private fun versionOf(sources: PackageSources, name: String, request: PackageRequest): String {
        if (request !is PackageRequest.Folder) return ""
        val opened = sources.open(name, request) as? OpenedPackage
        return "${opened?.source?.read(ProjectManifest.FILE_NAME)?.let(::parseManifest)?.version ?: "unreadable"}, "
    }

    private fun describe(request: PackageRequest): String = when (request) {
        is PackageRequest.Folder -> request.location
        is PackageRequest.Git -> request.url + ((request.commit ?: request.rev)?.let { " at $it" } ?: "")
    }

    private fun describe(origin: PackageSource): String = when (origin) {
        is PackageSource.Path -> origin.path
        is PackageSource.Git -> "${origin.url} at ${origin.commit.ifEmpty { origin.rev ?: "its default branch" }}"
        is PackageSource.Registry -> origin.url
    }

    /** A loop among packages (the project's own loop is found as it's resolved): reported once, where it closes. */
    private fun cycles(home: String, resolved: Map<String, ResolvedPackage>): List<Problem> {
        val problems = mutableListOf<Problem>()
        val done = HashSet<String>()
        fun visit(name: String, path: List<String>) {
            if (name in done) return
            val manifest = resolved[name]?.manifest ?: return
            for (next in manifest.dependencies.orEmpty().keys.sorted()) {
                if (next !in resolved) continue
                val at = path.indexOf(next)
                if (at >= 0) {
                    val loop = (path.drop(at) + name + next).joinToString(" → ") { "\"$it\"" }
                    problems += ProblemCodes.PACKAGE_CYCLE.at(
                        manifestPath(name, home),
                        "Packages depend on each other in a loop: $loop",
                        CanonicalJson.childPath("$.dependencies", next)
                    )
                } else {
                    visit(next, path + name)
                }
            }
            done += name
        }
        for (name in resolved.keys) visit(name, emptyList())
        return problems.distinct()
    }

    private fun parseManifest(text: String): ProjectManifest? =
        (ManifestKind.parse(text, ProjectManifest.FILE_NAME) as? CanonicalJson.Parsed.Ok)?.value

    /** The `netherforge.json` of [namespace] as [home]'s problems name it: the project's own, or a package path. */
    private fun manifestPath(namespace: String, home: String) =
        if (namespace == home) ProjectManifest.FILE_NAME else PackagePaths.of(namespace, ProjectManifest.FILE_NAME)

    /**
     * Whether [path] can be a dependency's `path`: relative, `/` between its
     * parts, no empty parts (`..` and `.` are fine: a package is usually a
     * sibling folder). No drive letters, no backslashes.
     */
    fun isPath(path: String): Boolean = path.isNotEmpty() &&
        !path.startsWith("/") &&
        path.split('/').all { it.isNotEmpty() && (it == "." || it == ".." || Names.SEGMENT.matches(it)) }

    /**
     * [path] (a dependency's, relative to the folder at [base]) as a folder
     * relative to the root project's: `.` and `..` resolved as far as they
     * go, `""` for the root itself. `join("../library", "../economy")` is
     * `../economy`.
     */
    fun join(base: String, path: String): String {
        val parts = ArrayList<String>()
        for (part in (if (base.isEmpty()) emptyList() else base.split('/')) + path.split('/')) {
            when {
                part == "." || part.isEmpty() -> {}
                part == ".." && parts.isNotEmpty() && parts.last() != ".." -> parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }

    /**
     * The `netherforge.lock` in [text] (the file's, null when there's none),
     * for its pins: null when there's none or it doesn't read (loading
     * reports why).
     */
    fun parseLock(text: String?): LockFile? = text?.let { (LockKind.parse(it, LockFile.FILE_NAME) as? CanonicalJson.Parsed.Ok)?.value }

    /**
     * What's wrong with the project's `netherforge.lock` ([text], null when
     * there's none) given what resolved: missing while there are
     * dependencies, or saying something other than they resolve to now
     * (a hash the host didn't compute isn't compared).
     */
    internal fun checkLock(text: String?, resolution: Resolution, problems: MutableList<Problem>) {
        val file = LockFile.FILE_NAME
        if (text == null) {
            if (resolution.packages.isNotEmpty()) {
                problems += ProblemCodes.LOCK_MISSING.at(
                    ProjectManifest.FILE_NAME,
                    "There's no ${LockFile.FILE_NAME} pinning the packages this project depends on; the editor writes it, or run netherforge lock",
                    "$.dependencies"
                )
            }
            return
        }
        val lock = when (val parsed = LockKind.parse(text, file)) {
            is CanonicalJson.Parsed.Failed -> {
                problems += parsed.problem
                return
            }
            is CanonicalJson.Parsed.Ok -> parsed.value
        }
        // A git package that isn't what the lock pinned is that problem, and the lock stays as it is.
        if (resolution.problems.any { it.code == ProblemCodes.PACKAGE_HASH.code }) return
        val stale = (lock.packages.keys + resolution.packages.keys).sorted().filter { name ->
            val locked = lock.packages[name]
            val now = resolution.packages[name]
            when {
                locked == null || now == null -> true
                now.manifest?.version != locked.version -> true
                locked.source != now.origin -> true
                now.hash != null && now.hash != locked.hash -> true
                else -> false
            }
        }
        if (stale.isNotEmpty()) {
            problems += ProblemCodes.LOCK_STALE.at(
                file,
                "${LockFile.FILE_NAME} is out of date for ${stale.joinToString {
                    "\"$it\""
                }}; the editor rewrites it, or run netherforge lock",
                "$.packages"
            )
        }
    }

    /** [problem], found in package [namespace]'s own files, as the project that depends on it names them: at package paths. */
    internal fun inPackage(namespace: String, problem: Problem): Problem = problem.copy(
        file = PackagePaths.of(namespace, problem.file),
        related = problem.related.map {
            if (PackagePaths.split(it.file).first !=
                null
            ) {
                it
            } else {
                it.copy(file = PackagePaths.of(namespace, it.file))
            }
        }
    )
}
