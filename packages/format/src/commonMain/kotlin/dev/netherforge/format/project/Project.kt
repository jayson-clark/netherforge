package dev.netherforge.format.project

import dev.netherforge.format.Location
import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCode
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.block.VanillaFile
import dev.netherforge.format.datapack.DatapackKind
import dev.netherforge.format.datapack.DatapackLayout
import dev.netherforge.format.datapack.PackMeta
import dev.netherforge.format.dimensiontype.DimensionTypeKind
import dev.netherforge.format.game.FeatureTable
import dev.netherforge.format.game.GameData
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.game.MinecraftVersion
import dev.netherforge.format.hasErrors
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.module.ModuleKind
import dev.netherforge.format.ref.BrokenRef
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.RefScope
import dev.netherforge.format.ref.RefUse
import dev.netherforge.format.ref.ReferenceIndex
import dev.netherforge.format.ref.ResourceKey
import dev.netherforge.format.resourcepack.CompiledResourcePack
import dev.netherforge.format.resourcepack.ResourcePackFile
import dev.netherforge.format.resourcepack.ResourcePackKind
import dev.netherforge.format.resourcepack.ResourcePackRead
import dev.netherforge.format.script.ScriptDef
import dev.netherforge.format.settings.SettingsValidator
import dev.netherforge.format.terrain.TerrainKind
import dev.netherforge.format.terrain.TerrainValidator
import dev.netherforge.format.text.DefaultFontFile
import dev.netherforge.format.text.DefaultFontValidator
import dev.netherforge.format.wasm.WasmInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Read access to a project's files. Paths are project-relative with `/`
 * separators. The plugin implements this over the disk, the editor over its
 * open workspace, tests over a map.
 */
interface ProjectSource {
    /** Every file in the project, excluding `.git/` and `.netherforge/`. */
    fun files(): Collection<String>

    /** A file's text, or null if it doesn't exist. */
    fun read(path: String): String?

    /**
     * Whether [image] reads pixels at all. When it does, a null [image] for a
     * texture that exists means the picture can't be read, which the pack
     * validator warns about.
     */
    val readsImages: Boolean get() = false

    /**
     * Pixel facts about a PNG (a pack texture), or null when this source can't
     * read pixels, the file doesn't exist, or it isn't a readable image. The
     * plugin reads them off the disk; the editor passes what it measured.
     */
    fun image(path: String): ImageInfo? = null

    /**
     * Whether [size] answers at all: a source that reads [image]s does, and one that can only measure a picture's
     * header (the CLI's) says so without having to read its pixels.
     */
    val readsSizes: Boolean get() = readsImages

    /** A PNG's `[width, height]` in pixels, or null where unknown; the pixel facts' size by default. */
    fun size(path: String): List<Int>? = image(path)?.let { listOf(it.width, it.height) }

    /**
     * Whether [wasm] reads WebAssembly modules at all. When it does, a null [wasm] for a module that exists means
     * the file can't be read.
     */
    val readsWasm: Boolean get() = false

    /**
     * What a WebAssembly module (`wasm/<id>/module.wasm`) says about itself, read by [dev.netherforge.format.wasm.WasmReader.read], or null when
     * this source doesn't read modules or the file isn't there. The plugin and the CLI read the file; the editor
     * passes none, so its problems come from the dev server.
     */
    fun wasm(path: String): WasmInfo? = null
}

/**
 * What only an image's pixels say. [opaqueWidth] is the rightmost column
 * holding any pixel that isn't fully transparent, plus one (0 for an empty
 * picture): what Minecraft measures a bitmap glyph's advance from.
 * [opaqueColumns] says of each column, left to right, whether it holds such a
 * pixel: what a picture cut into pieces ([dev.netherforge.format.resourcepack.PackFonts.pieces])
 * measures each piece's advance from. A host may leave it out for a picture
 * no wider than [dev.netherforge.format.resourcepack.PackFonts.SHEET], which is never cut.
 */
@Serializable
data class ImageInfo(val width: Int, val height: Int, val opaqueWidth: Int, val opaqueColumns: List<Boolean>? = null) {
    /**
     * [opaqueWidth] of the columns from [from] until [until] alone, counted from [from]. Without [opaqueColumns]
     * every column left of [opaqueWidth] is taken to hold a pixel.
     */
    fun opaqueWidth(from: Int, until: Int): Int {
        val columns = opaqueColumns
        if (columns == null) return (opaqueWidth.coerceAtMost(until) - from).coerceAtLeast(0)
        for (x in until - 1 downTo from) if (columns.getOrElse(x) { false }) return x - from + 1
        return 0
    }
}

/**
 * A project from memory. With [images], it [readsImages] and answers from them; with [sizes] alone (a host that
 * only measured the pictures' headers) it [readsSizes]; with [wasm], it [readsWasm].
 */
class MapProjectSource(
    private val contents: Map<String, String?>,
    private val images: Map<String, ImageInfo>? = null,
    private val sizes: Map<String, List<Int>>? = null,
    private val wasm: Map<String, WasmInfo>? = null
) : ProjectSource {
    override val readsWasm: Boolean get() = wasm != null

    override fun wasm(path: String): WasmInfo? = wasm?.get(path)

    override fun files(): Collection<String> = contents.keys

    override fun read(path: String): String? = contents[path]

    override val readsImages: Boolean get() = images != null

    override fun image(path: String): ImageInfo? = images?.get(path)

    override val readsSizes: Boolean get() = sizes != null || images != null

    override fun size(path: String): List<Int>? = sizes?.get(path) ?: super.size(path)
}

/**
 * A whole project, read and checked.
 *
 * [resources] holds every resource that read, by kind ([KindSpec.id]) then
 * id; [get], [models] and [compiled] are its typed views. A resource that
 * failed to parse is absent and its problem is in [problems]; a resource
 * with semantic errors is present (the editor still wants to show it) but
 * has nothing [compiled] (nothing should run it).
 */
