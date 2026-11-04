package dev.netherforge.plugin.project

import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.game.GameData
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.BundleKind
import dev.netherforge.format.project.BundleManifest
import dev.netherforge.format.project.BundledResourcePack
import dev.netherforge.format.project.ImageInfo
import dev.netherforge.format.project.LockFile
import dev.netherforge.format.project.ManifestKind
import dev.netherforge.format.project.Names
import dev.netherforge.format.project.OpenedPackage
import dev.netherforge.format.project.PackageHash
import dev.netherforge.format.project.PackageLookup
import dev.netherforge.format.project.PackageMissing
import dev.netherforge.format.project.PackagePaths
import dev.netherforge.format.project.PackageRequest
import dev.netherforge.format.project.PackageSources
import dev.netherforge.format.project.Packages
import dev.netherforge.format.project.ProjectManifest
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.format.project.ProjectSource
import dev.netherforge.format.project.Projects
import dev.netherforge.format.resourcepack.PackLayout
import dev.netherforge.format.wasm.WasmInfo
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Every file the runtime reads: the project's, by project path, and each
 * package's it depends on, by package path (`library:modules/greetings/init.lua`).
 *
 * The configured folder is a project or a bundle. A project's packages are
 * found where its `netherforge.json`'s paths point, and a git package's
 * checkout at the commit `netherforge.lock` pins in [packageCache] (which the
 * editor fetched it into: a server never fetches, and its hash is checked
 * against the lock's), but only when the runtime [resolves] them (the
 * editor's dev server); a production server runs
 * bundles, whose packages are folders named by namespace beside the
 * project's, each checked against the hash the bundle lists before anything
 * runs. Which one it is, and where every package is, is decided again by
 * every [load].
 *
 * **Packages the server provides** ([provided], the owner's
 * `packages.provided`): each folder is a package, found by the namespace its
 * `netherforge.json` says, and it's what a dependency of that namespace opens,
 * in place of any copy the project points at, whether or not the server
 * resolves (a production server running a project whose only dependencies
 * are provided needs no bundle). It satisfies a dependency only within the
 * dependency's `version` range (format's `package.version`). A bundle that
 * carries its own copy of one is refused (`runtime.provided-package`): it was
 * built against something else, and the owner's code is the one that runs.
 */
