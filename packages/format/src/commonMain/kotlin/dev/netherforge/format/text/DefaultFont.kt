package dev.netherforge.format.text

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.game.MinecraftVersion
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.menu.MenuType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `fonts/default.json`: the default font's glyph advances for one Minecraft
 * version, so the server can measure text ([TextWidth]).
 *
 * Only the game client knows them (they come from the font's bitmaps), and
 * the plugin never needs a client fact. So the editor reads them from the
 * imported client and writes them into the project as explicit data, the same
 * way it fits model hitboxes; it's committed and deployed with everything
 * else. It lives outside `.netherforge/`, which is neither committed nor read by
 * the plugin.
 */
@Serializable
data class DefaultFontFile(
    @SerialName("\$schema") val schema: String? = null,
    /** The Minecraft version whose client these were read from: `26.3`. */
    val minecraft: String,
    /**
     * Pixels the cursor moves after each character, gap included, by code
     * point (`"65": 6` is `A`). Characters the font draws from no bitmap or
     * space provider (unifont's) aren't here, and text using them can't be
     * measured.
     */
    val advances: Map<String, Int> = emptyMap()
) {
    /** A character's advance, or null when the font file doesn't cover it. */
    fun advance(codePoint: Int): Int? = advances[codePoint.toString()]

    companion object {
        const val FILE = "fonts/default.json"
        const val SCHEMA = "../.netherforge/schema/default_font.schema.json"
    }
}

object DefaultFontValidator {

    /**
     * Checks the file itself against the project's [target] version: a file
     * generated for another version is stale (its advances may have changed)
     * and the editor rewrites it once that version is imported.
     */
    fun validate(file: DefaultFontFile, sink: ProblemSink, target: String?) {
        val version = MinecraftVersion.parse(file.minecraft)
        if (version == null) {
            sink.report(ProblemCodes.FONT_MINECRAFT, "\"${file.minecraft}\" isn't a Minecraft version", "$.minecraft")
        } else if (target != null && MinecraftVersion.parse(target)?.let { it != version } == true) {
            sink.report(
                ProblemCodes.FONT_STALE,
                "These advances are Minecraft ${file.minecraft}'s, but the project targets $target: open the project in the editor " +
                    "with $target imported to regenerate them",
                "$.minecraft"
            )
        }
        for ((key, advance) in file.advances) {
            val at = CanonicalJson.childPath("$.advances", key)
            val code = key.toIntOrNull()
            if (code == null || code !in 0..MAX_CODE_POINT || key != code.toString()) {
                sink.report(ProblemCodes.FONT_CODE_POINT, "\"$key\" isn't a code point (a whole number, no leading zeros)", at)
            } else if (advance < 0) {
                sink.report(ProblemCodes.FONT_ADVANCE, "An advance can't be negative", at)
            }
        }
    }

    /** Whether [font] is there and was generated for [target]: what measuring text for the project needs. */
    fun isCurrent(font: DefaultFontFile?, target: String?): Boolean {
        if (font == null || target == null) return false
        val version = MinecraftVersion.parse(font.minecraft) ?: return false
        return MinecraftVersion.parse(target) == version
    }

    /**
     * Warns, at [at] in the file that needs it, that [why] needs the default
     * font's advances when [font] is missing or stale for [target]. For
     * anything that measures text on the server.
     */
    fun requireCurrent(font: DefaultFontFile?, target: String?, sink: ProblemSink, at: String?, why: String) {
        if (isCurrent(font, target)) return
        val state = if (font == null) "there's no ${DefaultFontFile.FILE}" else "${DefaultFontFile.FILE} is for Minecraft ${font.minecraft}"
        sink.report(
            ProblemCodes.FONT_NEEDED,
            "$why, but $state: open the project in the editor with Minecraft ${target ?: "(the target)"} imported to write it",
            at
        )
    }

    /**
     * A skinned window whose type centres its title (dispenser, dropper,
     * crafter) and that has title words: the server positions the skin from
     * the words' width, so without current advances it may be misaligned.
     */
    fun checkCentredTitle(
        type: MenuType,
        skin: String?,
        title: String?,
        font: DefaultFontFile?,
        target: String?,
        sink: ProblemSink,
        at: String?
    ) {
        if (!type.centresTitle || skin == null || title.isNullOrBlank()) return
        requireCurrent(font, target, sink, at, "A ${type.id} centres its title, so its skin is placed from the title's width")
    }

    private const val MAX_CODE_POINT = 0x10FFFF
}
