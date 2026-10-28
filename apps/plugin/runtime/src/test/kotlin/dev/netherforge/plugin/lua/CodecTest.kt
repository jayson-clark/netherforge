package dev.netherforge.plugin.lua
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How values cross between Lua and Kotlin (the `LuaCodec`s the generated
 * bindings are built from), through real API functions: one argument is one
 * Lua value, Kotlin tells what it is, and a mistake names where it was, however
 * deep. Each check runs in Lua and logs only what it got wrong.
 */
class CodecTest {
    /** Runs [body] as Alex's `/run`, with `player` (Alex) and `world` (the default) at hand. */
    private fun run(body: String): List<String> = TestServer(
        mapOf(
            "modules/t/init.lua" to LuaChecks.command("local player = event.player\nlocal world = nf.worlds.default()\n$body"),
            "menus/i/menu.json" to """{ "shared": true }"""
        )
    ).use { server ->
        server.platform.commands.run(server.player("Alex"), "run")
        server.output()
    }

    @Test
    fun `a mistake names where it was, however deep`() {
        val result = run(
            """
            local menu = nf.menus.shared("i")
            fails("a list with names", function() player:sidebar():set_lines({ "a", x = "b" }) end,
              "bad argument 'lines' (list of string expected, got a table with other keys)")
            fails("a list's item", function() player:sidebar():set_lines({ "a", 5 }) end,
              "bad argument 'lines[2]' (string expected, got number)")
            fails("a map's key", function() menu:set_items({ a = { kind = "minecraft:bread" } }) end,
              "bad argument 'items' (whole number keys expected, got a string key)")
            fails("a map's item", function() menu:set_items({ [3] = { kind = "minecraft:bread", cuont = 2 } }) end,
              "items[3]: items have no field \"cuont\"")
            fails("an option table's field", function() nf.centities.all({ near = player }) end,
              "bad argument 'filter.near' (Vec3 or nil expected, got table)")
            fails("a misspelled field", function() nf.centities.all({ raduis = 1 }) end,
              "unknown field 'filter.raduis' (fields: kind, near, radius, world)")
            fails("a union", function() world:border():contains(world) end,
              "bad argument 'location_or_position' (Location or Vec3 expected, got table)")
            fails("a string choice", function() world:set_weather("snow") end,
              "bad argument 'weather' (one of \"clear\", \"rain\", \"thunder\" expected, got \"snow\")")
            fails("a whole number", function() menu:fill({ kind = "minecraft:bread" }, { 1.5 }) end,
              "bad argument 'indices[1]' (whole number expected, got number)")
            fails("a fake handle", function() world:spawn_entity("pig", vec3(0, 64, 0)):add_passenger(setmetatable({}, { __name = "Player" })) end,
              "bad argument 'entity' (Entity expected, got table)")
            -- At the script's line, as Lua's own `error(message, 2)` would put it.
            local ok, err = pcall(function() player:sidebar():set_lines(5) end)
            check("located", err:match("^modules/t/init%.lua:%d+: ") ~= nil, true)
            """
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `a handle goes wherever its class or a class it extends does, and comes back as itself`() {
        val result = run(
            """
            local pig = world:spawn_entity("pig", vec3(0, 64, 0))
            -- A Player is an Entity.
            check("a player as an entity", pig:add_passenger(player), true)
            check("comes back as the player", pig:passengers()[1], player)
            check("a list of them", #pig:passengers(), 1)
            -- 2.0 is a whole number.
            check("a whole float", player:inventory():set_item(2.0, { kind = "minecraft:bread" }), true)
            check("read back", player:inventory():item(2).kind, "minecraft:bread")
            """
        )
        assertEquals(listOf("done"), result)
    }
}
