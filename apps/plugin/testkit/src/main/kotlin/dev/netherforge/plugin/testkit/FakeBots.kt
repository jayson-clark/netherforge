package dev.netherforge.plugin.testkit

import dev.netherforge.format.bridge.BotActResult
import dev.netherforge.format.bridge.BotAction
import dev.netherforge.format.bridge.BotBossBar
import dev.netherforge.format.bridge.BotClick
import dev.netherforge.format.bridge.BotDialog
import dev.netherforge.format.bridge.BotDialogInput
import dev.netherforge.format.bridge.BotEntity
import dev.netherforge.format.bridge.BotEvent
import dev.netherforge.format.bridge.BotEvents
import dev.netherforge.format.bridge.BotHand
import dev.netherforge.format.bridge.BotInfo
import dev.netherforge.format.bridge.BotItem
import dev.netherforge.format.bridge.BotListEntry
import dev.netherforge.format.bridge.BotMenu
import dev.netherforge.format.bridge.BotPack
import dev.netherforge.format.bridge.BotPackAnswer
import dev.netherforge.format.bridge.BotSidebar
import dev.netherforge.format.bridge.BotState
import dev.netherforge.format.bridge.BotTarget
import dev.netherforge.format.bridge.BotTargetKind
import dev.netherforge.format.dialog.BooleanInput
import dev.netherforge.format.dialog.DialogInput
import dev.netherforge.format.dialog.DialogType
import dev.netherforge.format.dialog.OptionInput
import dev.netherforge.format.dialog.RangeInput
import dev.netherforge.format.dialog.TextInput
import dev.netherforge.format.game.Box
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.game.has
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.menu.MenuType
import dev.netherforge.plugin.platform.BlockRef
import dev.netherforge.plugin.platform.BotAim
import dev.netherforge.plugin.platform.BotJoin
import dev.netherforge.plugin.platform.BotOps
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.DialogSpec
import dev.netherforge.plugin.platform.EntityNumber
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.PackOffer
import dev.netherforge.plugin.platform.Ray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Bots on the fake server: each is a [FakePlayer] once it's in,
 * online like anyone, and answers resource packs as it was told to. What a
 * bot does raises what the Paper adapter raises for the same packets, in the
 * same order, through the fake's own players, entities and blocks: the
 * `BotActionsContract` suite holds the two to that. What the fake has no
 * model of (signs, enchanting, bows, a menu of the server's own) is an
 * error saying so, never a silent success.
 *
 * Like the real ones, a join finishes later, on the main thread (here, the
 * scheduler's next [FakeScheduler.runPending]); an action is done at once.
 */
class FakeBots(private val platform: FakePlatform) : BotOps {
    private val online = linkedMapOf<String, FakePlayer>()

    /** What each bot online has been sent, by its player's UUID: a new one each join, as a real bot is a new client. */
    private val screens = HashMap<UUID, Screen>()

    /** A bot's own view of what the server sent it: its events, numbered from 0 (the last [KEPT]), and the packs offered. */
    private class Screen {
        val kept = ArrayDeque<BotEvent>()
        var next = 0
        val packs = LinkedHashMap<UUID, BotPack>()

        fun add(event: (seq: Int) -> BotEvent) {
            kept.addLast(event(next++))
            while (kept.size > KEPT) kept.removeFirst()
        }

        fun since(seq: Int): BotEvents {
            val first = kept.firstOrNull()?.seq ?: next
            return BotEvents(kept.filter { it.seq >= seq }, next, dropped = (first - seq).coerceAtLeast(0).coerceAtMost(next))
        }
    }

    /**
     * The fake's packets: what the server sends [player] reaches their client
     * as [event], when they're a bot (anyone else has no client here). The
     * fake's sounds, particles, boss bars and resource packs call it where
     * Paper would send the packet the real bots record.
     */
    fun sent(player: UUID, event: (seq: Int) -> BotEvent) {
        screens[player]?.add(event)
    }

    /** [player]'s client was offered [pack] and answered as it was told to ([FakePlayer.packAnswer]): the real bots' answers. */
    fun offered(player: FakePlayer, pack: PackOffer) {
        val screen = screens[player.ref.uuid] ?: return
        val status = when (player.packAnswer) {
            "loaded" -> "successfully_loaded"
            "declined" -> "declined"
            "failed" -> "failed_download"
            else -> "offered"
        }
        screen.packs[pack.id] = BotPack(pack.id.toString(), pack.url, pack.sha1, status)
        screen.add { BotEvent.ResourcePack(it, pack.id.toString(), pack.url, status) }
    }

    /** Every action asked for, with where the runtime aimed it. */
    val acted = mutableListOf<Triple<String, BotAction, BotAim?>>()

    private val players get() = platform.players
    private val raise get() = platform.raise
    private val events get() = platform.events

    /** The joins asked for, in order: what a test reads a script bot's options back from. */
    val joins = mutableListOf<BotJoin>()

    /** Names asked to join that haven't yet: [leave] stops one. */
    private val joining = HashSet<String>()

    /** The script bots online, by UUID: never [FakePlayers.known] once they've gone, since a real one is never saved. */
    val scripted = HashSet<UUID>()

    override fun join(join: BotJoin, done: (Result<BotInfo>) -> Unit) {
        val name = join.name
        require(NAME.matches(name)) { "a bot's name is 3 to 16 letters, digits or _, not \"$name\"" }
        require(platform.players.find(name) == null && name !in joining) { "$name is already online" }
        val spawn = platform.worlds.spawnLocation(platform.worlds.defaultWorld())!!
        val at = join.at
        val location = at?.let { Location(it.world ?: spawn.world, it.x, it.y, it.z, it.yaw ?: 0.0, it.pitch ?: 0.0) } ?: spawn
        joins += join
        joining += name
        platform.scheduler.runOnMain {
            if (!joining.remove(name)) {
                done(Result.failure(IllegalStateException("$name couldn't join: it left")))
                return@runOnMain
            }
            val uuid = if (join.script != null) UUID.randomUUID() else null
            val firstJoin = uuid != null || platform.players.known(name) == null
            val player = platform.players.add(name, location, uuid)
            player.packAnswer = when (join.pack) {
                BotPackAnswer.ACCEPT, BotPackAnswer.LOADED -> "loaded"
                BotPackAnswer.DECLINE -> "declined"
                BotPackAnswer.FAIL -> "failed"
                BotPackAnswer.IGNORE -> null
            }
            online[name] = player
            if (uuid != null) scripted += uuid
            screens[player.ref.uuid] = Screen()
            platform.raise.playerJoin(GameEvent.PlayerJoin(player.ref, firstJoin, "$name joined the game"))
            done(Result.success(info(player)))
        }
    }

    override fun leave(name: String) {
        if (joining.remove(name)) return
        val bot = bot(name)
        platform.players.quit(bot)
        gone(bot)
    }

    /** A bot that left: a script's is forgotten as a real one is, never saved. */
    private fun gone(bot: FakePlayer) {
        online.remove(bot.ref.name)
        screens.remove(bot.ref.uuid)
        if (scripted.remove(bot.ref.uuid)) platform.players.forget(bot.ref.uuid)
    }

    override fun isBot(id: UUID): Boolean = online.values.any { it.ref.uuid == id && id in players.byId }

    override fun leaveAll() {
        joining.clear()
        for (player in online.values.toList()) {
            if (player.ref.uuid in platform.players.byId) platform.players.quit(player)
            gone(player)
        }
    }

    override fun list(): List<BotInfo> = online.values.filter { it.ref.uuid in platform.players.byId }.map(::info)

    /**
     * Does [action] at once, as the real bots do whatever takes no ticks. A
     * walk or a mine finishes at once here too: the fake has no ticks of its
     * own to spread them over.
     */
    override fun act(name: String, action: BotAction, aim: BotAim?, done: (Result<BotActResult>) -> Unit) {
        val bot = bot(name)
        acted += Triple(name, action, aim)
        val outcome = runCatching {
            check(bot.ref.uuid in players.byId) { "$name is gone" }
            perform(bot, action, aim)
            val at = bot.location
            BotActResult(at.world, at.x, at.y, at.z, since = 0)
        }
        done(outcome)
    }

    private fun perform(bot: FakePlayer, action: BotAction, aim: BotAim?) {
        fun alive() = check(!bot.dead) { "${bot.ref.name} is dead; respawn first" }
        when (action) {
            is BotAction.Teleport -> {
                val world = action.world ?: bot.location.world
                require(platform.worlds.exists(world)) { "no world \"$world\"" }
                val to = Location(world, action.x, action.y, action.z, action.yaw ?: bot.location.yaw, action.pitch ?: bot.location.pitch)
                check(players.teleport(bot, to, "plugin")) { "the server refused to teleport ${bot.ref.name}" }
            }
            is BotAction.Look -> bot.location = bot.location.copy(yaw = action.yaw, pitch = action.pitch.coerceIn(-90.0, 90.0))
            is BotAction.LookAt -> lookAt(bot, action.x, action.y, action.z)
            is BotAction.WalkTo -> {
                alive()
                walk(bot, action)
            }
            is BotAction.Jump -> {
                alive()
                // The client presses jump, leaves the ground on its next move, and lets go once it lands.
                input(bot, jump = true)
                val from = bot.location
                if (!raise.playerJump(GameEvent.PlayerJump(bot.ref, from, from.offset(0.0, JUMP, 0.0)))) bot.location = from
                input(bot)
            }
            is BotAction.Sneak -> if (action.on != bot.sneaking) {
                // The keys first, then the sneak they make (as Paper reads the input packet).
                input(bot, sneak = action.on)
                raise.playerSneak(GameEvent.PlayerSneak(bot.ref, action.on))
                bot.sneaking = action.on
            }
            is BotAction.Sprint -> sprint(bot, action.on)
            is BotAction.Chat -> players.chat(bot, action.message)
            is BotAction.Command -> {
                val line = action.line.trim().removePrefix("/")
                if (!raise.playerCommand(GameEvent.PlayerCommand(bot.ref, line))) {
                    bot.commands += line
                    platform.commands.dispatch(platform.commands.sender(bot), line)
                }
            }
            is BotAction.Interact -> {
                alive()
                val (target, at) = target(bot, action.entity, aim)
                aimed(bot, target, action.entity == null && aim == null, action.at?.let { Triple(it.x, it.y, it.z) } ?: at)
                val hand = hand(action.hand)
                if (target in platform.entities.all) {
                    // A centity's: the click goes to it, the main hand's only.
                    if (hand == "main_hand") events!!.entityClicked(target, bot.ref, ClickButton.RIGHT, sight(bot))
                } else {
                    events!!.playerInteractEntity(bot.ref, target, hand)
                }
            }
            is BotAction.Attack -> {
                alive()
                val (target, at) = target(bot, action.entity, aim)
                aimed(bot, target, action.entity == null && aim == null, action.at?.let { Triple(it.x, it.y, it.z) } ?: at)
                if (target in platform.entities.all) {
                    events!!.entityClicked(target, bot.ref, ClickButton.LEFT, sight(bot))
                } else {
                    platform.worldEntities.body(target)?.takeIf { it.living }?.let {
                        platform.worldEntities.hurt(it, FIST, "entity_attack", bot.ref.uuid)
                    }
                }
                raise.playerSwing(GameEvent.PlayerSwing(bot.ref, "main_hand"))
            }
            is BotAction.Swing -> {
                // An arm swing at no block: a left click at the air first, as Paper raises it.
                events!!.playerInteract(bot.ref, ClickButton.LEFT, null, null, held(bot, "main_hand"), "main_hand")
                raise.playerSwing(GameEvent.PlayerSwing(bot.ref, "main_hand"))
            }
            is BotAction.UseItem -> {
                alive()
                val hand = hand(action.hand)
                val item = held(bot, hand) ?: return
                if (events!!.playerInteract(bot.ref, ClickButton.RIGHT, null, null, item, hand)) return
                if (events!!.playerUseItem(bot.ref, item, hand)) return
                startUsing(bot, item, hand)
            }
            is BotAction.ReleaseItem -> {
                alive()
                val use = bot.using ?: return
                bot.using = null
                raise.playerStopUsingItem(GameEvent.PlayerStopUsingItem(bot.ref, use.item, use.total - use.left))
            }
            is BotAction.Fly -> {
                alive()
                // The client only says whether it's flying; the server listens to a player who may.
                if (bot.mayFly && bot.flying != action.on && !raise.playerFly(GameEvent.PlayerFly(bot.ref, action.on))) {
                    bot.flying = action.on
                }
            }
            is BotAction.UseBlock -> {
                alive()
                useBlock(bot, action)
            }
            is BotAction.BreakBlock -> {
                alive()
                breakBlock(bot, action)
            }
            is BotAction.PickBlock -> {
                alive()
                pickBlock(bot, action)
            }
            is BotAction.SelectSlot -> {
                require(action.slot in 0..8) { "a hotbar slot is 0 to 8" }
                val from = (bot.numbers[EntityNumber.HELD_SLOT] ?: 0.0).toInt()
                if (from != action.slot && !raise.playerChangeSlot(GameEvent.PlayerChangeSlot(bot.ref, from, action.slot))) {
                    bot.numbers[EntityNumber.HELD_SLOT] = action.slot.toDouble()
                }
            }
            is BotAction.SwapHands -> {
                alive()
                val main = heldSlot(bot)
                val slots = bot.inventorySlots
                if (!raise.playerSwapHands(GameEvent.PlayerSwapHands(bot.ref, slots[main], slots[OFF_HAND], false))) {
                    slots[main] = slots[OFF_HAND].also { slots[OFF_HAND] = slots[main] }
                }
            }
            is BotAction.Drop -> {
                alive()
                drop(bot, action.all)
            }
            is BotAction.ClickSlot -> click(bot, action)
            is BotAction.Drag -> drag(bot, action)
            is BotAction.CloseMenu -> closeMenu(bot)
            is BotAction.DialogButton -> {
                val shown = platform.dialogs.showing[bot.ref.uuid] ?: throw IllegalStateException("${bot.ref.name} has no dialog open")
                val buttons = shown.file.buttons
                val button = when {
                    action.index != null -> buttons.getOrNull(action.index!!)
                        ?: throw IllegalArgumentException("the dialog has ${buttons.size} buttons; there's no button ${action.index}")
                    action.button != null -> buttons.firstOrNull {
                        platform.text.strip(it.label ?: it.key) == action.button ||
                            it.key == action.button
                    }
                        ?: throw IllegalArgumentException("the dialog has no button \"${action.button}\"")
                    else -> throw IllegalArgumentException("name the button by `button` (its label) or `index`")
                }
                // Every input's answer goes with the press, as the client sends them: what was typed, else where it started.
                val values = shown.file.inputs.associate { input ->
                    val given = action.inputs[input.key]?.let {
                        it as? JsonPrimitive ?: throw IllegalArgumentException("an input's value is a string, number or boolean")
                    }
                    input.key to answer(input, given)
                }
                platform.dialogs.press(bot, button.key, values)
            }
            is BotAction.DialogClose -> platform.dialogs.escape(bot)
            // A walk or a mine is done at once here, so there's never anything to stop.
            is BotAction.Stop -> {}
            is BotAction.Respawn -> {
                check(bot.dead) { "${bot.ref.name} isn't dead" }
                respawn(bot)
            }
            is BotAction.EditSign -> unsupported("signs")
            is BotAction.ClickButton -> unsupported("menu buttons (enchanting, stonecutting)")
        }
    }

    // ---- moving -----------------------------------------------------------------------

    /** The keys held: what a bot's client sends when they change (heard only while input is watched). */
    private fun input(bot: FakePlayer, forward: Boolean = false, jump: Boolean = false, sneak: Boolean = bot.sneaking) {
        raise.playerInput(GameEvent.PlayerInput(bot.ref, forward, false, false, false, jump, sneak, bot.sprinting))
    }

    private fun sprint(bot: FakePlayer, on: Boolean) {
        if (bot.sprinting == on) return
        bot.sprinting = on
        raise.playerSprint(GameEvent.PlayerSprint(bot.ref, on))
        input(bot)
    }

    /**
     * A walk in a straight line, a client's step at a time: each move into
     * another block heard (while moves are watched). One a script cancels
     * puts them back, and a client walking into it again is stuck.
     */
    private fun walk(bot: FakePlayer, walk: BotAction.WalkTo) {
        require(walk.timeoutTicks in 1..BotAction.MAX_TICKS) { "timeoutTicks is from 1 to ${BotAction.MAX_TICKS}" }
        val wasSprinting = bot.sprinting
        if (walk.sprint) sprint(bot, true)
        input(bot, forward = true)
        try {
            val speed = if (bot.sprinting) {
                SPRINT_SPEED
            } else if (bot.sneaking) {
                SNEAK_SPEED
            } else {
                WALK_SPEED
            }
            var ticks = 0
            while (true) {
                val at = bot.location
                val dx = walk.x - at.x
                val dz = walk.z - at.z
                val distance = hypot(dx, dz)
                if (distance < ARRIVED) break
                check(++ticks <= walk.timeoutTicks) { "${bot.ref.name} stopped short of ${walk.x}, ${walk.z} (out of time)" }
                val step = minOf(speed, distance)
                val yaw = Math.toDegrees(atan2(-dx, dz))
                val to = at.copy(x = at.x + dx / distance * step, z = at.z + dz / distance * step, yaw = yaw)
                check(players.move(bot, to)) { "${bot.ref.name} stopped short of ${walk.x}, ${walk.z} (stuck)" }
            }
        } finally {
            input(bot)
            sprint(bot, wasSprinting)
        }
    }

    private fun lookAt(bot: FakePlayer, x: Double, y: Double, z: Double) {
        val at = bot.location
        val dx = x - at.x
        val dy = y - (at.y + bot.eyeHeight)
        val dz = z - at.z
        bot.location = at.copy(yaw = Math.toDegrees(atan2(-dx, dz)), pitch = Math.toDegrees(-atan2(dy, hypot(dx, dz))))
    }

    private fun sight(bot: FakePlayer): Ray? = players.eye(bot.ref.uuid)

    private fun eye(bot: FakePlayer) = bot.location.let { Triple(it.x, it.y + bot.eyeHeight, it.z) }

    // ---- clicking entities ------------------------------------------------------------------

    /** The entity to click and the point to aim at, as the real bots choose: the runtime's aim, one by UUID, or the one in sight. */
    private fun target(bot: FakePlayer, uuid: String?, aim: BotAim?): Pair<UUID, Triple<Double, Double, Double>?> {
        val id = aim?.entity ?: uuid?.let {
            runCatching { UUID.fromString(it) }.getOrNull() ?: throw IllegalArgumentException("\"$it\" isn't a UUID")
        }
        if (id == null) {
            // What's in its crosshair, NetherForge's hitboxes included, as the real bots pick it.
            val hit = crosshair(bot)?.entity?.let(UUID::fromString)
                ?: throw IllegalStateException("${bot.ref.name} isn't looking at anything it could click within reach")
            return hit to null
        }
        val tagged = platform.entities.all[id]
        val body = platform.worldEntities.body(id)
        check(tagged != null || body != null) { "there's no entity $id in ${bot.location.world}" }
        val hidden = tagged?.hiddenFrom?.contains(bot.ref.uuid) ?: body!!.hiddenFrom.contains(bot.ref.uuid)
        check(!hidden) { "${bot.ref.name}'s client doesn't know entity $id (it's hidden from them, or too far away to be sent)" }
        return id to aim?.at?.let { Triple(it.x, it.y, it.z) }
    }

    /** As the real bots aim a click: one picked from the crosshair with no point keeps its look; otherwise [aimAt]. */
    private fun aimed(bot: FakePlayer, target: UUID, inSight: Boolean, at: Triple<Double, Double, Double>?) {
        if (!inSight || at != null) aimAt(bot, target, at)
    }

    /** Looks at [target] ([at], or its middle) and checks it's within reach of the eyes, as the real bots do. */
    private fun aimAt(bot: FakePlayer, target: UUID, at: Triple<Double, Double, Double>?) {
        val tagged = platform.entities.all[target]
        val (where, width, height) = tagged?.let { Triple(it.location, it.width, it.height) }
            ?: platform.worldEntities.body(target)!!.let { Triple(it.location, it.width, it.height) }
        val (x, y, z) = at ?: Triple(where.x, where.y + height / 2, where.z)
        lookAt(bot, x, y, z)
        val (ex, ey, ez) = eye(bot)
        val half = width / 2
        val nearest =
            Triple(
                ex.coerceIn(where.x - half, where.x + half),
                ey.coerceIn(where.y, where.y + height),
                ez.coerceIn(
                    where.z - half,
                    where.z + half
                )
            )
        val distance = sqrt((nearest.first - ex).sq() + (nearest.second - ey).sq() + (nearest.third - ez).sq())
        require(distance <= REACH) { "it is ${"%.1f".format(distance)} blocks away; ${bot.ref.name} reaches $REACH" }
    }

    // ---- items and blocks ------------------------------------------------------------------

    private fun hand(hand: BotHand) = if (hand == BotHand.MAIN) "main_hand" else "off_hand"

    private fun heldSlot(bot: FakePlayer) = (bot.numbers[EntityNumber.HELD_SLOT] ?: 0.0).toInt()

    private fun slotOf(bot: FakePlayer, hand: String) = if (hand == "off_hand") OFF_HAND else heldSlot(bot)

    private fun held(bot: FakePlayer, hand: String) = bot.inventorySlots[slotOf(bot, hand)]

    /** One fewer of what's in [slot] (a survival player's; creative keeps it). */
    private fun useUp(bot: FakePlayer, slot: Int) {
        if (bot.gameMode == "creative") return
        val item = bot.inventorySlots[slot] ?: return
        val count = (item.def.count ?: 1) - 1
        bot.inventorySlots[slot] = if (count > 0) item.copy(def = item.def.copy(count = count)) else null
    }

    /** Food starts being eaten (when they may eat it); anything else the fake has no use over time for. */
    private fun startUsing(bot: FakePlayer, item: ItemData, hand: String) {
        val kind = item.def.kind
        if (kind !in FakePlatform.FOODS) return
        val hungry = (bot.numbers[EntityNumber.FOOD] ?: 20.0) < 20.0
        if (!hungry && kind !in FakePlatform.ALWAYS_EDIBLE && bot.gameMode != "creative") return
        bot.using = FakePlayer.Using(item, hand, FakePlatform.EATING_TICKS, FakePlatform.EATING_TICKS)
    }

    private fun block(world: String, x: Int, y: Int, z: Int): BlockRef {
        val state = platform.worlds.state(world, x, y, z)
        return BlockRef(world, x, y, z, state.substringBefore('['), state)
    }

    /** The middle of a block's face, checked to be within reach and looked at, as the real bots do. */
    private fun face(bot: FakePlayer, x: Int, y: Int, z: Int, face: String): Triple<Int, Int, Int> {
        val step = FACES[face] ?: throw IllegalArgumentException("\"$face\" isn't a face; one of ${FACES.keys.joinToString()}")
        val hit = Triple(x + 0.5 + step.first * 0.5, y + 0.5 + step.second * 0.5, z + 0.5 + step.third * 0.5)
        val (ex, ey, ez) = eye(bot)
        val distance = sqrt((hit.first - ex).sq() + (hit.second - ey).sq() + (hit.third - ez).sq())
        require(distance <= BLOCK_REACH) { "that block is ${"%.1f".format(distance)} blocks away; ${bot.ref.name} reaches $BLOCK_REACH" }
        lookAt(bot, hit.first, hit.second, hit.third)
        return step
    }

    /**
     * A right click on a block: heard as a click (cancelled, that's all), the
     * held item used, then a container opened or the held block placed
     * against that face, as the server does each.
     */
    private fun useBlock(bot: FakePlayer, use: BotAction.UseBlock) {
        val world = bot.location.world
        val step = face(bot, use.x, use.y, use.z, use.face)
        val hand = hand(use.hand)
        val clicked = block(world, use.x, use.y, use.z)
        val item = held(bot, hand)
        if (events!!.playerInteract(bot.ref, ClickButton.RIGHT, clicked, use.face, item, hand)) return
        if (item != null && events!!.playerUseItem(bot.ref, item, hand)) return
        if (clicked.id in CONTAINERS && !bot.sneaking) {
            val inventory = InventoryRef.Block(world, use.x, use.y, use.z)
            val opened = GameEvent.PlayerInventory(bot.ref, clicked.id.substringAfter(':'), inventory, clicked)
            if (!raise.playerOpenInventory(opened)) platform.inventories.open(inventory, bot.ref.uuid)
            return
        }
        val kind = item?.def?.kind ?: return
        if (platform.game.block(kind) == null) return
        val (x, y, z) = Triple(use.x + step.first, use.y + step.second, use.z + step.third)
        if (platform.worlds.state(world, x, y, z) != "minecraft:air") return
        val state = platform.game.block(kind)!!.let { info ->
            if (info.defaults.isEmpty()) {
                kind
            } else {
                "$kind[${info.defaults.entries.sortedBy {
                    it.key
                }.joinToString(",") { "${it.key}=${it.value}" }}]"
            }
        }
        val placed = GameEvent.BlockPlace(bot.ref, BlockRef(world, x, y, z, kind, state), state, clicked)
        if (raise.blockPlace(placed)) return
        platform.blocks.set(world, x, y, z, state, true)
        useUp(bot, slotOf(bot, hand))
    }

    /**
     * Mining, as the server hears it: the left click, the first hit (an
     * instant one in creative), then the break with its drops. Any of them
     * cancelled leaves the block, and the bot is refused as a real one is.
     * One that can't be mined (bedrock) it lets go of, which is heard.
     */
    private fun breakBlock(bot: FakePlayer, mine: BotAction.BreakBlock) {
        val world = bot.location.world
        val target = block(world, mine.x, mine.y, mine.z)
        require(target.id != "minecraft:air") { "there's no block at ${mine.x} ${mine.y} ${mine.z} to break" }
        face(bot, mine.x, mine.y, mine.z, mine.face)
        val item = held(bot, "main_hand")
        val refused = IllegalStateException("the server didn't let ${bot.ref.name} break the block at ${mine.x} ${mine.y} ${mine.z}")
        if (events!!.playerInteract(bot.ref, ClickButton.LEFT, target, mine.face, item, "main_hand")) throw refused
        val creative = bot.gameMode == "creative"
        if (raise.blockStartBreak(GameEvent.BlockStartBreak(bot.ref, target, item, creative))) throw refused
        if (!creative && (platform.blocks.hardness(target.state) ?: 0.0) < 0) {
            // It gives up, and lets go as a player does.
            events!!.blockStopBreak(bot.ref, target)
            throw IllegalStateException("${bot.ref.name} can't break the block at ${mine.x} ${mine.y} ${mine.z}")
        }
        val drops = if (creative ||
            !isItem(target.id)
        ) {
            emptyList()
        } else {
            listOf(ItemData(dev.netherforge.format.item.ItemDef(kind = target.id, count = 1)))
        }
        val answer = events!!.blockBreak(bot.ref, target, { drops }, 0) ?: throw refused
        platform.worlds.blocks[BlockAt(world, mine.x, mine.y, mine.z)] = "minecraft:air"
        platform.blocks.changes += "$world ${mine.x} ${mine.y} ${mine.z} minecraft:air"
        if (creative) return
        val dropped = answer.drops ?: drops
        if (raise.blockDropItem(GameEvent.BlockDropItem(bot.ref, target, target.state) { dropped })) return
        val middle = dev.netherforge.format.math.Vec3(mine.x + 0.5, mine.y + 0.5, mine.z + 0.5)
        for (stack in dropped) platform.worldEntities.spawnItem(world, middle, stack)
    }

    /**
     * A middle click on a block, as the server does it: what the runtime answers is brought to hand as the game
     * brings a block's item (selected in the hotbar, swapped in from elsewhere, or put in a hotbar slot); left to
     * the game, a survival player with none of the block's item gets nothing, which is all a test needs.
     */
    private fun pickBlock(bot: FakePlayer, pick: BotAction.PickBlock) {
        val target = block(bot.location.world, pick.x, pick.y, pick.z)
        val slots = bot.inventorySlots
        val answer = events!!.pickBlock(bot.ref, target, (0 until PICKABLE).map { slots[it] }) ?: return
        val held = heldSlot(bot)
        val hotbar = (0 until HOTBAR).map { (held + it) % HOTBAR }
        val into = hotbar.firstOrNull { slots[it] == null } ?: held
        val source = answer.slot
        val to = when {
            source != null && source < HOTBAR -> source
            source != null -> into.also {
                val was = slots[it]
                slots[it] = slots[source]
                slots[source] = was
            }
            answer.item != null -> into.also {
                slots[it]?.let { was -> (0 until PICKABLE).firstOrNull { free -> slots[free] == null }?.let { free -> slots[free] = was } }
                slots[it] = answer.item
            }
            else -> return
        }
        bot.numbers[EntityNumber.HELD_SLOT] = to.toDouble()
    }

    /** Q: one of the held stack, or all of it, heard first (cancelled, it stays), then thrown ahead of them. */
    private fun drop(bot: FakePlayer, all: Boolean) {
        val slot = heldSlot(bot)
        val item = bot.inventorySlots[slot] ?: return
        val count = item.def.count ?: 1
        val thrown = if (all) count else 1
        val dropped = item.copy(def = item.def.copy(count = thrown))
        if (events!!.playerDropItem(bot.ref, dropped)) return
        bot.inventorySlots[slot] = if (thrown < count) item.copy(def = item.def.copy(count = count - thrown)) else null
        val at = bot.location
        val yaw = Math.toRadians(at.yaw)
        val ahead = dev.netherforge.format.math.Vec3(at.x - sin(yaw) * THROW, at.y + bot.eyeHeight - 0.3, at.z + cos(yaw) * THROW)
        platform.worldEntities.spawnItem(at.world, ahead, dropped)?.let { id ->
            platform.worldEntities.body(id)?.numbers?.set(EntityNumber.PICKUP_DELAY, PLAYER_DROP_DELAY)
        }
    }

    /** A click in a project menu: the menu's own slots first, then the inventory below it. */
    private fun clickName(click: BotClick) = when (click) {
        BotClick.HOTBAR -> "number_key"
        BotClick.DROP_ALL -> "control_drop"
        BotClick.OFF_HAND -> "swap_offhand"
        else -> click.name.lowercase()
    }

    /** What a swap-hands key over [held] that was let through is heard as, after the click. */
    private fun swappedOver(bot: FakePlayer, held: ItemData?) {
        raise.playerSwapHands(GameEvent.PlayerSwapHands(bot.ref, held, bot.inventorySlots[OFF_HAND], true))
    }

    /**
     * Raw slot [slot] of the screen with no project menu open, as Paper's click names it: in the vanilla inventory
     * open (null), or the crafting grid, its own slots first; below it the bot's inventory, its main rows then the
     * hotbar. With only its own open, the game's inventory screen: result and grid 0 to 4, armour 5 (head) to 8,
     * main rows 9 to 35, hotbar 36 to 44, off hand 45. The inventory clicked, whether it's the bot's own, and the index.
     */
    private fun inventorySlot(bot: FakePlayer, slot: Int): Triple<InventoryRef?, Boolean, Int> {
        val open = platform.inventories.open[bot.ref.uuid]
        val own = InventoryRef.Player(bot.ref.uuid)
        if (open != null) {
            val size = platform.inventories.size(open) ?: 0
            if (slot < size) return Triple(open, false, slot)
            val below = slot - size
            return Triple(own, true, if (below < MAIN_ROWS) below + HOTBAR else below - MAIN_ROWS)
        }
        return when (slot) {
            in 0 until CRAFTING_SLOTS -> Triple(null, false, slot)
            in CRAFTING_SLOTS until HOTBAR -> Triple(own, true, HEAD - (slot - CRAFTING_SLOTS))
            in HOTBAR until HOTBAR + MAIN_ROWS -> Triple(own, true, slot)
            in HOTBAR + MAIN_ROWS until HOTBAR + MAIN_ROWS + HOTBAR -> Triple(own, true, slot - HOTBAR - MAIN_ROWS)
            HOTBAR + MAIN_ROWS + HOTBAR -> Triple(own, true, OFF_HAND)
            else -> throw IllegalArgumentException("slot $slot isn't in the inventory (slots 0 to ${HOTBAR + MAIN_ROWS + HOTBAR})")
        }
    }

    private fun kindOpen(bot: FakePlayer) = platform.inventories.open[bot.ref.uuid]?.let { platform.inventories.kind(it) } ?: "crafting"

    /** A click with no project menu open: in its own inventory or the vanilla one it has open, heard as Paper's is. */
    private fun inventoryClick(bot: FakePlayer, click: BotAction.ClickSlot) {
        val (inventory, own, index) = inventorySlot(bot, click.slot)
        val held = inventory?.let { platform.inventories.item(it, index) }
        val hotbar = click.hotbar.takeIf { click.click == BotClick.HOTBAR }
        val heard = GameEvent.PlayerClickInventory(
            bot.ref,
            kindOpen(
                bot
            ),
            inventory, own, index, clickName(click.click), hotbar, held, null
        )
        if (!raise.playerClickInventory(heard) && click.click == BotClick.OFF_HAND) swappedOver(bot, held)
    }

    /** A drag: the project menu's when one is open, else in its own inventory or the vanilla one it has open. */
    private fun drag(bot: FakePlayer, drag: BotAction.Drag) {
        val button = drag.button.name.lowercase()
        val window = platform.menus.viewing(bot.ref.uuid)
        if (window != null) {
            val size = platform.menus.windows.getValue(window).slots.size
            val (top, below) = drag.slots.partition { it < size }
            val own = below.map { (it - size).let { b -> if (b < MAIN_ROWS) b + HOTBAR else b - MAIN_ROWS } }
            platform.menus.drag(bot, top, ownSlots = own, click = button)
            return
        }
        val slots = drag.slots.map { inventorySlot(bot, it) }
        val inventory = platform.inventories.open[bot.ref.uuid]
        raise.playerDragInventory(
            GameEvent.PlayerDragInventory(
                bot.ref,
                kindOpen(bot),
                inventory,
                slots.filter { !it.second }.map { it.third },
                slots.filter { it.second }.map { it.third },
                button,
                null
            )
        )
    }

    private fun click(bot: FakePlayer, click: BotAction.ClickSlot) {
        val window = platform.menus.viewing(bot.ref.uuid) ?: return inventoryClick(bot, click)
        val size = platform.menus.windows.getValue(window).slots.size
        val top = click.slot < size
        val name = clickName(click.click)
        // Below the menu, the player's inventory as Paper numbers it: its main rows from 9, then the hotbar from 0.
        val below = click.slot - size
        val slot = if (top) {
            click.slot
        } else if (below < MAIN_ROWS) {
            below + HOTBAR
        } else {
            below - MAIN_ROWS
        }
        // From below, only what moves a stack across: a shift-click on one, or gathering onto the cursor.
        val shift = click.click == BotClick.SHIFT_LEFT || click.click == BotClick.SHIFT_RIGHT
        val moves = top || (shift && bot.inventorySlots[slot] != null) || click.click == BotClick.DOUBLE
        val hotbar = click.hotbar.takeIf { click.click == BotClick.HOTBAR }
        if (!platform.menus.click(bot, slot, top, name, moves, hotbar) && click.click == BotClick.OFF_HAND) {
            swappedOver(bot, if (top) platform.menus.windows.getValue(window).slots.getOrNull(slot) else bot.inventorySlots[slot])
        }
    }

    /** Escape: a project menu closes (heard), or a container they opened. */
    private fun closeMenu(bot: FakePlayer) {
        val uuid = bot.ref.uuid
        platform.menus.viewing(uuid)?.let {
            platform.menus.close(it, uuid)
            return
        }
        val inventory = platform.inventories.open.remove(uuid) ?: throw IllegalStateException("${bot.ref.name} has no menu open")
        val ref = inventory as? InventoryRef.Block
        val kind = platform.inventories.kind(inventory) ?: "chest"
        raise.playerCloseInventory(GameEvent.PlayerInventory(bot.ref, kind, inventory, ref?.let { block(it.world, it.x, it.y, it.z) }))
    }

    private fun respawn(bot: FakePlayer) {
        bot.dead = false
        bot.numbers[EntityNumber.HEALTH] = bot.maxHealth ?: 20.0
        val world = platform.worlds.defaultWorld()
        val respawn = GameEvent.PlayerRespawn(bot.ref, platform.worlds.spawnLocation(world)!!)
        raise.playerRespawn(respawn)
        bot.location = respawn.location
    }

    /**
     * An input's answer as the server reads it (`PlatformEvents.dialogPressed`):
     * what was [given], else where the client's screen starts it; a
     * checkbox as its `onTrue`/`onFalse` string.
     */
    private fun answer(input: DialogInput, given: JsonPrimitive?): Any = when (input) {
        is TextInput -> given?.content ?: input.initial ?: ""
        is BooleanInput -> if (given?.booleanOrNull ?: input.initial ?: false) input.onTrue ?: "true" else input.onFalse ?: "false"
        is OptionInput -> given?.content ?: initial(input)
        is RangeInput -> given?.doubleOrNull ?: input.initial ?: ((input.start + input.end) / 2)
    }

    private fun initial(input: OptionInput) = (input.options.firstOrNull { it.initial == true } ?: input.options.first()).id

    private fun unsupported(what: String): Nothing = throw UnsupportedOperationException("the fake server's bots have no model of $what")

    // ---- what it shows -----------------------------------------------------------------------

    override fun state(name: String): BotState = with(bot(name)) {
        BotState(
            ref.name, ref.uuid.toString(), location.world, location.x, location.y, location.z, location.yaw, location.pitch,
            onGround = !flying,
            health = numbers[EntityNumber.HEALTH] ?: maxHealth ?: 20.0,
            food = (numbers[EntityNumber.FOOD] ?: 20.0).toInt(),
            gameMode = gameMode,
            dead = dead,
            sneaking = sneaking,
            sprinting = sprinting,
            selectedSlot = heldSlot(this),
            inventory = inventorySlots.withIndex().mapNotNull { (slot, item) ->
                platform.itemViews.display(ref.uuid, FakeItemViews.Place.Inventory(slot), item)?.let { botItem(slot, it) }
            },
            menu = platform.menus.viewing(ref.uuid)?.let { window -> menu(ref.uuid, window, platform.menus.windows.getValue(window)) },
            dialog = platform.dialogs.showing[ref.uuid]?.let(::dialog),
            bossBars = platform.bossBars.of(this).map { BotBossBar(platform.text.strip(it.text), it.progress, it.color, it.style) },
            sidebar = platform.sidebars.showing[ref.uuid]?.let { (title, lines) ->
                BotSidebar(platform.text.strip(title), lines.map(platform.text::strip))
            },
            actionBar = platform.pause.actionBars[ref.uuid],
            resourcePacks = screens[ref.uuid]?.packs?.values?.toList().orEmpty(),
            playerList = playerList(this),
            entities = nearby(this),
            target = crosshair(this),
            events = screens[ref.uuid]?.next ?: 0
        )
    }

    /**
     * What its crosshair is on, as the real bots pick it: the nearest entity within [REACH] (a body, or
     * one of NetherForge's hitboxes it hasn't been hidden), in front of the nearest block within
     * [BLOCK_REACH].
     */
    private fun crosshair(bot: FakePlayer): BotTarget? {
        val ray = sight(bot) ?: return null
        val origin = Vec3(ray.x, ray.y, ray.z)
        val direction = Vec3(ray.dx, ray.dy, ray.dz)
        val block = platform.worlds.raycastBlocks(ray.world, origin, direction, BLOCK_REACH, false)
        val blockDistance = block?.position?.let { length(it - origin) } ?: Double.MAX_VALUE
        val body = platform.worldEntities.raycast(ray.world, origin, direction, REACH, setOf(bot.ref.uuid))
            ?.takeIf { hit -> platform.worldEntities.body(hit.id)?.hiddenFrom?.contains(bot.ref.uuid) != true }
            ?.let { hit -> Triple(hit.id.toString(), platform.worldEntities.body(hit.id)?.kind ?: "minecraft:player", hit.position) }
        val hitbox = platform.entities.hitboxes
            .filter { it.location.world == ray.world && bot.ref.uuid !in it.hiddenFrom }
            .mapNotNull { box ->
                val half = box.width / 2
                val l = box.location
                val bounds = Box(Vec3(l.x - half, l.y, l.z - half), Vec3(l.x + half, l.y + box.height, l.z + half))
                FakePlatform.slab(bounds, origin, direction, REACH)?.let { (distance, _) -> box to distance }
            }
            .minByOrNull { it.second }
            ?.let { (box, distance) -> Triple(box.id.toString(), "minecraft:interaction", origin + direction * distance) }
        val entity = listOfNotNull(body, hitbox)
            .map { it to length(it.third - origin) }
            .filter { it.second <= blockDistance }
            .minByOrNull { it.second }
        if (entity != null) {
            val (hit, distance) = entity
            return BotTarget(BotTargetKind.ENTITY, hit.third.x, hit.third.y, hit.third.z, distance, entity = hit.first, type = hit.second)
        }
        block ?: return null
        return BotTarget(
            BotTargetKind.BLOCK, block.position.x, block.position.y, block.position.z, blockDistance,
            blockX = block.x,
            blockY = block.y,
            blockZ = block.z,
            face = faceOf(block.normal),
            state = platform.worlds.state(ray.world, block.x, block.y, block.z)
        )
    }

    private fun length(v: Vec3) = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)

    private fun faceOf(normal: Vec3) = when {
        normal.y > 0.5 -> "up"
        normal.y < -0.5 -> "down"
        normal.z < -0.5 -> "north"
        normal.z > 0.5 -> "south"
        normal.x > 0.5 -> "east"
        else -> "west"
    }

    /**
     * The entities its client knows, nearest first, as the real bots list them (within [NEARBY], at most
     * [MAX_NEARBY]): the world's mobs and players and NetherForge's hitboxes, in its world and not hidden
     * from it. Displays are left out; the fake has no model of their entity types, and nothing clicks them.
     */
    private fun nearby(bot: FakePlayer): List<BotEntity> {
        val at = bot.location
        val bodies = (platform.players.byId.values + platform.worldEntities.mobs.values)
            .filter { it.id != bot.ref.uuid && bot.ref.uuid !in it.hiddenFrom }
            .map { Triple(it.id, it.kind, it.location) }
        val hitboxes = platform.entities.hitboxes
            .filter { bot.ref.uuid !in it.hiddenFrom }
            .map { Triple(it.id, "minecraft:interaction", it.location) }
        return (bodies + hitboxes)
            .filter { (_, _, where) -> where.world == at.world }
            .map { (id, kind, where) -> BotEntity(id.toString(), kind, where.x, where.y, where.z, distance(at, where)) }
            .filter { it.distance <= NEARBY }
            .sortedBy { it.distance }
            .take(MAX_NEARBY)
    }

    /**
     * The players its client knows, as the real bots list them (by name): everyone online, whether they're listed for
     * it, the name their entry shows when one was set, their place, and the line under their name tag, as plain text.
     */
    private fun playerList(bot: FakePlayer): List<BotListEntry> = platform.players.byId.values.sortedBy { it.ref.name }.map { player ->
        val list = platform.playerList
        BotListEntry(
            player.ref.name,
            player.ref.uuid.toString(),
            listed = list.isListed(bot.ref.uuid, player.ref.uuid) ?: false,
            displayName = list.names[player.ref.uuid]?.let(platform.text::strip),
            order = list.order(player.ref.uuid) ?: 0,
            belowName = platform.teams.belowNames[player.ref.name]?.let(platform.text::strip)
        )
    }

    private fun distance(a: Location, b: Location) = sqrt((a.x - b.x).pow(2) + (a.y - b.y).pow(2) + (a.z - b.z).pow(2))

    /** A stack as the client shows it; the fake has no item names of its own, so an unnamed one shows its kind. */
    private fun botItem(slot: Int, item: ItemData) = BotItem(
        slot,
        item.def.kind,
        item.def.count ?: 1,
        item.def.name?.let(platform.text::strip) ?: item.def.kind,
        item.def.lore.orEmpty()
    )

    /** A project menu as the client shows it: the vanilla screen its type opens. */
    private fun menu(player: UUID, id: UUID, window: FakeWindow): BotMenu {
        val type = when (window.spec.type) {
            MenuType.CHEST, MenuType.BARREL -> "minecraft:generic_9x${window.spec.size / 9}"
            MenuType.SHULKER_BOX -> "minecraft:shulker_box"
            MenuType.HOPPER -> "minecraft:hopper"
            MenuType.DISPENSER, MenuType.DROPPER -> "minecraft:generic_3x3"
            MenuType.CRAFTER -> "minecraft:crafter_3x3"
        }
        val items = window.slots.withIndex().mapNotNull { (slot, item) ->
            platform.itemViews.display(player, FakeItemViews.Place.Menu(id, slot), item)?.let { botItem(slot, it) }
        }
        val cursor = platform.itemViews.display(player, FakeItemViews.Place.Cursor, window.cursors[player])?.let { botItem(-1, it) }
        return BotMenu(type, platform.text.strip(window.title), window.spec.size, items, cursor)
    }

    /** A dialog as the client shows it: plain text, its inputs with where they start. */
    private fun dialog(shown: DialogSpec): BotDialog {
        val file = shown.file
        val inputs = file.inputs.map { input ->
            val (kind, start) = when (input) {
                is TextInput -> "text" to JsonPrimitive(input.initial ?: "")
                is BooleanInput -> "boolean" to JsonPrimitive(input.initial ?: false)
                is RangeInput -> "number" to JsonPrimitive(input.initial ?: ((input.start + input.end) / 2))
                is OptionInput -> "option" to JsonPrimitive(initial(input))
            }
            BotDialogInput(
                input.key,
                kind,
                platform.text.strip(input.label ?: input.key),
                start,
                (input as? OptionInput)?.options?.map {
                    it.id
                }.orEmpty()
            )
        }
        return BotDialog(
            type = (file.type ?: DialogType.NOTICE).name.lowercase(),
            title = platform.text.strip(file.title),
            inputs = inputs,
            buttons = file.buttons.map { platform.text.strip(it.label ?: it.key) },
            canEscape = file.canCloseWithEscape ?: true
        )
    }

    /** What the bot's client was sent that the fake models: sounds, particles, boss bars shown and hidden, and resource packs. */
    override fun events(name: String, since: Int): BotEvents = screens.getValue(bot(name).ref.uuid).since(since)

    private fun info(player: FakePlayer) = with(player.location) {
        BotInfo(player.ref.name, player.ref.uuid.toString(), world, x, y, z, 0)
    }

    /** A bot that's still online: one that was kicked, banned or quit is gone. */
    private fun bot(name: String): FakePlayer =
        online[name]?.takeIf { it.ref.uuid in platform.players.byId } ?: throw IllegalArgumentException("no bot named \"$name\"")

    private fun Double.sq() = this * this

    private fun isItem(kind: String) = platform.game.has(RegistryKey.ITEM, kind) == true

    private companion object {
        const val OFF_HAND = 40

        /** A player's helmet slot, above leggings and boots (36). */
        const val HEAD = 39

        /** The inventory screen's crafting result and 2×2 grid, before its armour. */
        const val CRAFTING_SLOTS = 5

        /** The real bots' `PaperBots.NAME`. */
        val NAME = Regex("[A-Za-z0-9_]{3,16}")

        /** The real bots' `BotEventLog.KEPT`. */
        const val KEPT = 1000

        /** The real bots' `Bot.NEARBY` and `Bot.MAX_NEARBY`. */
        const val NEARBY = 16.0
        const val MAX_NEARBY = 50
        const val HOTBAR = 9
        const val MAIN_ROWS = 27

        /** The slots a picked block's item is looked for in: the hotbar and the rest of the inventory. */
        const val PICKABLE = 36
        const val JUMP = 0.42
        const val FIST = 1.0
        const val REACH = 3.0
        const val BLOCK_REACH = 4.5
        const val THROW = 0.3
        const val PLAYER_DROP_DELAY = 40.0

        // A client's walking, in blocks a tick, and how close is there.
        const val WALK_SPEED = 0.2158
        const val SPRINT_SPEED = 0.2806
        const val SNEAK_SPEED = 0.0647
        const val ARRIVED = 0.3

        val CONTAINERS = setOf("minecraft:chest", "minecraft:barrel")

        val FACES = mapOf(
            "up" to Triple(0, 1, 0),
            "down" to Triple(0, -1, 0),
            "north" to Triple(0, 0, -1),
            "south" to Triple(0, 0, 1),
            "west" to Triple(-1, 0, 0),
            "east" to Triple(1, 0, 0)
        )
    }
}
