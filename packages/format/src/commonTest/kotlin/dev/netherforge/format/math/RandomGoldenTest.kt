package dev.netherforge.format.math

import dev.netherforge.format.TestFiles
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail

/**
 * What `Random(seed)` draws, which the particle sampler and the terrain generator depend on: the Rust port
 * (`crates/format/src/random.rs`) is held to `testdata/random.txt` (`UPDATE_GOLDEN=1` rewrites it). A double is
 * written by its bits.
 */
class RandomGoldenTest {
    @Test
    fun drawsAreTheGoldens() {
        val actual = SEEDS.joinToString("") { seed ->
            val r = Random(seed)
            val ints = List(3) { r.nextInt() }.joinToString(" ")
            "$seed: $ints ${r.nextDouble().toRawBits()} ${r.nextInt(10)} ${r.nextInt(-5, 5)} ${r.nextInt(7)} ${r.nextBoolean()}\n"
        }
        val path = "packages/format/testdata/random.txt"
        if (actual == TestFiles.read(path)) return
        if (TestFiles.updateGolden) TestFiles.write(path, actual) else fail("$path differs. Run with UPDATE_GOLDEN=1 to accept.\n$actual")
    }

    private companion object {
        val SEEDS = listOf(0L, 1L, 42L, -7L, 1L shl 40, -123456789012345L, Int.MAX_VALUE.toLong())
    }
}
