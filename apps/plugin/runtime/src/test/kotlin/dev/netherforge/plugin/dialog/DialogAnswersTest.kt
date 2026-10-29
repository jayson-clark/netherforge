package dev.netherforge.plugin.dialog

import dev.netherforge.format.dialog.DialogFile
import dev.netherforge.format.dialog.DialogOption
import dev.netherforge.format.dialog.OptionInput
import dev.netherforge.format.dialog.RangeInput
import dev.netherforge.format.dialog.TextInput
import dev.netherforge.plugin.platform.DialogAnswers
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a player answered, as scripts get it: only what the dialog itself could have given. */
class DialogAnswersTest {
    private val file = DialogFile(
        title = "Shop",
        inputs = listOf(
            TextInput("name", maxLength = 5),
            TextInput("note"),
            OptionInput("size", options = listOf(DialogOption("small"), DialogOption("large"))),
            RangeInput("count", start = 1.0, end = 10.0)
        )
    )

    private fun answers(text: Map<String, String>, number: Map<String, Double>) = object : DialogAnswers {
        override fun text(key: String) = text[key]

        override fun bool(key: String): Boolean? = null

        override fun number(key: String) = number[key]
    }

    @Test
    fun `answers the dialog could give are passed on`() {
        assertEquals(
            mapOf("name" to "alex", "note" to "hi", "size" to "large", "count" to 3.0),
            Dialogs.values(file, answers(mapOf("name" to "alex", "note" to "hi", "size" to "large"), mapOf("count" to 3.0)))
        )
    }

    @Test
    fun `answers a modified client made up are left out`() {
        assertEquals(
            emptyMap(),
            Dialogs.values(
                file,
                answers(
                    mapOf("name" to "toolong", "note" to "x".repeat(33), "size" to "huge"),
                    mapOf("count" to 1e9)
                )
            )
        )
        assertEquals(emptyMap(), Dialogs.values(file, answers(emptyMap(), mapOf("count" to Double.NaN))))
    }
}
