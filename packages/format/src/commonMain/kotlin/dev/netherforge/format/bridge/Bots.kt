package dev.netherforge.format.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement

/*
 * Bots: fake players on the dev server, for tests and coding agents to try
 * what only a player can (joins, chat and commands, clicking centities,
 * menus, dialogs, sidebars, boss bars, the resource pack).
 *
 * A bot is a real player to the server: it joins through the same
 * configuration phase a client does, its inputs are the packets a client
 * sends (so every check and event fires as for a person), and what the
 * server sends it is recorded: [BotState] is what its screen shows now,
 * [BotEvent]s what arrived, in order. Only a dev server has bots.
 */

/** Where a bot is, or where to put it. [world] null: the bot's current world (or the default one on joining). */
@Serializable
data class BotPosition(
    val x: Double,
    val y: Double,
    val z: Double,
    val world: String? = null,
    val yaw: Double? = null,
    val pitch: Double? = null
)

/** How a bot answers a resource pack the server offers. */
@Serializable
enum class BotPackAnswer {
    /** Accept, download it from its URL and check its SHA-1: loaded, or failed to download. */
    @SerialName("accept")
    ACCEPT,

    @SerialName("decline")
    DECLINE,

    /** Accept, then report a failed download without trying. */
    @SerialName("fail")
    FAIL,

    /** Say nothing, like a client that never answers. */
    @SerialName("ignore")
    IGNORE,

    /**
     * Accept and report it loaded without fetching it: a bot that draws
     * nothing (a script's) needs none of it, but the server and scripts see a
     * player who has the pack.
     */
    @SerialName("loaded")
    LOADED
}

@Serializable
data class BotInfo(
    val name: String,
    val uuid: String,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    /** The sequence number the bot's next event will have: pass it to `bots/events` as `since`. */
    val events: Int
)

/** What `bots/act` answers once the action is done: where the bot is, and where its events stood before it started. */
@Serializable
data class BotActResult(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    /** The first event the action can have caused: `bots/events` from here shows what followed it. */
    val since: Int
)

/** The mouse button of a click. */
@Serializable
enum class BotButton {
    @SerialName("left")
    LEFT,

    @SerialName("right")
    RIGHT
}

/** A hand. */
@Serializable
enum class BotHand {
    @SerialName("main")
    MAIN,

    @SerialName("off")
    OFF
}

/** How a slot of the open menu is clicked, as the client's inputs make them. */
@Serializable
enum class BotClick {
    @SerialName("left")
    LEFT,

    @SerialName("right")
    RIGHT,

    @SerialName("shift_left")
    SHIFT_LEFT,

    @SerialName("shift_right")
    SHIFT_RIGHT,

    /** The middle button: a creative player's copy of the stack. */
    @SerialName("middle")
    MIDDLE,

    /** Q over the slot: drop one. */
    @SerialName("drop")
    DROP,

    /** Ctrl+Q over the slot: drop the stack. */
    @SerialName("drop_all")
    DROP_ALL,

    /** A double click: gather matching items onto the cursor. */
    @SerialName("double")
    DOUBLE,

    /** A number key over the slot: swap with hotbar slot `hotbar`. */
    @SerialName("hotbar")
    HOTBAR,

    /** F over the slot: swap with the off hand. */
    @SerialName("off_hand")
    OFF_HAND
}

/** One thing a bot does, the way a player's client would do it. */
@Serializable
sealed interface BotAction {
    /** Moved there by the server, as `/tp` would (not walked). */
    @Serializable
    @SerialName("teleport")
    data class Teleport(
        val x: Double,
        val y: Double,
        val z: Double,
        val world: String? = null,
        val yaw: Double? = null,
        val pitch: Double? = null
    ) : BotAction

    /** Turn the head: yaw 0 is south, 90 west; pitch 90 is straight down. */
    @Serializable
    @SerialName("look")
    data class Look(val yaw: Double, val pitch: Double) : BotAction

    /** Turn the head toward a point. */
    @Serializable
    @SerialName("look_at")
    data class LookAt(val x: Double, val y: Double, val z: Double) : BotAction

