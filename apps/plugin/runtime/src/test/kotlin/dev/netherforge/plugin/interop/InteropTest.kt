package dev.netherforge.plugin.interop
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.testkit.FakePlatform
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plugin interop: Vault (`nf.economy`) and PlaceholderAPI (`nf.placeholders`)
 * behind the platform. A package declares the plugin; a plugin the server
 * hasn't is a problem and an error at the call; one that enables after
 * NetherForge (the server's order isn't ours) makes both go away.
 */
class InteropTest {
    private val both = TestServer.manifest(requires = """{ "plugins": ["vault", "placeholderapi"] }""")

    private fun server(script: String, manifest: String = both, platform: FakePlatform = FakePlatform(), start: Boolean = true) =
        TestServer(mapOf(TestServer.MANIFEST to manifest, "modules/main/init.lua" to script), platform, start = start)

    private fun TestServer.missing(): List<String> =
        runtime.currentProblems().filter { it.code == "runtime.plugin-missing" }.map { it.message }

    // ---- presence -----------------------------------------------------------------------------

    @Test
    fun `a declared plugin the server lacks is a load problem, one per package, cleared when it enables late`() {
        server("-- nothing").use { server ->
            val problems = server.runtime.currentProblems().filter { it.code == "runtime.plugin-missing" }
            assertEquals(listOf("netherforge.json", "netherforge.json"), problems.map { it.file })
            assertEquals(listOf("$.requires.plugins", "$.requires.plugins"), problems.map { it.path })
            assertTrue(problems.any { "\"vault\"" in it.message } && problems.any { "\"placeholderapi\"" in it.message })
            assertTrue(server.platform.log.lines.any { "need the plugin \"vault\", which isn't enabled" in it })

            // Paper enables them after NetherForge, in its own order.
            server.platform.plugins.enable("Vault")
            server.tick()
            assertEquals(listOf("placeholderapi"), server.missing().map { it.substringAfter('"').substringBefore('"') })
            server.platform.plugins.enable("PlaceholderAPI")
            server.tick()
            assertEquals(emptyList(), server.missing())

            // And one that goes brings its problem back.
            server.platform.plugins.disable("vault")
            server.tick()
            assertEquals(1, server.missing().size)
        }
    }

    @Test
    fun `a plugin that is there when the project starts is no problem, and a package that declares nothing has none`() {
        val platform = FakePlatform().also { it.plugins.enable("vault") }
        server("-- nothing", TestServer.manifest(requires = """{ "plugins": ["vault"] }"""), platform).use { server ->
            server.tick()
            assertEquals(emptyList(), server.missing())
        }
        server("-- nothing", TestServer.manifest()).use { server ->
            server.tick()
            assertEquals(emptyList(), server.missing())
        }
    }

    @Test
    fun `the problem names each package that declares the plugin`() {
        val files = mapOf(
            TestServer.MANIFEST to TestServer.manifest(requires = """{ "plugins": ["vault"] }""").replace(
                "\"minecraft\"",
                "\"dependencies\": { \"chat\": { \"path\": \"../chat\" } }, \"minecraft\""
            ),
            "../chat/netherforge.json" to
                """{ "formatVersion": ${dev.netherforge.format.project.FormatVersion.CURRENT}, "name": "chat", "namespace": "chat", "version": "1.0.0", "minecraft": "26.3", "requires": { "plugins": ["vault"] } }"""
        )
        TestServer(files).use { server ->
            val declaring = server.runtime.currentProblems().filter { it.code == "runtime.plugin-missing" }.map { it.file }
            assertEquals(2, declaring.size)
            assertTrue(
                declaring.any {
                    it == "netherforge.json"
                } &&
                    declaring.any { it.endsWith(":netherforge.json") },
                declaring.toString()
            )
        }
    }

    // ---- nf.economy ----------------------------------------------------------------------------

    @Test
    fun `economy calls are errors until Vault is enabled and an economy has registered, then work, looked up at each call`() {
        val platform = FakePlatform()
        server(
            """
            nf.commands.register("pay", function(event)
              local ok, result = pcall(nf.economy.balance, event.player)
              log(tostring(ok) .. ": " .. tostring(result))
            end)
            """,
            platform = platform
        ).use { server ->
            val alex = server.player("Alex")
            platform.vault.accounts[alex.ref.uuid] = 100.0
            server.platform.commands.run(alex, "pay")
            val refusal = "nf.economy.balance needs the plugin \"vault\", which isn't enabled on this server"
            assertTrue(refusal in server.logs.last(), server.logs.last())

            // Vault enables after NetherForge, but no economy has registered with it yet.
            platform.plugins.enable("vault")
            server.platform.commands.run(alex, "pay")
            assertTrue("no economy plugin has registered" in server.logs.last(), server.logs.last())

            // The economy plugin registers later still: the next call finds it.
            platform.vault.register()
            server.platform.commands.run(alex, "pay")
            assertTrue(server.logs.last().endsWith("true: 100.0"), server.logs.last())
        }
    }

