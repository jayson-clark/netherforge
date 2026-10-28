package dev.netherforge.format.project

import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCode
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.block.VanillaFile
import dev.netherforge.format.datapack.DatapackCollection
import dev.netherforge.format.datapack.DatapackFiles
import dev.netherforge.format.datapack.DatapackKind
import dev.netherforge.format.game.FeatureTable
import dev.netherforge.format.game.GameData
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.module.ModuleRequires
import dev.netherforge.format.ref.ReferenceIndex
import dev.netherforge.format.script.ScriptDef
import dev.netherforge.format.text.DefaultFontFile
import dev.netherforge.format.wasm.WasmInfo
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement

/** How a kind's resources sit in a project. */
sealed interface Layout {
    /**
     * A folder per resource, `<folder>/<id>/…`, and everything in it is the
     * resource's. [main] is the file that makes a folder one (a folder
     * without it is ignored), or null when any file does (a module).
     */
    data class Folder(val main: String?) : Layout

    /**
     * One file per resource, `<folder>/<id><extension>`, and nothing beside it
     * but its [companion], when it has one: an optional document of the same
     * name (`<folder>/<id><companion>`) that says more about the resource and
     * means nothing without the file (a structure's `.json` beside its `.nbt`).
     */
    data class SingleFile(val extension: String, val companion: String? = null) : Layout
}

/** What format makes of a kind's files. */
enum class Contents {
    /** A JSON document format parses, validates and writes canonically. */
    JSON,

    /** Lua, which the server runs and format only checks the names of. */
    LUA,

    /**
     * Binary files (Minecraft's own, WebAssembly modules) which only the server reads: format checks where they are,
     * never reads them, and knows of a module only what its host says ([ProjectSource.wasm]).
     */
    BINARY,

    /** SQL the server runs and format only checks the names of. */
    SQL
}

/**
 * The Lua a kind's resources run: a resource's one script, or a module's
 * files. [thisClass] is the class `this` is in the script (null for a
 * module, which has no `this`); [body] is what a new script says after the
 * header line, given the resource's id.
 */
class ScriptSpec(val thisClass: String?, val body: (id: String) -> String)

/**
 * Everything about one kind of resource: where its resources live, how
 * they're read, validated and compiled, what a new one starts as, and
 * whether it runs a script. [Kinds.all] lists every one; nothing else in
 * format, the plugin or the editor keeps a list of kinds.
 *
 * [T] is what a resource reads as (its parsed document, or what its files
 * say), [C] what the server runs (its compiled form; [T] itself for a kind
 * with no compiler).
 */
