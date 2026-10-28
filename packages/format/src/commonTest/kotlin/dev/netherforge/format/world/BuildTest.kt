package dev.netherforge.format.world

import dev.netherforge.format.project.Kinds
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.PathRole
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.format.project.Projects
import dev.netherforge.format.testManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `builds/<id>/`: a grid of structure files, checked by their names alone. */
class BuildTest {
    private fun load(vararg files: Pair<String, String?>): ProjectSnapshot =
        Projects.load(MapProjectSource(mapOf("netherforge.json" to testManifest()) + files))

    private fun parts(id: String, x: Int, y: Int, z: Int): Array<Pair<String, String?>> =
        BuildGrid(x, y, z).parts.map { "builds/$id/${it.file}" to null }.toTypedArray()

    @Test
    fun aPartIsAFileOfItsBuildAndTheFirstMakesTheFolderOne() {
        fun at(path: String) = Kinds.classify(path)?.let { listOf(it.kind, it.id, it.role, it.rest) }
        assertEquals(listOf("build", "city", PathRole.MAIN, "0_0_0.nbt"), at("builds/city/0_0_0.nbt"))
        assertEquals(listOf("build", "city", PathRole.FILE, "2_0_1.nbt"), at("builds/city/2_0_1.nbt"))
        assertEquals(listOf("build", "city", PathRole.FOLDER, ""), at("builds/city"))
        assertEquals(listOf("build", "city", PathRole.MAIN, "0_0_0.nbt"), at("builds/(towns)/city/0_0_0.nbt"))
    }

    @Test
    fun partNamesAreTheirPlaceInTheGrid() {
        assertEquals(BuildPart(2, 0, 11), Builds.partOf("2_0_11.nbt"))
        for (name in listOf("2_0.nbt", "01_0_0.nbt", "0_0_0.NBT", "0_0_0.nbt.bak", "a_0_0.nbt", "-1_0_0.nbt", "0_0_0")) {
            assertNull(Builds.partOf(name), name)
        }
        assertEquals("1_2_3.nbt", BuildPart(1, 2, 3).file)
        assertEquals(listOf(48, 96, 144), BuildPart(1, 2, 3).let { listOf(it.originX, it.originY, it.originZ) })
        // A box is cut into whole parts and what's left.
        assertEquals(BuildGrid(1, 1, 1), Builds.gridFor(48, 1, 48))
        assertEquals(BuildGrid(3, 1, 2), Builds.gridFor(97, 48, 49))
        // Bottom up, x fastest: the order a build is placed in.
        assertEquals(
            listOf("0_0_0.nbt", "1_0_0.nbt", "0_0_1.nbt", "1_0_1.nbt", "0_1_0.nbt", "1_1_0.nbt", "0_1_1.nbt", "1_1_1.nbt"),
            BuildGrid(2, 2, 2).parts.map { it.file }
        )
    }

    @Test
    fun aWholeGridLoadsClean() {
        val snapshot = load(*parts("city", 3, 2, 2))
        assertEquals(emptyList(), snapshot.problems)
        assertEquals(BuildGrid(3, 2, 2), snapshot.models(BuildKind).getValue("city").grid)
        assertEquals(listOf("city"), snapshot.compiled(BuildKind).keys.toList())
    }

    @Test
    fun aGapInTheGridIsAnError() {
        val files = parts("city", 3, 1, 2).filter { it.first != "builds/city/1_0_1.nbt" && it.first != "builds/city/2_0_0.nbt" }
        val problem = load(*files.toTypedArray()).problems.single()
        assertEquals("build.part-missing", problem.code)
        assertEquals("builds/city/0_0_0.nbt", problem.file)
        assertTrue("2_0_0.nbt, 1_0_1.nbt aren't there" in problem.message, problem.message)
    }

    @Test
    fun aFileThatIsntAPartIsReportedAndIgnored() {
        val snapshot = load("builds/city/0_0_0.nbt" to null, "builds/city/notes.txt" to null, "builds/city/old/0_0_0.nbt" to null)
        assertEquals(
            listOf("build.part-name" to "builds/city/notes.txt", "build.part-name" to "builds/city/old/0_0_0.nbt"),
            snapshot.problems.map { it.code to it.file }.sortedBy { it.second }
        )
        assertEquals(BuildGrid(1, 1, 1), snapshot.models(BuildKind).getValue("city").grid)
    }

    @Test
    fun tooManyPartsAlongAnAxisIsAnError() {
        val far = Builds.MAX_PARTS
        val problem = load("builds/city/0_0_0.nbt" to null, "builds/city/${far}_0_0.nbt" to null).problems.single()
        assertEquals("build.size", problem.code)
        assertTrue("${far + 1} parts along x" in problem.message, problem.message)
    }

    @Test
    fun aBuildCantTakeAStructuresId() {
        val problem = load("builds/arena/0_0_0.nbt" to null, "structures/arena.nbt" to null).problems.single()
        assertEquals("build.id-taken", problem.code)
        assertEquals("builds/arena/0_0_0.nbt", problem.file)
        assertTrue("structures/arena.nbt" in problem.message, problem.message)
    }
}
