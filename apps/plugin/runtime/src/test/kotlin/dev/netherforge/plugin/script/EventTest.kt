package dev.netherforge.plugin.script
import dev.netherforge.format.bridge.Problems
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.DEFAULT_CHAT_FORMAT
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.Location
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The event core: `:on`, event objects, custom events, lifetimes and the handler error policy. */
class EventTest {
    private val at = Location("world", 0.0, 64.0, 0.0)

    private fun module(source: String, vararg more: Pair<String, String>) = mapOf("modules/t/init.lua" to source) + more

    @Test
    fun `an event object has a name, read-only state, and cancel only where it means something`() {
        TestServer(
            module(
                """
                nf.on("block_break", function(event)
                  log(event.name, tostring(event.cancelled), tostring(event.stopped), tostring(event.current))
                  event:cancel()
                  log("cancelled", tostring(event.cancelled))
                  log(pcall(function() event.cancelled = false end))
                  log(pcall(function() event.block = nil end))
                  log(pcall(event.stop))
                end)
                nf.on("block_break", function(event)
                  log("second", tostring(event.cancelled))
                  event:uncancel()
                end)
                nf.on("player_sneak", function(event)
                  log(pcall(event.cancel, event))
                end)
                """
            )
        ).use { server ->
            val alex = server.player("Alex")
            val stone = dev.netherforge.plugin.platform.BlockRef("world", 1, 2, 3, "minecraft:stone", "minecraft:stone")
            assertFalse(server.breaks(alex, stone), "the second handler uncancelled it")
            server.platform.raise.playerSneak(GameEvent.PlayerSneak(alex.ref, true))
            assertEquals(
                listOf(
                    "block_break\tfalse\tfalse\tnil",
                    "cancelled\ttrue",
                    "false\tmodules/t/init.lua:5: event.cancelled can't be assigned",
                    "false\tmodules/t/init.lua:6: event.block can't be assigned on a block_break event (writable: drops, experience)",
                    "false\tcall Event methods with ':' on an event, like event:stop()",
                    "second\ttrue",
                    "false\tthe player_sneak event can't be cancelled"
                ),
                server.logs
            )
        }
    }

    @Test
    fun `writable fields are read back after every handler, and only they can be assigned`() {
        TestServer(
            module(
                """
                nf.on("player_join", function(event)
                  log("first", tostring(event.first_join), event.message)
                  event.message = "<gold>" .. event.player:name() .. " arrives"
                  log(pcall(function() event.message = 3 end))
                  log(pcall(function() event.player = nil end))
                end)
                nf.on("player_join", function(event)
                  event.message = event.message .. "!"
                  return "ignored"
                end)
                nf.on("player_quit", function(event)
                  event.message = nil
                end)
                """
            )
        ).use { server ->
            val alex = server.player("Alex")
            val join = GameEvent.PlayerJoin(alex.ref, true, "<yellow>Alex joined")
            server.platform.raise.playerJoin(join)
            assertEquals("<gold>Alex arrives!", join.message)
            val quit = GameEvent.PlayerQuit(alex.ref, "<yellow>Alex left")
            server.platform.raise.playerQuit(quit)
            assertNull(quit.message)
            assertEquals(
                listOf(
                    "first\ttrue\t<yellow>Alex joined",
                    "false\tmodules/t/init.lua:4: bad value for event.message (string or nil expected, got number)",
                    "false\tmodules/t/init.lua:5: event.player can't be assigned on a player_join event (writable: message)"
                ),
                server.logs
            )
        }
    }

    @Test
    fun `once runs one time, and a subscription can be cancelled and asked whether it's active`() {
        TestServer(
            module(
                """
                local once = nf.once("tick", function(event) log("once", event.tick) end)
                local every = nf.on("tick", function(event) log("every 2", event.tick) end, { every = 2 })
                nf.after(2, function() log("once active", once:is_active()) end)
                nf.after(4, function()
                  every:cancel()
                  every:cancel()
                  log("every active", every:is_active())
                end)
                """
            )
        ).use { server ->
            server.tick(6)
            assertEquals(listOf("once\t1", "once active\tfalse", "every 2\t2", "every active\tfalse"), server.logs)
        }
    }

