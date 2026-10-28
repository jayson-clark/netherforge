package dev.netherforge.plugin.api

import dev.netherforge.format.bridge.BotAction
import dev.netherforge.format.bridge.BotClick
import dev.netherforge.format.bridge.BotHand
import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.async.WorkFailed
import dev.netherforge.plugin.binding.BotApi
import dev.netherforge.plugin.binding.BotInteractOptions
import dev.netherforge.plugin.binding.BotJoinOptions
import dev.netherforge.plugin.binding.BotWalkOptions
import dev.netherforge.plugin.binding.Caller
import dev.netherforge.plugin.binding.LocationOrVec3
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.NfBotsApi
import dev.netherforge.plugin.binding.place
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.BotAim
import dev.netherforge.plugin.platform.SkinTexture
import dev.netherforge.plugin.session.ProjectSession
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.math.abs
import kotlin.math.floor

/**
 * `nf.bots`: joining bots and finding them. The `bots` requirement is the
 * generated binding's; the owner's `bots:` and the limits are [ScriptBots]'.
 */
internal class NfBotsImpl(private val session: ProjectSession) : NfBotsApi {
    override fun join(
        caller: Caller,
        name: String,
        locationOrPosition: LocationOrVec3?,
        options: BotJoinOptions?
    ): CompletionStage<LuaHandle.Bot> {
        val scope = caller.scope
        val at = locationOrPosition?.place?.resolve(session.platform.worlds.defaultWorld())
        val skin = options?.skin?.let { SkinTexture(it.texture, it.signature) }
        return session.bots.join(scope, name, at, skin).thenApply { LuaHandle.Bot(it.toString()) }
    }

    override fun all(caller: Caller): List<LuaHandle.Bot> = session.bots.online().map { LuaHandle.Bot(it.toString()) }
}

/**
 * `Bot`: a bot's controls, each sent as its client would through
 * [ScriptBots.act]. What takes no ticks answers whether it was done; a walk
 * and a mine are asynchronous.
 */
internal class BotImpl(private val session: ProjectSession) : BotApi {
    private val bots get() = session.bots

    private fun did(self: LuaHandle.Bot, action: BotAction, aim: BotAim? = null): Boolean {
        val id = self.uuidOrNull() ?: return false
        return bots.did(id, action, aim)
    }

    override fun leave(self: LuaHandle.Bot): Boolean {
        val name = self.uuidOrNull()?.let(bots::nameOf) ?: return false
        return bots.leave(name)
    }

    override fun walkTo(self: LuaHandle.Bot, position: Vec3, options: BotWalkOptions?): CompletionStage<Vec3> {
        val timeout = options?.timeout ?: BotAction.DEFAULT_WALK_TICKS.toLong()
        if (timeout !in 1..BotAction.MAX_TICKS) {
            throw LuaApiException("bad argument 'options.timeout' (1 to ${BotAction.MAX_TICKS} ticks expected, got $timeout)")
        }
        val walk = BotAction.WalkTo(position.x, position.z, options?.sprint == true, timeout.toInt())
        return act(self, walk).thenApply { Vec3(it.x, it.y, it.z) }
    }

    override fun stop(self: LuaHandle.Bot): Boolean = did(self, BotAction.Stop)

    override fun lookAt(self: LuaHandle.Bot, point: Vec3): Boolean = did(self, BotAction.LookAt(point.x, point.y, point.z))

    override fun jump(self: LuaHandle.Bot): Boolean = did(self, BotAction.Jump)

    override fun setSneaking(self: LuaHandle.Bot, sneaking: Boolean): Boolean = did(self, BotAction.Sneak(sneaking))

    override fun setSprinting(self: LuaHandle.Bot, sprinting: Boolean): Boolean = did(self, BotAction.Sprint(sprinting))

    override fun chat(self: LuaHandle.Bot, message: String): Boolean = did(self, BotAction.Chat(message))

    override fun attack(self: LuaHandle.Bot, target: LuaHandle): Boolean {
        val (entity, aim) = aimAt(target) ?: return false
        return did(self, BotAction.Attack(entity = entity), aim)
    }

