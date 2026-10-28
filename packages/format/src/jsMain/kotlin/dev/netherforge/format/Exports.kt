@file:JsExport

package dev.netherforge.format

import dev.netherforge.format.editor.CanonicalResult
import dev.netherforge.format.editor.GitFetchRequest
import dev.netherforge.format.editor.PackageInputs
import dev.netherforge.format.editor.PackageOutline
import dev.netherforge.format.editor.PackagesNeeded
import dev.netherforge.format.editor.ProjectOutline
import dev.netherforge.format.editor.ProjectValidation
import dev.netherforge.format.editor.ResourcePackOutline
import dev.netherforge.format.editor.SettingValueResult
import dev.netherforge.format.game.BlockState
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.module.ModuleKind
import dev.netherforge.format.project.DocumentKind
import dev.netherforge.format.project.Kinds
import dev.netherforge.format.project.LockFile
import dev.netherforge.format.project.LockKind
import dev.netherforge.format.project.ManifestKind
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.OpenedPackage
import dev.netherforge.format.project.PackageLookup
import dev.netherforge.format.project.PackageMissing
import dev.netherforge.format.project.PackagePaths
import dev.netherforge.format.project.PackageRequest
import dev.netherforge.format.project.PackageSources
import dev.netherforge.format.project.Packages
import dev.netherforge.format.project.ProjectCache
import dev.netherforge.format.project.ProjectManifest
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.format.project.Projects
import dev.netherforge.format.project.Requirement
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.resourcepack.ResourcePackKind
import dev.netherforge.format.settings.SettingDef
import dev.netherforge.format.settings.SettingRead
import dev.netherforge.format.wasm.WasmInfo
import dev.netherforge.format.wasm.WasmReader
import kotlinx.serialization.builtins.serializer

/*
 * The editor's view of `format`. JSON strings in and out; every result is a
 * class in `dev.netherforge.format.editor` (or the model), so its TypeScript
 * type is generated into `@netherforge/format/types` and the editor's wrapper
 * (apps/editor/src/core/format.ts) only parses. Keep this surface small: each
 * export is a question the editor has to ask in exactly the server's terms.
 */

// This file: a project's documents and the project as a whole (loading, validating, its outline). The rest of the
// surface is beside it, by what it's about: PackageExports, RefExports, PreviewExports, NewFileExports,
// TextExports; ExportSupport has what they share.

/** Parses [text] as a document of [kind] and returns a [CanonicalResult]. */
fun canonicalize(kind: String, path: String, text: String): String {
    val documentKind = Kinds.document(kind)
        ?: return encode(
            CanonicalResult.serializer(),
            CanonicalResult(null, listOf(Problem(Severity.ERROR, path, "Unknown document kind \"$kind\"")))
        )
    return encode(CanonicalResult.serializer(), canonicalizeAs(documentKind, path, text))
}

private fun <T> canonicalizeAs(kind: DocumentKind<T>, path: String, text: String): CanonicalResult =
    when (val parsed = kind.parse(text, path)) {
        is CanonicalJson.Parsed.Failed -> CanonicalResult(null, listOf(parsed.problem))
        is CanonicalJson.Parsed.Ok -> CanonicalResult(kind.write(parsed.value, path), emptyList())
    }

/**
 * [packagesJson] (a [PackageInputs], null for none) as the packages the
 * project's dependencies find. What it hasn't got (a folder not read yet, a
 * repository not fetched yet) is recorded in [locations] and [fetches], for
 * [packagesNeeded].
 */
private class MapPackages(packagesJson: String?) : PackageSources {
    private val inputs = packagesJson?.let { CanonicalJson.json.decodeFromString(PackageInputs.serializer(), it) } ?: PackageInputs()
    val locations = LinkedHashSet<String>()
    val fetches = LinkedHashMap<String, GitFetchRequest>()

    override fun open(namespace: String, request: PackageRequest): PackageLookup = when (request) {
        is PackageRequest.Folder -> folder(request.location, null)
        is PackageRequest.Git -> {
            val fetched = inputs.git[request.key]
            when {
                fetched == null -> {
                    fetches[request.key] = GitFetchRequest(request.key, request.url, request.rev, request.commit)
                    PackageMissing("it hasn't been fetched yet")
                }
                fetched.commit == null -> PackageMissing(fetched.error ?: "the fetch failed")
                else -> folder(Packages.gitLocation(fetched.commit), fetched.commit)
            }
        }
    }

