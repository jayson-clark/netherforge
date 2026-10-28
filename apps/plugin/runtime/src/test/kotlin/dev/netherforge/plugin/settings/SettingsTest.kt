package dev.netherforge.plugin.settings
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.bridge.ServerSettings
import dev.netherforge.format.dialog.BooleanInput
import dev.netherforge.format.dialog.DialogType
import dev.netherforge.format.dialog.OptionInput
import dev.netherforge.format.dialog.RangeInput
import dev.netherforge.format.dialog.TextInput
import dev.netherforge.format.project.FormatVersion
import dev.netherforge.plugin.NetherForgeRuntime
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.GameEvent
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Server-owner settings: `nf.config`, the values file, `setting_changed` or a restart, `/nf settings` and its dialog. */
class SettingsTest {
    private fun manifest(namespace: String, settings: String, more: String = "") =
        """{ "formatVersion": ${FormatVersion.CURRENT}, "name": "$namespace game", "namespace": "$namespace", "version": "1.0.0", "minecraft": "26.3", "settings": $settings$more }"""

    private val settings = """
        {
          "rounds": { "type": "integer", "description": "Rounds a game lasts.", "default": 3, "min": 1, "max": 10 },
          "ratio": { "type": "number", "description": "A share.", "default": 0.5 },
          "pvp": { "type": "boolean", "description": "Players may hurt each other.", "default": false },
          "motd": { "type": "string", "description": "The greeting.", "default": "Hi" },
          "size": { "type": "choice", "description": "How big.", "default": "small", "choices": ["small", "large"] }
        }
    """

    /** A module that listens, one that reads without listening, and one that never reads. */
    private val modules = mapOf(
        "modules/listener/init.lua" to """
            local rounds = nf.config("rounds")
            log("listener starts", rounds)
            nf.on("setting_changed", function(event)
              log("heard", event.setting, event.value, math.type(event.value), event.previous)
            end)
        """,
        "modules/reader/init.lua" to """
            log("reader starts", nf.config("rounds"), nf.config("size"))
            nf.on("unload", function() log("reader unloads") end)
        """,
        "modules/bystander/init.lua" to """
            log("bystander starts")
        """
    )

    private fun project(extra: Map<String, Any> = emptyMap()): Map<String, Any> =
        mapOf("netherforge.json" to manifest("test", settings)) + modules + extra

    private val TestServer.file: Path get() = state.resolve("settings/test.json")

    private val TestServer.settings get() = runtime.session.settings

    @Test
    fun `nf config gives each setting's default, typed, and the owner's value from the server's file`() {
        val reads = "modules/reads/init.lua" to """
            for _, name in ipairs({ "rounds", "ratio", "pvp", "motd", "size" }) do
              local value = nf.config(name)
              log(name, value, math.type(value) or type(value))
            end
            local ok, problem = pcall(function() return nf.config("nope") end)
            log(ok, problem)
        """
        TestServer(mapOf("netherforge.json" to manifest("test", settings), reads)).use { server ->
            assertEquals(
                listOf(
                    "rounds\t3\tinteger",
                    "ratio\t0.5\tfloat",
                    "pvp\tfalse\tboolean",
                    "motd\tHi\tstring",
                    "size\tsmall\tstring",
                    "false\tthere's no setting \"nope\" in netherforge.json's settings " +
                        "(it has motd, pvp, ratio, rounds, size)"
                ),
                server.logs
            )
            server.state.resolve("settings").createDirectories()
            server.file.writeText("""{ "rounds": 7, "ratio": 1, "pvp": true, "motd": "Welcome!", "size": "large" }""")
            server.restart()
            assertEquals(
                listOf("rounds\t7\tinteger", "ratio\t1.0\tfloat", "pvp\ttrue\tboolean", "motd\tWelcome!\tstring", "size\tlarge\tstring"),
                server.logs.takeLast(6).dropLast(1)
            )
        }
    }

