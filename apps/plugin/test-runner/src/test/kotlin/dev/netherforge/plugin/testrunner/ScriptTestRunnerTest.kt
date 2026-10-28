package dev.netherforge.plugin.testrunner

import dev.netherforge.format.project.FormatVersion
import dev.netherforge.plugin.WasmFixtures
import dev.netherforge.plugin.testkit.FakePlatform
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The runner on small projects written to a temp folder: what passes, fails and errors, and how each is reported. */
class ScriptTestRunnerTest {
    private val manifest = """
        {
          "${'$'}schema": ".netherforge/schema/netherforge.schema.json",
          "formatVersion": ${FormatVersion.CURRENT},
          "name": "Test",
          "namespace": "test",
          "version": "1.0.0",
          "minecraft": "26.3"
        }
    """.trimIndent()

    /** [files]: text by path, or a ByteArray for a binary file (a module). */
    private fun project(files: Map<String, Any>): Path {
        val root = Files.createTempDirectory("netherforge-runner")
        root.resolve("netherforge.json").writeText(((files["netherforge.json"] as String?) ?: manifest) + "\n")
        for ((path, contents) in files - "netherforge.json") {
            val file = root.resolve(path)
            file.parent.createDirectories()
            if (contents is ByteArray) file.writeBytes(contents) else file.writeText((contents as String).trimIndent() + "\n")
        }
        return root
    }

