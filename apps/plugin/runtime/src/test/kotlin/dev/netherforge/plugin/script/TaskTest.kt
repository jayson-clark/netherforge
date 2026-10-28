package dev.netherforge.plugin.script
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.DEFAULT_CHAT_FORMAT
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.Location
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `nf.task` and its waits, and `dialog:ask`. */
class TaskTest {
    private val at = Location("world", 0.0, 64.0, 0.0)

    private fun module(init: String, vararg more: Pair<String, Any>) = mapOf<String, Any>("modules/t/init.lua" to init) + more

    @Test
    fun `a task runs now to its first wait, and carries on after the ticks it waited`() {
        TestServer(
            module(
                """
                local start = nf.server.tick()
                local function at() return nf.server.tick() - start end
                local task = nf.task(function(a, b)
                  log("start", a, b, at())
                  nf.wait(3)
                  log("after 3", at())
                  nf.wait(0)
                  log("after 0", at())
                  nf.wait(1)
                  log("after 1", at())
                end, "x", "y")
                log("body goes on", task:is_active())
                nf.after(10, function() log("finished", task:is_active()) end)
                """
            )
        ).use { server ->
            server.tick(12)
            assertEquals(
                listOf("start\tx\ty\t0", "body goes on\ttrue", "after 3\t3", "after 0\t4", "after 1\t5", "finished\tfalse"),
                server.logs
            )
            assertEquals(emptyList(), server.errors)
        }
    }

    @Test
    fun `tasks waiting for the same tick carry on in the order they waited`() {
        TestServer(
            module(
                """
                for _, name in ipairs({ "a", "b", "c" }) do
                  nf.task(function()
                    nf.wait(2)
                    log(name)
                    nf.wait(1)
                    log(name .. "2")
                  end)
                end
                """
            )
        ).use { server ->
            server.tick(5)
            assertEquals(listOf("a", "b", "c", "a2", "b2", "c2"), server.logs)
        }
    }

    @Test
    fun `wait_until checks now and every tick, and gives up after its timeout`() {
        TestServer(
            module(
                """
                local start = nf.server.tick()
                local function at() return nf.server.tick() - start end
                local flag = false
                nf.after(4, function() flag = true end)
                nf.task(function()
                  log("already", nf.wait_until(function() return true end), at())
                  log("flag", nf.wait_until(function() return flag end), at())
                  log("never", nf.wait_until(function() return false end, 3), at())
                  log("zero", nf.wait_until(function() return false end, 0), at())
                end)
                """
            )
        ).use { server ->
            server.tick(10)
            assertEquals(listOf("already\ttrue\t0", "flag\ttrue\t4", "never\tfalse\t7", "zero\tfalse\t7"), server.logs)
        }
    }