class ProjectSnapshot internal constructor(
    val manifest: ProjectManifest?,
    /** Every reference the project's files make, and what they can name: by namespace, the project's own first. */
    val references: ReferenceIndex,
    /** `fonts/default.json`, when it's there and parses: the default font's advances, for measuring text (`TextWidth`). */
    val defaultFont: DefaultFontFile?,
    /** `client/vanilla.json`, when it's there and parses: the game's own looks of the blocks custom blocks are held in. */
    val vanilla: VanillaFile?,
    val resources: Map<String, Map<String, Loaded<*, *>>>,
    /**
     * Every pack, the packages' included ([everywhere]'s keys), compiled
     * together (glyph characters are numbered across them all), when none
     * has errors.
     */
    val compiledResourcePacks: Map<String, CompiledResourcePack>,
    /**
     * Every problem: the project's own, its dependencies' (as they resolved,
     * and in `netherforge.lock`), and each package's, at package paths
     * ([PackagePaths]).
     */
    val problems: List<Problem>,
    /**
     * The packages the project depends on, their own dependencies included,
     * by namespace: each loaded on its own, in its own namespace. Empty for
     * a package's own snapshot (its dependencies are its project's).
     */
    val packages: Map<String, LoadedPackage> = emptyMap()
) {
    val minecraft: MinecraftVersion? get() = manifest?.minecraft?.let { MinecraftVersion.parse(it) }

    /** The project's namespace: what its references resolve in when they name none. */
    val namespace: String get() = references.home

    /** Every resource of [kind] that read, by id. */
    @Suppress("UNCHECKED_CAST")
    operator fun <T, C> get(kind: KindSpec<T, C>): Map<String, Loaded<T, C>> = resources[kind.id].orEmpty() as Map<String, Loaded<T, C>>

    /** What every resource of [kind] that read says, by id: errors or not. */
    fun <T> models(kind: KindSpec<T, *>): Map<String, T> = get(kind).mapValues { it.value.value }

    /** What the server runs of [kind]: each resource with no errors, compiled, by id. */
    fun <C> compiled(kind: KindSpec<*, C>): Map<String, C> =
        get(kind).entries.mapNotNull { (id, loaded) -> loaded.compiled?.let { id to it } }.toMap()

    /**
     * Every resource of [kind] in the project and every package, as a server
     * running them names them: the project's own by id (`ruby`), a
     * package's as `ns:id` (`acme:coin`), which is how the project refers to
     * it too. A package's are compiled with every reference written in full,
     * so they mean the same beside the project's.
     */
    fun <T, C> everywhere(kind: KindSpec<T, C>): Map<String, Loaded<T, C>> {
        val all = LinkedHashMap(get(kind))
        for ((namespace, pkg) in packages.entries.sortedBy { it.key }) {
            for ((id, loaded) in pkg.snapshot[kind]) all[PackagePaths.of(namespace, id)] = loaded
        }
        return all
    }

    /** What the server runs of [kind], the packages' included: [everywhere]'s, compiled. */
    fun <C> running(kind: KindSpec<*, C>): Map<String, C> =
        everywhere(kind).entries.mapNotNull { (id, loaded) -> loaded.compiled?.let { id to it } }.toMap()

    /**
     * The `netherforge.lock` the packages make (each one's version, source
     * and hash), null while one lacks a version or a hash, or a git package
     * isn't what the lock pinned; what the editor and `netherforge lock` write.
     */
    fun lock(): LockFile? = Resolution.lockOf(packages.values.map { it.resolved }, problems)

    /** The manifest of [namespace]: the project's own, or a package's; null when it has none that reads. */
    fun manifestOf(namespace: String): ProjectManifest? = if (namespace == this.namespace) manifest else packages[namespace]?.manifest

    /**
     * What code or files in [namespace] (the project's own, or a package's)
     * can name, and every reference its files make: its own names whole, and
     * what each package it depends on exports. Null for a namespace that
     * isn't loaded.
     */
    fun referencesOf(namespace: String): ReferenceIndex? =
        if (namespace == this.namespace) references else packages[namespace]?.snapshot?.references

    /**
     * Why something in [from] (the project's namespace, or a package's) can't
     * name [key], a resource of [kind]: [key]'s namespace is neither [from]
     * nor a package [from] depends on, or [key] is that package's and it
     * doesn't export it. Null when it may: whether [key] exists is the
     * caller's to say. What files may name ([ReferenceIndex.check]) and what
     * scripts may, for every kind of resource.
     */
    fun nameable(from: String, kind: KindSpec<*, *>, key: ResourceKey): BrokenRef? {
        if (key.namespace == from) return null
        if (manifestOf(from)?.dependencies?.containsKey(key.namespace) != true) return ReferenceIndex.unknownNamespace(key.namespace, from)
        val target = packages[key.namespace] ?: return null
        val exists = key.path in target.snapshot[kind]
        if (exists && target.manifest?.exports(kind, key.path) != true) {
            return ReferenceIndex.notExported(key.namespace, "${kind.id.replace('_', ' ')} \"${key.path}\"")
        }
        return null
    }

    fun problemsFor(file: String) = problems.filter { it.file == file }

    /**
     * Where the resource [id] of [kind] sits, in its group folders: the project's own by id, a package's as `ns:id`
     * (at its package path). One that isn't there is placed in its kind's folder, where a new one would go.
     */
    fun placeOf(kind: KindSpec<*, *>, id: String): ResourcePlace {
        val (pkg, bare) = PackagePaths.split(id)
        val loaded = if (pkg == null) get(kind)[id] else packages[pkg]?.snapshot?.get(kind)?.get(bare)
        return kind.place(id, loaded?.group)
    }
}

