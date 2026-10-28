package dev.netherforge.plugin.paper.bots

import dev.netherforge.format.bridge.BotBossBar
import dev.netherforge.format.bridge.BotItem
import dev.netherforge.format.bridge.BotListEntry
import dev.netherforge.format.bridge.BotMenu
import dev.netherforge.format.bridge.BotPack
import dev.netherforge.format.bridge.BotSidebar
import dev.netherforge.format.bridge.BotTeam
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.numbers.FixedFormat
import net.minecraft.network.chat.numbers.NumberFormat
import net.minecraft.network.protocol.game.ClientboundBossEventPacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundResetScorePacket
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket
import net.minecraft.network.protocol.game.ClientboundSetScorePacket
import net.minecraft.server.dialog.Dialog
import net.minecraft.world.BossEvent
import net.minecraft.world.entity.EntityType
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.scores.DisplaySlot
import java.util.UUID

/**
 * What a bot's client knows and shows, kept from the packets it was sent and
 * nothing else: the same bookkeeping a real client does, so a test sees what
 * a player would (a sidebar line is what the scoreboard packets add up to,
 * not what the server meant to show).
 *
 * Main thread only.
 */
internal class BotScreen {
    /** A container window the client has open: the player's own inventory (id 0) always is. */
    class Container(val id: Int, val type: MenuType<*>?, val title: String) {
        var stateId = 0
        var items: List<ItemStack> = emptyList()
    }

    val inventory = Container(0, null, "")

    /** The menu on screen, if the server opened one. */
    var menu: Container? = null

    var cursor: ItemStack = ItemStack.EMPTY

    var dialog: Dialog? = null

    val bossBars = LinkedHashMap<UUID, BotBossBar>()

    /** Resource packs offered, by id, in the order they came. */
    val packs = LinkedHashMap<UUID, BotPack>()

    /** Entities the client has been sent, by network id: their UUID and type. */
    val entities = HashMap<Int, Pair<UUID, EntityType<*>>>()

    private class Title(val text: String, val until: Int)

    private var title: Title? = null
    private var subtitle: Title? = null
    private var actionBar: Title? = null
    private var fadeIn = DEFAULT_FADE_IN
    private var stay = DEFAULT_STAY
    private var fadeOut = DEFAULT_FADE_OUT

    /**
     * The stack the client was last sent for slot [slot] of the player's inventory (numbered as the runtime numbers
     * it: hotbar 0–8, the rest 9–35, armour 36–39 from the feet, the offhand 40), where its inventory screen draws
     * it; null for a slot it has no place for (41 on) or before it was sent the inventory. What the server holds
     * can differ: a stack can look different to one player (`ItemViewOps`).
     */
    fun shown(slot: Int): ItemStack? {
        val index = when (slot) {
            in 0..8 -> InventoryMenu.USE_ROW_SLOT_START + slot
            in InventoryMenu.INV_SLOT_START until InventoryMenu.INV_SLOT_END -> slot
            in ARMOR_FEET..ARMOR_HEAD -> InventoryMenu.ARMOR_SLOT_END - 1 - (slot - ARMOR_FEET)
            OFF_HAND -> InventoryMenu.SHIELD_SLOT
            else -> return null
        }
        return inventory.items.getOrNull(index)
    }

    /**
     * Copies the player's slots at the end of the open menu into the inventory: the client's windows all show the
     * one inventory, so what the server sends a menu's copy of a slot is in the inventory too, after it closes.
     */
    fun shareInventory() {
        val menu = menu ?: return
        if (menu.items.size < PLAYER_SLOTS || inventory.items.size < InventoryMenu.USE_ROW_SLOT_END) return
        val base = menu.items.size - PLAYER_SLOTS
        inventory.items = inventory.items.toMutableList().apply {
            for (i in 0 until PLAYER_SLOTS) set(InventoryMenu.INV_SLOT_START + i, menu.items[base + i])
        }
    }

    /** The open container a click goes to: the menu, or the inventory when none is. */
    fun clicked(): Container = menu ?: inventory

    fun container(id: Int): Container? = when (id) {
        0 -> inventory
        menu?.id -> menu
        else -> null
    }

    fun showTitle(text: Component, now: Int) {
        title = Title(text.getString(), now + fadeIn + stay + fadeOut)
    }

    fun showSubtitle(text: Component, now: Int) {
        subtitle = Title(text.getString(), now + fadeIn + stay + fadeOut)
    }

    fun showActionBar(text: Component, now: Int) {
        actionBar = Title(text.getString(), now + ACTION_BAR_TICKS)
    }

    fun titleTimes(fadeIn: Int, stay: Int, fadeOut: Int) {
        this.fadeIn = fadeIn
        this.stay = stay
        this.fadeOut = fadeOut
    }