    @Test
    fun `balance, deposit and withdraw work in plain numbers for online and offline players`() {
        val platform = FakePlatform().also {
            it.plugins.enable("vault")
            it.vault.register()
        }
        val away = UUID.nameUUIDFromBytes("Away".toByteArray())
        platform.vault.accounts[away] = 20.0
        server(
            """
            nf.commands.register("econ", function(event)
              local me = event.player
              log("start " .. nf.economy.balance(me))
              log("deposit " .. nf.economy.deposit(me, 25.5))
              log("withdraw " .. nf.economy.withdraw(me, 10))
              log("too much " .. tostring(nf.economy.withdraw(me, 1000)))
              log("balance " .. nf.economy.balance(me))
              log("no account " .. tostring(nf.economy.deposit(nf.players.get("Nobody"), 5)))
              local away = nf.players.get("Away")
              log("offline " .. nf.economy.deposit(away, 5))
              log("zero " .. select(2, pcall(nf.economy.deposit, me, 0)))
              log("nan " .. select(2, pcall(nf.economy.withdraw, me, 0 / 0)))
            end)
            """,
            platform = platform
        ).use { server ->
            val alex = server.player("Alex")
            platform.vault.accounts[alex.ref.uuid] = 0.0
            platform.players.known[away] = PlayerRef(away, "Away")
            val nobody = UUID.nameUUIDFromBytes("Nobody".toByteArray())
            platform.players.known[nobody] = PlayerRef(nobody, "Nobody")
            server.platform.commands.run(alex, "econ")
            val logs = server.logs
            assertEquals(
                listOf("start 0.0", "deposit 25.5", "withdraw 15.5", "too much nil", "balance 15.5", "no account nil", "offline 25.0"),
                logs.take(7)
            )
            assertTrue("above 0" in logs[7], logs[7])
            assertTrue("above 0" in logs[8], logs[8])
            assertEquals(15.5, platform.vault.accounts[alex.ref.uuid])
        }
    }

    @Test
    fun `an undeclared package can't call even when the server has the plugin`() {
        val platform = FakePlatform().also {
            it.plugins.enable("vault")
            it.vault.register()
        }
        server(
            "nf.commands.register('t', function(e) log(select(2, pcall(nf.economy.balance, e.player))) end)",
            TestServer.manifest(),
            platform
        ).use { server ->
            server.platform.commands.run(server.player("Alex"), "t")
            assertTrue("needs plugin:vault, which package \"test\" hasn't declared" in server.logs.last(), server.logs.last())
        }
    }

    // ---- nf.placeholders -----------------------------------------------------------------------

    private fun papi(): FakePlatform = FakePlatform().also { it.plugins.enable("placeholderapi") }