    /**
     * Walk in a straight line to (x, z), jumping up steps and falling as a
     * player does. Answered when it arrives; an error when it's still short
     * after [timeoutTicks] (stuck against a wall, say).
     */
    @Serializable
    @SerialName("walk_to")
    data class WalkTo(val x: Double, val z: Double, val sprint: Boolean = false, val timeoutTicks: Int = DEFAULT_WALK_TICKS) : BotAction

    @Serializable
    @SerialName("jump")
    data object Jump : BotAction

    @Serializable
    @SerialName("sneak")
    data class Sneak(val on: Boolean) : BotAction

    @Serializable
    @SerialName("sprint")
    data class Sprint(val on: Boolean) : BotAction

    /** Say [message] in chat. A leading `/` is a command (see [Command]). */
    @Serializable
    @SerialName("chat")
    data class Chat(val message: String) : BotAction

    /** Run a command as this player, typed into chat (with or without its `/`). */
    @Serializable
    @SerialName("command")
    data class Command(val line: String) : BotAction

    /**
     * Right-click an entity: [entity] by UUID, or a centity's node ([centity]
     * is its instance's UUID, [node] defaults to its first clickable one).
     * The bot turns to [x], [y], [z] (a point in the world, on the entity:
     * a square of a board) or, without one, to the entity's middle (the
     * node's hitbox's); it must be within reach (3 blocks). With neither
     * [entity] nor [centity], it clicks whatever is in its crosshair, where its
     * line of sight meets it, without turning (`look`/`look_at` first, then
     * this, clicks exactly where it looks).
     */
    @Serializable
    @SerialName("interact")
    data class Interact(
        val entity: String? = null,
        val centity: String? = null,
        val node: String? = null,
        val hand: BotHand = BotHand.MAIN,
        val x: Double? = null,
        val y: Double? = null,
        val z: Double? = null
    ) : BotAction {
        /** The point to aim at, when one was given; all three or none. */
        val at: BotPoint? get() = BotPoint.of(x, y, z)
    }

    /** Left-click (attack) an entity, chosen and aimed at as [Interact] chooses and aims. */
    @Serializable
    @SerialName("attack")
    data class Attack(
        val entity: String? = null,
        val centity: String? = null,
        val node: String? = null,
        val x: Double? = null,
        val y: Double? = null,
        val z: Double? = null
    ) : BotAction {
        val at: BotPoint? get() = BotPoint.of(x, y, z)
    }

    /** Left-click the air: an arm swing. */
    @Serializable
    @SerialName("swing")
    data object Swing : BotAction

    /** Right-click with the item in [hand], at nothing. */
    @Serializable
    @SerialName("use_item")
    data class UseItem(val hand: BotHand = BotHand.MAIN) : BotAction

    /**
     * Let go of right-click while using an item: a drawn bow shoots, a raised
     * shield lowers, food half-eaten is put away. (Food that's held long
     * enough is eaten without it, as in the game.)
     */
    @Serializable
    @SerialName("release_item")
    data object ReleaseItem : BotAction

    /** Start or stop flying, as a double tap of jump does; the server only lets a player who may fly. */
    @Serializable
    @SerialName("fly")
    data class Fly(val on: Boolean) : BotAction

    /**
     * Write the text of the sign at (x, y, z), [front] or back, as the sign
     * editor's Done does. The server only takes it from the player it opened
     * the editor for: place the sign, or right-click it ([UseBlock]), first.
     */
    @Serializable
    @SerialName("edit_sign")
    data class EditSign(val x: Int, val y: Int, val z: Int, val lines: List<String>, val front: Boolean = true) : BotAction

    /**
     * Press button [button] of the open menu: an enchanting table's offer
     * (0 to 2), a stonecutter's or loom's choice, a lectern's page turn.
     */
    @Serializable
    @SerialName("click_button")
    data class ClickButton(val button: Int) : BotAction

    /** Right-click the face [face] (`up`, `down`, `north`, …) of the block at (x, y, z). */
    @Serializable
    @SerialName("use_block")
    data class UseBlock(val x: Int, val y: Int, val z: Int, val face: String = "up", val hand: BotHand = BotHand.MAIN) : BotAction