sealed class KindSpec<T, C>(
    /** Stable name: in [Kinds.classify]'s answers, reload results (`centity:tower`) and the editor's tables. */
    val id: String,
    /** The project folder its resources live in. */
    val folder: String,
    val layout: Layout
) {
    abstract val contents: Contents

    /** The file that makes a folder a resource; null for a single-file kind, or a folder kind any file makes one of. */
    val mainFile: String? get() = (layout as? Layout.Folder)?.main

    /** The id `KindsTest` makes a resource of this kind with: any id, unless the kind's ids have a form of their own. */
    open val sampleId: String get() = "sample"

    /** Whether a package may list it in `exports`: false for what belongs to the package alone (a database's migrations). */
    open val exportable: Boolean get() = true

    /**
     * Whether its resources may sit in group folders (`items/(weapons)/ruby_wand/`): every kind's but a resource
     * pack's, whose id is part of every name in it (`ui/coin`) and of every file the built pack is made from.
     */
    open val groupable: Boolean get() = true

    /** The Lua its resources run, or null when they run none. */
    open val script: ScriptSpec? get() = null

    /**
     * What its resources put in the start-up datapack
     * ([dev.netherforge.format.datapack.StartupDatapack]), for a kind the
     * server learns only as it starts; null for every other. A change to
     * one of its resources needs a restart.
     */
    open val datapack: DatapackFiles<C>? get() = null

    /**
     * What all its running resources together put in the start-up datapack,
     * for files that are one per kind rather than per resource (a registry
     * tag listing them) or that only some resources need. Same restart rule
     * as [datapack]; null for every other kind.
     */
    open val datapackAll: DatapackCollection<C>? get() = null

    /**
     * What a resource is on disk, for rename and delete: its folder
     * (`menus/shop`), or its one file (`recipes/ruby.json`), inside its
     * [group] folders when it's in any (`menus/(shops)/shop`; see
     * [ResourcePlace]). A package's resource, named `ns:id` as the server
     * names it, is at its package path ([PackagePaths]: `acme:menus/bank`).
     */
    fun locationOf(id: String, group: String? = null): String {
        val (pkg, bare) = PackagePaths.split(id)
        val inside = if (group.isNullOrEmpty()) folder else "$folder/$group"
        val local = when (val layout = layout) {
            is Layout.Folder -> "$inside/$bare"
            is Layout.SingleFile -> "$inside/$bare${layout.extension}"
        }
        return if (pkg == null) local else PackagePaths.of(pkg, local)
    }

    /** Where the resource [id] of this kind sits, in its [group] folders (none by default). */
    fun place(id: String, group: String? = null): ResourcePlace = ResourcePlace(this, id, group.orEmpty())

    /**
     * Whether the loader reads [file] (a path inside one of the kind's folder resources, other than its main file) as
     * text and hands it to [validate], [crossCheck] and [compile] through [ResourceContext.text]: for a kind whose
     * resources hold documents of the game's that format checks but doesn't model (a datapack's worldgen JSON). False
     * for every other kind: format reads a resource's main file and nothing else of it.
     */
    open fun readsFile(file: String): Boolean = false

    /**
     * The entries of the game's registries a resource puts in the start-up datapack, by registry folder
     * (`worldgen/biome` → `ruby_grove`), in the namespace it runs in: what a datapack passed through
     * ([DatapackKind]) mustn't write too, and what its files may name in the project's namespace. Empty for a kind
     * that writes none.
     */
    open fun datapackEntries(id: String, value: T): Map<String, Set<String>> = emptyMap()

    /** The document a [Layout.SingleFile.companion] is, for a kind that has one. */
    open val companionDocument: DocumentKind<*>? get() = null

    /**
     * A resource's [companionDocument] as JSON, when it read: where the
     * loader finds the references it makes (the document's marked fields),
     * checked and reported at [companionPathOf] like a document's.
     */
    open fun companion(value: T): JsonElement? = null

    /** The path of a single-file resource's companion document ([Layout.SingleFile.companion]), or null for a kind without one. */
    fun companionPathOf(id: String, group: String? = null): String? {
        val layout = layout as? Layout.SingleFile ?: return null
        val companion = layout.companion ?: return null
        return locationOf(id, group).removeSuffix(layout.extension) + companion
    }

    /** The file a resource is read from: its main file, or its [locationOf] when it has none. */
    fun pathOf(id: String, group: String? = null): String = mainFile?.let { fileOf(id, it, group) } ?: locationOf(id, group)

    /** A file in a folder resource, by its path there: a script, a texture. */
    fun fileOf(id: String, file: String, group: String? = null): String {
        require(layout is Layout.Folder) { "$folder/ holds single files, not folders" }
        return "${locationOf(id, group)}/$file"
    }

    /**
     * Checks a resource that read, on its own: [ctx] has its id, its files,
     * the game and the target version, and nothing else of the project, so
     * the answer depends on those alone and a [ProjectCache] can keep it
     * until one of them changes. Problems in its main file go to `ctx.sink`,
     * in its other files to [ResourceContext.problem].
     */
    open fun validate(value: T, ctx: ResourceContext) {}

    /**
     * Checks what a resource says about the rest of the project, once every
     * resource has read and been [validate]d: [ctx] adds the other resources,
     * the default font, images and the [KindContext.references]. The loader
     * checks every reference itself; this is for what one means beyond
     * existing (a stack's kind against its project item's). It runs on every
     * load, cached or not, so it stays a matter of lookups.
     */
    open fun crossCheck(value: T, ctx: KindContext) {}

    /** The form the server runs, for a resource [validate] and [crossCheck] found no errors in. */
    abstract fun compile(id: String, value: T, ctx: ResourceContext): C

    /**
     * The files of a new resource ([id] already checked), `{ path: text }` already canonical; null for a kind made
     * some other way. [game] is the server's game data, for a kind whose new files say something only it knows (a
     * datapack's format): such a kind throws [TemplateNeedsGame] without it.
     */
    open fun template(id: String, game: GameData? = null): Map<String, String>? = null
}

/** A new resource of a kind whose template needs the server's game data, asked for before there is any: [message] says why. */
class TemplateNeedsGame(message: String) : IllegalStateException(message)

/** A kind whose resources are JSON documents: centities, menus, recipes… */
abstract class DocumentResourceKind<T, C>(
    id: String,
    folder: String,
    layout: Layout,
    override val serializer: KSerializer<T>,
    override val schemaRef: String
) : KindSpec<T, C>(id, folder, layout),
    DocumentKind<T> {
    override val contents get() = Contents.JSON

    /** A resource's one script, for a kind whose resources have one (`script` in its file). */
    open fun scriptOf(value: T): ScriptDef? = null
}

/**
 * A kind whose resources format never parses: a resource is its files. A
 * module (Lua), a structure or a map (Minecraft's binary files).
 */
