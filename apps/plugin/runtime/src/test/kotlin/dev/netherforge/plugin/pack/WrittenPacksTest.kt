package dev.netherforge.plugin.pack

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.Severity
import dev.netherforge.format.image.Pixels
import dev.netherforge.format.image.PngWriter
import dev.netherforge.format.project.FormatVersion
import dev.netherforge.plugin.TestServer
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Resource packs scripts write into their package's `nf.files` folder (`packs/<name>/`): built with the project's by
 * `nf.packs.rebuild`, there again after a restart, a broken one replaced by its last good copy or left out, and never
 * taking a name the package's own `resource_packs/` has.
 */
class WrittenPacksTest {
    private val script = """
        local function write(name, json)
          local picture = nf.images.fill(4, 4, "#FFD700"):encode()
          local file = assert(nf.files.get("packs/" .. name .. "/textures/banner.png"))
          log("bytes", file:write_base64(picture), file:read_base64() == picture)
          assert(nf.files.get("packs/" .. name .. "/pack.json")):write(json)
        end
        nf.on("player_chat", function(event)
          local word, name, rest = event.message:match("^(%S+)%s*(%S*)%s*(.*)$")
          if word == "write" then
            write(name, rest)
            nf.packs.rebuild()
          elseif word == "glyph" then
            local ok, char = pcall(nf.text.glyph, name)
            log("glyph", name, ok and char ~= nil)
          end
        end)
    """

    private val good = """{ "glyphs": { "banner": { "texture": "banner.png" } } }"""

    private fun server() = TestServer(
        mapOf(
            "modules/m/init.lua" to script,
            "resource_packs/ui/pack.json" to """{ "glyphs": { "coin": { "texture": "banner.png" } } }"""
        )
    ).also { server ->
        server.writeBytes("resource_packs/ui/textures/banner.png", PngWriter.write(Pixels(2, 2, IntArray(4) { -1 })))
        server.runtime.reloadAll()
    }

    private fun TestServer.say(text: String) {
        platform.players.chat(player("Alex"), text)
        tick()
    }

    private fun names(bytes: ByteArray) = ZipInputStream(bytes.inputStream()).use { zip ->
        generateSequence { zip.nextEntry?.name }.toList()
    }

    private fun TestServer.written() = runtime.currentProblems().filter { it.code == ProblemCodes.RUNTIME_WRITTEN_PACK.code }

    @Test
    fun `a pack a script writes is built with the project's, its glyphs named as the package's own, and is there after a restart`() {
        server().use { server ->
            server.say("glyph featured/banner")
            server.say("write featured $good")
            server.say("glyph featured/banner")
            assertEquals(listOf("glyph\tfeatured/banner\tfalse", "bytes\ttrue\ttrue", "glyph\tfeatured/banner\ttrue"), server.logs)
            val built = assertNotNull(server.runtime.packs.built)
            assertTrue("assets/test/textures/featured/banner.png" in names(built.bytes), "${names(built.bytes)}")
            assertTrue("assets/test/textures/ui/banner.png" in names(built.bytes), "the project's own pack is still in it")
            assertTrue(server.written().isEmpty(), "${server.written()}")

            server.restart()
            assertTrue("featured" in server.runtime.session.snapshot.compiledResourcePacks, "read again as the server starts")
            assertEquals(built.sha1, server.runtime.packs.built?.sha1)
        }
    }

    @Test
    fun `an onDemand pack a script writes is pushed by name`() {
        server().use { server ->
            server.say("write featured " + """{ "onDemand": true, "glyphs": { "banner": { "texture": "banner.png" } } }""")
            val featured = assertNotNull(server.runtime.packs.onDemand["featured"])
            assertTrue(names(featured.bytes).any { it.endsWith("featured/banner.png") })
            assertFalse(names(server.runtime.packs.built!!.bytes).any { it.endsWith("featured/banner.png") })
        }
    }

