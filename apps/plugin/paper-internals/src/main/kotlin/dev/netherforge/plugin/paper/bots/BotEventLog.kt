package dev.netherforge.plugin.paper.bots

import dev.netherforge.format.bridge.BotEvent
import dev.netherforge.format.bridge.BotEvents

/** A bot's events in order, numbered from 0; the last [KEPT] are kept. */
internal class BotEventLog {
    private val kept = ArrayDeque<BotEvent>()

    /** The sequence number the next event gets. */
    var next = 0
        private set

    fun add(event: (seq: Int) -> BotEvent) {
        kept.addLast(event(next++))
        while (kept.size > KEPT) kept.removeFirst()
    }

    fun since(seq: Int): BotEvents {
        val first = kept.firstOrNull()?.seq ?: next
        return BotEvents(kept.filter { it.seq >= seq }, next, dropped = (first - seq).coerceAtLeast(0).coerceAtMost(next))
    }

    private companion object {
        const val KEPT = 1000
    }
}
