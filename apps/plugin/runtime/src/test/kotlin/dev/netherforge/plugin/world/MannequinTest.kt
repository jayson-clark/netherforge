package dev.netherforge.plugin.world
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.platform.MannequinPose
import dev.netherforge.plugin.platform.MannequinSkin
import dev.netherforge.plugin.platform.SkinPart
import kotlin.test.Test
import kotlin.test.assertEquals

/** `Mannequin` from a script on the fake: spawned as one, and everything it has set and read back. */
class MannequinTest {
    @Test
    fun `a mannequin spawns as one, and what a script sets it reads back`() {
        val output = LuaChecks.run(
            """
            local world = nf.worlds.default()
            local guide = world:spawn_entity("minecraft:mannequin", vec3(0.5, 64, 0.5))
            check("a mannequin", getmetatable(guide) == getmetatable(world:spawn_entity("minecraft:mannequin", vec3(2, 64, 2))), true)
            check("living", guide:is_living(), true)
            check("default skin", guide:skin(), nil)
            check("a player's skin", guide:set_skin("Notch"), true)
            check("read back", guide:skin(), "Notch")
            check("a signed skin", guide:set_skin({ texture = "dGV4dHVyZQ==", signature = "c2ln" }), true)
            check("its texture", guide:skin().texture, "dGV4dHVyZQ==")
            check("description", guide:set_description("<gold>Shopkeeper"), true)
            check("description read", guide:description(), "<gold>Shopkeeper")
            check("none", guide:set_description(), true)
            check("none read", guide:description(), nil)
            check("immovable", guide:set_immovable(true), true)
            check("immovable read", guide:is_immovable(), true)
            check("hand", guide:set_main_hand("left"), true)
            check("hand read", guide:main_hand(), "left")
            check("pose", guide:set_pose("crouching"), true)
            check("pose read", guide:pose(), "crouching")
            check("parts", guide:set_skin_parts({ cape = false, hat = false }), true)
            local parts = guide:skin_parts()
            check("cape", parts.cape, false)
            check("hat", parts.hat, false)
            check("jacket kept", parts.jacket, true)
            fails("a name", function() guide:set_skin("not a name") end, "a player's name")
            fails("a pose", function() guide:set_pose("flying") end, "pose")
            guide:remove()
            check("gone", guide:set_pose("standing"), false)
            check("gone pose", guide:pose(), nil)
            """
        ) { server ->
            val mannequin = server.platform.worldEntities.mobs.values.single { it.kind == "minecraft:mannequin" }
            val look = mannequin.mannequin!!
            assertEquals(MannequinSkin.Default, look.skin)
            assertEquals(MannequinPose.STANDING, look.pose)
            assertEquals(SkinPart.entries.toSet(), look.skinParts)
        }
        assertEquals(LuaChecks.DONE, output)
    }
}