    @Test
    fun `a name a class doesn't have, or a wrong option, is an error at the call`() {
        for ((line, message) in listOf(
            "this:on(\"clik\", function() end)" to
                "no event \"clik\" for Centity (events: animation_end, animation_start, bounce, buff_add, buff_end, bump, chunk_load, chunk_unload, click, damage, death, flight_end, hit, land, path_end, remove, spawn, stick, tick, touch; a custom event's name has a ':' in it)",
            "this:node(\"root\"):on(\"tick\", function() end)" to "no event \"tick\" for Node (events: click, collide, sleep, wake)",
            "this:node(\"root\"):on(\"door:open\", function() end)" to "only nf and Centity take custom events",
            "this:on(\"click\", function() end, { every = 2 })" to "option 'every' doesn't apply to the click event",
            "this:on(\"tick\", function() end, { evry = 2 })" to "unknown field 'options.evry' (fields: every)",
            "this:on(\"tick\", function() end, { every = 0 })" to "bad field 'options.every'",
            "this:on(\"tick\", \"not a function\")" to "bad argument 'handler' (function expected, got string)",
            "this:emit(\"spawn\")" to "\"spawn\" is a built-in event name",
            "Centity_on = this.on(nil, \"tick\", function() end)" to "call Centity methods with ':'"
        )) {
            TestServer(
                mapOf(
                    "centities/c/centity.json" to TestServer.scriptedCentity(),
                    "centities/c/script.lua" to "-- line 1\n$line\n"
                )
            ).use { server ->
                server.runtime.session.centities.spawn("c", at)
                val error = server.errors.single()
                assertTrue(message in error.message, "${error.message} should say $message")
                assertEquals(2, error.source?.line, error.message)
            }
        }
    }

    @Test
    fun `custom events reach every handler for them, carry their payload as it is, and can be vetoed`() {
        TestServer(
            mapOf(
                "modules/shop/init.lua" to """
                    nf.on("shop:purchased", function(event)
                      log("shop heard", event.item, event.name)
                      event.price = 5
                    end)
                    nf.on("shop:purchased", function(event)
                      if event.item == "ruby" then
                        event:cancel()
                      end
                    end)
                """,
                "modules/buyer/init.lua" to """
                    -- On the next tick, once every module has started.
                    nf.after(1, function()
                      local order = { item = "bread" }
                      log("vetoed", nf.emit("shop:purchased", order), order.price)
                      log("vetoed", nf.emit("shop:purchased", { item = "ruby" }))
                      log("nobody", nf.emit("shop:nobody"))
                      log(pcall(nf.emit, "tick"))
                    end)
                """,
                "centities/door/centity.json" to TestServer.scriptedCentity(),
                "centities/door/script.lua" to """
                    this:on("door:open", function(event) log("door opened by", event.by, tostring(event.current == this)) end)
                    nf.after(1, function() log("door emit", this:emit("door:open", { by = "Alex" })) end)
                """
            )
        ).use { server ->
            server.runtime.session.centities.spawn("door", at)
            server.tick()
            assertEquals(
                listOf(
                    "shop heard\tbread\tshop:purchased",
                    "vetoed\tfalse\t5",
                    "shop heard\truby\tshop:purchased",
                    "vetoed\ttrue",
                    "nobody\tfalse",
                    "false\t\"tick\" is a built-in event name: only the server raises those; a custom event's name has a ':' in it",
                    "door opened by\tAlex\ttrue",
                    "door emit\tfalse"
                ),
                server.logs
            )
        }
    }

    @Test
    fun `stop ends the event on the same handle too, after the handlers before it`() {
        TestServer(
            module(
                """
                nf.on("player_chat", function(event) log("first") event:stop() log("stopped", tostring(event.stopped)) end)
                nf.on("player_chat", function() log("never") end)
                """
            )
        ).use { server ->
            server.platform.raise.playerChat(GameEvent.PlayerChat(server.player().ref, "hi", DEFAULT_CHAT_FORMAT))
            assertEquals(listOf("first", "stopped\ttrue"), server.logs)
        }
    }

