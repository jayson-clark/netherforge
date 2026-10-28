package dev.netherforge.plugin.bots

import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.bridge.BotActResult
import dev.netherforge.format.bridge.BotAction
import dev.netherforge.format.bridge.BotPackAnswer
import dev.netherforge.format.bridge.BotPosition
import dev.netherforge.format.project.PackagePaths
import dev.netherforge.format.project.ProjectManifest
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.plugin.async.WorkFailed
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.BotAim
import dev.netherforge.plugin.platform.BotJoin
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.ScriptBot
import dev.netherforge.plugin.platform.SkinTexture
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.session.RuntimeService
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * The bots scripts join (`nf.bots`), and the one way anything of the
 * runtime drives a bot ([act]).
 *
 * A bot belongs to the scope that joined it and leaves when that scope is
 * released, and every one leaves when the session ends: a bot is a new
 * player each time (a fresh UUID, never saved), so there's nothing to carry
 * across a reload. The owner's `bots:` decides whether scripts may join any
 * and how many may be online at once ([BotsConfig]); the dev bridge's own
 * bots aren't counted.
 */
internal class ScriptBots(private val platform: Platform, private val config: BotsConfig, private val snapshot: () -> ProjectSnapshot?) :
    RuntimeService {
    override val name get() = "bots"

    /** A script's bot: [uuid] is set once it's in the world. */
    private class Owned(val name: String, val scope: Scope) {
        var uuid: UUID? = null
    }

    /** By name in lowercase, joining ones included: what counts towards `bots.max`. */
    private val owned = LinkedHashMap<String, Owned>()

    /**
     * Joins a bot for [scope]: its UUID once it's in the world, or a
     * [WorkFailed] saying why not (bots are off, too many, the name is taken,
     * the server turned it away). A name that can't be a player's is the
     * script's mistake, an error at its line.
     */
    fun join(scope: Scope, name: String, at: Location?, skin: SkinTexture?): CompletionStage<UUID> {
        if (!NAME.matches(name)) throw LuaApiException("bad argument 'name' (3 to 16 letters, digits or _ expected, got \"$name\")")
        val failed = when {
            !config.enabled -> "bots are off on this server: the server owner turns them on with bots.enabled in config.yml"
            owned.size >= config.max -> "${config.max} bots are online already, as many as this server allows (bots.max in config.yml)"
            key(name) in owned || platform.players.find(name) != null -> "someone called $name is online already"
            else -> null
        }
        if (failed != null) return CompletableFuture.failedFuture(WorkFailed(failed))
        val bot = Owned(name, scope)
        owned[key(name)] = bot
        val joined = CompletableFuture<UUID>()
        val position = at?.let { BotPosition(it.x, it.y, it.z, it.world, it.yaw, it.pitch) }
        try {
            platform.bots.join(BotJoin(name, position, BotPackAnswer.LOADED, ScriptBot(skin))) { result ->
                result.fold(
                    { info ->
                        val uuid = UUID.fromString(info.uuid)
                        if (owned[key(name)] === bot) {
                            bot.uuid = uuid
                            joined.complete(uuid)
                        } else {
                            // Its scope went while it was joining: it leaves as it arrives.
                            runCatching { platform.bots.leave(name) }
                            joined.completeExceptionally(WorkFailed("$name's script stopped while it was joining"))
                        }
                    },
                    { why ->
                        if (owned[key(name)] === bot) owned.remove(key(name))
                        joined.completeExceptionally(WorkFailed(why.message ?: "$name couldn't join"))
                    }
                )
            }
        } catch (e: IllegalArgumentException) {
            owned.remove(key(name))
            joined.completeExceptionally(WorkFailed(e.message ?: "$name couldn't join"))
        }
        return joined
    }

    /** Disconnects bot [name], whoever joined it; false when no bot of that name is online. */
    fun leave(name: String): Boolean {
        owned.remove(key(name))
        return runCatching { platform.bots.leave(name) }.isSuccess
    }

    /**
     * Has the bot [id] do [action] as its client would: done once the platform
     * says so (now, or ticks later for a walk or a mine), or a failure saying
     * why it couldn't. A bot that has left fails at once.
     */
    fun act(id: UUID, action: BotAction, aim: BotAim? = null): CompletionStage<BotActResult> {
        val done = CompletableFuture<BotActResult>()
        val name = nameOf(id) ?: return done.apply { completeExceptionally(WorkFailed("the bot has left")) }
        try {
            platform.bots.act(name, action, aim) { result ->
                result.fold(done::complete) { done.completeExceptionally(WorkFailed(it.message ?: "$name couldn't")) }
            }
        } catch (e: IllegalArgumentException) {
            done.completeExceptionally(WorkFailed(e.message ?: "$name couldn't"))
        } catch (e: IllegalStateException) {
            done.completeExceptionally(WorkFailed(e.message ?: "$name couldn't"))
        }
        return done
    }

    /**
     * [act] for what takes no ticks: whether the bot did it. (Something that
     * would take ticks counts as done once it has started.)
     */
    fun did(id: UUID, action: BotAction, aim: BotAim? = null): Boolean {
        val done = act(id, action, aim).toCompletableFuture()
        return !done.isCompletedExceptionally
    }

    /** The online bot [id]'s name, or null when it isn't one (it left, or it was never a bot). */
    fun nameOf(id: UUID): String? = platform.players.get(id)?.takeIf { platform.bots.isBot(id) }?.name

    /** Every bot online, whoever joined it. */
    fun online(): List<UUID> = platform.bots.list().map { UUID.fromString(it.uuid) }

    override fun scopeReleased(scope: Scope) {
        for (bot in owned.values.filter { it.scope === scope }) leave(bot.name)
    }

    override fun playerQuit(player: PlayerRef) {
        owned.entries.removeIf { it.value.uuid == player.uuid }
    }

    override fun stop() {
        for (bot in owned.values.toList()) leave(bot.name)
    }

    override fun problems(): List<Problem> {
        if (config.enabled) return emptyList()
        val snapshot = snapshot() ?: return emptyList()
        return (listOf(snapshot.namespace) + snapshot.packages.keys.sorted())
            .filter { snapshot.manifestOf(it)?.requires?.bots == true }
            .map { namespace ->
                val file = if (namespace ==
                    snapshot.namespace
                ) {
                    ProjectManifest.FILE_NAME
                } else {
                    PackagePaths.of(namespace, ProjectManifest.FILE_NAME)
                }
                ProblemCodes.RUNTIME_BOTS_DISABLED.at(
                    file,
                    "Bots are off on this server, so this package's scripts can't join any: the server owner turns them on with bots.enabled in config.yml",
                    "$.requires.bots"
                )
            }
    }

    private fun key(name: String) = name.lowercase()

    private companion object {
        /** A player's name, as the platform's bots take one. */
        val NAME = Regex("[A-Za-z0-9_]{3,16}")
    }
}
