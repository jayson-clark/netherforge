package dev.netherforge.plugin.contract

import dev.netherforge.plugin.platform.BlockRef
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.DeathAnswer
import dev.netherforge.plugin.platform.DialogAnswers
import dev.netherforge.plugin.platform.DropsAnswer
import dev.netherforge.plugin.platform.EntityTag
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.GameEventFunnel
import dev.netherforge.plugin.platform.GameEvents
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.ItemView
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.MenuClick
import dev.netherforge.plugin.platform.PickAnswer
import dev.netherforge.plugin.platform.PlatformEvents
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.Ray
import dev.netherforge.plugin.platform.StructureMarker
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * What a platform told the runtime, as the contract suites see it: every call
 * of [PlatformEvents] and [GameEvents] is remembered by its method's name with
 * its arguments (a [GameEvents] one's payload), and answered as a runtime with
 * no scripts answers (nothing changed, nothing cancelled), unless a suite asks
 * for an event to be [cancelling]. The platform calls on the server's main
 * thread; suites read from theirs.
 */
class RecordingEvents : PlatformEvents {
    /** One call: the method's name and its arguments, in order. */
    data class Heard(val event: String, val args: List<Any?>)

    private val heard = ArrayList<Heard>()

    /** Events, by method name, answered as cancelled (true, or a null answer where null cancels). */
    val cancelling: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Everything heard so far, in order. */
    fun all(): List<Heard> = synchronized(heard) { heard.toList() }

    /**
     * Every event a suite has read calls of and found some: on a server the
     * suites share (Paper's), the events they've seen it raise between them.
     */
    val looked: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The arguments of every call of [event], in order. */
    fun of(event: String): List<List<Any?>> =
        all().filter { it.event == event }.map { it.args }.also { if (it.isNotEmpty()) looked += event }

    fun clear() = synchronized(heard) { heard.clear() }

    /** Remembers a call; true when it's to be cancelled. */
    private fun hear(event: String, vararg args: Any?): Boolean {
        synchronized(heard) { heard += Heard(event, args.toList()) }
        return event in cancelling
    }

    /** The generated events, each remembered by its method's name with its payload as the one argument. */
    override val game: GameEvents = GameEventFunnel { method, event -> hear(method, event) }

    /** The payload of every call of the generated event [method], in order. */
    inline fun <reified T : GameEvent> heard(method: String): List<T> = of(method).map { it.single() as T }

    // Every tick: nothing a suite needs, and the most frequent call by far.
    override fun tick() {}

    /** Heard, and the stack as it is: no handlers here. */
    override fun itemRendered(stack: ItemData): ItemData = stack.also { hear("itemRendered", it) }

    /** How a stack looks to the player it's sent to, as a suite says: as it is, until one sets this. */
    @Volatile
    var view: ((player: UUID, stack: ItemData) -> ItemView?)? = null

    /** Whether a suite set a [view]; heard only then, since it's asked for every stack sent. */
    override fun itemViewing(reference: String?): Boolean = (view != null).also { if (it) hear("itemViewing", reference) }

    override fun itemViewed(player: UUID, stack: ItemData): ItemView? {
        hear("itemViewed", player, stack)
        return view?.invoke(player, stack)
    }

    override fun playerInteract(player: PlayerRef, button: ClickButton, block: BlockRef?, face: String?, item: ItemData?, hand: String) =
        hear("playerInteract", player, button, block, face, item, hand)

    override fun playerUseItem(player: PlayerRef, item: ItemData, hand: String) = hear("playerUseItem", player, item, hand)

    /** Whether a bow draws with no ammo, as a suite says: it doesn't, until one sets this. */
    @Volatile var ammoFree = false

    override fun drawsWithoutAmmo(item: ItemData): Boolean = ammoFree.also { hear("drawsWithoutAmmo", item) }

    override fun generatedLoot(table: String, player: UUID?, location: Location): List<ItemData>? {
        hear("generatedLoot", table, player, location)
        return emptyList()
    }

    override fun blockBreak(player: PlayerRef, block: BlockRef, drops: () -> List<ItemData>, experience: Int): DropsAnswer? =
        if (hear("blockBreak", player, block, experience)) null else DropsAnswer(null, experience)

    override fun blockStopBreak(player: PlayerRef, block: BlockRef) {
        hear("blockStopBreak", player, block)
    }

    override fun worldBroke(block: BlockRef): List<ItemData>? {
        hear("worldBroke", block)
        return null
    }

    override fun blockUpdated(block: BlockRef) {
        hear("blockUpdated", block)
    }

    override fun fallingLanded(entity: UUID, block: BlockRef) {
        hear("fallingLanded", entity, block)
    }

    /** What a suite answers a pick with; null (the default) leaves it to the game. */
    @Volatile var pick: PickAnswer? = null

    override fun pickBlock(player: PlayerRef, block: BlockRef, inventory: List<ItemData?>): PickAnswer? {
        hear("pickBlock", player, block, inventory)
        return pick
    }

    override fun playerDied(
        player: PlayerRef,
        killer: UUID?,
        cause: String,
        message: String?,
        keepInventory: Boolean,
        drops: List<ItemData>,
        experience: Int
    ): DeathAnswer {
        hear("playerDied", player, killer, cause, message, keepInventory, drops, experience)
        return DeathAnswer(message, keepInventory, null, experience)
    }

    override fun playerDropItem(player: PlayerRef, item: ItemData) = hear("playerDropItem", player, item)

    override fun playerPickupItem(player: PlayerRef, item: ItemData, entity: UUID) = hear("playerPickupItem", player, item, entity)

    override fun playerConsumeItem(player: PlayerRef, item: ItemData) = hear("playerConsumeItem", player, item)

    override fun worldSaving(world: String) {
        hear("worldSaving", world)
    }

    override fun pluginsChanged() {
        hear("pluginsChanged")
    }

    override fun entityClicked(entity: UUID, player: PlayerRef, button: ClickButton, sight: Ray?) =
        hear("entityClicked", entity, player, button, sight)

    override fun entitiesLoaded(tagged: Map<UUID, EntityTag>, untagged: List<UUID>) {
        hear("entitiesLoaded", tagged, untagged)
    }

    override fun structureMarkersLoaded(markers: List<StructureMarker>) {
        hear("structureMarkersLoaded", markers)
    }

    override fun entitiesUnloading(entities: List<UUID>) {
        hear("entitiesUnloading", entities)
    }

    override fun entityDamaged(entity: UUID, amount: Double, cause: String, attacker: UUID?): Double? =
        if (hear("entityDamaged", entity, amount, cause, attacker)) null else amount

    override fun entityDied(entity: UUID, killer: UUID?, drops: () -> List<ItemData>, experience: Int): DropsAnswer {
        hear("entityDied", entity, killer, experience)
        return DropsAnswer(null, experience)
    }

    override fun playerInteractEntity(player: PlayerRef, entity: UUID, hand: String) = hear("playerInteractEntity", player, entity, hand)

    override fun resourcePackStatus(player: UUID, pack: UUID, status: String?) {
        hear("resourcePackStatus", player, pack, status)
    }

    override fun menuClicked(window: UUID, click: MenuClick) = hear("menuClicked", window, click)

    override fun menuDragged(window: UUID, player: PlayerRef, slots: List<Int>, cursor: ItemData?, ownSlots: List<Int>, click: String) =
        hear("menuDragged", window, player, slots, cursor, ownSlots, click)

    override fun menuClosed(window: UUID, player: PlayerRef) {
        hear("menuClosed", window, player)
    }

    override fun menuChanged(window: UUID, slots: Set<Int>) {
        hear("menuChanged", window, slots)
    }

    override fun dialogPressed(player: PlayerRef, dialog: String, button: String, values: Map<String, Any>) {
        hear("dialogPressed", player, dialog, button, values)
    }

    override fun dialogClosed(player: PlayerRef, dialog: String) {
        hear("dialogClosed", player, dialog)
    }

    override fun customClicked(player: PlayerRef, id: String, answers: DialogAnswers): Boolean {
        hear("customClicked", player, id)
        return false
    }
}
