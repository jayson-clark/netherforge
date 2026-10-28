package dev.netherforge.format.resourcepack

import dev.netherforge.format.menu.MenuType
import dev.netherforge.format.project.Names
import dev.netherforge.format.ref.Ref
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.ResourceRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `resource_packs/<namespace>/pack.json`: named pictures scripts and other files can
 * point at, built into a Minecraft resource pack.
 *
 * The folder name **is** the pack's Minecraft asset namespace, so `ui:forest`
 * in a menu is literally the key the client resolves; there is no
 * mapping table anywhere. Textures are PNGs under `resource_packs/<namespace>/textures/`
 * and are referenced by their path below that folder (`gui/forest.png`).
 *
 * Sounds are `.ogg` files under `resource_packs/<namespace>/sounds/`: each one is the
 * sound event `<namespace>:<path without .ogg>`, with nothing to declare.
 * [sounds] only refines events (variants, volume, pitch, a subtitle).
 *
 * The kinds are keyed objects: every reference is `<pack>:<key>`.
 */
@Serializable
data class ResourcePackFile(
    @SerialName("\$schema") val schema: String? = null,
    val name: String? = null,
    /** Shown in the client's pack screen. */
    val description: String? = null,
    /** GUI backgrounds drawn behind menus. */
    val skins: Map<String, SkinDef> = emptyMap(),
    /** Small pictures usable in any text. */
    val glyphs: Map<String, GlyphDef> = emptyMap(),
    /** Custom item appearances (`itemModel` on an item). */
    val items: Map<String, ItemModelDef> = emptyMap(),
    /** Custom tooltip frames (`tooltipStyle` on an item). */
    val tooltips: Map<String, TooltipDef> = emptyMap(),
    /** Armour and equipment looks (`equipment` on an item): what a worn item is drawn as. */
    val equipment: Map<String, EquipmentAssetDef> = emptyMap(),
    /** Cube looks for custom blocks (`model` on a block). */
    val blocks: Map<String, BlockModelDef> = emptyMap(),
    /**
     * Sound events that need more than their one file, keyed by the event's
     * key (`menu/open`). Every `.ogg` under `sounds/` is already an event.
     * A key in the game's namespace (`minecraft:block.stone.break`) replaces
     * that event of the game's, for everything that plays it, with its `files`.
     */
    val sounds: Map<String, SoundDef> = emptyMap(),
    /**
     * The game's own textures to draw with the pack's pictures instead, for every player (blocks, items, mobs, the
     * screens): the texture's name under `textures/` (`block/stone`, `gui/container/inventory`), without `.png`, to a
     * texture of the pack of the game's shape, or for a screen's sheet its exact size ([ReplacedTexture]).
     */
    @Ref(RefKind.TEXTURE) val textures: Map<String, String> = emptyMap(),
    /**
     * The game's own models to draw with the pack's model files instead, for every player: the model's name under
     * `models/` (`block/red_mushroom`, `item/lantern`), without `.json`, to a model file of the pack (`mushroom.json`, a
     * path under its `models/` folder). Every block or item drawn with the model takes its shape.
     */
    val models: Map<String, String> = emptyMap(),
    /** What it does to the HUD: the game's elements it hides, and elements of its own scripts fill in ([HudDef]). */
    val hud: HudDef? = null
) {
    companion object {
        const val FILE_NAME = "pack.json"
        const val SCHEMA = "../../.netherforge/schema/resource_pack.schema.json"
        const val TEXTURES = "textures"
        const val SOUNDS = "sounds"

        const val TEXTURE_EXTENSION = ".png"

        /**
         * A texture's path under `textures/` (`gui/shop.png`): a relative path
         * that stays inside the folder ([Names.isRelativeFile]), to a PNG.
         */
        val TEXTURE_PATH = Regex("^(${Names.SEGMENT_BODY}/)*${Names.SEGMENT_BODY}\\.png$")
        const val TEXTURE_PATH_RULE =
            "a $TEXTURE_EXTENSION path of letters, digits, _, - and . inside this resource pack's $TEXTURES/ folder"

        fun isTexturePath(path: String): Boolean = TEXTURE_PATH.matches(path)

        /** A model file's path in a pack's folder, from its path under `models/`. */
        fun modelFile(path: String) = "${PackModels.FOLDER}/$path"
    }
}

