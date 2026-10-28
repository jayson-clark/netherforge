package dev.netherforge.format.ref

import dev.netherforge.format.game.GameIds
import dev.netherforge.format.project.Names
import dev.netherforge.format.resourcepack.PackSounds
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Marks a property that refers to something else (a string, a list of
 * them, or a map whose values are): [RefWalker] finds every one in a document, with its JSON path, so
 * validation, find usages, rename and delete all follow a new reference by
 * its being marked. Glyph tags inside `@MiniMessage` text are found the same
 * way, as [RefKind.GLYPH].
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class Ref(val kind: RefKind)

/** Where what a [RefKind] names lives. */
enum class RefScope {
    /** A resource of its own (`items/ruby`), named by id: `ruby`, `acme:ruby`. */
    RESOURCE,

    /** An entry of a pack (a skin, a glyph…), named by pack and key: `ui/coin`, `acme:ui/coin`. */
    RESOURCE_PACK,

    /** A file inside the referring resource's own folder, by its path there: `gui/shop.png`, `script.lua`. */
    FILE,

    /**
     * An entry of one of the game's registries that a project's datapack defines (`datapacks/<id>/data/<ns>/worldgen/…`),
     * named by its id in the registry: `my_trees`, `acme:trees/oak`.
     */
    DATAPACK
}

/**
 * What a reference names. [RefScope.RESOURCE] kinds name a resource of kind
 * [resourceKind]; [RefScope.RESOURCE_PACK] kinds an entry of a pack's [resourcePackEntries]
 * (`pack.json`'s key, or the sounds under `sounds/`); [RefScope.FILE] kinds a
 * file under [folder] in the resource's own folder; [RefScope.DATAPACK] kinds an
 * entry of the game's registry [registry] a project datapack defines. A
 * [RefScope.RESOURCE] kind with a [registry] names those entries too (a biome:
 * `biomes/<id>.json`, or a datapack's `worldgen/biome`), so its paths may have
 * `/` in them as the registry's do.
 *
 * A kind that's [game] also names the game's own of its kind, written in the
 * game's namespace (`minecraft:plains`) or as a `#tag` (`#minecraft:is_forest`):
 * those aren't the project's to check, so the loader leaves them to the
 * kind's validator (against game data), and find usages and renames never
 * match them. Everything else is the project's or a package's, as for any kind.
 * A kind with a [tagRegistry] also names `#tags` a project datapack defines (`#showcase:soft`, under
 * `data/<ns>/tags/<tagRegistry>/`): a tag in the game's namespace (or none) is the game's, any other the
 * project's or a package's, checked like any reference. A kind with [any] takes that one word for "anything".
 */
