package dev.netherforge.plugin.session
import dev.netherforge.format.project.FormatVersion
import dev.netherforge.format.project.Requirement
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.lua.LuaApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Declared capabilities on the server: the one check every capability goes
 * through (held to the calling package), and the tree's sum, which whoever
 * runs the server sees in the log and in `/nf requires`.
 */
class RequirementsTest {
    private fun manifest(namespace: String, more: String = "") =
        """{ "formatVersion": ${FormatVersion.CURRENT}, "name": "$namespace", "namespace": "$namespace", "version": "1.0.0", "minecraft": "26.3"$more }"""

    /** A project needing moderation and one host, over `chat` (a host and a plugin), over `store` (a database). */
    private val tree = mapOf(
        "netherforge.json" to manifest(
            "test",
            """, "requires": { "moderation": true, "http": ["discord.com"] }, "dependencies": { "chat": { "path": "../chat" } }"""
        ),
        "../chat/netherforge.json" to manifest(
            "chat",
            """, "requires": { "http": ["*.discord.com", "discord.com"], "plugins": ["vault"] }, "dependencies": { "store": { "path": "../store" } }"""
        ),
        "../store/netherforge.json" to manifest("store", """, "requires": { "db": true }""")
    )

    @Test
    fun `the tree's requirements are logged at load and listed by nf requires`() {
        TestServer(tree).use { server ->
            assertTrue(
                "INFO Requires (by its packages' netherforge.json): moderation (test); db (store); " +
                    "http:*.discord.com (chat); http:discord.com (test, chat); plugin:vault (chat)" in server.platform.log.lines,
                server.platform.log.lines.joinToString("\n")
            )
            val alex = server.player("Alex")
            assertEquals(
                listOf(
                    "<gold>The project and its packages require:",
                    "<yellow>moderation <gray>declared by test",
                    "<yellow>db <gray>declared by store",
                    "<yellow>http:*.discord.com <gray>declared by chat",
                    "<yellow>http:discord.com <gray>declared by test, chat",
                    "<yellow>plugin:vault <gray>declared by chat"
                ),
                server.platform.commands.run(alex, "nf requires")
            )
        }
    }

    @Test
    fun `a project that needs nothing says so`() {
        TestServer(mapOf(TestServer.MANIFEST to TestServer.manifest())).use { server ->
            assertTrue("INFO Requires nothing beyond what every project may do" in server.platform.log.lines)
            assertEquals(
                listOf("<gray>The project and its packages require nothing beyond what every project may do."),
                server.platform.commands.run(server.player("Alex"), "nf requires")
            )
        }
    }

    /**
     * The entry point future capabilities call (`nf.http.request` with its
     * URL's host, `nf.db`, a plugin's functions): held to the package whose
     * code runs, which outside any script is the project.
     */
    @Test
    fun `the check holds the calling package to its own declaration, naming what to add`() {
        TestServer(tree).use { server ->
            val requirements = server.runtime.session.requirements
            requirements.check(Requirement.Moderation, "Player:ban")
            requirements.check(Requirement.Http("discord.com"), "nf.http.request")
            requirements.check("http", "nf.http.request")
            val host = assertFailsWith<LuaApiException> { requirements.check(Requirement.Http("api.discord.com"), "nf.http.request") }
            assertEquals(
                "nf.http.request needs http:api.discord.com, which package \"test\" hasn't declared: " +
                    "add \"requires\": { \"http\": [\"api.discord.com\"] } to its netherforge.json",
                host.message
            )
            val db = assertFailsWith<LuaApiException> { requirements.check(Requirement.Db, "nf.db") }
            assertEquals(
                "nf.db needs db, which package \"test\" hasn't declared: add \"requires\": { \"db\": true } to its netherforge.json",
                db.message
            )
            val plugin = assertFailsWith<LuaApiException> { requirements.check("plugin:vault", "nf.economy.balance") }
            assertTrue("add \"requires\": { \"plugins\": [\"vault\"] }" in plugin.message!!, plugin.message)
        }
    }

    /** What a script of a package can do is what that package declared, whoever called it. */
    @Test
    fun `a package's script is held to the package's declaration, not the project's`() {
        val files = tree + mapOf(
            "netherforge.json" to manifest(
                "test",
                """, "requires": { "moderation": true }, "dependencies": { "chat": { "path": "../chat" } }"""
            ),
            "../chat/netherforge.json" to manifest("chat", """, "exports": { "modules": ["api"] }"""),
            "../chat/modules/api/init.lua" to """
                local api = {}
                function api.ban(name) nf.players.get(name):ban() end
                -- A tail call: its frame is gone, so the stack shows the project's code calling.
                function api.sneak(name) return nf.players.get(name):ban() end
                return api
            """,
            "modules/main/init.lua" to """
                local api = require("chat:api")
                local function mine(name) return nf.players.get(name):ban() end
                nf.commands.register("go", function()
                  log(select(2, pcall(api.ban, "Alex")))
                  log(select(2, pcall(api.sneak, "Alex")))
                  log(select(2, pcall(mine, "Alex")))
                  nf.players.get("Alex"):ban()
                  log("banned by the project")
                end)
            """
        )
        TestServer(files, start = false).use { server ->
            server.player("Alex")
            server.start()
            server.platform.commands.runAsConsole("go")
            val hidden = "Player:ban was called as a tail call (`return` and the call), so NetherForge can't tell which " +
                "package's code called it, and not every package declares moderation: call it on a line of its own, or wrap it in parentheses"
            assertEquals(
                listOf(
                    "Player:ban needs moderation, which package \"chat\" hasn't declared: " +
                        "add \"requires\": { \"moderation\": true } to its netherforge.json",
                    hidden,
                    // The project's own tail call can't be told from a package's either.
                    hidden,
                    "banned by the project"
                ),
                server.logs
            )
        }
    }

