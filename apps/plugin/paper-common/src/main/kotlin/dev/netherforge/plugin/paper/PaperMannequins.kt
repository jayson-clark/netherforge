package dev.netherforge.plugin.paper

import com.destroystokyo.paper.SkinParts
import com.destroystokyo.paper.profile.ProfileProperty
import dev.netherforge.plugin.platform.MannequinLook
import dev.netherforge.plugin.platform.MannequinOps
import dev.netherforge.plugin.platform.MannequinPose
import dev.netherforge.plugin.platform.MannequinSkin
import dev.netherforge.plugin.platform.SkinPart
import dev.netherforge.plugin.platform.SkinTexture
import io.papermc.paper.datacomponent.item.ResolvableProfile
import org.bukkit.entity.Mannequin
import org.bukkit.entity.Pose
import org.bukkit.inventory.MainHand
import java.util.UUID

/** [MannequinOps] on Paper's `Mannequin`. */
class PaperMannequins(private val entities: PaperWorldEntities) : MannequinOps {
    private val mini = PaperText.mini

    private fun mannequin(id: UUID) = entities.entity(id) as? Mannequin

    override fun look(id: UUID): MannequinLook? {
        val mannequin = mannequin(id) ?: return null
        val parts = mannequin.skinParts
        return MannequinLook(
            skin = skinOf(mannequin.profile),
            description = mannequin.description?.let(mini::serialize),
            immovable = mannequin.isImmovable,
            leftHanded = mannequin.mainHand == MainHand.LEFT,
            pose = POSES.entries.firstOrNull { it.value == mannequin.pose }?.key ?: MannequinPose.STANDING,
            skinParts = buildSet {
                if (parts.hasCapeEnabled()) add(SkinPart.CAPE)
                if (parts.hasJacketEnabled()) add(SkinPart.JACKET)
                if (parts.hasLeftSleeveEnabled()) add(SkinPart.LEFT_SLEEVE)
                if (parts.hasRightSleeveEnabled()) add(SkinPart.RIGHT_SLEEVE)
                if (parts.hasLeftPantsEnabled()) add(SkinPart.LEFT_PANTS)
                if (parts.hasRightPantsEnabled()) add(SkinPart.RIGHT_PANTS)
                if (parts.hasHatsEnabled()) add(SkinPart.HAT)
            }
        )
    }

    override fun setLook(id: UUID, look: MannequinLook): Boolean {
        val mannequin = mannequin(id) ?: return false
        val now = look(id) ?: return false
        if (look.skin != now.skin) mannequin.profile = profileOf(look.skin)
        if (look.description != now.description) mannequin.description = look.description?.let(mini::deserialize)
        if (look.immovable != now.immovable) mannequin.isImmovable = look.immovable
        if (look.leftHanded != now.leftHanded) mannequin.setMainHand(if (look.leftHanded) MainHand.LEFT else MainHand.RIGHT)
        // Fixed: the game would otherwise put it back to standing at its next tick.
        if (look.pose != now.pose) mannequin.setPose(POSES.getValue(look.pose), true)
        if (look.skinParts != now.skinParts) {
            val parts = SkinParts.allParts().apply {
                setCapeEnabled(SkinPart.CAPE in look.skinParts)
                setJacketEnabled(SkinPart.JACKET in look.skinParts)
                setLeftSleeveEnabled(SkinPart.LEFT_SLEEVE in look.skinParts)
                setRightSleeveEnabled(SkinPart.RIGHT_SLEEVE in look.skinParts)
                setLeftPantsEnabled(SkinPart.LEFT_PANTS in look.skinParts)
                setRightPantsEnabled(SkinPart.RIGHT_PANTS in look.skinParts)
                setHatsEnabled(SkinPart.HAT in look.skinParts)
            }
            mannequin.setSkinParts(parts)
        }
        return true
    }

    private fun skinOf(profile: ResolvableProfile): MannequinSkin {
        profile.properties().firstOrNull { it.name == TEXTURES }?.let { return MannequinSkin.Texture(SkinTexture(it.value, it.signature)) }
        return profile.name()?.let(MannequinSkin::Player) ?: MannequinSkin.Default
    }

    private fun profileOf(skin: MannequinSkin): ResolvableProfile = when (skin) {
        MannequinSkin.Default -> Mannequin.defaultProfile()
        // Dynamic: the server looks the name's skin up, and every client draws what it found.
        is MannequinSkin.Player -> ResolvableProfile.resolvableProfile().name(skin.name).build()
        is MannequinSkin.Texture ->
            ResolvableProfile.resolvableProfile()
                .uuid(UUID(0, 0))
                .addProperty(ProfileProperty(TEXTURES, skin.texture.value, skin.texture.signature))
                .build()
    }

    private companion object {
        const val TEXTURES = "textures"

        val POSES = mapOf(
            MannequinPose.STANDING to Pose.STANDING,
            MannequinPose.CROUCHING to Pose.SNEAKING,
            MannequinPose.SWIMMING to Pose.SWIMMING,
            MannequinPose.FALL_FLYING to Pose.FALL_FLYING,
            MannequinPose.SLEEPING to Pose.SLEEPING
        )
    }
}
