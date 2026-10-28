package dev.netherforge.format.workspace

import dev.netherforge.format.workspace.NetworkUse.Kind
import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkUsesTest {
    @Test
    fun `finds the literal names scripts give nf_network`() {
        val source = """
            local network = nf.network
            local games = nf.network.channel("games")
            local seats = network.map 'seats'
            -- nf.network.send_player(player, "commented")
            nf.network.send_player(player, "chess", { board = 1 })
            nf.network.send_player(find(player, ","), [[terra]])
            nf.network.send_player(player, "a" .. suffix)
            nf.network.send_player(player, target)
            other.send_player(player, "nope")
            nf.network.channel(name)
        """.trimIndent()
        assertEquals(
            listOf(
                NetworkUse(Kind.CHANNEL, "games", 2),
                NetworkUse(Kind.MAP, "seats", 3),
                NetworkUse(Kind.SERVER, "chess", 5),
                NetworkUse(Kind.SERVER, "terra", 6)
            ),
            NetworkUses.of(source)
        )
    }

    @Test
    fun `a player sent to a server the network doesn't have is a warning at its line`() {
        val problems = NetworkUses.check(
            mapOf(
                "modules/lobby/init.lua" to "nf.network.send_player(p, \"hub\")\n\nnf.network.send_player(p, \"chesss\")",
                "modules/other/init.lua" to "nf.network.channel(\"x\")"
            ),
            listOf("hub", "chess")
        )
        assertEquals(
            listOf(Triple("workspace.unknown-server", "modules/lobby/init.lua", 3)),
            problems.map {
                Triple(it.code, it.file, it.line)
            }
        )
        assertEquals("There's no server \"chesss\" in the workspace's network: it has chess, hub.", problems.single().message)
    }
}
