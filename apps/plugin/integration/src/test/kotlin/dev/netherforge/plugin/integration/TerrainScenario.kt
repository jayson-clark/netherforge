package dev.netherforge.plugin.integration

import dev.netherforge.format.bridge.BlockPos
import dev.netherforge.format.bridge.Bridge
import dev.netherforge.format.bridge.Log
import dev.netherforge.format.bridge.SaveStructureParams
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.terrain.ChunkBuffer
import dev.netherforge.format.terrain.TerrainBlock
import dev.netherforge.format.terrain.TerrainCompiler
import dev.netherforge.format.terrain.TerrainGenerator
import dev.netherforge.format.terrain.TerrainKind
import dev.netherforge.plugin.integration.support.Scenario
import dev.netherforge.plugin.integration.support.TestProject
import dev.netherforge.plugin.integration.support.VersionIndependent
import dev.netherforge.plugin.integration.support.eventually
import dev.netherforge.plugin.testkit.StructureFiles
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A terrain of the project on a real server (W5.2, W5.8): a world `netherforge.json` makes with `terrain/ruby_hills.json`
 * (the example's) has the heights (blended between its areas' own terrain), layers, ores and decorations format's own generator
 * makes for its seed (the example's tree, read here and by the server's own structure loader, where format puts it), an ore of
 * the project's custom block is there and adopted as that block, a vanilla-placed structure of the project generates in it
 * (its marker becoming a centity), a changed file shapes only the chunks generated after it, and the world keeps its generator
 * across a restart. Scripts read its biomes (W5.9): a block's is the generator's, the server's own search finds another off
 * the main thread, and a new chunk is heard as it's generated.
 */
@VersionIndependent
class TerrainScenario : Scenario("terrain", "structures") {
    private val seed = 20260714L
    private val file = "terrain/ruby_hills.json"

    /**
     * The steps compare exact blocks, so nothing of the game's may decorate the ground: the example's areas are its
     * biomes, whose features the game places (its own grove's cherry trees, the plains' grass), so here every area is
     * one of the fixture's biomes without features (`BiomeScenario` has the example's own); and its game structures start off.
     */
    override fun prepare(project: TestProject) {
        project.write(
            file,
            project.read(file)
                .replace("\"vanilla\": true", "\"vanilla\": false")
                .replace("\"biome\": \"ruby_grove\"", "\"biome\": \"it_bare_warm\"")
                .replace("\"biome\": \"minecraft:plains\"", "\"biome\": \"it_bare\"")
                .replace("\"biome\": \"minecraft:snowy_plains\"", "\"biome\": \"it_bare_cold\"")
        )
        // The project makes the world itself, as the server starts: netherforge.json names it with its generator and seed.
        project.write(
            "netherforge.json",
            project.read(
                "netherforge.json"
            ).replace("\"worlds\": {", "\"worlds\": {\n    \"it_wg\": { \"terrain\": \"ruby_hills\", \"seed\": $seed },")
        )
    }

    /** The generator of [text] (the file as it is on disk now), with the structures it places, for the world's seed and height. */
    private fun generator(text: String = project.read(file)): TerrainGenerator {
        val parsed = TerrainKind.parse(text, file) as CanonicalJson.Parsed.Ok
        val compiled = TerrainCompiler.compile(parsed.value)
        val structures = compiled.structures.associateWith { StructureFiles.template(project.file("structures/$it.nbt")) }
        return compiled.withStructures(structures).bind(seed, -64, 320)
    }

    private fun step(command: String, line: String): List<String> {
        editor.run("it-terrain $command")
        return editor.line(line)
    }

    /**
     * [read] with the chunks from the column [from] to [to] generated and held by the server's `forceload` (commands run
     * in order on the main thread), so a step's own `load_chunk` finds them loaded: the console makes them outside any
     * script's time limit, which a new chunk and the neighbours it needs can overrun on a busy machine.
     */
    private fun <T> generated(from: Pair<Int, Int>, to: Pair<Int, Int> = from, read: () -> T): T {
        val area = "${from.first} ${from.second} ${to.first} ${to.second}"
        editor.run("execute in minecraft:it_wg run forceload add $area")
        try {
            return read()
        } finally {
            editor.run("execute in minecraft:it_wg run forceload remove $area")
        }
    }

