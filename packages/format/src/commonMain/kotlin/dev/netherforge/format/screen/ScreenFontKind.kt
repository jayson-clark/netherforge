package dev.netherforge.format.screen

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.game.GameData
import dev.netherforge.format.project.DocumentResourceKind
import dev.netherforge.format.project.KindContext
import dev.netherforge.format.project.Layout
import dev.netherforge.format.project.ResourceContext

/**
 * `screen_fonts/<id>/font.json`: a font screens write text in: drawn in the file, or from a picture or a font file
 * in its folder.
 */
object ScreenFontKind : DocumentResourceKind<ScreenFontFile, ScreenFontFile>(
    "screen_font",
    "screen_fonts",
    Layout.Folder(ScreenFontFile.FILE_NAME),
    ScreenFontFile.serializer(),
    ScreenFontFile.SCHEMA
) {
    override fun canonical(value: ScreenFontFile) = value.copy(schema = schemaRef)

    override fun validate(value: ScreenFontFile, ctx: ResourceContext) {
        ScreenFontValidator.validate(value, ctx.sink, ctx.files)
        if (ctx.id == ScreenFontFile.DEFAULT) {
            ctx.sink.report(
                ProblemCodes.SCREEN_FONT_ID,
                "\"${ScreenFontFile.DEFAULT}\" is the game's own map font: call this one something else",
                "$"
            )
        }
    }

    /** A bitmap font's picture must cut into its grid exactly: what only the picture's size says. */
    override fun crossCheck(value: ScreenFontFile, ctx: KindContext) {
        val file = value.file
        if (value.type != ScreenFontType.BITMAP || !ctx.readsSizes || file == null || file !in ctx.files) return
        val (across, down) = ScreenFontValidator.grid(value) ?: return
        val size = ctx.size(ctx.placeOf(ScreenFontKind, ctx.id).file(file)) ?: return
        val (width, height) = size[0] to size[1]
        if (width % across != 0 || height % down != 0) {
            ctx.sink.report(
                ProblemCodes.SCREEN_FONT_GRID,
                "The picture is $width by $height pixels, which doesn't cut into $across characters across and $down rows",
                "$.chars"
            )
        }
    }

    override fun compile(id: String, value: ScreenFontFile, ctx: ResourceContext) = value

    /** The digits, three pixels by five: a pixels font to draw more characters into. */
    override fun template(id: String, game: GameData?) = mapOf(
        pathOf(id) to write(ScreenFontFile(type = ScreenFontType.PIXELS, glyphs = DIGITS))
    )

    private val DIGITS = mapOf(
        "0" to listOf("###", "#.#", "#.#", "#.#", "###"),
        "1" to listOf(".#.", "##.", ".#.", ".#.", "###"),
        "2" to listOf("###", "..#", "###", "#..", "###"),
        "3" to listOf("###", "..#", ".##", "..#", "###"),
        "4" to listOf("#.#", "#.#", "###", "..#", "..#"),
        "5" to listOf("###", "#..", "###", "..#", "###"),
        "6" to listOf("###", "#..", "###", "#.#", "###"),
        "7" to listOf("###", "..#", ".#.", ".#.", ".#."),
        "8" to listOf("###", "#.#", "###", "#.#", "###"),
        "9" to listOf("###", "#.#", "###", "..#", "###")
    )
}