    fun clearTitles(resetTimes: Boolean) {
        title = null
        subtitle = null
        if (resetTimes) titleTimes(DEFAULT_FADE_IN, DEFAULT_STAY, DEFAULT_FADE_OUT)
    }

    fun title(now: Int) = title?.takeIf { now < it.until }?.text

    fun subtitle(now: Int) = subtitle?.takeIf { now < it.until }?.text

    fun actionBar(now: Int) = actionBar?.takeIf { now < it.until }?.text

    fun menu(): BotMenu? {
        val menu = menu ?: return null
        // The menu's own slots come first; the client's inventory (36) is drawn below them.
        val size = (menu.items.size - PLAYER_SLOTS).coerceAtLeast(0)
        return BotMenu(
            type = menu.type?.let { BuiltInRegistries.MENU.getKey(it).toString() } ?: "minecraft:inventory",
            title = menu.title,
            size = size,
            items = menu.items.take(size).mapIndexedNotNull { slot, stack -> item(slot, stack) },
            cursor = item(-1, cursor)
        )
    }

    // ---- boss bars -------------------------------------------------------------

    /** Applies a boss bar packet; [shown] hears about bars that appear and go. */
    fun bossBar(packet: ClientboundBossEventPacket, shown: (name: String, shown: Boolean) -> Unit) {
        packet.dispatch(object : ClientboundBossEventPacket.Handler {
            override fun add(
                id: UUID,
                name: Component,
                progress: Float,
                color: BossEvent.BossBarColor,
                overlay: BossEvent.BossBarOverlay,
                darkenScreen: Boolean,
                playMusic: Boolean,
                createWorldFog: Boolean
            ) {
                val bar = BotBossBar(name.getString(), progress.toDouble(), color.getName(), overlay.getName())
                bossBars[id] = bar
                shown(bar.name, true)
            }

            override fun remove(id: UUID) {
                bossBars.remove(id)?.let { shown(it.name, false) }
            }

            override fun updateProgress(id: UUID, progress: Float) {
                bossBars.computeIfPresent(id) { _, bar -> bar.copy(progress = progress.toDouble()) }
            }

            override fun updateName(id: UUID, name: Component) {
                bossBars.computeIfPresent(id) { _, bar -> bar.copy(name = name.getString()) }
            }

            override fun updateStyle(id: UUID, color: BossEvent.BossBarColor, overlay: BossEvent.BossBarOverlay) {
                bossBars.computeIfPresent(id) { _, bar -> bar.copy(color = color.getName(), overlay = overlay.getName()) }
            }
        })
    }

    // ---- the scoreboard: the sidebar, teams and the line under name tags ------

    private class Score(val value: Int, val display: Component?, val format: NumberFormat?)

    private class Team(var look: TeamLook?, val members: MutableSet<String>) {
        val prefix: Component get() = look?.prefix ?: Component.empty()
        val suffix: Component get() = look?.suffix ?: Component.empty()
    }

    private val objectives = HashMap<String, Component>()
    private val scores = HashMap<String, HashMap<String, Score>>()
    private val teams = HashMap<String, Team>()
    private var sidebarObjective: String? = null
    private var belowNameObjective: String? = null

    fun objective(packet: ClientboundSetObjectivePacket) {
        val name = packet.objectiveName
        when (packet.method) {
            ClientboundSetObjectivePacket.METHOD_REMOVE -> {
                objectives.remove(name)
                scores.remove(name)
                // The client empties the slots that showed it.
                if (sidebarObjective == name) sidebarObjective = null
                if (belowNameObjective == name) belowNameObjective = null
            }
            else -> objectives[name] = packet.displayName
        }
    }

    fun displayObjective(packet: ClientboundSetDisplayObjectivePacket) {
        val name = packet.objectiveName?.takeIf { it.isNotEmpty() }
        when (packet.slot) {
            DisplaySlot.SIDEBAR -> sidebarObjective = name
            DisplaySlot.BELOW_NAME -> belowNameObjective = name
            else -> {}
        }
    }

    fun score(packet: ClientboundSetScorePacket) {
        scores.getOrPut(packet.objectiveName()) { HashMap() }[packet.owner()] =
            Score(packet.score(), packet.display().orElse(null), packet.numberFormat().orElse(null))
    }

    fun resetScore(packet: ClientboundResetScorePacket) {
        val objective = packet.objectiveName()
        if (objective == null) scores.values.forEach { it.remove(packet.owner()) } else scores[objective]?.remove(packet.owner())
    }