    private fun folder(location: String, commit: String?): PackageLookup {
        if (location !in inputs.folders) {
            locations += location
            return PackageMissing(if (commit != null) "its files haven't been read yet" else null)
        }
        val folder = inputs.folders[location] ?: return PackageMissing(if (commit != null) "its checkout isn't in the cache" else null)
        return OpenedPackage(MapProjectSource(folder.files), folder.hash, commit)
    }
}

/**
 * What resolving the project `{ path: text | null }`'s dependencies needs
 * that [packagesJson] (a [PackageInputs], as [loadProject] takes it) doesn't
 * have yet: a [PackagesNeeded], folders to read and repositories to fetch.
 * The host does those and asks again until nothing's needed, since a
 * package's own dependencies are only known once it's read. Git
 * dependencies are asked for at the commit the project's `netherforge.lock`
 * pins, when it does: leave the lock out of [filesJson] to resolve them afresh.
 */
fun packagesNeeded(filesJson: String, packagesJson: String?): String {
    val files = CanonicalJson.json.decodeFromString<Map<String, String?>>(filesJson)
    val packages = MapPackages(packagesJson)
    val source = MapProjectSource(files)
    val manifest = source.read(ProjectManifest.FILE_NAME)
        ?.let { (ManifestKind.parse(it, ProjectManifest.FILE_NAME) as? CanonicalJson.Parsed.Ok)?.value }
    if (manifest != null) Packages.resolve(manifest, packages, Packages.parseLock(source.read(LockFile.FILE_NAME)))
    return encode(PackagesNeeded.serializer(), PackagesNeeded(packages.locations.toList(), packages.fetches.values.toList()))
}

/**
 * Loads a whole project from `{ path: text | null }` (null for files whose
 * contents don't matter here, like `.lua` and `.png`) and its packages
 * ([packagesJson]: a [PackageInputs], read and fetched as [packagesNeeded]
 * asks; null for none), and
 * returns its [ProjectOutline]. [gameDataJson] is a [GameDataBundle], or null
 * when the target version hasn't been imported yet. [sizesJson] is `{ path: [width, height] }` for the PNGs the host
 * measured (the CLI reads their headers), which holds a resource pack's `textures` to the game's sizes; null for none.
 * [wasmJson] is `{ path: WasmInfo }` for the WebAssembly modules the host read ([readWasm]), which checks what they
 * import and export; null for none.
 */
fun loadProject(filesJson: String, packagesJson: String?, gameDataJson: String?, sizesJson: String?, wasmJson: String?): String {
    val files = CanonicalJson.json.decodeFromString<Map<String, String?>>(filesJson)
    val snapshot = Projects.load(
        MapProjectSource(
            files,
            sizes = sizesJson?.let(::sizes),
            wasm = wasmJson?.let {
                CanonicalJson.json.decodeFromString<Map<String, WasmInfo>>(it)
            }
        ),
        gameDataJson?.let(::gameData),
        MapPackages(packagesJson)
    )
    return encode(ProjectOutline.serializer(), outlineOf(snapshot))
}

/** What a WebAssembly module's [bytes] say about it, as a `WasmInfo`: what a host passes [loadProject] for it. */
fun readWasm(bytes: ByteArray): String = encode(WasmInfo.serializer(), WasmReader.read(bytes))

/**
 * An open project validated again and again as it changes: the editor's
 * validation worker holds one per project. It keeps the project's files and
 * its packages' (told only what changed, never sent the whole project) and a
 * [ProjectCache], so [validate] after an edit reads and validates only the
 * files that changed, then checks everything across files again.
 */
class ProjectValidator {
    private val files = HashMap<String, String?>()
    private val cache = ProjectCache()
    private var game: GameDataBundle? = null
    private var packagesJson: String? = null
    private var sizes: Map<String, List<Int>>? = null

    /** The pictures' sizes (`{ path: [width, height] }`) for the checks that hold a replacement to the game's, or null for none known. */
    fun setSizes(sizesJson: String?) {
        sizes = sizesJson?.let(::sizes)
    }

    /** Sets a file's text: null for one whose contents format doesn't read (`.lua`, `.png`). */
    fun setFile(path: String, text: String?) {
        files[path] = text
    }

    /** Forgets a file that's gone. */
    fun deleteFile(path: String) {
        files.remove(path)
    }

    /** The game data validation checks ids against: a [GameDataBundle], or null before an import. */
    fun setGameData(gameDataJson: String?) {
        game = gameDataJson?.let(::gameData)
    }

    /** The packages the dependencies resolve to, as [loadProject] takes them (a [PackageInputs], null for none). */
    fun setPackages(packagesJson: String?) {
        this.packagesJson = packagesJson
    }