/** A package the project depends on, as it was found ([resolved]) and as it loaded on its own ([snapshot]). */
class LoadedPackage internal constructor(val resolved: ResolvedPackage, val snapshot: ProjectSnapshot) {
    val namespace: String get() = resolved.namespace
    val manifest: ProjectManifest? get() = resolved.manifest

    /** Where its files are: a folder relative to the project's (`../economy`), or `git:<commit>` in the cache. */
    val location: String get() = resolved.location
}

object Projects {

    /**
     * A resource that read and was [KindSpec.validate]d on its own, waiting
     * for the rest of the project before it's checked against it. It depends
     * only on its files, the game and the target version, so a
     * [ProjectCache] keeps it (with the references it makes and its compiled
     * form, worked out once) while those stay the same.
     */
    private class Pending<T, C>(
        val kind: KindSpec<T, C>,
        val id: String,
        /** The group folders it's in, empty for none. */
        val group: String,
        val value: T,
        val files: Set<String>,
        game: GameData?,
        minecraft: String?,
        features: FeatureTable,
        texts: ResourceTexts
    ) {
        /** Its own problems, which don't depend on anything else in the project. */
        private val own = ResourceContext(id, group, files, game, minecraft, ProblemSink(kind.pathOf(id, group)), features, texts)

        init {
            kind.validate(value, own)
            if (kind is DocumentResourceKind<T, C> && kind.script != null) scriptFiles(kind.scriptOf(value), own)
        }

        private var uses: Pair<String, List<RefUse>>? = null

        /** The references it makes, for a document; none for a resource format doesn't read. */
        fun uses(home: String): List<RefUse> {
            uses?.takeIf { it.first == home }?.let { return it.second }
            val found = when (kind) {
                is DocumentResourceKind<T, C> ->
                    ReferenceIndex.usesOf(
                        home,
                        kind.pathOf(id, group),
                        kind,
                        CanonicalJson.json.encodeToJsonElement(kind.serializer, value)
                    )
                is FilesKind<*> -> {
                    val document = kind.companionDocument
                    val path = kind.companionPathOf(id, group)
                    val json = kind.companion(value)
                    if (document != null && path != null && json != null) ReferenceIndex.usesOf(home, path, document, json) else emptyList()
                }
            }
            uses = home to found
            return found
        }

        /** What it compiled to, for the namespace it ran in (null: as the project); kept with it. */
        private var compiled: Pair<String?, C>? = null

        private fun compiled(qualifiedIn: String?): C {
            compiled?.takeIf { it.first == qualifiedIn }?.let { return it.second }
            val running = if (qualifiedIn != null && kind is DocumentResourceKind<T, C>) qualify(kind, value, qualifiedIn) else value
            // Named as the server running it names it (`chess:board`), as [ProjectSnapshot.running] keys it: what a
            // compiled resource says its id is is what the runtime spells it by and finds its files from.
            val named = if (qualifiedIn != null) PackagePaths.of(qualifiedIn, id) else id
            return kind.compile(named, running, own).also { compiled = qualifiedIn to it }
        }

        /** Checked against the project, and compiled when nothing found errors: from a [qualified] copy for a package, which runs beside the project. */
        fun load(index: ProjectIndex, qualified: Boolean): Loaded<T, C> {
            val ctx =
                KindContext(
                    id, group, files, own.game, own.minecraft,
                    ProblemSink(
                        kind.pathOf(id, group)
                    ),
                    index, own.features, own.texts, qualified
                )
            kind.crossCheck(value, ctx)
            // Every reference it makes (in its document, or a binary resource's companion document), found by the walker
            // wherever the model marks one.
            val documents = when (kind) {
                is DocumentResourceKind<T, C> -> listOf(ctx.sink)
                is FilesKind<*> -> listOfNotNull(kind.companionPathOf(id, group)?.let(::ProblemSink))
            }
            for (sink in documents) {
                for (use in index.references.usesIn(sink.file)) {
                    val broken = index.references.check(use) ?: continue
                    sink.report(broken.code, broken.message, use.path, broken.related)
                }
                if (sink !== ctx.sink) ctx.report(sink.problems)
            }
            val problems = own.problems + ctx.problems
            if (problems.hasErrors) return Loaded(id, value, files, problems, null, group)
            return Loaded(id, value, files, problems, compiled(if (qualified) index.references.home else null), group)
        }

        private fun qualify(kind: DocumentResourceKind<T, C>, value: T, home: String): T {
            val element = CanonicalJson.json.encodeToJsonElement(kind.serializer, value)
            return CanonicalJson.json.decodeFromJsonElement(
                kind.serializer,
                ReferenceIndex.qualify(kind.serializer.descriptor, element, home)
            )
        }

        /** Lua beside a resource's script is the script's to require, so it's named as a module's files are. */
        private fun scriptFiles(script: ScriptDef?, ctx: ResourceContext) {
            for (file in files.sorted()) {
                if (!file.endsWith(".lua") || file == script?.file || Names.isLuaFile(file)) continue
                ctx.problem(
                    ProblemCodes.SCRIPT_FILE_NAME,
                    kind.fileOf(id, file, group),
                    "Lua files beside a script must be named with letters, digits and _ so require() can reach them"
                )
            }
        }
    }

