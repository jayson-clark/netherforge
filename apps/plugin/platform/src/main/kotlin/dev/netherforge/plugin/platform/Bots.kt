package dev.netherforge.plugin.platform

import dev.netherforge.format.bridge.BotActResult
import dev.netherforge.format.bridge.BotAction
import dev.netherforge.format.bridge.BotEvents
import dev.netherforge.format.bridge.BotInfo
import dev.netherforge.format.bridge.BotPackAnswer
import dev.netherforge.format.bridge.BotPosition
import dev.netherforge.format.bridge.BotState
import dev.netherforge.format.math.Vec3
import java.util.UUID

/**
 * Bots: fake players, made in the server, that do what a player's client
 * would (format's `bridge/Bots.kt` says what they are and what each action
 * means). The dev bridge drives them for tests and coding agents, and
 * scripts drive their own (`nf.bots`, the runtime's `bots/ScriptBots`).
 *
 * A bot is a player like any other to the rest of the platform: it's in
 * [PlayerOps.online], its joins, clicks and chat arrive through
 * [PlatformEvents] as anyone's do. This group only makes them and drives
 * them.
 *
 * Everything is called on the main thread, and every callback is made on it.
 * Failures (no such bot, an action that can't be done) are
 * [IllegalArgumentException]s or [IllegalStateException]s with a sentence
 * for whoever asked; a callback gets them as a failed [Result].
 */
interface BotOps {
    /**
     * Joins a bot as [join] says (its name not online already) and calls
     * [done] once it's in the world: ticks later, since joining loads the
     * world around it.
     */
    fun join(join: BotJoin, done: (Result<BotInfo>) -> Unit)

    /** Disconnects bot [name], as a player quitting: a bot still joining stops trying (its [join] fails). */
    fun leave(name: String)

    /** Disconnects every bot (the plugin is stopping). */
    fun leaveAll()

    fun list(): List<BotInfo>

    /** Whether the online player [id] is a bot (either kind). */
    fun isBot(id: UUID): Boolean

    /**
     * Has bot [name] do [action]; [done] is called when it has, now or ticks
     * later (walking, mining). [aim] is where an [BotAction.Interact] or
     * [BotAction.Attack] naming a centity was resolved to, by the runtime.
     */
    fun act(name: String, action: BotAction, aim: BotAim?, done: (Result<BotActResult>) -> Unit)

    fun state(name: String): BotState

    fun events(name: String, since: Int): BotEvents
}

/**
 * How a bot joins: as [name], at [at] when given (else where it last was,
 * or the spawn), answering resource packs with [pack]. [script] makes it a
 * script's bot rather than the bridge's.
 */
data class BotJoin(
    val name: String,
    val at: BotPosition? = null,
    val pack: BotPackAnswer = BotPackAnswer.DECLINE,
    val script: ScriptBot? = null
)

/**
 * A script's bot. The bridge's are the same player every time (a UUID from
 * the name, saved like anyone's, so a test can leave and come back); a
 * script's is a new player each time it joins: a random UUID, never saved
 * (no player data, statistics or advancements), marked as an NPC for other
 * plugins (the `NPC` metadata Citizens set, which plugins check to leave
 * fake players alone), loading only the chunks right around it
 * ([VIEW_DISTANCE]), and wearing [skin] when given.
 */
data class ScriptBot(val skin: SkinTexture? = null) {
    companion object {
        /** The view distance a script's bot has, in chunks: enough to stand and walk in, and as little as the server allows. */
        const val VIEW_DISTANCE = 2
    }
}

/** A skin as Mojang's session servers sign it: the profile's `textures` property, base64, and its signature. */
data class SkinTexture(val value: String, val signature: String? = null)

/** An entity for a bot to click and the point on it to look at. */
data class BotAim(val entity: UUID, val at: Vec3)
