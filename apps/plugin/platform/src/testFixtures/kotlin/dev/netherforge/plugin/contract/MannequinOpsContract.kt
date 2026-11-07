package dev.netherforge.plugin.contract

import dev.netherforge.plugin.platform.EntityCategory
import dev.netherforge.plugin.platform.MannequinOps
import dev.netherforge.plugin.platform.MannequinPose
import dev.netherforge.plugin.platform.MannequinSkin
import dev.netherforge.plugin.platform.SkinPart
import dev.netherforge.plugin.platform.SkinTexture
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [MannequinOps]: what only a mannequin has, read back as it was set. */
abstract class MannequinOpsContract : PlatformContract() {
    private val mannequins: MannequinOps get() = platform.mannequins

    @Test
    fun `a mannequin is living, of its own class, and starts as the game makes one`() {
        val mannequin = spawn("minecraft:mannequin", at(3))
        main {
            val info = assertNotNull(platform.worldEntities.info(mannequin))
            assertEquals(EntityCategory.MANNEQUIN, info.category)
            assertTrue(info.living)
            val look = assertNotNull(mannequins.look(mannequin))
            assertEquals(MannequinSkin.Default, look.skin)
            assertNotNull(look.description, "a new mannequin shows a line under its name")
            assertFalse(look.immovable)
            assertFalse(look.leftHanded)
            assertEquals(MannequinPose.STANDING, look.pose)
        }
    }

    @Test
    fun `what's set is read back, and only a mannequin has a look`() {
        val mannequin = spawn("minecraft:mannequin", at(3))
        val pig = spawn("minecraft:pig", at(5), ai = false)
        main {
            val changed = mannequins.look(mannequin)!!.copy(
                skin = MannequinSkin.Player("Notch"),
                description = "<gold>Shopkeeper",
                immovable = true,
                leftHanded = true,
                pose = MannequinPose.CROUCHING,
                skinParts = setOf(SkinPart.HAT, SkinPart.JACKET)
            )
            assertTrue(mannequins.setLook(mannequin, changed))
            assertEquals(changed, mannequins.look(mannequin))

            val texture = changed.copy(skin = MannequinSkin.Texture(SkinTexture("dGV4dHVyZQ==", "c2ln")), description = null)
            assertTrue(mannequins.setLook(mannequin, texture))
            assertEquals(texture, mannequins.look(mannequin))

            assertNull(mannequins.look(pig))
            assertFalse(mannequins.setLook(pig, texture))
            assertNull(mannequins.look(UUID.randomUUID()))
        }
    }
}
