package dev.netherforge.format.project

import dev.netherforge.format.block.VanillaFile
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.text.DefaultFontFile
import kotlinx.serialization.KSerializer

/**
 * One kind of JSON document in a project: how it parses, and how it's
 * written canonically. Every resource kind whose resources are JSON is one
 * ([DocumentResourceKind]); so are the two project files, the manifest and
 * the default font.
 *
 * [canonical] puts a model into its one written form (keyframes in time
 * order, the `$schema` pointer set); [CanonicalJson.write] puts every map in
 * key order. Together, [write] never depends on the order anything was built in.
 */
interface DocumentKind<T> {
    /** Stable name used across the JS boundary and in the schema file name. */
    val id: String
    val serializer: KSerializer<T>

    /**
     * The `$schema` value written into every file of this kind; null for a
     * document no project holds (a bundle's manifest), which points at none.
     */
    val schemaRef: String?

    fun canonical(value: T): T

    fun parse(text: String, file: String): CanonicalJson.Parsed<T> = CanonicalJson.parse(serializer, text, file)

    fun write(value: T): String = CanonicalJson.write(serializer, canonical(value))

    /** [value] written as the file [path]: [write], with its `$schema` pointing at `.netherforge/` from that file's folder. */
    fun write(value: T, path: String): String = CanonicalJson.write(serializer, canonical(value), schemaAt(path))

    /**
     * The `$schema` of the file [path] (a project path, or a package path
     * read as one): [schemaRef] with a `../` for each folder it's in, so a
     * file in group folders (`blocks/(ores)/ruby/block.json`) still finds it.
     */
    fun schemaAt(path: String): String? = schemaRef?.let { ref ->
        var target = ref
        while (target.startsWith("../")) target = target.removePrefix("../")
        "../".repeat(PackagePaths.split(path).second.count { it == '/' }) + target
    }
}

/** `netherforge.json`: the project's manifest. */
object ManifestKind : DocumentKind<ProjectManifest> {
    override val id = "netherforge"
    override val serializer = ProjectManifest.serializer()
    override val schemaRef = ProjectManifest.SCHEMA

    // Managed worlds are a set: in name order, so two people adding one merge cleanly.
    override fun canonical(value: ProjectManifest) = value.copy(
        schema = schemaRef,
        managedWorlds = value.managedWorlds?.distinct()?.sorted(),
        // Only worlds that say something (the writer puts them in name order).
        worlds = value.worlds?.filterValues { !it.isEmpty }?.takeIf { it.isNotEmpty() },
        requires = value.requires?.let { it.copy(http = it.http?.distinct()?.sorted(), plugins = it.plugins?.distinct()?.sorted()) },
        allow = value.allow?.let { it.copy(permissions = it.permissions?.distinct()?.sorted()) },
        // Exports are sets of ids too.
        exports = value.exports?.mapValues { it.value.distinct().sorted() }
    )
}

/** `netherforge.lock`: the packages a project's dependencies resolved to, each pinned by version and content. */
object LockKind : DocumentKind<LockFile> {
    override val id = "lock"
    override val serializer = LockFile.serializer()
    override val schemaRef = LockFile.SCHEMA

    override fun canonical(value: LockFile) = value.copy(schema = schemaRef)
}

/** `netherforge-bundle.json`: what a bundle `netherforge build` wrote holds, at its top. */
object BundleKind : DocumentKind<BundleManifest> {
    override val id = "bundle"
    override val serializer = BundleManifest.serializer()

    // A bundle has no .netherforge/ to point a schema at: it's for a server, not an editor.
    override val schemaRef: String? = null

    override fun canonical(value: BundleManifest) = value
}

/** `fonts/default.json`: the default font's advances, which the editor writes from the imported client. */
object DefaultFontKind : DocumentKind<DefaultFontFile> {
    override val id = "default_font"
    override val serializer = DefaultFontFile.serializer()
    override val schemaRef = DefaultFontFile.SCHEMA

    override fun canonical(value: DefaultFontFile) = value.copy(schema = schemaRef)
}

/** `client/vanilla.json`: the game's own blockstate files of the blocks custom blocks are held in, which the editor writes from the imported client. */
object VanillaKind : DocumentKind<VanillaFile> {
    override val id = "vanilla"
    override val serializer = VanillaFile.serializer()
    override val schemaRef = VanillaFile.SCHEMA

    override fun canonical(value: VanillaFile) = value.copy(schema = schemaRef)
}
