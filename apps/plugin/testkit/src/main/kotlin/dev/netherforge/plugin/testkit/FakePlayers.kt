package dev.netherforge.plugin.testkit

import dev.netherforge.format.game.has
import dev.netherforge.plugin.platform.DEFAULT_CHAT_FORMAT
import dev.netherforge.plugin.platform.EntityFlag
import dev.netherforge.plugin.platform.EntityNumber
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.PlayerOps
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.Ray
import java.util.UUID
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

// Players on the fake server.

class FakePlayer(val ref: PlayerRef, location: Location) : FakeBody(ref.uuid, "minecraft:player", location) {
    val messages = mutableListOf<String>()
    val commands = mutableListOf<String>()
    val permissions = mutableSetOf<String>()

    /** What the project's attachment holds (`PermissionOps.apply`): it wins over [permissions]. */
    var granted: Map<String, Boolean> = emptyMap()

    fun has(permission: String) = granted[permission] ?: (permission in permissions)

    /** What their game mode lets them do: fly in creative and spectator. */
    val mayFly get() = gameMode == "creative" || gameMode == "spectator"
    var op = false
    var displayName: String? = null
    val actionbars = mutableListOf<String>()

    /** Every title shown: title, subtitle, fade in, stay, fade out. */
    val titles = mutableListOf<List<Any>>()
    var gameMode = "survival"
    var locale = "en_us"
    val cooldowns = mutableMapOf<String, Int>()
    val tab = mutableMapOf<Boolean, String>()

    /** On the death screen: dead until they press respawn. */
    var dead = false
    var flying = false
    var sneaking = false
    var sprinting = false

    /** What they're using over ticks (food being eaten), until it's done or they let go. */
    var using: Using? = null

    /** An item being used: [left] of its [total] ticks to go. */
    class Using(val item: ItemData, val hand: String, var left: Int, val total: Int)

    /** How their game answers a resource pack: `loaded`, `declined`, `failed`, or null for never. */
    var packAnswer: String? = null

    /** Hotbar 0-8, main 9-35, armour 36-39 (feet to head), offhand 40, body armour 41 and saddle 42, as on Paper. */
    val inventorySlots = arrayOfNulls<ItemData>(43)
    val enderChest = arrayOfNulls<ItemData>(27)

    /** Their storages (`player:storage`), by namespaced name: each filled slot's item, past the size too. */
    val storages = LinkedHashMap<String, MutableMap<Int, ItemData>>()
    var kicked: String? = null

    override val eyeHeight get() = 1.62

    /** Every experience point they've been given. */
    val experience get() = points

    private var points = 0

    /** Gives experience points as orbs do: the bar fills, and the level goes up each time it's full. */
    fun giveExperience(amount: Int) {
        points += amount
        var level = (numbers[EntityNumber.LEVEL] ?: 0.0).toInt()
        var into = (numbers[EntityNumber.EXPERIENCE_PROGRESS] ?: 0.0) * toNext(level) + amount
        while (into >= toNext(level)) {
            into -= toNext(level)
            level++
        }
        numbers[EntityNumber.LEVEL] = level.toDouble()
        numbers[EntityNumber.EXPERIENCE_PROGRESS] = into / toNext(level)
    }

    /** The game's points from [level] to the next. */
    private fun toNext(level: Int): Int = when {
        level >= 30 -> 9 * level - 158
        level >= 15 -> 5 * level - 38
        else -> 2 * level + 7
    }
}

class FakePlayers(private val platform: FakePlatform) : PlayerOps {
    val byId = LinkedHashMap<UUID, FakePlayer>()
    val broadcasts = mutableListOf<String>()

    /** Everyone who has ever joined, online or not. */
    val known = LinkedHashMap<UUID, PlayerRef>()

    /** What scripts sent the console. */
    val console = mutableListOf<String>()

    /** Someone joining as [name], with a UUID from the name unless [uuid] says otherwise (a script's bot is new each time). */
    fun add(name: String, location: Location = Location("world", 0.5, 64.0, 0.5), uuid: UUID? = null): FakePlayer {
        val player = FakePlayer(PlayerRef(uuid ?: UUID.nameUUIDFromBytes(name.toByteArray()), name), location)
        byId[player.ref.uuid] = player
        known[player.ref.uuid] = player.ref
        return player
    }

    /** Forgets someone who has left was ever here, as the server does a player it never saved. */
    fun forget(uuid: UUID) {
        known.remove(uuid)
    }

    override fun online() = byId.values.map { it.ref }

    override fun find(nameOrUuid: String) =
        byId.values.firstOrNull { it.ref.name.equals(nameOrUuid, ignoreCase = true) || it.ref.uuid.toString() == nameOrUuid }?.ref

    override fun known(nameOrUuid: String) =
        known.values.firstOrNull { it.name.equals(nameOrUuid, ignoreCase = true) || it.uuid.toString() == nameOrUuid }

