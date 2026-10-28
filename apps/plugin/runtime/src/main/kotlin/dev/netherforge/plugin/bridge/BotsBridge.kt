package dev.netherforge.plugin.bridge

import dev.netherforge.format.bridge.BotAction
import dev.netherforge.format.bridge.BotState
import dev.netherforge.format.bridge.BotsExtension
import dev.netherforge.plugin.NetherForgeRuntime
import dev.netherforge.plugin.platform.BotAim
import dev.netherforge.plugin.platform.BotJoin
import dev.netherforge.plugin.platform.BotOps
import java.util.UUID

/**
 * The bridge's bots extension ([BotsExtension], `bots/…`): requests that
 * drive the platform's bots, for tests and coding agents. Not part of the
 * core protocol, and self-contained: it needs only the [BridgeService] to
 * install into, the bots, and the runtime's centities for aiming.
 */
class BotsBridge(private val runtime: NetherForgeRuntime, private val bots: BotOps) {
    fun install(service: BridgeService) {
        // Joining and acting finish ticks later; the answer waits for them.
        service.handle(BotsExtension.join) { params, reply -> bots.join(BotJoin(params.name, params.at, params.resourcePack), reply) }
        service.answer(BotsExtension.leave) { bots.leave(it.name) }
        service.answer(BotsExtension.list) { bots.list() }
        service.handle(BotsExtension.act) { params, reply -> bots.act(params.name, params.action, aimOf(params.action), reply) }
        service.answer(BotsExtension.state) { withCentity(bots.state(it.name)) }
        service.answer(BotsExtension.events) { bots.events(it.name, it.since) }
    }

    /**
     * [state] with its target's centity and node filled in when it looks at a
     * centity's hitbox: which node a click there reaches is the runtime's to
     * say, from the same line of sight a click would be picked by.
     */
    private fun withCentity(state: BotState): BotState {
        val target = state.target ?: return state
        val entity = target.entity?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return state
        val player = UUID.fromString(state.uuid)
        val (instance, node) = runtime.session.centities.clickTarget(entity, player, runtime.platform.players.eye(player)) ?: return state
        return state.copy(target = target.copy(centity = instance.id.toString(), node = node))
    }

    /**
     * Where an interact or attack naming a centity's node aims: the node's
     * interaction entity and the middle of its hitbox, which only the runtime
     * knows. Null for anything else.
     */
    private fun aimOf(action: BotAction): BotAim? {
        val (centity, node) = when (action) {
            is BotAction.Interact -> action.centity to action.node
            is BotAction.Attack -> action.centity to action.node
            else -> return null
        }
        if (centity == null) {
            require(node == null) { "`node` names a node of the centity given as `centity`, and there's none" }
            return null
        }
        val instance = runtime.session.centities.find(centity) ?: throw IllegalArgumentException("no centity instance \"$centity\"")
        if (node != null) {
            requireNotNull(instance.indexOf(node)) { "centity ${instance.centity} has no node \"$node\"" }
        }
        val nothing = node?.let { "node \"$it\" of centity ${instance.centity} has no hitbox" } ?: "centity ${instance.centity} has nothing"
        val (entity, at) = runtime.session.centities.aim(instance, node) ?: throw IllegalArgumentException("$nothing to click")
        return BotAim(entity, at)
    }
}
