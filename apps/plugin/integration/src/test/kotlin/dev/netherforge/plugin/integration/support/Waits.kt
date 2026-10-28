package dev.netherforge.plugin.integration.support

import java.util.concurrent.TimeUnit
import kotlin.test.fail

/**
 * How long a scenario waits for the server to do something before failing. One limit for every wait (a bridge
 * frame, a bot's screen, a condition polled): up to four servers share a machine (`integrationTest` runs the
 * versions in parallel), and a tick or a chunk can take seconds there. A wait that passes returns as soon as it
 * can, so a generous limit only costs time when something is already wrong.
 */
const val WAIT_SECONDS = 60L

private const val POLL_MILLIS = 100L

/**
 * Polls [poll] until [accept] takes what it answered, and answers that; fails after [seconds] with [what] and the
 * last value polled (and [context], when the failure needs more), never after a fixed sleep. For whatever the
 * server only shows when asked again: a count after chunks load, a file written off the main thread.
 */
fun <T> eventually(what: String, seconds: Long = WAIT_SECONDS, context: () -> String = { "" }, poll: () -> T, accept: (T) -> Boolean): T {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
    while (true) {
        val value = poll()
        if (accept(value)) return value
        if (System.nanoTime() >= deadline) {
            val more = context()
            fail("$what: not within ${seconds}s; last: $value" + if (more.isEmpty()) "" else "\n$more")
        }
        Thread.sleep(POLL_MILLIS)
    }
}