    /** One project's (or package's) files read: its manifest and default font, and every resource that read, before validating. */
    private class Reading(
        val source: ProjectSource,
        val files: Set<String>,
        val manifest: ProjectManifest?,
        val home: String,
        val defaultFont: DefaultFontFile?,
        val vanilla: VanillaFile?,
        val pending: List<Pending<*, *>>,
        val problems: MutableList<Problem>
    ) {
        /** What its resources define, by the kinds of reference that can name them, with the part it exports. */
        val defined: ReferenceIndex.Defined by lazy {
            ReferenceIndex.Defined(
                names { _, _ ->
                    true
                },
                packs { _, _ -> true },
                ReferenceIndex.Defined(names(::exported), packs(::exported))
            )
        }

        private fun exported(kind: KindSpec<*, *>, id: String) = manifest?.exports(kind, id) == true

        private fun packs(include: (KindSpec<*, *>, String) -> Boolean) =
            pending.filter { it.kind == ResourcePackKind && include(ResourcePackKind, it.id) }.map { it.id }.toSet()

        private fun names(include: (KindSpec<*, *>, String) -> Boolean): Map<RefKind, Set<String>> {
            fun ids(kind: KindSpec<*, *>) = pending.filter { it.kind == kind && include(kind, it.id) }.map { it.id }.toSet()
            val packs = pending.filter { it.kind == ResourcePackKind && include(ResourcePackKind, it.id) }.associate {
                it.id to
                    (it.value as ResourcePackFile to it.files)
            }
            fun entries(keys: (String, ResourcePackFile, Set<String>) -> Collection<String>) =
                packs.flatMap { (pack, read) -> keys(pack, read.first, read.second).map { "$pack/$it" } }.toSet()
            // What the datapacks define in this namespace, by registry: a biome's or a placed feature's names.
            val datapackFiles = pending.filter { it.kind == DatapackKind && include(DatapackKind, it.id) }
                .flatMap { DatapackLayout.entries(it.value as PackMeta, it.files) }
                .filter { it.namespace == home }
            val datapacks = datapackFiles.filter { !it.tag }.groupBy({ it.registry }, { it.path })
            val datapackTags = datapackFiles.filter { it.tag }.groupBy({ it.registry }, { it.path })
            fun entries(registry: String?) = registry?.let { datapacks[it] }.orEmpty().toSet()

            // A kind that names tags too has them among its names, each after a `#`.
            fun tags(registry: String?) = registry?.let { datapackTags[it] }.orEmpty().map { "#$it" }.toSet()
            // A resource kind's names are its ids, whichever kind it is, and a datapack's entries of its registry.
            val resources = RefKind.entries.filter { it.scope == RefScope.RESOURCE }.associateWith {
                ids(Kinds.byId(it.resourceKind!!)!!) + entries(it.registry) + tags(it.tagRegistry)
            }
            val entries = RefKind.entries.filter { it.scope == RefScope.DATAPACK }.associateWith { entries(it.registry) }
            return resources + entries + mapOf(
                RefKind.SKIN to entries { _, file, _ -> file.skins.keys },
                RefKind.GLYPH to entries { _, file, _ -> file.glyphs.keys },
                RefKind.ITEM_MODEL to entries { _, file, _ -> file.items.keys },
                RefKind.TOOLTIP to entries { _, file, _ -> file.tooltips.keys },
                RefKind.EQUIPMENT to entries { _, file, _ -> file.equipment.keys },
                RefKind.BLOCK_MODEL to entries { _, file, _ -> file.blocks.keys },
                RefKind.HUD to entries { _, file, _ -> file.hud?.keys.orEmpty() },
                RefKind.SOUND to entries { pack, file, files -> ResourcePackKind.sounds(pack, file, files).keys }
            )
        }
    }

    /**
     * Loads the project in [source] and every package it depends on, from
     * [packages] (none, by default: every dependency is then missing).
     * Each package is read and validated on its own, in its own namespace;
     * the project (and each package) can name what the packages it depends
     * on export, and nothing else of theirs.
     *
     * Each file is read and validated on its own, then everything is checked
     * against everything else (references, [KindSpec.crossCheck]). With a
     * [cache] kept from an earlier load of the same project, only files that
     * changed are read and validated again (a package's under its own
     * [ProjectCache.scope]); the checks across files always run. [features]
     * is the feature table targets are checked against (a test's own, or
     * the real one).
     */
    fun load(
        source: ProjectSource,
        game: GameData? = null,
        packages: PackageSources = PackageSources.NONE,
        cache: ProjectCache = ProjectCache(),
        features: FeatureTable = FeatureTable.CURRENT
    ): ProjectSnapshot {
        val root = read(source, null, game, cache, features)
        val problems = root.problems
        val lockText = root.manifest?.let { source.read(LockFile.FILE_NAME) }
        val resolution = root.manifest?.let { Packages.resolve(it, packages, Packages.parseLock(lockText)) } ?: Resolution.NONE
        problems += resolution.problems
        if (packages.checksLock && root.manifest != null) Packages.checkLock(lockText, resolution, problems)

        val readings = resolution.packages.mapValues { (namespace, pkg) ->
            read(pkg.source, namespace, game, cache.scope(namespace), features)
        }
        cache.retainScopes(readings.keys)
        val defined = readings.mapValues { it.value.defined } + (root.home to root.defined)
        val loaded = readings.mapValues { (namespace, reading) ->
            LoadedPackage(resolution.packages.getValue(namespace), validate(reading, defined, qualified = true, emptyMap()))
        }
        for ((namespace, pkg) in loaded.entries.sortedBy { it.key }) {
            problems +=
                pkg.snapshot.problems.map { Packages.inPackage(namespace, it) }
        }
        return validate(root, defined, qualified = false, loaded)
    }