    /** A column's top block as the generator makes it: its y and block. */
    private fun top(generator: TerrainGenerator, buffer: ChunkBuffer, x: Int, z: Int): Pair<Int, String> {
        val lx = x and 15
        val lz = z and 15
        val y = (buffer.maxY - 1 downTo buffer.minY).first { buffer[lx, it, lz] != 0 }
        return y to generator.terrain.palette[buffer[lx, y, lz]].label
    }

    private fun assertColumns(generator: TerrainGenerator, columns: List<Pair<Int, Int>>) {
        for ((x, z) in columns) {
            val (y, kind) = top(generator, generator.generate(x shr 4, z shr 4), x, z)
            val top = generated(x to z) { step("column $x $z", "column") }
            assertEquals(listOf("$y", kind.substringBefore('[')), top, "the top of $x,$z")
        }
    }

    @Test
    @Order(1)
    fun `a ruin is captured and told where to generate, which the datapack needs a restart for`() {
        editor.run("it-structures prepare")
        editor.logged("prepared", "true")
        val (saved, structure) = editor.request(
            Bridge.saveStructure,
            SaveStructureParams("world", BlockPos(0, -60, 0), BlockPos(2, -59, 2), entities = true)
        )
        assertTrue(saved.ok, saved.error)
        Files.createDirectories(project.file("structures"))
        Files.write(project.file("structures/it_ruins.nbt"), Base64.getDecoder().decode(structure!!.nbt))
        project.write(
            "structures/it_ruins.json",
            // In the world's biomes, the project's: a structure generates in them as in the game's.
            """{ "biomes": ["it_bare", "it_bare_cold", "it_bare_warm"], "spacing": 2, "separation": 0,
                "terrainAdaptation": "beard_thin" }""" + "\n"
        )
        assertTrue(editor.reload("structures/it_ruins.nbt", "structures/it_ruins.json").restart)
    }

