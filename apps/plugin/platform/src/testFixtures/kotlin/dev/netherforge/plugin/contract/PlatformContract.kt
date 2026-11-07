package dev.netherforge.plugin.contract

import dev.netherforge.format.bridge.BotActResult
import dev.netherforge.format.bridge.BotAction
import dev.netherforge.format.bridge.BotEvent
import dev.netherforge.format.bridge.BotPackAnswer
import dev.netherforge.format.bridge.BotPosition
import dev.netherforge.format.bridge.BotState
import dev.netherforge.format.item.ItemDef
import dev.netherforge.plugin.platform.BotAim
import dev.netherforge.plugin.platform.BotJoin
import dev.netherforge.plugin.platform.BotOps
import dev.netherforge.plugin.platform.EntityFlag
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.SpawnSetup
import dev.netherforge.plugin.platform.WatchedEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * What every contract suite shares: the server it runs against, and helpers
 * that undo what a test made (players leave, entities and teams go, blocks are
 * put back), so suites can share one real server.
 *
 * A suite is an abstract class per `Ops` interface saying what that interface
 * promises. A concrete subclass per server says which one: the runtime's
 * tests run each against the fake, each adapter's integration test against
 * Paper. Where they disagree, Paper is the truth and the fake is fixed.
 */
abstract class PlatformContract {
    /** The server this runs against: a fresh fake for each test, or the one real server. */
    protected abstract fun connect(): ContractServer

    protected lateinit var server: ContractServer
        private set

    protected val platform: Platform get() = server.platform
    protected val events: RecordingEvents get() = server.events
    protected val origin: Location get() = server.origin
    protected val world: String get() = server.origin.world

    private val joined = mutableListOf<PlayerRef>()
    private val spawned = mutableListOf<UUID>()
    private val tagged = mutableListOf<UUID>()

    /** Blocks changed, with what was there before, to put back. */
    private val placed = LinkedHashMap<Triple<Int, Int, Int>, String>()
    private val undo = mutableListOf<() -> Unit>()

    @BeforeEach
    fun connectServer() {
        server = connect()
        events.cancelling.clear()
        events.clear()
    }

    @AfterEach
    fun cleanUp() {
        events.cancelling.clear()
        main {
            for (action in undo.asReversed()) runCatching(action)
            for (id in spawned) platform.worldEntities.remove(id)
            for (id in tagged) platform.entities.remove(id)
            for ((at, state) in placed) platform.blocks.set(world, at.first, at.second, at.third, state, false)
        }
        for (player in joined.toList()) leave(player)
        server.itemLooks.clear()
        server.blockStates.clear()
    }

    protected fun <T> main(block: () -> T): T = server.main(block)

    protected fun ticks(count: Int = 1) = server.ticks(count)

    /** Waits up to [ticks] ticks for [check] (run on the main thread) to hold. */
    protected fun eventually(what: String, ticks: Int = 100, check: () -> Boolean) {
        repeat(ticks) {
            if (main(check)) return
            server.ticks(1)
        }
        if (!main(check)) fail("$what didn't happen within $ticks ticks; the runtime last heard ${events.all().takeLast(HEARD_IN_FAILURE)}")
    }

    /** Runs [action] when the test ends, on the main thread, last-registered first: puts back what the test changed. */
    protected fun afterwards(action: () -> Unit) {
        undo += action
    }

    /** [origin] moved by whole blocks. */
    protected fun at(dx: Int, dy: Int = 0, dz: Int = 0): Location = origin.offset(dx.toDouble(), dy.toDouble(), dz.toDouble())

    /** The block coordinates of [at]. */
    protected fun block(at: Location) =
        Triple(kotlin.math.floor(at.x).toInt(), kotlin.math.floor(at.y).toInt(), kotlin.math.floor(at.z).toInt())

    /** Sets a block (neighbours not told), putting back what was there when the test ends. Must run on the main thread. */
    protected fun place(at: Location, state: String) {
        val (x, y, z) = block(at)
        placed.getOrPut(Triple(x, y, z)) { platform.blocks.get(world, x, y, z)!!.state }
        check(platform.blocks.set(world, x, y, z, state, false)) { "couldn't place $state at $x $y $z" }
    }

    // ---- players --------------------------------------------------------------

    protected val bots: BotOps get() = platform.bots

    /**
     * A player who joins at [at] (declining any resource pack), and leaves
     * when the test ends. What the server heard while they joined is
     * forgotten, so a test's [events] start from here.
     */
    protected fun join(at: Location = origin, name: String = "nfc${NAMES.incrementAndGet()}"): PlayerRef {
        var result: Result<*>? = null
        main {
            bots.join(BotJoin(name, BotPosition(at.x, at.y, at.z, at.world), BotPackAnswer.DECLINE)) { result = it }
        }
        eventually("$name joining", ticks = 400) { result != null }
        result!!.getOrThrow()
        val player = main { platform.players.find(name) } ?: fail("$name joined but isn't online")
        joined += player
        events.clear()
        return player
    }