    @Test
    fun `a module's handler on a centity's node lives as long as both`() {
        val files = mapOf(
            "centities/c/centity.json" to TestServer.scriptedCentity(),
            "centities/c/script.lua" to "-- nothing",
            "modules/watch/init.lua" to """
                local subscriptions = {}
                nf.commands.register("watch", function(ctx)
                  local centity = nf.centities.all()[1]
                  subscriptions[#subscriptions + 1] = centity:node("root"):on("click", function() log("module heard a click") end)
                end)
                nf.commands.register("check", function(ctx)
                  local active = {}
                  for i, subscription in ipairs(subscriptions) do active[i] = tostring(subscription:is_active()) end
                  log("active", table.concat(active, " "))
                end)
            """
        )
        TestServer(files).use { server ->
            val alex = server.player("Alex")
            val instance = server.runtime.session.centities.spawn("c", at)!!
            val hitbox = server.platform.entities.hitbox(instance.id, "root").id
            server.platform.commands.run(alex, "watch")
            server.runtime.events.entityClicked(hitbox, alex.ref, ClickButton.RIGHT, null)
            // Reloading the centity restarts its script, but the instance (and the module's handler on it) stays.
            server.reload("centities/c/script.lua")
            server.runtime.events.entityClicked(hitbox, alex.ref, ClickButton.RIGHT, null)
            server.platform.commands.run(alex, "check")
            // The centity goes: so does the handler.
            server.runtime.session.centities.remove(instance)
            server.platform.commands.run(alex, "check")
            assertEquals(listOf("module heard a click", "module heard a click", "active\ttrue", "active\tfalse"), server.logs)
        }
        TestServer(files).use { server ->
            val alex = server.player("Alex")
            val instance = server.runtime.session.centities.spawn("c", at)!!
            val hitbox = server.platform.entities.hitbox(instance.id, "root").id
            server.platform.commands.run(alex, "watch")
            // The module goes: so does its handler, though the centity stays.
            server.reload("modules/watch/init.lua")
            server.runtime.events.entityClicked(hitbox, alex.ref, ClickButton.RIGHT, null)
            assertEquals(emptyList(), server.logs)
        }
    }

    @Test
    fun `nf unload is heard only by the script that registered it`() {
        TestServer(
            mapOf(
                "modules/a/init.lua" to "nf.on('unload', function(event) log('a unloads', event.name) end)",
                "modules/b/init.lua" to "nf.on('unload', function() log('b unloads') end)"
            )
        ).use { server ->
            server.reload("modules/a/init.lua")
            assertEquals(listOf("a unloads\tunload"), server.logs)
        }
    }

    @Test
    fun `a handler that keeps failing is logged now and then, and cancelled after 20 in a row`() {
        TestServer(
            module(
                """
                local calls = 0
                nf.on("tick", function()
                  calls = calls + 1
                  error("broken")
                end)
                local flaky = 0
                nf.on("tick", function(event)
                  flaky = flaky + 1
                  if flaky % 10 ~= 0 then error("flaky") end
                end)
                nf.on("tick", function(event)
                  if event.tick == 30 then log("still running", calls) end
                end)
                """
            )
        ).use { server ->
            server.tick(30)
            val errors = server.errors.map { it.message }
            // The same error is logged once, then held back; the cancellation is always said.
            assertEquals(
                listOf(
                    "module t: tick handler failed: broken",
                    "module t: tick handler failed: flaky",
                    "module t: tick handler failed 20 times in a row, so it was cancelled (modules/t/init.lua:4)"
                ),
                errors
            )
            // The broken one stopped after 20; the other handlers kept going, and so does the module.
            assertEquals(listOf("still running\t20"), server.logs)
            assertEquals(dev.netherforge.plugin.module.Modules.Status.RUNNING, server.runtime.session.modules.status("t"))
            // In the editor's problems, at the file and line.
            val problems = server.sent.filterIsInstance<Problems>().last().problems
            assertTrue(problems.any { it.code == "script.error" && it.file == "modules/t/init.lua" && it.line == 4 }, "$problems")
            // Five seconds on, a still-failing handler is logged again, saying how many were held back.
            server.tick(100)
            assertTrue(
                server.errors.any {
                    it.message.startsWith("module t: tick handler failed: flaky (and ")
                },
                "${server.errors.map { it.message }}"
            )
        }
    }

