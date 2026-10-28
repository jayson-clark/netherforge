package dev.netherforge.plugin.centity
import dev.netherforge.format.centity.TextDisplay
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.Location
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A centity's `health`: damage, healing, death, and the bar drawn over it. */
class CentityHealthTest {
    private fun files(health: String, script: String = "") = mapOf(
        "centities/slime/centity.json" to """
            {
              "nodes": { "root": { "hitbox": { "boxes": [ { "min": [-0.5, 0, -0.5], "max": [0.5, 1, 0.5] } ] } } },
              "health": $health,
              "script": { "file": "script.lua" }
            }
        """,
        "centities/slime/script.lua" to script,
        "centities/rock/centity.json" to """{ "nodes": { "root": {} } }"""
    )

    @Test
    fun `damage goes through its handlers, and at 0 it dies and is removed`() {
        val result = LuaChecks.run(
            """
            local slime = nf.centities.spawn("slime", vec3(0, 64, 0))
            check("whole", slime:health(), 14)
            check("max", slime:max_health(), 14)
            local heard = {}
            slime:on("damage", function(event)
              heard[#heard + 1] = event.cause
              if event.cause == "fire" then event:cancel() end
              if event.cause == "melee" then event.amount = event.amount - 1 end
            end)
            slime:on("death", function(event) heard[#heard + 1] = "death " .. tostring(event.cause) end)
            check("cancelled", slime:damage(5, { cause = "fire" }), 0)
            check("armour", slime:damage(5, { cause = "melee" }), 4)
            check("left", slime:health(), 10)
            check("healed", slime:heal(100), 4)
            check("whole again", slime:health(), 14)
            check("the last blow takes only what's left", slime:damage(50, { cause = "smash" }), 14)
            check("heard", table.concat(heard, ","), "fire,melee,smash,death smash")
            check("gone", slime:exists(), false)

            local rock = nf.centities.spawn("rock", vec3(3, 64, 0))
            check("no health", rock:health(), nil)
            check("can't be damaged", rock:damage(3), nil)
            check("given some", rock:set_max_health(20), true)
            check("full", rock:health(), 20)
            check("now it can", rock:damage(3), 3)
            fails("negative", function() rock:damage(-1) end, "at least 0")
            fails("max", function() rock:set_max_health(0) end, "more than 0")
            """,
            files("""{ "max": 14 }""")
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `one its file keeps stays dead until it's given health`() {
        val result = LuaChecks.run(
            """
            local slime = nf.centities.spawn("slime", vec3(0, 64, 0))
            check("dies", slime:damage(20), 10)
            check("kept", slime:exists(), true)
            check("dead", slime:is_dead(), true)
            check("no more damage", slime:damage(1), nil)
            check("set", slime:set_health(5), true)
            check("alive", slime:is_dead(), false)
            """,
            files("""{ "max": 10, "removeOnDeath": false }""")
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `its bar shows what it has left for a while after a change`() {
        TestServer(files("""{ "max": 10, "bar": { "segments": 5, "showTicks": 3 } }""")).use { server ->
            val slime = assertNotNull(server.runtime.session.centities.spawn("slime", Location("world", 0.0, 64.0, 0.0)))
            val bar = assertNotNull(slime.indexOf("health_bar"), "a bar node was added")
            assertFalse(slime.visible(bar), "hidden until it's hurt")
            assertEquals(1.35, slime.get(bar, dev.netherforge.format.centity.Channel.TRANSLATION).y, 1e-9, "over its hitbox")
            server.runtime.session.centities.healths.damage(slime, 4.0, null, null)
            assertTrue(slime.visible(bar))
            assertEquals("<red>▮▮▮<dark_gray>▮▮", (slime.display(bar) as TextDisplay).text)
            server.tick(4)
            assertFalse(slime.visible(bar), "hidden again")
        }
    }
}