    /** The project as it is now: a [ProjectValidation]. */
    fun validate(): String {
        val snapshot = Projects.load(MapProjectSource(HashMap(files), sizes = sizes), game, MapPackages(packagesJson), cache)
        val validated = cache.validated +
            snapshot.packages.keys.sorted().flatMap { namespace -> cache.scope(namespace).validated.map { PackagePaths.of(namespace, it) } }
        return encode(ProjectValidation.serializer(), ProjectValidation(outlineOf(snapshot), validated))
    }
}

/** The group folders each resource in a group is in, by kind and id (kinds with none left out). */
private fun groupsOf(snapshot: ProjectSnapshot): Map<String, Map<String, String>> = Kinds.all.mapNotNull { kind ->
    snapshot[kind].filterValues { it.group.isNotEmpty() }.mapValues { it.value.group }.takeIf { it.isNotEmpty() }?.let { kind.id to it }
}.toMap()

private fun outlineOf(snapshot: ProjectSnapshot) = ProjectOutline(
    name = snapshot.manifest?.name,
    namespace = snapshot.manifest?.namespace,
    minecraft = snapshot.manifest?.minecraft,
    resources = Kinds.all.associate { kind -> kind.id to snapshot[kind].keys.toList() },
    groups = groupsOf(snapshot),
    resourcePacks = snapshot.models(ResourcePackKind).mapValues { (id, pack) ->
        ResourcePackOutline(
            pack.skins.keys.toList(),
            pack.glyphs.keys.toList(),
            pack.items.keys.toList(),
            pack.tooltips.keys.toList(),
            pack.equipment.keys.toList(),
            pack.blocks.keys.toList(),
            snapshot.references.names(RefKind.SOUND).filter { it.startsWith("$id/") }.map { it.removePrefix("$id/") }
        )
    },
    modules = snapshot.models(ModuleKind).mapValues { it.value.files },
    problems = snapshot.problems,
    packages = snapshot.packages.mapValues { (_, pkg) ->
        PackageOutline(
            pkg.location,
            pkg.resolved.origin,
            pkg.manifest?.name,
            pkg.manifest?.version,
            Kinds.all.associate { kind -> kind.id to pkg.snapshot[kind].keys.toList() },
            pkg.manifest?.exports.orEmpty(),
            groupsOf(pkg.snapshot)
        )
    },
    lock = snapshot.lock()?.takeIf { it.packages.isNotEmpty() }?.let(LockKind::write),
    requirements = Requirement.combined(snapshot),
    registryNames = RefKind.entries.mapNotNull { kind -> kind.registry?.let { it to snapshot.references.names(kind) } }.toMap(),
    replacedTextures = allResourcePacks(snapshot).flatMap { it.textures.keys }.distinct().sorted(),
    replacedModels = allResourcePacks(snapshot).flatMap { it.models.keys }.distinct().sorted()
)

/** Every resource pack of the project and its packages, as written. */
private fun allResourcePacks(snapshot: ProjectSnapshot) =
    snapshot.models(ResourcePackKind).values + snapshot.packages.values.flatMap { it.snapshot.models(ResourcePackKind).values }

/**
 * A value for the server-owner setting [definitionJson] (a `SettingDef`, as
 * `netherforge.json` declares it): [input] is JSON, or words as typed when
 * [typed] (a number field's text, `12`). JSON: a `SettingValueResult`, the
 * value in its one written form or why the setting can't have it, in the
 * words the server uses.
 */
fun readSetting(definitionJson: String, input: String, typed: Boolean): String {
    val definition = CanonicalJson.json.decodeFromString(SettingDef.serializer(), definitionJson)
    val read = if (typed) {
        definition.parse(input)
    } else {
        val json = runCatching { CanonicalJson.json.parseToJsonElement(input) }.getOrNull()
            ?: return encode(SettingValueResult.serializer(), SettingValueResult(error = "$input isn't JSON"))
        definition.read(json)
    }
    val result = when (read) {
        is SettingRead.Ok -> SettingValueResult(value = read.json)
        is SettingRead.Bad -> SettingValueResult(error = read.message)
    }
    return encode(SettingValueResult.serializer(), result)
}

/**
 * Why the editor won't open a project whose `netherforge.json` is [manifestText]:
 * its format version isn't this build's (older or newer), with the version
 * named. Null when it's the current one, or the manifest doesn't parse (the
 * project opens and shows that problem).
 */
fun projectRefusal(manifestText: String): String? = Projects.formatVersionOf(manifestText)?.let(Projects::formatProblem)?.second

/**
 * A block state's canonical text (namespaced, properties sorted by name),
 * or null when [text] isn't shaped like a block state.
 */
fun canonicalBlockState(text: String): String? = BlockState.parse(text)?.toString()
