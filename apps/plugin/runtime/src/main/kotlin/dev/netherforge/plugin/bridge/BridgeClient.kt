package dev.netherforge.plugin.bridge

import dev.netherforge.format.bridge.Bridge
import dev.netherforge.format.bridge.BridgeEvent
import dev.netherforge.format.bridge.BridgeStream
import dev.netherforge.format.bridge.HelloParams
import dev.netherforge.format.bridge.JsonRpc
import dev.netherforge.format.bridge.RpcFrame
import dev.netherforge.format.bridge.RpcMessage
import dev.netherforge.format.bridge.RpcResponse
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The plugin's end of the dev bridge: a socket to the editor on
 * `127.0.0.1:<port>`, JSON-RPC 2.0 frames one per line both ways.
 *
 * Two threads, so the server's main thread never waits on the editor: the
 * connection thread connects, sends the hello request and waits for its
 * answer, then reads frames and hands them to [onFrame]; a writer thread
 * drains the outgoing queue. Anything sent while disconnected waits in the
 * queue (bounded; the oldest go first) and is delivered after the next
 * accepted hello. A dropped connection is retried with backoff, from 250 ms
 * up to 5 s, until [stop].
 *
 * Stream items ([stream]) are coalesced: an item joins the batch at the end
 * of the queue when there is one, so a burst of log lines goes out as one
 * notification rather than one each, and in order with everything else.
 *
 * An editor that refuses the hello (another protocol version) is final:
 * [onRefused] gets its sentence and the client stops. Retrying gives up after
 * [abandonAfterMillis] without a connection (counted from the last one
 * ending, or from [start] if there never was one), and then [onAbandoned]
 * runs once. A dev server's editor listens on a fresh port each run, so an
 * editor that's been gone that long isn't coming back to this one.
 */
