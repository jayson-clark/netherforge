@file:JsExport

package dev.netherforge.format

import dev.netherforge.format.centity.CentityValidator
import dev.netherforge.format.centity.DisplayDef
import dev.netherforge.format.centity.TextDisplay
import dev.netherforge.format.editor.BlockPreview
import dev.netherforge.format.editor.FitResult
import dev.netherforge.format.editor.GlyphPreview
import dev.netherforge.format.editor.ResourcePackPreview
import dev.netherforge.format.editor.ResourcePacksPreview
import dev.netherforge.format.editor.SkinPreview
import dev.netherforge.format.editor.StyledChar
import dev.netherforge.format.editor.StyledOverlay
import dev.netherforge.format.editor.StyledText
import dev.netherforge.format.editor.TextLayout
import dev.netherforge.format.editor.TextLine
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.ImageInfo
import dev.netherforge.format.resourcepack.CompiledResourcePack
import dev.netherforge.format.resourcepack.ResourcePackKind
import dev.netherforge.format.text.MiniMessagePass
import dev.netherforge.format.text.TextMetrics
import dev.netherforge.format.text.TextStyle
import kotlinx.serialization.builtins.serializer

// Text and the resource packs it's drawn with: the server's skins and glyphs, the game's line breaking and widths.

/**
 * Every pack's skins and glyphs with the characters and placement the server
 * will use, from `{ pack id: pack.json text }` in a project whose namespace
 * is [namespace], so previews draw exactly what a player sees: a [ResourcePacksPreview].
 *
 * [imagesJson] is `{ projectPath: ImageInfo }` for the textures the editor
 * could measure (null for none). A skin or glyph whose picture isn't there
 * gets `advance: null`, and a skin's `titlePrefix` then has no way back, as
 * on a server that can't read it.
 */
fun compileResourcePacks(namespace: String, packsJson: String, imagesJson: String?): String {
    val texts = CanonicalJson.json.decodeFromString<Map<String, String>>(packsJson)
    val images = imagesJson?.let { CanonicalJson.json.decodeFromString<Map<String, ImageInfo>>(it) }.orEmpty()
    val problems = mutableListOf<Problem>()
    val files = texts.mapNotNull { (id, text) ->
        when (val parsed = ResourcePackKind.parse(text, ResourcePackKind.pathOf(id))) {
            is CanonicalJson.Parsed.Failed -> {
                problems += parsed.problem
                null
            }
            is CanonicalJson.Parsed.Ok -> id to parsed.value
        }
    }.toMap()
    if (problems.isNotEmpty()) return encode(ResourcePacksPreview.serializer(), ResourcePacksPreview(emptyMap(), problems))
    val compiled = CompiledResourcePack.compileAll(namespace, files) { images[it] }
    val packs = compiled.mapValues { (_, pack) ->
        ResourcePackPreview(
            pack.skins.mapValues { (_, s) -> SkinPreview(s.char, s.titlePrefix, s.height, s.ascent, s.offset, s.advance, s.def.texture) },
            pack.glyphs.mapValues { (_, g) -> GlyphPreview(g.char, g.height, g.ascent, g.advance, g.def.texture) },
            pack.blocks.mapValues { (_, b) -> BlockPreview(b.faces(), b.particle()) }
        )
    }
    return encode(ResourcePacksPreview.serializer(), ResourcePacksPreview(packs, emptyList()))
}

/**
 * The hitbox that fits a node's display ([displayJson], a `DisplayDef`) when
 * it's a `fixed` text display, measured with the default font's [advancesJson]
 * (`{ "<code point>": advance }`, from the client import) and the pack
 * glyphs' advances [glyphsJson] (`{ "ui/coin": 9 }`, by reference as tags write it):
 * a [FitResult], whose box is null for anything else.
 */
fun fitTextHitbox(displayJson: String, advancesJson: String?, glyphsJson: String?): String {
    val display = CanonicalJson.json.decodeFromString(DisplayDef.serializer(), displayJson)
    val box = (display as? TextDisplay)?.let { metrics(advancesJson, glyphsJson).box(it) }
    return encode(FitResult.serializer(), FitResult(box))
}

/**
 * How the game lays out MiniMessage [text], measured as in [fitTextHitbox]
 * and wrapped at [lineWidth] pixels (0: no wrapping): a [TextLayout], each
 * line's width in pixels and the range of [text] it shows (tags included),
 * for previews that must wrap exactly where the game does.
 */
fun layoutText(text: String, lineWidth: Int, advancesJson: String?, glyphsJson: String?): String {
    val lines = metrics(advancesJson, glyphsJson).lines(text, lineWidth)
    return encode(TextLayout.serializer(), TextLayout(lines.map { TextLine(it.width, it.start, it.end) }))
}

/**
 * MiniMessage [text] as the characters a preview draws, each with its style
 * and where it is in [text] (so [layoutText]'s line ranges cut them), a glyph
 * tag as one picture, a `<space>` as a move and an `<at>` as where an overlay
 * starts and ends: a [StyledText]. Line breaks and other tags draw nothing.
 */
fun styleText(text: String): String {
    val chars = MiniMessagePass.pieces(text).mapNotNull { piece ->
        when (piece) {
            is MiniMessagePass.Char -> StyledChar(piece.text, null, piece.at, piece.style)
            is MiniMessagePass.Glyph -> StyledChar("", piece.reference, piece.at, piece.style)
            is MiniMessagePass.Break -> null
            is MiniMessagePass.Move -> StyledChar("", null, piece.at, TextStyle(), move = piece.px)
            is MiniMessagePass.Overlay -> StyledChar(
                "",
                null,
                piece.at,
                TextStyle(),
                overlay = piece.spec.let { StyledOverlay(it.x, it.from.name.lowercase(), it.align.name.lowercase()) }
            )
            is MiniMessagePass.OverlayEnd -> StyledChar("", null, piece.at, TextStyle(), overlayEnd = true)
        }
    }
    return encode(StyledText.serializer(), StyledText(chars))
}

/**
 * What a hitbox fitted to [displayJson] (a `DisplayDef`) records in
 * `fittedTo`, or null for a display nothing can be fitted to. The validator
 * warns when this stops matching.
 */
fun fitKey(displayJson: String): String? =
    CentityValidator.fitKey(CanonicalJson.json.decodeFromString(DisplayDef.serializer(), displayJson))

private fun metrics(advancesJson: String?, glyphsJson: String?): TextMetrics {
    val advances = advancesJson?.let { CanonicalJson.json.decodeFromString<Map<String, Int>>(it) }.orEmpty()
    val glyphs = glyphsJson?.let { CanonicalJson.json.decodeFromString<Map<String, Int>>(it) }.orEmpty()
    return TextMetrics(glyph = { glyphs[it] }) { code -> advances[code.toString()] }
}