    @Test
    fun `a change is heard by the scripts that listen, restarts those that read it, and leaves the rest`() {
        TestServer(project()).use { server ->
            assertEquals(
                listOf("listener starts\t3", "reader starts\t3\tsmall", "bystander starts"),
                server.logs.sorted().let {
                    listOf(it[1], it[2], it[0])
                }
            )
            val before = server.logs.size
            assertTrue(server.settings.set("test", "rounds", JsonPrimitive(5)))
            assertEquals(
                listOf("heard\trounds\t5\tinteger\t3", "reader unloads", "reader starts\t5\tsmall"),
                server.logs.drop(before)
            )
            assertEquals("{\n  \"rounds\": 5\n}\n", server.file.readText())

            // A setting nobody read: heard, and nothing restarts.
            val next = server.logs.size
            server.settings.set("test", "pvp", JsonPrimitive(true))
            assertEquals(listOf("heard\tpvp\ttrue\tnil\tfalse"), server.logs.drop(next))

            // The same value again changes nothing; back to the default takes it out of the file.
            assertFalse(server.settings.set("test", "rounds", JsonPrimitive(5)))
            val reset = server.logs.size
            assertTrue(server.settings.set("test", "rounds", null))
            assertEquals(listOf("heard\trounds\t3\tinteger\t5", "reader unloads", "reader starts\t3\tsmall"), server.logs.drop(reset))
            assertEquals("{\n  \"pvp\": true\n}\n", server.file.readText())
            server.settings.set("test", "pvp", null)
            assertFalse(server.file.exists())
        }
    }

    @Test
    fun `a value a setting can't have is refused with what it expects, and nothing changes`() {
        TestServer(project()).use { server ->
            val before = server.logs.size
            val error = assertFailsWith<IllegalArgumentException> { server.settings.set("test", "rounds", JsonPrimitive(11)) }
            assertEquals("rounds: 11 isn't a whole number from 1 to 10", error.message)
            assertEquals(
                "size: \"huge\" isn't one of small, large",
                assertFailsWith<IllegalArgumentException> {
                    server.settings.setText("test", "size", "huge")
                }.message
            )
            assertTrue(
                assertFailsWith<IllegalArgumentException> {
                    server.settings.set("test", "nope", JsonPrimitive(1))
                }.message!!.startsWith("there's no setting \"nope\"")
            )
            assertTrue(
                assertFailsWith<IllegalArgumentException> {
                    server.settings.set("other", "rounds", JsonPrimitive(1))
                }.message!!.startsWith("no package \"other\"")
            )
            assertEquals(before, server.logs.size)
            assertFalse(server.file.exists())
        }
    }

    @Test
    fun `a package's settings are its own, and only its scripts hear them change`() {
        val lib = mapOf(
            "../lib/netherforge.json" to
                manifest("lib", """{ "rounds": { "type": "integer", "description": "The library's rounds.", "default": 100 } }"""),
            "../lib/modules/api/init.lua" to """
                log("lib starts", nf.config("rounds"))
                nf.on("setting_changed", function(event) log("lib heard", event.setting, event.value) end)
                return {}
            """
        )
        val files = project(lib) + mapOf(
            "netherforge.json" to manifest("test", settings, """, "dependencies": { "lib": { "path": "../lib" } }""")
        )
        TestServer(files).use { server ->
            assertTrue("lib starts\t100" in server.logs, "${server.logs}")
            val before = server.logs.size
            server.settings.set("lib", "rounds", JsonPrimitive(42))
            assertEquals(listOf("lib heard\trounds\t42"), server.logs.drop(before))
            assertEquals("{\n  \"rounds\": 42\n}\n", server.state.resolve("settings/lib.json").readText())
            val project = server.logs.size
            server.settings.set("test", "motd", JsonPrimitive("Yo"))
            assertEquals(listOf("heard\tmotd\tYo\tnil\tHi"), server.logs.drop(project))
            assertEquals(listOf("test", "lib"), server.settings.namespaces())
        }
    }

