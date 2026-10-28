package dev.netherforge.plugin.platform

import dev.netherforge.format.game.GameData
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.item.ItemDef
import java.util.UUID

/**
 * Everything the runtime needs from a Minecraft server, in NetherForge's own
 * types.
 *
 * The runtime never sees a Paper class. One adapter per Minecraft version
 * (`apps/plugin/paper-<version>`) implements this with the Paper API, and the tests
 * implement it with an in-memory fake. That split is what lets a new Minecraft
 * version be a new adapter rather than a hunt through the runtime.
 *
 * Grouped by subject rather than flat, so a new capability is a new group
 * added without reshaping the others. Every group is required: an adapter
 * implements all of them, and the contract suites (the runtime's test
 * fixtures) check each against the fake and against Paper. A feature a
 * Minecraft version lacks is gated by version, never answered with a
 * missing group.
 *
 * Every call happens on the server's main thread unless it says otherwise.
 */
interface Platform {
    val info: ServerInfo

    /** Game facts from the live server's registries. Never a bundled table. */
    val game: GameData

    val log: PlatformLog
    val worlds: WorldOps
    val entities: EntityOps
    val players: PlayerOps
    val commands: CommandOps

    /** Blocks in the world: reading and changing them, and the data scripts keep on them. */
    val blocks: BlockOps

    /** Entities as scripts handle them, by UUID: mobs, dropped items, players (`platform/Entities.kt`). */
    val worldEntities: WorldEntityOps

    /** What only a mannequin has: its skin, the line under its name, its pose (`platform/Mannequins.kt`). */
    val mannequins: MannequinOps

    /** Real inventories: players', ender chests, container blocks', entities'. */
    val inventories: InventoryOps

    /** Entities' attributes (`platform/Attributes.kt`). */
    val attributes: AttributeOps

    /** Mobs walking paths (`platform/PathfindingOps.kt`). */
    val pathfinding: PathfindingOps

    /** Mobs' AI goals (`platform/MobGoalOps.kt`). */
    val mobGoals: MobGoalOps

    /** Boss bars. */
    val bossBars: BossBarOps

    /** Per-player sidebars. */
    val sidebars: SidebarOps

    /** Scoreboard teams and the line under name tags (`platform/Teams.kt`). */
    val teams: TeamOps

    /** Players' entries in the player list (`platform/Teams.kt`). */
    val playerList: PlayerListOps

    /** Particles. */
    val particles: ParticleOps

    /** Maps drawn as screens: their pixels, the frames they hang in (`platform/ScreenOps.kt`). */
    val screens: ScreenOps

    /** Sounds. */
    val sounds: SoundOps

    /** How the server is keeping up. */
    val performance: PerformanceOps

    /** MiniMessage, parsed the way the adapter parses everything players see. */
    val text: TextOps

    /** Menu windows. */
    val menus: MenuOps

    /** Dialog screens. */
    val dialogs: DialogOps

    /** Sending a resource pack to a player. */
    val resourcePacks: ResourcePackOps

    /** Project items on real stacks: rewriting stale looks. */
    val projectItems: ProjectItemOps

    /** How stacks look to the one player they're sent to ([ItemViewOps]). */
    val itemViews: ItemViewOps

    /** Recipes the project adds to the server, and players' recipe books. */
    val recipes: RecipeOps

    /** The game's own loot tables, rolled by the server (`platform/Loot.kt`). */
    val loot: LootOps

    /** Making, loading, copying and unloading worlds (`platform/WorldAdmin.kt`). */
    val worldManager: WorldManagerOps

    /** World borders and players' own. */
    val borders: BorderOps

    /** Saving and placing structures. */
    val structures: StructureOps

    /** What one player is shown that isn't so, and their camera, compass and view distance (`platform/PlayerAdmin.kt`). */
    val playerViews: PlayerViewOps

    /** The server's settings, who has played, the whitelist and bans. */
    val serverAdmin: ServerAdminOps

    /** The permission nodes the project sets on players. */
    val permissions: PermissionOps

    /** Players' advancements. */
    val advancements: AdvancementOps

    /** Which other plugins are on the server (`platform/Interop.kt`). */
    val plugins: PluginOps

    /** PlaceholderAPI (`platform/Interop.kt`): used only while it's enabled. */
    val placeholders: PlaceholderOps

    /**
     * The server's economy (Vault's), looked up now; null when Vault isn't
     * enabled or no economy plugin has registered with it. Economies register
     * after other plugins have enabled, so this is asked at each use and never
     * kept.
     */
    fun economy(): EconomyOps?

    /** The datapack the server started with, and building one again (`platform/Datapacks.kt`). */
    val datapacks: DatapackOps

    /** What players are shown the world's biomes as: a sky's twins ([SkyOps]). Nothing by default (a server without). */
    val skies: SkyOps get() = SkyOps.NONE

    /** Keeping a server alive while the debugger holds its main thread at a breakpoint. */
    val pause: PauseOps

    /**
     * Fake players (`platform/Bots.kt`): the dev bridge's, for tests and
     * agents, and scripts' (`nf.bots`). Whether anyone may make one is the
     * runtime's to decide (the bridge, the owner's `bots:`), not the adapter's.
     */
    val bots: BotOps

