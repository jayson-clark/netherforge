package dev.netherforge.plugin.control

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A very small HTTP/1.1 server for a local tool: one request a connection (`Connection: close`), a body by its
 * `Content-Length` (at most [MAX_BODY]), on a Unix domain socket or a loopback TCP port. The JDK's own HTTP server
 * can't listen on a Unix socket, which is why this exists; it serves nothing else.
 */
class LocalHttp(private val address: SocketAddress, private val handle: (Request, Responder) -> Unit) {
    class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String
    ) {
        fun header(name: String) = headers[name.lowercase()]
    }

    /** How a handler answers: once with [send], or by streaming with [stream]. */
    interface Responder {
        fun send(status: Int, json: String)

        /** Starts a `text/event-stream` answer, written to with the returned stream until it fails. */
        fun stream(): OutputStream
    }

    private val running = AtomicBoolean(false)
    private var channel: ServerSocketChannel? = null
    private var thread: Thread? = null
    private val connections = Semaphore(MAX_CONNECTIONS)

    /** Where it listens, once started (the TCP port picked for 0). */
    val boundTo: SocketAddress? get() = channel?.localAddress

    fun start() {
        val server = when (address) {
            is UnixDomainSocketAddress -> {
                Files.createDirectories(address.path.toAbsolutePath().parent)
                Files.deleteIfExists(address.path)
                ServerSocketChannel.open(StandardProtocolFamily.UNIX).also {
                    it.bind(address)
                    owner(address.path)
                }
            }
            else -> ServerSocketChannel.open().also { it.bind(address) }
        }
        channel = server
        running.set(true)
        thread = Thread(::accept, "NetherForge control").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        try {
            channel?.close()
        } catch (_: IOException) {
        }
        (address as? UnixDomainSocketAddress)?.let { Files.deleteIfExists(it.path) }
        thread?.join(STOP_WAIT_MILLIS)
    }

    private fun accept() {
        val server = channel ?: return
        while (running.get()) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            if (!connections.tryAcquire()) {
                client.close()
                continue
            }
            Thread({
                try {
                    serve(client)
                } finally {
                    connections.release()
                }
            }, "NetherForge control connection").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun serve(client: SocketChannel) {
        client.use {
            val input = BufferedInputStream(Channels.newInputStream(client))
            val output = Channels.newOutputStream(client)
            val request = try {
                read(input)
            } catch (e: IOException) {
                return
            } catch (e: IllegalArgumentException) {
                write(output, 400, """{"error":${quote(e.message.orEmpty())}}""")
                return
            }
            var answered = false
            val responder = object : Responder {
                override fun send(status: Int, json: String) {
                    check(!answered) { "answered already" }
                    answered = true
                    write(output, status, json)
                }

                override fun stream(): OutputStream {
                    check(!answered) { "answered already" }
                    answered = true
                    output.write(
                        STREAM_HEAD.toByteArray()
                    )
                    output.flush()
                    return output
                }
            }
            try {
                handle(request, responder)
            } catch (_: IOException) {
            } catch (e: Exception) {
                if (!answered) write(output, 500, """{"error":${quote(e.message ?: e.toString())}}""")
            }
            if (!answered) write(output, 500, """{"error":"nothing answered"}""")
        }
    }

    private fun read(input: InputStream): Request {
        val head = line(input)
        val parts = head.split(' ')
        require(parts.size == 3 && parts[2].startsWith("HTTP/1.")) { "not an HTTP request" }
        val headers = HashMap<String, String>()
        while (true) {
            val line = line(input)
            if (line.isEmpty()) break
            require(headers.size < MAX_HEADERS) { "too many headers" }
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        require(length in 0..MAX_BODY) { "the body is over $MAX_BODY bytes" }
        val body = input.readNBytes(length).toString(Charsets.UTF_8)
        val target = parts[1]
        val path = java.net.URLDecoder.decode(target.substringBefore('?'), Charsets.UTF_8)
        val query = target.substringAfter('?', "").split('&').filter { '=' in it }.associate {
            java.net.URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to
                java.net.URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
        }
        return Request(parts[0], path, query, headers, body)
    }

    /** One line, CRLF or LF ended, at most [MAX_LINE] bytes. */
    private fun line(input: InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("the connection ended")
            if (b == '\n'.code) break
            if (b != '\r'.code) bytes.write(b)
            require(bytes.size() <= MAX_LINE) { "a line is over $MAX_LINE bytes" }
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun write(output: OutputStream, status: Int, json: String) {
        val body = json.toByteArray()
        val reason = REASONS[status] ?: "Status"
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        output.write(head.toByteArray())
        output.write(body)
        output.flush()
    }

    /** The socket's file only for its owner and group: who may reach it is who may read it. */
    private fun owner(path: Path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-rw----"))
        } catch (_: UnsupportedOperationException) {
        }
    }

    companion object {
        const val MAX_BODY = 64 * 1024
        private const val STREAM_HEAD =
            "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        private const val MAX_LINE = 8 * 1024
        private const val MAX_HEADERS = 64
        private const val MAX_CONNECTIONS = 16
        private const val STOP_WAIT_MILLIS = 2_000L
        private val REASONS = mapOf(
            200 to "OK",
            400 to "Bad Request",
            401 to "Unauthorized",
            404 to "Not Found",
            409 to "Conflict",
            500 to "Error",
            503 to "Unavailable"
        )

        fun quote(text: String) = kotlinx.serialization.json.JsonPrimitive(text).toString()

        /** A loopback TCP address: the endpoint never listens anywhere else. */
        fun loopback(port: Int): SocketAddress = InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port)
    }
}