    @Test
    fun `parse fills placeholders for a player, and register offers our own to every plugin`() {
        val platform = papi()
        platform.placeholders.others["player_name"] = "Alex"
        server(
            """
            nf.placeholders.register("testshop", function(key, player)
              if key == "sales" then return 12 end
              if key == "price" then return 2.5 end
              if key == "owner" then return player and player:name() or "nobody" end
              if key == "flag" then return true end
              if key == "boom" then error("no") end
            end)
            nf.commands.register("p", function(event)
              log(nf.placeholders.parse("%player_name% sold %testshop_sales% at %testshop_price% for %testshop_owner%, %testshop_unknown%", event.player))
              log(nf.placeholders.parse("%testshop_owner%"))
            end)
            """,
            platform = platform
        ).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "p")
            assertEquals(listOf("Alex sold 12 at 2.5 for Alex, %testshop_unknown%", "nobody"), server.logs)
            // Another plugin reads it the way PlaceholderAPI asks.
            assertEquals("true", platform.placeholders.request("testshop_flag"))
            assertNull(platform.placeholders.request("testshop_boom"))
            assertTrue(server.errors.any { "placeholder %testshop_boom%" in it.message })
        }
    }

    @Test
    fun `off the main thread a placeholder answers what its callback last gave, never running Lua`() {
        val platform = papi()
        server(
            """
            local calls = 0
            nf.placeholders.register("testcount", function(key, player)
              calls = calls + 1
              return calls
            end)
            """,
            platform = platform
        ).use { server ->
            val alex = server.player("Alex").ref.uuid
            val pool = Executors.newSingleThreadExecutor()
            try {
                val off = { pool.submit<String?> { platform.placeholders.request("testcount_a", alex) }.get(10, TimeUnit.SECONDS) }
                // Nothing has asked from the main thread yet: no value, and no Lua was entered (it would throw WrongThread).
                assertNull(off())
                assertEquals("1", platform.placeholders.request("testcount_a", alex))
                assertEquals("1", off())
                assertEquals("2", platform.placeholders.request("testcount_a", alex))
                assertEquals("2", off())
                // Another key or player has its own answer.
                assertNull(pool.submit<String?> { platform.placeholders.request("testcount_b", alex) }.get(10, TimeUnit.SECONDS))
                assertNull(pool.submit<String?> { platform.placeholders.request("testcount_a", null) }.get(10, TimeUnit.SECONDS))
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `a callback that parses its own placeholder is answered nil rather than recursing`() {
        val platform = papi()
        server(
            """
            nf.placeholders.register("testloop", function(key)
              return nf.placeholders.parse("<%testloop_" .. key .. "%>")
            end)
            """,
            platform = platform
        ).use { _ ->
            assertEquals("<%testloop_x%>", platform.placeholders.request("testloop_x"))
        }
    }

    @Test
    fun `register refuses what PlaceholderAPI can't hold and what is taken`() {
        val platform = papi()
        platform.placeholders.taken += "testtaken"
        server(
            """
            local function try(name) log(name .. ": " .. tostring(select(2, pcall(nf.placeholders.register, name, function() end)))) end
            try("Shop")
            try("my_shop")
            try("")
            try("testtaken")
            try("luckperms")
            nf.placeholders.register("testmine", function() end)
            try("testmine")
            """,
            platform = platform
        ).use { server ->
            val logs = server.logs
            assertTrue("can't be a placeholder namespace" in logs[0], logs[0])
            assertTrue("can't be a placeholder namespace" in logs[1], logs[1])
            assertTrue("can't be a placeholder namespace" in logs[2], logs[2])
            assertTrue("already used by another plugin" in logs[3], logs[3])
            assertTrue("start with its own namespace, \"test\"" in logs[4], logs[4])
            assertTrue("already registered by module main" in logs[5], logs[5])
            assertEquals(setOf("testmine"), platform.placeholders.registered)
        }
    }

    @Test
    fun `without PlaceholderAPI register and parse are errors, until it enables`() {
        val platform = FakePlatform()
        server(
            """
            nf.commands.register("t", function(event)
              log(select(2, pcall(nf.placeholders.parse, "%a%", event.player)))
              log(tostring(pcall(nf.placeholders.register, "testlate", function() end)))
            end)
            """,
            platform = platform
        ).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "t")
            assertTrue("nf.placeholders.parse needs the plugin \"placeholderapi\", which isn't enabled" in server.logs[0], server.logs[0])
            assertEquals("false", server.logs[1])
            platform.plugins.enable("placeholderapi")
            server.platform.commands.run(alex, "t")
            assertEquals("true", server.logs[3])
        }
    }

    @Test
    fun `expansions are the session's, gone with a reload, offered again by the new script, and again after PlaceholderAPI reloads`() {
        val platform = papi()
        server("nf.placeholders.register('testone', function() return 'a' end)", platform = platform).use { server ->
            assertEquals("a", platform.placeholders.request("testone_x"))
            server.write("modules/main/init.lua", "nf.placeholders.register('testone', function() return 'b' end)")
            server.reload("modules/main/init.lua")
            assertEquals("b", platform.placeholders.request("testone_x"))
            assertEquals(setOf("testone"), platform.placeholders.registered)

            // PlaceholderAPI forgets everything when it's reloaded; the runtime offers them again.
            platform.placeholders.forgetAll()
            platform.plugins.disable("placeholderapi")
            platform.plugins.enable("placeholderapi")
            server.tick()
            assertEquals("b", platform.placeholders.request("testone_x"))

            server.write("modules/main/init.lua", "-- none now")
            server.reload("modules/main/init.lua")
            assertEquals(emptySet(), platform.placeholders.registered)
        }
    }

    @Test
    fun `a restart takes the session's expansions away`() {
        val platform = papi()
        server("nf.placeholders.register('testone', function() return 'a' end)", platform = platform).use { server ->
            assertEquals(setOf("testone"), platform.placeholders.registered)
            server.runtime.reloadAll()
            assertEquals(setOf("testone"), platform.placeholders.registered)
            server.restart()
            assertEquals(setOf("testone"), platform.placeholders.registered)
        }
        assertEquals(emptySet(), platform.placeholders.registered)
    }
}
