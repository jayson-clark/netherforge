package dev.netherforge.plugin.paper

import dev.netherforge.format.text.GlyphTags
import dev.netherforge.format.text.SpaceTags
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer

/**
 * The one MiniMessage every part of the adapter parses with: the standard
 * tags plus `<glyph:ui/coin>`, which inserts a pack glyph's character.
 *
 * MiniMessage splits tag arguments at `:`, so `<glyph:shop:ui/coin>` arrives
 * as `shop`, `ui/coin` and `<glyph:'shop:ui/coin'>` as `shop:ui/coin`; joined
 * with `:` they're the same reference ([GlyphTags]). The glyph goes in the default font (the
 * pack merges glyphs into it, and the text around it may use another) and in
 * white, so the surrounding colour doesn't tint the picture, and never bold or
 * obfuscated. An unknown one inserts nothing; the runtime reports it.
 *
 * `<space:N>` inserts the pack's space characters (the default font holds
 * them), never bold (a bold space is a pixel wider) or obfuscated (which
 * swaps characters for others as wide). `<at>` only scopes what it holds:
 * before parsing, every text goes through the runtime's [layout], which puts
 * the moves that place it around it ([SpaceTags]).
 */
object PaperText {
    /** Set by the runtime through `Platform.bind`. */
    @Volatile
    var glyphs: (reference: String) -> String? = { null }

    /** Set by the runtime through `Platform.bindText`. */
    @Volatile
    var spaces: (px: Int) -> String? = { null }

    /** Set by the runtime through `Platform.bindText`. */
    @Volatile
    var layout: (text: String) -> String = { it }

    val mini: MiniMessage = parser({ glyphs(it) }, { spaces(it) }) { layout(it) }

    /**
     * MiniMessage [text] as the game's JSON text component, in the form this
     * server's datapacks read (its own Adventure's serializer), with glyph
     * tags drawn as [glyph] answers them: what the start-up datapack holds.
     */
    fun json(text: String, glyph: (reference: String) -> String?): String =
        GsonComponentSerializer.gson().serialize(parser(glyph, { spaces(it) }) { layout(it) }.deserialize(text))

    private fun parser(glyph: (reference: String) -> String?, space: (px: Int) -> String?, layout: (text: String) -> String): MiniMessage {
        fun arguments(args: ArgumentQueue): List<String> {
            val parts = mutableListOf<String>()
            while (args.hasNext()) parts += args.pop().value()
            return parts
        }
        val glyphTag = TagResolver.resolver(GlyphTags.NAME) { args, _ ->
            val char = glyph(arguments(args).joinToString(":"))
            Tag.selfClosingInserting(
                if (char == null) {
                    Component.empty()
                } else {
                    // A picture, maybe in pieces with moves between them: bold would widen each piece and
                    // obfuscated swap it for another picture.
                    Component.text(char).font(Style.DEFAULT_FONT).color(NamedTextColor.WHITE)
                        .decoration(TextDecoration.BOLD, false)
                        .decoration(TextDecoration.OBFUSCATED, false)
                }
            )
        }
        val spaceTag = TagResolver.resolver(SpaceTags.SPACE) { args, _ ->
            val chars = SpaceTags.space(arguments(args))?.let(space)
            Tag.selfClosingInserting(
                if (chars.isNullOrEmpty()) {
                    Component.empty()
                } else {
                    Component.text(chars).font(Style.DEFAULT_FONT)
                        .decoration(TextDecoration.BOLD, false)
                        .decoration(TextDecoration.OBFUSCATED, false)
                }
            )
        }
        // Placed by [layout] before parsing; the tag itself only scopes what it holds.
        val atTag = TagResolver.resolver(SpaceTags.AT) { args, _ ->
            while (args.hasNext()) args.pop()
            Tag.styling()
        }
        return MiniMessage.builder()
            .editTags { it.resolver(glyphTag).resolver(spaceTag).resolver(atTag) }
            .preProcessor { layout(it) }
            .build()
    }
}