    /**
     * Bot [player] does [action] as a client would, and this waits until it
     * has (walking and mining take ticks): where it ended up. Fails with
     * why the bot couldn't.
     */
    protected fun act(player: PlayerRef, action: BotAction, aim: BotAim? = null): BotActResult {
        var result: Result<BotActResult>? = null
        main { bots.act(player.name, action, aim) { result = it } }
        eventually("${player.name} doing $action", ticks = BotAction.MAX_TICKS) { result != null }
        return result!!.getOrThrow()
    }

    // ---- what a player's client was sent (players are bots, which keep it) ---------

    /** What [player]'s client shows now: its menu, dialog, boss bars, sidebar, packs, … */
    protected fun screen(player: PlayerRef): BotState = main { bots.state(player.name) }

    /** The number [player]'s client's next event will have: what [sentSince] and [awaitSent] count from. */
    protected fun mark(player: PlayerRef): Int = screen(player).events

    /** Everything [player]'s client was sent from event [since] on. */
    protected fun sentSince(player: PlayerRef, since: Int): List<BotEvent> = main { bots.events(player.name, since).events }

    /**
     * Waits until [player]'s client has been sent what [find] looks for in
     * its events from [since] on (null: not yet), and answers what it found.
     */
    protected fun <T : Any> awaitSent(player: PlayerRef, since: Int, what: String, find: (List<BotEvent>) -> T?): T {
        var found: T? = null
        eventually("$what reaching ${player.name}") {
            found = find(bots.events(player.name, since).events)
            found != null
        }
        return found!!
    }

    /** Waits until [player]'s screen shows what [check] wants, and answers the screen. */
    protected fun awaitScreen(player: PlayerRef, what: String, check: (BotState) -> Boolean): BotState {
        eventually("${player.name}'s screen showing $what") { check(bots.state(player.name)) }
        return screen(player)
    }

    /**
     * Lets the server send what it would (a real client hears it a tick or two
     * later): after this, what [player]'s client wasn't sent, it won't be.
     */
    protected fun settled() = ticks(SETTLE_TICKS)

    /** The platform watches [event] (`Platform.watch`) until the test ends. */
    protected fun watching(event: WatchedEvent) {
        main { platform.watch(event, true) }
        afterwards { platform.watch(event, false) }
    }

    /** [player] leaves, as a quit; waits until they're gone. */
    protected fun leave(player: PlayerRef) {
        joined -= player
        main { if (platform.players.get(player.uuid) != null) bots.leave(player.name) }
        eventually("${player.name} leaving") { platform.players.get(player.uuid) == null }
    }

    // ---- entities ---------------------------------------------------------------

    /** Spawns a vanilla entity at [at], removed when the test ends; a mob without [ai] stands still and leaves players be. */
    protected fun spawn(kind: String, at: Location = at(4), setup: SpawnSetup = SpawnSetup(), ai: Boolean = true): UUID {
        val id = main {
            platform.worldEntities.spawn(kind, at, setup)?.also { if (!ai) platform.worldEntities.setFlag(it, EntityFlag.AI, false) }
        } ?: fail("couldn't spawn $kind at $at")
        spawned += id
        return id
    }

    /** Drops an item stack at [at], removed when the test ends. */
    protected fun drop(item: ItemData, at: Location = at(4, 1)): UUID {
        val id = main { platform.worldEntities.spawnItem(at.world, dev.netherforge.format.math.Vec3(at.x, at.y, at.z), item) }
            ?: fail("couldn't drop $item at $at")
        spawned += id
        return id
    }

    /** An entity [EntityOps] made: removed when the test ends. Answers [id]. */
    protected fun tagged(id: UUID?): UUID {
        assertNotNull(id, "the entity was made")
        tagged += id
        return id
    }

    protected fun item(kind: String, count: Int? = null) = ItemData(ItemDef(kind = kind, count = count))

    protected fun assertNear(expected: Double, actual: Double?, tolerance: Double = 1e-6, message: String? = null) {
        assertNotNull(actual, message)
        if (abs(expected - actual) > tolerance) assertEquals(expected, actual, message)
    }

    protected companion object {
        /** Ticks after which what the server sent has reached a bot: it reads packets once a tick. */
        private const val SETTLE_TICKS = 3

        /** A world no server has. */
        const val MISSING_WORLD = "nf_no_such_world"

        /** How many of the last calls the platform made a failed wait shows. */
        private const val HEARD_IN_FAILURE = 30

        /** Players' names are unique for the whole run: a real server may still be letting one go. */
        private val NAMES = AtomicInteger()
    }
}
