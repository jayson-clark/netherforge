package dev.netherforge.network

import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A server's connection to its network's hub: over TCP ([HubClient]), or to a hub in the same JVM ([LocalLink]). */
interface HubLink {
    /** Whether the hub has accepted this server and the connection is up. */
    val connected: Boolean

    /** Queues [frame] for the hub. False, and nothing queued, while not [connected] (or past the queue's limit). */
    fun send(frame: ServerFrame): Boolean

    fun start()

    fun stop()
}

/**
 * What a [HubLink] tells the server, from its own thread ([HubClient]'s reader) or, for a [LocalLink], from
 * whichever thread the hub is acting on: [received] may wait (for room in the server's queue) only on a [HubClient],
 * whose waiting stops it reading the socket.
 */
interface HubListener {
    /** The hub accepted the hello: the network as it is now. Every frame after it is a change to it. */
    fun connected(welcome: HubFrame.Welcome)

    fun received(frame: HubFrame)

    /** A connection the hub had accepted ended; it's dialled again. */
    fun disconnected(why: String)

    /** The hub refused the hello ([why]), or couldn't be reached; it's tried again, less often. */
    fun failed(why: String)
}

/**
 * The plugin's end of a TCP connection to the hub: a thread that connects, answers the challenge, and reads; a
 * writer that drains what [send] queued and pings when there's nothing to say. A dropped or refused connection is
 * dialled again after a pause that doubles from [MIN_BACKOFF_MILLIS] to [MAX_BACKOFF_MILLIS], until [stop]. What's
 * queued when a connection drops is thrown away: a message for the network as it was then isn't worth sending later.
 */
class HubClient(
    private val host: String,
    private val port: Int,
    private val server: String,
    private val secret: String,
    private val listener: HubListener,
    private val maxQueued: Int = 50_000
) : HubLink {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var accepted = false
    private val queue = LinkedBlockingQueue<String>(maxQueued)

    override val connected get() = accepted

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread(::dial, "NetherForge network").apply {
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        running.set(false)
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        thread?.interrupt()
        thread?.join(STOP_WAIT_MILLIS)
        thread = null
    }

    override fun send(frame: ServerFrame): Boolean = accepted && queue.offer(Protocol.encode(frame))

    private fun dial() {
        var backoff = MIN_BACKOFF_MILLIS
        while (running.get()) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
                    socket.tcpNoDelay = true
                    this.socket = socket
                    if (session(socket)) backoff = MIN_BACKOFF_MILLIS
                }
            } catch (e: IOException) {
                if (running.get()) listener.failed("the network's hub at $host:$port couldn't be reached (${e.message})")
            } finally {
                socket = null
                accepted = false
                queue.clear()
            }
            if (!running.get()) break
            try {
                Thread.sleep(backoff)
            } catch (_: InterruptedException) {
                break
            }
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
        }
    }

    /** One connection, from the challenge to its end. True when the hub accepted it. */
    private fun session(socket: Socket): Boolean {
        socket.soTimeout = Protocol.HELLO_MILLIS.toInt()
        val reader = LineReader(socket.getInputStream(), Protocol.MAX_FRAME_BEFORE_WELCOME)
        val out = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        val challenge = Protocol.decodeHubFrame(reader.readLine() ?: return false) as? HubFrame.Challenge
            ?: return false.also { listener.failed("$host:$port didn't speak like a NetherForge hub") }
        if (challenge.version != Protocol.VERSION) {
            listener.failed(
                "the hub speaks the network's protocol version ${challenge.version}, and this server ${Protocol.VERSION}: " +
                    "update NetherForge on the ${if (challenge.version < Protocol.VERSION) "proxy" else "server"}"
            )
            return false
        }
        out.write(Protocol.encode(ServerFrame.Hello(Protocol.VERSION, server, Auth.proof(secret, challenge.nonce, server))))
        out.write("\n")
        out.flush()
        reader.limit = Protocol.MAX_FRAME
        val welcome = when (val answer = reader.readLine()?.let(Protocol::decodeHubFrame)) {
            is HubFrame.Welcome -> answer
            is HubFrame.Refused -> return false.also { listener.failed("the network's hub refused this server: ${answer.reason}") }
            else -> return false.also { listener.failed("the network's hub closed the connection before answering") }
        }
        socket.soTimeout = Protocol.IDLE_MILLIS.toInt()
        queue.clear()
        accepted = true
        val writer = Thread({ drain(socket, out) }, "NetherForge network writer").apply {
            isDaemon = true
            start()
        }
        var why = "the hub closed the connection"
        try {
            listener.connected(welcome)
            while (running.get()) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                Protocol.decodeHubFrame(line)?.let(listener::received)
            }
        } catch (_: SocketTimeoutException) {
            why = "the hub was silent for ${Protocol.IDLE_MILLIS / 1000} seconds"
        } catch (e: LineTooLongException) {
            why = "the hub sent ${e.message}"
        } catch (e: IOException) {
            why = e.message ?: why
        } catch (_: InterruptedException) {
            why = "the server is stopping"
        } finally {
            accepted = false
            socket.close()
            writer.interrupt()
            listener.disconnected(why)
        }
        return true
    }

    private fun drain(socket: Socket, out: BufferedWriter) {
        try {
            while (!socket.isClosed) {
                val first = queue.poll(Protocol.PING_MILLIS, TimeUnit.MILLISECONDS) ?: Protocol.encode(ServerFrame.Ping)
                out.write(first)
                out.write("\n")
                while (true) {
                    val next = queue.poll() ?: break
                    out.write(next)
                    out.write("\n")
                }
                out.flush()
            }
        } catch (_: InterruptedException) {
        } catch (_: IOException) {
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }

    private companion object {
        const val MIN_BACKOFF_MILLIS = 500L
        const val MAX_BACKOFF_MILLIS = 10_000L
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val STOP_WAIT_MILLIS = 2_000L
    }
}

/**
 * A server connected to a [Hub] in the same JVM: what a script test run's network is (the hub is real, the other
 * servers are the test's), and what the runtime's own tests connect servers with. Everything happens on the calling
 * thread: [send] is the hub receiving, and the hub's frames reach [listener] as it sends them.
 */
class LocalLink(private val hub: Hub, private val server: String, private val secret: String, private val listener: HubListener) : HubLink {
    private var session: Hub.Session? = null
    private var nonce: String? = null

    @Volatile
    private var accepted = false

    override val connected get() = accepted

    private val peer = object : Peer {
        override val address = "this JVM"

        override fun send(frame: HubFrame) {
            when (frame) {
                is HubFrame.Challenge -> nonce = frame.nonce
                is HubFrame.Welcome -> {
                    accepted = true
                    listener.connected(frame)
                }
                is HubFrame.Refused -> listener.failed("the network's hub refused this server: ${frame.reason}")
                else -> if (accepted) listener.received(frame)
            }
        }

        override fun close(reason: String) {
            val was = accepted
            accepted = false
            session?.closed()
            session = null
            if (was) listener.disconnected(reason)
        }
    }

    override fun start() {
        if (session != null) return
        val session = hub.open(peer)
        this.session = session
        session.receive(ServerFrame.Hello(Protocol.VERSION, server, Auth.proof(secret, nonce!!, server)))
    }

    override fun stop() = peer.close("the server is stopping")

    override fun send(frame: ServerFrame): Boolean {
        if (!accepted) return false
        session?.receive(frame) ?: return false
        return true
    }
}