    @Test
    fun `a repeating timer that keeps failing stops after 20 in a row`() {
        TestServer(
            module("local task = nf.every(1, function() error('tick tock') end)\nnf.after(25, function() log(task:is_active()) end)")
        ).use { server ->
            server.tick(25)
            assertEquals(listOf("false"), server.logs)
            assertTrue(server.errors.any { "timer failed 20 times in a row, so it was cancelled" in it.message })
        }
    }

    @Test
    fun `a centity hears its chunk unload and load`() {
        TestServer(
            mapOf(
                "centities/c/centity.json" to TestServer.scriptedCentity(),
                "centities/c/script.lua" to """
                    this:on("chunk_unload", function(event) log("unload", event.centity == this) end)
                    this:on("chunk_load", function() log("load") end)
                """
            )
        ).use { server ->
            server.runtime.session.centities.spawn("c", at)
            val chunk = server.platform.worlds.chunkOf(at)
            server.tick()
            server.platform.worlds.unloaded += chunk
            server.tick(2)
            server.platform.worlds.unloaded -= chunk
            server.tick(2)
            assertEquals(listOf("unload\ttrue", "load"), server.logs)
        }
    }

    @Test
    fun `a physics body reports its landing, falling asleep, and waking`() {
        TestServer(
            mapOf(
                "centities/crate/centity.json" to """
                    {
                      "nodes": {
                        "box": {
                          "transform": { "translation": [0, 3, 0] },
                          "display": { "type": "block", "block": "minecraft:stone" },
                          "hitbox": {},
                          "physics": {}
                        }
                      },
                      "script": { "file": "box.lua" }
                    }
                """,
                "centities/crate/box.lua" to """
                    local box = this:node("box")
                    local hardest
                    box:on("collide", function(event)
                      if hardest == nil or event.speed > hardest.speed then
                        hardest = event
                      end
                    end)
                    box:on("sleep", function()
                      -- The landing: the hardest hit, on the ground below (the block grid's side faces touch too, gently).
                      log("landed", tostring(hardest.other), hardest.hit_normal.y, hardest.speed > 5, hardest.node == box)
                    end)
                    box:on("wake", function(event) log("awake", event.node == box) end)
                """,
                "modules/push/init.lua" to """
                    nf.commands.register("push", function()
                      nf.centities.all()[1]:node("box"):apply_impulse(vec3(0, 3, 0))
                    end)
                """
            )
        ).use { server ->
            server.runtime.session.centities.spawn("crate", at)
            server.tick(120)
            server.platform.commands.run(server.player(), "push")
            server.tick()
            assertEquals(listOf("landed\tnil\t1.0\ttrue\ttrue", "awake\ttrue"), server.logs)
        }
    }

    /** Any package may emit any custom event: `from` says whose code did, which a handler can check. */
    @Test
    fun `a custom event says which package emitted it`() {
        val manifest = { ns: String, more: String ->
            """{ "formatVersion": ${dev.netherforge.format.project.FormatVersion.CURRENT}, "name": "$ns", "namespace": "$ns", "version": "1.0.0", "minecraft": "26.3"$more }"""
        }
        TestServer(
            mapOf(
                "netherforge.json" to manifest("test", """, "dependencies": { "lib": { "path": "../lib" } }"""),
                "../lib/netherforge.json" to manifest("lib", """, "exports": { "modules": ["api"] }"""),
                "../lib/modules/api/init.lua" to """
                    return { pay = function() nf.emit("shop:paid", { amount = 100, from = "test" }) end }
                """,
                "modules/t/init.lua" to """
                    local lib = require("lib:api")
                    nf.on("shop:paid", function(e)
                      e.from = "test"
                      log(e.from, e.amount)
                    end)
                    nf.emit("shop:paid", { amount = 1 })
                    lib.pay()
                """
            )
        ).use { server ->
            assertEquals(emptyList(), server.errors.map { it.message })
            assertEquals(listOf("test\t1", "lib\t100"), server.logs)
        }
    }
}
