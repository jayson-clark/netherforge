package dev.netherforge.plugin.mob
import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What an `Entity` is made of and where a mob walks, against the fake
 * server: attributes and the project's own modifiers, `move_to` and the
 * `path_end` the runtime works out each tick.
 */
class MobTest {
    private fun TestServer.mob(kind: String): UUID = platform.worldEntities.mobs.values.single { it.kind == kind }.id

    @Test
    fun `attributes read, change and take the project's modifiers`() {
        val result = LuaChecks.runModuleBody(
            """
            local world = nf.worlds.default()
            local zombie = world:spawn_entity("minecraft:zombie", vec3(0, 64, 0))
            check("value", zombie:attribute("movement_speed"), 0.25)
            check("namespaced", zombie:attribute("minecraft:movement_speed"), 0.25)
            check("base", zombie:attribute_base("movement_speed"), 0.25)
            check("set base", zombie:set_attribute_base("movement_speed", 0.5), true)
            check("new value", zombie:attribute("movement_speed"), 0.5)
            check("add", zombie:add_attribute_modifier("movement_speed", "slow_zone", -0.5, "add_multiplied_total"), true)
            check("slowed", zombie:attribute("movement_speed"), 0.25)
            check("base kept", zombie:attribute_base("movement_speed"), 0.5)
            local modifiers = zombie:attribute_modifiers("movement_speed")
            check("listed", #modifiers, 1)
            check("full id", modifiers[1].id, "test:slow_zone")
            check("amount", modifiers[1].amount, -0.5)
            check("operation", modifiers[1].operation, "add_multiplied_total")
            check("replace", zombie:add_attribute_modifier("movement_speed", "test:slow_zone", 0.25), true)
            modifiers = zombie:attribute_modifiers("movement_speed")
            check("replaced", #modifiers, 1)
            check("added by default", modifiers[1].operation, "add_value")
            check("faster", zombie:attribute("movement_speed"), 0.75)
            check("remove", zombie:remove_attribute_modifier("movement_speed", "slow_zone"), true)
            check("removed", #zombie:attribute_modifiers("movement_speed"), 0)
            check("remove again", zombie:remove_attribute_modifier("movement_speed", "slow_zone"), false)
            check("max_health stays", zombie:max_health(), 20)

            fails("unknown attribute", function() zombie:attribute("minecraft:flying_pigs") end, 'no attribute "minecraft:flying_pigs" on this server')
            fails("unknown everywhere", function() zombie:set_attribute_base("Speed!", 1) end, "no attribute")
            fails("bad id", function() zombie:add_attribute_modifier("armor", "Bad Id", 1) end, "isn't a modifier id")
            fails("not ours to add", function() zombie:add_attribute_modifier("armor", "other:boost", 1) end, "isn't one of the project's modifier ids")
            fails("not ours to remove", function() zombie:remove_attribute_modifier("armor", "minecraft:sprinting") end, "isn't one of the project's modifier ids")
            fails("bad operation", function() zombie:add_attribute_modifier("armor", "x", 1, "multiply") end, "operation")
            fails("not a number", function() zombie:set_attribute_base("armor", 0 / 0) end, "value must be a number")

            local pig = world:spawn_entity("minecraft:pig", vec3(2, 64, 0))
            check("a pig doesn't attack", pig:attribute("attack_damage"), nil)
            check("nor has a base", pig:attribute_base("attack_damage"), nil)
            check("nor modifiers", pig:attribute_modifiers("attack_damage"), nil)
            check("nor takes one", pig:add_attribute_modifier("attack_damage", "x", 1), false)
            check("nor a base", pig:set_attribute_base("attack_damage", 1), false)
            local bread = world:spawn_item(vec3(1, 64, 1), { kind = "minecraft:bread" })
            check("an item has no attributes", bread.attribute, nil)

            local alex = nf.players.get("Alex")
            check("a player's", alex:attribute("movement_speed"), 0.1)
            check("a player grows", alex:set_attribute_base("scale", 2) and alex:attribute("scale"), 2)
            check("not saved", alex:add_attribute_modifier("scale", "shrink", -0.5, "add_value", false), true)

            zombie:remove()
            check("nil once gone", zombie:attribute("armor"), nil)
            check("false once gone", zombie:add_attribute_modifier("armor", "x", 1), false)
            """,
            setup = { it.player("Alex") },
            after = { server ->
                // What reached the server: the project's namespace, the operation as given.
                val alex = server.platform.players.byId.keys.single()
                assertEquals(2.0, server.platform.attributes.byEntity.getValue(alex).getValue("minecraft:scale").base)
                val space = server.platform.attributes.byEntity.getValue(alex).getValue("minecraft:scale").modifiers.getValue("test:shrink")
                assertEquals(false, space.saved)
            }
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `a mob walks where move_to sends it, and path_end says whether it got there`() {
        val result = LuaChecks.runModuleBody(
            """
            local world = nf.worlds.default()
            local zombie = world:spawn_entity("minecraft:zombie", vec3(0, 64, 0))
            zombie:on("path_end", function(event)
              log("path_end " .. tostring(event.reached) .. " " .. tostring(event.entity == zombie))
            end)
            check("sets off", zombie:move_to(vec3(10, 64, 10), { speed = 1.5 }), true)
            check("walking", zombie:has_path(), true)
            check("heading", zombie:path_target(), vec3(10, 64, 10))
            fails("too slow", function() zombie:move_to(vec3(1, 64, 1), { speed = 0 }) end, "options.speed must be more than 0")
            fails("not a place", function() zombie:move_to("there") end, "Location, Vec3 or Entity")
            check("a location in its world", zombie:move_to(world:location(vec3(1, 64, 1)), {}), true)
            check("back on course", zombie:move_to(vec3(10, 64, 10), { speed = 1.5 }), true)
            """,
            after = { server ->
                val zombie = server.mob("minecraft:zombie")
                val paths = server.platform.pathfinding
                assertEquals(Vec3(10.0, 64.0, 10.0) to 1.5, paths.paths[zombie])
                server.tick()
                // Still on its way: nothing yet.
                assertEquals(LuaChecks.DONE, server.output())
                paths.arrive(zombie)
                server.tick()
                assertEquals(listOf("done", "path_end true true"), server.logs)
                server.tick(3)
                assertEquals(2, server.logs.size, "a walk ends once")
            }
        )
        assertEquals(listOf("done", "path_end true true"), result)
    }

    @Test
    fun `a walk is given up when the mob stops short, or its AI sends it elsewhere`() {
        val result = LuaChecks.runModuleBody(
            """
            local world = nf.worlds.default()
            local zombie = world:spawn_entity("minecraft:zombie", vec3(0, 64, 0))
            zombie:on("path_end", function(event)
              log("path_end " .. tostring(event.reached))
            end)
            nf.commands.register("go", function()
              zombie:move_to(vec3(20, 64, 0))
            end)
            nf.commands.register("stop", function()
              check("stopped", zombie:stop_pathing(), true)
              check("not walking", zombie:has_path(), false)
              check("nowhere", zombie:path_target(), nil)
            end)
            """,
            after = { server ->
                val zombie = server.mob("minecraft:zombie")
                val paths = server.platform.pathfinding
                server.platform.commands.runConsole("go")
                paths.stall(zombie)
                server.tick()
                server.platform.commands.runConsole("go")
                paths.divert(zombie, Vec3(-5.0, 64.0, 3.0))
                server.tick()
                // Stopping is the script's own doing: no event.
                server.platform.commands.runConsole("go")
                server.platform.commands.runConsole("stop")
                server.tick(2)
                // Nowhere to go.
                paths.blocked = { true }
                server.platform.commands.runConsole("go")
                server.tick()
            }
        )
        assertEquals(listOf("done", "path_end false", "path_end false"), result)
    }

    @Test
    fun `a mob follows an entity, finding a new path as it moves, until it has gone`() {
        val result = LuaChecks.runModuleBody(
            """
            local world = nf.worlds.default()
            local zombie = world:spawn_entity("minecraft:zombie", vec3(0, 64, 0))
            local pig = world:spawn_entity("minecraft:pig", vec3(8, 64, 0))
            zombie:on("path_end", function(event)
              log("path_end " .. tostring(event.reached))
            end)
            check("follows", zombie:move_to(pig), true)
            check("toward it", zombie:path_target(), vec3(8, 64, 0))
            check("not itself", zombie:move_to(zombie), false)
            check("not a mob", pig:has_path(), false)
            nf.commands.register("again", function()
              zombie:move_to(pig)
            end)
            """,
            after = { server ->
                val zombie = server.mob("minecraft:zombie")
                val pig = server.platform.worldEntities.mobs.getValue(server.mob("minecraft:pig"))
                val paths = server.platform.pathfinding
                pig.location = pig.location.copy(x = 12.0)
                server.tick(MobPaths.REPATH_TICKS.toInt())
                assertEquals(Vec3(12.0, 64.0, 0.0), paths.pathEnd(zombie), "a new path to where it is now")
                paths.arrive(zombie)
                server.tick()
                server.platform.commands.runConsole("again")
                server.platform.worldEntities.remove(pig.id)
                server.tick()
            }
        )
        assertEquals(listOf("done", "path_end true", "path_end false"), result)
    }

    @Test
    fun `things that aren't mobs don't walk`() {
        val result = LuaChecks.runModuleBody(
            """
            local world = nf.worlds.default()
            local cart = world:spawn_entity("minecraft:chest_minecart", vec3(0, 64, 0))
            check("a cart has no move_to", cart.move_to, nil)
            check("nor stop_pathing", cart.stop_pathing, nil)
            local stand = world:spawn_entity("minecraft:armor_stand", vec3(2, 64, 0))
            check("an armour stand is living", getmetatable(stand), "Living")
            check("but doesn't walk", stand.move_to, nil)
            local alex = nf.players.get("Alex")
            check("a player has no move_to", alex.move_to, nil)
            check("nor stop_pathing", alex.stop_pathing, nil)
            """,
            setup = { it.player("Alex") }
        )
        assertEquals(listOf("done"), result)
    }
}