/**
 * A whole GUI background. Minecraft won't let a server choose a container's
 * texture, but it does let it choose the title, so a skin is a very tall
 * character in a font of the pack's own, negatively spaced to start at the
 * window's left edge, put at the front of the title.
 */
@Serializable
data class SkinDef(
    /** Path under `textures/`. */
    @Ref(RefKind.TEXTURE) val texture: String,
    /** GUI pixels. A six-row chest is 222 tall. */
    val height: Int? = null,
    /** Pixels from the picture's top to its baseline; can't exceed [height]. */
    val ascent: Int? = null,
    /** How far left drawing starts, in GUI pixels. Default -8: flush with the window's edge. */
    val offset: Int? = null,
    /** Editor hint: the container it was drawn for. */
    val type: MenuType? = null,
    /** Editor hint: the chest rows it was drawn for. */
    val rows: Int? = null
) {
    companion object {
        const val DEFAULT_HEIGHT = 256
        const val DEFAULT_ASCENT = 13
        const val DEFAULT_OFFSET = -8
    }
}

/** A small picture merged into the default font, so it works in names, lore, chat and dialogs. */
@Serializable
data class GlyphDef(
    @Ref(RefKind.TEXTURE) val texture: String,
    /** GUI pixels; 8 is a line of text. */
    val height: Int? = null,
    val ascent: Int? = null
) {
    companion object {
        const val DEFAULT_HEIGHT = 8
        const val DEFAULT_ASCENT = 7
    }
}

/**
 * One custom item look, reached through the `item_model` component; no vanilla item is replaced.
 * One of: a flat picture ([texture]), a pack block's look ([block]), drawn the way the game
 * draws a block item (in 3D, in slots and in the hand), or a [model] of its own shape.
 */
@Serializable
data class ItemModelDef(
    @Ref(RefKind.TEXTURE) val texture: String? = null,
    /** A pack's block look (`ui/ruby_ore`) this item is drawn as, instead of a [texture]. */
    @Ref(RefKind.BLOCK_MODEL) val block: ResourceRef? = null,
    /**
     * A model file under `models/` (`crystal.json`): the game's JSON model with elements, as Blockbench
     * exports one, its textures in this pack's `textures/` ([PackModels]). Its own `display` transforms say
     * how it's held, worn and shown in slots and on a centity.
     */
    @Ref(RefKind.MODEL) val model: String? = null,
    /** How a [texture] is held. Default [ItemModelParent.GENERATED]. */
    val parent: ItemModelParent? = null,
    /** A different picture used only in GUIs. */
    @Ref(RefKind.TEXTURE) val guiTexture: String? = null,
    /**
     * Model files (`crystal_pull.json`) for the stages of drawing the item, from the first pull to a full draw, as a bow
     * is drawn: while it's in use the first is shown, and each later one from its share of the first second of use
     * (the last from a full draw). Once it's drawn, the look above is shown again. Needs at least one.
     */
    @Ref(RefKind.MODEL) val drawn: List<String>? = null
) {
    companion object {
        /** The game's `use_duration` property scales ticks used by this: 0.05 makes one second (20 ticks) a value of 1. */
        const val DRAW_SCALE = 0.05
    }
}

/** The vanilla item models a custom item model builds on. */
@Serializable
enum class ItemModelParent {
    /** A flat sprite. */
    @SerialName("minecraft:item/generated")
    GENERATED,

    /** Held like a tool. */
    @SerialName("minecraft:item/handheld")
    HANDHELD
}

/**
 * How a custom block's cube is drawn, reached through a block's `model`: one
 * [texture] for every face, or a texture for some faces and the rest from
 * [side] and [texture]. Each face takes the first of: its own, [side] for the
 * four sides, [texture]. The block's breaking particles take the first of
 * [texture], [side] and [top] that's set.
 */
