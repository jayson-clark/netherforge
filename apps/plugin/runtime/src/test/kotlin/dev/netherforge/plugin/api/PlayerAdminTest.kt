package dev.netherforge.plugin.api
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.BanSpec
import dev.netherforge.plugin.platform.GameEvent
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Per-player and server-level control against the fake server: permissions
 * the project grants (held to `allow.permissions`, kept across restarts,
 * given back on join), moderation (only with `requires.moderation`), when people
 * played, advancements, and what only one player is shown.
 */
class PlayerAdminTest {
    /** A server whose `/run` runs [body], with [allow] and [requires] in its manifest; Alex is online before it starts. */
    private fun server(body: String, allow: String? = null, requires: String? = null) = TestServer(
        mapOf(
            TestServer.MANIFEST to TestServer.manifest(allow = allow, requires = requires),
            "modules/t/init.lua" to LuaChecks.command(body)
        ),
        start = false
    ).also {
        it.player("Alex")
        it.start()
    }

    /** What `/run` logged and any script errors, after running it once. */
    private fun TestServer.run(): List<String> {
        val before = logs.size
        platform.commands.runConsole("run")
        return errors.map { "ERROR ${it.message}" } + logs.drop(before)
    }

    /** Runs [body] once on a fresh server and hands back what it logged. */
    private fun run(body: String, allow: String? = null, requires: String? = null, after: (TestServer) -> Unit = {}): List<String> =
        server(body, allow, requires).use { server ->
            server.run().also {
                assertEquals(listOf("done"), it)
                after(server)
            }
        }

    private fun TestServer.alex() = platform.players.byId.values.first { it.ref.name == "Alex" }

    private fun TestServer.leave(name: String) {
        val player = platform.players.byId.values.first { it.ref.name == name }
        platform.players.byId.remove(player.ref.uuid)
        platform.raise.playerQuit(GameEvent.PlayerQuit(player.ref, null))
    }

    private fun TestServer.rejoin(name: String) {
        val ref = platform.players.known.values.first { it.name == name }
        platform.players.add(ref.name)
        platform.raise.playerJoin(GameEvent.PlayerJoin(ref, false, null))
    }

    // ---- permissions -------------------------------------------------------------

