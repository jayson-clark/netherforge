package dev.netherforge.plugin.lua
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.Location
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `Vec3` and `Location`, the values the prelude implements: their maths,
 * that they can't be changed or faked, and how they print. Each check runs in
 * Lua and logs only what it got wrong, so a failure names the expression.
 */
class ValuesTest {
    @Test
    fun `vectors add, scale and compare`() {
        val result = LuaChecks.run(
            """
            local a, b = vec3(1, 2, 3), vec3(4, 5, 6)
            check("fields", a.x + a.y + a.z, 6)
            check("add", a + b, vec3(5, 7, 9))
            check("sub", b - a, vec3(3, 3, 3))
            check("unm", -a, vec3(-1, -2, -3))
            check("scale", a * 2, vec3(2, 4, 6))
            check("scale on the left", 2 * a, vec3(2, 4, 6))
            check("component-wise", a * b, vec3(4, 10, 18))
            check("divide", b / 2, vec3(2, 2.5, 3))
            check("equal", vec3(1, 2, 3) == a, true)
            check("equal is exact", vec3(1, 2, 3.0000001) == a, false)
            check("not equal to a table", a == { 1, 2, 3 }, false)
            check("tostring", tostring(a), "vec3(1, 2, 3)")
            check("tostring of floats", tostring(vec3(1.0, -0.5, 2 / 3)), "vec3(1, -0.5, 0.66666666666667)")
            check("type", type(a), "table")
            check("metatable", getmetatable(a), "Vec3")
            local x, y, z = a:unpack()
            check("unpack", x + y * 10 + z * 100, 321)
            fails("add a number", function() return a + 1 end, "a Vec3 can only be added to another Vec3")
            fails("divide by a vector", function() return a / a end, "a Vec3 can only be divided by a number")
            fails("build from a string", function() return vec3("1", 2, 3) end, "bad argument 'x' (number expected, got string)")
            """
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `vectors measure, turn and round`() {
        val result = LuaChecks.run(
            """
            local v = vec3(3, 4, 0)
            check("length", v:length(), 5)
            check("length_squared", v:length_squared(), 25)
            check("normalized", v:normalized(), vec3(0.6, 0.8, 0))
            check("zero stays zero", vec3.zero:normalized(), vec3(0, 0, 0))
            check("dot", vec3(1, 2, 3):dot(vec3(4, 5, 6)), 32)
            check("cross", vec3.east:cross(vec3.up), vec3(0, 0, 1))
            check("distance", vec3(1, 1, 1):distance(vec3(1, 4, 5)), 5)
            check("distance_squared", vec3(1, 1, 1):distance_squared(vec3(1, 4, 5)), 25)
            check("lerp", vec3(0, 0, 0):lerp(vec3(10, 20, 30), 0.5), vec3(5, 10, 15))
            check("flat", vec3(1, 2, 3):flat(), vec3(1, 0, 3))
            check("with_x", v:with_x(9), vec3(9, 4, 0))
            check("with_y", v:with_y(9), vec3(3, 9, 0))
            check("with_z", v:with_z(9), vec3(3, 4, 9))
            check("floor", vec3(1.5, -1.5, 2):floor(), vec3(1, -2, 2))
            check("round", vec3(1.5, -1.5, 2.4):round(), vec3(2, -1, 2))
            -- Right-handed: a quarter turn about up takes east to north, about east takes up to south.
            near("rotated about up", vec3.east:rotated(vec3.up, 90), vec3.north)
            near("rotated about east", vec3.up:rotated(vec3(2, 0, 0), 90), vec3.south)
            near("rotated about a slant", vec3(1, 0, 0):rotated(vec3(1, 1, 1), 120), vec3(0, 1, 0))
            fails("rotated about nothing", function() return v:rotated(vec3.zero, 90) end, "isn't zero")
            check("clamp_length scales a long one down", vec3(3, 0, 4):clamp_length(2.5), vec3(1.5, 0, 2))
            check("clamp_length leaves a short one", vec3(3, 0, 4):clamp_length(5), vec3(3, 0, 4))
            check("clamp_length to nothing", vec3(3, 0, 4):clamp_length(0), vec3(0, 0, 0))
            check("clamp_length of zero", vec3.zero:clamp_length(1), vec3.zero)
            fails("clamp_length below zero", function() return v:clamp_length(-1) end, "bad argument 'max_length' (a length can't be negative)")
            check("reflect off the floor", vec3(1, -2, 3):reflect(vec3.up), vec3(1, 2, 3))
            check("reflect off a wall, any length of normal", vec3(2, 1, 0):reflect(vec3(-5, 0, 0)), vec3(-2, 1, 0))
            near("reflect off a slant", vec3(1, 0, 0):reflect(vec3(-1, 1, 0)), vec3(0, 1, 0))
            check("reflect along the surface", vec3(1, 0, 0):reflect(vec3.up), vec3(1, 0, 0))
            fails("reflect off nothing", function() return v:reflect(vec3.zero) end, "bad argument 'normal' (a surface's normal can't be zero)")
            local a, b = vec3(0, 0, 0), vec3(10, 0, 0)
            check("closest_on_segment: its foot", vec3(4, 3, 0):closest_on_segment(a, b), vec3(4, 0, 0))
            check("closest_on_segment: before the start", vec3(-5, 2, 0):closest_on_segment(a, b), a)
            check("closest_on_segment: past the end", vec3(15, 0, 7):closest_on_segment(a, b), b)
            check("closest_on_segment: on it", vec3(2, 0, 0):closest_on_segment(a, b), vec3(2, 0, 0))
            check("closest_on_segment: a point for a segment", vec3(4, 3, 0):closest_on_segment(b, b), b)
            check("closest_on_segment: slanted", vec3(0, 2, 0):closest_on_segment(vec3(0, 0, 0), vec3(2, 2, 2)), vec3(2 / 3, 2 / 3, 2 / 3))
            fails("dot with a number", function() return v:dot(1) end, "bad argument 'other' (Vec3 expected, got number)")
            fails("method with a dot", function() return vec3.one.length(5) end, "call Vec3 methods with ':'")
            """
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `the vec3 table holds the common vectors and Minecraft's angles`() {
        val result = LuaChecks.run(
            """
            check("zero", vec3.zero, vec3(0, 0, 0))
            check("one", vec3.one, vec3(1, 1, 1))
            check("up", vec3.up, vec3(0, 1, 0))
            check("down", vec3.down, vec3(0, -1, 0))
            check("north", vec3.north, vec3(0, 0, -1))
            check("south", vec3.south, vec3(0, 0, 1))
            check("east", vec3.east, vec3(1, 0, 0))
            check("west", vec3.west, vec3(-1, 0, 0))
            near("yaw 0 faces south", vec3.from_yaw_pitch(0, 0), vec3.south)
            near("yaw 90 faces west", vec3.from_yaw_pitch(90, 0), vec3.west)
            near("yaw 180 faces north", vec3.from_yaw_pitch(180, 0), vec3.north)
            near("yaw -90 faces east", vec3.from_yaw_pitch(-90, 0), vec3.east)
            near("pitch -90 faces up", vec3.from_yaw_pitch(0, -90), vec3.up)
            near("pitch 45", vec3.from_yaw_pitch(0, 45), vec3(0, -1, 1):normalized())
            local keys = {}
            for key in pairs(vec3) do keys[#keys + 1] = key end
            table.sort(keys)
            log(table.concat(keys, ","))
            """
        )
        assertEquals(listOf("down,east,from_yaw_pitch,north,one,south,up,west,zero", "done"), result)
    }

    @Test
    fun `values can't be changed or faked`() {
        val result = LuaChecks.run(
            """
            local v = vec3(1, 2, 3)
            fails("assign a field", function() v.x = 5 end, "vectors can't be changed (setting 'x')")
            fails("add a field", function() v.w = 5 end, "vectors can't be changed (setting 'w')")
            check("unchanged", v, vec3(1, 2, 3))
            fails("change a constant", function() vec3.zero = vec3.one end, "vec3 is shared by every script")
            fails("change the table", function() vec3.extra = 1 end, "vec3 is shared by every script")
            -- A table that claims to be a vector isn't one.
            local fake = setmetatable({ 1, 2, 3 }, { __metatable = "Vec3" })
            fails("a fake vector", function() return nf.centities.all({ near = fake, radius = 1 }) end, "bad argument 'filter.near' (Vec3 or nil expected, got table)")
            fails("a fake as a place", function() nf.centities.spawn("c", fake) end, "bad argument 'location_or_position' (Location or Vec3 expected, got table)")
            local location = nf.players.get("Alex"):location()
            fails("assign a location field", function() location.world = "nether" end, "locations can't be changed (setting 'world')")
            """,
            setup = { server -> server.player("Alex") }
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `locations carry a world, a position and maybe a facing`() {
        val result = LuaChecks.run(
            """
            local alex = nf.players.get("Alex")
            local here = alex:location()
            check("world", here.world, nf.worlds.default())
            check("built from a world", nf.worlds.get("world"):location(vec3(1, 64, 2), 90, 0), here)
            check("a world location with no facing", nf.worlds.default():location(vec3(1, 2, 3)).yaw, nil)
            check("position", here.position, vec3(1, 64, 2))
            check("yaw", here.yaw, 90)
            check("pitch", here.pitch, 0)
            check("tostring", tostring(here), 'location("world", vec3(1, 64, 2), 90, 0)')
            near("direction", here:direction(), vec3.west)
            check("with_position", here:with_position(vec3(5, 6, 7)), here:offset(vec3(4, -58, 5)))
            check("offset keeps the facing", here:offset(vec3(0, 1, 0)).yaw, 90)
            check("equal", here == alex:location(), true)
            check("not equal elsewhere", here == here:offset(vec3.up), false)
            local crate = nf.centities.spawn("c", here)
            local spot = crate:location()
            check("a centity faces the yaw it was spawned with", spot.yaw, 90)
            check("but has no pitch", spot.pitch, nil)
            near("so it looks level", spot:direction(), vec3.west)
            check("tostring with a facing", tostring(spot), 'location("world", vec3(1, 64, 2), 90, 0)')
            check("centity world", crate:world(), nf.worlds.default())
            check("centity position", crate:position(), vec3(1, 64, 2))
            check("teleport to a position", crate:teleport(vec3(10, 70, 10)), true)
            check("moved", crate:position(), vec3(10, 70, 10))
            check("teleport to a location", crate:teleport(here), true)
            check("back", crate:location(), spot)
            crate:remove()
            check("gone", crate:location(), nil)
            check("gone position", crate:position(), nil)
            """,
            setup = { server ->
                server.write("centities/c/centity.json", TestServer.scriptedCentity())
                server.write("centities/c/script.lua", "-- nothing")
                server.player("Alex").location = Location("world", 1.0, 64.0, 2.0, yaw = 90.0, pitch = 0.0)
            }
        )
        assertEquals(listOf("done"), result)
    }
}