    /**
     * One project or package's files, classified, read and each validated on
     * its own (or kept from [cache]); [home] is the namespace it's loaded as
     * (its manifest's, for the project).
     */
    private fun read(source: ProjectSource, home: String?, game: GameData?, cache: ProjectCache, features: FeatureTable): Reading {
        cache.begin()
        val problems = mutableListOf<Problem>()
        val files = source.files().map {
            it.replace('\\', '/')
        }.filterNot { it.startsWith(".git/") || it.startsWith(".netherforge/") }.toSet()

        val manifestText = source.read(ProjectManifest.FILE_NAME)
        val (manifest, manifestProblems) = cache.keep(ProjectManifest.FILE_NAME, manifestText) { loadManifest(manifestText) }
        problems += manifestProblems
        val fontText = if (DefaultFontFile.FILE in files) source.read(DefaultFontFile.FILE) else null
        val (defaultFont, fontProblems) = when (fontText) {
            null -> null to emptyList()
            else -> cache.keep(DefaultFontFile.FILE, fontText, manifest?.minecraft) { loadDefaultFont(fontText, manifest?.minecraft) }
        }
        problems += fontProblems
        val vanillaText = if (VanillaFile.FILE in files) source.read(VanillaFile.FILE) else null
        val (vanilla, vanillaProblems) = when (vanillaText) {
            null -> null to emptyList()
            else -> cache.keep(VanillaFile.FILE, vanillaText, manifest?.minecraft) {
                loadVanillaBlockStates(vanillaText, manifest?.minecraft)
            }
        }
        problems += vanillaProblems

        // Which resources there are, each in its group folders with its files: classify every path.
        val found = HashMap<KindSpec<*, *>, MutableMap<String, MutableMap<String, MutableSet<String>>>>()
        val badGroups = mutableSetOf<String>()
        for (path in files.sorted()) {
            val kind = Kinds.inFolder(path.substringBefore('/', "")) ?: continue
            val at = Kinds.classify(path)
            if (at?.id == null || at.role == PathRole.FOLDER) {
                // Hidden files (`.DS_Store`, `.gitkeep`) are nobody's, and not worth a warning.
                if (path.split('/').drop(1).none { it.startsWith(".") }) problems += strayFile(kind, path)
                continue
            }
            val group = at.group.orEmpty()
            val segments = if (group.isEmpty()) emptyList() else group.split('/')
            if (segments.isNotEmpty() && !kind.groupable) {
                val folder = "${kind.folder}/${segments.first()}"
                if (badGroups.add(folder)) {
                    problems += ProblemCodes.PROJECT_GROUP.at(
                        folder,
                        "${kind.id.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }}s can't go in group folders: " +
                            "an id names everything in it, so they stay in ${kind.folder}/, and everything in here is ignored"
                    )
                }
                continue
            }
            val bad = segments.indexOfFirst { !Names.isGroup(it) }
            if (bad >= 0) {
                // Everything in a group folder that isn't one is ignored, and said once.
                val folder = (listOf(kind.folder) + segments.take(bad + 1)).joinToString("/")
                if (badGroups.add(folder)) {
                    problems += ProblemCodes.PROJECT_GROUP.at(
                        folder,
                        "\"${segments[bad]}\" isn't a usable group name (${Names.GROUP_RULE}), so everything in it is ignored"
                    )
                }
                continue
            }
            found.getOrPut(kind) { mutableMapOf() }.getOrPut(at.id) { mutableMapOf() }.getOrPut(group) { mutableSetOf() }
                .also { at.rest?.let(it::add) }
        }
        val pending = Kinds.all.flatMap { kind ->
            found[kind].orEmpty().entries.sortedBy { it.key }.mapNotNull { (id, byGroup) ->
                // An id is the project's whole whatever group it's in: the first place in path order is read.
                val groups = byGroup.keys.sorted()
                val first = groups.first()
                for (other in groups.drop(1)) {
                    problems += ProblemCodes.PROJECT_DUPLICATE_ID.at(
                        kind.locationOf(id, other),
                        "Another ${kind.id.replace('_', ' ')} is \"$id\" too (${kind.locationOf(id, first)}): an id is the " +
                            "project's whole, whatever group folder it's in, so this one is ignored",
                        related = listOf(Location(kind.locationOf(id, first)))
                    )
                }
                read(kind, id, first, byGroup.getValue(first), source, game, manifest?.minecraft, features, cache, problems)
            }
        }
        cache.end()
        return Reading(source, files, manifest, home ?: manifest?.namespace.orEmpty(), defaultFont, vanilla, pending, problems)
    }

    /**
     * [reading] validated against itself and what it can name of the
     * packages it depends on ([defined], by namespace), and compiled; with
     * [packages], the project's snapshot holding them and their packs.
     */
    private fun validate(
        reading: Reading,
        defined: Map<String, ReferenceIndex.Defined>,
        qualified: Boolean,
        packages: Map<String, LoadedPackage>
    ): ProjectSnapshot {
        val home = reading.home
        val problems = reading.problems
        val manifest = reading.manifest
        checkExports(reading, problems)
        val visible = manifest?.dependencies.orEmpty().keys.mapNotNull { name ->
            defined[name]?.takeIf { name != home }?.let { name to it }
        }
        val read = reading.pending.groupBy({ it.kind.id }, { it.id to it.value }).mapValues { it.value.toMap() }
        // Its own namespace, all of it; of each package it depends on, what that exports.
        val own = ReferenceIndex.Defined(reading.defined.names, reading.defined.packs)
        // The manifest names resources too (a world's terrain): its references are found and checked like any file's.
        val manifestUses = manifest?.let {
            ReferenceIndex.usesOf(
                home,
                ProjectManifest.FILE_NAME,
                ManifestKind,
                CanonicalJson.json.encodeToJsonElement(ManifestKind.serializer, it)
            )
        }.orEmpty()
        val references = ReferenceIndex(home, mapOf(home to own) + visible, reading.pending.flatMap { it.uses(home) } + manifestUses)
        val manifestSink = ProblemSink(ProjectManifest.FILE_NAME)
        for (use in manifestUses) {
            val broken = references.check(use) ?: continue
            manifestSink.report(broken.code, broken.message, use.path, broken.related)
        }
        problems += manifestSink.problems
        val folders = reading.pending.groupBy({ it.kind.id }, { it.id to it.files }).mapValues { it.value.toMap() }
        val groups = reading.pending.filter {
            it.group.isNotEmpty()
        }.groupBy({ it.kind.id }, { it.id to it.group }).mapValues { it.value.toMap() }
        val exportedModules = reading.manifest?.exports?.get(ModuleKind.folder).orEmpty().toSet()
        val index = ProjectIndex(reading.source, reading.defaultFont, read, references, folders, reading.vanilla, groups, exportedModules)
        val loaded = reading.pending.map { it.kind to it.load(index, qualified) }
        for ((_, resource) in loaded) problems += resource.problems
        manifest?.worlds?.let { problems += worldHeights(it, references, read, index, packages) }
        val resources = Kinds.all.associate { kind ->
            kind.id to loaded.filter { it.first == kind }.associate { (_, resource) -> resource.id to resource }
        }

        // Every pack in the tree builds into the one resource pack, a package's in its own namespace.
        @Suppress("UNCHECKED_CAST")
        val packs = (resources[ResourcePackKind.id].orEmpty() as Map<String, Loaded<ResourcePackFile, ResourcePackRead>>) +
            packages.entries.flatMap { (namespace, pkg) ->
                pkg.snapshot[ResourcePackKind].map { (id, it) ->
                    PackagePaths.of(namespace, id) to
                        it
                }
            }
        val images = { path: String ->
            val (pkg, local) = PackagePaths.split(path)
            if (pkg == null) index.image(path) else packages[pkg]?.resolved?.source?.image(local)
        }

        return ProjectSnapshot(
            manifest = manifest,
            references = references,
            defaultFont = reading.defaultFont,
            vanilla = reading.vanilla,
            resources = resources,
            compiledResourcePacks = ResourcePackKind.build(home, packs, images),
            problems = problems.sortedWith(compareBy({ it.file }, { it.line ?: 0 })),
            packages = packages
        )
    }

