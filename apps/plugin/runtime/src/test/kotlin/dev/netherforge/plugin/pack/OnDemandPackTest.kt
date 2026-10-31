package dev.netherforge.plugin.pack

import dev.netherforge.format.image.Pixels
import dev.netherforge.format.image.PngWriter
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.GameEvent
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `onDemand` resource packs: built apart, pushed to and popped from one player, rebuilt from a script. */
class OnDemandPackTest {
    private val png = PngWriter.write(Pixels(2, 2, IntArray(4) { -1 }))

    private val script = """
        nf.on("player_join", function(event)
          log("on demand", table.concat(nf.packs.on_demand(), ","))
          log("pushed", event.player:push_resource_pack("featured"))
          log("again", event.player:push_resource_pack("featured"))
          log("not one", pcall(event.player.push_resource_pack, event.player, "ui"))
        end)
        nf.on("player_resource_pack_status", function(event)
          log("status", tostring(event.pack), event.status, tostring(event.player:resource_pack_status(event.pack)))
        end)
        nf.on("player_chat", function(event)
          if event.message == "pop" then log("popped", event.player:pop_resource_pack("featured")) end
          if event.message == "rebuild" then nf.packs.rebuild() end
        end)
    """

    private fun server() = TestServer(
        mapOf(
            "modules/m/init.lua" to script,
            "resource_packs/ui/pack.json" to """{ "glyphs": { "coin": { "texture": "coin.png" } } }""",
            "resource_packs/featured/pack.json" to """{ "onDemand": true, "glyphs": { "banner": { "texture": "banner.png" } } }"""
        )
    ).also { server ->
        server.writeBytes("resource_packs/ui/textures/coin.png", png)
        server.writeBytes("resource_packs/featured/textures/banner.png", png)
        server.runtime.reloadAll()
    }

    private fun names(bytes: ByteArray) = ZipInputStream(bytes.inputStream()).use { zip ->
        generateSequence { zip.nextEntry?.name }.toList()
    }

    @Test
    fun `an onDemand pack is built apart, pushed beside the project's pack, and popped`() {
        server().use { server ->
            val main = assertNotNull(server.runtime.packs.built)
            val featured = assertNotNull(server.runtime.packs.onDemand["featured"])
            assertTrue("assets/case/textures/ui/coin.png" in names(main.bytes) || names(main.bytes).any { it.endsWith("ui/coin.png") })
            assertFalse(names(main.bytes).any { it.endsWith("featured/banner.png") }, "not in the pack players join with")
            assertTrue(names(featured.bytes).any { it.endsWith("featured/banner.png") })
            assertTrue(featured.url!!.endsWith("${featured.sha1}.zip"))
            // Glyphs are numbered together, so the banner's character is its own.
            val packs = server.runtime.session.snapshot.compiledResourcePacks
            assertNotEquals(packs.getValue("ui").glyphs.getValue("coin").char, packs.getValue("featured").glyphs.getValue("banner").char)

            val alex = server.player("Alex")
            alex.packAnswer = "loaded"
            server.platform.raise.playerJoin(GameEvent.PlayerJoin(alex.ref, false, null))
            val sent = server.platform.resourcePacks.sent
            assertEquals(2, sent.size)
            assertTrue(sent[0].second.replacing, "the project's pack, first: everything else comes off")
            assertEquals(Packs.packId("featured"), sent[1].second.id)
            assertFalse(sent[1].second.replacing, "beside it")
            assertEquals(featured.sha1, sent[1].second.sha1)
            assertEquals(
                listOf(
                    // The project's pack is sent as they join, before scripts hear of it.
                    "status\tnil\tloaded\tloaded",
                    "on demand\tfeatured",
                    "status\tfeatured\tloaded\tloaded",
                    "pushed\ttrue",
                    "again\ttrue",
                    "not one\tfalse\tresource pack \"ui\" isn't onDemand: give it \"onDemand\": true in its pack.json to push it"
                ),
                server.logs.map { it.replace(Regex("^\\S+:\\d+: "), "") }.map { line ->
                    // pcall's message carries the script's line.
                    line.replace(Regex("\\[string [^]]*]:\\d+: |m/init.lua:\\d+: "), "")
                }
            )
            val seen = server.logs.size
            server.platform.players.chat(alex, "pop")
            assertEquals(listOf("popped\ttrue"), server.logs.drop(seen))
            assertEquals(alex.ref.uuid to Packs.packId("featured"), server.platform.resourcePacks.removed.single())
        }
    }

    @Test
    fun `a rebuild from a script resends the onDemand pack to whoever has it, and only a pack that changed`() {
        server().use { server ->
            val alex = server.player("Alex")
            server.platform.raise.playerJoin(GameEvent.PlayerJoin(alex.ref, false, null))
            assertEquals(2, server.platform.resourcePacks.sent.size)
            val before = server.runtime.packs.onDemand.getValue("featured").sha1
            server.writeBytes(
                "resource_packs/featured/textures/banner.png",
                PngWriter.write(
                    Pixels(
                        2,
                        2,
                        IntArray(4) {
                            0xFF00FF00.toInt()
                        }
                    )
                )
            )
            server.platform.players.chat(alex, "rebuild")
            // At the end of the tick.
            assertEquals(before, server.runtime.packs.onDemand.getValue("featured").sha1)
            server.tick()
            val after = server.runtime.packs.onDemand.getValue("featured").sha1
            assertNotEquals(before, after)
            val resent = server.platform.resourcePacks.sent.drop(2)
            assertEquals(listOf(Packs.packId("featured")), resent.map { it.second.id }, "only the pack that changed")
            assertEquals(after, resent.single().second.sha1)
        }
    }
}