@Serializable
data class BlockModelDef(
    /**
     * A model file under `models/` (`crystal.json`), for a block that isn't a plain cube, instead of
     * textures. A block held in a note block hides its neighbours' faces as a full cube does, so a model
     * that isn't one wants another `carrier` (see the block format).
     */
    @Ref(RefKind.MODEL) val model: String? = null,
    /** Path under `textures/`: every face that has no texture of its own. */
    @Ref(RefKind.TEXTURE) val texture: String? = null,
    @Ref(RefKind.TEXTURE) val top: String? = null,
    @Ref(RefKind.TEXTURE) val bottom: String? = null,
    /** The four sides that have no texture of their own. */
    @Ref(RefKind.TEXTURE) val side: String? = null,
    @Ref(RefKind.TEXTURE) val north: String? = null,
    @Ref(RefKind.TEXTURE) val south: String? = null,
    @Ref(RefKind.TEXTURE) val east: String? = null,
    @Ref(RefKind.TEXTURE) val west: String? = null
) {
    /** The texture on [face] (one of [FACES]), or null when nothing says. */
    fun faceTexture(face: String): String? = when (face) {
        "up" -> top ?: texture
        "down" -> bottom ?: texture
        "north" -> north ?: side ?: texture
        "south" -> south ?: side ?: texture
        "east" -> east ?: side ?: texture
        "west" -> west ?: side ?: texture
        else -> null
    }

    /** Each face's texture, or null when some face has none. */
    fun faces(): Map<String, String>? {
        val all = FACES.associateWith { faceTexture(it) }
        return if (all.values.any { it == null }) null else all.mapValues { it.value!! }
    }

    /** The texture breaking it throws up, or null when it has none at all. */
    fun particle(): String? = texture ?: side ?: top ?: bottom ?: north ?: south ?: east ?: west

    /** Every texture it names, each once, in a fixed order. */
    fun textures(): Set<String> = listOfNotNull(texture, top, bottom, side, north, south, east, west).toCollection(LinkedHashSet())

    companion object {
        /** The model's faces, as the game names them. */
        val FACES = listOf("down", "up", "north", "south", "west", "east")
    }
}

/** A tooltip frame and/or backing, both nine-slice sprites. */
@Serializable
data class TooltipDef(@Ref(RefKind.TEXTURE) val background: String? = null, @Ref(RefKind.TEXTURE) val frame: String? = null)

/**
 * How an item looks worn, reached through an item's `equipment` (the
 * `equippable` component's asset). Each layer is a texture drawn on the
 * wearer; a layer left out draws nothing for that kind of wearer, so give the
 * ones the item's slot is drawn with: [humanoid] for a helmet, chestplate or
 * boots (and [humanoidLeggings] for leggings) on a player or a mob that wears
 * armour, [wings] for an elytra-style chest piece, [horseBody] and
 * [wolfBody] for the body slot of those animals.
 */
@Serializable
data class EquipmentAssetDef(
    /** The armour on a humanoid's head, chest and feet. */
    @Ref(RefKind.TEXTURE) val humanoid: String? = null,
    /** The armour on a humanoid's legs. */
    @Ref(RefKind.TEXTURE) val humanoidLeggings: String? = null,
    /** Wings on the wearer's back. */
    @Ref(RefKind.TEXTURE) val wings: String? = null,
    /** Armour on a horse's body. */
    @Ref(RefKind.TEXTURE) val horseBody: String? = null,
    /** Armour on a wolf's body. */
    @Ref(RefKind.TEXTURE) val wolfBody: String? = null
) {
    /** Each layer set, by the folder the game draws it from (`humanoid_leggings`), in a fixed order. */
    fun layers(): List<Pair<String, String>> = listOfNotNull(
        humanoid?.let { "humanoid" to it },
        humanoidLeggings?.let { "humanoid_leggings" to it },
        wings?.let { "wings" to it },
        horseBody?.let { "horse_body" to it },
        wolfBody?.let { "wolf_body" to it }
    )
}

/**
 * One sound event, beyond what its file alone says. Without [files] it plays
 * `sounds/<key>.ogg`; with them, one of them at random each time.
 */
@Serializable
data class SoundDef(
    /** Paths under `sounds/` (`step/grass_1.ogg`), picked from at random. */
    @Ref(RefKind.SOUND_FILE) val files: List<String>? = null,
    /** 1 is the file as recorded. Above 1 it carries further, not louder. */
    val volume: Double? = null,
    /** 1 is the file as recorded; the game clamps what it plays to 0.5–2. */
    val pitch: Double? = null,
    /** Stream the files from disk instead of loading them whole: for long tracks (music). Off unless set. */
    val stream: Boolean? = null,
    /** What players with subtitles on read when it plays: a translation key or plain text. */
    val subtitle: String? = null
)