    /** Mine the block at (x, y, z) until it breaks, as long as that takes with what's in hand. */
    @Serializable
    @SerialName("break_block")
    data class BreakBlock(val x: Int, val y: Int, val z: Int, val face: String = "up") : BotAction

    /** Middle-click the block at (x, y, z): pick it, bringing its item to hand. */
    @Serializable
    @SerialName("pick_block")
    data class PickBlock(val x: Int, val y: Int, val z: Int) : BotAction

    /** Hold hotbar slot [slot] (0–8). */
    @Serializable
    @SerialName("select_slot")
    data class SelectSlot(val slot: Int) : BotAction

    /** F: swap the main and off hand items. */
    @Serializable
    @SerialName("swap_hands")
    data object SwapHands : BotAction

    /** Q: drop one of the held item, or the whole stack. */
    @Serializable
    @SerialName("drop")
    data class Drop(val all: Boolean = false) : BotAction

    /** Click slot [slot] of the open menu (its numbering: the menu's own slots first, then the player's inventory). */
    @Serializable
    @SerialName("click_slot")
    data class ClickSlot(val slot: Int, val click: BotClick = BotClick.LEFT, val hotbar: Int? = null) : BotAction

    /** Drag the cursor's stack across [slots] of the open menu: left spreads it evenly, right puts one in each. */
    @Serializable
    @SerialName("drag")
    data class Drag(val slots: List<Int>, val button: BotButton = BotButton.LEFT) : BotAction

    /** Close the open menu (Escape). */
    @Serializable
    @SerialName("close_menu")
    data object CloseMenu : BotAction

    /**
     * Press a button of the dialog on screen: by its label ([button], the
     * text it shows) or its position ([index], from 0). [inputs] sets the
     * dialog's inputs by key first (text: a string; boolean: true/false;
     * number: a number; option: the option's id); the rest keep their
     * initial values.
     */
    @Serializable
    @SerialName("dialog_button")
    data class DialogButton(val button: String? = null, val index: Int? = null, val inputs: Map<String, JsonElement> = emptyMap()) :
        BotAction

    /** Escape on the dialog on screen: its exit action, when it may be closed that way. */
    @Serializable
    @SerialName("dialog_close")
    data object DialogClose : BotAction

    /**
     * Stop what it's doing over several ticks (walking, mining, waiting for a
     * teleport to arrive): that action ends with an error saying it was
     * stopped. Nothing to stop is fine.
     */
    @Serializable
    @SerialName("stop")
    data object Stop : BotAction

    /** Press Respawn on the death screen. */
    @Serializable
    @SerialName("respawn")
    data object Respawn : BotAction

    companion object {
        const val DEFAULT_WALK_TICKS = 200

        /** What a bridge request may wait for; the editor gives a request 30 seconds. */
        const val MAX_TICKS = 500
    }
}

/** An item stack as the bot's client shows it. */
@Serializable
data class BotItem(
    val slot: Int,
    /** `minecraft:stone`. */
    val item: String,
    val count: Int,
    /** The name it shows (its custom name, or the item's). */
    val name: String,
    val lore: List<String> = emptyList()
)

/** The menu (container window) open on the bot's screen. */
@Serializable
data class BotMenu(
    /** `minecraft:generic_9x3`, `minecraft:hopper`, … */
    val type: String,
    val title: String,
    /** The menu's own slots; the player's inventory below them isn't repeated here. */
    val size: Int,
    /** The menu's slots that hold something. */
    val items: List<BotItem>,
    /** What the cursor holds, if anything. */
    val cursor: BotItem? = null
)

/** A dialog's input, as the bot's client would show it. */
@Serializable
data class BotDialogInput(
    val key: String,
    /** `text`, `boolean`, `number` or `option`. */
    val kind: String,
    val label: String,
    /** What it holds before anyone changes it. */
    val initial: JsonElement? = null,
    /** An `option` input's choices, by id. */
    val options: List<String> = emptyList()
)

/** The dialog on the bot's screen. */
@Serializable
data class BotDialog(
    /** `notice`, `confirmation`, `multi_action`, `dialog_list` or `server_links`. */
    val type: String,
    val title: String,
    /** Its body's text, one entry per message. */
    val body: List<String> = emptyList(),
    val inputs: List<BotDialogInput> = emptyList(),
    /** Its buttons' labels, in order (what `dialog_button` names). */
    val buttons: List<String> = emptyList(),
    /** Whether Escape closes it (running its exit action). */
    val canEscape: Boolean = true
)

