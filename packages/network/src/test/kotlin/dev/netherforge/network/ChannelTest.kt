package dev.netherforge.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A connection's sealed lines: each opens once, in order, only at the other end of the same connection. */
class ChannelTest {
    private val hubNonce = Auth.newNonce()
    private val serverNonce = Auth.newNonce()
    private val hub = Channel.hub("s3cret", hubNonce, serverNonce, "lobby")
    private val server = Channel.server("s3cret", hubNonce, serverNonce, "lobby")

    @Test
    fun `what one end seals the other opens, both ways, in order`() {
        val lines = (1..3).map { hub.seal("""{"t":"pong","n":$it}""") }
        assertTrue(lines.all { it.startsWith(Channel.PREFIX) && '\n' !in it && "pong" !in it })
        assertEquals((1..3).map { """{"t":"pong","n":$it}""" }, lines.map { server.open(it) })
        assertEquals("""{"t":"ping"}""", hub.open(server.seal("""{"t":"ping"}""")))
    }

    @Test
    fun `an altered, replayed, reordered or foreign line doesn't open`() {
        val first = hub.seal("one")
        val second = hub.seal("two")
        assertNull(server.open(second), "out of order")
        val altered = first.dropLast(2) + (if (first.endsWith("A=")) "B=" else "A=")
        assertNull(server.open(altered), "altered")
        assertEquals("one", server.open(first))
        assertNull(server.open(first), "replayed")
        assertEquals("two", server.open(second))
        // Another connection's keys (new nonces), another server's, another secret's: none opens it.
        val line = hub.seal("three")
        assertNull(Channel.server("s3cret", Auth.newNonce(), serverNonce, "lobby").open(line))
        assertNull(Channel.server("s3cret", hubNonce, serverNonce, "survival").open(line))
        assertNull(Channel.server("other", hubNonce, serverNonce, "lobby").open(line))
        // Its own direction's key doesn't open it either: the hub can't be fed its own lines back.
        assertNull(Channel.hub("s3cret", hubNonce, serverNonce, "lobby").open(line))
        assertFalse(server.open("{\"t\":\"pong\"}") != null, "a plain line")
    }
}
