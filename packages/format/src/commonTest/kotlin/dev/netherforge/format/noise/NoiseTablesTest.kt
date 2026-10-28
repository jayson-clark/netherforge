package dev.netherforge.format.noise

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The port's lookup tables, by what the reference's are: a typo in one of their 2048 numbers might move a few
 * samples by less than `FastNoiseLiteTest`'s 230 reference points notice, but not past these.
 */
class NoiseTablesTest {
    private fun lengths(table: DoubleArray, stride: Int, dims: Int) = (table.indices step stride).map { i ->
        sqrt((0 until dims).sumOf { table[i + it] * table[i + it] })
    }

    @Test
    fun theTablesAreTheReferencesSizes() {
        assertEquals(256, NoiseTables.gradients2D.size)
        assertEquals(512, NoiseTables.randVecs2D.size)
        assertEquals(256, NoiseTables.gradients3D.size)
        assertEquals(1024, NoiseTables.randVecs3D.size)
    }

    @Test
    fun twoDimensionalVectorsAreUnitLength() {
        for (table in listOf(NoiseTables.gradients2D, NoiseTables.randVecs2D)) {
            lengths(table, 2, 2).forEachIndexed { i, length -> assertTrue(abs(length - 1) < 1e-9, "vector $i is $length long") }
        }
    }

    @Test
    fun threeDimensionalOnesArePaddedToFourWithAZero() {
        for (table in listOf(NoiseTables.gradients3D, NoiseTables.randVecs3D)) {
            for (i in 3 until table.size step 4) assertEquals(0.0, table[i], "padding at $i")
        }
        // The 3D gradients are the cube's twelve edge midpoints (length √2), repeated; the random vectors unit length.
        lengths(NoiseTables.gradients3D, 4, 3).forEachIndexed { i, length -> assertTrue(abs(length - sqrt(2.0)) < 1e-12, "gradient $i") }
        lengths(NoiseTables.randVecs3D, 4, 3).forEachIndexed { i, length ->
            assertTrue(abs(length - 1) < 1e-9, "vector $i is $length long")
        }
        assertTrue(NoiseTables.gradients3D.all { it == 0.0 || abs(it) == 1.0 }, "gradient components are -1, 0 or 1")
    }
}
