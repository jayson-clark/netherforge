package dev.netherforge.plugin.api

import dev.netherforge.plugin.binding.HttpStreamApi
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.WebSocketApi
import dev.netherforge.plugin.http.Connection
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.session.ProjectSession
import java.nio.charset.StandardCharsets

/** What `state()` answers for [connection] (null once it has ended). */
private fun stateOf(connection: Connection?) = when {
    connection == null || connection.ended -> "ended"
    connection.opened -> "open"
    else -> "connecting"
}

/** `HttpStream`: a handle to a stream by number; once it has ended, `"ended"`, nil and false. */
internal class HttpStreamImpl(private val session: ProjectSession) : HttpStreamApi {
    private fun connection(self: LuaHandle.HttpStream): Connection? = session.connections.find(self.number)

    override fun url(self: LuaHandle.HttpStream): String = self.url

    override fun state(self: LuaHandle.HttpStream): String = stateOf(connection(self))

    override fun status(self: LuaHandle.HttpStream): Long? = connection(self)?.status?.toLong()

    override fun close(self: LuaHandle.HttpStream): Boolean = connection(self)?.let { session.connections.close(it) } == true
}

/** `WebSocket`: a handle to a socket by number; once it has ended, `"ended"`, nil and false. */
internal class WebSocketImpl(private val session: ProjectSession) : WebSocketApi {
    private fun connection(self: LuaHandle.WebSocket): Connection? = session.connections.find(self.number)

    override fun url(self: LuaHandle.WebSocket): String = self.url

    override fun state(self: LuaHandle.WebSocket): String = stateOf(connection(self))

    override fun protocol(self: LuaHandle.WebSocket): String? = connection(self)?.protocol

    override fun send(self: LuaHandle.WebSocket, text: String): Boolean {
        val max = session.config.http.connections.maxMessageBytes
        val size = text.toByteArray(StandardCharsets.UTF_8).size
        if (size > max) {
            throw LuaApiException(
                "the message is $size bytes, over the server's limit of $max (http.connections.max-message-bytes in config.yml)"
            )
        }
        val connection = connection(self) ?: return false
        return session.connections.send(connection, text)
    }

    override fun close(self: LuaHandle.WebSocket, code: Long?, reason: String?): Boolean {
        val closeCode = code ?: 1000L
        if (closeCode != 1000L && closeCode !in APPLICATION_CODES) {
            throw LuaApiException("bad argument 'code' (1000, or 3000 to 4999 expected, got $closeCode)")
        }
        if (reason != null && reason.toByteArray(StandardCharsets.UTF_8).size > MAX_REASON_BYTES) {
            throw LuaApiException("bad argument 'reason' (at most $MAX_REASON_BYTES bytes expected)")
        }
        val connection = connection(self) ?: return false
        return session.connections.close(connection, closeCode.toInt(), reason)
    }

    private companion object {
        val APPLICATION_CODES = 3000L..4999L
        const val MAX_REASON_BYTES = 123
    }
}
