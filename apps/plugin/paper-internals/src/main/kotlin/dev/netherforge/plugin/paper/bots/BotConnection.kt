package dev.netherforge.plugin.paper.bots

import io.netty.channel.ChannelFutureListener
import io.netty.channel.embedded.EmbeddedChannel
import net.minecraft.network.Connection
import net.minecraft.network.protocol.BundlePacket
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.network.ServerGamePacketListenerImpl
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress

/**
 * A bot's network connection: Minecraft's own [Connection], so the server
 * keeps it, ticks its listener and disconnects it as any player's, but over a
 * channel that goes nowhere. What the server sends is handed to [received]
 * (on whichever thread sent it) instead of being encoded; what the bot sends
 * goes straight to the listener (see [Bot]).
 */
internal class BotConnection(private val received: (Packet<*>) -> Unit) : Connection(PacketFlow.SERVERBOUND) {
    private val embedded = BotChannel()

    init {
        // What channelActive would set had a socket connected.
        channel = embedded
        address = embedded.remoteAddress()
        preparing = false
    }

    override fun send(packet: Packet<*>, listener: ChannelFutureListener?, flush: Boolean) {
        if (!isConnected) return
        // Not getPlayer(): null-marked as never null, but null before the player is in the world.
        val player = (packetListener as? ServerGamePacketListenerImpl)?.player
        packet.onPacketDispatch(player)
        receive(packet)
        val sent = embedded.newSucceededFuture()
        packet.onPacketDispatchFinish(player, sent)
        // A send's listener is how the server finishes what follows it (a kick closes the connection once its message is out).
        listener?.operationComplete(sent)
    }

    private fun receive(packet: Packet<*>) {
        if (packet is BundlePacket<*>) packet.subPackets().forEach(::receive) else received(packet)
    }

    /**
     * Drops what the server wrote to the channel itself: switching protocols
     * writes a task for the pipeline's codecs, which a real channel's
     * handlers consume and this one has none of.
     */
    fun drain() {
        embedded.releaseOutbound()
    }

    /** A channel with a loopback address, as Paper expects of a player's ([InetSocketAddress], not an embedded one). */
    private class BotChannel : EmbeddedChannel() {
        override fun remoteAddress0(): SocketAddress = ADDRESS

        override fun localAddress0(): SocketAddress = ADDRESS
    }

    private companion object {
        val ADDRESS = InetSocketAddress(InetAddress.getLoopbackAddress(), 0)
    }
}
