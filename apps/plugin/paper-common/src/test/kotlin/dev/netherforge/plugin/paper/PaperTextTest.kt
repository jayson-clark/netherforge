package dev.netherforge.plugin.paper

import dev.netherforge.format.centity.TextDisplay
import dev.netherforge.format.centity.TextRun
import dev.netherforge.format.resourcepack.PackFonts
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** `<space:N>` and `<at>` as the adapter parses them. */
class PaperTextTest {
    @AfterTest
    fun unbind() {
        PaperText.spaces = { null }
        PaperText.layout = { it }
    }

    private fun plain(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)

    private fun texts(component: Component): List<TextComponent> =
        listOfNotNull(component as? TextComponent) + component.children().flatMap(::texts)

    @Test
    fun `an image's text is built from its runs, a coloured piece each, not parsed`() {
        val runs = listOf(TextRun("ab", 0xFF0000), TextRun(PackFonts.offset(10)), TextRun("\n"), TextRun("c", 0x00FF00))
        val shown = PaperText.displayText(TextDisplay("<red>ignored", runs = runs))
        assertEquals(listOf("ab", PackFonts.offset(10), "\n", "c"), shown.children().map { (it as TextComponent).content() })
        assertEquals(listOf(TextColor.color(0xFF0000), null, null, TextColor.color(0x00FF00)), shown.children().map { it.color() })
        // Text without runs is MiniMessage, as always.
        assertEquals("hi", plain(PaperText.displayText(TextDisplay("<red>hi"))))
    }

    @Test
    fun `a space is the pack's characters, never bold, and nothing before the runtime says what they are`() {
        assertEquals("ab", plain(PaperText.mini.deserialize("a<space:5>b")))
        PaperText.spaces = { PackFonts.offset(it) }
        val parsed = PaperText.mini.deserialize("<bold>a<space:-3>b")
        assertEquals("a" + PackFonts.offset(-3) + "b", plain(parsed))
        val space = texts(parsed).single { it.content() == PackFonts.offset(-3) }
        assertEquals(TextDecoration.State.FALSE, space.decoration(TextDecoration.BOLD))
    }

    @Test
    fun `every text is laid out before it's parsed, and an at only scopes what it holds`() {
        PaperText.spaces = { PackFonts.offset(it) }
        PaperText.layout = { it.replace("<at:4>", "<space:4><at:4>").replace("</at>", "<space:-10></at>") }
        assertEquals(PackFonts.offset(4) + "x" + PackFonts.offset(-10) + "y", plain(PaperText.mini.deserialize("<at:4>x</at>y")))
        // Stripped (nf.text.strip), the moves go with the tags.
        assertEquals("xy", PaperText.mini.stripTags("<at:4>x</at>y"))
    }
}
