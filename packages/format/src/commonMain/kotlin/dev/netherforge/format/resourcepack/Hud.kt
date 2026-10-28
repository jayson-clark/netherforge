package dev.netherforge.format.resourcepack

import dev.netherforge.format.ref.Ref
import dev.netherforge.format.ref.RefKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `pack.json`'s `hud`: what the pack does to the HUD, the game's own elements it hides for every player, and
 * elements of its own that scripts fill in per player (`player:hud()`): bars of a value out of a max, rows of
 * icons, and lines of text.
 *
 * The pack's elements are drawn in the action bar, the line above the hotbar, as characters of a font of the
 * pack's own (`<ns>:<pack>/hud`): each bitmap's ascent puts it at its height on the screen, and moves of the
 * cursor ([PackFonts.offset]) put it across, so the whole line moves the cursor nowhere and the game's centring
 * leaves every element where it says. Positions are GUI pixels: [HudBarDef.x] from the middle of the screen
 * (negative to the left) to the element's left edge, [HudBarDef.y] from the bottom of the screen up to its
 * bottom edge, from 0 to [MAX_Y] (the highest a glyph's ascent can raise it above the action bar's line). The
 * game's own rows are at these heights: the hotbar's top at 22, the experience bar 24 to 29, hearts and hunger 30
 * to 39, armour 40 to 49.
 */
@Serializable
data class HudDef(
    /** The game's own HUD elements to hide, for every player of the project. */
    val hide: List<HudElement>? = null,
    /**
     * The game's GUI sprites to draw with the pack's own pictures instead, for every player: the sprite's name under
     * `textures/gui/sprites/` (`hud/hotbar`), without `.png`, to a texture of the pack. A `.mcmeta` beside the picture
     * (nine-slice scaling) comes along; without one the sprite is drawn stretched.
     */
    @Ref(RefKind.TEXTURE) val replace: Map<String, String> = emptyMap(),
    /** Bars, by key: a value out of a max, drawn as segments. */
    val bars: Map<String, HudBarDef> = emptyMap(),
    /** Rows of icons, by key: which of its icons show is a script's to say. */
    val icons: Map<String, HudIconsDef> = emptyMap(),
    /** Lines of text, by key, drawn on the action bar's own line in the game's font. */
    val texts: Map<String, HudTextDef> = emptyMap()
) {
    /** Every key, of every kind of element: what scripts name them by (`<pack>/<key>`). */
    val keys: Set<String> get() = bars.keys + icons.keys + texts.keys

    companion object {
        /** How far above the screen's bottom the highest element's bottom edge can be. */
        const val MAX_Y = 65

        /** Where a glyph with an ascent of 0 has its bottom edge, above the screen's bottom, less its height: the action bar's line. */
        const val BASELINE = 65

        /** How far either way an element can be from the middle of the screen. */
        const val MAX_X = 1000

        /** The most segments a bar has. */
        const val MAX_SEGMENTS = 64
    }
}

/**
 * One of the game's own HUD elements, which a pack can hide: [sprites] are the prefixes of the game's GUI sprites
 * (`textures/gui/sprites/`) that draw it, which the built pack replaces with nothing. The game's names for its own
 * sprites, the one fact about them this needs; which sprites there are comes from the player's client
 * (`client/vanilla.json`).
 */
@Serializable
enum class HudElement(val sprites: List<String>) {
    /** The hearts, every kind of them (poisoned, frozen, a mount's). */
    @SerialName("health")
    HEALTH(listOf("hud/heart/")),

    /** The hunger bar. */
    @SerialName("food")
    FOOD(listOf("hud/food_")),

    /** The armour bar. */
    @SerialName("armor")
    ARMOR(listOf("hud/armor_")),

    /** The air bubbles, under water. */
    @SerialName("air")
    AIR(listOf("hud/air")),

    /** The experience bar. Its level is drawn as text, which shows while the level is above 0. */
    @SerialName("experience")
    EXPERIENCE(listOf("hud/experience_bar_"));

    /** Whether the game's sprite [sprite] (`hud/heart/full`) is one that draws this. */
    fun draws(sprite: String): Boolean = sprites.any(sprite::startsWith)
}

/**
 * A bar: [segments] pictures in a row, each [full], [half] or [empty] by how much of the max the value is (half
 * segments only with a [half] picture), filling from the left (from the right with [reverse]). Each segment takes
 * the width of the widest picture, and [gap] pixels between them. A script can show fewer of its segments (a row
 * of hearts as long as the player's max life), and [align] says which part of what's drawn is at [x].
 */
@Serializable
data class HudBarDef(
    @Ref(RefKind.TEXTURE) val full: String,
    @Ref(RefKind.TEXTURE) val empty: String,
    @Ref(RefKind.TEXTURE) val half: String? = null,
    val segments: Int,
    val x: Int,
    val y: Int,
    /** Pixels between segments. Default 0. */
    val gap: Int? = null,
    /** Fill from the right. Default false. */
    val reverse: Boolean? = null,
    /** Which part of the segments drawn is at [x]: their left edge, middle or right edge. Default [HudAlign.LEFT]. */
    val align: HudAlign? = null
)

/**
 * A row of icons, of which a script shows any, in the order it says, [gap] pixels apart; [align] says which part
 * of the icons shown is at [x].
 */
@Serializable
data class HudIconsDef(
    /** The icons it can show, by key: each a picture. */
    @Ref(RefKind.TEXTURE) val icons: Map<String, String>,
    val x: Int,
    val y: Int,
    /** Pixels between icons. Default 1. */
    val gap: Int? = null,
    /** Which part of the icons shown is at [x]: their left edge, middle or right edge. Default [HudAlign.LEFT]. */
    val align: HudAlign? = null
)

/** A line of text on the action bar's own line, in the game's font, which scripts set (MiniMessage, glyphs too). */
@Serializable
data class HudTextDef(
    /** Where it's anchored across, from the middle of the screen: its left edge, middle or right edge, by [align]. */
    val x: Int,
    /** Which part of the text is at [x]. Default [HudAlign.LEFT]. */
    val align: HudAlign? = null
)

@Serializable
enum class HudAlign {
    @SerialName("left")
    LEFT,

    @SerialName("center")
    CENTER,

    @SerialName("right")
    RIGHT
}
