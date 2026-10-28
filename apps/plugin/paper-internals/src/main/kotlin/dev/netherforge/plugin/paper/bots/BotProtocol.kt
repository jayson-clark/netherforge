package dev.netherforge.plugin.paper.bots

import it.unimi.dsi.fastutil.ints.IntList
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket
import net.minecraft.network.protocol.game.ServerGamePacketListener
import net.minecraft.server.dialog.body.ItemBody
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3

/**
 * What the bots say differently on each Minecraft version: packets that were
 * added, split or reshaped, and classes that became records. The bots
 * (`apps/plugin/paper-internals/src/bots`) are written once against this;
 * each adapter's `Protocol` object (`apps/plugin/paper-<minecraft>/src/bots`)
 * answers it for its own server, compiled against it. A new version's
 * adapter starts from the closest one's and changes what its server changed
 * (see the minecraft-versions skill).
 */
internal interface BotProtocol {
    /** The key the client keeps a chunk under: the chunk [pos] is in. */
    fun chunk(pos: BlockPos): Long

    /** The key of the chunk [packet] brings. */
    fun chunk(packet: ClientboundLevelChunkWithLightPacket): Long

    /** The key of the chunk [packet] takes away. */
    fun chunk(packet: ClientboundForgetLevelChunkPacket): Long

    /** The entity whose motion [packet] sets, and the motion. */
    fun motion(packet: ClientboundSetEntityMotionPacket): Pair<Int, Vec3>

    /** A chat message's text as the server decorated it, when it isn't the signed body's. */
    fun unsignedContent(packet: ClientboundPlayerChatPacket): Component?

    /** The entities [packet] removes. */
    fun removed(packet: ClientboundRemoveEntitiesPacket): IntList

    /** The answer to teleport [id], which put the client at [at]. */
    fun acceptTeleport(id: Int, at: PositionMoveRotation): Packet<ServerGamePacketListener>

    /** What the client sends to use [hand] on [target] at [offset] from its position: every packet, in order. */
    fun interact(target: Entity, hand: InteractionHand, offset: Vec3, sneaking: Boolean): List<Packet<ServerGamePacketListener>>

    /** What the client sends to attack [target]. */
    fun attack(target: Entity, sneaking: Boolean): Packet<ServerGamePacketListener>

    /** What the client sends when the main hand swings (an attack, or a click at nothing). */
    fun swing(): Packet<ServerGamePacketListener>

    /** A click in container [containerId], as the client sends it (nothing it predicted changed). */
    fun click(containerId: Int, stateId: Int, slot: Int, button: Int, kind: ClickKind): Packet<ServerGamePacketListener>

    /** What the sign editor's Done sends: the four [lines] of the sign at [pos], its [front] or back. */
    fun signUpdate(pos: BlockPos, front: Boolean, lines: List<String>): Packet<ServerGamePacketListener>

    /** The item a dialog's item body shows. */
    fun item(body: ItemBody): ItemStack

    /** A team's look, from the parameters the server sends. */
    fun teamLook(parameters: ClientboundSetPlayerTeamPacket.Parameters): TeamLook

    /** The speed particles [packet] sends move at, and whether it's forced past the client's particle setting and range. */
    fun particleMotion(packet: ClientboundLevelParticlesPacket): Pair<Float, Boolean>
}

/** The kinds of container click, which each version's server names its own way. */
internal enum class ClickKind { PICKUP, QUICK_MOVE, SWAP, CLONE, THROW, QUICK_CRAFT, PICKUP_ALL }

/** A team as the client draws it: [color] and [nameTags]/[collision] by their serialized names, [options] as the packet's bits. */
internal data class TeamLook(
    val displayName: Component,
    val prefix: Component,
    val suffix: Component,
    val color: String?,
    val options: Int,
    val nameTags: String,
    val collision: String
)