abstract class FilesKind<T>(id: String, folder: String, layout: Layout, override val contents: Contents) :
    KindSpec<T, T>(id, folder, layout) {
    /** A resource from its files' paths alone, relative to its folder and sorted (none for a single-file kind). */
    abstract fun of(id: String, files: List<String>): T

    /**
     * A resource whose [companion document][Layout.SingleFile.companion] is
     * there, from its [text]: a kind with one reads it here and puts what's
     * wrong with it in [problems] (reported at [path], the document's). The
     * resource still reads when its document doesn't.
     */
    open fun ofCompanion(id: String, files: List<String>, text: String, path: String, problems: MutableList<Problem>): T = of(id, files)

    override fun compile(id: String, value: T, ctx: ResourceContext): T = value
}

/**
 * What a kind's [KindSpec.validate] and [KindSpec.compile] know: the
 * resource itself, the game and the target version, and nothing else of the
 * project (so what they say can be kept while those stay the same).
 */
open class ResourceContext internal constructor(
    /** The resource's id. */
    val id: String,
    /** The group folders it's in (`(weapons)`), empty when none: where its files are, never what it means. */
    val group: String,
    /** The files in the resource's folder, relative to it; empty for a single-file kind. */
    val files: Set<String>,
    /** The game's facts, or null when there are none (the editor before an import). */
    val game: GameData?,
    /** The project's target Minecraft version (`netherforge.json`'s `minecraft`), when its manifest reads. */
    val minecraft: String?,
    /** Problems in the resource's main file (its folder, for a kind without one). */
    val sink: ProblemSink,
    /** Which version each gated feature arrived in. */
    val features: FeatureTable = FeatureTable.CURRENT,
    /** The resource's other files the loader read, for a kind that reads some ([KindSpec.readsFile]). */
    internal val texts: ResourceTexts = ResourceTexts.NONE
) {
    /** A file in the resource's folder, by its project path: what a problem in it is reported at. */
    fun pathIn(kind: KindSpec<*, *>, file: String): String = kind.fileOf(id, file, group)

    /** The text of [file] (a path inside the resource's folder) when the kind reads it ([KindSpec.readsFile]) and it's there. */
    fun text(file: String): String? = texts.text(file)

    /**
     * [file] (a path inside the resource's folder) parsed as any JSON, a parse problem reported at [path] (its project
     * path); null when it wasn't read. Parsed once, however often it's asked for.
     */
    fun json(file: String, path: String): CanonicalJson.Parsed<JsonElement>? = texts.json(file, path)

    private val elsewhere = mutableListOf<Problem>()

    /** Reports using feature [id] at [path] in the main file when the project's target lacks it. */
    fun requireFeature(id: String, path: String? = null) = features.require(minecraft, id, sink, path)

    /** A problem in another of the resource's files, by its project path. */
    fun problem(code: ProblemCode, file: String, message: String) {
        elsewhere += code.at(file, message)
    }

    internal fun report(problems: List<Problem>) {
        elsewhere += problems
    }

    /** Every problem found in the resource: its main file's, then the rest. */
    val problems: List<Problem> get() = sink.problems + elsewhere
}

/**
 * What a kind's [KindSpec.crossCheck] knows: the resource, and the project
 * around it, its [references] included.
 */
class KindContext internal constructor(
    id: String,
    group: String,
    files: Set<String>,
    game: GameData?,
    minecraft: String?,
    sink: ProblemSink,
    private val project: ProjectIndex,
    features: FeatureTable = FeatureTable.CURRENT,
    texts: ResourceTexts = ResourceTexts.NONE,
    /** Whether the resource is a package's (checked in the package's own namespace) rather than the project's. */
    val inPackage: Boolean = false
) : ResourceContext(id, group, files, game, minecraft, sink, features, texts) {
    /** Every resource of [kind] that read, by id. */
    fun <T> models(kind: KindSpec<T, *>): Map<String, T> = project.models(kind)

    /** Where the resource [id] of [kind] sits (in its group folders), or where it would if it's not there. */
    fun placeOf(kind: KindSpec<*, *>, id: String): ResourcePlace = kind.place(id, project.groupOf(kind, id))

    /** The files in the folder of the resource [id] of [kind], relative to it (empty for a single-file kind's). */
    fun filesOf(kind: KindSpec<*, *>, id: String): Set<String> = project.filesOf(kind, id)

    /**
     * Every reference the project makes and what they can name, by namespace.
     * The loader checks this resource's references itself (every `@Ref` and
     * glyph tag); a kind asks it only for what a reference means beyond
     * existing (a stack's kind against its project item's).
     */
    val references: ReferenceIndex get() = project.references

    /** `fonts/default.json`, when it's there and reads. */
    val defaultFont: DefaultFontFile? get() = project.defaultFont

    /** `client/vanilla.json`, when it's there and reads. */
    val vanilla: VanillaFile? get() = project.vanilla

    /** The ids of the modules some `.lua` file in the project requires by name ([ModuleRequires]), or that it exports for others to; read once per load. */
    val requiredModules: Set<String> get() = project.requiredModules

    /** Whether [image] reads pixels at all (see [ProjectSource.readsImages]). */
    val readsImages: Boolean get() = project.source.readsImages

    /** A PNG's pixel facts by project path (see [ProjectSource.image]), read once per load. */
    fun image(path: String): ImageInfo? = project.image(path)

    /** Whether [size] knows pictures' sizes at all (see [ProjectSource.readsSizes]). */
    val readsSizes: Boolean get() = project.source.readsSizes

    /** A PNG's `[width, height]` by project path (see [ProjectSource.size]). */
    fun size(path: String): List<Int>? = project.source.size(path)

    /** Whether [wasm] reads WebAssembly modules at all (see [ProjectSource.readsWasm]). */
    val readsWasm: Boolean get() = project.source.readsWasm

    /** What a WebAssembly module says about itself, by project path (see [ProjectSource.wasm]). */
    fun wasm(path: String): WasmInfo? = project.source.wasm(path)
}

