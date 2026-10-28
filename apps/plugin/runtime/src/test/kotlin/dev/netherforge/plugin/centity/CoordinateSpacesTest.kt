package dev.netherforge.plugin.centity
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The coordinate spaces scripts convert between:
 * world, the centity's own (anchor origin, world axes), and each node's.
 * Checked against a rig whose transforms are easy to work out by hand.
 */
class CoordinateSpacesTest {
    /**
     * A rig anchored at (10, 64, 20): `base` one block east of the anchor and
     * turned a quarter about up (so its +Z points east, its +X north); `arm`
     * two blocks along base's +Z and scaled 2; `tip` one block up arm's Y.
     */
    private val rig = """
        {
          "nodes": {
            "base": { "transform": { "translation": [1, 0, 0], "rotation": [0, 90, 0] } },
            "arm": { "parent": "base", "transform": { "translation": [0, 0, 2], "scale": [2, 2, 2] } },
            "tip": { "parent": "arm", "transform": { "translation": [0, 1, 0] } }
          },
          "animations": {
            "lift": { "tracks": { "base": { "translation": [{ "time": 0, "value": [1, 5, 0] }, { "time": 1, "value": [1, 5, 0] }] } } }
          }
        }
    """

    private val crate = """
        {
          "nodes": {
            "box": {
              "transform": { "translation": [0, 3, 0] },
              "display": { "type": "block", "block": "minecraft:stone" },
              "hitbox": {},
              "physics": {}
            }
          }
        }
    """

    private fun run(body: String): List<String> {
        val script = """
            ${LuaChecks.HELPERS}
            nf.commands.register("run", function()
            $body
            log("done")
            end)
        """.trimIndent()
        TestServer(
            mapOf(
                "centities/rig/centity.json" to rig,
                "centities/crate/centity.json" to crate,
                "modules/t/init.lua" to script
            )
        ).use { server ->
            server.platform.commands.runConsole("run")
            return server.output()
        }
    }

    @Test
    fun `nodes convert between their own space and the world`() {
        val result = run(
            """
            local rig = nf.centities.spawn("rig", vec3(10, 64, 20))
            local base, arm, tip = rig:node("base"), rig:node("arm"), rig:node("tip")
            near("base translation", base:translation(), vec3(1, 0, 0))
            near("base world_position", base:world_position(), vec3(11, 64, 20))
            near("arm world_position", arm:world_position(), vec3(13, 64, 20))
            near("tip world_position, through arm's scale", tip:world_position(), vec3(13, 66, 20))
            near("arm world_rotation", arm:world_rotation(), vec3(0, 90, 0))
            near("arm to_world: its +X is north, scaled", arm:to_world(vec3(1, 0, 0)), vec3(13, 64, 18))
            near("arm to_local undoes it", arm:to_local(vec3(13, 64, 18)), vec3(1, 0, 0))
            near("arm world_direction: turned, not scaled", arm:world_direction(vec3.south), vec3.east)
            near("centity to_local", rig:to_local(vec3(11, 64, 20)), vec3(1, 0, 0))
            near("centity to_world", rig:to_world(vec3(1, 0, 0)), vec3(11, 64, 20))

            -- As shown now: a playing clip moves what the conversions see.
            rig:play_animation("lift")
            near("lifted", tip:world_position(), vec3(13, 71, 20))
            rig:stop_animation()
            near("back down", tip:world_position(), vec3(13, 66, 20))

            arm:set_scale(vec3(0, 1, 1))
            if arm:to_local(vec3(0, 0, 0)) ~= nil then log("FAIL to_local through a flattened node") end
            arm:set_scale(vec3(2, 2, 2))
            """
        )
        assertEquals(listOf("done"), result)
    }

