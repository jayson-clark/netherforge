package dev.netherforge.network

import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [Hub] on a TCP port: a thread accepting connections, and for each a reader (frames into
 * [Hub.Session.receive]) and a writer (draining what the hub queued, so a slow server never holds the hub up),
 * plus a timer calling [Hub.tick] every second. A connection that sends nothing for [Protocol.IDLE_MILLIS] (servers
 * ping every [Protocol.PING_MILLIS]) or queues past [NetworkLimits.maxQueuedFrames] is dropped.
 *
 * Before its hello is accepted a connection is anyone's: each costs two threads, so at most [MAX_PENDING] wait at
 * once, [MAX_PENDING_PER_ADDRESS] from one address, each for [Protocol.HELLO_MILLIS] at most (its read timeout until
 * then). Past either, a new connection is closed as it's accepted, so a flood can't take the proxy's threads.
 */
class HubServer(
    private val hub: Hub,
    private val bind: InetSocketAddress,
    private val log: HubLog,
    private val limits: NetworkLimits = NetworkLimits()
) {
    private val running = AtomicBoolean(false)
    private var socket: ServerSocket? = null
    private var acceptor: Thread? = null
    private var timer: ScheduledExecutorService? = null
    private val connections = java.util.concurrent.ConcurrentHashMap.newKeySet<Connection>()

    /** The port it listens on, once [start]ed (the one asked for, or the one the system picked for port 0). */
    val port: Int get() = socket?.localPort ?: bind.port

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(bind)
        socket = server
        acceptor = Thread(::accept, "NetherForge hub").apply {
            isDaemon = true
            start()
        }
        timer =
            Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "NetherForge hub timer").apply { isDaemon = true } }
                .also { timer ->
                    timer.scheduleWithFixedDelay(
                        {
                            try {
                                hub.tick()
                            } catch (e: Exception) {
                                log.warn("NetherForge hub: its tick failed: $e")
                            }
                        },
                        1,
                        1,
                        TimeUnit.SECONDS
                    )
                }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        timer?.shutdownNow()
        hub.close()
        for (connection in connections.toList()) connection.close("the hub is stopping")
        acceptor?.join(STOP_WAIT_MILLIS)
    }

    private fun accept() {
        val server = socket ?: return
        while (running.get()) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                if (running.get()) log.warn("NetherForge hub: accepting a connection failed: ${e.message}")
                continue
            }
            val host = (client.remoteSocketAddress as? InetSocketAddress)?.address
            val pending = connections.filter { !it.welcomed }
            if (pending.size >= MAX_PENDING || pending.count { it.host == host } >= MAX_PENDING_PER_ADDRESS) {
                refusedSince++
                val now = System.nanoTime()
                if (now - lastRefusalLog >= REFUSAL_LOG_NANOS) {
                    lastRefusalLog = now
                    log.warn(
                        "NetherForge hub: refusing connections from ${host?.hostAddress ?: "an unknown address"} and others " +
                            "($refusedSince lately): too many are waiting to say hello"
                    )
                    refusedSince = 0
                }
                try {
                    client.close()
                } catch (_: IOException) {
                }
                continue
            }
            try {
                client.tcpNoDelay = true
                // Until its hello is accepted: a stranger waits no longer than the hub gives it to say hello.
                client.soTimeout = Protocol.HELLO_MILLIS.toInt()
                val connection = Connection(client)
                connections += connection
                connection.start()
            } catch (e: Exception) {
                log.warn("NetherForge hub: couldn't start a connection: ${e.message}")
                client.close()
            }
        }
    }

    private var refusedSince = 0
    private var lastRefusalLog = System.nanoTime() - REFUSAL_LOG_NANOS

    private inner class Connection(private val socket: Socket) : Peer {
        override val address: String = socket.remoteSocketAddress.toString().removePrefix("/")
        val host = (socket.remoteSocketAddress as? InetSocketAddress)?.address

        /** Whether its hello was accepted: a server of the network, no longer a stranger. */
        @Volatile var welcomed = false
        private val queue = LinkedBlockingQueue<String>(limits.maxQueuedFrames)
        private val open = AtomicBoolean(true)
        private lateinit var session: Hub.Session
        private var writer: Thread? = null

        /** What every line is sealed with once the hello is accepted ([secure]). */
        @Volatile private var channel: Channel? = null

        override fun secure(channel: Channel) {
            this.channel = channel
        }

        fun start() {
            writer = Thread(::drain, "NetherForge hub writer $address").apply {
                isDaemon = true
                start()
            }
            session = hub.open(this)
            Thread(::read, "NetherForge hub reader $address").apply {
                isDaemon = true
                start()
            }
        }

        override fun send(frame: HubFrame) {
            if (!open.get()) return
            // Sealed and queued as one step: a line's place in the queue is its place in the count it's sealed under.
            val queued = synchronized(this) {
                val text = Protocol.encode(frame)
                queue.offer(channel?.seal(text) ?: text)
            }
            if (!queued) {
                log.warn(
                    "NetherForge hub: dropping $address, which isn't reading what it's sent (${limits.maxQueuedFrames} frames waiting)"
                )
                queue.clear()
                close("it fell too far behind")
            }
        }

        override fun close(reason: String) {
            if (!open.compareAndSet(true, false)) return
            connections -= this
            // The writer sends what was queued before (a refusal), then closes the socket: never wait here, the hub
            // may be holding its lock.
            if (writer == null) closeSocket()
        }

        private fun closeSocket() {
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }

        private fun read() {
            val reader = LineReader(socket.getInputStream(), Protocol.MAX_FRAME_BEFORE_WELCOME)
            try {
                while (open.get()) {
                    val read = reader.readLine() ?: break
                    if (read.isBlank()) continue
                    val sealed = channel
                    val line = if (sealed == null) {
                        read
                    } else {
                        sealed.open(read) ?: run {
                            log.warn("NetherForge hub: dropping $address, which sent a line not sealed with its connection's keys")
                            null
                        } ?: break
                    }
                    val frame = Protocol.decodeServerFrame(line)
                    if (frame == null) {
                        send(HubFrame.Problem("the hub couldn't read a frame: ${line.take(120)}"))
                        continue
                    }
                    session.receive(frame)
                    if (session.name != null && !welcomed) {
                        welcomed = true
                        reader.limit = Protocol.MAX_SEALED_LINE
                        socket.soTimeout = Protocol.IDLE_MILLIS.toInt()
                    }
                }
            } catch (_: SocketTimeoutException) {
                if (welcomed) {
                    log.info("NetherForge hub: dropping $address, silent for ${Protocol.IDLE_MILLIS / 1000} seconds")
                } else {
                    log.info("NetherForge hub: dropping $address, which didn't say hello within ${Protocol.HELLO_MILLIS / 1000} seconds")
                }
            } catch (e: LineTooLongException) {
                log.warn("NetherForge hub: dropping $address, which sent ${e.message}")
            } catch (_: IOException) {
                // Closed, from either end.
            } finally {
                open.set(false)
                connections -= this
                closeSocket()
                session.closed()
            }
        }

        private fun drain() {
            try {
                val out = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                while (true) {
                    val first = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)
                    if (first == null) {
                        if (!open.get()) break
                        continue
                    }
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
            } finally {
                open.set(false)
                closeSocket()
            }
        }
    }

    internal companion object {
        const val STOP_WAIT_MILLIS = 2_000L
        const val POLL_MILLIS = 200L

        /** Connections that haven't said hello yet, at most, in all and from one address. */
        const val MAX_PENDING = 64
        const val MAX_PENDING_PER_ADDRESS = 8

        /** How often a flood of refusals is logged. */
        const val REFUSAL_LOG_NANOS = 10_000_000_000L
    }
}