    /**
     * Connects the adapter to the runtime, once, when it enables, before
     * anything is shown: from then on the server's happenings go to [events]
     * (the adapter registers whatever listeners it needs; nothing else wires
     * them), and `<glyph:ui/coin>` in any MiniMessage the adapter parses
     * (names, lore, titles, dialogs, text displays, chat) inserts [glyphs]'
     * answer for the reference, in the default font, or nothing for null.
     * [glyphs] may be called from any thread.
     *
     * [items] answers a project item's current look by reference (`ruby`, or
     * the `shop:ruby` a stack carries; null for an item the project doesn't
     * have): every item the adapter builds whose [ItemDef.item] names one is
     * resolved against it (`ProjectItems.resolve`) and stamped with its
     * namespaced id and hash, and stacks stamped with an old hash are what
     * [ProjectItemOps.refresh] rewrites.
     *
     * [namespace] is the project's (it changes only when the project
     * restarts): what the adapter names everything it registers or persists
     * for the project under. References the runtime hands over are as files
     * and scripts write them (`ruby`, `ui/gem`); the adapter resolves them in
     * [namespace] when it writes them to the game (`shop:ruby` on a stack,
     * the item model `shop:ui/gem`, the recipe `shop:ruby_sword`, a modifier's
     * default id `shop:attack_damage_0`), and reads them back the same way,
     * the project's own without the namespace.
     *
     * [blocks] is the block state one of the project's blocks is held as now (canonical text,
     * `minecraft:note_block[instrument=bit,note=3,powered=true]`), by the name the server knows it by
     * (`ruby_ore`, `library:gem_ore`), or null for a block it doesn't hold: an item's `canBreak` and
     * `canPlaceOn` name the project's blocks, and the adapter writes their states. They can move when the
     * project's blocks change, which is when [ProjectItemOps.refresh] finds a stack stale.
     */
    fun bind(
        events: PlatformEvents,
        glyphs: (reference: String) -> String?,
        items: (reference: String) -> ItemLook?,
        blocks: (name: String) -> String?,
        namespace: () -> String
    )

    /**
     * How text moves its cursor (`SpaceTags`): [space] is what `<space:N>`
     * inserts (the pack's space characters, or null while no pack is built to
     * draw them), and [layout] is MiniMessage with each `<at>` turned into
     * the moves that place it, which the adapter applies to every text before
     * it parses it. Until it's called, `<space>` inserts nothing and `<at>`
     * draws what it holds in line.
     */
    fun bindText(space: (px: Int) -> String?, layout: (text: String) -> String)

    /**
     * Whether a script listens for [event] now. The adapter watches these
     * only while one does, because watching them costs even when nobody
     * cares: the runtime calls this whenever the answer changes (never twice
     * in a row with the same answer), starting from not listening.
     */
    fun watch(event: WatchedEvent, listening: Boolean)

    /**
     * Everything the editor caches about this Minecraft version: blocks with
     * their properties and defaults, items, entity types, and collision boxes.
     * Called rarely (once per version per machine), so it may take a moment.
     */
    fun exportGameData(): GameDataBundle

    /**
     * Stops the server cleanly (worlds saved, plugins disabled), as `/stop`
     * would. Only a dev server whose editor has gone away asks for this: see
     * [dev.netherforge.plugin.bridge.BridgeClient]. Called on the main thread.
     */
    fun shutdownServer(reason: String)
}

data class ServerInfo(
    /** The running server's Minecraft version, `26.3`. */
    val minecraftVersion: String,
    val pluginVersion: String,
    /** Project targets this adapter runs. A project naming anything else is refused. */
    val supportedTargets: List<String>
)

/** How the server is keeping up, as Minecraft measures it. */
interface PerformanceOps {
    /** Ticks a second, averaged over the last minute: 20 when it keeps up. */
    fun ticksPerSecond(): Double

    /** How long a tick has taken lately, in milliseconds, averaged over the last 100 ticks. */
    fun tickMilliseconds(): Double
}

/** MiniMessage helpers, with the adapter's own MiniMessage (glyph tags included). */
interface TextOps {
    /** [text] escaped so MiniMessage shows it as written rather than reading tags in it. */
    fun escape(text: String): String

    /** The plain text [miniMessage] reads as, tags removed. */
    fun strip(miniMessage: String): String
}

/**
 * What a server needs while the debugger holds its main thread at a
 * breakpoint, for as long as the person debugging likes: nothing ticks, so
 * the server must not decide it has hung (Paper's watchdog dumps the threads
 * after a few seconds and stops the server after a minute), and its players
 * must not time out (a client drops a server it hears nothing from for 30
 * seconds). Only a dev server ever pauses.
 */
interface PauseOps {
    /**
     * Called on the held main thread about once a second while it's paused:
     * tells the server's watchdog it's alive, and shows every online player
     * [message] on their action bar, which is also what keeps their
     * connections from timing out.
     */
    fun hold(message: String)
}

/** The server console. The runtime decides what also goes to the editor. */
interface PlatformLog {
    fun info(message: String)

    fun warn(message: String)

    fun error(message: String, cause: Throwable? = null)
}

/** A world by name and a position in blocks. [yaw] and [pitch] are degrees, for a player's facing. */
data class Location(val world: String, val x: Double, val y: Double, val z: Double, val yaw: Double = 0.0, val pitch: Double = 0.0) {
    fun offset(dx: Double, dy: Double, dz: Double) = copy(x = x + dx, y = y + dy, z = z + dz)
}

/** A ray in a world, as a player's line of sight. The direction is normalised. */
data class Ray(val world: String, val x: Double, val y: Double, val z: Double, val dx: Double, val dy: Double, val dz: Double)

data class PlayerRef(val uuid: UUID, val name: String)

/** A block in the world, for events: `id` is namespaced, `state` is the full canonical block state. */
data class BlockRef(val world: String, val x: Int, val y: Int, val z: Int, val id: String, val state: String)
