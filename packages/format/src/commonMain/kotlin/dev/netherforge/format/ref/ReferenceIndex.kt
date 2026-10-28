package dev.netherforge.format.ref

import dev.netherforge.format.Location
import dev.netherforge.format.ProblemCode
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.datapack.DatapackKind
import dev.netherforge.format.datapack.DatapackLayout
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.DocumentKind
import dev.netherforge.format.project.Kinds
import dev.netherforge.format.project.PackagePaths
import dev.netherforge.format.project.PathRole
import dev.netherforge.format.project.ProjectManifest
import dev.netherforge.format.text.GlyphTags
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonElement

/**
 * One reference a project file makes: where ([file] and the JSON [path] of
 * the string), what it names ([kind], as written: [text]), and what that
 * resolves to, [target]: a namespaced key (`shop:ruby`, `shop:ui/coin`) for a
 * resource or a pack entry, a project path (`resource_packs/ui/textures/coin.png`) for
 * a file; null when it isn't shaped like a reference of its kind. A glyph tag
 * also says where in its text it is ([start], [end]).
 */
@Serializable
data class RefUse(
    val file: String,
    val path: String,
    val kind: RefKind,
    val text: String,
    val target: String?,
    val start: Int? = null,
    val end: Int? = null
) {
    val location: Location get() = Location(file, path)
}

/**
 * Something references can name, for find usages, rename and delete: a whole
 * [Resource] (a pack stands for every entry in it), one [ResourcePackEntry], or a
 * [File] (or a folder of them) inside a resource. Always in the project's own
 * namespace: nothing renames another package's resources.
 */
@Serializable
sealed interface RefTarget {
    /** A resource of kind [kind] (a `KindSpec.id`) named [id]. */
    @Serializable
    @SerialName("resource")
    data class Resource(val kind: String, val id: String) : RefTarget

    /** The entry [key] of a pack's [entry] kind (`skin`, `glyph`…). */
    @Serializable
    @SerialName("resource_pack_entry")
    data class ResourcePackEntry(val pack: String, val entry: RefKind, val key: String) : RefTarget

    /** A file in a resource's folder, or a folder of them, by project path (`resource_packs/ui/textures/gui`). */
    @Serializable
    @SerialName("file")
    data class File(val path: String) : RefTarget
}

/** Why a reference names nothing: a problem's code and message, and where it should have been found. */
class BrokenRef(val code: ProblemCode, val message: String, val related: List<Location> = emptyList())

/**
 * What a project's references can name, and every reference its files make.
 *
 * Keyed by namespace: [home] is the project's own, and each package it
 * depends on adds its own, of which the project can name only what the
 * package exports. A reference resolves against [home] when it names no
 * namespace.
 */
