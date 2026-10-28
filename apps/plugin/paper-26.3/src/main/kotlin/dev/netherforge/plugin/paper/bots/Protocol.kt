package dev.netherforge.plugin.paper.bots

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntList
import net.minecraft.core.BlockPos
import net.minecraft.network.HashedStack
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
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.network.protocol.game.ServerboundInteractPacket
import net.minecraft.network.protocol.game.ServerboundPunchPacket
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket
import net.minecraft.server.dialog.body.ItemBody
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.entity.SignTextSlot
import net.minecraft.world.phys.Vec3

/** [BotProtocol] on Minecraft 26.3. */
internal object Protocol : BotProtocol {
    override fun chunk(pos: BlockPos): Long = ChunkPos.pack(pos)

    override fun chunk(packet: ClientboundLevelChunkWithLightPacket): Long = ChunkPos.pack(packet.x(), packet.z())

    override fun chunk(packet: ClientboundForgetLevelChunkPacket): Long = packet.pos().pack()

    override fun motion(packet: ClientboundSetEntityMotionPacket): Pair<Int, Vec3> = packet.id() to packet.movement()

    override fun unsignedContent(packet: ClientboundPlayerChatPacket): Component? = packet.unsignedContent().orElse(null)

    override fun removed(packet: ClientboundRemoveEntitiesPacket): IntList = packet.entityIds()

    override fun acceptTeleport(id: Int, at: PositionMoveRotation): Packet<ServerGamePacketListener> =
        ServerboundAcceptTeleportationPacket(id, at.position().x, at.position().y, at.position().z, at.yRot(), at.xRot())

    // One packet per hand, always with the point it hit.
    override fun interact(target: Entity, hand: InteractionHand, offset: Vec3, sneaking: Boolean): List<Packet<ServerGamePacketListener>> =
        listOf(ServerboundInteractPacket(target.id, hand, offset, sneaking))

    override fun attack(target: Entity, sneaking: Boolean): Packet<ServerGamePacketListener> = ServerboundAttackPacket(target.id)

    override fun swing(): Packet<ServerGamePacketListener> = ServerboundPunchPacket()

    override fun click(containerId: Int, stateId: Int, slot: Int, button: Int, kind: ClickKind): Packet<ServerGamePacketListener> =
        ServerboundContainerClickPacket(
            containerId,
            stateId,
            slot.toShort(),
            button.toByte(),
            when (kind) {
                ClickKind.PICKUP -> ContainerInput.PICKUP
                ClickKind.QUICK_MOVE -> ContainerInput.QUICK_MOVE
                ClickKind.SWAP -> ContainerInput.SWAP
                ClickKind.CLONE -> ContainerInput.CLONE
                ClickKind.THROW -> ContainerInput.THROW
                ClickKind.QUICK_CRAFT -> ContainerInput.QUICK_CRAFT
                ClickKind.PICKUP_ALL -> ContainerInput.PICKUP_ALL
            },
            Int2ObjectOpenHashMap(),
            HashedStack.EMPTY
        )

    override fun signUpdate(pos: BlockPos, front: Boolean, lines: List<String>): Packet<ServerGamePacketListener> =
        ServerboundSignUpdatePacket(pos, lines, if (front) SignTextSlot.FRONT else SignTextSlot.BACK)

    override fun item(body: ItemBody): ItemStack = body.item().create()

    // A record since 26.3, its speed one per axis (the same for a spawn given one speed, as Bukkit's are).
    override fun particleMotion(packet: ClientboundLevelParticlesPacket) = packet.xMaxSpeed to packet.overrideLimiter

    override fun teamLook(parameters: ClientboundSetPlayerTeamPacket.Parameters) = TeamLook(
        displayName = parameters.displayName(),
        prefix = parameters.playerPrefix(),
        suffix = parameters.playerSuffix(),
        color = parameters.color().orElse(null)?.serializedName,
        options = parameters.options().toInt(),
        nameTags = parameters.nameTagVisibility().serializedName,
        collision = parameters.collisionRule().serializedName
    )
}
