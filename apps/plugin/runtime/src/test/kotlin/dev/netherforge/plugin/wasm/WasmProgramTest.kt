package dev.netherforge.plugin.wasm

import dev.netherforge.plugin.binding.LuaHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a program prints, cut into the lines scripts hear. */
class WasmProgramTest {
    private fun printed(maxLineBytes: Int, text: String): List<String> {
        val program = WasmProgram(1, LuaHandle.WasmProcess(1, "m"), 0, "t", "m", 100, maxLineBytes)
        program.LineOutput("stdout").apply {
            write(text.toByteArray(Charsets.UTF_8))
            finish()
        }
        return program.output.map { (it as ProgramOutput.Line).text }
    }

    @Test
    fun `lines end at LF or CRLF`() {
        assertEquals(listOf("one", "two", "three"), printed(64, "one\ntwo\r\nthree"))
    }

    @Test
    fun `a line longer than the limit is cut between characters, never through one`() {
        // A limit of 3 bytes would cut é (two bytes) in half: it starts the next piece instead.
        assertEquals(listOf("aa", "éb"), printed(3, "aaéb"))
        assertEquals(listOf("aaé", "b"), printed(4, "aaéb"))
        val pieces = printed(5, "ab€€€c")
        assertEquals("ab€€€c", pieces.joinToString(""))
        for (piece in pieces) assertEquals(false, '�' in piece, "no broken character in \"$piece\"")
        assertEquals(true, pieces.all { it.toByteArray(Charsets.UTF_8).size <= 5 })
        val emoji = printed(4, "x😀y")
        assertEquals("x😀y", emoji.joinToString(""))
    }

    @Test
    fun `whole characters is all of them unless the last is cut off`() {
        assertEquals(3, Utf8.wholeCharacters("abc".toByteArray()))
        val euro = "a€".toByteArray()
        assertEquals(4, Utf8.wholeCharacters(euro))
        assertEquals(1, Utf8.wholeCharacters(euro.copyOf(3)))
        assertEquals(1, Utf8.wholeCharacters(euro.copyOf(2)))
    }

    /** A program that doesn't read can't make the server keep everything a script sends it. */
    @Test
    fun `a program's input holds at most its limit of unread lines, and its end always fits`() {
        val input = WasmProgram.ProgramInput(3)
        assertEquals(listOf(true, true, true, false), (1..4).map { input.send("line $it") })
        assertTrue(input.finish(), "the end of its input has room")
        val read = input.readBytes().decodeToString()
        assertEquals("line 1\nline 2\nline 3\n", read)
        val standIn = StandInInput(2)
        assertEquals(listOf(true, true, false), (1..3).map { standIn.send("x") })
    }
}
