package dev.netherforge.format.datapack

import dev.netherforge.format.ref.ResourceKey

/**
 * One file of a project datapack (`datapacks/<id>/…`), placed: the [overlay] it's in (null for the pack's own
 * `data/`), and the registry entry or tag it is. [file] is its path inside the datapack's folder.
 */
data class DatapackPath(
    val file: String,
    val overlay: String?,
    val namespace: String,
    /** The registry, as its folder under `data/<namespace>/` (`worldgen/placed_feature`), or under `tags/` for a tag. */
    val registry: String,
    /** The entry's (or tag's) path in its namespace: `trees/oak`. */
    val path: String,
    /** Whether it's a tag of [registry]'s entries (`data/<namespace>/tags/worldgen/…`) rather than an entry. */
    val tag: Boolean
) {
    val key: ResourceKey get() = ResourceKey(namespace, path)

    /** Where it goes in a datapack without overlays: what it replaces or adds to. */
    val target: String get() = DatapackLayout.entryFile(key, registry, tag)
}

/**
 * Where things are in a datapack, as the game lays one out: `data/<namespace>/<registry>/<path>.json` for an entry,
 * `data/<namespace>/tags/<registry>/<path>.json` for a tag, and the same under an overlay's folder. Only worldgen
 * passes through: a registry is a folder of `worldgen/`, which every supported version keeps there (what's in it
 * changes between versions, which is the game's to say; see [DatapackPath]), and besides it the tags of a few
 * other registries ([TAG_REGISTRIES]), which items' `canBreak` and `canPlaceOn` name.
 */
object DatapackLayout {
    /** The folder every pack and overlay keeps its files in. */
    const val DATA = "data"

    /** The folder of the game's worldgen registries, under a namespace (and under `tags/` for their tags). */
    const val WORLDGEN = "worldgen"

    private const val TAGS = "tags"
    private const val JSON = ".json"

    /** Registries outside worldgen whose tags pass through, by their folder under `tags/`: blocks, for items' `canBreak`. */
    val TAG_REGISTRIES = setOf("block")

    /** A namespace as the game takes one. */
    private val NAMESPACE = Regex("^[a-z0-9_.-]+$")

    /** One folder of a path as the game takes one. */
    private val SEGMENT = Regex("^[a-z0-9_.-]+$")

    /** An overlay's folder name, as the game takes one. */
    val OVERLAY = Regex("^[a-z0-9_.-]+$")

    /** `data/<namespace>/<registry>/<path>.json`, or under `tags/` for a tag. */
    fun entryFile(key: ResourceKey, registry: String, tag: Boolean): String =
        "$DATA/${key.namespace}/${if (tag) "$TAGS/" else ""}$registry/${key.path}$JSON"

    /** What placing [file] (a path in a datapack's folder, not `pack.mcmeta`) gives: where it is, or why it can't be one of its files. */
    sealed interface Placed {
        data class Ok(val path: DatapackPath) : Placed

        data class Wrong(val message: String) : Placed
    }

    /** [file], a path inside a datapack's folder, as one of its files, with [overlays] its overlays' folders. */
    fun place(file: String, overlays: Set<String>): Placed {
        val parts = file.split('/')
        val (overlay, rest) = when {
            parts.firstOrNull() == DATA -> null to parts.drop(1)
            parts.size > 1 && parts[0] in overlays && parts[1] == DATA -> parts[0] to parts.drop(2)
            else -> return Placed.Wrong(
                "Only $DATA/ and the overlays pack.mcmeta lists" +
                    (if (overlays.isEmpty()) "" else " (${overlays.sorted().joinToString()})") + " hold a datapack's files"
            )
        }
        val namespace = rest.firstOrNull().orEmpty()
        if (!NAMESPACE.matches(namespace)) return Placed.Wrong("\"$namespace\" isn't a namespace: lowercase letters, digits, _, - and .")
        val inNamespace = rest.drop(1)
        val tag = inNamespace.firstOrNull() == TAGS
        val registryAt = if (tag) inNamespace.drop(1) else inNamespace
        // A tag of one of TAG_REGISTRIES is `tags/<registry>/<path>.json`; everything else is worldgen's, a folder deeper.
        val other = tag && registryAt.firstOrNull() in TAG_REGISTRIES && registryAt.size >= 2
        if (!other && (registryAt.firstOrNull() != WORLDGEN || registryAt.size < 3)) {
            return Placed.Wrong(
                "Only worldgen passes through, and tags of blocks: entries go in $DATA/$namespace/$WORLDGEN/<registry>/, " +
                    "tags of them in $DATA/$namespace/$TAGS/$WORLDGEN/<registry>/, and " +
                    TAG_REGISTRIES.sorted().joinToString { "$it tags in $DATA/$namespace/$TAGS/$it/" }
            )
        }
        val depth = if (other) 1 else 2
        val registry = if (other) registryAt[0] else "$WORLDGEN/${registryAt[1]}"
        val last = registryAt.last()
        if (!last.endsWith(JSON) || last.length == JSON.length) return Placed.Wrong("A datapack's files are .json")
        val path = (registryAt.drop(depth).dropLast(1) + last.removeSuffix(JSON)).joinToString("/")
        if (!SEGMENT.matches(registryAt[depth - 1]) || !path.split('/').all(SEGMENT::matches)) {
            return Placed.Wrong("\"$path\" isn't an id the game takes: lowercase letters, digits, _, - and ., in folders")
        }
        return Placed.Ok(DatapackPath(file, overlay, namespace, registry, path, tag))
    }

    /** Every file of a datapack with [files] (paths in its folder) and [meta] that is one, placed; the rest left out. */
    fun entries(meta: PackMeta, files: Collection<String>): List<DatapackPath> {
        val overlays = overlaysOf(meta)
        return files.filter { it != PackMeta.FILE_NAME }.mapNotNull { (place(it, overlays) as? Placed.Ok)?.path }
    }

    /** The folders [meta]'s overlays are in. */
    fun overlaysOf(meta: PackMeta): Set<String> = meta.overlays?.entries.orEmpty().map { it.directory }.toSet()
}
