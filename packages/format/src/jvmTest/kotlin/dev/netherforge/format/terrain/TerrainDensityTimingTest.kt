package dev.netherforge.format.terrain

import dev.netherforge.format.TestFiles
import dev.netherforge.format.json.CanonicalJson
import org.junit.Assume.assumeTrue
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What 3D terrain costs: chunks of the density fixture (`testdata/terrain/density.json`: ground leaning into
 * overhangs, arches in one area, islands over another, caves, ores and decorations) against the same file with its
 * heights only, generated on one thread after a warm-up, and the preview's map of each. Prints the numbers and fails
 * only when 3D terrain is far past what it should cost.
 *
 * A benchmark, not a check: it takes seconds and its numbers depend on the machine, so like the runtime's benchmarks
 * it only runs with `NETHERFORGE_BENCH=1` (`NETHERFORGE_BENCH=1 node tools/gradle.mjs :format:jvmTest --tests
 * '*TerrainDensityTimingTest*' -i`) and is skipped otherwise.
 */
class TerrainDensityTimingTest {
    private fun generators(): Pair<TerrainGenerator, TerrainGenerator> {
        val parsed = TerrainKind.parse(TestFiles.read("packages/format/testdata/terrain/density.json")!!, "terrain/t.json")
        val file = (parsed as CanonicalJson.Parsed.Ok<TerrainFile>).value
        val heights = file.copy(
            terrain = file.terrain.copy(density = null),
            biomes = file.biomes.mapValues { (_, area) -> area.copy(terrain = area.terrain?.copy(density = null)) }
        )
        return TerrainCompiler.compile(heights).bind(7L, -64, 320) to TerrainCompiler.compile(file).bind(7L, -64, 320)
    }

    /** Milliseconds a chunk takes, over [count] chunks in a row (each of them new, as a world's are). */
    private fun perChunk(generator: TerrainGenerator, from: Int, count: Int): Double {
        val start = System.nanoTime()
        for (i in 0 until count) generator.generate(from + i % 20, i / 20)
        return (System.nanoTime() - start) / 1e6 / count
    }

    private fun mapMillis(generator: TerrainGenerator): Double {
        val start = System.nanoTime()
        TerrainPreview.map(generator, -512, -512, 256, 4)
        return (System.nanoTime() - start) / 1e6
    }

    @Before
    fun onlyWhenBenchmarking() {
        assumeTrue("set NETHERFORGE_BENCH=1 to run the terrain timing", System.getenv("NETHERFORGE_BENCH") == "1")
    }

    @Test
    fun threeDTerrainCostsASensibleMultipleOfHeights() {
        val (heights, density) = generators()
        perChunk(heights, 1000, 200)
        perChunk(density, 1000, 200)
        val h = perChunk(heights, 0, 400)
        val d = perChunk(density, 0, 400)
        val hMap = mapMillis(heights)
        val dMap = mapMillis(density)
        println(
            "terrain timing: heights %.2f ms a chunk, density %.2f ms a chunk (%.1fx); a 256x256 map: %.0f ms and %.0f ms"
                .format(h, d, d / h, hMap, dMap)
        )
        assertTrue(d < h * 10, "3D terrain took ${d / h} times as long as heights")
    }
}
