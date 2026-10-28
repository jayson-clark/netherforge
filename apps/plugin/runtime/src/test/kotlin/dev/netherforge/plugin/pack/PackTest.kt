package dev.netherforge.plugin.pack
import dev.netherforge.format.ref.ResourceKey
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.testkit.FakePlatform
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackTest {
    private val http = HttpClient.newHttpClient()

    private fun get(url: String): HttpResponse<ByteArray> =
        http.send(HttpRequest.newBuilder(URI(url)).build(), HttpResponse.BodyHandlers.ofByteArray())

    @Test
    fun `the example's packs are built, served at their hash and sent to players as they join`() {
        TestServer(TestServer.example("showcase")).use { server ->
            val built = assertNotNull(server.runtime.packs.built)
            val response = get(built.url!!)
            assertEquals(200, response.statusCode())
            assertEquals(built.sha1, Packs.sha1(response.body()))
            assertTrue(built.url!!.startsWith("http://127.0.0.1:"))
            assertEquals(404, get(built.url!!.replace(built.sha1, "0".repeat(40))).statusCode())

            val names = ZipInputStream(response.body().inputStream()).use { zip ->
                generateSequence { zip.nextEntry?.name }.toList()
            }
            assertTrue("pack.mcmeta" in names)
            assertTrue("assets/showcase/font/ui/gui.json" in names)
            assertTrue("assets/minecraft/font/default.json" in names)
            assertTrue("assets/showcase/textures/ui/gui/shop.png" in names)

            val alex = server.player("Alex")
            server.platform.raise.playerJoin(GameEvent.PlayerJoin(alex.ref, false, null))
            server.platform.raise.playerJoin(GameEvent.PlayerJoin(alex.ref, false, null))
            val sent = server.platform.resourcePacks.sent.single()
            assertEquals(built.sha1, sent.second.sha1)
            assertEquals(built.url, sent.second.url)
        }
    }

    @Test
    fun `a server that doesn't say its resource pack format builds no pack, and says why (runtime pack-format)`() {
        val game = FakePlatform.GAME.copy(packFormat = null)
        TestServer(TestServer.example("showcase"), platform = FakePlatform(game = game)).use { server ->
            assertNull(server.runtime.packs.built, "nothing built")
            val problem = server.runtime.session.problems().single { it.code == "runtime.pack-format" }
            assertEquals("resource_packs", problem.file)
            assertTrue("doesn't say which resource pack format" in problem.message, problem.message)
            assertTrue(server.platform.log.lines.any { "resource pack format" in it }, "${server.platform.log.lines}")
        }
    }

    @Test
    fun `saving a texture rebuilds with a new hash and resends to everyone online, and the same content keeps its hash`() {
        TestServer(TestServer.example("showcase")).use { server ->
            val alex = server.player("Alex")
            server.platform.raise.playerJoin(GameEvent.PlayerJoin(alex.ref, false, null))
            val before = server.runtime.packs.built!!.sha1

            // Same content: same bytes, same hash, nothing sent.
            assertEquals(before, server.reload("resource_packs/ui/pack.json").resources.single().pack!!.sha1)
            assertEquals(1, server.platform.resourcePacks.sent.size)

            val png = server.project.resolve("resource_packs/ui/textures/item/ruby.png")
            server.writeBytes("resource_packs/ui/textures/item/ruby.png", java.nio.file.Files.readAllBytes(png) + byteArrayOf(0))
            val result = server.reload("resource_packs/ui/textures/item/ruby.png").resources.single()
            assertEquals("resource_pack:ui", result.label)
            assertTrue(result.ok)
            assertEquals(1, result.reattached, "sent to Alex")
            val after = result.pack!!
            assertNotEquals(before, after.sha1)
            assertEquals(after.sha1, Packs.sha1(get(after.url!!).body()))
            assertEquals(after.sha1, server.platform.resourcePacks.sent.last().second.sha1)
        }
    }

    @Test
    fun `a pack with errors keeps the last good build`() {
        TestServer(TestServer.example("showcase")).use { server ->
            val before = server.runtime.packs.built!!.sha1
            server.write("resource_packs/ui/pack.json", """{ "glyphs": { "coin": { "texture": "nope.png" } } }""")
            val result = server.reload("resource_packs/ui/pack.json").resources.single()
            assertEquals(false, result.ok)
            assertEquals(before, server.runtime.packs.built!!.sha1)
            assertTrue(result.problems.any { it.code == "resource_pack.texture-missing" })
        }
    }

    @Test
    fun `zips are byte-identical for the same files whatever order they're given in`() {
        val a = PackZip.zip(linkedMapOf("b.txt" to byteArrayOf(1), "a.txt" to byteArrayOf(2)))
        val b = PackZip.zip(linkedMapOf("a.txt" to byteArrayOf(2), "b.txt" to byteArrayOf(1)))
        assertContentEquals(a, b)
    }

    @Test
    fun `glyphs resolve for scripts, a typo is an error, and a project without packs builds nothing`() {
        TestServer(
            TestServer.example("showcase") + ("modules/g/init.lua" to "log(#nf.text.glyph('ui/coin'))\nnf.text.glyph('ui/coins')")
        ).use { server ->
            assertEquals(listOf("3"), server.logs, "one private-use character, three bytes of UTF-8")
            assertTrue("resource pack \"ui\" has no glyph \"coins\" (it has: coin, spark, wisp)" in server.errors.single().message)
        }
        TestServer(mapOf("modules/g/init.lua" to "-- nothing")).use { server ->
            assertNull(server.runtime.packs.built)
        }
    }

    @Test
    fun `glyph tags resolve to the glyph's character, and an unknown one is nothing, reported once`() {
        TestServer(TestServer.example("showcase")).use { server ->
            val glyphs = server.platform.glyphs
            val coin = server.runtime.packs.glyph(ResourceKey("showcase", "ui/coin"))!!
            assertEquals(coin, glyphs("ui/coin"))
            assertNull(glyphs("ui/gem"))
            assertNull(glyphs("ui/gem"))
            assertNull(glyphs("coin"))
            val warnings = server.platform.log.lines.filter { "names no glyph" in it }
            assertEquals(2, warnings.size, warnings.toString())
            assertTrue("<glyph:ui/gem>" in warnings.first(), warnings.first())
        }
    }
}
