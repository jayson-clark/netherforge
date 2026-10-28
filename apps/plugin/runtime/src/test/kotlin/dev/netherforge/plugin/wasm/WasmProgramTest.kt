package dev.netherforge.plugin.wasm

import dev.netherforge.plugin.binding.LuaHandle
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