    @Test
    fun `a written pack with errors runs its last good copy, through a restart, and the rest builds without one`() {
        server().use { server ->
            server.say("write featured $good")
            val before = server.runtime.packs.built!!.sha1
            server.say("write featured " + """{ "glyphs": { "banner": { "texture": "missing.png" } } }""")
            assertEquals(before, server.runtime.packs.built?.sha1, "the last good copy, built as it was")
            assertTrue(server.runtime.session.running)
            val summary = server.written().single()
            assertEquals(Severity.WARNING, summary.severity)
            assertTrue("last good copy" in summary.message, summary.message)
            assertTrue(summary.file.endsWith(".netherforge/data/test/packs/featured/pack.json"), summary.file)
            // Its own problems are there too, at the file the script wrote, and none stops the project.
            val own = server.runtime.currentProblems().filter { it.file == summary.file && it.code != summary.code }
            assertTrue(own.isNotEmpty() && own.all { it.severity == Severity.WARNING }, "${server.runtime.currentProblems()}")

            server.restart()
            assertEquals(before, server.runtime.packs.built?.sha1, "the copy is kept beside the server's state")
            val seen = server.logs.size
            server.say("glyph featured/banner")
            assertEquals(listOf("glyph\tfeatured/banner\ttrue"), server.logs.drop(seen))

            // One that was never good isn't built, and the project's own pack builds without it.
            server.say("write fresh " + """{ "glyphs": { "x": { "texture": "missing.png" } } }""")
            val fresh = server.written().single { "\"fresh\"" in it.message }
            assertTrue("isn't built" in fresh.message, fresh.message)
            assertFalse("fresh" in server.runtime.session.snapshot.compiledResourcePacks)
            assertTrue("ui" in server.runtime.session.snapshot.compiledResourcePacks)
        }
    }

    @Test
    fun `a written pack can't take the name of one in resource_packs`() {
        server().use { server ->
            server.say("write ui $good")
            val problem = server.written().single()
            assertTrue("of its own" in problem.message, problem.message)
            assertTrue(problem.file.endsWith("packs/ui/pack.json"), problem.file)
            assertEquals(setOf("ui"), server.runtime.session.snapshot.compiledResourcePacks.keys)
        }
    }

    @Test
    fun `a package's scripts write packs in its own namespace`() {
        val library = """{ "formatVersion": ${FormatVersion.CURRENT}, "name": "Library", "namespace": "library", "version": "1.0.0", """ +
            """"minecraft": "26.3", "exports": { "modules": ["api"] } }"""
        val api = """
            return {
              write = function()
                local picture = nf.images.fill(4, 4, "#00FF00"):encode()
                assert(nf.files.get("packs/badges/textures/badge.png")):write_base64(picture)
                assert(nf.files.get("packs/badges/pack.json")):write_json({ glyphs = { badge = { texture = "badge.png" } } })
                nf.packs.rebuild()
              end,
              glyph = function() return pcall(nf.text.glyph, "badges/badge") end,
            }
        """
        val manifest = TestServer.manifest().replace(
            "\"version\": \"1.0.0\"",
            "\"version\": \"1.0.0\", \"dependencies\": { \"library\": { \"path\": \"../library\" } }"
        )
        TestServer(
            mapOf(
                "netherforge.json" to manifest,
                "../library/netherforge.json" to library,
                "../library/modules/api/init.lua" to api,
                "modules/m/init.lua" to """
                    local api = require("library:api")
                    nf.on("player_chat", function(event)
                      if event.message == "write" then api.write() else log("glyph", (api.glyph())) end
                    end)
                """
            )
        ).use { server ->
            assertTrue(server.runtime.session.running, "${server.runtime.currentProblems()}")
            server.say("write")
            server.say("glyph")
            assertEquals(listOf("glyph\ttrue"), server.logs)
            assertTrue("library:badges" in server.runtime.session.snapshot.compiledResourcePacks)
            assertTrue("assets/library/textures/badges/badge.png" in names(server.runtime.packs.built!!.bytes))
        }
    }
}