    /**
     * Each world in `netherforge.json` that names both a terrain and a dimension: the terrain held to that
     * dimension's build limits. On its own a terrain is held only to what any world can be, so what this finds is
     * what's out of range in this world alone (`project.world-height`, at the world's terrain, the terrain's own
     * place related). A reference that names nothing is the reference check's.
     */
    private fun worldHeights(
        worlds: Map<String, WorldConfig>,
        references: ReferenceIndex,
        read: Map<String, Map<String, Any?>>,
        index: ProjectIndex,
        packages: Map<String, LoadedPackage>
    ): List<Problem> {
        val sink = ProblemSink(ProjectManifest.FILE_NAME)

        fun <T> model(kind: KindSpec<T, *>, key: ResourceKey): Pair<String, T>? {
            if (key.namespace == references.home) {
                @Suppress("UNCHECKED_CAST")
                val value = read[kind.id]?.get(key.path) as T? ?: return null
                return kind.pathOf(key.path, index.groupOf(kind, key.path)) to value
            }
            val loaded = packages[key.namespace]?.snapshot?.get(kind)?.get(key.path) ?: return null
            return PackagePaths.of(key.namespace, kind.pathOf(key.path, loaded.group)) to loaded.value
        }
        for ((name, config) in worlds) {
            val terrainRef = config.terrain ?: continue
            val dimensionRef = config.dimensionType ?: continue
            val (terrainPath, terrain) =
                references.resolve(RefKind.TERRAIN, terrainRef.text)?.let { model(TerrainKind, it) } ?: continue
            val dimension =
                references.resolve(RefKind.DIMENSION_TYPE, dimensionRef.text)?.let { model(DimensionTypeKind, it) }?.second ?: continue
            val height = dimension.worldHeight
            // What any world allows is the terrain's own problem: only what this world's limits add is reported here.
            val anywhere = ProblemSink(terrainPath).also { TerrainValidator.validate(terrain, it, null) }
                .problems.map { it.code to it.path }.toSet()
            val here = ProblemSink(terrainPath).also { TerrainValidator.validate(terrain, it, null, height) }.problems
            val at = CanonicalJson.childPath(CanonicalJson.childPath("$.worlds", name), "terrain")
            for (problem in here) {
                if (problem.code to problem.path in anywhere) continue
                sink.report(
                    ProblemCodes.PROJECT_WORLD_HEIGHT,
                    "World \"$name\" is from y ${height.minY} to ${height.maxY - 1} (dimension type \"$dimensionRef\"), but in terrain " +
                        "\"$terrainRef\", ${problem.path?.removePrefix("$.") ?: "the file"}: ${problem.message}",
                    at,
                    listOf(Location(terrainPath, problem.path))
                )
            }
        }
        return sink.problems
    }

    /** `exports` names kinds by their folders, and resources the project has. */
    private fun checkExports(reading: Reading, problems: MutableList<Problem>) {
        val exports = reading.manifest?.exports ?: return
        val sink = ProblemSink(ProjectManifest.FILE_NAME)
        for ((folder, ids) in exports) {
            val at = CanonicalJson.childPath("$.exports", folder)
            val kind = Kinds.inFolder(folder)
            if (kind == null || !kind.exportable) {
                sink.report(
                    ProblemCodes.PACKAGE_EXPORT_KIND,
                    "\"$folder\" isn't a kind of resource: export from ${Kinds.all.filter { it.exportable }.joinToString { it.folder }}",
                    at
                )
                continue
            }
            ids.forEachIndexed { i, id ->
                if (reading.pending.none { it.kind == kind && it.id == id }) {
                    sink.report(
                        ProblemCodes.PACKAGE_EXPORT_MISSING,
                        "There's no ${kind.id} \"$id\" (${kind.locationOf(id)}) to export",
                        "$at[$i]"
                    )
                }
            }
        }
        problems += sink.problems
    }