    @Test
    fun `a hand-edited file reports what doesn't fit, keeps it, and reload announces what changed`() {
        TestServer(project(), start = false).use { server ->
            server.state.resolve("settings").createDirectories()
            server.file.writeText("""{ "rounds": "many", "gone": 1, "size": "large" }""")
            server.start()
            assertTrue("reader starts\t3\tlarge" in server.logs)
            val problems = server.runtime.currentProblems().filter { it.code?.startsWith("settings.") == true }
            assertEquals(listOf(ProblemCodes.SETTINGS_VALUE.code, ProblemCodes.SETTINGS_UNKNOWN.code), problems.map { it.code })
            assertEquals("$.settings.rounds", problems[0].path)
            assertEquals("netherforge.json", problems[0].file)

            // Setting another value writes back what the owner wrote, even what doesn't fit.
            server.settings.set("test", "pvp", JsonPrimitive(true))
            assertEquals("{\n  \"gone\": 1,\n  \"pvp\": true,\n  \"rounds\": \"many\",\n  \"size\": \"large\"\n}\n", server.file.readText())

            server.file.writeText("""{ "rounds": 2 }""")
            val before = server.logs.size
            assertEquals(3, server.settings.reload())
            assertEquals(
                listOf(
                    "heard\tpvp\tfalse\tnil\ttrue",
                    "heard\trounds\t2\tinteger\t3",
                    "heard\tsize\tsmall\tnil\tlarge",
                    "reader unloads",
                    "reader starts\t2\tsmall"
                ),
                server.logs.drop(before).sorted().let {
                    it.filter { line -> line.startsWith("heard") } +
                        it.filterNot { line -> line.startsWith("heard") }.reversed()
                }
            )
            assertTrue(server.runtime.currentProblems().none { it.code?.startsWith("settings.") == true })

            server.file.writeText("[1, 2]")
            server.settings.reload()
            assertEquals(
                listOf(ProblemCodes.SETTINGS_FILE.code),
                server.runtime.currentProblems().mapNotNull {
                    it.code?.takeIf { code -> code.startsWith("settings.") }
                }
            )
            assertTrue(
                assertFailsWith<IllegalStateException> {
                    server.settings.set("test", "pvp", JsonPrimitive(true))
                }.message!!.contains("isn't a JSON object")
            )
            assertEquals("[1, 2]", server.file.readText())
        }
    }

    @Test
    fun `nf settings lists, sets, resets and reloads, a value with spaces and all`() {
        TestServer(project()).use { server ->
            val alex = server.player("Alex")
            val commands = server.platform.commands
            val listed = commands.runAsConsole("nf settings list")
            assertEquals("<gold>test game <gray>(test, settings/test.json)", listed.first())
            assertTrue("<yellow>rounds <white>3 <gray>(default) <dark_gray>Rounds a game lasts." in listed, "$listed")

            assertEquals(listOf("<green>motd is now Hello  there."), commands.run(alex, "nf settings set motd Hello  there"))
            assertEquals(JsonPrimitive("Hello  there"), server.settings.values("test").first { it.first == "motd" }.third)
            assertEquals(listOf("<gray>motd was already Hello  there."), commands.run(alex, "nf settings set motd Hello  there"))
            assertEquals(
                listOf("<red>rounds: \"lots\" isn't a whole number from 1 to 10"),
                commands.run(alex, "nf settings set rounds lots")
            )
            assertEquals(listOf("<green>test:size is now large."), commands.run(alex, "nf settings set test:size large"))
            assertTrue("<yellow>size <white>large <gray>(default small) <dark_gray>How big." in commands.run(alex, "nf settings list test"))
            assertEquals(listOf("<green>size is back to its default, small."), commands.run(alex, "nf settings reset size"))
            assertEquals(listOf("<green>Read the settings files again: 0 settings changed."), commands.run(alex, "nf settings reload"))

            alex.permissions += NetherForgeRuntime.PERMISSION
            assertEquals(listOf("list", "reload", "reset", "set"), commands.complete(alex, "nf settings ").sorted())
            assertEquals(listOf("size"), commands.complete(alex, "nf settings set si"))
            assertEquals(listOf("large", "small"), commands.complete(alex, "nf settings set size ").sorted())
        }
    }

    @Test
    fun `the settings dialog shows each setting as its kind of input, and Save sets what changed`() {
        TestServer(project()).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "nf settings")
            val shown = server.platform.dialogs.showing.getValue(alex.ref.uuid)
            assertEquals("netherforge:settings/test", shown.id)
            assertEquals(DialogType.CONFIRMATION, shown.file.type)
            val inputs = shown.file.inputs.associateBy { it.key }
            assertEquals(listOf("motd", "pvp", "ratio", "rounds", "size"), inputs.keys.toList())
            assertEquals(3.0, assertIs<RangeInput>(inputs["rounds"]).initial)
            assertEquals(false, assertIs<BooleanInput>(inputs["pvp"]).initial)
            assertEquals("0.5", assertIs<TextInput>(inputs["ratio"]).initial)
            assertEquals("small", assertIs<OptionInput>(inputs["size"]).options.single { it.initial == true }.id)