    override fun get(uuid: UUID) = byId[uuid]?.ref

    override fun location(uuid: UUID) = byId[uuid]?.location

    /** Looking along the player's facing from eye height. */
    override fun eye(uuid: UUID): Ray? {
        val player = byId[uuid] ?: return null
        val l = player.location
        val yaw = Math.toRadians(l.yaw)
        val pitch = Math.toRadians(l.pitch)
        return Ray(l.world, l.x, l.y + 1.62, l.z, -sin(yaw) * cos(pitch), -sin(pitch), cos(yaw) * cos(pitch))
    }

    override fun message(uuid: UUID, miniMessage: String): Boolean {
        val player = byId[uuid] ?: return false
        player.messages += miniMessage
        return true
    }

    /** Teleports as the server does: its teleport event first, which may send them elsewhere or cancel it. */
    override fun teleport(uuid: UUID, to: Location): Boolean = teleport(byId[uuid] ?: return false, to, "plugin")

    fun teleport(player: FakePlayer, to: Location, cause: String): Boolean {
        val from = player.location
        val teleport = GameEvent.PlayerTeleport(player.ref, from, to, cause)
        if (platform.raise.playerTeleport(teleport)) return false
        val destination = teleport.to
        player.location = destination
        if (destination.world !=
            from.world
        ) {
            platform.raise.playerChangeWorld(GameEvent.PlayerChangeWorld(player.ref, from.world, destination.world))
        }
        return true
    }

    /**
     * Walks [player] to [to] as a move packet would: heard only while the
     * runtime watches moves, and only when it changes block. False when
     * a script cancelled it (they stay put).
     */
    fun move(player: FakePlayer, to: Location): Boolean {
        val from = player.location
        val changed = floor(from.x) != floor(to.x) || floor(from.y) != floor(to.y) || floor(from.z) != floor(to.z)
        if (changed && platform.raise.playerMove(GameEvent.PlayerMove(player.ref, from, to))) return false
        player.location = to
        return true
    }

    /**
     * Food being eaten is eaten once its time is up: heard first
     * (cancelled, nothing is eaten), then their food fills (heard too,
     * and a handler may change it) and the stack shrinks.
     */
    fun useItems() {
        for (player in byId.values.toList()) {
            val use = player.using ?: continue
            if (--use.left > 0) continue
            player.using = null
            val slot = if (use.hand == "off_hand") 40 else (player.numbers[EntityNumber.HELD_SLOT] ?: 0.0).toInt()
            val held = player.inventorySlots[slot]?.takeIf { it.def.kind == use.item.def.kind } ?: continue
            if (platform.events?.playerConsumeItem(player.ref, held) == true) continue
            val food = (player.numbers[EntityNumber.FOOD] ?: 20.0).toInt()
            val change = GameEvent.PlayerChangeFood(player.ref, minOf(20, food + (FakePlatform.FOODS[held.def.kind] ?: 0)), held)
            if (!platform.raise.playerChangeFood(change)) player.numbers[EntityNumber.FOOD] = change.food.toDouble()
            if (player.gameMode != "creative") {
                val count = (held.def.count ?: 1) - 1
                player.inventorySlots[slot] = if (count > 0) held.copy(def = held.def.copy(count = count)) else null
            }
        }
    }

    /** Every chat line everyone saw: the format filled in, as the server renders it. */
    val chat = mutableListOf<String>()

    /** [player] says [message]: scripts hear it only while the runtime watches chat. False when cancelled. */
    fun chat(player: FakePlayer, message: String): Boolean {
        val line = GameEvent.PlayerChat(player.ref, message, DEFAULT_CHAT_FORMAT)
        if (platform.raise.playerChat(line)) return false
        chat += line.format.replace("<player>", player.ref.name).replace("<message>", line.message)
        return true
    }

    override fun runCommand(uuid: UUID, line: String): Boolean {
        val player = byId[uuid] ?: return false
        player.commands += line
        return platform.commands.dispatch(platform.commands.sender(player), line)
    }

    override fun hasPermission(uuid: UUID, permission: String) = byId[uuid]?.has(permission) == true

    /** Operators by UUID, online or not. */
    val ops = mutableSetOf<UUID>()

    override fun isOperator(uuid: UUID) = uuid in ops || byId[uuid]?.op == true

    override fun displayName(uuid: UUID) = byId[uuid]?.let { it.displayName ?: it.ref.name }

    override fun setDisplayName(uuid: UUID, miniMessage: String): Boolean {
        byId[uuid]?.displayName = miniMessage
        return uuid in byId
    }

    override fun actionbar(uuid: UUID, miniMessage: String) = byId[uuid]?.actionbars?.add(miniMessage) == true

    override fun title(uuid: UUID, title: String, subtitle: String, fadeIn: Int, stay: Int, fadeOut: Int) =
        byId[uuid]?.titles?.add(listOf(title, subtitle, fadeIn, stay, fadeOut)) == true