    fun team(packet: ClientboundSetPlayerTeamPacket) {
        val name = packet.name
        if (packet.teamAction == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
            teams.remove(name)
            return
        }
        val team = teams.getOrPut(name) { Team(null, HashSet()) }
        packet.parameters.ifPresent { team.look = Protocol.teamLook(it) }
        when (packet.playerAction) {
            ClientboundSetPlayerTeamPacket.Action.ADD -> team.members += packet.players
            ClientboundSetPlayerTeamPacket.Action.REMOVE -> team.members -= packet.players.toSet()
            null -> {}
        }
    }

    /** The sidebar as the client draws it: highest score first, a line's own text or its team's prefix, owner and suffix. */
    fun sidebar(): BotSidebar? {
        val name = sidebarObjective ?: return null
        val title = objectives[name] ?: return null
        val lines = scores[name].orEmpty().entries
            .sortedWith(compareByDescending<Map.Entry<String, Score>> { it.value.value }.thenBy { it.key })
            .take(SIDEBAR_LINES)
            .map { (owner, score) ->
                score.display?.getString() ?: teams.values.firstOrNull { owner in it.members }
                    ?.let { it.prefix.getString() + owner + it.suffix.getString() } ?: owner
            }
        return BotSidebar(title.getString(), lines)
    }

    /** The teams as the client knows them, by name. */
    fun teams(): List<BotTeam> = teams.entries.sortedBy { it.key }.map { (name, team) ->
        val look = team.look
        BotTeam(
            name = name,
            displayName = look?.displayName?.getString() ?: name,
            prefix = team.prefix.getString(),
            suffix = team.suffix.getString(),
            color = look?.color,
            friendlyFire = look != null && look.options and FRIENDLY_FIRE != 0,
            seeInvisibleTeammates = look != null && look.options and SEE_INVISIBLE != 0,
            nametags = look?.nameTags ?: "always",
            collision = look?.collision ?: "always",
            members = team.members.sorted()
        )
    }

    // ---- the player list -------------------------------------------------------

    private class ListEntry(val name: String, val uuid: UUID) {
        var listed = false
        var displayName: Component? = null
        var order = 0
    }

    private val list = HashMap<UUID, ListEntry>()

    /** Applies a player info packet the way the client's player list does. */
    fun playerInfo(packet: ClientboundPlayerInfoUpdatePacket) {
        for (entry in packet.newEntries()) {
            val profile = entry.profile() ?: continue
            list.putIfAbsent(entry.profileId(), ListEntry(profile.name(), entry.profileId()))
        }
        for (entry in packet.entries()) {
            val known = list[entry.profileId()] ?: continue
            for (action in packet.actions()) {
                when (action) {
                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED -> known.listed = entry.listed()
                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME -> known.displayName = entry.displayName()
                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LIST_ORDER -> known.order = entry.listOrder()
                    else -> {}
                }
            }
        }
    }

    fun playerInfoRemove(packet: ClientboundPlayerInfoRemovePacket) {
        packet.profileIds().forEach(list::remove)
    }

    /** Everyone the client's player list knows, by name, with the line it would draw under each one's name tag. */
    fun playerList(): List<BotListEntry> = list.values.sortedBy { it.name }.map {
        BotListEntry(it.name, it.uuid.toString(), it.listed, it.displayName?.getString(), it.order, belowName(it.name))
    }

    /** The line under [owner]'s name tag: their below-name score, formatted as the client would; null with no score. */
    private fun belowName(owner: String): String? {
        val score = scores[belowNameObjective ?: return null]?.get(owner) ?: return null
        return (score.format as? FixedFormat)?.value()?.getString() ?: score.value.toString()
    }

    companion object {
        /** The bits of a team's options byte: members can hurt each other, and see each other invisible. */
        private const val FRIENDLY_FIRE = 1
        private const val SEE_INVISIBLE = 2

        /** The player's inventory slots every container window ends with. */
        const val PLAYER_SLOTS = 36
        const val SIDEBAR_LINES = 15
        const val DEFAULT_FADE_IN = 10
        const val DEFAULT_STAY = 70
        const val DEFAULT_FADE_OUT = 20
        const val ACTION_BAR_TICKS = 60

        /** The runtime's numbers for the feet and head armour slots and the offhand. */
        private const val ARMOR_FEET = 36
        private const val ARMOR_HEAD = 39
        private const val OFF_HAND = 40

        /** An item stack as a [BotItem]; null when the slot is empty. */
        fun item(slot: Int, stack: ItemStack): BotItem? {
            if (stack.isEmpty) return null
            return BotItem(
                slot = slot,
                item = BuiltInRegistries.ITEM.getKey(stack.item).toString(),
                count = stack.count,
                name = stack.hoverName.getString(),
                lore = stack.get(DataComponents.LORE)?.lines()?.map { it.getString() }.orEmpty()
            )
        }
    }
}