    /**
     * The same rig turned to yaw 90 (facing west): the centity's +Z is west
     * (−x), its +X south (+z). By hand: base sits one block south of the
     * anchor; its own quarter turn and the centity's cancel, so base and arm
     * face the world's way, arm two blocks south of base, tip two up.
     */
    @Test
    fun `a centity's yaw turns its space and every conversion through it`() {
        val result = run(
            """
            -- A location facing a yaw, at (10, 64, 20): scripts can't build one yet (phase 11), so borrow a centity's.
            local function rig_location(yaw)
              local helper = nf.centities.spawn("rig", vec3(10, 64, 20))
              helper:set_yaw(yaw)
              local l = helper:location()
              helper:remove()
              return l
            end
            local rig = nf.centities.spawn("rig", rig_location(90))
            local base, arm, tip = rig:node("base"), rig:node("arm"), rig:node("tip")
            if rig:yaw() ~= 90 then log("FAIL yaw " .. tostring(rig:yaw())) end
            local l = rig:location()
            if l.yaw ~= 90 or l.pitch ~= nil then log("FAIL location facing " .. tostring(l.yaw) .. " " .. tostring(l.pitch)) end
            near("translation is unchanged, in the centity's space", base:translation(), vec3(1, 0, 0))
            near("base world_position", base:world_position(), vec3(10, 64, 21))
            near("arm world_position", arm:world_position(), vec3(10, 64, 23))
            near("tip world_position", tip:world_position(), vec3(10, 66, 23))
            near("arm world_rotation: the two turns cancel", arm:world_rotation(), vec3(0, 0, 0))
            near("arm to_world", arm:to_world(vec3(1, 0, 0)), vec3(12, 64, 23))
            near("arm to_local", arm:to_local(vec3(12, 64, 23)), vec3(1, 0, 0))
            near("arm world_direction", arm:world_direction(vec3.south), vec3.south)
            near("centity to_world: its +Z is west", rig:to_world(vec3(0, 0, 1)), vec3(9, 64, 20))
            near("centity to_world: its +X is south", rig:to_world(vec3(1, 0, 0)), vec3(10, 64, 21))
            near("centity to_local", rig:to_local(vec3(9, 65, 20)), vec3(0, 1, 1))

            -- A node's look_at and rotate work in its parent's space, which for a root is turned.
            local target = vec3(20, 70, 25)
            assert(arm:look_at(target))
            near("arm faces the point", arm:world_direction(vec3.south), (target - arm:world_position()):normalized())
            assert(base:look_at(vec3(10, 64, 30)))
            near("base faces south", base:world_direction(vec3.south), vec3.south)
            assert(base:rotate(vec3.up, 90))
            near("rotate about the centity's up", base:world_direction(vec3.south), vec3.east)

            -- Turning: set_yaw wraps, look_at turns about the vertical only.
            rig:set_yaw(270)
            if rig:yaw() ~= -90 then log("FAIL wrapped yaw " .. tostring(rig:yaw())) end
            near("facing east, its +Z is east", rig:to_world(vec3(0, 0, 1)), vec3(11, 64, 20))
            assert(rig:look_at(vec3(10, 0, 40)))
            if rig:yaw() ~= 0 then log("FAIL looking south " .. tostring(rig:yaw())) end
            assert(rig:look_at(vec3(0, 100, 20)))
            if math.abs(rig:yaw() - 90) > 1e-9 then log("FAIL looking west " .. tostring(rig:yaw())) end
            assert(rig:look_at(vec3(10, 80, 20)))
            if math.abs(rig:yaw() - 90) > 1e-9 then log("FAIL straight above keeps the yaw " .. tostring(rig:yaw())) end

            -- Teleport takes a location's facing, and a bare position keeps the centity's.
            assert(rig:teleport(vec3(0, 64, 0)))
            if math.abs(rig:yaw() - 90) > 1e-9 then log("FAIL a position keeps the yaw " .. tostring(rig:yaw())) end
            assert(rig:teleport(rig_location(180)))
            if rig:yaw() ~= -180 then log("FAIL teleported facing " .. tostring(rig:yaw())) end
            near("and stands there", rig:position(), vec3(10, 64, 20))

            local plain = nf.centities.spawn("rig", vec3(10, 64, 20))
            if plain:yaw() ~= 0 then log("FAIL spawned at a position faces south") end

            rig:remove()
            if rig:yaw() ~= nil or rig:look_at(target) ~= false then log("FAIL a removed centity's yaw") end
            rig:set_yaw(10)
            """
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `look_at and rotate turn a node in its parent's space`() {
        val result = run(
            """
            local rig = nf.centities.spawn("rig", vec3(10, 64, 20))
            local base, arm = rig:node("base"), rig:node("arm")
            -- arm's parent is turned, so the rotation look_at sets isn't the world one.
            local target = vec3(20, 70, 25)
            assert(arm:look_at(target))
            near("arm faces the point", arm:world_direction(vec3.south), (target - arm:world_position()):normalized())
            near("with its X level", vec3(0, arm:world_direction(vec3.east).y, 0), vec3.zero)
            assert(arm:look_at(arm:world_position() + vec3(0, 3, 0)))
            near("straight up", arm:world_direction(vec3.south), vec3.up)
            assert(arm:look_at(arm:world_position()))

            assert(base:rotate(vec3.up, -90))
            near("rotate undoes the quarter turn", base:rotation(), vec3.zero)
            assert(base:rotate(vec3(5, 0, 0), 90))
            near("rotate about east", base:rotation(), vec3(90, 0, 0))
            assert(base:rotate(vec3.up, 90))
            near("then about the parent's up", base:world_direction(vec3.up), vec3.east)
            local ok, err = pcall(base.rotate, base, vec3.zero, 10)
            if ok or not err:find("isn't zero", 1, true) then log("FAIL rotate about nothing: " .. tostring(err)) end

            rig:remove()
            if base:look_at(target) ~= false or base:rotate(vec3.up, 1) ~= false or base:world_position() ~= nil then
              log("FAIL a removed centity's nodes")
            end
            """
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `impulses land at world points`() {
        val result = run(
            """
            local function shove(at)
              local crate = nf.centities.spawn("crate", vec3(100, 64, 200))
              local box = crate:node("box")
              -- The unit cube's centre, as a world position.
              local centre = box:to_world(vec3(0.5, 0.5, 0.5))
              assert(box:apply_impulse_at(vec3(0, 0, 4), centre + at))
              local result = { box:velocity(), box:angular_velocity() }
              crate:remove()
              return result
            end
            local through = shove(vec3.zero)
            near("through the centre it only slides", through[1], vec3(0, 0, 4))
            near("without turning", through[2], vec3.zero)
            local above = shove(vec3(0, 0.5, 0))
            near("above the centre it slides as much", above[1], vec3(0, 0, 4))
            if not (above[2].x > 10 and math.abs(above[2].y) < 1e-6 and math.abs(above[2].z) < 1e-6) then
              log("FAIL above the centre it tips forward about x: " .. tostring(above[2]))
            end
            local crate = nf.centities.spawn("crate", vec3(100, 64, 200))
            assert(crate:node("box"):apply_impulse(vec3(0, 0, 4)))
            near("apply_impulse is world space too", crate:node("box"):velocity(), vec3(0, 0, 4))
            """
        )
        assertEquals(listOf("done"), result)
    }
}