    @Test
    @Order(2)
    fun `a world made with the generator has its heights, layers and ores for its seed`() {
        restart()
        // The world is the one netherforge.json's `worlds` made when the server first started, loaded with its generator now.
        assertEquals(listOf("it_wg", "true"), step("make 0", "made"))
        val generator = generator()
        val columns = listOf(3 to 4, -40 to 25, 100 to -77, 17 to 16, -5 to -150, 230 to 230)
        assertColumns(generator, columns)
        // Ores of the project's own block, which the server holds as a note block and the project has adopted as the block.
        val buffers = listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1).associateWith { (cx, cz) -> generator.generate(cx, cz) }
        val ruby = generator.terrain.palette.indexOf(TerrainBlock.Custom("ruby_ore"))
        assertTrue(ruby > 0, "the example's file has the ruby ore")
        val places = buffers.flatMap { (chunk, buffer) ->
            (0 until 16).flatMap { lx ->
                (0 until 16).flatMap { lz ->
                    (buffer.minY until buffer.maxY).filter { buffer[lx, it, lz] == ruby }.map {
                        Triple(
                            chunk.first * 16 + lx,
                            it,
                            chunk.second * 16 + lz
                        )
                    }
                }
            }
        }
        assertTrue(places.size > 5, "the generator puts ruby ore around the origin (${places.size})")
        for ((x, y, z) in places.take(8)) {
            val block = generated(x to z) { step("block $x $y $z", "block") }
            assertEquals(listOf("minecraft:note_block", "ruby_ore"), block, "ruby ore at $x,$y,$z")
        }
        // The example's trees (a project structure, as decorations): a log where format's generator puts one, as the server read the file.
        val log = generator.terrain.palette.indexOfFirst { it.label.startsWith("minecraft:oak_log") }
        assertTrue(log > 0, "the example's trees are linked in")
        // The nearest chunk to the origin (row by row outwards) where format's generator puts one.
        val trunk = (0..8).asSequence().flatMap { r -> (-r..r).asSequence().flatMap { cx -> (-r..r).asSequence().map { cx to it } } }
            .distinct()
            .firstNotNullOfOrNull { (cx, cz) ->
                val buffer = generator.generate(cx, cz)
                (0 until 16).firstNotNullOfOrNull { lx ->
                    (0 until 16).firstNotNullOfOrNull { lz ->
                        (buffer.minY until buffer.maxY).firstOrNull { buffer[lx, it, lz] == log }?.let {
                            Triple(
                                cx * 16 + lx,
                                it,
                                cz * 16 + lz
                            )
                        }
                    }
                }
            }
        val (tx, ty, tz) = assertNotNull(trunk, "the example's file puts trees near the origin")
        val trunkBlock = generated(tx to tz) { step("block $tx $ty $tz", "block") }
        assertEquals("minecraft:oak_log", trunkBlock[0].substringBefore('['), "a tree's log at $tx,$ty,$tz")
        // Stone is plain stone: no block of the project's, whatever the note block states around it.
        val stone = generator.terrain.palette.indexOf(TerrainBlock.Vanilla("minecraft:stone"))
        val buffer = buffers.getValue(0 to 0)
        val (sx, sy, sz) = Triple(5, (buffer.minY until buffer.maxY).first { buffer[5, it, 5] == stone }, 5)
        assertEquals(listOf("minecraft:stone", "none"), generated(sx to sz) { step("block $sx $sy $sz", "block") })
    }

    @Test
    @Order(3)
    fun `with the game's structures on, one of the project's generates in later chunks and its marker becomes the centity`() {
        project.write(file, project.read(file).replace("\"vanilla\": false", "\"vanilla\": true"))
        val result = editor.reload(file)
        assertTrue(result.resources.single().ok, "${result.resources}")
        assertTrue(!result.restart, "a terrain is data the chunk threads read: nothing restarts")
        // A marker becomes its centity on a tick after its chunk loads: asked again until one has, while they're held.
        generated(-82 * 16 to -82 * 16, -78 * 16 to -78 * 16) {
            eventually("a guard spawned from a generated ruin", poll = { step("guards", "guards")[0].toInt() }) { it > 0 }
        }
    }

    @Test
    @Order(4)
    fun `saving it again shapes only the chunks generated after it`() {
        val before = generator()
        project.write(file, project.read(file).replace("\"base\": 66", "\"base\": 110").replace("\"vanilla\": true", "\"vanilla\": false"))
        assertTrue(editor.reload(file).resources.single().ok)
        val after = generator()
        assertNotEquals(before.surfaceAt(900, 900), after.surfaceAt(900, 900))
        // A chunk made before the save is as it was, and a far one made now is the new file's.
        assertColumns(before, listOf(3 to 4, -40 to 25))
        assertColumns(after, listOf(900 to 900, 905 to 912))
    }

    @Test
    @Order(5)
    fun `the world is loaded with its generator after a restart, shaping new chunks by the file as it is`() {
        restart()
        assertEquals(listOf("it_wg", "true"), step("make 0", "made"))
        assertColumns(generator(), listOf(1500 to -1500, 1503 to -1489))
        // Chunks saved before the change (and the restart) stay as they were made.
        assertColumns(generator(project.read(file).replace("\"base\": 110", "\"base\": 66")), listOf(3 to 4, -40 to 25))
    }

    @Test
    @Order(6)
    fun `scripts read the generator's biomes, find another with the server's search, and hear new chunks`() {
        val generator = generator()

        // Scripts name biomes as files do, so the generator's own spelling (the project's bare, the game's in full) is what they read and take.
        // Columns on the 4-block grid the game keeps biomes in, so the generator's biome there is the column's.
        for ((x, z) in listOf(8 to 8, 200 to -120, -1204 to 1500)) {
            val biome = generated(x to z) { step("biome $x $z", "biome") }
            assertEquals(listOf(generator.biomeAt(x, z)), biome, "the biome at $x,$z")
        }
        val here = generator.biomeAt(0, 0)
        val other = generator.terrain.biomes.first { it != here }
        editor.run("it-terrain-locate 0 0 $other")
        val found = editor.line("located")
        assertNotEquals("none", found[0], "a place of $other near the origin: ${found.drop(1)}")
        assertEquals(other, generator.biomeAt(found[0].toInt(), found[1].toInt()), "what the search found at ${found[0]},${found[1]}")
        // A chunk nobody has been near, generated as it loads: the handler hears it and reads its blocks.
        assertEquals(listOf("it_wg"), step("listen", "listening"))
        val heard = generated(-300 * 16 to 300 * 16) {
            (editor.next { it is Log && it.message.startsWith("generated\t-300\t300\t") } as Log).message.split('\t')
        }
        assertEquals(generator.biomeAt(-300 * 16 + 8, 300 * 16 + 8), heard[3])
    }
}
