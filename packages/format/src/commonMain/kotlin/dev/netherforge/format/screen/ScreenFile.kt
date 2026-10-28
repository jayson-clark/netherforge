package dev.netherforge.format.screen

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.Names
import dev.netherforge.format.ref.Ref
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.format.script.ScriptDef
import dev.netherforge.format.validate.Rules
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `screens/<id>/screen.json`: a screen made of maps. [width] by [height] maps, each [MapScreen.TILE] pixels a
 * side, which scripts draw on (pixels, shapes, text, pictures) and play videos on. Where it hangs is [place]: a
 * wall of invisible item frames the server puts there and keeps there, across restarts. A screen with no [place]
 * is still drawn on; a script hangs it (`screen:place`) or hands players its maps (`screen:item`).
 *
 * The pictures are the server's to send, a player at a time ([viewers]): nothing a screen shows is saved with the
 * world, so its [script] draws it whenever it starts.
 */
@Serializable
data class ScreenFile(
    @SerialName("\$schema") val schema: String? = null,
    /** Maps across, 1 to [MapScreen.MAX_TILES]. */
    val width: Int,
    /** Maps down, 1 to [MapScreen.MAX_TILES]. */
    val height: Int,
    /** What it's filled with before anything is drawn, `#rrggbb` (the nearest map colour); absent: nothing (clear). */
    val background: String? = null,
    /** Where it hangs, kept there by the server. */
    val place: ScreenPlace? = null,
    /** Who it's drawn for: every player near it ([ScreenViewers.NEAR], the default), or only those a script shows it to. */
    val viewers: ScreenViewers? = null,
    /** How near a player must be to be drawn it, in blocks (1 to [MapScreen.MAX_RANGE], default [MapScreen.DEFAULT_RANGE]). */
    val range: Int? = null,
    /** Whether its frames glow, drawing the picture at full brightness whatever the light (default true). */
    val glowing: Boolean? = null,
    /**
     * How far a player's click reaches it from, in blocks (0 to [MapScreen.MAX_CLICK_RANGE], default
     * [MapScreen.DEFAULT_CLICK_RANGE]): within the game's own reach a click on a frame always counts; past it, a
     * swing of the arm aimed at the screen does. 0 is the game's reach alone.
     */
    val clickRange: Double? = null,
    /** A video it plays from the start, over and over unless it says otherwise. */
    val video: ScreenVideo? = null,
    /** The screen's one script, with `this` the screen: it draws it and hears its clicks. */
    val script: ScriptDef? = null
) {
    val viewersOrDefault: ScreenViewers get() = viewers ?: ScreenViewers.NEAR
    val rangeOrDefault: Int get() = range ?: MapScreen.DEFAULT_RANGE
    val glowingOrDefault: Boolean get() = glowing ?: true
    val clickRangeOrDefault: Double get() = clickRange ?: MapScreen.DEFAULT_CLICK_RANGE

    companion object {
        const val FILE_NAME = "screen.json"
        const val SCHEMA = "../../.netherforge/schema/screen.schema.json"
    }
}

/**
 * Where a screen hangs: [world], and the block its top left map hangs in, as a player facing it sees it ([x],
 * [y], [z]: the air in front of the wall, where an item frame would go). [facing] is the way it faces, out of the
 * wall towards whoever looks at it; the rest of its maps go to that player's right and down.
 */
@Serializable
data class ScreenPlace(val world: String, val x: Int, val y: Int, val z: Int, val facing: ScreenFacing)

/** Which way a screen faces: the four walls. */
@Serializable
enum class ScreenFacing(
    /** The way out of the wall, towards a player looking at it. */
    val normalX: Int,
    val normalZ: Int
) {
    @SerialName("north")
    NORTH(0, -1),

    @SerialName("south")
    SOUTH(0, 1),

    @SerialName("east")
    EAST(1, 0),

    @SerialName("west")
    WEST(-1, 0);

    /** The way to the right of a player looking at the screen: the way its maps go across. */
    val rightX: Int get() = normalZ
    val rightZ: Int get() = -normalX
}

/** Who a screen is drawn for. */
@Serializable
enum class ScreenViewers {
    /** Every player within its range (of where it hangs; everyone online, for one that hangs nowhere). */
    @SerialName("near")
    NEAR,

    /** Only the players a script shows it to (`screen:show_to`). */
    @SerialName("script")
    SCRIPT
}

/** A video a screen plays from the start. */
@Serializable
data class ScreenVideo(
    @Ref(RefKind.VIDEO) val video: ResourceRef,
    /** Plays it again from the start when it ends (default true). */
    val loop: Boolean? = null,
    /** Its sound's volume, 0 to 1 (default 1); 0 plays it silent. */
    val volume: Double? = null
) {
    val loopOrDefault: Boolean get() = loop ?: true
    val volumeOrDefault: Double get() = volume ?: 1.0
}

/** The numbers every screen shares. */
object MapScreen {
    /** A map's side in pixels. */
    const val TILE = 128

    /** The most maps a screen may have along a side. */
    const val MAX_TILES = 16

    const val DEFAULT_RANGE = 64
    const val MAX_RANGE = 256

    const val DEFAULT_CLICK_RANGE = 24.0
    const val MAX_CLICK_RANGE = 64.0
}

object ScreenValidator {
    fun validate(file: ScreenFile, sink: ProblemSink, files: Set<String>?) {
        size(file.width, file.height, sink)
        file.background?.let { color(it, "$.background", sink) }
        file.place?.let { place ->
            if (!Names.isWorldName(place.world)) {
                sink.report(ProblemCodes.SCREEN_WORLD, "\"${place.world}\" isn't a world name", "$.place.world")
            }
        }
        file.range?.let {
            if (it !in 1..MapScreen.MAX_RANGE) {
                sink.report(ProblemCodes.SCREEN_RANGE, "range is 1 to ${MapScreen.MAX_RANGE} blocks, not $it", "$.range")
            }
        }
        file.clickRange?.let {
            if (it.isNaN() || it < 0 || it > MapScreen.MAX_CLICK_RANGE) {
                sink.report(
                    ProblemCodes.SCREEN_RANGE,
                    "clickRange is 0 to ${MapScreen.MAX_CLICK_RANGE.toInt()} blocks, not ${CanonicalJson.formatNumber(it.toString())}",
                    "$.clickRange"
                )
            }
        }
        file.video?.volume?.let {
            if (it.isNaN() ||
                it < 0 ||
                it > 1
            ) {
                sink.report(
                    ProblemCodes.SCREEN_VOLUME,
                    "volume is 0 to 1, not ${CanonicalJson.formatNumber(it.toString())}",
                    "$.video.volume"
                )
            }
        }
        file.script?.let { Rules.script(it, "$.script", sink, files) }
    }

    /** A screen's (or a video's) size in maps. */
    fun size(width: Int, height: Int, sink: ProblemSink) {
        for ((name, value) in listOf("width" to width, "height" to height)) {
            if (value !in 1..MapScreen.MAX_TILES) {
                sink.report(ProblemCodes.SCREEN_SIZE, "$name is 1 to ${MapScreen.MAX_TILES} maps, not $value", "$.$name")
            }
        }
    }

    fun color(text: String, at: String, sink: ProblemSink) {
        if (MapPalette.parse(text) == null) {
            sink.report(ProblemCodes.SCREEN_COLOR, "\"$text\" isn't a colour written #rrggbb, like \"#202030\"", at)
        }
    }
}