class ReferenceIndex internal constructor(
    val home: String,
    private val namespaces: Map<String, Defined>,
    /** Every reference every file makes, file by file in path order, each in document order. */
    val uses: List<RefUse>
) {
    /**
     * What one namespace defines: each kind's names, and its packs (so a
     * missing pack and a missing key read differently). A package's has
     * [exported], the part of it other projects may name.
     */
    class Defined internal constructor(val names: Map<RefKind, Set<String>>, val packs: Set<String>, val exported: Defined? = null) {
        /** What a project depending on this one sees of it. */
        internal val visible: Defined get() = exported ?: this
    }

    private val byFile = uses.groupBy { it.file }

    /** Every reference [file] makes. */
    fun usesIn(file: String): List<RefUse> = byFile[file].orEmpty()

    /** [text] as a reference of [kind], resolved; null when it isn't shaped like one. */
    fun resolve(kind: RefKind, text: String): ResourceKey? = resolve(home, kind, text)

    /** Whether [key] is something of [kind] in its namespace that this project can name. */
    fun has(kind: RefKind, key: ResourceKey): Boolean = namespaces[key.namespace]?.visible?.names?.get(kind)?.contains(key.path) == true

    /** The packs [namespace] (the project's own by default) has that this project can name, by id. */
    fun packs(namespace: String = home): Set<String> = namespaces[namespace]?.visible?.packs.orEmpty()

    /** Every name of [kind] in [namespace] (the project's own by default) that this project can name, sorted. */
    fun names(kind: RefKind, namespace: String = home): List<String> = namespaces[namespace]?.visible?.names?.get(kind).orEmpty().sorted()

    /** The namespaces of the packages this project depends on, sorted. */
    val packages: List<String> get() = namespaces.keys.filter { it != home }.sorted()

    /**
     * Why [text], a reference of [kind], names nothing, or null when it names
     * something (or is a file reference, which its kind's validator checks).
     */
    fun check(kind: RefKind, text: String): BrokenRef? {
        if (kind.scope == RefScope.FILE || kind.isGame(text)) return null
        if (kind.isDatapackTag(text)) return checkTag(kind, text)
        val key = resolve(kind, text)
            ?: return BrokenRef(ProblemCodes.REFERENCE_SYNTAX, "\"$text\" isn't a reference to ${kind.aNoun}: write ${kind.shape}")
        val all = namespaces[key.namespace] ?: return unknownNamespace(key.namespace, home)
        val namespace = all.visible
        if (key.path in namespace.names[kind].orEmpty()) return null
        if (key.path in all.names[kind].orEmpty()) {
            return notExported(
                key.namespace,
                if (kind.scope ==
                    RefScope.RESOURCE_PACK
                ) {
                    "resource pack \"${key.pack}\""
                } else {
                    "${kind.noun} \"${key.path}\""
                }
            )
        }
        val own = key.namespace == home
        return when (kind.scope) {
            RefScope.RESOURCE -> {
                val resource = Kinds.byId(kind.resourceKind!!)!!
                // A kind a datapack can define too says where either would be.
                val datapack = kind.registry?.let { ", or ${datapackFile(key, it)}" }.orEmpty()
                val where = if (own) " (${resource.pathOf(key.path)}$datapack)" else " in \"${key.namespace}\""
                // A bare id of a kind the game has too is likely the game's, written as if it were the project's.
                val game = if (kind.game && own && ResourceRef(text).namespace == null) {
                    "; the game's are written \"${GameIds.NAMESPACE}:${key.path}\""
                } else {
                    ""
                }
                BrokenRef(ProblemCodes.missing(kind), "There's no ${kind.noun} \"${key.path}\"$where$game")
            }
            RefScope.DATAPACK -> {
                val where = if (own) " (${datapackFile(key, kind.registry!!)})" else " in \"${key.namespace}\"'s datapacks"
                val game = if (kind.game && own && ResourceRef(text).namespace == null) {
                    "; the game's are written \"${GameIds.NAMESPACE}:${key.path}\""
                } else {
                    ""
                }
                BrokenRef(ProblemCodes.missing(kind), "There's no ${kind.noun} \"${key.path}\"$where$game")
            }
            RefScope.RESOURCE_PACK -> {
                val inNamespace = if (own) "" else " in \"${key.namespace}\""
                if (key.pack !in namespace.packs) {
                    BrokenRef(ProblemCodes.REFERENCE_RESOURCE_PACK, "There's no resource pack \"${key.pack}\"$inNamespace")
                } else {
                    val file = Kinds.byId(RESOURCE_PACK_KIND)!!.pathOf(key.pack)
                    // Sounds are files under sounds/ as much as entries in pack.json: point at the file.
                    val at = if (kind == RefKind.SOUND) null else "$.${kind.resourcePackEntries}"
                    val has = namespace.names[kind].orEmpty().filter {
                        it.startsWith("${key.pack}/")
                    }.map { it.substringAfter('/') }.sorted()
                    val hint = if (has.isEmpty()) "" else " (it has: ${has.joinToString()})"
                    BrokenRef(
                        ProblemCodes.REFERENCE_RESOURCE_PACK_KEY,
                        "Resource pack \"${key.pack}\"$inNamespace has no ${kind.noun} \"${key.key}\"$hint",
                        listOf(Location(if (own) file else PackagePaths.of(key.namespace, file), at))
                    )
                }
            }
            RefScope.FILE -> null
        }
    }

    /**
     * Why `#tag` [text] of [kind]'s [RefKind.tagRegistry] names nothing: it's in a namespace that is
     * neither the project's nor a package's, or no datapack there defines it (each namespace's tags are
     * its names of [kind] that start with `#`).
     */
    private fun checkTag(kind: RefKind, text: String): BrokenRef? {
        val registry = kind.tagRegistry!!
        val key = ResourceRef(text.removePrefix("#")).resolve(home)
            ?: return BrokenRef(ProblemCodes.REFERENCE_SYNTAX, "\"$text\" isn't a tag: write #<namespace>:<path>")
        val all = namespaces[key.namespace] ?: return unknownNamespace(key.namespace, home)
        val name = "#${key.path}"
        if (name in all.visible.names[kind].orEmpty()) return null
        if (name in all.names[kind].orEmpty()) return notExported(key.namespace, "datapack defining \"#$key\"")
        val where = if (key.namespace == home) {
            " (${DatapackLayout.entryFile(key, registry, tag = true)} in one of ${DatapackKind.folder}/)"
        } else {
            " in \"${key.namespace}\"'s datapacks"
        }
        return BrokenRef(ProblemCodes.missing(kind), "There's no ${kind.noun} tag \"#$key\"$where")
    }

    /** Why [use] names nothing, as [check] says, its message naming the tag for a glyph in text. */
    fun check(use: RefUse): BrokenRef? {
        val broken = check(use.kind, use.text) ?: return null
        if (use.start == null) return broken
        return BrokenRef(broken.code, "<${GlyphTags.NAME}:${use.text}>: ${broken.message}", broken.related)
    }

    /** Every reference to [target], from outside it (a pack's own textures don't count as its usages). */
    fun usagesOf(target: RefTarget): List<RefUse> {
        // A resource's own files are its, in whichever group folder it is.
        fun inside(file: String) = target is RefTarget.Resource &&
            Kinds.classify(file)?.let { it.pkg == null && it.kind == target.kind && it.id == target.id } == true
        return uses.filter { use -> matches(home, target, use.kind, use.target) && !inside(use.file) }
    }

    companion object {
        private const val RESOURCE_PACK_KIND = "resource_pack"

        /** Where the entry [key] of [registry] would be: `data/<ns>/<registry>/<path>.json` in one of the project's datapacks ([DatapackKind]). */
        private fun datapackFile(key: ResourceKey, registry: String) =
            "${DatapackLayout.entryFile(key, registry, tag = false)} in one of ${DatapackKind.folder}/"

        /**
         * Why a reference from [home] can't name anything in [namespace]:
         * it's neither [home]'s own nor a package [home] depends on. The one
         * message, for files and for what a script names alike.
         */
        fun unknownNamespace(namespace: String, home: String): BrokenRef = BrokenRef(
            ProblemCodes.REFERENCE_NAMESPACE,
            "\"$namespace\" is neither this project's namespace (\"$home\") nor a package's it depends on"
        )

        /**
         * Why package [namespace]'s [what] (`item "gem"`, `resource pack "ui"`) can't be
         * named from outside it: it isn't in its exports. The one message, for
         * files and for what a script names alike.
         */
        fun notExported(namespace: String, what: String): BrokenRef = BrokenRef(
            ProblemCodes.REFERENCE_NOT_EXPORTED,
            "Package \"$namespace\" doesn't export its $what, so only it can use it (its netherforge.json's exports)",
            listOf(Location(PackagePaths.of(namespace, ProjectManifest.FILE_NAME), "$.exports"))
        )

        internal fun resolve(home: String, kind: RefKind, text: String): ResourceKey? {
            val key = ResourceRef(text).resolve(home) ?: return null
            return key.takeIf { kind.isPath(it.path) }
        }

        /**
         * What a reference in [file] resolves to: a key for a resource or pack
         * entry, the project path for a file inside [file]'s resource.
         */
        internal fun targetOf(home: String, file: String, kind: RefKind, text: String): String? {
            if (kind.scope != RefScope.FILE) return resolve(home, kind, text)?.toString()
            return baseOf(file, kind)?.let { it + text }
        }

        /** Where [kind]'s file references in [file] are relative to: `resource_packs/ui/textures/`; null outside a folder resource. */
        private fun baseOf(file: String, kind: RefKind): String? {
            val at = Kinds.classify(file)?.takeIf { it.role == PathRole.MAIN } ?: return null
            val resource = Kinds.byId(at.kind ?: return null) ?: return null
            if (resource.mainFile == null) return null
            return resource.locationOf(at.id ?: return null, at.group) + "/" + kind.folder
        }

        /** Every reference [element], the document [file] of [kind], makes. */
        internal fun usesOf(home: String, file: String, kind: DocumentKind<*>, element: JsonElement): List<RefUse> =
            RefWalker.find(kind.serializer.descriptor, element).map { found ->
                RefUse(file, found.path, found.kind, found.text, targetOf(home, file, found.kind, found.text), found.start, found.end)
            }

        private fun isUnder(path: String, folder: String) = path == folder || path.startsWith("$folder/")

        /** Whether a reference of [kind] resolving to [resolved] names [target] (or something inside it). */
        internal fun matches(home: String, target: RefTarget, kind: RefKind, resolved: String?): Boolean {
            resolved ?: return false
            return when (target) {
                is RefTarget.Resource -> if (target.kind == RESOURCE_PACK_KIND) {
                    kind.scope == RefScope.RESOURCE_PACK && resolved.startsWith("$home:${target.id}/")
                } else {
                    kind.resourceKind == target.kind && resolved == "$home:${target.id}"
                }
                is RefTarget.ResourcePackEntry -> kind == target.entry && resolved == "$home:${target.pack}/${target.key}"
                is RefTarget.File -> kind.scope == RefScope.FILE && isUnder(resolved, target.path)
            }
        }

        /**
         * Document [text] (the file [file] of [kind]) with every reference to
         * [target] renamed to [to] (a resource's or entry's new id, or a file's
         * new project path), written as the file would: without the namespace
         * when it's [home]. Null when nothing in it names [target], or it
         * doesn't parse as JSON (the editor then leaves it, and validation
         * points at the stale reference).
         */
        fun rename(kind: DocumentKind<*>, file: String, text: String, home: String, target: RefTarget, to: String): String? {
            val element = runCatching { CanonicalJson.json.parseToJsonElement(text) }.getOrNull() ?: return null
            var changed = false
            val renamed = RefWalker.rewrite(kind.serializer.descriptor, element) { found ->
                val resolved = targetOf(home, file, found.kind, found.text)
                if (!matches(home, target, found.kind, resolved)) return@rewrite null
                renamedText(home, file, target, found.kind, resolved!!, to)?.also { changed = true }
            }
            return if (changed) canonical(kind, file, CanonicalJson.print(renamed)) else null
        }

        /**
         * [element], a document whose shape is [descriptor] written in
         * namespace [home], with every reference to a resource or pack entry
         * written in full (`ruby` is `shop:ruby`), so it names the same things
         * read from anywhere: how a package's resources are compiled to run
         * beside the project's. Files inside a resource are left as they are.
         */
        internal fun qualify(descriptor: SerialDescriptor, element: JsonElement, home: String): JsonElement =
            RefWalker.rewrite(descriptor, element) { found ->
                if (found.kind.scope == RefScope.FILE) return@rewrite null
                resolve(home, found.kind, found.text)?.toString()?.takeIf { it != found.text }
            }

        /**
         * Document [text] (the file [file] of [kind], from the resource
         * [self] of package [from]) as it must read once the resource is
         * copied into the project [to] under the id [id]: what named the
         * package's own things names them as `from:…`, what named [self] (or,
         * for a pack, anything in it) names the copy, and the rest is as it
         * was. Canonical; null when [text] doesn't parse as JSON.
         */
        fun move(
            kind: DocumentKind<*>,
            file: String,
            text: String,
            from: String,
            to: String,
            self: RefTarget.Resource,
            id: String
        ): String? {
            val element = runCatching { CanonicalJson.json.parseToJsonElement(text) }.getOrNull() ?: return null
            val moved = RefWalker.rewrite(kind.serializer.descriptor, element) { found ->
                if (found.kind.scope == RefScope.FILE) return@rewrite null
                val key = resolve(from, found.kind, found.text) ?: return@rewrite null
                val target = when {
                    self.kind == RESOURCE_PACK_KIND &&
                        found.kind.scope == RefScope.RESOURCE_PACK &&
                        key.namespace == from &&
                        key.pack == self.id ->
                        ResourceKey(to, "$id/${key.key}")
                    found.kind.resourceKind == self.kind && key == ResourceKey(from, self.id) -> ResourceKey(to, id)
                    else -> key
                }
                target.relativeTo(to).text.takeIf { it != found.text }
            }
            return canonical(kind, file, CanonicalJson.print(moved))
        }

        /** [text] in its canonical form, or as it is when it doesn't read as a [kind] (a document with errors in it). */
        private fun <T> canonical(kind: DocumentKind<T>, file: String, text: String): String = when (val parsed = kind.parse(text, file)) {
            is CanonicalJson.Parsed.Ok -> kind.write(parsed.value, file)
            is CanonicalJson.Parsed.Failed -> text + "\n"
        }

        private fun renamedText(home: String, file: String, target: RefTarget, kind: RefKind, resolved: String, to: String): String? =
            when (target) {
                is RefTarget.Resource -> {
                    val key = ResourceKey(
                        home,
                        if (target.kind ==
                            RESOURCE_PACK_KIND
                        ) {
                            "$to/" + resolved.substringAfter("$home:${target.id}/")
                        } else {
                            to
                        }
                    )
                    key.relativeTo(home).text
                }
                is RefTarget.ResourcePackEntry -> ResourceKey(home, "${target.pack}/$to").relativeTo(home).text
                is RefTarget.File -> {
                    val moved = to + resolved.removePrefix(target.path)
                    val base = baseOf(file, kind)
                    if (base != null && moved.startsWith(base)) moved.removePrefix(base) else null
                }
            }
    }
}
