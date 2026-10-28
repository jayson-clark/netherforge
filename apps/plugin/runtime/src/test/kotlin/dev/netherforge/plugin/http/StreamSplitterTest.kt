package dev.netherforge.plugin.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** How a streamed body becomes messages, fed in pieces of any size: lines, Server-Sent Events and chunks. */
class StreamSplitterTest {
    private fun split(
        format: StreamFormat,
        vararg pieces: String,
        max: Int = 1024,
        finish: Boolean = true
    ): List<Triple<String, String?, String?>> {
        val out = ArrayList<Triple<String, String?, String?>>()
        val splitter = StreamSplitter(format, max) { data, type, id -> out += Triple(data, type, id) }
        for (piece in pieces) splitter.feed(piece.toByteArray())
        if (finish) splitter.finish()
        return out
    }

    private fun lines(vararg pieces: String, finish: Boolean = true) = split(StreamFormat.LINES, *pieces, finish = finish).map { it.first }

    @Test
    fun `lines end at LF, CRLF or a lone CR, wherever the pieces are cut`() {
        assertEquals(listOf("a", "b", "c", "", "d"), lines("a\nb\r", "\nc\r\rd\n"))
        assertEquals(listOf("{\"x\":1}", "{\"x\":2}"), lines("{\"x\"", ":1}\n{\"x\":2}\n"))
    }

    @Test
    fun `a last line without its ending is a line when the body ends, and only then`() {
        assertEquals(listOf("a", "tail"), lines("a\ntail"))
        assertEquals(listOf("a"), lines("a\ntail", finish = false))
    }

    @Test
    fun `a character cut between two pieces is decoded whole`() {
        val bytes = "é€\n".toByteArray()
        val out = ArrayList<String>()
        val splitter = StreamSplitter(StreamFormat.LINES, 1024) { data, _, _ -> out += data }
        for (b in bytes) splitter.feed(byteArrayOf(b))
        assertEquals(listOf("é€"), out)
    }

    @Test
    fun `server-sent events join data lines and carry their type and the last id`() {
        val events = split(
            StreamFormat.EVENTS,
            ": a comment\nretry: 1000\n",
            "event: score\ndata: 1\ndata:2\nid: 7\n\n",
            "data: plain\n\n",
            "id\ndata: no id\n\r\n",
            "data: never finished\n"
        )
        assertEquals(
            listOf(Triple("1\n2", "score", "7"), Triple("plain", "message", "7"), Triple("no id", "message", "")),
            events
        )
    }

    @Test
    fun `an event with no data is no event, and resets its type`() {
        assertEquals(listOf(Triple("x", "message", null)), split(StreamFormat.EVENTS, "event: ping\n\ndata: x\n\n"))
    }

    @Test
    fun `chunks are the text as it comes, a character cut between pieces held for the next`() {
        val bytes = "ab€".toByteArray()
        val out = ArrayList<String>()
        val splitter = StreamSplitter(StreamFormat.CHUNKS, 1024) { data, type, _ -> out += "$data:$type" }
        splitter.feed(bytes, 3)
        splitter.feed(bytes.copyOfRange(3, bytes.size))
        splitter.finish()
        assertEquals(listOf("ab:null", "€:null"), out)
    }

    @Test
    fun `a line or an event's data over the limit is refused`() {
        assertFailsWith<MessageTooLarge> { split(StreamFormat.LINES, "12345\n", max = 4) }
        assertEquals(listOf("1234"), split(StreamFormat.LINES, "1234\n", max = 4).map { it.first })
        assertFailsWith<MessageTooLarge> { split(StreamFormat.EVENTS, "data: 12\ndata: 34\n\n", max = 4) }
    }
}