    override fun clearTitle(uuid: UUID) = byId[uuid]?.titles?.add(emptyList()) == true

    override fun gameMode(uuid: UUID) = byId[uuid]?.gameMode

    /** As on the server: its change event first, which may cancel it; leaving creative or spectator stops them flying. */
    override fun setGameMode(uuid: UUID, gameMode: String): Boolean {
        val player = byId[uuid] ?: return false
        if (player.gameMode == gameMode) return true
        if (platform.raise.playerChangeGameMode(GameEvent.PlayerChangeGameMode(player.ref, gameMode, "plugin"))) return true
        player.gameMode = gameMode
        if (!player.mayFly) player.flying = false
        // What the server's abilities say of each mode: creative may fly, spectator is flying, the others neither.
        player.flags[EntityFlag.CAN_FLY] = player.mayFly
        if (gameMode == "spectator") player.flags[EntityFlag.FLYING] = true
        if (!player.mayFly) player.flags[EntityFlag.FLYING] = false
        return true
    }

    override fun giveExperience(uuid: UUID, points: Int): Boolean {
        val player = byId[uuid] ?: return false
        val level = (player.numbers[EntityNumber.LEVEL] ?: 0.0).toInt()
        player.giveExperience(points)
        val now = (player.numbers[EntityNumber.LEVEL] ?: 0.0).toInt()
        // The server notices a new level on the player's next tick, and says so.
        if (now != level) platform.raise.playerChangeLevel(GameEvent.PlayerChangeLevel(player.ref, level, now))
        return true
    }

    override fun locale(uuid: UUID) = byId[uuid]?.locale

    /** Kicks as the server does: they leave ([quit]). */
    override fun kick(uuid: UUID, reason: String?): Boolean {
        val player = byId[uuid] ?: return false
        val kick = GameEvent.PlayerKick(player.ref, reason ?: "Kicked", "${player.ref.name} left the game", "plugin")
        if (platform.raise.playerKick(kick)) return true
        player.kicked = kick.text
        quit(player)
        return true
    }

    /**
     * [player] leaves, as the server sees a quit: the window they have
     * open closes (heard, as Paper's close event is), the runtime hears
     * them leave, and everything the server keeps only while they're
     * online goes: what's hidden from them, their entry in the player
     * list, their own border, sidebar, boss bars and dialog.
     */
    fun quit(player: FakePlayer) {
        val uuid = player.ref.uuid
        if (uuid !in byId) return
        platform.menus.closeAny(uuid)
        platform.inventories.open.remove(uuid)
        byId.remove(uuid)
        platform.raise.playerQuit(GameEvent.PlayerQuit(player.ref, "${player.ref.name} left the game"))
        platform.entities.forget(uuid)
        for (body in platform.worldEntities.mobs.values) body.hiddenFrom -= uuid
        platform.playerList.quit(uuid)
        platform.borders.players.remove(uuid)
        platform.sidebars.showing.remove(uuid)
        for (viewers in platform.bossBars.shown.values) viewers -= uuid
        platform.dialogs.showing.remove(uuid)
    }

    override fun cooldown(uuid: UUID, key: String) = byId[uuid]?.let { it.cooldowns[key] ?: 0 }

    override fun setCooldown(uuid: UUID, key: String, ticks: Int): Boolean {
        byId[uuid]?.cooldowns?.set(key, ticks)
        return uuid in byId
    }

    override fun tabText(uuid: UUID, footer: Boolean) = byId[uuid]?.tab?.get(footer)?.takeIf { it.isNotEmpty() }

    override fun setTabText(uuid: UUID, footer: Boolean, miniMessage: String): Boolean {
        byId[uuid]?.tab?.set(footer, miniMessage)
        return uuid in byId
    }

    override fun closeInventory(uuid: UUID): Boolean {
        if (uuid !in byId) return false
        platform.inventories.open.remove(uuid)
        platform.menus.closeAny(uuid)
        cursors.remove(uuid)
        return true
    }

    /** What players carry on the cursor with no project menu open (a menu's window keeps its viewers'). */
    val cursors = LinkedHashMap<UUID, ItemData>()

    override fun cursor(uuid: UUID): ItemData? {
        if (uuid !in byId) return null
        val window = platform.menus.viewing(uuid) ?: return cursors[uuid]
        return platform.menus.cursor(window, uuid)
    }

    override fun setCursor(uuid: UUID, item: ItemData?): Boolean {
        if (uuid !in byId) return false
        platform.menus.viewing(uuid)?.let { return platform.menus.setCursor(it, uuid, item) }
        if (item == null) cursors.remove(uuid) else cursors[uuid] = platform.stack(item) ?: return false
        return true
    }

    override fun broadcast(miniMessage: String) {
        broadcasts += miniMessage
    }

    override fun messageConsole(miniMessage: String) {
        console += miniMessage
    }
}
