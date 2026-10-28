package dev.netherforge.format.datapack

import dev.netherforge.format.biome.BiomeJson
import dev.netherforge.format.biome.BiomeKind
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.project.Kinds
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.ResourceKey
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.format.sky.SkyKind
import dev.netherforge.format.sky.SkyTwins
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The datapack NetherForge generates when the server starts: what the game
 * reads from its registries only while it loads them (advancements, and the dialogs
 * of the pause screen and quick actions, structures that generate, biomes and dimension types), which no API adds to a running
 * server. The plugin builds it before the worlds load and hands it to the
 * server; a change to anything in it needs a restart, so the plugin builds
 * it again after a reload to tell whether one is needed.
 *
 * It's built from what the project's files say alone, never from game data
 * (there's none that early), so building it again later from the same files
 * gives the same bytes. A kind puts files in it through [KindSpec.datapack].
 */
object StartupDatapack {
    /** What the server knows the pack by. */
    const val ID = "netherforge"

    /** The kinds whose resources go into it: a change to one of theirs needs a restart. */
    val kinds: List<KindSpec<*, *>> get() = Kinds.all.filter { it.datapack != null || it.datapackAll != null }

    /**
     * Every file of the pack, by path, sorted: `pack.mcmeta` with [format]
     * (the server's data pack format, `[major, minor]`) and each running
     * resource's files, the packages' included, in their own namespaces.
     * Empty when no resource puts anything in it: then there's no pack.
     * [text] turns MiniMessage into the game's JSON text. [mainWorld] is the
     * name of the server's main world (`level-name`), whose dimension type the
     * pack replaces when `netherforge.json` names one for it; null for none.
     * Without [passThrough], the project's datapacks ([dev.netherforge.format.datapack.DatapackKind]) are left out,
     * and so is everything of NetherForge's that names what they define: how a server that refused them starts
     * without them. The skies [leaveOut] says yes to have no twin biomes in it: how a server that refused theirs
     * starts with the others.
     */
    fun build(
        snapshot: ProjectSnapshot,
        format: List<Int>,
        text: TextJson,
        mainWorld: String? = null,
        passThrough: Boolean = true,
        game: GameDatapack = GameDatapack.NONE,
        leaveOut: (sky: ResourceKey) -> Boolean = { false }
    ): Map<String, DatapackEntry> {
        val context = DatapackContext(snapshot, format, text, mainWorld, passThrough, game, leaveOut)
        val files = HashMap<String, DatapackEntry>()
        for (kind in kinds) add(kind, snapshot, context, files)
        if (files.isEmpty()) return emptyMap()
        val name = snapshot.manifest?.name?.takeIf { it.isNotBlank() } ?: snapshot.namespace
        files[MCMETA] = DatapackEntry.json(
            buildJsonObject {
                putJsonObject("pack") {
                    put("description", "NetherForge: $name")
                    put("min_format", JsonArray(format.map { JsonPrimitive(it) }))
                    put("max_format", JsonArray(format.map { JsonPrimitive(it) }))
                }
            }
        )
        return files.entries.sortedBy { it.key }.associate { it.key to it.value }
    }

    /**
     * The project (or package) file that puts the entry [element] (`showcase:rocks`) of the game's [registry]
     * (`minecraft:worldgen/placed_feature`) in the pack for a server of [format]: a datapack's file, or a resource
     * that writes it ([KindSpec.datapackEntries]); null when nothing does. Where the server's own complaint about an
     * entry belongs.
     */
    fun sourceOf(snapshot: ProjectSnapshot, format: List<Int>, registry: String, element: String): String? {
        val folder = registry.substringAfter(':')
        val key = ResourceKey.parse(element) ?: return null
        if (folder == BiomeJson.FOLDER) {
            SkyTwins.skyOf(snapshot.namespace, key)?.let { sky -> return skyFile(snapshot, sky) }
        }
        val target = DatapackLayout.entryFile(key, folder, tag = false)
        for ((name, datapack) in snapshot.running(DatapackKind)) {
            val path = datapack.filesFor(PackFormat.of(format))[target] ?: continue
            return snapshot.placeOf(DatapackKind, name).file(path.file)
        }
        for (kind in Kinds.all) sourceIn(kind, snapshot, folder, key)?.let { return it }
        return null
    }

    /** The file of the sky [sky] names, the project's or a package's; null when there's none. */
    private fun skyFile(snapshot: ProjectSnapshot, sky: ResourceKey): String? {
        for ((name, loaded) in snapshot.everywhere(SkyKind)) {
            if (ResourceRef(name).resolve(snapshot.namespace) == sky) return SkyKind.pathOf(name, loaded.group)
        }
        return null
    }

    private fun <T> sourceIn(kind: KindSpec<T, *>, snapshot: ProjectSnapshot, folder: String, key: ResourceKey): String? {
        for ((name, loaded) in snapshot.everywhere(kind)) {
            val namespace = ResourceRef(name).resolve(snapshot.namespace)?.namespace ?: continue
            if (namespace != key.namespace) continue
            val id = name.substringAfter(':')
            if (key.path in kind.datapackEntries(id, loaded.value)[folder].orEmpty()) return kind.pathOf(name, loaded.group)
        }
        return null
    }

    private fun <C> add(
        kind: KindSpec<*, C>,
        snapshot: ProjectSnapshot,
        context: DatapackContext,
        into: MutableMap<String, DatapackEntry>
    ) {
        val running = snapshot.running(kind)
        kind.datapack?.let { files ->
            for ((name, value) in running) {
                for ((path, entry) in files.files(context.key(name), value, context)) {
                    check(into.put(path, entry) == null) { "two resources put $path in the start-up datapack" }
                }
            }
        }
        kind.datapackAll?.let { all ->
            for ((path, entry) in all.files(running.mapKeys { (name, _) -> context.key(name) }, context)) {
                check(into.put(path, entry) == null) { "two resources put $path in the start-up datapack" }
            }
        }
    }

    private const val MCMETA = "pack.mcmeta"
}

/**
 * What the game's own data pack (in the server's jar) has that a kind builds on: its [biomes] (each its JSON, by
 * key), and the [overworld]'s dimension type. The server hands it over as it starts (the start-up datapack is built
 * before anything else of the game is there); without one ([NONE]: the editor, a test) nothing is built on it.
 */
class GameDatapack(val biomes: Map<ResourceKey, JsonObject>, val overworld: JsonObject?) {
    companion object {
        val NONE = GameDatapack(emptyMap(), null)
    }
}

/** One file of the start-up datapack. */
sealed interface DatapackEntry {
    /** A file written out: JSON, as text. */
    data class Text(val text: String) : DatapackEntry

    /** A project file copied in as it is (a structure's `.nbt`), by project or package path. */
    data class Copy(val projectPath: String) : DatapackEntry

    companion object {
        /** [element] as a file: one way of printing it, so the same JSON is always the same bytes. */
        fun json(element: JsonElement): DatapackEntry = Text(CanonicalJson.print(element) + "\n")
    }
}

/** What a kind puts in the start-up datapack for one running resource. */
fun interface DatapackFiles<C> {
    /** The files for the resource [key] names (`shop:first_steps`), whose compiled form is [value], by path in the pack. */
    fun files(key: ResourceKey, value: C, ctx: DatapackContext): Map<String, DatapackEntry>
}

/** What a kind puts in the start-up datapack for all its running resources together. */
fun interface DatapackCollection<C> {
    /** The files for [resources] (each by its key, `shop:welcome`), by path in the pack. */
    fun files(resources: Map<ResourceKey, C>, ctx: DatapackContext): Map<String, DatapackEntry>
}

/**
 * MiniMessage as the game's JSON text component (what a datapack's text
 * fields hold), from the server's own serializer, so it's in the form the
 * running version reads. [glyph] answers a `<glyph:…>` tag's reference with
 * its character, or null for none.
 */
fun interface TextJson {
    fun json(text: String, glyph: (reference: String) -> String?): JsonElement
}

/** What a kind's [DatapackFiles] can ask about the project while the pack is built. */
class DatapackContext internal constructor(
    val snapshot: ProjectSnapshot,
    /**
     * The server's data pack format, `[major, minor]`: where the game's
     * format differs between the versions NetherForge supports, a kind writes
     * the one this server reads.
     */
    val format: List<Int>,
    private val textJson: TextJson,
    /** The server's main world's name, when the pack is built for a server that says (null: none is known). */
    val mainWorld: String? = null,
    /** Whether the project's datapacks go in (see [StartupDatapack.build]). */
    val passThrough: Boolean = true,
    /** What the server's own data pack says, for a kind that builds on it ([GameDatapack]). */
    val game: GameDatapack = GameDatapack.NONE,
    private val leaveOut: (sky: ResourceKey) -> Boolean = { false }
) {
    /** Whether the sky [sky] names is left out of this pack (see [StartupDatapack.build]). */
    fun leftOut(sky: ResourceKey): Boolean = leaveOut(sky)

    /** [format] as a data pack format, to compare with a datapack's. */
    val packFormat: PackFormat get() = PackFormat.of(format)

    private val passed = HashMap<String, Set<ResourceKey>>()

    /**
     * The entries of [registry] (`worldgen/placed_feature`) the project's datapacks and its packages' put in this
     * pack, for this format: none without [passThrough].
     */
    fun passedThrough(registry: String): Set<ResourceKey> = passed.getOrPut(registry) {
        if (!passThrough) return@getOrPut emptySet()
        snapshot.running(DatapackKind).values.flatMap { it.filesFor(packFormat).values }
            .filter { !it.tag && it.registry == registry }.map { it.key }.toSet()
    }

    /**
     * Every biome this pack has: the project's (and its packages') that go in it ([BiomeJson.writes]) and the
     * datapacks'. A structure naming any other would make the game refuse the pack.
     */
    val biomes: Set<ResourceKey> by lazy {
        snapshot.running(BiomeKind).filter { (_, file) -> BiomeJson.writes(file, this) }.keys.map(::key).toSet() +
            passedThrough(BiomeJson.FOLDER)
    }

    /** Whether the server's data pack format is [major] (and [minor]) or later. */
    fun formatAtLeast(major: Int, minor: Int = 0): Boolean {
        val have = format.getOrElse(0) { 0 }
        return have > major || (have == major && format.getOrElse(1) { 0 } >= minor)
    }

    /** The project's namespace: what a running resource's name and its references resolve in. */
    val home: String get() = snapshot.namespace

    /** A running resource's name (`first_steps`, `acme:quest`) as its key. */
    fun key(name: String): ResourceKey = requireNotNull(ResourceRef(name).resolve(home)) { "\"$name\" isn't a resource's name" }

    /** [ref] as the key it names; a running resource's references are written so they resolve here. */
    fun resolve(ref: ResourceRef): ResourceKey = requireNotNull(ref.resolve(home)) { "\"$ref\" isn't a reference" }

    /** MiniMessage [text] as the game's JSON text, its glyph tags drawn as the project's packs build them. */
    fun text(text: String): JsonElement = textJson.json(text, ::glyph)

    /** The character a `<glyph:…>` tag's [reference] draws, from the packs as they compiled; null for none. */
    private fun glyph(reference: String): String? {
        val key = ResourceRef(reference).resolve(home)?.takeIf { RefKind.GLYPH.isPath(it.path) } ?: return null
        val pack = snapshot.compiledResourcePacks[ResourceKey(key.namespace, key.pack).relativeTo(home).text] ?: return null
        return pack.glyphs[key.key]?.char
    }
}