    override fun interact(self: LuaHandle.Bot, target: LuaHandle, options: BotInteractOptions?): Boolean {
        val (entity, aim) = aimAt(target) ?: return false
        val at = options?.at
        val hand = hand(options?.offHand)
        return did(self, BotAction.Interact(entity = entity, hand = hand, x = at?.x, y = at?.y, z = at?.z), aim)
    }

    /**
     * What a click on [target] names: an entity by UUID, or for a centity's
     * node, the runtime's aim at its hitbox (its interaction entity and middle,
     * which only the runtime knows). Null when it's gone or has no hitbox.
     */
    private fun aimAt(target: LuaHandle): Pair<String?, BotAim?>? = when (target) {
        is LuaHandle.Entity -> target.uuidOrNull()?.let { it.toString() to null }
        is LuaHandle.Node -> {
            val instance = session.instance(LuaHandle.Centity(target.centity))
            instance?.takeIf { it.indexOf(target.name) != null }
                ?.let { session.centities.aim(it, target.name) }
                ?.let { (entity, at) -> null to BotAim(entity, at) }
        }
        else -> null
    }

    override fun useItem(self: LuaHandle.Bot, offHand: Boolean?): Boolean = did(self, BotAction.UseItem(hand(offHand)))

    override fun releaseItem(self: LuaHandle.Bot): Boolean = did(self, BotAction.ReleaseItem)

    override fun swing(self: LuaHandle.Bot): Boolean = did(self, BotAction.Swing)

    override fun useBlock(self: LuaHandle.Bot, position: Vec3, face: String?, offHand: Boolean?): Boolean =
        did(self, BotAction.UseBlock(block(position.x), block(position.y), block(position.z), face ?: "up", hand(offHand)))

    override fun breakBlock(self: LuaHandle.Bot, position: Vec3): CompletionStage<Boolean> {
        val (x, y, z) = Triple(block(position.x), block(position.y), block(position.z))
        val face = self.uuidOrNull()?.let { faceToward(it, x, y, z) } ?: "up"
        return act(self, BotAction.BreakBlock(x, y, z, face)).thenApply { true }
    }

    /** The face of the block at [x], [y], [z] that faces the bot [id]'s eyes, as a player mines it. */
    private fun faceToward(id: UUID, x: Int, y: Int, z: Int): String? {
        val eye = session.platform.players.eye(id) ?: return null
        val dx = eye.x - (x + 0.5)
        val dy = eye.y - (y + 0.5)
        val dz = eye.z - (z + 0.5)
        return when {
            abs(dy) >= abs(dx) && abs(dy) >= abs(dz) -> if (dy > 0) "up" else "down"
            abs(dx) >= abs(dz) -> if (dx > 0) "east" else "west"
            else -> if (dz > 0) "south" else "north"
        }
    }

    override fun swapHands(self: LuaHandle.Bot): Boolean = did(self, BotAction.SwapHands)

    override fun dropItem(self: LuaHandle.Bot, stack: Boolean?): Boolean = did(self, BotAction.Drop(all = stack == true))

    override fun clickSlot(self: LuaHandle.Bot, slot: Long, click: String?): Boolean {
        val kind = when (click ?: "left") {
            "left" -> BotClick.LEFT
            "right" -> BotClick.RIGHT
            "shift_left" -> BotClick.SHIFT_LEFT
            "shift_right" -> BotClick.SHIFT_RIGHT
            "drop" -> BotClick.DROP
            "drop_all" -> BotClick.DROP_ALL
            "double" -> BotClick.DOUBLE
            "swap_offhand" -> BotClick.OFF_HAND
            else -> error("the binding checked click: $click")
        }
        if (slot !in 0..Int.MAX_VALUE) return false
        return did(self, BotAction.ClickSlot(slot.toInt(), kind))
    }

    override fun respawn(self: LuaHandle.Bot): Boolean = did(self, BotAction.Respawn)

    private fun act(self: LuaHandle.Bot, action: BotAction) =
        self.uuidOrNull()?.let { bots.act(it, action) } ?: CompletableFuture.failedFuture(WorkFailed("the bot has left"))

    private fun hand(offHand: Boolean?) = if (offHand == true) BotHand.OFF else BotHand.MAIN

    private fun block(coordinate: Double) = floor(coordinate).toInt()
}
