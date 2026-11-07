package dev.netherforge.plugin.platform

import java.util.UUID

/**
 * Mannequins (`minecraft:mannequin`): figures that look like players. What
 * they share with any living entity (health, equipment, a custom name,
 * turning) is [WorldEntityOps]'; this is what only they have, read whole
 * and written back whole.
 */
interface MannequinOps {
    /** How the mannequin [id] looks now; null when it's gone, unloaded or not a mannequin. */
    fun look(id: UUID): MannequinLook?

    /** Makes the mannequin [id] look as [look] says, changing only what differs; false when it's gone. */
    fun setLook(id: UUID, look: MannequinLook): Boolean
}

/** Everything about how a mannequin looks and stands that a living entity doesn't have. */
data class MannequinLook(
    val skin: MannequinSkin = MannequinSkin.Default,
    /** The line under its name, as MiniMessage; null for none. A new mannequin shows the game's "NPC". */
    val description: String? = null,
    /** Pushed by nothing: players, mobs, water, explosions. */
    val immovable: Boolean = false,
    val leftHanded: Boolean = false,
    val pose: MannequinPose = MannequinPose.STANDING,
    /** The parts of its skin's outer layer it shows. */
    val skinParts: Set<SkinPart> = SkinPart.entries.toSet()
)

sealed interface MannequinSkin {
    /** The game's own default skin. */
    data object Default : MannequinSkin

    /** A player's skin by [name], which the server looks up. */
    data class Player(val name: String) : MannequinSkin

    /** A signed skin. */
    data class Texture(val texture: SkinTexture) : MannequinSkin
}

/** The poses a mannequin may stand in, as scripts name them. */
enum class MannequinPose(val luaName: String) {
    STANDING("standing"),
    CROUCHING("crouching"),
    SWIMMING("swimming"),
    FALL_FLYING("fall_flying"),
    SLEEPING("sleeping");

    companion object {
        fun of(luaName: String): MannequinPose = entries.first { it.luaName == luaName }
    }
}

/** A part of a skin's outer layer, as scripts name it. */
enum class SkinPart(val luaName: String) {
    CAPE("cape"),
    JACKET("jacket"),
    LEFT_SLEEVE("left_sleeve"),
    RIGHT_SLEEVE("right_sleeve"),
    LEFT_PANTS("left_pants"),
    RIGHT_PANTS("right_pants"),
    HAT("hat")
}