@Serializable
enum class RefKind(
    val noun: String,
    val scope: RefScope,
    val resourceKind: String? = null,
    val resourcePackEntries: String? = null,
    val folder: String? = null,
    val game: Boolean = false,
    /** The game's registry, as a datapack's folder under `data/<ns>/` (`worldgen/biome`), whose entries a project datapack defines for it. */
    val registry: String? = null,
    /** The game's registry whose tags a project datapack defines and this kind names (`block`: `data/<ns>/tags/block/`). */
    val tagRegistry: String? = null,
    /** The word that stands for anything of the kind (`*`), which is the game's to understand. */
    val any: String? = null
) {
    @SerialName("item")
    ITEM("item", RefScope.RESOURCE, resourceKind = "item"),

    @SerialName("dialog")
    DIALOG("dialog", RefScope.RESOURCE, resourceKind = "dialog"),

    @SerialName("loot_table")
    LOOT_TABLE("loot table", RefScope.RESOURCE, resourceKind = "loot_table"),

    @SerialName("buff")
    BUFF("buff", RefScope.RESOURCE, resourceKind = "buff"),

    @SerialName("advancement")
    ADVANCEMENT("advancement", RefScope.RESOURCE, resourceKind = "advancement"),

    @SerialName("block")
    BLOCK("block", RefScope.RESOURCE, resourceKind = "block"),

    /**
     * A block an item's `canBreak` or `canPlaceOn` names: one of the project's (`ruby_ore`), the game's
     * (`minecraft:stone`), a `#tag` of blocks (the game's, or one a project datapack defines), or `*`.
     */
    @SerialName("adventure_block")
    ADVENTURE_BLOCK("block", RefScope.RESOURCE, resourceKind = "block", game = true, tagRegistry = "block", any = "*"),

    @SerialName("centity")
    CENTITY("centity", RefScope.RESOURCE, resourceKind = "centity"),

    @SerialName("terrain")
    TERRAIN("terrain", RefScope.RESOURCE, resourceKind = "terrain"),

    @SerialName("structure")
    STRUCTURE("structure", RefScope.RESOURCE, resourceKind = "structure"),

    @SerialName("biome")
    BIOME("biome", RefScope.RESOURCE, resourceKind = "biome", game = true, registry = "worldgen/biome"),

    /** A placed feature: the game's (`minecraft:trees_plains`), or one a project datapack defines. */
    @SerialName("placed_feature")
    PLACED_FEATURE("placed feature", RefScope.DATAPACK, game = true, registry = "worldgen/placed_feature"),

    @SerialName("dimension_type")
    DIMENSION_TYPE("dimension type", RefScope.RESOURCE, resourceKind = "dimension_type"),

    @SerialName("video")
    VIDEO("video", RefScope.RESOURCE, resourceKind = "video"),

    @SerialName("skin")
    SKIN("skin", RefScope.RESOURCE_PACK, resourcePackEntries = "skins"),

    @SerialName("glyph")
    GLYPH("glyph", RefScope.RESOURCE_PACK, resourcePackEntries = "glyphs"),

    @SerialName("item_model")
    ITEM_MODEL("item model", RefScope.RESOURCE_PACK, resourcePackEntries = "items"),

    @SerialName("tooltip")
    TOOLTIP("tooltip", RefScope.RESOURCE_PACK, resourcePackEntries = "tooltips"),

    @SerialName("equipment")
    EQUIPMENT("equipment look", RefScope.RESOURCE_PACK, resourcePackEntries = "equipment"),

    @SerialName("block_model")
    BLOCK_MODEL("block model", RefScope.RESOURCE_PACK, resourcePackEntries = "blocks"),

    @SerialName("sound")
    SOUND("sound", RefScope.RESOURCE_PACK, resourcePackEntries = "sounds"),

    /** An element of a resource pack's `hud` (a bar, a row of icons, a line of text), which scripts fill in. */
    @SerialName("hud")
    HUD("HUD element", RefScope.RESOURCE_PACK, resourcePackEntries = "hud"),

    @SerialName("texture")
    TEXTURE("texture", RefScope.FILE, folder = "textures/"),

    @SerialName("sound_file")
    SOUND_FILE("sound file", RefScope.FILE, folder = "sounds/"),

    /** A model file of a resource pack, by its path under `models/`. */
    @SerialName("model")
    MODEL("model", RefScope.FILE, folder = "models/"),

    @SerialName("script")
    SCRIPT("script", RefScope.FILE, folder = "");

    /** Whether [path] (what follows the namespace) has the shape this kind names: an id, or `<pack>/<key>`. */
    fun isPath(path: String): Boolean = when (scope) {
        RefScope.RESOURCE -> if (registry != null) ResourceRef.RESOURCE_PATH.matches(path) else Names.isId(path)
        RefScope.DATAPACK -> ResourceRef.RESOURCE_PATH.matches(path)
        RefScope.RESOURCE_PACK -> {
            val pack = path.substringBefore('/', "")
            val key = path.substringAfter('/', "")
            Names.isId(pack) && if (this == SOUND) PackSounds.isKey(key) else Names.isId(key)
        }
        RefScope.FILE -> true
    }

    /** Whether [text], a reference of this kind, names one of the game's own (see [game]). */
    fun isGame(text: String): Boolean {
        if (!game) return false
        if (any != null && text == any) return true
        if (text.startsWith("#")) {
            return tagRegistry == null ||
                ResourceRef(text.removePrefix("#")).namespace.let { it == null || it == GameIds.NAMESPACE }
        }
        return ResourceRef(text).namespace == GameIds.NAMESPACE
    }

    /** Whether [text] is a `#tag` of [tagRegistry] that isn't the game's: one a project datapack defines. */
    fun isDatapackTag(text: String): Boolean = tagRegistry != null && text.startsWith("#") && !isGame(text)

    /** [noun] with its article, for messages: `an item model`. */
    val aNoun: String get() = (if (noun.first() in "aeiou") "an " else "a ") + noun

    /** How a reference of this kind is written, for messages. */
    val shape: String
        get() = when (scope) {
            RefScope.RESOURCE, RefScope.DATAPACK ->
                "<id>, or <namespace>:<id> for another package's" +
                    (if (game) ", or ${GameIds.NAMESPACE}:<id> for the game's" else "") +
                    (if (tagRegistry != null) ", or a #tag" else "") +
                    (if (any != null) ", or $any for any" else "")
            RefScope.RESOURCE_PACK -> "<pack>/<key>, or <namespace>:<pack>/<key> for another package's"
            RefScope.FILE -> "a path inside the resource's folder"
        }
}
