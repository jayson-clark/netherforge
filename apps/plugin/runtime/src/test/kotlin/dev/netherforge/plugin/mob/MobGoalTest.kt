package dev.netherforge.plugin.mob
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.testkit.FakeMobGoals
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A mob's goals against the fake server's AI ([FakeMobGoals], which thinks
 * after every [TestServer.tick] as the server does after plugins): the game's
 * goals listed, removed and cleared, and goals written in Lua run by the AI,
 * held to their scope, their budget and the AI's own loop.
 */
class MobGoalTest {
    /** `keys(goals)`: a goal list's keys, in order, comma-separated. */
    private val keys = """
        local function keys(goals)
          local out = {}
          for _, goal in ipairs(goals) do
            out[#out + 1] = goal.key
          end
          return table.concat(out, ",")
        end
    """.trimIndent()

    private fun TestServer.mob(kind: String): UUID = platform.worldEntities.mobs.values.single { it.kind == kind }.id

    /** Runs [body] as a module's body (with [LuaChecks]' helpers and `keys`), then [after] with the server. */
    private fun run(body: String, setup: (TestServer) -> Unit = {}, after: (TestServer) -> Unit = {}) =
        LuaChecks.runModuleBody("$keys\n$body", setup = setup, after = after)

    @Test
    fun `a mob's goals are listed, removed and cleared`() {
        val result = run(
            """
            local world = nf.worlds.default()
            local zombie = world:spawn_entity("minecraft:zombie", vec3(0, 64, 0))
            local goals = zombie:goals()
            check("listed by key", keys(goals), "minecraft:float,minecraft:hurt_by_target,minecraft:look_at_player,minecraft:melee_attack,minecraft:nearest_attackable_target,minecraft:random_stroll")
            local attack = goals[4]
            check("key", attack.key, "minecraft:melee_attack")
            check("the game's priority", attack.priority, 2)
            check("controls", table.concat(attack.controls, ","), "move,look")
            check("idle", attack.running, false)
            check("a targeting goal", table.concat(goals[2].controls, ","), "target")

            check("removed", zombie:remove_goal("minecraft:random_stroll"), true)
            check("for short", zombie:remove_goal("look_at_player"), true)
            check("not twice", zombie:remove_goal("random_stroll"), false)
            check("left", keys(zombie:goals()), "minecraft:float,minecraft:hurt_by_target,minecraft:melee_attack,minecraft:nearest_attackable_target")
            check("cleared", zombie:clear_goals({ keep = { "float", "minecraft:hurt_by_target" } }), true)
            check("kept", keys(zombie:goals()), "minecraft:float,minecraft:hurt_by_target")
            check("all of them", zombie:clear_goals(), true)
            check("none", #zombie:goals(), 0)

            fails("a bad key", function() zombie:remove_goal("Random Stroll") end, "isn't a goal key")
            fails("a bad kept key", function() zombie:clear_goals({ keep = { "ok", "Not OK" } }) end, "options.keep[2]")
            fails("an unknown option", function() zombie:clear_goals({ keep_all = true }) end, "keep_all")

            local cart = world:spawn_entity("minecraft:chest_minecart", vec3(2, 64, 0))
            check("a cart has no goals", cart.goals, nil)
            check("nor add_goal", cart.add_goal, nil)
            local alex = nf.players.get("Alex")
            check("a player has no goals", alex.goals, nil)
            check("nor add_goal", alex.add_goal, nil)
            zombie:remove()
            check("gone: nil", zombie:goals(), nil)
            check("gone: false", zombie:add_goal("x", { priority = 1 }), false)
            """,
            setup = { it.player("Alex") }
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `a goal written in Lua runs when the AI starts it, and stops`() {
        val result = run(
            """
            local world = nf.worlds.default()
            local zombie = world:spawn_entity("minecraft:zombie", vec3(0, 64, 0))
            local wanted, ticks = false, 0
            check("added", zombie:add_goal("guard_post", {
              priority = 1,
              controls = { "move", "look" },
              should_start = function(mob)
                check("the mob", mob, zombie)
                return wanted
              end,
              should_continue = function() return ticks < 3 and 1 end,
              start = function() log("start") end,
              tick = function() ticks = ticks + 1; log("tick " .. ticks) end,
              stop = function() log("stop") end,
            }), true)
            local goal
            for _, it in ipairs(zombie:goals()) do
              if it.key == "test:guard_post" then goal = it end
            end
            check("namespaced", goal ~= nil, true)
            check("its priority", goal.priority, 1)
            check("its controls", table.concat(goal.controls, ","), "move,look")
            nf.commands.register("want", function() wanted = true end)
            nf.commands.register("running", function()
              for _, it in ipairs(zombie:goals()) do
                if it.key == "test:guard_post" then log("running " .. tostring(it.running)) end
              end
            end)
            """,
            after = { server ->
                server.tick(2)
                assertEquals(LuaChecks.DONE, server.output(), "should_start said no")
                server.platform.commands.runConsole("want")
                server.tick()
                server.platform.commands.runConsole("running")
                server.tick(3)
            }
        )
        // should_continue's answer counts as Lua counts it: 1 is yes, nil is no.
        assertEquals(listOf("done", "start", "tick 1", "running true", "tick 2", "tick 3", "stop", "start", "tick 4"), result)
    }

    @Test
    fun `a more important goal takes the controls of a less important one, and target goals run apart`() {
        val result = run(
            """
            local world = nf.worlds.default()
            local zombie = world:spawn_entity("minecraft:zombie", vec3(0, 64, 0))
            local urgent = false
            local function goal(name, priority, controls, ready)
              zombie:add_goal(name, {
                priority = priority,
                controls = controls,
                should_start = ready,
                start = function() log("start " .. name) end,
                stop = function() log("stop " .. name) end,
              })
            end
            goal("wander", 5, { "move" }, function() return true end)
            goal("flee", 1, { "move", "jump" }, function() return urgent end)
            goal("pick_target", 3, { "target" }, function() return true end)
            nf.commands.register("urgent", function() urgent = true end)
            fails("target with more", function() goal("both", 1, { "target", "move" }) end, "can't claim anything else")
            fails("an unknown control", function() goal("fly", 1, { "move", "fly" }) end, "definition.controls[2]")
            fails("a control twice", function() goal("twice", 1, { "move", "move" }) end, "names a control twice")
            fails("not a list", function() goal("odd", 1, { move = true }) end, "must be a list of controls")
            fails("no priority", function() zombie:add_goal("x", {}) end, "'definition.priority' is missing")
            fails("a fraction", function() zombie:add_goal("x", { priority = 1.5 }) end, "definition.priority")
            fails("an unknown field", function() zombie:add_goal("x", { priority = 1, on_tick = print }) end, "unknown field 'definition.on_tick'")
            fails("a bad callback", function() zombie:add_goal("x", { priority = 1, tick = 3 }) end, "definition.tick")
            fails("someone else's id", function() zombie:add_goal("minecraft:float", { priority = 1 }) end, "isn't one of the project's goal ids")
            fails("a bad id", function() zombie:add_goal("Guard Post", { priority = 1 }) end, "isn't a goal id")
            """,
            after = { server ->
                server.tick()
                server.platform.commands.runConsole("urgent")
                server.tick()
                val zombie = server.mob("minecraft:zombie")
                val goals = server.platform.mobGoals
                assertEquals(false, goals.goal(zombie, "test:pick_target")!!.controls.isEmpty())
                assertTrue(goals.byMob.getValue(zombie)[1].any { it.key == "test:pick_target" }, "with the targeting goals")
            }
        )
        assertEquals(listOf("done", "start wander", "start pick_target", "stop wander", "start flee"), result)
    }

    @Test
    fun `a goal with an id the mob has replaces it, and its stop runs`() {
        val result = run(
            """
            local zombie = nf.worlds.default():spawn_entity("minecraft:zombie", vec3(0, 64, 0))
            local function goal(version)
              zombie:add_goal("test:guard", {
                priority = 2,
                start = function() log("start " .. version) end,
                stop = function() log("stop " .. version) end,
              })
            end
            goal(1)
            nf.commands.register("replace", function()
              goal(2)
              local count = 0
              for _, it in ipairs(zombie:goals()) do
                if it.key == "test:guard" then count = count + 1 end
              end
              check("one", count, 1)
            end)
            nf.commands.register("remove", function()
              check("removed", zombie:remove_goal("test:guard"), true)
            end)
            """,
            after = { server ->
                server.tick()
                server.platform.commands.runConsole("replace")
                server.tick()
                server.platform.commands.runConsole("remove")
                server.tick()
                assertEquals(0, server.runtime.session.mobGoals.countOwned(server.runtime.session.scripts.scopes().single()))
            }
        )
        assertEquals(listOf("done", "start 1", "stop 1", "start 2", "stop 2"), result)
    }

    @Test
    fun `a callback that fails is reported with its line, and its goal comes off at the next tick`() {
        val source = """
            local zombie = nf.worlds.default():spawn_entity("minecraft:zombie", vec3(0, 64, 0))
            zombie:add_goal("broken", {
              priority = 1,
              tick = function() error("boom") end,
            })
            zombie:add_goal("fine", { priority = 2, controls = { "look" }, tick = function() log("fine") end })
        """.trimIndent()
        TestServer(mapOf("modules/t/init.lua" to source)).use { server ->
            val zombie = server.mob("minecraft:zombie")
            server.tick()
            val error = server.errors.single()
            assertTrue("goal test:broken's tick failed: " in error.message, error.message)
            assertTrue("boom" in error.message, error.message)
            assertEquals("modules/t/init.lua", error.source?.file)
            assertEquals(4, error.source?.line)
            assertTrue(server.platform.log.lines.any { "goal test:broken was taken off its mob because its tick failed" in it })
            // Not changed while the AI went through the goals: at the runtime's next tick.
            val goals = server.platform.mobGoals
            server.tick()
            assertNull(goals.goal(zombie, "test:broken"))
            assertEquals(1, server.errors.size, "never called again")
            assertEquals(listOf("fine", "fine"), server.logs)
        }
    }

    @Test
    fun `a callback past its budget fails like an error`() {
        val source = """
            local zombie = nf.worlds.default():spawn_entity("minecraft:zombie", vec3(0, 64, 0))
            zombie:add_goal("spin", { priority = 1, should_start = function() while true do end end })
        """.trimIndent()
        TestServer(mapOf("modules/t/init.lua" to source)).use { server ->
            server.tick(2)
            val error = server.errors.single()
            assertTrue("goal test:spin's should_start failed: ran past its budget" in error.message, error.message)
            assertNull(server.platform.mobGoals.goal(server.mob("minecraft:zombie"), "test:spin"))
        }
    }

    @Test
    fun `changes from inside a goal's callbacks wait for the next tick`() {
        val result = run(
            """
            local zombie = nf.worlds.default():spawn_entity("minecraft:zombie", vec3(0, 64, 0))
            local once = false
            zombie:add_goal("once", {
              priority = 1,
              start = function(mob)
                log("start once")
                check("still there", mob:remove_goal("test:once"), true)
                check("strolls", mob:remove_goal("random_stroll"), true)
                check("added", mob:add_goal("next", { priority = 1, start = function() log("start next") end }), true)
                check("cleared", mob:clear_goals({ keep = { "test:next", "test:once" } }), true)
              end,
            })
            """,
            after = { server ->
                // The fake AI throws if its goals change while it goes through them, as the game's loop would break.
                server.tick()
                server.tick()
                server.tick()
                val zombie = server.mob("minecraft:zombie")
                assertEquals(listOf("test:next"), server.platform.mobGoals.goals(zombie)!!.map { it.key })
            }
        )
        assertEquals(listOf("done", "start once", "start next"), result)
    }

    @Test
    fun `a module's goals come off when it restarts, and its new body sets them up again`() {
        val v1 = """
            local zombie = nf.worlds.default():entities({ kind = "minecraft:zombie" })[1]
              or nf.worlds.default():spawn_entity("minecraft:zombie", vec3(0, 64, 0))
            zombie:add_goal("guard", { priority = 1, tick = function() log("v1") end, stop = function() log("v1 stop") end })
        """.trimIndent()
        TestServer(mapOf("modules/t/init.lua" to v1)).use { server ->
            server.tick()
            server.write("modules/t/init.lua", v1.replace("v1", "v2").replace("\"guard\"", "\"guard_v2\""))
            server.reload("modules/t/init.lua")
            server.tick()
            val zombie = server.mob("minecraft:zombie")
            assertEquals(
                listOf("test:guard_v2"),
                server.platform.mobGoals.goals(zombie)!!.map { it.key }.filter { it.startsWith("test:") }
            )
            // The old scope is never called again: not even its stop, as its goal comes off.
            assertEquals(listOf("v1", "v2"), server.logs)
            assertEquals(emptyList(), server.errors)
        }
    }

    @Test
    fun `a mob that goes takes its Lua goals with it`() {
        val source = """
            local world = nf.worlds.default()
            local zombie = world:spawn_entity("minecraft:zombie", vec3(0, 64, 0))
            zombie:add_goal("guard", { priority = 1 })
            local pig = world:spawn_entity("minecraft:pig", vec3(5, 64, 0))
            pig:add_goal("guard", { priority = 1 })
        """.trimIndent()
        TestServer(mapOf("modules/t/init.lua" to source)).use { server ->
            val scope = server.runtime.session.scripts.scopes().single()
            val goals = server.runtime.session.mobGoals
            assertEquals(2, goals.countOwned(scope))
            server.runtime.events.entitiesUnloading(listOf(server.mob("minecraft:pig")))
            assertEquals(1, goals.countOwned(scope), "an unloaded mob comes back with the game's goals")
            server.platform.worldEntities.mobs.remove(server.mob("minecraft:zombie"))
            server.tick(dev.netherforge.plugin.mob.MobGoals.SWEEP_TICKS.toInt())
            assertEquals(0, goals.countOwned(scope), "one that went without a word is found")
        }
    }
}
