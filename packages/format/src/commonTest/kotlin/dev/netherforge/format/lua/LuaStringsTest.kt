package dev.netherforge.format.lua

import kotlin.test.Test
import kotlin.test.assertEquals

/** The string literals of Lua source, as Lua reads them. */
class LuaStringsTest {
    @Test
    fun quotesEscapesAndLongBracketsAreRead() {
        val source = """
            local a = "minecraft:stone" -- "not this"
            local b = 'it''s' .. "a\"b\n" .. "\65\x42\u{43}"
            --[[ "nor this"
              ]] local c = [[
            long]] .. [==[with ]] in it]==]
            local d = "a\z
                       b"
        """.trimIndent()
        assertEquals(listOf("minecraft:stone", "it", "s", "a\"b\n", "ABC", "long", "with ]] in it", "ab"), LuaStrings.literals(source))
    }
}
