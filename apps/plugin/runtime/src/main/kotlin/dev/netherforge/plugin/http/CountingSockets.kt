package dev.netherforge.plugin.http

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.nio.channels.SocketChannel
import javax.net.SocketFactory

/** What a reader throws once a connection's package is over its byte limit (the sink has already ended it `"limit"`). */
class OverByteLimit : IOException("over the byte limit")

/**
 * Sockets whose every byte read is counted against a connection's package
 * ([Connection.Sink.received]) before OkHttp sees it, so a far end can't make
 * the server buffer more than the owner's `bytes-per-minute`, whatever it
 * sends (one endless WebSocket frame included: OkHttp has no message limit of
 * its own). Counted on the raw socket, under TLS: bytes on the wire.
 *
 * OkHttp only ever asks for an unconnected socket, which it connects itself.
 */
class CountingSocketFactory(private val base: SocketFactory, private val sink: Connection.Sink) : SocketFactory() {
    override fun createSocket(): Socket = CountedSocket(base.createSocket(), sink)

    override fun createSocket(host: String?, port: Int): Socket = unsupported()

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = unsupported()

    override fun createSocket(host: InetAddress?, port: Int): Socket = unsupported()

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("connections make unconnected sockets only")
}

/** [inner], its input counted. Everything else is [inner]'s. */
private class CountedSocket(private val inner: Socket, private val sink: Connection.Sink) : Socket() {
    private val input by lazy { Counted(inner.getInputStream(), sink) }

    override fun connect(endpoint: SocketAddress?) = inner.connect(endpoint)

    override fun connect(endpoint: SocketAddress?, timeout: Int) = inner.connect(endpoint, timeout)

    override fun bind(bindpoint: SocketAddress?) = inner.bind(bindpoint)

    override fun getInetAddress(): InetAddress? = inner.inetAddress

    override fun getLocalAddress(): InetAddress? = inner.localAddress

    override fun getPort() = inner.port

    override fun getLocalPort() = inner.localPort

    override fun getRemoteSocketAddress(): SocketAddress? = inner.remoteSocketAddress

    override fun getLocalSocketAddress(): SocketAddress? = inner.localSocketAddress

    override fun getChannel(): SocketChannel? = null

    override fun getInputStream(): InputStream = input

    override fun getOutputStream(): OutputStream = inner.getOutputStream()

    override fun setTcpNoDelay(on: Boolean) {
        inner.tcpNoDelay = on
    }

    override fun getTcpNoDelay() = inner.tcpNoDelay

    override fun setSoLinger(on: Boolean, linger: Int) = inner.setSoLinger(on, linger)

    override fun getSoLinger() = inner.soLinger

    override fun sendUrgentData(data: Int) = inner.sendUrgentData(data)

    override fun setOOBInline(on: Boolean) {
        inner.oobInline = on
    }

    override fun getOOBInline() = inner.oobInline

    override fun setSoTimeout(timeout: Int) {
        inner.soTimeout = timeout
    }

    override fun getSoTimeout() = inner.soTimeout

    override fun setSendBufferSize(size: Int) {
        inner.sendBufferSize = size
    }

    override fun getSendBufferSize() = inner.sendBufferSize

    override fun setReceiveBufferSize(size: Int) {
        inner.receiveBufferSize = size
    }

    override fun getReceiveBufferSize() = inner.receiveBufferSize

    override fun setKeepAlive(on: Boolean) {
        inner.keepAlive = on
    }

    override fun getKeepAlive() = inner.keepAlive

    override fun setTrafficClass(tc: Int) {
        inner.trafficClass = tc
    }

    override fun getTrafficClass() = inner.trafficClass

    override fun setReuseAddress(on: Boolean) {
        inner.reuseAddress = on
    }

    override fun getReuseAddress() = inner.reuseAddress

    override fun close() = inner.close()

    override fun shutdownInput() = inner.shutdownInput()

    override fun shutdownOutput() = inner.shutdownOutput()

    override fun toString() = inner.toString()

    override fun isConnected() = inner.isConnected

    override fun isBound() = inner.isBound

    override fun isClosed() = inner.isClosed

    override fun isInputShutdown() = inner.isInputShutdown

    override fun isOutputShutdown() = inner.isOutputShutdown
}

private class Counted(input: InputStream, private val sink: Connection.Sink) : FilterInputStream(input) {
    override fun read(): Int {
        val b = super.read()
        if (b >= 0) count(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) count(n)
        return n
    }

    private fun count(n: Int) {
        if (!sink.received(n.toLong())) throw OverByteLimit()
    }
}
