package dev.netherforge.plugin.script
import dev.netherforge.plugin.TestServer
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `nf.file`'s folder: a script reaches inside it and nowhere else, symlinks included. */
class ScriptFilesTest {
    @Test
    fun `reads and writes inside, refuses escapes`() {
        val tmp = Files.createTempDirectory("nf-files")
        val files = ScriptFiles(tmp.resolve("data"))
        assertTrue(files.write("notes/a.txt", "hi", append = false))
        assertEquals("hi", files.read("notes/a.txt"))
        assertNull(files.normalize("../outside.txt"))
        assertFalse(files.write("../outside.txt", "x", append = false))
    }

    @Test
    fun `a symlink placed in the folder doesn't lead out of it`() {
        val tmp = Files.createTempDirectory("nf-files")
        val data = tmp.resolve("data").createDirectories()
        val outside = tmp.resolve("outside").createDirectories()
        outside.resolve("secret.txt").writeText("secret")
        Files.createSymbolicLink(data.resolve("link"), outside)
        Files.createSymbolicLink(data.resolve("dangling.txt"), outside.resolve("new.txt"))
        val files = ScriptFiles(data)

        assertNull(files.read("link/secret.txt"))
        assertFalse(files.write("link/planted.txt", "x", append = false))
        assertFalse(files.write("dangling.txt", "x", append = false))
        assertFalse(Files.exists(outside.resolve("planted.txt")))
        assertFalse(Files.exists(outside.resolve("new.txt")))
        assertEquals("secret", outside.resolve("secret.txt").readText())
    }

    @Test
    fun `moves and copies stay inside and never overwrite`() {
        val tmp = Files.createTempDirectory("nf-files")
        val files = ScriptFiles(tmp.resolve("data"))
        assertTrue(files.write("a.txt", "a", append = false))
        assertTrue(files.write("b.txt", "b", append = false))
        assertFalse(files.move("a.txt", "b.txt"), "b.txt is taken")
        assertFalse(files.move("a.txt", "../a.txt"))
        assertFalse(files.move("missing.txt", "c.txt"))
        assertTrue(files.copy("a.txt", "deep/copy.txt"))
        assertEquals("a", files.read("deep/copy.txt"))
        assertFalse(files.copy("deep", "deep2"), "folders aren't copied")
        assertFalse(files.move("deep", "deep/inner"), "not into itself")
        assertTrue(files.move("deep", "moved"))
        assertEquals("a", files.read("moved/copy.txt"))
        assertFalse(files.move("", "x"), "never the root")
    }

    @Test
    fun `scripts rename, copy, date and read files line by line`() {
        val files = mapOf(
            "modules/m/init.lua" to """
                local file = nf.files.get("notes.txt")
                file:write("one\r\ntwo\n\nthree")
                local lines = {}
                for line in file:lines() do lines[#lines + 1] = "[" .. line .. "]" end
                log(table.concat(lines))
                local before = nf.server.unix_time()
                log(math.abs(file:modified_time() - before) < 60000, tostring(nf.files.get("nope"):modified_time()))
                for _ in nf.files.get("nope"):lines() do log("never") end
                local copy = file:copy_to("backup/notes.txt")
                log(copy:path(), copy:read() == file:read())
                local moved = file:rename("archive/notes.txt")
                log(moved:path(), file:exists(), moved:exists())
                log(tostring(moved:rename("../escape.txt")), tostring(copy:copy_to("archive/notes.txt")))
            """
        )
        TestServer(files).use { server ->
            assertEquals(
                listOf(
                    "[one][two][][three]",
                    "true\tnil",
                    "backup/notes.txt\ttrue",
                    "archive/notes.txt\tfalse\ttrue",
                    "nil\tnil"
                ),
                server.logs
            )
        }
    }
}