    /**
     * One resource of [kind] read from [source] and validated on its own, or
     * null (with its problem) when it can't be read: an id that isn't usable,
     * a folder without its main file, a document that doesn't parse. [inside]
     * is the files in its folder, in its [group] folders. What [cache] kept is used while the main
     * file's text, [inside], [game] and [minecraft] are what they were.
     */
    private fun <T, C> read(
        kind: KindSpec<T, C>,
        id: String,
        group: String,
        inside: Set<String>,
        source: ProjectSource,
        game: GameData?,
        minecraft: String?,
        features: FeatureTable,
        cache: ProjectCache,
        problems: MutableList<Problem>
    ): Pending<T, C>? {
        val location = kind.locationOf(id, group)
        if (!Names.isId(id)) {
            problems += ProblemCodes.PROJECT_ID.at(location, "\"$id\" isn't a usable id (${Names.ID_RULE})")
            return null
        }
        val main = kind.mainFile
        if (main != null && main !in inside) {
            problems += ProblemCodes.PROJECT_MISSING_FILE.at(location, "Folder has no $main, so it's ignored")
            return null
        }
        val path = kind.pathOf(id, group)
        // A file with a companion document: the document is only read beside the file.
        val companion = kind.companionPathOf(id, group)?.takeIf { it.substringAfterLast('/') in inside }
        if (companion != null && path.substringAfterLast('/') !in inside) {
            problems += ProblemCodes.PROJECT_MISSING_FILE.at(companion, "There's no $path beside this, so it's ignored")
            return null
        }
        val text = when (kind) {
            is FilesKind<*> -> companion?.let(source::read)
            is DocumentResourceKind<T, C> -> source.read(path) ?: return null
        }
        // The other files a kind reads besides its main one (a datapack's), compared whole like the main file's text.
        val texts = inside.filter { kind.layout is Layout.Folder && kind.readsFile(it) }.sorted().mapNotNull { file ->
            source.read(kind.fileOf(id, file, group))?.let { file to it }
        }.toMap()
        val (pending, failed) = cache.keep<Pair<Pending<T, C>?, List<Problem>>>(path, text, inside, texts, game, minecraft, features) {
            val found = mutableListOf<Problem>()
            val value = when (kind) {
                // A files kind is a KindSpec<T, T>.
                is FilesKind<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    kind as FilesKind<T>
                    if (text ==
                        null
                    ) {
                        kind.of(id, inside.sorted())
                    } else {
                        kind.ofCompanion(id, inside.sorted(), text, requireNotNull(companion), found)
                    }
                }
                is DocumentResourceKind<T, C> -> when (val parsed = kind.parse(requireNotNull(text), path)) {
                    is CanonicalJson.Parsed.Failed -> return@keep null to listOf(parsed.problem)
                    is CanonicalJson.Parsed.Ok -> parsed.value
                }
            }
            Pending(kind, id, group, value, inside, game, minecraft, features, ResourceTexts(texts)) to found
        }
        problems += failed
        return pending
    }

    /** A file in [kind]'s folder that isn't one of its resources, which is ignored. */
    private fun strayFile(kind: KindSpec<*, *>, path: String): Problem {
        val shape = when (val layout = kind.layout) {
            is Layout.Folder -> "${kind.folder}/<id>/${layout.main ?: "…"}"
            is Layout.SingleFile ->
                "${kind.folder}/<id>${layout.extension}" + (layout.companion?.let { " (and <id>$it beside it)" } ?: "")
        }
        return ProblemCodes.PROJECT_STRAY_FILE.at(path, "Only $shape goes in ${kind.folder}/, so this file is ignored")
    }

    /**
     * The `formatVersion` a `netherforge.json` declares, read without holding
     * the rest of [text] to this format's shape (another format's manifest
     * needn't parse as this one's); null when it doesn't say.
     */
    fun formatVersionOf(text: String): Int? =
        runCatching { (CanonicalJson.json.parseToJsonElement(text) as? JsonObject)?.get("formatVersion")?.jsonPrimitive?.intOrNull }
            .getOrNull()

    /**
     * Why a project in [formatVersion] can't be opened by this build, as a
     * problem code and message naming the version, or null when it's the
     * current one. There's no migration: the editor and the plugin refuse it.
     */
    fun formatProblem(formatVersion: Int): Pair<ProblemCode, String>? = when {
        formatVersion > FormatVersion.CURRENT ->
            ProblemCodes.PROJECT_FORMAT_VERSION to
                "This project uses format $formatVersion; this NetherForge reads format ${FormatVersion.CURRENT}. Update NetherForge."
        formatVersion < FormatVersion.CURRENT ->
            ProblemCodes.PROJECT_FORMAT_VERSION to
                "This project says format $formatVersion, which no NetherForge has used: this NetherForge reads format ${FormatVersion.CURRENT}."
        else -> null
    }

    /** `netherforge.json` from its [text] (null when there's none), and its problems. */
    private fun loadManifest(text: String?): Pair<ProjectManifest?, List<Problem>> {
        val path = ProjectManifest.FILE_NAME
        if (text == null) {
            return null to listOf(
                ProblemCodes.PROJECT_NO_MANIFEST.at(path, "This folder has no netherforge.json, so it isn't a NetherForge project")
            )
        }
        // Another format's manifest needn't read as this one's: say which format it is, and nothing else.
        formatVersionOf(text)?.let(::formatProblem)?.let { (code, message) ->
            return null to listOf(code.at(path, message, "$.formatVersion"))
        }
        val manifest = when (val parsed = ManifestKind.parse(text, path)) {
            is CanonicalJson.Parsed.Failed -> return null to listOf(parsed.problem)
            is CanonicalJson.Parsed.Ok -> parsed.value
        }
        val sink = ProblemSink(path)
        val version = MinecraftVersion.parse(manifest.minecraft)
        if (version == null) {
            sink.report(ProblemCodes.PROJECT_MINECRAFT, "\"${manifest.minecraft}\" isn't a Minecraft version", "$.minecraft")
        } else if (version < MinecraftVersion.of(MinecraftVersion.OLDEST_SUPPORTED)) {
            sink.report(
                ProblemCodes.PROJECT_MINECRAFT_OLD,
                "NetherForge supports Minecraft ${MinecraftVersion.OLDEST_SUPPORTED} and later",
                "$.minecraft"
            )
        }
        if (manifest.name.isBlank()) sink.report(ProblemCodes.PROJECT_NAME, "The project has no name", "$.name")
        val namespace = manifest.namespace
        if (!Names.isNamespace(namespace)) {
            sink.report(ProblemCodes.PROJECT_NAMESPACE, "\"$namespace\" isn't a usable namespace (${Names.NAMESPACE_RULE})", "$.namespace")
        } else if (namespace in Names.RESERVED_NAMESPACES) {
            sink.report(
                ProblemCodes.PROJECT_NAMESPACE_RESERVED,
                "\"$namespace\" is taken (by the game, the server or NetherForge): pick one that's the project's own",
                "$.namespace"
            )
        }
        if (!Names.isVersion(manifest.version)) {
            sink.report(ProblemCodes.PROJECT_VERSION, "\"${manifest.version}\" isn't a semantic version, like 1.0.0", "$.version")
        }
        manifest.managedWorlds?.forEachIndexed { index, name ->
            if (!Names.isWorldName(name)) {
                sink.report(
                    ProblemCodes.PROJECT_WORLD_NAME,
                    "\"$name\" isn't a world name (${Names.WORLD_NAME_RULE})",
                    "$.managedWorlds[$index]"
                )
            }
        }
        manifest.worlds?.forEach { (name, config) ->
            if (!Names.isWorldName(name)) {
                sink.report(
                    ProblemCodes.PROJECT_WORLD_NAME,
                    "\"$name\" isn't a world name (${Names.WORLD_NAME_RULE})",
                    CanonicalJson.childPath("$.worlds", name)
                )
            }
            config.gameRules?.forEach { (rule, value) ->
                val at = CanonicalJson.childPath(CanonicalJson.childPath(CanonicalJson.childPath("$.worlds", name), "gameRules"), rule)
                if (!GameIds.isValid(rule)) {
                    sink.report(ProblemCodes.PROJECT_WORLD_RULE, "\"$rule\" isn't a game rule's id (keep_inventory)", at)
                } else if (value.isString || (value.booleanOrNull == null && value.content.toIntOrNull() == null)) {
                    sink.report(ProblemCodes.PROJECT_WORLD_RULE, "$rule is true, false or a whole number, as the rule takes", at)
                }
            }
            for ((field, values) in listOf("spawnLimits" to config.spawnLimits, "spawnIntervals" to config.spawnIntervals)) {
                values?.forEach { (category, value) ->
                    if (value < 0) {
                        sink.report(
                            ProblemCodes.PROJECT_WORLD_SPAWN,
                            "$field.${category.id} is $value: a whole number, 0 or more",
                            CanonicalJson.childPath(CanonicalJson.childPath(CanonicalJson.childPath("$.worlds", name), field), category.id)
                        )
                    }
                }
            }
        }
        manifest.allow?.permissions?.forEachIndexed { index, node ->
            if (!Names.isPermissionNode(node)) {
                sink.report(
                    ProblemCodes.PROJECT_PERMISSION_NODE,
                    "\"$node\" isn't a permission node (${Names.PERMISSION_NODE_RULE})",
                    "$.allow.permissions[$index]"
                )
            }
        }
        manifest.requires?.http?.forEachIndexed { index, host ->
            if (!Names.isHttpHost(host)) {
                // A URL where a host belongs is the usual mistake (a WebSocket's `wss://…` above all): name the host to write.
                val bare = host.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore(':').lowercase()
                val message = if (bare.isNotEmpty() && Names.isHttpHost(bare)) {
                    "\"$host\" is a URL: list its host alone, \"$bare\", which covers http, https, ws and wss"
                } else {
                    "\"$host\" isn't a host (${Names.HTTP_HOST_RULE})"
                }
                sink.report(ProblemCodes.PROJECT_REQUIRES_HOST, message, "$.requires.http[$index]")
            }
        }
        manifest.requires?.plugins?.forEachIndexed { index, name ->
            if (!Names.isPluginName(name)) {
                sink.report(
                    ProblemCodes.PROJECT_REQUIRES_PLUGIN,
                    "\"$name\" isn't a plugin's name (${Names.PLUGIN_NAME_RULE})",
                    "$.requires.plugins[$index]"
                )
            }
        }
        manifest.settings?.let { SettingsValidator.validate(it, sink) }
        return manifest to sink.problems
    }

    /** `client/vanilla.json` from its [text], and its problems: it was read from the target [minecraft] version's client, or it's stale. */
    private fun loadVanillaBlockStates(text: String, minecraft: String?): Pair<VanillaFile?, List<Problem>> {
        val path = VanillaFile.FILE
        return when (val parsed = VanillaKind.parse(text, path)) {
            is CanonicalJson.Parsed.Failed -> null to listOf(parsed.problem)
            is CanonicalJson.Parsed.Ok -> {
                val sink = ProblemSink(path)
                if (minecraft != null && parsed.value.minecraft != minecraft) {
                    sink.report(
                        ProblemCodes.BLOCK_VANILLA_LOOKS,
                        "This is the game's look of Minecraft ${parsed.value.minecraft}'s blocks, and the project is for $minecraft: " +
                            "the editor writes it again once Minecraft $minecraft is imported",
                        "$.minecraft"
                    )
                }
                parsed.value to sink.problems
            }
        }
    }

    /** `fonts/default.json` from its [text], checked for the target [minecraft] version, and its problems. */
    private fun loadDefaultFont(text: String, minecraft: String?): Pair<DefaultFontFile?, List<Problem>> {
        val path = DefaultFontFile.FILE
        return when (val parsed = DefaultFontKind.parse(text, path)) {
            is CanonicalJson.Parsed.Failed -> null to listOf(parsed.problem)
            is CanonicalJson.Parsed.Ok -> {
                val sink = ProblemSink(path)
                DefaultFontValidator.validate(parsed.value, sink, minecraft)
                parsed.value to sink.problems
            }
        }
    }
}