@Serializable
data class BotBossBar(val name: String, val progress: Double, val color: String, val overlay: String)

/** The sidebar on the bot's screen: its title, and its lines top to bottom. */
@Serializable
data class BotSidebar(val title: String, val lines: List<String>)

/**
 * A scoreboard team the bot's client knows, as its team packets left it:
 * text as plain strings, [color] a chat colour's name (`red`), [nametags]
 * and [collision] Minecraft's own names (`hideForOtherTeams`, `pushOwnTeam`),
 * and its members (players' names, other entities' UUIDs).
 */
@Serializable
data class BotTeam(
    val name: String,
    val displayName: String,
    val prefix: String,
    val suffix: String,
    val color: String? = null,
    val friendlyFire: Boolean,
    val seeInvisibleTeammates: Boolean,
    val nametags: String,
    val collision: String,
    val members: List<String>
)

/**
 * Someone in the bot's player list (what tab shows), as its player info
 * packets left them: whether they're [listed], the name their entry shows
 * when the server set one ([displayName], plain text), their [order], and
 * the line the client draws under their name tag ([belowName]), if any.
 */
@Serializable
data class BotListEntry(
    val name: String,
    val uuid: String,
    val listed: Boolean,
    val displayName: String? = null,
    val order: Int = 0,
    val belowName: String? = null
)

/** A resource pack the server offered the bot, and what became of it. */
@Serializable
data class BotPack(
    val id: String,
    val url: String,
    val hash: String,
    /** The bot's last answer: `accepted`, `downloaded`, `successfully_loaded`, `declined`, `failed_download`, … or `offered` when it didn't answer. */
    val status: String
)

/** An entity the bot's client knows about (has been sent), near it. */
@Serializable
data class BotEntity(
    val uuid: String,
    /** `minecraft:interaction`, `minecraft:zombie`, … */
    val type: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val distance: Double
)

/** A point in the world a click aims at. */
data class BotPoint(val x: Double, val y: Double, val z: Double) {
    companion object {
        /** [x], [y] and [z] as a point, null for none; an error for some but not all. */
        fun of(x: Double?, y: Double?, z: Double?): BotPoint? {
            if (x == null && y == null && z == null) return null
            require(x != null && y != null && z != null) { "give all of x, y and z to aim at a point, or none" }
            return BotPoint(x, y, z)
        }
    }
}

/** What a bot's crosshair is on. */
@Serializable
enum class BotTargetKind {
    @SerialName("block")
    BLOCK,

    @SerialName("entity")
    ENTITY
}

/**
 * What a bot is looking at, as its client picks it: the nearest entity it could click (within entity reach,
 * 3 blocks in survival) in front of the nearest block it could use or mine (within block reach, 4.5), so
 * what `interact` with no target or `use_block` there would reach.
 */
@Serializable
data class BotTarget(
    val kind: BotTargetKind,
    /** Where its line of sight meets it, in the world. */
    val x: Double,
    val y: Double,
    val z: Double,
    /** From its eyes. */
    val distance: Double,
    /** A block's position. */
    val blockX: Int? = null,
    val blockY: Int? = null,
    val blockZ: Int? = null,
    /** The block's face looked at: `up`, `down`, `north`, `south`, `east` or `west`. */
    val face: String? = null,
    /** The block's state: `minecraft:oak_log[axis=y]`. */
    val state: String? = null,
    /** An entity's UUID. */
    val entity: String? = null,
    /** An entity's type: `minecraft:zombie`, or `minecraft:interaction` for a centity's hitbox. */
    val type: String? = null,
    /** For a centity's hitbox: its instance's UUID, filled in by the runtime. */
    val centity: String? = null,
    /** For a centity's hitbox: the node a click there reaches, by name; null when it would reach none (an unclickable node, a miss of a `raycast` node). */
    val node: String? = null
)

