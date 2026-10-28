package dev.netherforge.plugin.project

import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.game.GameData
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.BundleKind
import dev.netherforge.format.project.BundleManifest
import dev.netherforge.format.project.ImageInfo
import dev.netherforge.format.project.LockFile
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
 */
class ProjectFiles(private val configured: Path, private val resolves: Boolean, private val packageCache: Path? = null) : ProjectSource {
    /** One load: the snapshot, what the runtime found wrong besides it (a project it won't run), and every file. */
    class Load(val snapshot: ProjectSnapshot, val problems: List<Problem>, val files: Set<String>)

    // Written on the main thread by [load]; [pathOf] reads them from the bridge's.
    @Volatile
    private var root = DiskProjectSource(configured)

    @Volatile
    private var packages: Map<String, DiskProjectSource> = emptyMap()
    private var rootFiles: Collection<String> = emptyList()

    /** The project's own folder: the configured one, or the project's inside a bundle. */
    val rootFolder: Path get() = root.root

    fun load(game: GameData?): Load {
        val bundle = configured.resolve(BundleManifest.FILE_NAME)
        return if (Files.isRegularFile(bundle)) loadBundle(bundle, game) else loadProject(game)
    }

    private fun loadProject(game: GameData?): Load {
        root = DiskProjectSource(configured)
        rootFiles = root.files()
        val opened = LinkedHashMap<String, DiskProjectSource>()
        val sources = object : PackageSources {
            override fun open(namespace: String, request: PackageRequest): PackageLookup {
                if (!resolves) return PackageMissing()
                return when (request) {
                    is PackageRequest.Folder -> {
                        val folder = configured.resolve(request.location).normalize()
                        if (!Files.isRegularFile(folder.resolve(ProjectManifest.FILE_NAME))) return PackageMissing()
                        OpenedPackage(DiskProjectSource(folder).also { opened[namespace] = it })
                    }
                    is PackageRequest.Git -> openGit(request)?.let { (source, commit) ->
                        opened[namespace] = source
                        OpenedPackage(source, hash(source), commit)
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
        if (!resolves && !snapshot.manifest?.dependencies.isNullOrEmpty()) {
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

    private fun loadBundle(file: Path, game: GameData?): Load {
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
        val project = manifest?.project?.takeIf { it in folders }
        root = DiskProjectSource(project?.let(configured::resolve) ?: configured)
        rootFiles = root.files()
        packages = folders.filter { it != project }.associateWith { DiskProjectSource(configured.resolve(it)) }
        if (manifest != null && project == null) {
            problems +=
                ProblemCodes.RUNTIME_BUNDLE_HASH.at(
                    BundleManifest.FILE_NAME,
                    "The bundle lists no package \"${manifest.project}\"",
                    "$.project"
                )
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
        val sources = object : PackageSources {
            override fun open(namespace: String, request: PackageRequest): PackageLookup =
                packages[namespace]?.let { OpenedPackage(it) } ?: PackageMissing("the bundle has no package \"$namespace\"")

            override val checksLock: Boolean get() = false
        }
        return Load(Projects.load(this, game, sources), problems, allFiles())
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
