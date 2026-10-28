package dev.netherforge.plugin.platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A chunk's positions to small numbers, a section at a time (what a chunk's custom blocks are kept as). */
class ChunkGridTest {
    @Test
    fun `a number reads back where it was set, below zero too, and clearing it counts`() {
        val grid = ChunkGrid()
        assertTrue(grid.isEmpty())
        assertEquals(0, grid.set(3, -60, 15, 7))
        grid[0, 319, 0] = 65535
        assertEquals(7, grid[3, -60, 15])
        assertEquals(65535, grid[0, 319, 0])
        assertEquals(0, grid[3, -59, 15])
        assertEquals(2, grid.size)
        assertEquals(7, grid.set(3, -60, 15, 0))
        assertEquals(1, grid.size)
        assertEquals(0, grid.set(1, 1, 1, 0), "clearing nothing is nothing")
        assertEquals(1, grid.size)
    }

    @Test
    fun `every position with a number is visited once, where it is`() {
        val grid = ChunkGrid()
        val set = mapOf(Triple(0, -64, 0) to 1, Triple(15, -49, 15) to 2, Triple(4, 70, 9) to 3, Triple(4, 71, 9) to 3)
        for ((at, value) in set) grid[at.first, at.second, at.third] = value
        val seen = HashMap<Triple<Int, Int, Int>, Int>()
        grid.forEach { x, y, z, value -> seen[Triple(x, y, z)] = value }
        assertEquals(set, seen)
    }

    @Test
    fun `a chunk full of numbers is a few kilobytes a section`() {
        val grid = ChunkGrid()
        for (y in -64 until 320) for (z in 0..15) for (x in 0..15) grid[x, y, z] = 1 + (x + z) % 3
        assertEquals(16 * 16 * 384, grid.size)
        assertEquals(2, grid[1, 100, 0])
    }
}