    @Test
    fun `a project grants and denies only the nodes netherforge json allows`() {
        val result = run(
            """
            local alex = nf.players.get("Alex")
            check("before", alex:has_permission("shop.vip"), false)
            alex:set_permission("shop.vip", true)
            check("granted", alex:has_permission("shop.vip"), true)
            alex:set_permission("shop", true)
            alex:set_permission("perks.fly", false)
            check("denied", alex:has_permission("perks.fly"), false)
            local set = alex:permissions()
            check("listed vip", set["shop.vip"], true)
            check("listed fly", set["perks.fly"], false)
            fails("not allowed", function() alex:set_permission("minecraft.command.op", true) end,
              "Player:set_permission needs permission \"minecraft.command.op\", which package \"test\" hasn't allowed: add it (or a node above it) to allow.permissions in its netherforge.json (it lists: perks, shop)")
            fails("only under a whole part", function() alex:set_permission("shopkeeper", true) end, "needs permission \"shopkeeper\"")
            fails("not a node", function() alex:set_permission("Shop VIP", true) end, "\"Shop VIP\" isn't a permission node")
            check("unset", alex:unset_permission("shop.vip"), true)
            check("unset again", alex:unset_permission("shop.vip"), false)
            check("gone", alex:has_permission("shop.vip"), false)
            check("left", alex:permissions()["shop.vip"], nil)
            """,
            allow = """{ "permissions": ["shop", "perks"] }"""
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `a project that allows no permissions can't set any`() {
        val result = run(
            """fails("none", function() nf.players.get("Alex"):set_permission("shop.vip", true) end, "(it lists none)")"""
        )
        assertEquals(listOf("done"), result)
    }

    /** `allow.permissions` is held to the package whose code calls, as `requires` is: each package's own list. */
    @Test
    fun `a package grants only the nodes its own netherforge json allows`() {
        fun manifest(namespace: String, more: String) =
            """{ "formatVersion": 1, "name": "$namespace", "namespace": "$namespace", "version": "1.0.0", "minecraft": "26.3"$more }"""
        val files = mapOf(
            "netherforge.json" to manifest(
                "test",
                """, "allow": { "permissions": ["shop"] }, "dependencies": { "perks": { "path": "../perks" } }"""
            ),
            "../perks/netherforge.json" to manifest(
                "perks",
                """, "allow": { "permissions": ["perks"] }, "exports": { "modules": ["api"] }"""
            ),
            "../perks/modules/api/init.lua" to """
                local api = {}
                function api.grant(node) nf.players.get("Alex"):set_permission(node, true) end
                function api.sneak(node) return nf.players.get("Alex"):set_permission(node, true) end
                return api
            """,
            "modules/main/init.lua" to """
                local api = require("perks:api")
                nf.commands.register("go", function()
                  local alex = nf.players.get("Alex")
                  api.grant("perks.fly")
                  log("package granted " .. tostring(alex:has_permission("perks.fly")))
                  log(select(2, pcall(api.grant, "shop.vip")))
                  log(select(2, pcall(api.sneak, "perks.fly")))
                  alex:set_permission("shop.vip", true)
                  log(select(2, pcall(function() alex:set_permission("perks.fly2", true) end)))
                  log("project granted " .. tostring(alex:has_permission("shop.vip")))
                end)
            """
        )
        TestServer(files, start = false).use { server ->
            server.player("Alex")
            server.start()
            server.platform.commands.runAsConsole("go")
            assertEquals(
                listOf(
                    "package granted true",
                    "Player:set_permission needs permission \"shop.vip\", which package \"perks\" hasn't allowed: " +
                        "add it (or a node above it) to allow.permissions in its netherforge.json (it lists: perks)",
                    "Player:set_permission was called as a tail call (`return` and the call), " +
                        "so NetherForge can't tell which package's code " +
                        "called it, and not every package declares permission \"perks.fly\": " +
                        "call it on a line of its own, or wrap it in parentheses",
                    "Player:set_permission needs permission \"perks.fly2\", which package \"test\" hasn't allowed: " +
                        "add it (or a node above it) to allow.permissions in its netherforge.json (it lists: shop)",
                    "project granted true"
                ),
                server.logs
            )
            assertEquals(mapOf("perks.fly" to true, "shop.vip" to true), server.alex().granted)
        }
    }

    @Test
    fun `grants outlast leaving and restarts, apply on join, and stop with the project`() {
        server(
            """nf.players.get("Alex"):set_permission("shop.vip", true)""",
            allow = """{ "permissions": ["shop"] }"""
        ).use { server ->
            assertEquals(listOf("done"), server.run())
            assertEquals(mapOf("shop.vip" to true), server.alex().granted)
            assertEquals(
                mapOf(server.alex().ref.uuid to mapOf("shop.vip" to true)),
                server.runtime.store.permissions.of("test"),
                "kept in the store, under the project's namespace"
            )

            server.leave("Alex")
            server.rejoin("Alex")
            assertEquals(mapOf("shop.vip" to true), server.alex().granted, "given back on join")

            server.restart()
            assertEquals(mapOf("shop.vip" to true), server.alex().granted, "kept across a restart")

            server.runtime.disable()
            assertEquals(emptyMap(), server.alex().granted, "taken away when the project stops")
            server.start()
        }
    }

    @Test
    fun `a grant the manifest no longer allows stops applying, and comes back with it`() {
        server(
            """nf.players.get("Alex"):set_permission("shop.vip", true)""",
            allow = """{ "permissions": ["shop"] }"""
        ).use { server ->
            server.run()
            server.write(TestServer.MANIFEST, TestServer.manifest(allow = """{ "permissions": ["perks"] }"""))
            server.reload(TestServer.MANIFEST)
            assertEquals(emptyMap(), server.alex().granted)
            server.write(TestServer.MANIFEST, TestServer.manifest(allow = """{ "permissions": ["shop"] }"""))
            server.reload(TestServer.MANIFEST)
            assertEquals(mapOf("shop.vip" to true), server.alex().granted)
        }
    }

    @Test
    fun `a grant to someone offline waits for them`() {
        server(
            """
            local alex = nf.players.get("Alex")
            alex:set_permission("shop.vip", true)
            check("kept", alex:permissions()["shop.vip"], true)
            check("not online", alex:has_permission("shop.vip"), false)
            """,
            allow = """{ "permissions": ["shop"] }"""
        ).use { server ->
            server.leave("Alex")
            assertEquals(listOf("done"), server.run())
            server.rejoin("Alex")
            assertEquals(mapOf("shop.vip" to true), server.alex().granted)
        }
    }

    // ---- moderation ----------------------------------------------------------------

    @Test
    fun `moderation needs requires moderation, and reading doesn't`() {
        val needs = "needs moderation, which package \"test\" hasn't declared: " +
            "add \"requires\": { \"moderation\": true } to its netherforge.json"
        val result = run(
            """
            local alex = nf.players.get("Alex")
            fails("ban", function() alex:ban() end, [[Player:ban $needs]])
            fails("unban", function() alex:unban() end, [[Player:unban $needs]])
            fails("whitelist", function() alex:set_whitelisted(true) end, [[Player:set_whitelisted $needs]])
            fails("motd", function() nf.server.set_motd("x") end, [[nf.server.set_motd $needs]])
            fails("max", function() nf.server.set_max_players(5) end, [[nf.server.set_max_players $needs]])
            fails("whitelist on", function() nf.server.set_whitelist_enabled(true) end, [[nf.server.set_whitelist_enabled $needs]])
            check("banned", alex:is_banned(), false)
            check("whitelisted", alex:is_whitelisted(), false)
            check("motd", nf.server.motd(), "A Minecraft Server")
            check("max", nf.server.max_players(), 20)
            check("whitelist on", nf.server.is_whitelist_enabled(), false)
            """
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `a ban kicks them with its reason, lists them, and lifts`() {
        server(
            """
            local alex = nf.players.get("Alex")
            fails("past", function() alex:ban({ expires = 1 }) end, "options.expires is in the past")
            fails("unknown key", function() alex:ban({ until_time = 1 }) end, "until_time")
            alex:ban({ reason = "Griefing", expires = nf.server.unix_time() + 86400000 })
            check("banned", alex:is_banned(), true)
            check("offline", alex:exists(), false)
            check("listed", nf.players.banned()[1], alex)
            check("unban", alex:unban(), true)
            check("unban again", alex:unban(), false)
            check("lifted", alex:is_banned(), false)
            check("unlisted", #nf.players.banned(), 0)
            """,
            requires = """{ "moderation": true }"""
        ).use { server ->
            val alex = server.alex()
            assertEquals(listOf("done"), server.run())
            assertEquals("Griefing", alex.kicked)
        }
    }

    @Test
    fun `a ban without options is the server's own, by NetherForge`() {
        server("""nf.players.get("Alex"):ban()""", requires = """{ "moderation": true }""").use { server ->
            val alex = server.alex()
            assertEquals(listOf("done"), server.run())
            assertEquals(BanSpec(null, null, "NetherForge"), server.platform.serverAdmin.bans[alex.ref.uuid])
            assertEquals("You are banned from this server.", alex.kicked)
        }
    }

    @Test
    fun `the whitelist, the message of the day and the most players change`() {
        val result = run(
            """
            local alex = nf.players.get("Alex")
            alex:set_whitelisted(true)
            check("whitelisted", alex:is_whitelisted(), true)
            check("listed", nf.players.whitelisted()[1], alex)
            nf.server.set_whitelist_enabled(true)
            check("on", nf.server.is_whitelist_enabled(), true)
            check("not kicked", alex:exists(), true)
            alex:set_whitelisted(false)
            check("off the list", #nf.players.whitelisted(), 0)
            nf.server.set_motd("<gold>Event tonight")
            check("motd", nf.server.motd(), "<gold>Event tonight")
            nf.server.set_max_players(50)
            check("max", nf.server.max_players(), 50)
            fails("negative", function() nf.server.set_max_players(-1) end, "count must be at least 0, not -1")
            """,
            requires = """{ "moderation": true }"""
        )
        assertEquals(listOf("done"), result)
    }

    // ---- played times -------------------------------------------------------------

    @Test
    fun `when people played, and everyone who has`() {
        server(
            """
            local alex = nf.players.get("Alex")
            local blake = nf.players.get("Blake")
            check("first", alex:first_played(), 1000)
            check("online is now", alex:last_seen(), 1800000000000)
            check("offline", blake:last_seen(), 5000)
            check("known", #nf.players.known(), 2)
            local stranger = nf.players.whitelisted()[1]
            check("never played", stranger:first_played(), nil)
            check("never seen", stranger:last_seen(), nil)
            """
        ).use { server ->
            val alex = server.alex().ref.uuid
            val blake = server.platform.players.add("Blake").ref.uuid
            server.platform.players.byId.remove(blake)
            server.platform.serverAdmin.now = 1_800_000_000_000L
            server.platform.serverAdmin.played[alex] = 1000L to 2000L
            server.platform.serverAdmin.played[blake] = 3000L to 5000L
            val stranger = UUID.randomUUID()
            server.platform.serverAdmin.whitelist += stranger
            server.platform.serverAdmin.names[stranger] = "Stranger"
            assertEquals(listOf("done"), server.run())
        }
    }

    // ---- advancements ---------------------------------------------------------------

    @Test
    fun `advancements are granted, read and revoked, by key`() {
        val result = run(
            """
            local alex = nf.players.get("Alex")
            check("has", alex:has_advancement("minecraft:story/mine_diamond"), false)
            check("grant", alex:grant_advancement("minecraft:story/mine_diamond"), true)
            check("again", alex:grant_advancement("minecraft:story/mine_diamond"), false)
            check("has now", alex:has_advancement("minecraft:story/mine_diamond"), true)
            local progress = alex:advancement_progress("minecraft:adventure/adventuring_time")
            check("done", #progress.done, 0)
            check("remaining", #progress.remaining, 3)
            check("first", progress.remaining[1], "minecraft:plains")
            check("revoke", alex:revoke_advancement("minecraft:story/mine_diamond"), true)
            check("revoke again", alex:revoke_advancement("minecraft:story/mine_diamond"), false)
            fails("unknown", function() alex:has_advancement("minecraft:story/mine_gold") end, "the server has no advancement \"minecraft:story/mine_gold\"")
            fails("bare is the project's", function() alex:has_advancement("story/mine_diamond") end, "isn't an advancement")
            fails("not a key", function() alex:has_advancement("Not A Key") end, "\"Not A Key\" isn't an advancement")
            """
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `advancements answer false and nil for someone offline, but a typo is still an error`() {
        server(
            """
            local alex = nf.players.get("Alex")
            check("grant", alex:grant_advancement("minecraft:story/mine_diamond"), false)
            check("progress", alex:advancement_progress("minecraft:story/mine_diamond"), nil)
            fails("unknown", function() alex:has_advancement("minecraft:story/nope") end, "no advancement")
            """
        ).use { server ->
            server.leave("Alex")
            assertEquals(listOf("done"), server.run())
        }
    }

    // ---- what only they see -----------------------------------------------------------

    @Test
    fun `fake blocks and equipment reach only that player`() {
        server(
            """
            local alex = nf.players.get("Alex")
            local pig = nf.worlds.default():spawn_entity("minecraft:pig", vec3(2, 64, 2))
            check("block", alex:send_block_change(vec3(10.5, 64, 10), "minecraft:gold_block"), true)
            check("elsewhere", alex:send_block_change(nf.worlds.get("nether"):spawn_location(), "minecraft:stone"), true)
            check("reset", alex:reset_block(vec3(10, 64, 10)), true)
            fails("bad state", function() alex:send_block_change(vec3(0, 64, 0), "minecraft:nope") end, "state: no block \"minecraft:nope\"")
            check("equipment", alex:send_equipment_change(pig, "head", { kind = "minecraft:bread" }), true)
            check("empty", alex:send_equipment_change(pig, "main_hand"), true)
            """
        ).use { server ->
            assertEquals(listOf("done"), server.run())
            val views = server.platform.playerViews
            val alex = server.alex().ref.uuid
            assertEquals<Map<String, String>?>(mapOf("nether 0 64 0" to "minecraft:stone"), views.blocks[alex])
            val shown = views.equipment.getValue(alex)
            assertEquals("minecraft:bread", shown.entries.first { it.key.endsWith(" head") }.value?.def?.kind)
            assertEquals(null, shown.entries.first { it.key.endsWith(" main_hand") }.value)
        }
    }

    @Test
    fun `the camera, the compass, a book and the view distance`() {
        val result = run(
            """
            local alex = nf.players.get("Alex")
            local pig = nf.worlds.default():spawn_entity("minecraft:pig", vec3(2, 64, 2))
            check("not spectating", alex:set_camera(pig), false)
            alex:set_game_mode("spectator")
            check("camera", alex:set_camera(pig), true)
            check("watching", alex:camera(), pig)
            check("own eyes", alex:set_camera(), true)
            check("no camera", alex:camera(), nil)

            check("compass default", alex:compass_target().position, vec3(0, 64, 0))
            local spot = nf.worlds.default():location(vec3(100, 70, -20))
            check("compass", alex:set_compass_target(spot), true)
            check("compass now", alex:compass_target().position, vec3(100, 70, -20))

            check("book", alex:open_book({ "<b>One", "Two" }), true)
            local pages = {}
            for i = 1, 101 do pages[i] = "p" end
            fails("long book", function() alex:open_book(pages) end, "a book has at most 100 pages, not 101")

            check("distance", alex:view_distance(), 10)
            check("set distance", alex:set_view_distance(6), true)
            check("distance now", alex:view_distance(), 6)
            fails("too far", function() alex:set_view_distance(64) end, "distance must be from 2 to 32 chunks, not 64")
            """
        ) { server ->
            assertEquals<List<List<String>>?>(listOf(listOf("<b>One", "Two")), server.platform.playerViews.books[server.alex().ref.uuid])
        }
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `what only they see answers false and nil while they're offline`() {
        server(
            """
            local alex = nf.players.get("Alex")
            check("block", alex:send_block_change(vec3(0, 64, 0), "minecraft:stone"), false)
            check("camera", alex:camera(), nil)
            check("compass", alex:compass_target(), nil)
            check("book", alex:open_book({ "x" }), false)
            check("distance", alex:view_distance(), nil)
            """
        ).use { server ->
            server.leave("Alex")
            assertEquals(listOf("done"), server.run())
            assertTrue(server.platform.playerViews.books.isEmpty())
        }
    }
}
