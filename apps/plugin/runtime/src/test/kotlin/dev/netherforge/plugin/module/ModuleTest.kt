package dev.netherforge.plugin.module
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.BlockRef
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.Location
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModuleTest {

    @Test
    fun `the example's perm command grants, denies, lists and forgets what allow permissions lets it`() {
        TestServer(TestServer.example("showcase")).use { server ->
            val alex = server.player("Alex")
            server.platform.raise.playerJoin(GameEvent.PlayerJoin(alex.ref, false, null))
            val commands = server.platform.commands

            /** What the console was told for one line it ran. */
            fun console(line: String): List<String> {
                val before = server.platform.players.console.size
                assertEquals(emptyList(), commands.runAsConsole(line))
                return server.platform.players.console.drop(before)
            }
            // showcase.admin: nobody is granted it, so the console (and ops, on Paper) use it.
            assertEquals(listOf("<red>You don't have permission to use /perm."), commands.run(alex, "perm list Alex"))

            assertEquals(listOf("<green>Granted showcase.vip for Alex."), console("perm grant Alex showcase.vip"))
            assertEquals(listOf("<green>Denied showcase.fly for Alex."), console("perm deny Alex showcase.fly"))
            assertEquals(mapOf("showcase.vip" to true, "showcase.fly" to false), alex.granted)
            assertEquals(listOf("Alex: <red>-showcase.fly<gray>, <green>+showcase.vip"), console("perm list Alex"))

            // Outside allow.permissions: refused, and the sender is told why.
            assertEquals(
                listOf(
                    "<red>Player:set_permission needs permission \"minecraft.command.op\", which package \"showcase\" hasn't allowed: " +
                        "add it (or a node above it) to allow.permissions in its netherforge.json (it lists: showcase)"
                ),
                console("perm grant Alex minecraft.command.op")
            )

            assertEquals(listOf("<green>Alex no longer sets showcase.fly."), console("perm unset Alex showcase.fly"))
            assertEquals(listOf("<gray>Nothing was set for showcase.fly on Alex."), console("perm unset Alex showcase.fly"))
            assertEquals(mapOf("showcase.vip" to true), alex.granted)
        }
    }

    @Test
    fun `the example's milestones make a diamond finder a VIP and show their progress`() {
        TestServer(TestServer.example("showcase")).use { server ->
            val alex = server.player("Alex")
            server.platform.raise.playerJoin(GameEvent.PlayerJoin(alex.ref, false, null))
            val advancements = server.platform.advancements
            advancements.grant(alex.ref.uuid, "minecraft:story/mine_stone")
            assertEquals(emptyMap(), alex.granted)
            advancements.grant(alex.ref.uuid, "minecraft:story/mine_diamond")
            assertEquals(mapOf("showcase.vip" to true), alex.granted)
            advancements.met.getValue(alex.ref.uuid)["minecraft:adventure/adventuring_time"] = mutableSetOf("minecraft:plains")

            assertEquals(
                listOf(
                    listOf(
                        "<gold>Milestones",
                        "<green>✔ Stone Age",
                        "<gray>✘ Acquire Hardware",
                        "<green>✔ Diamonds!",
                        "<gray>✘ Nether",
                        "<yellow>Biomes visited: 1 of 3",
                        "<aqua>You're a VIP."
                    ).joinToString("\n")
                ),
                server.platform.commands.run(alex, "milestones")
            )
        }
    }

    @Test
    fun `the example's milestones announce diamonds their own way`() {
        TestServer(TestServer.example("showcase")).use { server ->
            val alex = server.player("Alex")
            server.platform.raise.playerJoin(GameEvent.PlayerJoin(alex.ref, false, null))
            val diamonds = "minecraft:story/mine_diamond"
            fun complete(advancement: String): String? {
                val event = GameEvent.PlayerAdvancement(alex.ref, advancement, "x")
                server.platform.raise.playerCompleteAdvancement(event)
                return event.message
            }
            assertEquals("<aqua>Alex struck diamonds and is now a VIP!", complete(diamonds))
            assertEquals("x", complete("minecraft:story/mine_stone"))
        }
    }

    @Test
    fun `the example's quests are its own advancements, met by joining and rolling for treasure`() {
        TestServer(TestServer.example("showcase")).use { server ->
            val alex = server.player("Alex")
            val advancements = server.platform.advancements
            server.platform.raise.playerJoin(GameEvent.PlayerJoin(alex.ref, false, null))
            assertTrue(advancements.has(alex.ref.uuid, "showcase:adventurer"))
            assertEquals(
                listOf("<gold>Quests</gold>\n<green>✔ Adventurer\n<yellow>Treasure Hunter: 0 of 3 rolls"),
                server.platform.commands.run(alex, "quests")
            )
            repeat(2) { server.platform.commands.run(alex, "treasure") }
            assertEquals(listOf("first", "second") to listOf("third"), advancements.progress(alex.ref.uuid, "showcase:treasure_hunter"))
            assertFalse(advancements.has(alex.ref.uuid, "showcase:diamonds"))
            server.platform.commands.run(alex, "treasure")
            assertTrue(advancements.has(alex.ref.uuid, "showcase:treasure_hunter"))
            // Completing it gives a diamond, and the diamonds advancement for being given one.
            assertTrue("<gold>Treasure Hunter! Here's a diamond for the road." in alex.messages)
            assertTrue(advancements.has(alex.ref.uuid, "showcase:diamonds"))
        }
    }

    @Test
    fun `the example greeter welcomes players and adds a tower command`() {
        TestServer(TestServer.example("showcase")).use { server ->
            val alex = server.player("Alex")
            server.platform.raise.playerJoin(GameEvent.PlayerJoin(alex.ref, false, null))
            // The second line is the library's: examples/showcase requires its greetings module.
            assertEquals(listOf("<green>Welcome, Alex!", "<aqua>Well met, Alex!"), alex.messages)

            // Building needs showcase.builder, which the welcome dialog's Done grants.
            assertEquals(listOf("<red>You don't have permission to use /tower."), server.platform.commands.run(alex, "tower"))
            server.platform.dialogs.press(alex, "done", mapOf("nickname" to "Al"))
            assertEquals(mapOf("showcase.builder" to true), alex.granted)

            // It answers through event.sender, whoever ran it.
            assertEquals(listOf("<gray>A tower rises 2 blocks ahead."), server.platform.commands.run(alex, "tower"))
            val tower = server.runtime.session.centities.all().single()
            assertEquals("tower", tower.centity)
            assertEquals(Location("world", 2.5, 64.0, 0.5), tower.anchor)

            // The distance is an optional integer from 1 to 16.
            server.platform.commands.run(alex, "tower 5")
            assertEquals(Location("world", 5.5, 64.0, 0.5), server.runtime.session.centities.all().last().anchor)
            assertEquals(
                listOf("<red><distance> must be at most 16, not 40."),
                server.platform.commands.run(alex, "tower 40")
            )
            // players_only: the console is refused before the handler runs.
            assertEquals(listOf("<red>Only players can use /tower."), server.platform.commands.runAsConsole("tower"))
            assertEquals(2, server.runtime.session.centities.all().size)
        }
    }

    @Test
    fun `require gives the running module, so state is shared`() {
        TestServer(
            mapOf(
                "modules/counter/init.lua" to """
                    local util = require("util")
                    local state = { count = 0 }
                    nf.on("tick", function() log("counter sees " .. state.count .. util.suffix) end)
                    return state
                """,
                "modules/counter/util.lua" to """return { suffix = "!" }""",
                "modules/bumper/init.lua" to """
                    local counter = require("counter")
                    local util = require("counter.util")
                    counter.count = counter.count + 1
                    log("same util " .. tostring(util == require("counter.util")))
                """
            )
        ).use { server ->
            server.tick()
            assertEquals(listOf("same util true", "counter sees 1!"), server.logs)
        }
    }

    @Test
    fun `require reaches project modules only, and says what it looked for`() {
        TestServer(
            mapOf(
                "modules/a/init.lua" to """
                    log(pcall(require, "nope"))
                    log(pcall(require, "../../etc"))
                    log(pcall(require, "b"))
                """,
                "modules/b/init.lua" to """require("a")"""
            )
        ).use { server ->
            assertTrue(server.logs[0].startsWith("false\t") && "no module \"nope\"" in server.logs[0], server.logs[0])
            assertTrue("isn't a module name" in server.logs[1], server.logs[1])
            assertTrue("circular require" in server.logs[2], server.logs[2])
        }
    }

    @Test
    fun `a centity script can require a module`() {
        TestServer(
            mapOf(
                "modules/lib/shapes.lua" to """return { name = function() return "shape" end }""",
                "centities/c/centity.json" to TestServer.scriptedCentity(),
                "centities/c/script.lua" to """
                    local shapes = require("lib.shapes")
                    log(shapes.name())
                """
            )
        ).use { server ->
            server.runtime.session.centities.spawn("c", Location("world", 0.0, 64.0, 0.0))
            assertEquals(listOf("shape"), server.logs)
        }
    }

    @Test
    fun `timers run on their tick and stop when cancelled`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to """
                    nf.after(2, function() log("after at " .. nf.server.tick()) end)
                    local every
                    every = nf.every(3, function()
                      log("every at " .. nf.server.tick())
                      if nf.server.tick() >= 6 then every:cancel() end
                    end)
                    local sub = nf.on("tick", function() log("tick " .. nf.server.tick()) end)
                    nf.after(2, function() sub:cancel() end)
                """
            )
        ).use { server ->
            server.tick(10)
            assertEquals(listOf("tick 1", "after at 2", "every at 3", "every at 6"), server.logs)
        }
    }

    @Test
    fun `a handler cancels a cancellable event with cancel, and what it returns means nothing`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to """
                    nf.on("block_break", function(event)
                      if event.state == "minecraft:bedrock" then
                        event:cancel()
                      end
                    end)
                    nf.on("player_interact", function(event)
                      log(event.player:name(), event.click, tostring(event.block))
                      return true
                    end)
                """
            )
        ).use { server ->
            val alex = server.player("Alex")
            val stone = BlockRef("world", 1, 2, 3, "minecraft:stone", "minecraft:stone")
            val bedrock = stone.copy(id = "minecraft:bedrock", state = "minecraft:bedrock")
            assertFalse(server.breaks(alex, stone))
            assertTrue(server.breaks(alex, bedrock))
            assertFalse(server.runtime.events.playerInteract(alex.ref, ClickButton.LEFT, null, null, null, "main_hand"))
            assertEquals(listOf("Alex\tleft\tnil"), server.logs)
        }
    }

    @Test
    fun `commands get their arguments and sender, and a broken one is reported and stays`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to """
                    nf.commands.register("greet", {
                      description = "Say hi",
                      aliases = { "hi" },
                      arguments = { { name = "words", type = "text", default = "" } },
                    }, function(ctx)
                      ctx.sender:send_message("hi " .. ctx.arguments.words .. " from " .. ctx.sender:name() .. " as " .. ctx.label .. " (" .. ctx.input .. ")")
                    end)
                    log(nf.commands.register("tp", function() end))
                    nf.commands.register("boom", function(ctx)
                      local t = nil
                      return t.x
                    end)
                """,
                "modules/u/init.lua" to """log(nf.commands.register("greet", function() end))"""
            )
        ).use { server ->
            val alex = server.player("Alex")
            assertEquals(listOf("false", "false"), server.logs, "a server command and another module's name are refused")
            assertEquals(listOf("hi a b from Alex as greet (a b)"), server.platform.commands.run(alex, "greet a b"))
            assertEquals(listOf("hi  from Alex as hi ()"), server.platform.commands.run(alex, "hi"))
            // From the console, the sender is the console: its messages go to the console, not to any player.
            assertTrue(server.platform.commands.runConsole("greet x"))
            assertEquals(listOf("hi x from CONSOLE as greet (x)"), server.platform.players.console)

            val reply = server.platform.commands.run(alex, "boom")
            assertTrue(reply.single().startsWith("<red>/boom failed: attempt to index a nil value"), reply.single())
            assertEquals("modules/t/init.lua", server.errors.single().source?.file)
            assertTrue("boom" in server.platform.commands.registered, "a command that errors stays")
            assertEquals(
                listOf("hi a b from Alex as greet (a b)"),
                server.platform.commands.run(alex, "greet a b"),
                "and so does its module"
            )
        }
    }

    @Test
    fun `only modules declare commands`() {
        TestServer(
            mapOf(
                "centities/c/centity.json" to TestServer.scriptedCentity(),
                "centities/c/script.lua" to """nf.commands.register("mine", function() end)"""
            )
        ).use { server ->
            server.runtime.session.centities.spawn("c", Location("world", 0.0, 64.0, 0.0))
            assertTrue("only modules can declare commands" in server.errors.single().message)
        }
    }

    @Test
    fun `nf reaches players, centities and the console`() {
        val showcase = TestServer.example("showcase")
        // `nf.server.run` needs `console`, which the showcase doesn't ask for.
        val manifest = (
            showcase.getValue(
                "netherforge.json"
            ) as String
            ).replace("\"requires\": {", "\"requires\": {\n    \"console\": true,")
        TestServer(
            showcase + mapOf(
                "netherforge.json" to manifest,
                "modules/t/init.lua" to """
                    nf.commands.register("probe", function(ctx)
                      local p = ctx.player
                      log(p:id() == nf.players.get("alex"):id(), #nf.players.online(), p:position(), p:world():name(), p:exists())
                      log(p:location(), p:yaw(), p:pitch(), p:direction():distance(vec3.from_yaw_pitch(90, -30)) < 1e-9)
                      log(p:teleport(vec3(5, 70, 5)), p:location())
                      log(p:teleport(p:location():with_position(vec3(1, 2, 3))), p:position())
                      log(p:has_permission("x.y"), p:run("greet"))
                      local tower = nf.centities.spawn("tower", vec3(0, 64, 0))
                      log(tower:kind(), #nf.centities.all(), #nf.centities.all({ kind = "lamp" }), nf.centities.get(tower:id()) == tower, tostring(nf.centities.get("nope")))
                      log(pcall(nf.centities.spawn, "towr", vec3.zero))
                      log(pcall(function() nf.centities.spawn("tower", 0, 64, 0) end))
                      log(nf.server.run("greet"), nf.server.run("nothing"))
                      nf.server.broadcast("<gold>hello")
                      tower:remove()
                      log(tower:exists(), tostring(tower:kind()), #nf.centities.all())
                    end)
                """
            )
        ).use { server ->
            val alex = server.player("Alex")
            alex.location = alex.location.copy(yaw = 90.0, pitch = -30.0)
            server.platform.commands.run(alex, "probe")
            assertEquals(
                listOf(
                    "true\t1\tvec3(0.5, 64, 0.5)\tworld\ttrue",
                    "location(\"world\", vec3(0.5, 64, 0.5), 90, -30)\t90.0\t-30.0\ttrue",
                    // A bare position keeps their facing; a location brings its own.
                    "true\tlocation(\"world\", vec3(5, 70, 5), 90, -30)",
                    "true\tvec3(1, 2, 3)",
                    "false\tfalse",
                    "tower\t1\t0\ttrue\tnil",
                    "false\tno centity \"towr\" in this project (did you mean \"tower\"?)",
                    "false\tmodules/t/init.lua:11: bad argument 'location_or_position' (Location or Vec3 expected, got number)",
                    "false\tfalse",
                    "false\tnil\t0"
                ),
                server.logs
            )
            assertEquals(listOf("<gold>hello"), server.platform.players.broadcasts)
        }
    }

    @Test
    fun `nf's namespaces reach the server, players who left, filtered centities, text and JSON`() {
        TestServer(
            mapOf(
                "centities/c/centity.json" to TestServer.scriptedCentity(),
                "centities/c/script.lua" to "-- nothing",
                "modules/t/init.lua" to """
                    local task, sub
                    nf.commands.register("probe", function(event)
                      log(nf.server.tick(), math.type(nf.server.unix_time()), nf.server.ticks_per_second(), nf.server.tick_milliseconds(), nf.server.minecraft_version())
                      log(event.sender:name(), event.sender:is_player(), event.sender:is_console(), event.sender:has_permission("x.y"))

                      local sam = nf.players.get("sam")
                      log(sam:name(), sam:exists(), tostring(sam:position()), sam:send_message("hi"), sam == nf.players.get(sam:id()), tostring(nf.players.get("nobody")))

                      nf.centities.spawn("c", vec3(0, 64, 0))
                      nf.centities.spawn("c", vec3(100, 64, 0))
                      -- A location from someone in the nether puts it there.
                      nf.centities.spawn("c", nf.players.get("nora"):location():with_position(vec3(0, 64, 0)))
                      log(#nf.centities.all(), #nf.centities.all({ kind = "c", world = nf.worlds.default() }), #nf.centities.all({ near = vec3(1, 64, 1), radius = 5 }), #nf.centities.all({ kind = "d" }))
                      log(pcall(function() nf.centities.all({ knd = "c" }) end))
                      log(pcall(function() nf.centities.all({ radius = "far" }) end))
                      log(pcall(function() nf.centities.all({ near = { 1, 2, 3 }, radius = 3 }) end))

                      log(nf.text.escape("<red>hi"), nf.text.strip("<red>hi</red> there"))
                      log(nf.json.encode({ b = { true, "x", 1 } }), nf.json.decode("[1, 2]")[2], tostring(nf.json.decode("nope")), tostring(nf.json.decode("[nope]")))
                      log(pcall(nf.json.encode, { f = function() end }))
                      -- The same codec as saved data: typed values come back typed, and a `$` key that isn't a tag is as it was.
                      local back = nf.json.decode(nf.json.encode({ who = event.player, at = vec3(1, 2, 3) }))
                      log(back.who == event.player, back.at == vec3(1, 2, 3), nf.json.encode({ ["${'$'}ref"] = 1 }))

                      sub = nf.on("tick", function() end)
                      task = nf.after(1, function() end)
                      log(sub:is_active(), task:is_active())
                      sub:cancel()
                      log(sub:is_active())
                    end)
                    nf.commands.register("later", function() log(task:is_active()) end)
                """
            )
        ).use { server ->
            val alex = server.player("Alex")
            alex.permissions += "x.y"
            server.player("Nora").location = Location("nether", 0.0, 64.0, 0.0)
            server.player("Sam")
            // Sam leaves: still known to the server, no longer online.
            server.platform.players.byId.remove(server.platform.players.find("Sam")!!.uuid)
            server.platform.commands.run(alex, "probe")
            server.tick(2)
            server.platform.commands.run(alex, "later")
            assertEquals(
                listOf(
                    "0\tinteger\t20.0\t12.5\t26.3",
                    "Alex\ttrue\tfalse\ttrue",
                    "Sam\tfalse\tnil\tfalse\ttrue\tnil",
                    // Near is a point, not a world: both centities at the origin are near it.
                    "3\t2\t2\t0",
                    "false\tmodules/t/init.lua:14: unknown field 'filter.knd' (fields: kind, near, radius, world)",
                    "false\tmodules/t/init.lua:15: bad argument 'filter.radius' (number or nil expected, got string)",
                    "false\tmodules/t/init.lua:16: bad argument 'filter.near' (Vec3 or nil expected, got table)",
                    "\\<red>hi\thi there",
                    "{\"b\":[true,\"x\",1]}\t2\tnil\tnil",
                    "false\tvalue.f: a function can't be written as JSON",
                    "true\ttrue\t{\"${'$'}ref\":1}",
                    "true\ttrue",
                    "false",
                    "false"
                ),
                server.logs
            )
        }
    }

    /** Each package has a folder of its own: another package sees none of it unless it's handed a file. */
    @Test
    fun `a package's files are its own`() {
        val manifest = { ns: String, more: String ->
            """{ "formatVersion": ${dev.netherforge.format.project.FormatVersion.CURRENT}, "name": "$ns", "namespace": "$ns", "version": "1.0.0", "minecraft": "26.3"$more }"""
        }
        TestServer(
            mapOf(
                "netherforge.json" to manifest("test", """, "dependencies": { "lib": { "path": "../lib" } }"""),
                "../lib/netherforge.json" to manifest("lib", """, "exports": { "modules": ["api"] }"""),
                "../lib/modules/api/init.lua" to """
                    local api = {}
                    function api.peek() return tostring(nf.files.get("secret.txt"):read()), #nf.files.get():children() end
                    function api.write() return nf.files.get("lib.txt"):write("mine") end
                    function api.read(file) return file:read() end
                    return api
                """,
                "modules/t/init.lua" to """
                    local lib = require("lib:api")
                    nf.files.get("secret.txt"):write("token")
                    log(lib.peek())
                    log(lib.write(), tostring(nf.files.get("lib.txt"):read()))
                    log(lib.read(nf.files.get("secret.txt")))
                """
            )
        ).use { server ->
            assertEquals(listOf("nil\t0", "true\tnil", "token"), server.logs)
            assertEquals("mine", server.project.resolve(".netherforge/data/lib/lib.txt").readText())
        }
    }

    @Test
    fun `files stay inside the data folder`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to """
                    local save = nf.files.get("saves/state.json")
                    log(save:write_json({ count = 3, names = { "a", "b" } }), save:read_json().count, save:read_json().names[2])
                    log(nf.files.get("saves"):is_folder(), #nf.files.get("saves"):children(), nf.files.get("saves"):children()[1]:name(), save:parent():path())
                    log(save:append("x"), tostring(save:read_json()), nf.files.get("log.txt"):append("a"), nf.files.get("log.txt"):append("b"), nf.files.get("log.txt"):read())
                    log(tostring(nf.files.get("../escape")), tostring(nf.files.get("a/../../b")), tostring(nf.files.get(".hidden")), tostring(nf.files.get("con.txt")))
                    log(nf.files.get("/saves/state.json"):path(), nf.files.get():path(), nf.files.get():is_folder(), tostring(save:child("x/y")))
                    log(save:delete(), save:exists(), nf.files.get("saves"):delete(), nf.files.get():delete())
                    log(nf.files.get("bad.json"):write_json({ f = function() end }))
                """
            )
        ).use { server ->
            assertEquals(
                listOf(
                    "true\t3\tb",
                    "true\t1\tstate.json\tsaves",
                    "true\tnil\ttrue\ttrue\tab",
                    "nil\tnil\tnil\tnil",
                    "saves/state.json\t\ttrue\tnil",
                    "true\tfalse\ttrue\tfalse",
                    "false"
                ),
                server.logs
            )
            assertEquals("ab", server.project.resolve(".netherforge/data/test/log.txt").readText())
        }
    }
}