/** What a bot's screen shows now, and its state on the server. */
@Serializable
data class BotState(
    val name: String,
    val uuid: String,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Double,
    val pitch: Double,
    val onGround: Boolean,
    val health: Double,
    val food: Int,
    /** `survival`, `creative`, `adventure` or `spectator`. */
    val gameMode: String,
    val dead: Boolean,
    val sneaking: Boolean,
    val sprinting: Boolean,
    /** The hotbar slot held (0–8). */
    val selectedSlot: Int,
    /**
     * The player's inventory as the client was sent it (a stack can look different to one player than the server
     * holds it): hotbar 0–8, the rest 9–35, armor 36–39, off hand 40. Only slots that hold something.
     */
    val inventory: List<BotItem>,
    val menu: BotMenu? = null,
    val dialog: BotDialog? = null,
    val bossBars: List<BotBossBar> = emptyList(),
    val sidebar: BotSidebar? = null,
    /** The title, subtitle and action bar text last shown, while they're up. */
    val title: String? = null,
    val subtitle: String? = null,
    val actionBar: String? = null,
    val resourcePacks: List<BotPack> = emptyList(),
    /** Entities the client has been sent within 16 blocks, nearest first (at most 50). Hidden ones aren't sent. */
    val entities: List<BotEntity> = emptyList(),
    /** What it's looking at within reach, or null for nothing (the sky, or too far). */
    val target: BotTarget? = null,
    /** The scoreboard teams the client knows, by name. */
    val teams: List<BotTeam> = emptyList(),
    /** The players the client knows (listed or not), by name. */
    val playerList: List<BotListEntry> = emptyList(),
    /** The sequence number the bot's next event will have. */
    val events: Int
)

/** Something the server sent a bot. Every event has its bot's next sequence number, from 0. */
@Serializable
sealed interface BotEvent {
    val seq: Int

    /** A chat or system message, as the chat shows it. */
    @Serializable
    @SerialName("chat")
    data class Chat(override val seq: Int, val text: String) : BotEvent

    @Serializable
    @SerialName("action_bar")
    data class ActionBar(override val seq: Int, val text: String) : BotEvent

    @Serializable
    @SerialName("title")
    data class Title(override val seq: Int, val text: String) : BotEvent

    @Serializable
    @SerialName("subtitle")
    data class Subtitle(override val seq: Int, val text: String) : BotEvent

    /** A menu opened on the bot's screen; [menu] is its type, `minecraft:generic_9x3`, … */
    @Serializable
    @SerialName("menu_open")
    data class MenuOpen(override val seq: Int, val menu: String, val title: String) : BotEvent

    /** The server closed the bot's menu. */
    @Serializable
    @SerialName("menu_close")
    data class MenuClose(override val seq: Int) : BotEvent

    @Serializable
    @SerialName("dialog")
    data class Dialog(override val seq: Int, val dialog: BotDialog) : BotEvent

    /** The server took the dialog off the bot's screen. */
    @Serializable
    @SerialName("dialog_close")
    data class DialogClose(override val seq: Int) : BotEvent

    /** A resource pack was offered; [status] is the bot's answer so far (see [BotPack]). */
    @Serializable
    @SerialName("resource_pack")
    data class ResourcePack(override val seq: Int, val id: String, val url: String, val status: String) : BotEvent

    @Serializable
    @SerialName("sound")
    data class Sound(
        override val seq: Int,
        val sound: String,
        val x: Double,
        val y: Double,
        val z: Double,
        val volume: Double,
        val pitch: Double
    ) : BotEvent

    /** The server stopped [sound] (`minecraft:ui.button.click`) on the bot's client, or every sound for null. */
    @Serializable
    @SerialName("stop_sound")
    data class StopSound(override val seq: Int, val sound: String? = null) : BotEvent

    /**
     * Particles the server sent the bot: [particle] (`minecraft:flame`), [count]
     * of them at [x], [y], [z] spread by [dx], [dy], [dz] (or a direction, for a
     * count of 0) at [speed], and whether they were [forced] past the client's
     * particle setting and the usual 32 blocks.
     */
    @Serializable
    @SerialName("particle")
    data class Particle(
        override val seq: Int,
        val particle: String,
        val x: Double,
        val y: Double,
        val z: Double,
        val count: Int,
        val dx: Double,
        val dy: Double,
        val dz: Double,
        val speed: Double,
        val forced: Boolean
    ) : BotEvent

