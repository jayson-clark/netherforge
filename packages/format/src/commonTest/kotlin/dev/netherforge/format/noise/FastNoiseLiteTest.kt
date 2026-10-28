package dev.netherforge.format.noise

import dev.netherforge.format.TestFiles
import dev.netherforge.format.json.CanonicalJson
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The port of FastNoiseLite: faithful to the reference (within the rounding of its 32-bit floats), and the same
 * numbers on every platform. This test runs on the JVM and in JS against the same files, so a seed's noise is
 * proven identical on both, which is what lets the editor's preview show the server's world.
 */
class FastNoiseLiteTest {
    private fun configure(noiseType: String, fractal: String, rotation: String, variant: Int, seed: Int): FastNoiseLite {
        val n = FastNoiseLite(seed)
        n.setNoiseType(FastNoiseLite.NoiseType.valueOf(noiseType))
        n.setFractalType(FastNoiseLite.FractalType.valueOf(fractal))
        n.setRotationType3D(FastNoiseLite.RotationType3D.valueOf(rotation))
        n.setFrequency(0.013)
        n.setFractalOctaves(4)
        n.setFractalWeightedStrength(0.3)
        if (noiseType == "Cellular") {
            n.setCellularDistanceFunction(FastNoiseLite.CellularDistanceFunction.entries[variant % 4])
            n.setCellularReturnType(FastNoiseLite.CellularReturnType.entries[variant % 7])
        }
        return n
    }

    @Test
    fun theKotlinPortAgreesWithTheReferenceToItsRounding() {
        val lines = TestFiles.read("packages/format/testdata/noise/reference.csv")!!.lines().filter {
            it.isNotBlank() && !it.startsWith("#")
        }
        assertTrue(lines.size > 200, "the reference points are there")
        for (line in lines) {
            val p = line.split(";")
            val x = p[5].toDouble()
            val y = p[6].toDouble()
            val z = p[7].toDouble()
            if (p[0] == "N") {
                val n = configure(p[1], p[2], p[3], p[4].toInt(), 1337 + p[4].toInt() * 7)
                assertNear(p[8].toDouble(), n.getNoise(x, y), 2e-3, line)
                assertNear(p[9].toDouble(), n.getNoise(x, y, z), 2e-3, line)
            } else {
                val n = FastNoiseLite(99)
                n.setDomainWarpType(FastNoiseLite.DomainWarpType.valueOf(p[1]))
                n.setFractalType(FastNoiseLite.FractalType.valueOf(p[2]))
                n.setRotationType3D(FastNoiseLite.RotationType3D.valueOf(p[3]))
                n.setFrequency(0.02)
                n.setDomainWarpAmp(30.0)
                n.setFractalOctaves(3)
                val v2 = FastNoiseLite.Vector2(x, y)
                n.domainWarp(v2)
                val v3 = FastNoiseLite.Vector3(x, y, z)
                n.domainWarp(v3)
                // Warps move a point by up to 30 blocks, so a float's rounding shows a little more.
                assertNear(p[8].toDouble(), v2.x, 5e-2, line)
                assertNear(p[9].toDouble(), v2.y, 5e-2, line)
                assertNear(p[10].toDouble(), v3.x, 5e-2, line)
                assertNear(p[11].toDouble(), v3.y, 5e-2, line)
                assertNear(p[12].toDouble(), v3.z, 5e-2, line)
            }
        }
    }

    private fun assertNear(expected: Double, actual: Double, tolerance: Double, what: String) {
        assertTrue(abs(expected - actual) <= tolerance, "$what: expected $expected, got $actual")
    }

