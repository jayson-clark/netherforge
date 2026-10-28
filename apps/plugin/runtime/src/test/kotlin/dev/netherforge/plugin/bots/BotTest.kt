package dev.netherforge.plugin.bots
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.bridge.BotAction
import dev.netherforge.format.bridge.BotPackAnswer
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.SkinTexture
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `nf.bots` and `Bot` on the fake: joining (the owner's `bots:`, the
 * requirement, a script's mistakes), what a script's bot is (a new player,
 * the pack loaded, its skin), its controls reaching the platform as the
 * client's actions, and leaving with the script that joined it.
 */
class BotTest {
    private fun server(
        script: String,
        requires: Boolean = true,
        bots: BotsConfig = BotsConfig(enabled = true),
        files: Map<String, Any> = emptyMap()
    ) = TestServer(
        mapOf(
            TestServer.MANIFEST to TestServer.manifest(requires = if (requires) """{ "bots": true }""" else null),
            "modules/t/init.lua" to LuaChecks.HELPERS + "\n" + script
        ) + files,
        bots = bots
    )

    /** Ticks until the join (a later turn of the fake's main thread) and its delivery have both happened. */
    private fun TestServer.joined() = tick(3)

    @Test
    fun `a bot joins in a task as a new player, a Player too, with the pack loaded and its skin`() {
        server(
            """
            nf.task(function()
              local bot, err = nf.bots.join("Sparring", vec3(4.5, 64, 0.5), { skin = { texture = "dGV4dHVyZQ==", signature = "c2ln" } })
              check("err", err, nil)
              check("is a player", bot:is_player(), true)
              check("name", bot:name(), "Sparring")
              check("listed", nf.bots.all()[1], bot)
              check("the same handle", nf.players.get("Sparring"), bot)
              near("where", bot:position(), vec3(4.5, 64, 0.5))
              log("done")
            end)
            """
        ).use { server ->
            server.joined()
            assertEquals(LuaChecks.DONE, server.output())
            val join = server.platform.bots.joins.single()
            assertEquals(BotPackAnswer.LOADED, join.pack)
            assertEquals(SkinTexture("dGV4dHVyZQ==", "c2ln"), join.script?.skin)
            val bot = server.platform.players.find("Sparring")!!
            assertNotEquals(UUID.nameUUIDFromBytes("Sparring".toByteArray()), bot.uuid, "a script's bot has a UUID of its own")
            assertTrue(bot.uuid in server.platform.bots.scripted)
        }
    }

    @Test
    fun `a bot's controls are its client's actions, and a click on a node aims at its hitbox`() {
        server(
            """
            local pig
            nf.task(function()
              local bot = nf.bots.join("Sparring", vec3(0.5, 64, 0.5))
              local at, err = bot:walk_to(vec3(2.5, 64, 0.5), { sprint = true })
              near("walked", at, vec3(2.5, 64, 0.5), 0.5)
              check("walk err", err, nil)
              check("look", bot:look_at(vec3(4, 65, 0)), true)
              check("jump", bot:jump(), true)
              check("sneak", bot:set_sneaking(true), true)
              check("chat", bot:chat("hello"), true)
              pig = nf.worlds.default():spawn_entity("minecraft:pig", vec3(3.5, 64, 0.5))
              check("attack", bot:attack(pig), true)
              check("stop", bot:stop(), true)
              local tower = nf.centities.spawn("c", vec3(3.5, 64, 2.5))
              check("click a node", bot:interact(tower:node("root")), true)
              check("respawn when alive", bot:respawn(), false)
              log("done")
            end)
            """,
            files = mapOf("centities/c/centity.json" to TestServer.scriptedCentity(), "centities/c/script.lua" to "-- nothing")
        ).use { server ->
            server.joined()
            server.tick(3)
            assertEquals(LuaChecks.DONE, server.output())
            val acted = server.platform.bots.acted.map { it.second }
            assertEquals(BotAction.WalkTo(2.5, 0.5, sprint = true), acted[0])
            assertEquals(
                listOf(BotAction.LookAt(4.0, 65.0, 0.0), BotAction.Jump, BotAction.Sneak(true), BotAction.Chat("hello")),
                acted.subList(1, 5)
            )
            assertTrue(acted[5] is BotAction.Attack)
            assertEquals(BotAction.Stop, acted[6])
            // The node's hitbox, which only the runtime knows.
            val (_, click, aim) = server.platform.bots.acted[7]
            assertTrue(click is BotAction.Interact)
            val tower = server.runtime.session.centities.all().single()
            assertEquals(tower.hitboxes.getValue("root"), aim!!.entity)
        }
    }

    @Test
    fun `a bot leaves with the script that joined it`() {
        server("nf.bots.join(\"Sparring\", nil, nil, function(bot) log(\"in\") end)").use { server ->
            server.joined()
            assertEquals(listOf("in"), server.output())
            assertTrue(server.platform.players.find("Sparring") != null)
            server.write("modules/t/init.lua", "-- no bots now")
            server.reload("modules/t/init.lua")
            assertNull(server.platform.players.find("Sparring"))
            assertTrue(server.platform.bots.scripted.isEmpty())
            assertNull(server.platform.players.known("Sparring"), "a script's bot is never saved")
        }
    }

    @Test
    fun `the owner decides whether there are bots and how many, and a busy name is no bot`() {
        val joining = """
            nf.task(function()
              local first, err1 = nf.bots.join("One")
              local second, err2 = nf.bots.join("Two")
              local again, err3 = nf.bots.join("One")
              log(tostring(first ~= nil), tostring(err1), tostring(second ~= nil), tostring(err2), tostring(again ~= nil), tostring(err3))
            end)
        """
        server(joining, bots = BotsConfig(enabled = true, max = 1)).use { server ->
            server.joined()
            server.tick(3)
            assertEquals(
                listOf(
                    "true\tnil\tfalse\t1 bots are online already, as many as this server allows (bots.max in config.yml)\tfalse\t" +
                        "1 bots are online already, as many as this server allows (bots.max in config.yml)"
                ),
                server.output()
            )
        }
        server(joining, bots = BotsConfig(enabled = false)).use { server ->
            server.joined()
            val off = "bots are off on this server: the server owner turns them on with bots.enabled in config.yml"
            assertEquals(listOf("false\t$off\tfalse\t$off\tfalse\t$off"), server.output())
            val problem = server.runtime.session.problems().single { it.code == ProblemCodes.RUNTIME_BOTS_DISABLED.code }
            assertEquals("$.requires.bots", problem.path)
        }
        server(
            """
            nf.task(function()
              nf.wait(1)
              local bot, err = nf.bots.join("Alex")
              log(tostring(bot), err)
            end)
            """
        ).also { it.player("Alex") }.use { server ->
            server.joined()
            assertEquals(listOf("nil\tsomeone called Alex is online already"), server.output())
        }
    }

    @Test
    fun `a script's mistakes are errors, and a package must declare bots`() {
        server(
            """
            fails("a name", function() nf.bots.join("no spaces", nil, nil, function() end) end, "3 to 16 letters, digits or _")
            nf.task(function()
              local bot = nf.bots.join("Sparring")
              fails("a timeout", function() bot:walk_to(vec3(9, 64, 9), { timeout = 0 }) end, "1 to 500 ticks")
              log("done")
            end)
            """
        ).use { server ->
            server.joined()
            assertEquals(LuaChecks.DONE, server.output())
        }
        server(
            """fails("undeclared", function() nf.bots.all() end, "\"requires\": { \"bots\": true }")
            log("done")""",
            requires = false
        ).use { server ->
            server.tick()
            assertEquals(LuaChecks.DONE, server.output())
        }
    }

    @Test
    fun `a bot that has left answers false, and its walk nil and why`() {
        server(
            """
            nf.task(function()
              local bot = nf.bots.join("Sparring")
              check("leave", bot:leave(), true)
              check("leave again", bot:leave(), false)
              check("jump", bot:jump(), false)
              local at, err = bot:walk_to(vec3(9, 64, 9))
              check("walk", at, nil)
              check("why", err, "the bot has left")
              log("done")
            end)
            """
        ).use { server ->
            server.joined()
            server.tick(3)
            assertEquals(LuaChecks.DONE, server.output())
        }
    }
}