            val before = server.logs.size
            server.platform.dialogs.press(
                alex,
                "save",
                mapOf("motd" to "Hi", "pvp" to "true", "ratio" to "two", "rounds" to 6.0, "size" to "large")
            )
            assertEquals(
                listOf(
                    "<red>ratio: \"two\" isn't a number; it stays 0.5",
                    "<green>pvp is now true",
                    "<green>rounds is now 6",
                    "<green>size is now large"
                ),
                alex.messages.takeLast(4)
            )
            // One restart for both settings the reader read.
            assertEquals(1, server.logs.drop(before).count { it.startsWith("reader starts") })
            assertEquals("reader starts\t6\tlarge", server.logs.last { it.startsWith("reader starts") })

            // Cancel changes nothing.
            server.platform.commands.run(alex, "nf settings")
            server.platform.dialogs.press(alex, "cancel", mapOf("rounds" to 1.0))
            assertEquals(JsonPrimitive(6L), server.settings.values("test").first { it.first == "rounds" }.third)
        }
    }

    @Test
    fun `with several packages the dialog is a list of each package's form`() {
        val files = project(
            mapOf(
                "../lib/netherforge.json" to
                    manifest("lib", """{ "loud": { "type": "boolean", "description": "Shout.", "default": false } }""")
            )
        ) + ("netherforge.json" to manifest("test", settings, """, "dependencies": { "lib": { "path": "../lib" } }"""))
        TestServer(files).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "nf settings")
            val shown = server.platform.dialogs.showing.getValue(alex.ref.uuid)
            assertEquals(DialogType.DIALOG_LIST, shown.file.type)
            assertEquals(listOf("netherforge:settings/test", "netherforge:settings/lib"), shown.listed.map { it.id })
            server.platform.dialogs.press(alex, "save", mapOf("loud" to "true"), dialog = "netherforge:settings/lib")
            assertEquals("<green>loud is now true", alex.messages.last())
            assertEquals("{\n  \"loud\": true\n}\n", server.state.resolve("settings/lib.json").readText())
        }
    }

    @Test
    fun `the editor reads the settings and sets one over the bridge, and hears every change`() {
        TestServer(project()).use { server ->
            val state = server.runtime.settings()
            val rounds = state.packages.single().settings.getValue("rounds")
            assertEquals(JsonPrimitive(3), rounds.value)
            assertFalse(rounds.set)
            // The OS's own path, for the editor to open: separators differ on Windows.
            val file = Path.of(state.packages.single().file)
            assertTrue(file.endsWith(Path.of("settings", "test.json")), "$file")

            server.sent.clear()
            server.settings.set("test", "rounds", JsonPrimitive(4))
            val heard = server.sent.filterIsInstance<ServerSettings>().last()
            assertEquals(JsonPrimitive(4L), heard.packages.single().settings.getValue("rounds").value)
            assertTrue(heard.packages.single().settings.getValue("rounds").set)
        }
    }

    @Test
    fun `examples showcase reads its settings and its library's, and a change restarts what read them`() {
        TestServer(TestServer.example("showcase")).use { server ->
            assertTrue(server.runtime.session.running, "${server.runtime.currentProblems()}")
            assertEquals(listOf("showcase", "library"), server.settings.namespaces())
            val alex = server.player("Alex")
            server.platform.raise.playerJoin(GameEvent.PlayerJoin(alex.ref, false, null))
            assertEquals(listOf("<green>Welcome, Alex!", "<aqua>Well met, Alex!"), alex.messages.take(2))

            // The greeter listens, so it isn't restarted: the next join reads the new greeting.
            server.settings.setText("showcase", "greeting", "Hello")
            // The library's phrases read its style as they loaded: they restart, and the modules that require them.
            server.settings.setText("library", "style", "casual")
            val bea = server.player("Bea")
            server.platform.raise.playerJoin(GameEvent.PlayerJoin(bea.ref, false, null))
            assertEquals(listOf("<green>Hello, Bea!", "<aqua>Hey, Bea!"), bea.messages.take(2))
            assertEquals(emptyList(), server.errors.map { it.message })
        }
    }
}
