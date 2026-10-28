package dev.netherforge.plugin.integration.support

import dev.netherforge.format.bridge.BotActParams
import dev.netherforge.format.bridge.BotActResult
import dev.netherforge.format.bridge.BotAction
import dev.netherforge.format.bridge.BotEvent
import dev.netherforge.format.bridge.BotEventsParams
import dev.netherforge.format.bridge.BotParams
import dev.netherforge.format.bridge.BotState
import dev.netherforge.format.bridge.BotsExtension
import dev.netherforge.format.bridge.Log
import dev.netherforge.format.bridge.ScriptError
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Driving bots over [editor]'s bridge, the way a test or a coding agent does. Waits on what a bot hears or sees poll
 * for [WAIT_SECONDS], like every other wait.
 */
class Bots(private val editor: Editor) {
    /** Bot [bot] does [action], which must succeed. */
    fun act(bot: String, action: BotAction): BotActResult {
        val (response, result) = editor.request(BotsExtension.act, BotActParams(bot, action))
        assertTrue(response.ok, "$action: ${response.error}")
        return result!!
    }

    /** Bot [bot] can't do [action]: why. */
    fun refused(bot: String, action: BotAction): String {
        val (response, _) = editor.request(BotsExtension.act, BotActParams(bot, action))
        assertFalse(response.ok, "$action should have been refused")
        return response.error!!
    }

    fun state(bot: String): BotState = editor.request(BotsExtension.state, BotParams(bot)).second!!

    fun events(bot: String, since: Int): List<BotEvent> = editor.request(BotsExtension.events, BotEventsParams(bot, since)).second!!.events

    /** Waits for bot [bot]'s first event from [since] on that [matching] accepts. */
    fun <T : BotEvent> heard(bot: String, since: Int, type: Class<T>, matching: (T) -> Boolean = { true }): T = eventually(
        "$bot heard no ${type.simpleName} matching",
        context = ::console,
        poll = { events(bot, since).filterIsInstance(type) },
        accept = { heard -> heard.any(matching) }
    ).first(matching)

    /** Waits until bot [bot]'s screen shows what [check] wants: it reads what the server sent on its next tick. */
    fun eventually(bot: String, check: (BotState) -> Boolean): BotState =
        eventually("$bot's screen never showed it", context = ::console, poll = { state(bot) }, accept = check)

    /** Waits for bot [bot] to be told [text] in chat, from event [since] on. */
    fun said(bot: String, since: Int, text: String) = heard(bot, since, BotEvent.Chat::class.java) { it.text == text }

    /** The last of what the server's console said over the bridge, for a failure's message. */
    private fun console(): String = "the server said:\n" +
        editor.seen.filter { it is Log || it is ScriptError }.takeLast(CONSOLE_LINES).joinToString("\n") { "  " + Editor.describe(it) }

    private companion object {
        const val CONSOLE_LINES = 20
    }
}
