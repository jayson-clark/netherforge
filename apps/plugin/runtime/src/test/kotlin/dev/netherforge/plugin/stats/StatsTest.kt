package dev.netherforge.plugin.stats
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.Location
import kotlin.test.Test
import kotlin.test.assertEquals

/** Stats on players and centities: modifiers by source, buffs from `buffs/<id>.json`, counting down and ending. */
class StatsTest {
    private val files = mapOf(
        "buffs/ironskin.json" to
            """{ "name": "Ironskin", "color": "#8a8a98", "stats": { "defense": 8 }, "cures": ["frail"], "maxTicks": 100 }""",
        "buffs/frail.json" to
            """{ "name": "Frail", "debuff": true, "stats": { "defense": -4, "move": -0.2 }, "blockedBy": ["ironskin"], "stacking": "add" }""",
        "buffs/swift.json" to """{ "stats": { "move": 0.25 }, "stacking": "replace" }""",
        "centities/slime/centity.json" to """{ "nodes": { "root": {} } }"""
    )

    @Test
    fun `stats add up every source and every buff`() {
        val result = LuaChecks.run(
            """
            local alex = nf.players.get("Alex")
            check("nothing yet", alex:stat("defense"), 0)
            check("set", alex:set_modifiers("armour", { defense = 5, move = 0.05 }), true)
            alex:set_modifiers("charm", { defense = 2 })
            check("summed", alex:stat("defense"), 7)
            check("read back", alex:modifiers("armour").move, 0.05)
            check("a buff", alex:add_buff("ironskin", 40), true)
            check("with the buff", alex:stat("defense"), 15)
            check("every stat", alex:stats().move, 0.05)
            alex:set_modifiers("charm")
            check("a source taken away", alex:stat("defense"), 13)
            check("gone", alex:modifiers("charm"), nil)
            fails("a bad name", function() alex:set_modifiers("x", { ["Bad Name"] = 1 }) end, "stat name")
            fails("an unknown buff", function() alex:add_buff("nope", 10) end, "no buff")
            fails("ticks", function() alex:add_buff("ironskin", 0) end, "at least 1")

            local slime = nf.centities.spawn("slime", vec3(0, 64, 0))
            slime:add_buff("swift", 20)
            check("a centity's", slime:stat("move"), 0.25)
            check("its buffs", slime:buffs()[1].id, "swift")
            """,
            files,
            setup = { it.platform.players.add("Alex", Location("world", 0.5, 64.0, 0.5)) }
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `cures, blocks, stacking, the most ticks, and the handlers`() {
        val result = LuaChecks.run(
            """
            local alex = nf.players.get("Alex")
            local heard = {}
            alex:on("buff_add", function(event)
              if event.buff == "swift" then event.ticks = 7 end
            end)
            alex:on("buff_end", function(event) heard[#heard + 1] = event.buff .. " " .. event.reason end)
            check("frail", alex:add_buff("frail", 30), true)
            check("frail adds up", alex:add_buff("frail", 30) and alex:buff_ticks("frail"), 60)
            check("ironskin cures it", alex:add_buff("ironskin", 500), true)
            check("cured", alex:has_buff("frail"), false)
            check("capped", alex:buff_ticks("ironskin"), 100)
            check("blocked by ironskin", alex:add_buff("frail", 30), false)
            check("a handler's ticks", alex:add_buff("swift", 50) and alex:buff_ticks("swift"), 7)
            check("replace starts again", alex:add_buff("swift", 50) and alex:buff_ticks("swift"), 7)
            check("removed", alex:remove_buff("ironskin"), true)
            check("not twice", alex:remove_buff("ironskin"), false)
            check("heard", table.concat(heard, ","), "frail cured,ironskin removed")
            local shown = alex:buffs()[1]
            check("shown", shown.id .. " " .. tostring(shown.debuff), "swift false")
            """,
            files,
            setup = { it.platform.players.add("Alex", Location("world", 0.5, 64.0, 0.5)) }
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `buffs count down and end, and a reload changes what they add`() {
        TestServer(
            files + (
                "modules/t/init.lua" to LuaChecks.command(
                    """
            local alex = nf.players.get("Alex")
            alex:on("buff_end", function(event) log("end", event.buff, event.reason) end)
            alex:add_buff("ironskin", 3)
        """
                )
                )
        ).use { server ->
            server.platform.players.add("Alex", Location("world", 0.5, 64.0, 0.5))
            with(LuaChecks) { server.runChecks() }
            val alex = dev.netherforge.plugin.stats.StatSheets.Holder.Player(server.platform.players.byId.keys.single())
            assertEquals(8.0, server.runtime.session.stats.stat(alex, "defense"))
            server.write("buffs/ironskin.json", """{ "stats": { "defense": 10 } }""")
            server.reload("buffs/ironskin.json")
            assertEquals(10.0, server.runtime.session.stats.stat(alex, "defense"), "the file's new numbers")
            server.tick(3)
            assertEquals(listOf("done", "end\tironskin\texpired"), server.logs)
            assertEquals(0.0, server.runtime.session.stats.stat(alex, "defense"))
        }
    }
}