    @Serializable
    @SerialName("boss_bar")
    data class BossBar(override val seq: Int, val name: String, val shown: Boolean) : BotEvent

    /** The server moved the bot (a teleport, or a correction of a move it refused). */
    @Serializable
    @SerialName("teleport")
    data class Teleport(override val seq: Int, val x: Double, val y: Double, val z: Double) : BotEvent

    @Serializable
    @SerialName("death")
    data class Death(override val seq: Int, val message: String) : BotEvent

    /** A respawn or a change of world. */
    @Serializable
    @SerialName("respawn")
    data class Respawn(override val seq: Int, val world: String) : BotEvent

    /** The server told the bot a block changed: [state] is the block state it now draws there, `minecraft:gold_block`. */
    @Serializable
    @SerialName("block_change")
    data class BlockChange(override val seq: Int, val x: Int, val y: Int, val z: Int, val state: String) : BotEvent

    /**
     * The server told the bot what an entity wears or holds: [entity] is its
     * UUID, [slot] as scripts name it (`main_hand`, `head`, …), [item] its
     * kind, or null for nothing.
     */
    @Serializable
    @SerialName("equipment")
    data class Equipment(override val seq: Int, val entity: String, val slot: String, val item: String?) : BotEvent

    /** A book opened on the bot's screen, with each page's plain text. */
    @Serializable
    @SerialName("book_open")
    data class BookOpen(override val seq: Int, val pages: List<String>) : BotEvent

    /** The bot now looks through another entity's eyes ([entity], its UUID), or its own again (null). */
    @Serializable
    @SerialName("camera")
    data class Camera(override val seq: Int, val entity: String?) : BotEvent

    /** The server disconnected the bot; it's gone. */
    @Serializable
    @SerialName("kicked")
    data class Kicked(override val seq: Int, val reason: String) : BotEvent
}

/** A bot's events from `since` on. Older ones than the bot keeps (the last 1000) are gone: [dropped] says how many were asked for but lost. */
@Serializable
data class BotEvents(val events: List<BotEvent>, val next: Int, val dropped: Int = 0)

// ---- the bots extension ----------------------------------------------------

/**
 * Join a bot named [name], 3–16 letters, digits or `_`, through the same
 * configuration phase a client goes through. It joins where a player of that
 * name last was (the world spawn the first time), or at [at].
 */
@Serializable
data class BotJoinParams(val name: String, val at: BotPosition? = null, val resourcePack: BotPackAnswer = BotPackAnswer.ACCEPT)

/** A request about bot [name]. */
@Serializable
data class BotParams(val name: String)

/** Have bot [name] do [action]. */
@Serializable
data class BotActParams(val name: String, val action: BotAction)

/** Bot [name]'s events from sequence number [since] on. */
@Serializable
data class BotEventsParams(val name: String, val since: Int = 0)

/**
 * The bridge's `bots/…` requests. Not part of the core protocol: only a dev
 * server that runs bots answers them, and one without answers each with
 * method-not-found.
 */
object BotsExtension {
    /** Answered with [BotInfo] once the bot is in the world, ticks later. */
    val join = BridgeMethod("bots/join", BotJoinParams.serializer(), BotInfo.serializer())

    /** Disconnects the bot, as a player quitting. */
    val leave = BridgeMethod("bots/leave", BotParams.serializer(), Unit.serializer())

    /** The bots online. */
    val list = BridgeMethod("bots/list", Unit.serializer(), ListSerializer(BotInfo.serializer()))

    /**
     * Answered once the action is done, which for walking or mining is ticks
     * later (at most [BotAction.MAX_TICKS]); fails when the bot couldn't (out
     * of reach, no such slot, no dialog open).
     */
    val act = BridgeMethod("bots/act", BotActParams.serializer(), BotActResult.serializer())

    /** What the bot's screen shows now. */
    val state = BridgeMethod("bots/state", BotParams.serializer(), BotState.serializer())

    val events = BridgeMethod("bots/events", BotEventsParams.serializer(), BotEvents.serializer())

    val extension = BridgeExtension("bots", listOf(join, leave, list, act, state, events))
}