    private fun run(files: Map<String, Any>, filter: String? = null): Pair<List<TestEvent>, RunFinished> {
        val root = project(files)
        try {
            val events = mutableListOf<TestEvent>()
            val end = ScriptTestRunner(root, FakePlatform.GAME).run(filter) { events += it }
            return events to end
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun List<TestEvent>.results() = filterIsInstance<TestResult>()

    /** Every test's status, in order; a mismatch shows what the tests said. */
    private fun expectStatuses(events: List<TestEvent>, vararg expected: Status) = assertEquals(
        expected.toList(),
        events.results().map { it.status },
        events.results().joinToString { it.message.orEmpty() + (it.source?.let { place -> " at $place" } ?: "") }
    )

    @Test
    fun `a passing test passes and an assert that fails is reported at its line`() {
        val (events, end) = run(
            mapOf(
                "tests/basic_test.lua" to """
                    nf.test.case("adds up", function()
                      assert(1 + 1 == 2)
                    end)

                    nf.test.case("does not add up", function()
                      assert(1 + 1 == 3, "one and one is two")
                    end)
                """
            )
        )
        val results = events.results()
        assertEquals(listOf("adds up", "does not add up"), results.map { it.name })
        assertEquals(Status.PASSED, results[0].status)
        val failed = results[1]
        assertEquals(Status.FAILED, failed.status)
        assertEquals(Place("tests/basic_test.lua", 6), failed.source)
        assertTrue("one and one is two" in failed.message.orEmpty(), failed.message)
        assertEquals(RunFinished(1, 1, 0, end.durationMillis), end)
    }

    @Test
    fun `each test runs on a server of its own`() {
        val (events, _) = run(
            mapOf(
                "tests/isolation_test.lua" to """
                    nf.test.case("a leaves a timer and a player behind", function()
                      nf.test.player("Alex")
                      nf.after(1, function() end)
                      nf.data("shop").sales = 5
                    end)

                    nf.test.case("b finds nothing of it", function()
                      assert(#nf.players.online() == 0, "a player was left behind")
                      assert(nf.data("shop").sales == nil, "saved data was left behind")
                    end)
                """
            )
        )
        expectStatuses(events, Status.PASSED, Status.PASSED)
    }

    @Test
    fun `advance runs ticks and timers fall due`() {
        val (events, _) = run(
            mapOf(
                "tests/time_test.lua" to """
                    nf.test.case("a repeating timer", function()
                      local count = 0
                      nf.every(20, function() count = count + 1 end)
                      nf.test.advance(60)
                      assert(count == 3, "count was " .. count)
                    end)

                    nf.test.case("a tick handler", function()
                      local ticks = 0
                      nf.on("tick", function() ticks = ticks + 1 end)
                      nf.test.advance(5)
                      assert(ticks == 5, "ticks was " .. ticks)
                    end)
                """
            )
        )
        expectStatuses(events, Status.PASSED, Status.PASSED)
    }

    @Test
    fun `each call advance runs has its own budget, and one that runs out is reported as the server would`() {
        val (events, _) = run(
            mapOf(
                "modules/busy/init.lua" to """
                    -- About 5,000 instructions a tick: 300 ticks of it is far more than one call's budget.
                    nf.on("tick", function()
                      local n = 0
                      for i = 1, 1500 do n = n + i end
                    end)
                """,
                "tests/budget_test.lua" to """
                    nf.test.case("a long advance", function()
                      nf.test.advance(300)
                    end)

                    nf.test.case("a handler that runs out", function()
                      nf.on("tick", function()
                        while true do end
                      end)
                      nf.test.advance(1)
                      log("still running")
                    end)
                """
            )
        )
        // Ran past its own budget, not the test's: the test goes on, and errors for it, at the handler's line.
        expectStatuses(events, Status.PASSED, Status.ERRORED)
        val failed = events.results()[1]
        assertTrue("tick handler failed: ran past its budget of 200000 instructions" in failed.message.orEmpty(), failed.message)
        assertEquals(Place("tests/budget_test.lua", 7), failed.source)
        assertTrue(failed.logs.any { "still running" in it }, failed.logs.toString())
    }

    @Test
    fun `a fake player joins, and raised events reach handlers as the server's do`() {
        val (events, _) = run(
            mapOf(
                "modules/greeter/init.lua" to """
                    nf.on("player_join", function(event)
                      event.message = "<yellow>hello " .. event.player:name()
                    end)
                    nf.on("player_chat", function(event)
                      if event.message == "spam" then event:cancel() end
                    end)
                """,
                "tests/events_test.lua" to """
                    nf.test.case("joining raises player_join", function()
                      local seen
                      nf.on("player_join", function(event) seen = event.player:name() end)
                      local alex = nf.test.player("Alex")
                      assert(seen == "Alex", "heard " .. tostring(seen))
                      assert(alex:name() == "Alex")
                    end)

                    nf.test.case("a raised chat can be cancelled", function()
                      local alex = nf.test.player("Alex")
                      local spam = nf.test.raise("player_chat", { player = alex, message = "spam", format = "<message>" })
                      assert(spam.cancelled)
                      local fine = nf.test.raise("player_chat", { player = alex, message = "hi", format = "<message>" })
                      assert(not fine.cancelled)
                      assert(fine.event.message == "hi")
                    end)

                    nf.test.case("a bad payload is an error naming the fields", function()
                      local ok, err = pcall(nf.test.raise, "player_chat", { nope = 1 })
                      assert(not ok)
                      assert(err:find("unknown field"), err)
                    end)
                """
            )
        )
        expectStatuses(events, Status.PASSED, Status.PASSED, Status.PASSED)
    }

    @Test
    fun `a raised event is heard by the handle it is about before nf, in the server's order`() {
        val (events, _) = run(
            mapOf(
                "tests/order_test.lua" to """
                    nf.test.case("the player hears chat first, and can stop it", function()
                      local alex = nf.test.player("Alex")
                      local heard = {}
                      alex:on("chat", function(event)
                        heard[#heard + 1] = "player"
                        event:stop()
                      end)
                      nf.on("player_chat", function() heard[#heard + 1] = "nf" end)
                      nf.test.raise("player_chat", { player = alex, message = "hi", format = "<message>" })
                      assert(table.concat(heard, ",") == "player", table.concat(heard, ","))
                    end)

                    nf.test.case("an event with no payload is raised bare", function()
                      local ticks = 0
                      nf.on("tick", function() ticks = ticks + 1 end)
                      nf.test.raise("tick", { tick = 1 })
                      assert(ticks == 1)
                    end)

                    nf.test.case("nf.test.case can't be called from a test", function()
                      local ok, err = pcall(nf.test.case, "inner", function() end)
                      assert(not ok and err:find("test file's own code"), tostring(err))
                    end)
                """
            )
        )
        expectStatuses(events, Status.PASSED, Status.PASSED, Status.PASSED)
    }

    @Test
    fun `nf_test_terrain stands in for a terrain script's terrain`() {
        val (events, _) = run(
            mapOf(
                "modules/worldgen/init.lua" to """
                    local M = {}
                    function M.init(terrain)
                      M.ridges = terrain.noise("ridges")
                      M.towns = terrain.plan("towns", 64, function(cell_x, cell_z) return { x = cell_x, at = math.random(1, 1000000) } end)
                      M.terrain = terrain
                    end
                    function M.kind(x, z) return M.ridges:at(x, z) > 0.5 and "mountains" or "plains" end
                    return M
                """,
                "tests/terrain_test.lua" to """
                    local worldgen = require("worldgen")
                    nf.test.case("the stand-in answers what it's told", function()
                      worldgen.init(nf.test.terrain({ noises = { ridges = 0.9 }, height = function(x, z) return 60 + x end, sea_level = 40 }))
                      assert(worldgen.kind(0, 0) == "mountains")
                      local t = worldgen.terrain
                      assert(t.height(5, 0) == 65 and t.sea_level() == 40 and t.min_y() == -64 and t.max_y() == 319)
                      assert(t.noise("other"):at(1, 2, 3) == 0 and t.area(0, 0) == "default" and t.biome(0, 0, 0) == "minecraft:plains")
                      local a = worldgen.towns:get(2, 3)
                      assert(a.x == 2 and worldgen.towns:get(2, 3) == a, "made once")
                      local same, cell_x = worldgen.towns:at(130, 200)
                      assert(same == a and cell_x == 2)
                      assert(worldgen.towns:size() == 64)
                      worldgen.init(nf.test.terrain({ noises = { ridges = function(x) return x > 10 and 1 or 0 end } }))
                      assert(worldgen.kind(0, 0) == "plains" and worldgen.kind(11, 0) == "mountains")
                      assert(worldgen.towns:get(2, 3).at == a.at, "a cell's numbers are its own")
                    end)
                """
            )
        )
        expectStatuses(events, Status.PASSED)
    }

    @Test
    fun `click, release and input raise what the server would, with the held item filled in`() {
        val (events, _) = run(
            mapOf(
                "items/wand/item.json" to """{ "kind": "minecraft:stick", "script": { "file": "script.lua" } }""",
                "items/wand/script.lua" to """
                    -- What the wand heard, where the test (another script) can read it.
                    local function heard(player, what)
                      local session = assert(player:session())
                      session.wand = (session.wand and session.wand .. ", " or "") .. what
                    end
                    this:on("interact", function(event) heard(event.player, "interact " .. event.click) end)
                    this:on("use", function(event)
                      heard(event.player, "use " .. event.hand)
                      if event.player:name() == "Steve" then event:cancel() end
                    end)
                """,
                "tests/input_test.lua" to """
                    local function listen()
                      _G.heard = {}
                      nf.on("player_interact", function(event)
                        _G.heard[#_G.heard + 1] = "interact " .. event.click .. " " .. tostring(event.item and event.item.kind) .. " " .. tostring(event.block and event.block:kind()) .. " " .. tostring(event.face)
                      end)
                      nf.on("player_use_item", function(event) _G.heard[#_G.heard + 1] = "use " .. event.item.kind .. " " .. event.hand end)
                      nf.on("player_stop_using_item", function(event) _G.heard[#_G.heard + 1] = "stop " .. event.item.kind .. " " .. event.ticks end)
                      nf.on("player_input", function(event)
                        _G.heard[#_G.heard + 1] = "input " .. tostring(event.forward) .. " " .. tostring(event.jump) .. " " .. tostring(event.sneak)
                      end)
                    end
                    local function said() return table.concat(_G.heard, ", ") end

                    nf.test.case("a right click with a project item goes through its script, then use", function()
                      listen()
                      local alex = nf.test.player("Alex")
                      alex:set_held_item(nf.items.create("wand"))
                      assert(nf.test.click(alex, "right"))
                      assert(alex:session().wand == "interact right, use main_hand", tostring(alex:session().wand))
                      assert(said() == "interact right minecraft:stick nil nil, use minecraft:stick main_hand", said())
                      local steve = nf.test.player("Steve")
                      steve:set_held_item(nf.items.create("wand"))
                      assert(not nf.test.click(steve, "right"), "the wand's script cancels Steve's use")
                      -- Cancelled, not stopped: nf still hears it, cancelled.
                      assert(said() == "interact right minecraft:stick nil nil, use minecraft:stick main_hand, interact right minecraft:stick nil nil, use minecraft:stick main_hand", said())
                    end)

                    nf.test.case("a left click on a block, the off hand, an empty hand", function()
                      listen()
                      local alex = nf.test.player("Alex")
                      local world = nf.worlds.default()
                      world:set_block(vec3(0, 64, 0), "minecraft:stone")
                      assert(nf.test.click(alex, "left", { block = assert(world:block(vec3(0, 64, 0))), face = "north" }))
                      assert(nf.test.click(alex, "right", { hand = "off_hand" }))
                      assert(said() == "interact left nil minecraft:stone north, interact right nil nil nil", said())
                      local ok, err = pcall(nf.test.click, alex, "right", { face = "up" })
                      assert(not ok and err:find("only with options.block"), tostring(err))
                    end)

                    nf.test.case("release lets go of what they hold, and input says which keys", function()
                      listen()
                      local alex = nf.test.player("Alex")
                      alex:set_off_hand_item({ kind = "minecraft:bow" })
                      nf.test.release(alex, 12)
                      nf.test.input(alex, { forward = true, jump = true })
                      nf.test.input(alex, {})
                      assert(said() == "stop minecraft:bow 12, input true true false, input false false false", said())
                      alex:set_off_hand_item(nil)
                      local ok, err = pcall(nf.test.release, alex, 5)
                      assert(not ok and err:find("nothing in their hands"), tostring(err))
                    end)
                """
            )
        )
        expectStatuses(events, Status.PASSED, Status.PASSED, Status.PASSED)
    }

    @Test
    fun `a script of the project that errors while a test runs errors the test, at the script's line`() {
        val (events, _) = run(
            mapOf(
                "modules/broken/init.lua" to """
                    nf.on("tick", function()
                      error("the broken module")
                    end)
                """,
                "tests/broken_test.lua" to """
                    nf.test.case("ticks", function()
                      nf.test.advance(1)
                    end)
                """
            )
        )
        val result = events.results().single()
        assertEquals(Status.ERRORED, result.status)
        assertEquals("modules/broken/init.lua", result.source?.file)
        assertEquals(2, result.source?.line)
        assertTrue("the broken module" in result.message.orEmpty(), result.message)
    }

    @Test
    fun `a module that fails to start errors every test`() {
        val (events, end) = run(
            mapOf(
                "modules/broken/init.lua" to "error('no start')",
                "tests/a_test.lua" to """
                    nf.test.case("one", function() end)
                    nf.test.case("two", function() end)
                """
            )
        )
        assertEquals(listOf(Status.ERRORED, Status.ERRORED), events.results().map { it.status })
        assertTrue("while the project started" in events.results().first().message.orEmpty())
        assertEquals(2, end.errored)
    }

    @Test
    fun `a test file that does not run to its end is an error at its line`() {
        val (events, end) = run(
            mapOf(
                "tests/bad_test.lua" to """
                    nf.test.case("declared", function() end)
                    local x = nil + 1
                """,
                "tests/good_test.lua" to """nf.test.case("fine", function() end)"""
            )
        )
        val bad = events.results().first()
        assertEquals("tests/bad_test.lua", bad.file)
        assertEquals(Status.ERRORED, bad.status)
        assertEquals(Place("tests/bad_test.lua", 2), bad.source)
        assertEquals(1, end.passed)
    }

    @Test
    fun `a test can require the project's modules and the files beside it`() {
        val (events, _) = run(
            mapOf(
                "modules/math2/init.lua" to """return { double = function(n) return n * 2 end }""",
                "tests/helper.lua" to """return { five = 5 }""",
                "tests/uses_test.lua" to """
                    local math2 = require("math2")
                    local helper = require("helper")
                    nf.test.case("requires", function()
                      assert(math2.double(helper.five) == 10)
                    end)
                """
            )
        )
        expectStatuses(events, Status.PASSED)
    }

    @Test
    fun `the filter picks tests by file and name`() {
        val files = mapOf(
            "tests/a_test.lua" to """
                nf.test.case("alpha", function() end)
                nf.test.case("beta", function() end)
            """,
            "tests/b_test.lua" to """nf.test.case("gamma", function() end)"""
        )
        assertEquals(listOf("beta"), run(files, "beta").first.results().map { it.name })
        assertEquals(listOf("gamma"), run(files, "b_test").first.results().map { it.name })
        assertEquals(3, run(files).first.results().size)
    }

    @Test
    fun `two tests of one name are an error`() {
        val (events, _) = run(
            mapOf("tests/twice_test.lua" to "nf.test.case('same', function() end)\nnf.test.case('same', function() end)")
        )
        val result = events.results().single()
        assertEquals(Status.ERRORED, result.status)
        assertTrue("already a test named" in result.message.orEmpty(), result.message)
        assertEquals(Place("tests/twice_test.lua", 2), result.source)
    }

    @Test
    fun `a project with nothing to test runs no tests`() {
        val (events, end) = run(emptyMap())
        assertEquals(emptyList(), events.results())
        assertTrue(end.ok)
    }

    @Test
    fun `events are JSON lines the editor reads back`() {
        val (events, _) = run(mapOf("tests/j_test.lua" to "nf.test.case('x', function() assert(false, 'boom') end)"))
        val lines = events.map { EventJson.encodeToString(TestEvent.serializer(), it) }
        assertTrue(lines.first().startsWith("""{"event":"start""""), lines.first())
        val failed = lines.filter { """"event":"result"""" in it && """"status":"failed"""" in it && """"line":1""" in it }
        assertEquals(1, failed.size, lines.joinToString("\n"))
        assertEquals(events, lines.map { EventJson.decodeFromString(TestEvent.serializer(), it) })
    }

    @Test
    fun `JUnit XML carries each verdict`() {
        val (events, end) = run(
            mapOf(
                "tests/x_test.lua" to """
                    nf.test.case("ok", function() end)
                    nf.test.case("bad <one>", function() error("nope & more") end)
                """
            )
        )
        val xml = JUnitReport.write(events.results(), end.durationMillis)
        assertTrue("""<testsuite name="tests/x_test.lua" tests="2" failures="1" errors="0"""" in xml, xml)
        assertTrue("""name="bad &lt;one&gt;"""" in xml, xml)
        assertTrue("nope &amp; more" in xml, xml)
        // It's well-formed XML.
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
    }

    @Test
    fun `a project the runtime refuses is a refusal, not a failed test`() {
        val root = project(emptyMap())
        try {
            root.resolve("netherforge.json").writeText(manifest.replace("26.3", "1.8") + "\n")
            val error = kotlin.runCatching { ScriptTestRunner(root, FakePlatform.GAME.copy(minecraft = "1.8")).run { } }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error is ScriptTestRunner.Refused, error.toString())
            assertTrue("can't run" in error.message.orEmpty(), error.message)
        } finally {
            root.toFile().deleteRecursively()
        }
        assertNull(null)
    }

    @Test
    fun `game data of another Minecraft version than the project's is refused`() {
        val root = project(emptyMap())
        try {
            val error = kotlin.runCatching {
                ScriptTestRunner(root, FakePlatform.GAME.copy(minecraft = "1.21.11")).run { }
            }.exceptionOrNull()
            assertTrue(error is ScriptTestRunner.Refused && "targets 26.3" in error.message.orEmpty(), error.toString())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a project runs on the game data it is given`() {
        val files = mapOf(
            "items/charm/item.json" to """{ "kind": "minecraft:echo_shard", "name": "Charm" }""",
            "tests/g_test.lua" to """
                nf.test.case('gives the charm', function()
                    local alex = nf.test.player('Alex')
                    alex:give_item({ item = 'charm' })
                    assert(alex:inventory():count_item({ item = 'charm' }) == 1)
                end)
            """
        )
        val root = project(files)
        try {
            fun statuses(game: dev.netherforge.format.game.GameDataBundle): List<Status> {
                val events = mutableListOf<TestEvent>()
                ScriptTestRunner(root, game).run { events += it }
                return events.results().map { it.status }
            }
            // The item is outside the small fixture, so the fixture can't give it...
            assertEquals(listOf(Status.FAILED), statuses(FakePlatform.GAME))
            // ...and a game whose item registry has it can.
            val items = FakePlatform.GAME.registries.getValue("minecraft:item")
            val game = FakePlatform.GAME.copy(
                registries =
                FakePlatform.GAME.registries + ("minecraft:item" to items + "minecraft:echo_shard")
            )
            assertEquals(listOf(Status.PASSED), statuses(game))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `shown is a stack as the player's game was sent it, after the view handlers`() {
        val (events, _) = run(
            mapOf(
                "items/ruby/item.json" to """{ "kind": "minecraft:diamond", "lore": ["A gem."], "script": { "file": "script.lua" } }""",
                "items/ruby/script.lua" to """
                    local this = this --[[@as ProjectItem]]
                    this:on("view", function(event)
                      local menu = event.player:menu()
                      if menu and menu:kind() == "shop" then
                        event.lore = { event.lore[1], "<gold>Sells for 5" }
                      end
                    end)
                """,
                "menus/shop/menu.json" to """
                    { "rows": 1, "script": { "file": "script.lua" }, "slots": { "4": { "item": { "item": "ruby" } } } }
                """,
                "menus/shop/script.lua" to """
                    local this = this --[[@as Menu]]
                    this:on("open", function(event) event.player:refresh_items() end)
                    this:on("close", function(event) event.player:refresh_items() end)
                """,
                "tests/view_test.lua" to """
                    nf.test.case("the shop prices the player's own rubies", function()
                      local alex = nf.test.player("Alex")
                      local sam = nf.test.player("Sam")
                      alex:inventory():set_item(0, nf.items.create("ruby"))
                      sam:inventory():set_item(0, nf.items.create("ruby"))
                      nf.test.advance(1)
                      assert(#nf.test.shown(alex, 0).lore == 1, "no shop open")
                      alex:open_menu("shop")
                      nf.test.advance(1)
                      assert(nf.test.shown(alex, 0).lore[2] == "<gold>Sells for 5", "their own ruby")
                      assert(nf.test.shown(alex, 4, { menu = true }).lore[2] == "<gold>Sells for 5", "the shop's")
                      assert(#nf.test.shown(sam, 0).lore == 1, "someone else's")
                      assert(#alex:inventory():item(0).lore == 1, "the real stack")
                      assert(nf.test.shown(alex, "cursor") == nil, "nothing on the cursor")
                      alex:close_menu()
                      nf.test.advance(1)
                      assert(#nf.test.shown(alex, 0).lore == 1, "the shop closed")
                    end)
                """
            )
        )
        expectStatuses(events, Status.PASSED)
    }

    @Test
    fun `a package shows a look a project that depends on it hands over, kept in saved data, and refuses an unknown namespace`() {
        // Two projects side by side: app depends on lib, and hands lib's thing a model of app's own pack to show.
        val parent = Files.createTempDirectory("netherforge-runner-packages")
        fun write(path: String, text: String) {
            val file = parent.resolve(path)
            file.parent.createDirectories()
            file.writeText(text.trimIndent() + "\n")
        }
        fun manifest(namespace: String, more: String) =
            """{ "formatVersion": ${FormatVersion.CURRENT}, "name": "$namespace", "namespace": "$namespace", "version": "1.0.0", "minecraft": "26.3"$more }"""
        write("lib/netherforge.json", manifest("lib", ""","exports": { "modules": ["lib"] }"""))
        write("lib/resource_packs/gems/pack.json", """{ "items": { "gem": { "model": "gem.json" } } }""")
        write("lib/resource_packs/gems/models/gem.json", MODEL)
        write("lib/items/token/item.json", """{ "kind": "minecraft:paper" }""")
        write(
            "lib/centities/thing/centity.json",
            """{ "nodes": { "n": { "display": { "type": "model", "model": "gems/gem" } } }, "script": { "file": "script.lua" } }"""
        )
        write("lib/centities/thing/script.lua", """if this:data().model then this:node("n"):set_model(this:data().model) end""")
        write(
            "lib/modules/lib/init.lua",
            """
            local lib = {}
            -- Shows a look whoever uses lib hands it, and keeps it with the thing.
            function lib.place(model)
              local thing = assert(nf.centities.spawn("thing", vec3(0, 64, 0)))
              thing:data().model = model
              thing:node("n"):set_model(model)
              return thing
            end
            function lib.stack(model) return nf.items.create("token", { item_model = model }) end
            -- Not a tail call: one would hide that this is lib's code.
            function lib.shown(thing)
              local model = thing:node("n"):model()
              return model
            end
            return lib
            """
        )
        write("app/netherforge.json", manifest("app", ""","dependencies": { "lib": { "path": "../lib" } }"""))
        write("app/resource_packs/p/pack.json", """{ "items": { "box": { "model": "box.json" } } }""")
        write("app/resource_packs/p/models/box.json", MODEL)
        write(
            "app/tests/skins_test.lua",
            """
            local lib = require("lib:lib")

            nf.test.case("a package shows a look its dependent hands it", function()
              local box = nf.looks.qualify("p/box")
              assert(box == "app:p/box", box)
              local thing = lib.place(box)
              assert(lib.shown(thing) == "app:p/box", "lib reads " .. tostring(lib.shown(thing)))
              assert(thing:node("n"):model() == "p/box", "app reads " .. tostring(thing:node("n"):model()))
              -- An item table keeps the words it was handed out in: lib's, here.
              local stack = lib.stack(box)
              assert(stack.item_model == "app:p/box", stack.item_model)
            end)

            nf.test.case("the look is a plain string, so it survives saved data", function()
              local thing = lib.place(nf.looks.qualify("p/box"))
              local saved = nf.json.decode(nf.json.encode(thing:data()))
              local again = lib.place(saved.model)
              assert(again:node("n"):model() == "p/box")
            end)

            nf.test.case("an unknown namespace is refused, by set_model and item_model alike", function()
              local unknown = 'no package on this server has the namespace "nope"'
              local ok, problem = pcall(lib.place, "nope:p/box")
              assert(not ok and tostring(problem):find(unknown, 1, true), tostring(problem))
              ok, problem = pcall(lib.stack, "nope:p/box")
              assert(not ok and tostring(problem):find("item.item_model: " .. unknown, 1, true), tostring(problem))
              -- Handed bare, the name is lib's own: the error says how to write app's.
              ok, problem = pcall(lib.place, "p/box")
              assert(not ok and tostring(problem):find('"app:p/box"', 1, true), tostring(problem))
            end)
            """
        )
        try {
            val events = mutableListOf<TestEvent>()
            ScriptTestRunner(parent.resolve("app"), FakePlatform.GAME).run(null) { events += it }
            expectStatuses(events, Status.PASSED, Status.PASSED, Status.PASSED)
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    private companion object {
        /** A resource pack model with one element and no textures. */
        const val MODEL = """{ "elements": [{ "from": [6, 0, 6], "to": [10, 12, 10], "faces": {} }] }"""
    }

    @Test
    fun `a test plays the far end of the streams and WebSockets scripts open, with nothing on the network`() {
        val (events, _) = run(
            mapOf(
                "netherforge.json" to
                    manifest.replace(
                        "\"minecraft\": \"26.3\"",
                        "\"minecraft\": \"26.3\",\n  \"requires\": { \"http\": [\"chat.example.com\", \"feed.example.com\"] }"
                    ),
                "modules/bridge/init.lua" to """
                    local M = { heard = {}, ended = {} }
                    M.socket = nf.http.websocket({ url = "wss://chat.example.com/live", protocols = { "v2" } })
                    M.socket:send("hello")
                    M.socket:on("open", function(event) M.protocol = event.protocol end)
                    M.socket:on("message", function(event)
                      table.insert(M.heard, event.data)
                      M.socket:send("got " .. event.data)
                    end)
                    M.socket:on("end", function(event) table.insert(M.ended, event.reason .. " " .. tostring(event.code) .. " " .. tostring(event.detail)) end)
                    M.feed = nf.http.stream({ url = "https://feed.example.com/live", format = "events" })
                    M.feed:on("open", function(event) M.status = event.status end)
                    M.feed:on("message", function(event) table.insert(M.heard, event.type .. ":" .. event.data) end)
                    M.feed:on("end", function(event) table.insert(M.ended, event.reason .. " " .. tostring(event.detail)) end)
                    return M
                """,
                "tests/bridge_test.lua" to """
                    local bridge = require("bridge")

                    nf.test.case("the far end is the test's", function()
                      assert(#nf.test.connections() == 2)
                      local socket = nf.test.connections("wss://chat.example.com")[1]
                      assert(socket == bridge.socket)
                      assert(socket:state() == "connecting")
                      nf.test.accept(socket, { protocol = "v2" })
                      nf.test.receive(socket, "hi")
                      nf.test.advance(1)
                      assert(socket:state() == "open" and bridge.protocol == "v2")
                      assert(bridge.heard[1] == "hi")
                      local sent = nf.test.sent(socket)
                      assert(sent[1] == "hello" and sent[2] == "got hi", table.concat(sent, ","))

                      local feed = nf.test.connections("https://feed.example.com")[1]
                      nf.test.accept(feed, { status = 201 })
                      nf.test.receive(feed, "event: goal\ndata: red")
                      nf.test.receive(feed, "\n\n")
                      nf.test.advance(1)
                      assert(bridge.status == 201 and feed:status() == 201)
                      assert(bridge.heard[2] == "goal:red", tostring(bridge.heard[2]))

                      nf.test.disconnect(socket, { code = 4000, reason = "resume" })
                      nf.test.disconnect(feed, { error = "reset" })
                      nf.test.advance(1)
                      assert(bridge.ended[1] == "finished 4000 resume", bridge.ended[1])
                      assert(bridge.ended[2] == "failed reset", bridge.ended[2])
                      assert(socket:state() == "ended")
                      assert(not pcall(nf.test.receive, socket, "late"))
                    end)

                    nf.test.case("mistakes are errors", function()
                      local socket = nf.test.connections("wss://")[1]
                      assert(not pcall(nf.test.accept, socket, { protocol = "v9" }))
                      assert(not pcall(nf.test.accept, socket, { status = 200 }))
                      nf.test.accept(socket)
                      assert(not pcall(nf.test.accept, socket))
                    end)
                """
            )
        )
        expectStatuses(events, Status.PASSED, Status.PASSED)
    }

    @Test
    fun `a test plays the other servers of a network of its own`() {
        val (events, _) = run(
            mapOf(
                "netherforge.json" to
                    manifest.replace("\"minecraft\": \"26.3\"", "\"minecraft\": \"26.3\",\n  \"requires\": { \"network\": true }"),
                "modules/net/init.lua" to """
                    local M = { heard = {}, arrived = {}, players = {}, changes = {} }
                    nf.network.channel("chat"):on("message", function(event)
                      table.insert(M.heard, event.server .. ":" .. event.value)
                    end)
                    nf.network.channel("prices"):on("request", function(event)
                      event.reply = { item = event.value, price = 42, asker = event.server }
                    end)
                    M.scores = nf.network.map("scores")
                    M.scores:on("change", function(event)
                      table.insert(M.changes, event.key .. "=" .. tostring(event.value) .. " by " .. tostring(event.server))
                    end)
                    nf.on("player_arrive", function(event)
                      table.insert(M.arrived, event.player:name() .. " from " .. event.from .. " " .. event.data.kit)
                    end)
                    nf.on("network_player_join", function(event)
                      table.insert(M.players, event.player.name .. "@" .. tostring(event.player.server))
                    end)
                    return M
                """,
                "tests/net_test.lua" to """
                    local net = require("net")

                    nf.test.case("messages go both ways", function()
                      assert(nf.network.server_name() == "test" and nf.network.is_connected())
                      nf.test.network_server("lobby")
                      nf.test.network_received("lobby", "chat")
                      nf.test.advance(1)
                      nf.test.network_publish("lobby", "chat", "hi")
                      nf.test.advance(1)
                      assert(net.heard[1] == "lobby:hi", tostring(net.heard[1]))
                      assert(nf.network.channel("chat"):publish("hello"))
                      nf.test.advance(1)
                      local got = nf.test.network_received("lobby", "chat")
                      assert(#got == 2 and got[1].value == "hi" and got[1].server == "lobby")
                      assert(got[2].value == "hello" and got[2].server == "test" and got[2].channel == "chat")
                      assert(net.heard[2] == "test:hello", tostring(net.heard[2]))
                    end)

                    nf.test.case("requests go both ways", function()
                      nf.test.network_server("bank")
                      nf.test.network_answer("bank", "balance", function(name, server) return name .. " has 100, " .. server end)
                      local answer, err
                      nf.task(function() answer, err = nf.network.channel("balance"):request("bank", "ann") end)
                      nf.test.advance(3)
                      assert(answer == "ann has 100, test", tostring(answer) .. " " .. tostring(err))
                      local quote = nf.test.network_request("bank", "prices", "diamond")
                      assert(quote.item == "diamond" and quote.price == 42 and quote.asker == "bank")
                      assert(not pcall(nf.test.network_request, "bank", "nothing", 1))
                    end)

                    nf.test.case("maps are shared", function()
                      nf.test.network_server("lobby")
                      nf.test.advance(2)
                      assert(net.scores:is_ready())
                      nf.test.network_write("lobby", "scores", "ann", 5)
                      nf.test.advance(1)
                      assert(net.scores:get("ann") == 5)
                      assert(net.changes[1] == "ann=5 by lobby", tostring(net.changes[1]))
                      local total
                      nf.task(function() total = net.scores:add("ann", 2) end)
                      nf.test.advance(2)
                      assert(total == 7 and net.scores:get("ann") == 7)
                    end)

                    nf.test.case("players come and go, and are sent both ways", function()
                      nf.test.network_join("lobby", "Bob")
                      nf.test.advance(1)
                      assert(net.players[1] == "Bob@lobby", tostring(net.players[1]))
                      local ann = nf.test.network_send("lobby", "Ann", { kit = "archer" })
                      nf.test.advance(1)
                      assert(net.arrived[1] == "Ann from lobby archer", tostring(net.arrived[1]))
                      assert(nf.network.find_player("Ann").server == "test")
                      local moved, err
                      nf.task(function() moved, err = nf.network.send_player(ann, "lobby") end)
                      nf.test.advance(2)
                      assert(moved == true, tostring(err))
                      assert(#nf.players.online() == 0, "sent away, they left this server")
                      assert(nf.network.find_player("Ann").server == "lobby")
                      nf.test.network_quit("Bob")
                      nf.test.advance(1)
                      assert(nf.network.find_player("Bob") == nil)
                    end)
                """
            )
        )
        expectStatuses(events, Status.PASSED, Status.PASSED, Status.PASSED, Status.PASSED)
    }

    @Test
    fun `a test makes web requests through the routes, and plays a login through its callback`() {
        val (events, _) = run(
            mapOf(
                "netherforge.json" to manifest.replace(
                    "\"minecraft\": \"26.3\"",
                    "\"minecraft\": \"26.3\",\n  \"requires\": { \"web\": true, \"http\": [\"auth.example.com\"] }"
                ),
                "modules/site/init.lua" to """
                    local M = { linked = {}, failed = {} }
                    nf.web.route("POST", "/hooks/:kind", function(request, response)
                      nf.after(3, function() response:json(202, { kind = request.params.kind, got = request.body }) end)
                    end)
                    M.flow = nf.web.oauth({
                      name = "example",
                      authorize_url = "https://auth.example.com/authorize",
                      token_url = "https://auth.example.com/token",
                      client_id = "abc",
                      scopes = { "identify", "email" },
                    })
                    M.flow:on("complete", function(event) M.linked[event.player:name()] = event.access_token .. "/" .. tostring(event.expires_in) end)
                    M.flow:on("fail", function(event) table.insert(M.failed, event.error) end)
                    return M
                """,
                "tests/site_test.lua" to """
                    local site = require("site")

                    nf.test.case("a hook answered later is waited for", function()
                      local answer = nf.test.request("POST", "/test/hooks/store", { body = "order 7" })
                      assert(answer.status == 202, answer.status .. " " .. answer.body)
                      assert(answer.body == '{"got":"order 7","kind":"store"}', answer.body)
                      assert(answer.headers["content-type"] == "application/json")
                      assert(nf.test.request("GET", "/test/hooks/store").status == 405)
                      assert(nf.test.request("GET", "/test/nope").status == 404)
                    end)

                    nf.test.case("a login completes once, through its callback", function()
                      local alex = nf.test.player("Alex")
                      local url = assert(site.flow:begin(alex))
                      assert(url:find("^https://auth.example.com/authorize%?"), url)
                      assert(url:find("code_challenge_method=S256", 1, true), url)
                      assert(url:find("redirect_uri=http%3A%2F%2Flocalhost%2Ftest%2Foauth%2Fexample%2Fcallback", 1, true), url)
                      assert(url:find("scope=identify%20email", 1, true), url)
                      local state = url:match("state=([^&]+)")
                      nf.test.oauth_token(site.flow, { access_token = "tok", expires_in = 3600 })
                      local forged = nf.test.request("GET", "/test/oauth/example/callback?code=c&state=forged")
                      assert(forged.status == 400)
                      local back = nf.test.request("GET", "/test/oauth/example/callback?code=c&state=" .. state)
                      assert(back.status == 200, back.body)
                      assert(back.body:find("Signed in"), back.body)
                      assert(site.linked.Alex == "tok/3600", tostring(site.linked.Alex))
                      local again = nf.test.request("GET", "/test/oauth/example/callback?code=c&state=" .. state)
                      assert(again.status == 400, "a state is used once")
                    end)

                    nf.test.case("a declined login fails, and a route can't take a flow's path", function()
                      local alex = nf.test.player("Alex")
                      local state = site.flow:begin(alex):match("state=([^&]+)")
                      local back = nf.test.request("GET", "/test/oauth/example/callback?error=access_denied&state=" .. state)
                      assert(back.status == 400)
                      assert(site.failed[1] == "the service said access_denied", tostring(site.failed[1]))
                      assert(not pcall(nf.web.route, "GET", "/oauth/example/callback", function() end))
                      assert(not pcall(nf.web.oauth, { name = "x", authorize_url = "https://a.b/", token_url = "https://undeclared.example/t", client_id = "c" }))
                    end)
                """
            )
        )
        expectStatuses(events, Status.PASSED, Status.PASSED, Status.PASSED)
    }

    @Test
    fun `modules really run in a test, and a test can stand in for one`() {
        val (events, _) = run(
            mapOf(
                "netherforge.json" to
                    manifest.replace("\"minecraft\": \"26.3\"", "\"minecraft\": \"26.3\",\n  \"requires\": { \"wasm\": true }"),
                "wasm/math/module.wasm" to WasmFixtures.library,
                "wasm/echo/module.wasm" to WasmFixtures.echo,
                "modules/engine/init.lua" to """
                    local M = { said = {} }
                    function M.start()
                      M.program = nf.wasm.start("echo")
                      M.program:on("output", function(event) table.insert(M.said, event.line) end)
                      M.program:on("end", function(event) M.ended = event.reason .. " " .. tostring(event.exit_code) end)
                    end
                    return M
                """,
                "tests/engine_test.lua" to """
                    local engine = require("engine")

                    nf.test.case("a real module answers, a tick after it's asked", function()
                      local answer
                      nf.task(function()
                        local math = nf.wasm.instantiate("math")
                        answer = math:call("add", { 2, 3 })[1]
                      end)
                      nf.test.advance(3)
                      assert(answer == 5, tostring(answer))
                      engine.start()
                      engine.program:send("hello")
                      nf.test.advance(1)
                      assert(engine.said[1] == "echo: hello", tostring(engine.said[1]))
                    end)

                    nf.test.case("a stand-in answers in its place", function()
                      nf.test.stub_wasm("echo", {
                        input = function(line) return { "stub: " .. line, "again" } end,
                      })
                      nf.test.stub_wasm("math", {
                        functions = { add = function(arguments) return { arguments[1] * 100 } end, name = function() return "stub" end },
                        run = function(options) return { exit_code = 7, stdout = options.stdin, stderr = "" } end,
                      })
                      engine.start()
                      engine.program:send("hello")
                      nf.test.advance(2)
                      assert(engine.said[1] == "stub: hello" and engine.said[2] == "again", table.concat(engine.said, "|"))
                      engine.program:close_input()
                      nf.test.advance(2)
                      assert(engine.ended == "exited 0", tostring(engine.ended))

                      local got = {}
                      nf.task(function()
                        local math = nf.wasm.instantiate("math")
                        got.sum = math:call("add", { 2, 3 })[1]
                        got.name = math:call_text("name")
                        got.missing = select(2, math:call("nope"))
                        got.run = nf.wasm.run("math", { stdin = "in" })
                      end)
                      nf.test.advance(6)
                      assert(got.sum == 200, tostring(got.sum))
                      assert(got.name == "stub", tostring(got.name))
                      assert(got.missing:find("has no function"), tostring(got.missing))
                      assert(got.run.exit_code == 7 and got.run.stdout == "in")

                      -- Stand-ins count against the owner's limits as the modules they stand in for do.
                      local refused
                      nf.task(function()
                        for _ = 1, 9 do
                          local instance, err = nf.wasm.instantiate("math")
                          if not instance then refused = err break end
                        end
                      end)
                      nf.test.advance(20)
                      assert(refused and refused:find("max%-instances%-per%-package"), tostring(refused))
                    end)
                """
            )
        )
        expectStatuses(events, Status.PASSED, Status.PASSED)
    }
}