class BridgeClient(
    private val port: Int,
    private val hello: () -> HelloParams,
    /** Called on the connection thread for each frame after the hello. */
    private val onFrame: (RpcFrame) -> Unit,
    /** Called on the connection thread once the hello is accepted. */
    private val onConnected: () -> Unit,
    /** Called on the connection thread when a connection whose hello was accepted ends. */
    private val onDisconnected: () -> Unit = {},
    private val onProblem: (String) -> Unit,
    /** Called on the connection thread, once, when the editor refuses the hello. */
    private val onRefused: (String) -> Unit = onProblem,
    private val abandonAfterMillis: Long = Long.MAX_VALUE,
    /** Called on the connection thread, once, when retrying gives up. */
    private val onAbandoned: () -> Unit = {},
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var accepted = false

    /** What waits to be written, guarded by [lock]; [pending] counts stream items one each. */
    private val lock = ReentrantLock()
    private val waiting = lock.newCondition()
    private val queue = ArrayDeque<Outgoing>()
    private var pending = 0

    val connected: Boolean get() = accepted && socket?.isClosed == false

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread(::connectLoop, "NetherForge bridge").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        socket?.close()
        thread?.interrupt()
        thread?.join(STOP_WAIT_MILLIS)
        thread = null
    }

    /** Queues a message (a response). Safe from any thread; never blocks. */
    fun send(message: RpcMessage) = enqueue(Frame { JsonRpc.line(message) })

    /** Queues a batch of responses as one frame. */
    fun send(batch: List<RpcMessage>) = enqueue(Frame { JsonRpc.line(batch) })

    /** Queues a notification; [params] is encoded on the writer thread, so it mustn't change after. */
    fun <P> notify(event: BridgeEvent<P>, params: P) = enqueue(Frame { JsonRpc.line(event.notification(params)) })

    /** Adds [item] to [stream]'s batch at the end of the queue, or starts one. */
    fun <T> stream(stream: BridgeStream<T>, item: T) {
        lock.withLock {
            val last = queue.lastOrNull()
            if (last is Batch<*> && last.stream === stream && last.items.size < MAX_BATCH) {
                @Suppress("UNCHECKED_CAST")
                (last as Batch<T>).items += item
                pending++
                trim()
            } else {
                add(Batch(stream, mutableListOf(item)))
            }
        }
    }

    private fun enqueue(outgoing: Outgoing) = lock.withLock { add(outgoing) }

    /** Under the queue's lock. */
    private fun add(outgoing: Outgoing) {
        queue.addLast(outgoing)
        pending += outgoing.size
        trim()
        waiting.signalAll()
    }

    /** Under the queue's lock: drops the oldest until what waits fits. */
    private fun trim() {
        while (pending > QUEUE_LIMIT && queue.size > 1) pending -= queue.removeFirst().size
    }

    /** Everything waiting, waiting up to [POLL_MILLIS] for something. */
    private fun takeAll(): List<Outgoing> = lock.withLock {
        if (queue.isEmpty()) waiting.await(POLL_MILLIS, TimeUnit.MILLISECONDS)
        val all = queue.toList()
        queue.clear()
        pending = 0
        all
    }

    /** Puts back what couldn't be written, ahead of what's queued since. */
    private fun putBack(outgoing: List<Outgoing>) = lock.withLock {
        for (item in outgoing.asReversed()) {
            queue.addFirst(item)
            pending += item.size
        }
        trim()
    }

    private fun connectLoop() {
        var backoff = MIN_BACKOFF_MILLIS
        var warned = false
        var lastContact = clock()
        while (running.get()) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MILLIS)
                    socket.tcpNoDelay = true
                    this.socket = socket
                    backoff = MIN_BACKOFF_MILLIS
                    warned = false
                    session(socket)
                }
                lastContact = clock()
            } catch (e: IOException) {
                if (running.get() && !warned) {
                    onProblem("Dev bridge on port $port: ${e.message}; retrying")
                    warned = true
                }
            } finally {
                socket = null
                accepted = false
            }
            if (!running.get()) break
            if (clock() - lastContact >= abandonAfterMillis) {
                running.set(false)
                onAbandoned()
                break
            }
            try {
                Thread.sleep(backoff)
            } catch (_: InterruptedException) {
                break
            }
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
        }
    }

    private fun session(socket: Socket) {
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        val reader = FrameReader(socket.getInputStream(), MAX_FRAME_BEFORE_HELLO)
        writer.write(JsonRpc.line(Bridge.hello.request(HELLO_ID, hello())))
        writer.write("\n")
        writer.flush()

        // Nothing else goes out until the editor has accepted the hello.
        socket.soTimeout = HELLO_TIMEOUT_MILLIS
        val answer = awaitHelloAnswer(reader) ?: return
        answer.error?.let { error ->
            running.set(false)
            onRefused(error.message)
            return
        }
        socket.soTimeout = 0
        accepted = true
        reader.limit = MAX_FRAME

        val writing = Thread({ drain(socket, writer) }, "NetherForge bridge writer").apply {
            isDaemon = true
            start()
        }
        onConnected()
        try {
            while (running.get()) {
                val line = reader.readFrame() ?: break
                if (line.isBlank()) continue
                onFrame(JsonRpc.decode(line))
            }
        } catch (e: FrameTooLargeException) {
            // Dropped, and retried like any other drop; the reason goes to the console.
            onProblem("Dev bridge: closing the connection, the editor sent ${e.message}")
        } finally {
            socket.close()
            accepted = false
            writing.interrupt()
            try {
                writing.join(STOP_WAIT_MILLIS)
            } catch (_: InterruptedException) {
                // stop() interrupts us too; the writer is already on its way out.
            }
            onDisconnected()
        }
    }

    /** The editor's answer to the hello, or null when the connection closed first (a wrong token is never answered). */
    private fun awaitHelloAnswer(reader: FrameReader): RpcResponse? {
        while (true) {
            val line = try {
                reader.readFrame()
            } catch (e: FrameTooLargeException) {
                onProblem("Dev bridge: closing the connection, the editor sent ${e.message} before answering the hello")
                return null
            } ?: return null
            if (line.isBlank()) continue
            val message = (JsonRpc.decode(line) as? RpcFrame.Single)?.message
            if (message is RpcResponse && message.id.content == HELLO_ID.toString()) return message
            onProblem("The editor sent a frame before answering the hello; ignored")
        }
    }

    private fun drain(socket: Socket, writer: BufferedWriter) {
        try {
            while (!socket.isClosed) {
                val all = takeAll()
                if (all.isEmpty()) continue
                try {
                    for (outgoing in all) {
                        writer.write(outgoing.line())
                        writer.write("\n")
                    }
                    writer.flush()
                } catch (e: IOException) {
                    // Put it back for the next connection rather than lose it.
                    putBack(all)
                    socket.close()
                    return
                }
            }
        } catch (_: InterruptedException) {
            // Session over.
        }
    }

    private sealed interface Outgoing {
        val size: Int

        fun line(): String
    }

    private class Frame(private val encode: () -> String) : Outgoing {
        override val size get() = 1

        override fun line() = encode()
    }

    private class Batch<T>(val stream: BridgeStream<T>, val items: MutableList<T>) : Outgoing {
        override val size get() = items.size

        override fun line() = JsonRpc.line(stream.notification(items))
    }

    companion object {
        /** The most the editor's answer to the hello can be: a refusal is a sentence. */
        const val MAX_FRAME_BEFORE_HELLO = 16 * 1024

        /**
         * The most one frame from the editor can be once the hello is
         * accepted. Its requests are small (a reload's paths, a command);
         * the editor's own cap on what it reads from the plugin is larger,
         * because the game data export is megabytes.
         */
        const val MAX_FRAME = 16 * 1024 * 1024
        private const val HELLO_ID = 0
        private const val QUEUE_LIMIT = 10_000

        /** Items in one stream notification at most. */
        private const val MAX_BATCH = 500
        private const val MIN_BACKOFF_MILLIS = 250L
        private const val MAX_BACKOFF_MILLIS = 5_000L
        private const val CONNECT_TIMEOUT_MILLIS = 2_000
        private const val HELLO_TIMEOUT_MILLIS = 10_000
        private const val POLL_MILLIS = 200L
        private const val STOP_WAIT_MILLIS = 2_000L
    }
}
