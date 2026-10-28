package dev.netherforge.plugin.paper.version

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import dev.netherforge.plugin.paper.ItemViewHooks
import dev.netherforge.plugin.paper.StackViewer
import net.minecraft.core.component.TypedDataComponent
import net.minecraft.network.HashedPatchMap
import net.minecraft.network.HashedStack
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.HashOps
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerSynchronizer
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.inventory.RemoteSlot
import net.minecraft.world.item.ItemStack
import org.bukkit.Bukkit
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Player
import java.lang.reflect.Field
import org.bukkit.inventory.ItemStack as BukkitStack

/**
 * [ItemViewHooks] on the server's own container syncing.
 *
 * Every window a player has open (their own inventory's included) sends their game its stacks through the
 * player's `ContainerSynchronizer`: the whole window when it opens or is sent again, a slot when it changes, the
 * cursor. Each window takes the player's synchronizer when it opens (`ServerPlayer.initMenu`), so [install]
 * puts a [Synchronizer] of ours in the player's own (a final field, set by reflection: nothing else hands
 * windows theirs) and gives it to the windows open now; it hands each stack to the [viewer] and sends what that
 * answers, through the server's own synchronizer.
 *
 * What the server remembers of what the game holds (a window's `RemoteSlot`s) stays the real stacks, so a stack
 * that only looks different is never sent again by itself. A click tells the server what the game holds after
 * it as hashes of what it was shown; a [ViewSlot] takes the hash of the look it last sent for a stack as that
 * stack, so a click doesn't make the server send back every stack it touched. The looks sent for each real stack
 * are remembered ([Synchronizer.views], the most recent few hundred), which is also how a stack the game sends
 * back whole (a creative-mode click) is mapped back to the stack it was shown for ([realOf]).
 *
 * Stacks the server sends some other way than through a window's synchronizer (a crafting grid's result, an
 * item picked into the hotbar, a slot Bukkit sets directly) reach the game as they are until the slot next
 * changes or is refreshed: a slot the server sets directly is in step again at its next sync, since the server's
 * own record of it doesn't change.
 */
internal class ItemViews(private val viewer: StackViewer) : ItemViewHooks {
    override fun install(player: Player) {
        val handle = (player as CraftPlayer).handle
        val current = handle.containerSynchronizer
        if (current is Synchronizer && current.owner === this) return
        // Another platform's hooks (the contract tests run one beside the plugin's own) give way to the newest.
        val ours = Synchronizer(handle, (current as? Synchronizer)?.server ?: current)
        SYNCHRONIZER.set(handle, ours)
        // Each window keeps the synchronizer it opened with; giving it ours sends it again, as it looks.
        handle.inventoryMenu.setSynchronizer(ours)
        if (handle.containerMenu !== handle.inventoryMenu) handle.containerMenu.setSynchronizer(ours)
    }

    override fun uninstall(player: Player) {
        val handle = (player as CraftPlayer).handle
        val ours = (handle.containerSynchronizer as? Synchronizer)?.takeIf { it.owner === this } ?: return
        SYNCHRONIZER.set(handle, ours.server)
        handle.inventoryMenu.setSynchronizer(ours.server)
        if (handle.containerMenu !== handle.inventoryMenu) handle.containerMenu.setSynchronizer(ours.server)
    }

    override fun refresh(player: Player) {
        val handle = (player as CraftPlayer).handle
        (handle.containerSynchronizer as? Synchronizer)?.takeIf { it.owner === this }?.refresh()
    }

    override fun realOf(player: Player, stack: BukkitStack): BukkitStack? {
        val handle = (player as CraftPlayer).handle
        val ours = (handle.containerSynchronizer as? Synchronizer)?.takeIf { it.owner === this } ?: return null
        val real = ours.realOf(CraftItemStack.unwrap(stack)) ?: return null
        return Mojang.mirror(real)
    }

