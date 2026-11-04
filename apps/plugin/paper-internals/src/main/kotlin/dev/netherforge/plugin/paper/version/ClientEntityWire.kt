package dev.netherforge.plugin.paper.version

import dev.netherforge.plugin.paper.ClientEntityPackets
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket
import org.bukkit.craftbukkit.entity.CraftEntity
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import net.minecraft.world.entity.Entity as MojangEntity

/**
 * [ClientEntityPackets] from the server's own packets, made from the entity `World.createEntity` made: it has a
 * network id and synced data like any other, it just never joined a level, so nothing but these packets tells a
 * client of it.
 */
internal object ClientEntityWire : ClientEntityPackets {
    private fun mojang(entity: Entity): MojangEntity = (entity as CraftEntity).handle

    override fun place(entity: Entity, x: Double, y: Double, z: Double) {
        mojang(entity).setPos(x, y, z)
    }

    override fun spawn(entity: Entity): List<Any> {
        val it = mojang(entity)
        val add = ClientboundAddEntityPacket(
            it.id,
            it.uuid,
            it.x,
            it.y,
            it.z,
            it.xRot,
            it.yRot,
            it.type,
            0,
            it.deltaMovement,
            it.yHeadRot.toDouble()
        )
        // Sent in full, so what was dirty is in it: the next changes are only what changes after.
        it.entityData.packDirty()
        val data = it.entityData.nonDefaultValues
        return if (data == null) listOf(add) else listOf(add, ClientboundSetEntityDataPacket(it.id, data))
    }

    override fun changes(entity: Entity): Any? {
        val it = mojang(entity)
        return it.entityData.packDirty()?.let { values -> ClientboundSetEntityDataPacket(it.id, values) }
    }

    override fun position(entity: Entity): Any = ClientboundEntityPositionSyncPacket.of(mojang(entity))

    /** The packet's own constructor reads a vehicle's riders from the level; this one is read from the ids, as the client would. */
    override fun riders(vehicle: Int, riders: IntArray): Any {
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            buffer.writeVarInt(vehicle)
            buffer.writeVarIntArray(riders)
            return ClientboundSetPassengersPacket.STREAM_CODEC.decode(buffer)
        } finally {
            buffer.release()
        }
    }

    override fun remove(ids: IntArray): Any = ClientboundRemoveEntitiesPacket(*ids)

    @Suppress("UNCHECKED_CAST")
    override fun send(player: Player, packets: List<Any>) {
        if (packets.isEmpty()) return
        val connection = (player as CraftPlayer).handle.connection
        val game = packets.map { it as Packet<in ClientGamePacketListener> }
        connection.send(if (game.size == 1) game.single() else ClientboundBundlePacket(game))
    }
}
