package dev.netherforge.plugin.contract

import dev.netherforge.plugin.platform.AdvancementOps
import dev.netherforge.plugin.platform.BanSpec
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.PermissionOps
import dev.netherforge.plugin.platform.PlayerViewOps
import dev.netherforge.plugin.platform.ServerAdminOps
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [PlayerViewOps]: what one player is shown, and their own camera, compass and view distance. */
abstract class PlayerViewOpsContract : PlatformContract() {
    private val views: PlayerViewOps get() = platform.playerViews
    private val offline = UUID.randomUUID()

    @Test
    fun `blocks, equipment and books are shown to players online`() {
        val player = join()
        val pig = spawn("minecraft:pig")
        main {
            val (x, y, z) = block(at(1, 2))
            assertTrue(views.sendBlock(player.uuid, world, x, y, z, "minecraft:gold_block"))
            assertTrue(views.resetBlock(player.uuid, world, x, y, z))
            assertFalse(views.resetBlock(player.uuid, MISSING_WORLD, x, y, z))
            assertTrue(views.sendEquipment(player.uuid, pig, "head", item("minecraft:diamond")))
            assertTrue(views.sendEquipment(player.uuid, pig, "head", null))
            assertFalse(views.sendEquipment(player.uuid, UUID.randomUUID(), "head", null), "no such entity")
            assertTrue(views.openBook(player.uuid, listOf("<red>Page one", "Page two")))
            assertFalse(views.sendBlock(offline, world, x, y, z, "minecraft:gold_block"))
            assertFalse(views.resetBlock(offline, world, x, y, z))
            assertFalse(views.sendEquipment(offline, pig, "head", null))
            assertFalse(views.openBook(offline, emptyList()))
        }
    }

    @Test
    fun `a camera only in spectator mode`() {
        val player = join()
        val pig = spawn("minecraft:pig")
        main {
            assertNull(views.camera(player.uuid))
            assertFalse(views.setCamera(player.uuid, pig), "not spectating")
            assertTrue(platform.players.setGameMode(player.uuid, "spectator"))
            assertTrue(views.setCamera(player.uuid, pig))
            assertEquals(pig, views.camera(player.uuid))
            assertTrue(views.setCamera(player.uuid, null))
            assertNull(views.camera(player.uuid))
            assertFalse(views.setCamera(player.uuid, UUID.randomUUID()), "no such entity")
            assertFalse(views.setCamera(offline, null))
        }
    }

    @Test
    fun `a compass target and view distance read back as set`() {
        val player = join()
        main {
            val spawn = assertNotNull(platform.worlds.spawnLocation(world))
            assertEquals(block(spawn), block(assertNotNull(views.compassTarget(player.uuid))), "the world's spawn at first")
            assertTrue(views.setCompassTarget(player.uuid, Location(world, 10.0, 70.0, -10.0)))
            assertEquals(Triple(10, 70, -10), block(views.compassTarget(player.uuid)!!))
            assertNotNull(views.viewDistance(player.uuid))
            assertTrue(views.setViewDistance(player.uuid, 2))
            assertNull(views.compassTarget(offline))
            assertFalse(views.setCompassTarget(offline, Location(world, 0.0, 0.0, 0.0)))
            assertNull(views.viewDistance(offline))
            assertFalse(views.setViewDistance(offline, 5))
        }
        eventually("the new view distance, from a later tick") { views.viewDistance(player.uuid) == 2 }
    }
}

/** [ServerAdminOps]: the server's settings and lists. */
abstract class ServerAdminOpsContract : PlatformContract() {
    private val admin: ServerAdminOps get() = platform.serverAdmin

    @Test
    fun `settings read back as set`() {
        main {
            val max = admin.maxPlayers()
            val motd = admin.motd()
            val whitelist = admin.isWhitelistEnabled()
            afterwards {
                admin.setMaxPlayers(max)
                admin.setMotd(motd)
                admin.setWhitelistEnabled(whitelist)
            }
            admin.setMaxPlayers(42)
            assertEquals(42, admin.maxPlayers())
            admin.setMotd("<gold>Contract server")
            assertEquals("<gold>Contract server", admin.motd())
            admin.setWhitelistEnabled(true)
            assertTrue(admin.isWhitelistEnabled())
            admin.setWhitelistEnabled(false)
            assertFalse(admin.isWhitelistEnabled())
        }
    }

    @Test
    fun `who has played, and when`() {
        val player = join()
        main {
            assertTrue(admin.known().any { it == player })
            assertNotNull(admin.firstPlayed(player.uuid))
            assertNotNull(admin.lastSeen(player.uuid))
            val stranger = UUID.randomUUID()
            assertNull(admin.firstPlayed(stranger))
            assertNull(admin.lastSeen(stranger))
            assertTrue(admin.known().none { it.uuid == stranger })
        }
    }

    @Test
    fun `the whitelist`() {
        val player = join()
        main {
            afterwards { admin.setWhitelisted(player.uuid, false) }
            assertFalse(admin.isWhitelisted(player.uuid))
            admin.setWhitelisted(player.uuid, true)
            assertTrue(admin.isWhitelisted(player.uuid))
            assertTrue(admin.whitelisted().any { it == player })
            admin.setWhitelisted(player.uuid, false)
            assertFalse(admin.isWhitelisted(player.uuid))
            assertTrue(admin.whitelisted().none { it.uuid == player.uuid })
        }
    }