    /** One player's synchronizer: the server's own ([server]) for sending, with each stack as it looks to them. */
    private inner class Synchronizer(private val player: ServerPlayer, val server: ContainerSynchronizer) : ContainerSynchronizer {
        /** Whose hooks these are. */
        val owner: ItemViews get() = this@ItemViews

        /** Hashes of components, as the server's own synchronizer works them out, for the game's hashes to be checked against. */
        private val hashes: LoadingCache<TypedDataComponent<*>, Int> = CacheBuilder.newBuilder()
            .maximumSize(HASHES)
            .build(
                object : CacheLoader<TypedDataComponent<*>, Int>() {
                    private val ops = player.registryAccess().createSerializationContext(HashOps.CRC32C_INSTANCE)

                    override fun load(component: TypedDataComponent<*>): Int = component.encodeValue(ops)
                        .getOrThrow { IllegalArgumentException("Failed to hash $component: $it") }
                        .asInt()
                }
            )
        val hasher = HashedPatchMap.HashGenerator { hashes.getUnchecked(it) }

        /** The look last sent for each real stack (count aside), the most recently used last. */
        private val views = object : LinkedHashMap<Key, ItemStack>(LOOKS, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ItemStack>) = size > LOOKS
        }

        /** The real stack (count aside) each look sent stands for, the most recently used last. */
        private val reals = object : LinkedHashMap<Key, ItemStack>(LOOKS, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ItemStack>) = size > LOOKS
        }

        /** What the game was last sent for the cursor, as it looked. */
        private var cursor: ItemStack = ItemStack.EMPTY

        /** Whether a stack is being worked out now: a handler that sends stacks itself sends them as they are. */
        private var viewing = false

        /** [stack] as it looks to the player: [stack] itself when it looks as it is. */
        fun view(stack: ItemStack): ItemStack {
            if (stack.isEmpty || viewing || !Bukkit.isPrimaryThread()) return stack
            viewing = true
            val shown = try {
                viewer.view(player.bukkitEntity, Mojang.mirror(stack))
            } finally {
                viewing = false
            }
            val look = shown?.let(CraftItemStack::asNMSCopy)?.takeUnless { it.isEmpty || ItemStack.matches(it, stack) }
            if (look == null) {
                // It's sent as it is now: a hash of how it looked before is no longer the game in step.
                if (views.isNotEmpty()) views.remove(Key(stack))
                return stack
            }
            val key = Key(stack.copyWithCount(1))
            views[key] = look.copyWithCount(1)
            reals[Key(look.copyWithCount(1))] = key.stack
            return look
        }

        /** The look last sent for [stack] (with its count), or null for none: what the game's hash of it may be of. */
        fun lastView(stack: ItemStack): ItemStack? = views[Key(stack)]?.copyWithCount(stack.count)

        /** The real stack the game was shown as [stack] (with its count), or null when it was shown none like it. */
        fun realOf(stack: ItemStack): ItemStack? {
            if (stack.isEmpty) return null
            return reals[Key(stack)]?.copyWithCount(stack.count)
        }

        override fun sendInitialData(container: AbstractContainerMenu, items: List<ItemStack>, carried: ItemStack, data: IntArray) {
            val shown = items.mapIndexed { slot, stack -> view(stack).also { sent(container, slot, it) } }
            cursor = view(carried).copy()
            server.sendInitialData(container, shown, cursor, data)
        }

        override fun sendSlotChange(container: AbstractContainerMenu, slot: Int, stack: ItemStack) {
            val shown = view(stack)
            sent(container, slot, shown)
            server.sendSlotChange(container, slot, shown)
        }

        override fun sendCarriedChange(container: AbstractContainerMenu, stack: ItemStack) {
            cursor = view(stack).copy()
            server.sendCarriedChange(container, cursor)
        }

        override fun sendDataChange(container: AbstractContainerMenu, id: Int, value: Int) = server.sendDataChange(container, id, value)

        // Paper's: the offhand isn't one of another window's slots, so it's sent as the inventory's.
        override fun sendOffHandSlotChange() {
            val inventory = player.inventoryMenu
            sendSlotChange(inventory, InventoryMenu.SHIELD_SLOT, inventory.getSlot(InventoryMenu.SHIELD_SLOT).item.copy())
        }

        override fun createSlot(): RemoteSlot = ViewSlot(this)

        /** Records that [container]'s [slot] was sent as [shown]. */
        private fun sent(container: AbstractContainerMenu, slot: Int, shown: ItemStack) {
            (container.remoteSlots.getOrNull(slot) as? ViewSlot)?.shown = shown.copy()
        }

        /**
         * Works out every stack of the window open now and the cursor again, sending each that looks different
         * from what the game was last sent for it. A slot whose real stack changed since is left to the
         * server's own sync at the end of the tick. While another window is open, the game shows only that
         * one (and the hotbar through it): the inventory's own slots it isn't showing (armour, the offhand)
         * are worked out again when it next looks at them through a refresh.
         */
        fun refresh() {
            val menu = player.containerMenu
            for (index in menu.slots.indices) {
                val slot = menu.remoteSlots.getOrNull(index) as? ViewSlot ?: continue
                val real = menu.getSlot(index).item
                if (!slot.matches(real)) continue
                val shown = view(real)
                val last = slot.shown
                if (last != null && ItemStack.matches(last, shown)) continue
                slot.shown = shown.copy()
                server.sendSlotChange(menu, index, shown)
            }
            val carried = view(menu.carried)
            if (!ItemStack.matches(carried, cursor)) {
                cursor = carried.copy()
                server.sendCarriedChange(menu, cursor)
            }
        }
    }

    /**
     * What the server remembers of one slot of the player's game, as the server's own does (the real stack it
     * was sent, or the hash the game said it holds), and the look it was last sent ([shown], null when the game
     * said what it holds since). A hash of the look last sent for a stack matches that stack.
     */
    private class ViewSlot(private val views: ItemViews.Synchronizer) : RemoteSlot.Synchronized(views.hasher) {
        var shown: ItemStack? = null

        /** The hash the game last said it holds, while it isn't matched yet. */
        private var hash: HashedStack? = null

        override fun force(stack: ItemStack) {
            super.force(stack)
            hash = null
            // Sent as it is, unless the synchronizer says how it looked (it does, right after, when it's the one sending).
            shown = stack.copy()
        }

        override fun receive(stack: HashedStack) {
            super.receive(stack)
            hash = stack
            shown = null
        }

        override fun matches(stack: ItemStack): Boolean {
            val said = hash
            if (super.matches(stack)) {
                if (said != null) {
                    hash = null
                    shown = stack.copy()
                }
                return true
            }
            if (said == null) return false
            val look = views.lastView(stack) ?: return false
            if (!said.matches(look, views.hasher)) return false
            super.force(stack)
            hash = null
            shown = look
            return true
        }

        override fun copyFrom(other: RemoteSlot.Synchronized) {
            super.copyFrom(other)
            hash = (other as? ViewSlot)?.hash
            shown = (other as? ViewSlot)?.shown
        }
    }

    /** A stack as a map key: its item and components, count aside. */
    private class Key(val stack: ItemStack) {
        override fun hashCode(): Int = ItemStack.hashItemAndComponents(stack)

        override fun equals(other: Any?): Boolean = other is Key && ItemStack.isSameItemSameComponents(stack, other.stack)
    }

    private companion object {
        /** As many component hashes as the server's own synchronizer keeps. */
        const val HASHES = 256L

        /** Looks remembered per player: far more kinds of stack than a game shows at once. */
        const val LOOKS = 512

        /** `ServerPlayer.containerSynchronizer`: final, and what every window the player opens takes. */
        val SYNCHRONIZER: Field = ServerPlayer::class.java.getDeclaredField("containerSynchronizer").apply { isAccessible = true }
    }
}
