package dev.netherforge.plugin.api

import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.MannequinApi
import dev.netherforge.plugin.binding.Skin
import dev.netherforge.plugin.binding.SkinParts
import dev.netherforge.plugin.binding.StringOrSkin
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.MannequinLook
import dev.netherforge.plugin.platform.MannequinPose
import dev.netherforge.plugin.platform.MannequinSkin
import dev.netherforge.plugin.platform.SkinPart
import dev.netherforge.plugin.platform.SkinTexture
import dev.netherforge.plugin.session.ProjectSession

/**
 * `Mannequin`: what only a mannequin has, read from its look and written
 * back changed ([dev.netherforge.plugin.platform.MannequinOps]). What it
 * shares with any living entity is [LivingImpl]'s.
 */
internal class MannequinImpl(private val session: ProjectSession) : MannequinApi {
    private val mannequins get() = session.platform.mannequins

    private fun look(self: LuaHandle.Mannequin): MannequinLook? = self.uuidOrNull()?.let(mannequins::look)

    private fun change(self: LuaHandle.Mannequin, change: (MannequinLook) -> MannequinLook): Boolean {
        val id = self.uuidOrNull() ?: return false
        val look = mannequins.look(id) ?: return false
        return mannequins.setLook(id, change(look))
    }

    override fun skin(self: LuaHandle.Mannequin): StringOrSkin? = when (val skin = look(self)?.skin) {
        null, MannequinSkin.Default -> null
        is MannequinSkin.Player -> StringOrSkin.String(skin.name)
        is MannequinSkin.Texture -> StringOrSkin.Skin(Skin(skin.texture.value, skin.texture.signature))
    }

    override fun setSkin(self: LuaHandle.Mannequin, skin: StringOrSkin?): Boolean {
        val chosen = when (skin) {
            null -> MannequinSkin.Default
            is StringOrSkin.String -> {
                if (!PLAYER_NAME.matches(skin.value)) {
                    throw LuaApiException(
                        "bad argument 'skin' (a player's name, 3 to 16 letters, digits or _, expected, got \"${skin.value}\")"
                    )
                }
                MannequinSkin.Player(skin.value)
            }
            is StringOrSkin.Skin -> MannequinSkin.Texture(SkinTexture(skin.value.texture, skin.value.signature))
        }
        return change(self) { it.copy(skin = chosen) }
    }

    override fun description(self: LuaHandle.Mannequin): String? = look(self)?.description

    override fun setDescription(self: LuaHandle.Mannequin, text: String?): Boolean = change(self) { it.copy(description = text) }

    override fun isImmovable(self: LuaHandle.Mannequin): Boolean = look(self)?.immovable == true

    override fun setImmovable(self: LuaHandle.Mannequin, immovable: Boolean): Boolean = change(self) { it.copy(immovable = immovable) }

    override fun mainHand(self: LuaHandle.Mannequin): String? = look(self)?.let { if (it.leftHanded) "left" else "right" }

    override fun setMainHand(self: LuaHandle.Mannequin, side: String): Boolean = change(self) { it.copy(leftHanded = side == "left") }

    override fun pose(self: LuaHandle.Mannequin): String? = look(self)?.pose?.luaName

    override fun setPose(self: LuaHandle.Mannequin, pose: String): Boolean = change(self) { it.copy(pose = MannequinPose.of(pose)) }

    override fun skinParts(self: LuaHandle.Mannequin): SkinParts? = look(self)?.skinParts?.let { shown ->
        SkinParts(
            cape = SkinPart.CAPE in shown,
            jacket = SkinPart.JACKET in shown,
            leftSleeve = SkinPart.LEFT_SLEEVE in shown,
            rightSleeve = SkinPart.RIGHT_SLEEVE in shown,
            leftPants = SkinPart.LEFT_PANTS in shown,
            rightPants = SkinPart.RIGHT_PANTS in shown,
            hat = SkinPart.HAT in shown
        )
    }

    override fun setSkinParts(self: LuaHandle.Mannequin, parts: SkinParts): Boolean = change(self) { look ->
        val given = mapOf(
            SkinPart.CAPE to parts.cape,
            SkinPart.JACKET to parts.jacket,
            SkinPart.LEFT_SLEEVE to parts.leftSleeve,
            SkinPart.RIGHT_SLEEVE to parts.rightSleeve,
            SkinPart.LEFT_PANTS to parts.leftPants,
            SkinPart.RIGHT_PANTS to parts.rightPants,
            SkinPart.HAT to parts.hat
        )
        look.copy(skinParts = SkinPart.entries.filterTo(LinkedHashSet()) { given[it] ?: (it in look.skinParts) })
    }

    private companion object {
        /** A player's name, as the server looks one up. */
        val PLAYER_NAME = Regex("[A-Za-z0-9_]{3,16}")
    }
}