    @Test
    fun `wait_for returns the next such event, filtered, and the task can still cancel it`() {
        TestServer(
            module(
                """
                nf.task(function()
                  local event = nf.wait_for(nf, "shop:buy")
                  log("bought", event.item, event.name)
                  event:cancel()
                  local chat = nf.wait_for(nf, "player_chat", function(e) return e.message == "yes" end)
                  log("heard", chat.player:name(), chat.message)
                end)
                nf.on("player_chat", function(e) log("chat", e.message) end)
                nf.commands.register("buy", function() log("vetoed", nf.emit("shop:buy", { item = "bread" })) end)
                """
            )
        ).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "buy")
            server.platform.raise.playerChat(GameEvent.PlayerChat(alex.ref, "no", DEFAULT_CHAT_FORMAT))
            server.platform.raise.playerChat(GameEvent.PlayerChat(alex.ref, "yes", DEFAULT_CHAT_FORMAT))
            server.platform.raise.playerChat(GameEvent.PlayerChat(alex.ref, "yes", DEFAULT_CHAT_FORMAT))
            assertEquals(
                // The task waits for chat after the module's own handler was registered, so it hears it second.
                listOf("bought\tbread\tshop:buy", "vetoed\ttrue", "chat\tno", "chat\tyes", "heard\tAlex\tyes", "chat\tyes"),
                server.logs
            )
        }
    }

    @Test
    fun `a task waiting on a handle ends when the handle goes`() {
        TestServer(
            module(
                """
                local task
                nf.commands.register("watch", function()
                  local door = nf.centities.all()[1]
                  task = nf.task(function()
                    log("waiting")
                    nf.wait_for(door, "door:open")
                    log("never")
                  end)
                end)
                nf.commands.register("check", function() log("active", task:is_active()) end)
                """,
                "centities/c/centity.json" to TestServer.scriptedCentity(),
                "centities/c/script.lua" to "-- nothing"
            )
        ).use { server ->
            val alex = server.player("Alex")
            val instance = server.runtime.session.centities.spawn("c", at)!!
            server.platform.commands.run(alex, "watch")
            server.platform.commands.run(alex, "check")
            server.runtime.session.centities.remove(instance)
            server.platform.commands.run(alex, "check")
            assertEquals(listOf("waiting", "active\ttrue", "active\tfalse"), server.logs)
            assertEquals(emptyList(), server.errors)
        }
    }

    @Test
    fun `a centity's own task waits for its animation to end`() {
        TestServer(
            mapOf(
                "centities/c/centity.json" to """
                    {
                      "nodes": { "root": { "display": { "type": "block", "block": "minecraft:stone" } } },
                      "script": { "file": "script.lua" },
                      "animations": { "open": { "tracks": { "root": { "rotation": [{ "time": 0, "value": [0, 0, 0] }, { "time": 0.25, "value": [0, 90, 0] }] } } } }
                    }
                """,
                "centities/c/script.lua" to """
                    nf.task(function()
                      local start = nf.server.tick()
                      this:play_animation("open")
                      local event = nf.wait_for(this, "animation_end")
                      log("ended", event.animation, nf.server.tick() - start)
                    end)
                """
            )
        ).use { server ->
            server.runtime.session.centities.spawn("c", at)
            server.tick(10)
            assertEquals(1, server.logs.size, "${server.logs} ${server.errors}")
            assertEquals("ended\topen\t5", server.logs.single())
        }
    }

    @Test
    fun `the example door opens on a click, and a task waits to shut it again`() {
        TestServer(TestServer.example("showcase")).use { server ->
            assertTrue(server.runtime.currentProblems().isEmpty(), "${server.runtime.currentProblems()}")
            val alex = server.player("Alex")
            val door = server.runtime.spawnNear("door", alex.ref)
            val panel = server.platform.entities.hitbox(door.id, "panel")
            server.runtime.events.entityClicked(panel.id, alex.ref, ClickButton.RIGHT, null)
            // It swings open at once, with a shockwave. (The expo's booths play effects of their own, a looping trail
            // among them: only the door's are counted.)
            fun shockwaves() = server.runtime.session.particles.all().filter { it.kind == "shockwave" }
            assertEquals(listOf("shockwave"), shockwaves().map { it.kind })
            assertTrue(door.animations.isPlaying("open"))
            server.tick(30)
            val toAlex = server.platform.particles.sent.filter { (_, spawns, viewers) -> spawns.isNotEmpty() && alex.ref in viewers }
            assertTrue(toAlex.isNotEmpty(), "the shockwave reached Alex: ${server.platform.particles.sent}")
            assertEquals(emptyList(), shockwaves())
            // `open` holds its last pose; the task waits for it to end, then three seconds more, then shuts it.
            assertTrue(door.animations.isPlaying("open"), "held open")
            server.tick(60)
            assertFalse(door.animations.isPlaying("open"), "shut by the task")
            assertTrue(door.animations.isPlaying("close"))

            // A click while it's open shuts it at once, and cancels the task that would have.
            server.tick(20)
            server.runtime.events.entityClicked(panel.id, alex.ref, ClickButton.RIGHT, null)
            assertTrue(door.animations.isPlaying("open"))
            server.tick(5)
            server.runtime.events.entityClicked(panel.id, alex.ref, ClickButton.RIGHT, null)
            assertFalse(door.animations.isPlaying("open"))
            server.tick(100)
            assertFalse(door.animations.isPlaying("open"), "nothing opens it again")
            assertEquals(emptyList(), server.errors)
        }
    }

    @Test
    fun `cancel ends a waiting task, and one that cancels itself stops at its next wait`() {
        TestServer(
            module(
                """
                local waiting = nf.task(function() nf.wait(2) log("never") end)
                waiting:cancel()
                waiting:cancel()
                log("cancelled", waiting:is_active())
                local me
                me = nf.task(function()
                  nf.wait(1)
                  me:cancel()
                  log("goes on to its next wait", me:is_active())
                  nf.wait(1)
                  log("never either")
                end)
                local timer = nf.after(1, function() log("never a timer") end)
                timer:cancel()
                log("timer", timer:is_active())
                """
            )
        ).use { server ->
            server.tick(5)
            assertEquals(listOf("cancelled\tfalse", "timer\tfalse", "goes on to its next wait\tfalse"), server.logs)
            assertEquals(emptyList(), server.errors)
        }
    }

    @Test
    fun `a task ends with its script`() {
        TestServer(
            module(
                """
                nf.task(function()
                  local close <close> = setmetatable({}, { __close = function() log("closed") end })
                  while true do
                    nf.wait(1)
                    log("tick")
                  end
                end)
                """
            )
        ).use { server ->
            server.tick(2)
            server.write("modules/t/init.lua", "log('reloaded')")
            server.reload("modules/t/init.lua")
            server.tick(3)
            assertEquals(listOf("tick", "tick", "closed", "reloaded"), server.logs)
        }
    }

    @Test
    fun `an error in a task is logged at its line and ends the task, and its starter carries on`() {
        TestServer(
            module(
                """
                local task = nf.task(function()
                  nf.wait(1)
                  error("boom")
                end)
                log("started")
                nf.after(3, function() log("active", task:is_active()) end)
                nf.task(function() local x = nil; x.y = 1 end)
                log("still going")
                """
            )
        ).use { server ->
            server.tick(4)
            assertEquals(listOf("started", "still going", "active\tfalse"), server.logs)
            val errors = server.errors
            assertEquals(2, errors.size, "${errors.map { it.message }}")
            assertTrue(errors[0].message.startsWith("module t: task failed: attempt to index"), errors[0].message)
            assertEquals(7, errors[0].source?.line)
            assertEquals("module t: task failed: boom", errors[1].message)
            assertEquals("modules/t/init.lua", errors[1].source?.file)
            assertEquals(3, errors[1].source?.line)
        }
    }

    @Test
    fun `a task started by a call with little left says it has only that left`() {
        // Its resume is charged to the timer that started it, so the timer's budget is the task's limit too.
        TestServer(
            module(
                """
                nf.after(1, function()
                  while nf.instructions_left() > 30000 do end
                  local outer = nf.instructions_left()
                  nf.task(function()
                    local left = nf.instructions_left()
                    log(left <= outer, left > outer - 2000)
                  end)
                end)
                """
            )
        ).use { server ->
            server.tick(2)
            assertEquals(emptyList(), server.errors.map { it.message })
            assertEquals(listOf("true\ttrue"), server.logs)
        }
    }

    @Test
    fun `each resume gets the whole budget, and one resume can't run past it`() {
        TestServer(
            module(
                """
                nf.task(function()
                  for i = 1, 3 do
                    while nf.instructions_left() > 5000 do end
                    nf.wait(1)
                  end
                  log("three budgets' worth")
                  nf.wait_for(nf, "t:go")
                  while nf.instructions_left() > 5000 do end
                  log("and one more, woken by an event")
                  nf.wait(1)
                  while true do end
                end)
                nf.after(5, function() nf.emit("t:go") end)
                """
            )
        ).use { server ->
            server.tick(8)
            assertEquals(listOf("three budgets' worth", "and one more, woken by an event"), server.logs)
            val error = server.errors.single()
            assertTrue("task failed: ran past its budget" in error.message, error.message)
            assertEquals(11, error.source?.line, error.message)
        }
    }

    @Test
    fun `waiting outside a task's own code is an error that says what to do`() {
        val cases = listOf(
            "nf.wait(1)" to "nf.wait only works inside a task: start one with nf.task",
            "nf.on('tick', function() nf.wait_until(function() return true end) end)" to "nf.wait_until only works inside a task",
            "nf.task(function() nf.on('t:x', function() nf.wait(1) end); nf.emit('t:x') end)" to
                "nf.wait can't wait in an event handler, even one a task set off",
            "nf.task(function() coroutine.wrap(function() nf.wait(1) end)() end)" to "nf.wait can't wait inside a coroutine of your own",
            "nf.task(function() table.sort({ 2, 1 }, function(a, b) nf.wait(1) return a < b end) end)" to
                "can't wait inside a function called back from outside Lua",
            "nf.task(function() coroutine.yield() end)" to "coroutine.yield can't pause a task: use nf.wait",
            "nf.task(function() nf.wait_for(nf, 'tik') end)" to "no event \"tik\" for nf.on",
            "nf.task(function() nf.wait_for({}, 'tick') end)" to "bad argument 'handle'",
            "nf.task(function() nf.wait(-1) end)" to "bad argument 'ticks' (can't be negative)"
        )
        for ((line, message) in cases) {
            TestServer(module("-- line 1\n$line\n")).use { server ->
                server.tick(2)
                val error = server.errors.singleOrNull()
                assertTrue(error != null && message in error.message, "$line: ${server.errors.map { it.message }}")
                assertEquals(2, error.source?.line, "$line: ${error.message}")
            }
        }
    }

    @Test
    fun `a wait inside pcall in a task is fine`() {
        TestServer(
            module(
                """
                nf.task(function()
                  log(pcall(function() nf.wait(1) return "waited" end))
                end)
                """
            )
        ).use { server ->
            server.tick(2)
            assertEquals(listOf("true\twaited"), server.logs)
        }
    }

    private val dialogs = mapOf(
        "dialogs/ask/dialog.json" to """
            { "type": "multi_action", "title": "Ask", "buttons": [{ "key": "yes" }, { "key": "no" }] }
        """,
        "modules/t/init.lua" to """
            nf.dialogs.get("ask"):on("press", function(event) log("dialog heard", event.key) end)
            nf.commands.register("ask", function(ctx)
              nf.task(function()
                local answer = nf.dialogs.get("ask"):ask(ctx.player)
                log("answer", answer and answer.key or "nil", answer and answer.values ~= nil)
              end)
            end)
            nf.commands.register("shut", function(ctx) ctx.player:close_dialog() end)
        """
    )

    @Test
    fun `dialog ask opens the dialog and returns the player's press`() {
        TestServer(dialogs).use { server ->
            val alex = server.player("Alex")
            val bea = server.player("Bea")
            server.platform.commands.run(alex, "ask")
            assertEquals("ask", server.platform.dialogs.showing.getValue(alex.ref.uuid).id)
            // Someone else's press on the same dialog isn't the answer.
            server.platform.dialogs.showing[bea.ref.uuid] = server.platform.dialogs.showing.getValue(alex.ref.uuid)
            server.platform.dialogs.press(bea, "no")
            server.platform.dialogs.press(alex, "yes")
            server.platform.dialogs.press(alex, "yes")
            assertEquals(listOf("dialog heard\tno", "dialog heard\tyes", "answer\tyes\ttrue", "dialog heard\tyes"), server.logs)
        }
    }

    @Test
    fun `dialog ask returns nil when the dialog is closed or the player leaves`() {
        TestServer(dialogs).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "ask")
            server.platform.dialogs.escape(alex)
            server.platform.commands.run(alex, "ask")
            server.platform.commands.run(alex, "shut")
            server.platform.commands.run(alex, "ask")
            server.platform.raise.playerQuit(GameEvent.PlayerQuit(alex.ref, null))
            assertEquals(listOf("answer\tnil\tnil", "answer\tnil\tnil", "answer\tnil\tnil"), server.logs)
            assertEquals(emptyList(), server.errors)
        }
    }

    @Test
    fun `dialog ask for someone offline returns nil at once, and outside a task is an error`() {
        TestServer(
            dialogs + (
                "modules/u/init.lua" to """
                    local held
                    nf.commands.register("hold", function(ctx) held = ctx.player end)
                    nf.commands.register("later", function()
                      nf.task(function() log("offline", nf.dialogs.get("ask"):ask(held)) end)
                    end)
                    nf.commands.register("now", function(ctx) nf.dialogs.get("ask"):ask(ctx.player) end)
                """
                )
        ).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "hold")
            server.platform.players.byId.remove(alex.ref.uuid)
            server.platform.commands.runAsConsole("later")
            server.platform.players.byId[alex.ref.uuid] = alex
            server.platform.commands.run(alex, "now")
            assertEquals(listOf("offline\tnil"), server.logs)
            assertTrue(server.errors.any { "dialog:ask only works inside a task" in it.message }, "${server.errors.map { it.message }}")
        }
    }
}