/**
 * The files of a resource the loader read besides its main file ([KindSpec.readsFile]), by path inside its folder,
 * each parsed as JSON at most once. A [ProjectCache] keeps them with the resource, so a load that changed none of
 * them parses nothing again.
 */
class ResourceTexts internal constructor(private val texts: Map<String, String>) {
    private val parsed = HashMap<String, CanonicalJson.Parsed<JsonElement>>()

    fun text(file: String): String? = texts[file]

    fun json(file: String, path: String): CanonicalJson.Parsed<JsonElement>? {
        val text = texts[file] ?: return null
        return parsed.getOrPut(file) { CanonicalJson.parse(JsonElement.serializer(), text, path) }
    }

    internal companion object {
        val NONE = ResourceTexts(emptyMap())
    }
}

/** What a [KindContext] reads of the project beyond the resource itself. */
internal class ProjectIndex(
    val source: ProjectSource,
    val defaultFont: DefaultFontFile?,
    private val read: Map<String, Map<String, Any?>>,
    val references: ReferenceIndex,
    /** The files in each resource's folder, by kind and id, relative to it. */
    private val files: Map<String, Map<String, Set<String>>> = emptyMap(),
    val vanilla: VanillaFile? = null,
    /** The group folders each resource is in, by kind and id; absent for one in its kind's folder. */
    private val groups: Map<String, Map<String, String>> = emptyMap(),
    /** The modules the manifest exports, which other projects require. */
    private val exportedModules: Set<String> = emptySet()
) {
    private val images = HashMap<String, ImageInfo?>()

    val requiredModules: Set<String> by lazy { ModuleRequires.of(source) + exportedModules }

    @Suppress("UNCHECKED_CAST")
    fun <T> models(kind: KindSpec<T, *>): Map<String, T> = read[kind.id].orEmpty() as Map<String, T>

    fun filesOf(kind: KindSpec<*, *>, id: String): Set<String> = files[kind.id]?.get(id).orEmpty()

    fun groupOf(kind: KindSpec<*, *>, id: String): String = groups[kind.id]?.get(id).orEmpty()

    fun image(path: String): ImageInfo? = images.getOrPut(path) { source.image(path) }
}

/**
 * One resource that read: its [value], every problem found in it (in its
 * main file and its other files), and what the server runs, [compiled]
 * only when none of those problems is an error.
 */
class Loaded<T, C>(
    val id: String,
    val value: T,
    /** The files in its folder, relative to it; empty for a single-file kind. */
    val files: Set<String>,
    val problems: List<Problem>,
    val compiled: C?,
    /** The group folders it's in (`(weapons)/(swords)`), empty when none. */
    val group: String = ""
)

/**
 * Where one resource sits in its project: its [kind]'s folder, then any [group] folders (`(weapons)/(swords)`,
 * empty for none), then its folder or file by [id]. Groups sort resources and nothing else, so an id is the same
 * in any group; everything that needs a resource's files asks its place (the snapshot's [ProjectSnapshot.placeOf]).
 */
data class ResourcePlace(val kind: KindSpec<*, *>, val id: String, val group: String = "") {
    /** Its folder, or its one file: what rename and delete act on. */
    val location: String get() = kind.locationOf(id, group)

    /** The file it's read from: its main file, or its [location] when it has none. */
    val path: String get() = kind.pathOf(id, group)

    /** A single-file resource's companion document's path, or null for a kind without one. */
    val companionPath: String? get() = kind.companionPathOf(id, group)

    /** A file in its folder, by its path there. */
    fun file(file: String): String = kind.fileOf(id, file, group)

    /** Whether [path] is one of its files (or its folder, or its companion). */
    fun owns(path: String): Boolean = path == location || path.startsWith("$location/") || path == companionPath
}