    /** Noise at fixed points for every type and fractal, as text: what must come out bit for bit on every platform. */
    private fun goldenLines(): List<String> {
        val lines = mutableListOf<String>()
        for (type in FastNoiseLite.NoiseType.entries) {
            for (fractal in listOf("None", "FBm", "Ridged", "PingPong")) {
                for (rotation in listOf("None", "ImproveXYPlanes")) {
                    val n = configure(type.name, fractal, rotation, 1, -5025)
                    for (k in 0 until 3) {
                        val x = k * 713.25 - 600.5
                        val y = k * -291.75 + 40.125
                        val z = k * 57.5 - 3.0
                        lines += "${type.name} $fractal $rotation $k ${num(n.getNoise(x, y))} ${num(n.getNoise(x, y, z))}"
                    }
                }
            }
        }
        for (warp in FastNoiseLite.DomainWarpType.entries) {
            val n = FastNoiseLite(77)
            n.setDomainWarpType(warp)
            n.setFractalType(FastNoiseLite.FractalType.DomainWarpProgressive)
            n.setFractalOctaves(3)
            n.setDomainWarpAmp(25.0)
            n.setFrequency(0.02)
            val v2 = FastNoiseLite.Vector2(311.75, -902.5)
            n.domainWarp(v2)
            val v3 = FastNoiseLite.Vector3(311.75, 12.5, -902.5)
            n.domainWarp(v3)
            lines += "warp ${warp.name} ${num(v2.x)} ${num(v2.y)} ${num(v3.x)} ${num(v3.y)} ${num(v3.z)}"
        }
        // The seeds a world's parts start from.
        for (role in listOf("height:hills", "climate:temperature", "cave:caverns", "ore:iron")) {
            lines += "seed $role ${TerrainSeeds.forRole(123456789012345L, role)} ${TerrainSeeds.forRole(-7L, role)}"
        }
        lines += "unit ${num(TerrainSeeds.unit(5, 10, -20, 3000))} ${num(TerrainSeeds.unit(-9, 0, 0, 0))}"
        return lines
    }

    private fun num(d: Double) = CanonicalJson.formatNumber(d.toString())

    @Test
    fun everyNoiseIsTheSameOnEveryPlatform() {
        val actual = goldenLines()
        val path = "packages/format/testdata/noise/golden.txt"
        if (TestFiles.updateGolden) {
            TestFiles.write(path, actual.joinToString("\n") + "\n")
            return
        }
        val expected = TestFiles.read(path)!!.lines().filter { it.isNotBlank() }
        assertEquals(expected.size, actual.size)
        for ((e, a) in expected.zip(actual)) {
            // Compared as the numbers they are: the digits are what both platforms agree the number is.
            val ep = e.split(" ")
            val ap = a.split(" ")
            assertEquals(ep.size, ap.size, a)
            for (i in ep.indices) {
                val ev = ep[i].toDoubleOrNull()
                if (ev == null) assertEquals(ep[i], ap[i], a) else assertEquals(ev, ap[i].toDouble(), "$a (item $i)")
            }
        }
    }

    @Test
    fun aSeedGivesTheSameNoiseAndAnotherSeedAnotherOne() {
        val a = NoiseDef(frequency = 0.02, octaves = 3).build(7)
        val b = NoiseDef(frequency = 0.02, octaves = 3).build(7)
        val c = NoiseDef(frequency = 0.02, octaves = 3).build(8)
        var different = 0
        for (i in 0 until 50) {
            val x = i * 37.0
            assertEquals(a.getNoise(x, -x), b.getNoise(x, -x))
            if (a.getNoise(x, -x) != c.getNoise(x, -x)) different++
        }
        assertTrue(different > 40, "another seed is another pattern")
    }

    @Test
    fun everyTypeStaysWithinMinusOneAndOneOverManyPoints() {
        for (type in NoiseType.entries) {
            for (fractal in NoiseFractal.entries) {
                val n = NoiseDef(type = type, fractal = fractal, octaves = 4, frequency = 0.05).build(42)
                for (i in 0 until 400) {
                    val x = (i % 20) * 13.7
                    val z = (i / 20) * 11.3
                    val v2 = n.getNoise(x, z)
                    val v3 = n.getNoise(x, i * 3.1, z)
                    assertTrue(abs(v2) <= 1.0001 && abs(v3) <= 1.0001, "$type $fractal at $x,$z: $v2 $v3")
                }
            }
        }
    }

    @Test
    fun theRolesOfAWorldGetSeedsOfTheirOwn() {
        val roles = listOf("height:hills", "height:detail", "climate:temperature", "climate:humidity", "cave:a", "cave:a:b", "ore:iron")
        val seeds = roles.map { TerrainSeeds.forRole(42L, it) }
        assertEquals(roles.size, seeds.toSet().size)
        assertNotEquals(TerrainSeeds.forRole(42L, "height:hills"), TerrainSeeds.forRole(43L, "height:hills"))
        assertEquals(TerrainSeeds.forRole(42L, "height:hills"), TerrainSeeds.forRole(42L, "height:hills"))
        // A cell's number is in [0, 1) and not constant.
        val units = (0 until 200).map { TerrainSeeds.unit(9, it, it * 3, -it) }
        assertTrue(units.all { it >= 0.0 && it < 1.0 })
        assertTrue(units.toSet().size > 150)
    }
}