    /**
     * A coroutine whose body is the primitive itself has no project frame on its
     * stack: it's held to the package whose code made the coroutine, never to the
     * project (which declared more).
     */
    @Test
    fun `a call from a coroutine with no project frame is held to the package that made it`() {
        val files = tree + mapOf(
            "netherforge.json" to manifest(
                "test",
                """, "requires": { "moderation": true }, "dependencies": { "chat": { "path": "../chat" } }"""
            ),
            "../chat/netherforge.json" to manifest("chat", """, "exports": { "modules": ["api"] }"""),
            "../chat/modules/api/init.lua" to """
                local api = {}
                function api.go()
                  local p = nf.players.get("Alex")
                  log(select(2, pcall(coroutine.wrap(p.ban), p)))
                  log(select(2, pcall(coroutine.wrap(coroutine.wrap(p.ban)), p)))
                  local co = coroutine.create(p.ban)
                  log(select(2, coroutine.resume(co, p)))
                  log(tostring(p:is_banned()))
                end
                return api
            """,
            "modules/main/init.lua" to """
                local api = require("chat:api")
                nf.commands.register("go", function() api.go() end)
            """
        )
        TestServer(files, start = false).use { server ->
            server.player("Alex")
            server.start()
            server.platform.commands.runAsConsole("go")
            val refused = "Player:ban needs moderation, which package \"chat\" hasn't declared: " +
                "add \"requires\": { \"moderation\": true } to its netherforge.json"
            assertEquals(listOf(refused, refused, refused, "false"), server.logs)
        }
    }

    /** Console commands can do anything the console can, so they're a requirement of their own. */
    @Test
    fun `nf server run needs console`() {
        TestServer(
            mapOf(
                TestServer.MANIFEST to TestServer.manifest(requires = """{ "moderation": true }"""),
                "modules/t/init.lua" to "log(select(2, pcall(nf.server.run, \"op Alex\")))"
            )
        ).use { server ->
            assertEquals(
                listOf(
                    "nf.server.run needs console, which package \"test\" hasn't declared: " +
                        "add \"requires\": { \"console\": true } to its netherforge.json"
                ),
                server.logs
            )
            assertTrue(server.platform.commands.console.isEmpty(), server.platform.commands.console.toString())
        }
    }

    /** A tail call is fine where every package declares what it needs: whoever's code it was may. */
    @Test
    fun `a tail call passes when every package declares the requirement`() {
        val files = mapOf(
            "netherforge.json" to manifest(
                "test",
                """, "requires": { "moderation": true }, "dependencies": { "chat": { "path": "../chat" } }"""
            ),
            "../chat/netherforge.json" to manifest("chat", """, "requires": { "moderation": true }, "exports": { "modules": ["api"] }"""),
            "../chat/modules/api/init.lua" to """
                local api = {}
                function api.sneak(name) return nf.players.get(name):ban() end
                return api
            """,
            "modules/main/init.lua" to """
                local api = require("chat:api")
                nf.commands.register("go", function()
                  api.sneak("Alex")
                  log("banned")
                end)
            """
        )
        TestServer(files, start = false).use { server ->
            server.player("Alex")
            server.start()
            server.platform.commands.runAsConsole("go")
            assertEquals(listOf("banned"), server.logs)
        }
    }

    /** `nf.db` is `requires: "db"` in the spec: held to the package that calls it, a dependency included. */
    @Test
    fun `nf db needs the calling package to declare db`() {
        fun files(projectRequires: String, libRequires: String) = mapOf(
            "netherforge.json" to manifest("test", """$projectRequires, "dependencies": { "lib": { "path": "../lib" } }"""),
            "../lib/netherforge.json" to manifest("lib", """$libRequires, "exports": { "modules": ["api"] }"""),
            "../lib/modules/api/init.lua" to "return { open = function() local db = nf.db() return \"opened\" end }",
            "modules/main/init.lua" to """
                local api = require("lib:api")
                nf.commands.register("go", function()
                  local ok, err = pcall(function() local db = nf.db() end)
                  log(ok and "opened" or err)
                  ok, err = pcall(api.open)
                  log(err)
                end)
            """
        )
        fun refusal(namespace: String) =
            "nf.db needs db, which package \"$namespace\" hasn't declared: add \"requires\": { \"db\": true } to its netherforge.json"
        val declared = """, "requires": { "db": true }"""
        // The project declares db and the library doesn't: the library's call is refused, the project's isn't.
        TestServer(files(declared, ""), start = false).use { server ->
            server.player("Alex")
            server.start()
            server.platform.commands.runAsConsole("go")
            assertEquals(listOf("opened", refusal("lib")), server.logs)
        }
        // The project declares nothing and the library does: the other way round.
        TestServer(files("", declared), start = false).use { server ->
            server.player("Alex")
            server.start()
            server.platform.commands.runAsConsole("go")
            assertEquals(listOf(refusal("test"), "opened"), server.logs)
        }
    }
}