    @Test
    fun `a ban keeps a player out until it's lifted, and kicks them now`() {
        val player = join()
        main {
            afterwards { admin.unban(player.uuid) }
            assertFalse(admin.isBanned(player.uuid))
            admin.ban(player.uuid, BanSpec("contract", null, "contract"))
        }
        eventually("${player.name} being kicked") { platform.players.get(player.uuid) == null }
        main {
            assertTrue(admin.isBanned(player.uuid))
            assertTrue(admin.banned().any { it == player })
            assertTrue(admin.unban(player.uuid))
            assertFalse(admin.isBanned(player.uuid))
            assertFalse(admin.unban(player.uuid))
            assertTrue(admin.banned().none { it.uuid == player.uuid })
        }
        assertEquals(player, events.heard<GameEvent.PlayerQuit>("playerQuit").single().player)
    }

    @Test
    fun `a ban that has run out doesn't count`() {
        val player = join()
        leave(player)
        main {
            afterwards { admin.unban(player.uuid) }
            admin.ban(player.uuid, BanSpec("old", System.currentTimeMillis() - 60_000, "contract"))
            assertFalse(admin.isBanned(player.uuid))
            assertTrue(admin.banned().none { it.uuid == player.uuid })
        }
    }
}

/** [PermissionOps]: the project's nodes, held on players online. */
abstract class PermissionOpsContract : PlatformContract() {
    private val permissions: PermissionOps get() = platform.permissions

    @Test
    fun `nodes applied are what the player has, until replaced`() {
        val player = join()
        main {
            permissions.apply(player.uuid, mapOf("nf.contract.yes" to true, "nf.contract.no" to false))
            assertTrue(platform.players.hasPermission(player.uuid, "nf.contract.yes"))
            assertFalse(platform.players.hasPermission(player.uuid, "nf.contract.no"))
            permissions.apply(player.uuid, mapOf("nf.contract.other" to true))
            assertFalse(platform.players.hasPermission(player.uuid, "nf.contract.yes"), "replaced whole")
            assertTrue(platform.players.hasPermission(player.uuid, "nf.contract.other"))
            permissions.apply(player.uuid, emptyMap())
            assertFalse(platform.players.hasPermission(player.uuid, "nf.contract.other"))
            permissions.apply(UUID.randomUUID(), mapOf("nf.contract.yes" to true))
        }
    }

    @Test
    fun `nodes are held on the player while online, so one who leaves and comes back has none`() {
        val player = join()
        main {
            permissions.apply(player.uuid, mapOf("nf.contract.kept" to true, "nf.contract.denied" to false))
            assertTrue(platform.players.hasPermission(player.uuid, "nf.contract.kept"))
        }
        leave(player)
        val back = join(name = player.name)
        assertEquals(player.uuid, back.uuid, "the same player")
        main {
            assertFalse(platform.players.hasPermission(back.uuid, "nf.contract.kept"), "the server forgot it")
            assertFalse(platform.players.hasPermission(back.uuid, "nf.contract.denied"))
        }
    }
}

/** [AdvancementOps]: players' advancements by key. */
abstract class AdvancementOpsContract : PlatformContract() {
    private val advancements: AdvancementOps get() = platform.advancements

    @Test
    fun `the server's advancements are known by key, with their criteria`() {
        main {
            assertEquals(listOf("get_stone"), advancements.criteria(MINE_STONE))
            assertNull(advancements.criteria("minecraft:nf/no_such_advancement"))
        }
    }

    @Test
    fun `one criterion is granted and revoked on its own`() {
        val player = join()
        main {
            assertTrue(advancements.grant(player.uuid, MINE_STONE, "get_stone"))
            assertTrue(advancements.has(player.uuid, MINE_STONE))
            assertFalse(advancements.grant(player.uuid, MINE_STONE, "get_stone"), "met already")
            assertTrue(advancements.revoke(player.uuid, MINE_STONE, "get_stone"))
            assertFalse(advancements.revoke(player.uuid, MINE_STONE, "get_stone"), "not met")
            assertEquals(emptyList<String>() to listOf("get_stone"), advancements.progress(player.uuid, MINE_STONE))
        }
    }

    @Test
    fun `granting completes an advancement, which is heard, and revoking undoes it`() {
        val player = join()
        main {
            assertFalse(advancements.has(player.uuid, MINE_STONE))
            assertEquals(emptyList<String>() to listOf("get_stone"), advancements.progress(player.uuid, MINE_STONE))
            assertTrue(advancements.grant(player.uuid, MINE_STONE))
            assertTrue(advancements.has(player.uuid, MINE_STONE))
            assertEquals(listOf("get_stone") to emptyList(), advancements.progress(player.uuid, MINE_STONE))
            assertFalse(advancements.grant(player.uuid, MINE_STONE), "had it already")
            assertTrue(advancements.revoke(player.uuid, MINE_STONE))
            assertFalse(advancements.has(player.uuid, MINE_STONE))
            assertFalse(advancements.revoke(player.uuid, MINE_STONE), "had nothing")
        }
        val heard = events.heard<GameEvent.PlayerAdvancement>("playerCompleteAdvancement").filter { it.advancement == MINE_STONE }
        assertEquals(1, heard.size)
        assertEquals(player, heard.single().player)
    }

    @Test
    fun `nothing for someone offline`() {
        val offline = UUID.randomUUID()
        main {
            assertFalse(advancements.grant(offline, MINE_STONE))
            assertFalse(advancements.revoke(offline, MINE_STONE))
            assertFalse(advancements.has(offline, MINE_STONE))
            assertNull(advancements.progress(offline, MINE_STONE))
        }
    }

    private companion object {
        const val MINE_STONE = "minecraft:story/mine_stone"
    }
}