class ProjectFiles(
    private val configured: Path,
    private val resolves: Boolean,
    private val packageCache: Path? = null,
    /** Whether bundles must be signed, and by whom. */
    private val bundles: BundlesConfig = BundlesConfig(),
    /** The folders of the packages the server provides (`packages.provided` in `config.yml`). */
    private val provided: List<Path> = emptyList(),
    /** `nf.files`' root: each package's `packs/<name>/` there is a resource pack its scripts wrote ([WrittenPacks]). */
    writtenPacks: Path? = null,
    /** Where the written packs' last good copies are kept. */
    keptPacks: Path? = null
) : ProjectSource {
    private val written = WrittenPacks(writtenPacks, keptPacks)

    /** The project's namespace, as the last [load] read its manifest: what a project path's written pack is the package of. */
    @Volatile
    private var home: String = ""

    /** [problem] as the owner reads it: one in a pack the scripts wrote names the file in their `nf.files` folder. */
    fun shown(problem: Problem): Problem = written.shown(problem, home)

    /** Whether the last [load] read any resource pack a package's scripts wrote. */
    val readsWrittenPacks: Boolean get() = written.any

    /** The `pack.json` of every resource pack the last [load] read that scripts wrote, as a reload names it. */
    fun writtenPackFiles(): List<String> = written.packFiles(home)

    /** One load: the snapshot, what the runtime found wrong besides it (a project it won't run), and every file. */
    class Load(val snapshot: ProjectSnapshot, val problems: List<Problem>, val files: Set<String>)

    /** A package the server provides, as the last [load] found it: its [namespace], [version] and [folder]. */
    data class Provided(val namespace: String, val version: String, val folder: Path) {
        override fun toString() = "$namespace $version (from $folder)"
    }

    /** The packages the server provides that the project loaded, as the last [load] found them. */
    @Volatile
    var providedInUse: List<Provided> = emptyList()
        private set

    /** The provided packages by namespace, read afresh, and what's wrong with them (a folder that isn't a package, two of one namespace). */
    private fun readProvided(problems: MutableList<Problem>): Map<String, Pair<Provided, DiskProjectSource>> {
        val found = LinkedHashMap<String, Pair<Provided, DiskProjectSource>>()
        for (folder in provided) {
            val manifest = DiskProjectSource(folder).read(ProjectManifest.FILE_NAME)
                ?.let { (ManifestKind.parse(it, ProjectManifest.FILE_NAME) as? CanonicalJson.Parsed.Ok)?.value }
            if (manifest == null) {
                problems += ProblemCodes.RUNTIME_PROVIDED_PACKAGE.at(
                    ProjectManifest.FILE_NAME,
                    "The server provides a package at $folder (packages.provided in config.yml), but there's no readable " +
                        "${ProjectManifest.FILE_NAME} there",
                    "$.dependencies"
                )
                continue
            }
            val namespace = manifest.namespace
            found[namespace]?.let { (other, _) ->
                problems += ProblemCodes.RUNTIME_PROVIDED_PACKAGE.at(
                    ProjectManifest.FILE_NAME,
                    "The server provides two packages called \"$namespace\" (packages.provided in config.yml): ${other.folder} " +
                        "and $folder. It runs one of each namespace",
                    "$.dependencies"
                )
                continue
            }
            found[namespace] = Provided(namespace, manifest.version, folder) to DiskProjectSource(folder)
        }
        return found
    }

    /** [entry] as format is given it: the server's copy, named as such in its problems. */
    private fun opened(namespace: String, entry: Pair<Provided, DiskProjectSource>) =
        OpenedPackage(written.over(namespace, entry.second), hash(entry.second), from = "the server's, from ${entry.first.folder}")

    /** Which provided packages the load used: those the snapshot has, whose folder is the server's. */
    private fun inUse(snapshot: ProjectSnapshot, server: Map<String, Pair<Provided, DiskProjectSource>>) =
        server.filter { (namespace, entry) -> packages[namespace] === entry.second && namespace in snapshot.packages }
            .map { it.value.first }

    // Written on the main thread by [load]; [pathOf] reads them from the bridge's.
    @Volatile
    private var root = DiskProjectSource(configured)

    @Volatile
    private var packages: Map<String, DiskProjectSource> = emptyMap()
    private var rootFiles: Collection<String> = emptyList()

    /** The resource pack a bundle was built with, and where it is: null for a project, or a bundle built without one. */
    @Volatile
    var bundledPack: Pair<BundledResourcePack, Path>? = null
        private set

    /** Why the bundle's [bundledPack] isn't used though it has one (a package the server provides adds to the pack), or null. */
    @Volatile
    var bundledPackIgnored: String? = null
        private set

    /** The project's own folder: the configured one, or the project's inside a bundle. */
    val rootFolder: Path get() = root.root

    fun load(game: GameData?): Load {
        val load = written.load { loadOnce(game) }
        // The bundle's pack was built without them, and they may number the glyphs differently.
        if (bundledPack != null && written.any) {
            bundledPackIgnored = "packages' scripts wrote resource packs of their own"
            bundledPack = null
        }
        return load
    }

    private fun loadOnce(game: GameData?): Load {
        val bundle = configured.resolve(BundleManifest.FILE_NAME)
        bundledPack = null
        bundledPackIgnored = null
        val problems = mutableListOf<Problem>()
        val server = readProvided(problems)
        val load = if (Files.isRegularFile(bundle)) loadBundle(bundle, game, server) else loadProject(game, server)
        providedInUse = inUse(load.snapshot, server)
        return Load(load.snapshot, problems + load.problems, load.files)
    }

    private fun loadProject(game: GameData?, server: Map<String, Pair<Provided, DiskProjectSource>>): Load {
        root = DiskProjectSource(configured)
        rootFiles = rootFilesOf(root)
        val opened = LinkedHashMap<String, DiskProjectSource>()
        val sources = object : PackageSources {
            override fun open(namespace: String, request: PackageRequest): PackageLookup {
                server[namespace]?.let { entry ->
                    opened[namespace] = entry.second
                    return opened(namespace, entry)
                }
                if (!resolves) return PackageMissing()
                return when (request) {
                    is PackageRequest.Folder -> {
                        val folder = configured.resolve(request.location).normalize()
                        if (!Files.isRegularFile(folder.resolve(ProjectManifest.FILE_NAME))) return PackageMissing()
                        val source = DiskProjectSource(folder).also { opened[namespace] = it }
                        OpenedPackage(written.over(namespace, source))
                    }
                    is PackageRequest.Git -> openGit(request)?.let { (source, commit) ->
                        opened[namespace] = source
                        OpenedPackage(written.over(namespace, source), hash(source), commit)
                    } ?: PackageMissing(
                        when {
                            request.commit == null ->
                                "${LockFile.FILE_NAME} doesn't pin it yet, and a server never fetches: the editor does, and locks it"
                            else -> "it isn't in the editor's package cache yet: the editor fetches it"
                        }
                    )
                }
            }

            // The editor keeps netherforge.lock; a server only runs what resolves.
            override val checksLock: Boolean get() = false
        }
        val snapshot = Projects.load(this, game, sources)
        packages = opened.filterKeys { it in snapshot.packages }
        val problems = mutableListOf<Problem>()
        if (!resolves && snapshot.manifest?.dependencies.orEmpty().keys.any { it !in server }) {
            problems += ProblemCodes.RUNTIME_UNBUNDLED.at(
                ProjectManifest.FILE_NAME,
                "This project has dependencies, and a server outside the editor never resolves them itself: build a bundle with " +
                    "`netherforge build` and point the plugin's `project` setting at it",
                "$.dependencies"
            )
        }
        return Load(snapshot, problems, allFiles())
    }

    /** The checkout of the commit [request] pins, in [packageCache]; null when it isn't pinned or isn't there. */
    private fun openGit(request: PackageRequest.Git): Pair<DiskProjectSource, String>? {
        val commit = request.commit ?: return null
        val folder = packageCache?.resolve(Packages.gitCheckout(commit)) ?: return null
        if (!Files.isRegularFile(folder.resolve(ProjectManifest.FILE_NAME))) return null
        return DiskProjectSource(folder) to commit
    }

    private fun loadBundle(file: Path, game: GameData?, server: Map<String, Pair<Provided, DiskProjectSource>>): Load {
        val problems = mutableListOf<Problem>()
        val manifest = when (val parsed = BundleKind.parse(Files.readString(file), BundleManifest.FILE_NAME)) {
            is CanonicalJson.Parsed.Failed -> null.also { problems += parsed.problem }
            is CanonicalJson.Parsed.Ok -> parsed.value
        }
        val folders = manifest?.packages?.keys.orEmpty().filter { name ->
            Names.isNamespace(name).also { usable ->
                if (!usable) {
                    problems += ProblemCodes.RUNTIME_BUNDLE_HASH.at(
                        BundleManifest.FILE_NAME,
                        "\"$name\" isn't a package's namespace, so the bundle has no folder for it",
                        "$.packages"
                    )
                }
            }
        }
        bundledPack = manifest?.resourcePack?.let { it to configured.resolve(BundledResourcePack.FILE) }
        val project = manifest?.project?.takeIf { it in folders }
        root = DiskProjectSource(project?.let(configured::resolve) ?: configured)
        rootFiles = rootFilesOf(root)
        packages = folders.filter { it != project }.associateWith { DiskProjectSource(configured.resolve(it)) }
        if (manifest != null && project == null) {
            problems +=
                ProblemCodes.RUNTIME_BUNDLE_HASH.at(
                    BundleManifest.FILE_NAME,
                    "The bundle lists no package \"${manifest.project}\"",
                    "$.project"
                )
        }
        if (manifest != null) {
            BundleSignatures.problem(manifest, bundles)?.let { why ->
                problems += ProblemCodes.RUNTIME_BUNDLE_SIGNATURE.at(BundleManifest.FILE_NAME, why, "$.signatures")
            }
        }
        // A bundle runs as it was built: every package, the project's own included, as it was hashed.
        for (name in folders) {
            val expected = manifest!!.packages.getValue(name).hash
            val actual = hash(if (name == project) root else packages.getValue(name))
            if (actual != expected) {
                problems += ProblemCodes.RUNTIME_BUNDLE_HASH.at(
                    BundleManifest.FILE_NAME,
                    "The package \"$name\" in this bundle isn't the one it was built with (its files hash to $actual, not $expected): " +
                        "build the bundle again rather than changing it",
                    CanonicalJson.childPath("$.packages", name)
                )
            }
        }
        // The owner's copy is the one that runs: a bundle carrying its own was built against something else.
        for (name in folders.filter { it in server }) {
            problems += ProblemCodes.RUNTIME_PROVIDED_PACKAGE.at(
                BundleManifest.FILE_NAME,
                "This bundle carries its own \"$name\", which the server provides (${server.getValue(name).first.folder}, " +
                    "packages.provided in config.yml): build it without it (mark the dependency \"provided\": true, or " +
                    "netherforge build --exclude $name)",
                CanonicalJson.childPath("$.packages", name)
            )
        }
        val leftOut = manifest?.provided.orEmpty().toSet()
        val withServer = packages + server.filterKeys { it !in folders }.mapValues { it.value.second }
        packages = withServer
        val sources = object : PackageSources {
            override fun open(namespace: String, request: PackageRequest): PackageLookup {
                server[namespace]?.takeIf { namespace !in folders }?.let { return opened(namespace, it) }
                packages[namespace]?.takeIf { namespace in folders }?.let { return OpenedPackage(written.over(namespace, it)) }
                return PackageMissing(
                    if (namespace in leftOut) {
                        "the bundle leaves \"$namespace\" for the server to provide, and this server provides no such package " +
                            "(packages.provided in config.yml)"
                    } else {
                        "the bundle has no package \"$namespace\""
                    }
                )
            }

            override val checksLock: Boolean get() = false
        }
        val snapshot = Projects.load(this, game, sources)
        packages = withServer.filterKeys { it in folders || it in snapshot.packages }
        // The bundle's pack was built from what it carries: a package from the server that adds to it makes it another pack.
        val adding = snapshot.packages.filter { (name, pkg) -> name !in folders && PackLayout.needed(pkg.snapshot) }.keys
        if (bundledPack != null && adding.isNotEmpty()) {
            bundledPackIgnored = "the server's ${adding.sorted().joinToString(", ")} adds to it"
            bundledPack = null
        }
        return Load(snapshot, problems, allFiles())
    }

    /** [source]'s files, with the packs its scripts wrote, in the namespace its manifest says ([home]). */
    private fun rootFilesOf(source: DiskProjectSource): Collection<String> {
        home = source.read(ProjectManifest.FILE_NAME)
            ?.let { (ManifestKind.parse(it, ProjectManifest.FILE_NAME) as? CanonicalJson.Parsed.Ok)?.value?.namespace }
            .orEmpty()
        val files = source.files()
        return if (home.isEmpty()) files else written.withPacks(home, files)
    }

    /** The project's own files, as the last [load] listed them: what format loads it from. */
    override fun files(): Collection<String> = rootFiles

    /** Every file: the project's by project path, then each package's by package path. */
    private fun allFiles(): Set<String> = buildSet {
        addAll(rootFiles)
        for ((namespace, source) in packages) source.files().forEach { add(PackagePaths.of(namespace, it)) }
    }

    override fun read(path: String): String? = sourceOf(path)?.let { (source, local) -> source.read(local) }

    override val readsImages: Boolean get() = true

    override fun image(path: String): ImageInfo? = sourceOf(path)?.let { (source, local) -> source.image(local) }

    override val readsWasm: Boolean get() = true

    override fun wasm(path: String): WasmInfo? = sourceOf(path)?.let { (source, local) -> source.wasm(local) }

    fun readBytes(path: String): ByteArray? = sourceOf(path)?.let { (source, local) -> source.readBytes(local) }

    /** The file at a project or package path, or null for one that would leave its folder (or a package there isn't). */
    fun resolve(path: String): Path? = sourceOf(path)?.let { (source, local) -> source.resolve(local) }

    /**
     * The project or package path (the chunk name a script's file runs under) of the file at [file], an absolute path as an
     * editor sees it: in the project's folder, or in a package's (beside the project, or in the package cache). Null for a file
     * in neither. A package nested in another folder wins over the folder around it.
     */
    fun pathOf(file: Path): String? {
        val candidates = listOfNotNull(file.toAbsolutePath().normalize(), runCatching { file.toRealPath() }.getOrNull()).distinct()
        val folders = packages.map { (namespace, source) -> namespace to source } + (null to root)
        var best: Pair<Int, String>? = null
        for ((namespace, source) in folders) {
            val base = source.root.toAbsolutePath().normalize()
            val bases = listOfNotNull(base, runCatching { base.toRealPath() }.getOrNull()).distinct()
            for (candidate in candidates) {
                for (folder in bases) {
                    if (candidate == folder || !candidate.startsWith(folder)) continue
                    val local = folder.relativize(candidate).joinToString("/")
                    val path = if (namespace == null) local else PackagePaths.of(namespace, local)
                    if (best == null || folder.nameCount > best.first) best = folder.nameCount to path
                }
            }
        }
        return best?.second
    }

    private fun sourceOf(path: String): Pair<DiskProjectSource, String>? {
        val (pkg, local) = PackagePaths.split(path)
        written.sourceOf(pkg ?: home, local)?.let { return it }
        val source = if (pkg == null) root else packages[pkg] ?: return null
        return source to local
    }

    companion object {
        /** [source]'s [PackageHash]: its content files' SHA-256s, listed by format, hashed. */
        fun hash(source: DiskProjectSource): String {
            val digests = source.files().filter(PackageHash::isContent).associateWith { sha256(source.readBytes(it) ?: ByteArray(0)) }
            return PackageHash.of(sha256(PackageHash.listing(digests).encodeToByteArray()))
        }

        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
