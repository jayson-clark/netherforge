package dev.netherforge.format.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PixelsTest {
    @Test
    fun equalPixelsAreEqualPictures() {
        val a = Pixels(2, 1, intArrayOf(-1, 0))
        assertEquals(a, Pixels(2, 1, intArrayOf(-1, 0)))
        assertEquals(a.hashCode(), Pixels(2, 1, intArrayOf(-1, 0)).hashCode())
        assertNotEquals(a, Pixels(1, 2, intArrayOf(-1, 0)))
        assertNotEquals(a, Pixels(2, 1, intArrayOf(-1, 1)))
    }

    @Test
    fun resizingAveragesByAreaAndAlpha() {
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        // Two pixels into one: half each.
        assertEquals(0xFF800080.toInt(), Pixels(2, 1, intArrayOf(red, blue)).resized(1, 1)[0, 0])
        // A transparent pixel adds no colour, only transparency.
        assertEquals(0x80FF0000.toInt(), Pixels(2, 1, intArrayOf(red, 0x000000FF)).resized(1, 1)[0, 0])
        // Larger: each old pixel spread over the new ones it covers.
        val wide = Pixels(2, 1, intArrayOf(red, blue)).resized(4, 1)
        assertEquals(listOf(red, red, blue, blue), (0 until 4).map { wide[it, 0] })
        // Three into two: the middle one shared.
        val three = Pixels(3, 1, intArrayOf(red, red, blue)).resized(2, 1)
        assertEquals(red, three[0, 0])
        assertEquals(0xFF5500AA.toInt(), three[1, 0])
    }
}
