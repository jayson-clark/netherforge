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
            try {
                client.tcpNoDelay = true
                client.soTimeout = Protocol.IDLE_MILLIS.toInt()
                val connection = Connection(client)
                connections += connection
                connection.start()
            } catch (e: Exception) {
                log.warn("NetherForge hub: couldn't start a connection: ${e.message}")
                client.close()
            }
        }
    }

    private inner class Connection(private val socket: Socket) : Peer {
        override val address: String = socket.remoteSocketAddress.toString().removePrefix("/")
        private val queue = LinkedBlockingQueue<String>(limits.maxQueuedFrames)
        private val open = AtomicBoolean(true)
        private lateinit var session: Hub.Session
        private var writer: Thread? = null

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
            if (!queue.offer(Protocol.encode(frame))) {
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
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val frame = Protocol.decodeServerFrame(line)
                    if (frame == null) {
                        send(HubFrame.Problem("the hub couldn't read a frame: ${line.take(120)}"))
                        continue
                    }
                    session.receive(frame)
                    if (session.name != null) reader.limit = Protocol.MAX_FRAME
                }
            } catch (_: SocketTimeoutException) {
                log.info("NetherForge hub: dropping $address, silent for ${Protocol.IDLE_MILLIS / 1000} seconds")
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

    private companion object {
        const val STOP_WAIT_MILLIS = 2_000L
        const val POLL_MILLIS = 200L
    }
}
