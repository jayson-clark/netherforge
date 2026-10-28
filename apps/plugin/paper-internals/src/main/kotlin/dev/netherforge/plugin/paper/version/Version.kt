package dev.netherforge.plugin.paper.version

import com.destroystokyo.paper.entity.ai.Goal
import dev.netherforge.plugin.paper.ItemViewHooks
import dev.netherforge.plugin.paper.PaperVersion
import dev.netherforge.plugin.paper.ServerRegistries
import dev.netherforge.plugin.paper.StackViewer
import dev.netherforge.plugin.paper.bots.PaperBots
import dev.netherforge.plugin.platform.BotOps
import dev.netherforge.plugin.platform.ChunkGrid
import io.netty.buffer.Unpooled
import io.papermc.paper.configuration.GlobalConfiguration
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket
import net.minecraft.resources.Identifier
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.decoration.GlowItemFrame
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerListener
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.Palette
import net.minecraft.world.level.chunk.PalettedContainer
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.saveddata.maps.MapId
import net.minecraft.world.level.saveddata.maps.MapItemSavedData
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.bukkit.Instrument
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.craftbukkit.block.data.CraftBlockData
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Vector
import org.spigotmc.WatchdogThread
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Optional
import java.util.WeakHashMap
import kotlin.jvm.optionals.getOrNull
import net.minecraft.resources.ResourceKey as MojangKey

/** This adapter's [PaperVersion], listed in `META-INF/services`. */

class Version : PaperVersion {
    override fun mapColors(): List<Int> = (0 until MAP_COLORS).map { packed ->
        val base = packed shr 2
        val color = MapColor.byId(base)
        // An id the game has no colour for answers its "no colour", whose own shades are transparent too.
        if (base == 0 || color.id != base) 0 else MapColor.getColorFromPackedId(packed)
    }

    override fun hangFrame(
        world: World,
        x: Int,
        y: Int,
        z: Int,
        facing: BlockFace,
        glowing: Boolean,
        item: org.bukkit.inventory.ItemStack,
        prepare: (org.bukkit.entity.Entity) -> Unit
    ): org.bukkit.entity.Entity? {
        val level = (world as CraftWorld).handle
        val direction = when (facing) {
            BlockFace.NORTH -> Direction.NORTH
            BlockFace.SOUTH -> Direction.SOUTH
            BlockFace.EAST -> Direction.EAST
            BlockFace.WEST -> Direction.WEST
            BlockFace.UP -> Direction.UP
            else -> Direction.DOWN
        }
        val pos = BlockPos(x, y, z)
        val frame = if (glowing) GlowItemFrame(level, pos, direction) else ItemFrame(level, pos, direction)
        // Fixed before it's in the world: a fixed frame never asks for a wall, can't be turned and drops nothing.
        frame.fixed = true
        frame.isInvisible = true
        frame.setItem(CraftItemStack.asNMSCopy(item), false)
        prepare(frame.bukkitEntity)
        if (!level.addFreshEntity(frame, CreatureSpawnEvent.SpawnReason.CUSTOM)) return null
        return frame.bukkitEntity
    }

    override fun sendMap(player: Player, map: Int, x: Int, y: Int, width: Int, height: Int, colors: ByteArray) {
        val patch = MapItemSavedData.MapPatch(x, y, width, height, colors)
        val packet = ClientboundMapItemDataPacket(MapId(map), 0.toByte(), true, Optional.empty(), Optional.of(patch))
        (player as CraftPlayer).handle.connection.send(packet)
    }

    override fun bots(plugin: JavaPlugin): BotOps = PaperBots(plugin)

    override fun itemViews(viewer: StackViewer): ItemViewHooks = ItemViews(viewer)

    override fun fingerprintStates(world: World, chunkX: Int, chunkZ: Int, wanted: Collection<BlockData>): Long? {
        val chunk = (world as CraftWorld).handle.chunkSource.getChunkNow(chunkX, chunkZ) ?: return null
        val states = serverStates(wanted)
        var print = 1L
        val memos = sectionMemos(chunk, states)
        for ((i, section) in chunk.sections.withIndex()) {
            if (section.hasOnlyAir()) continue
            val raw = PaletteData.of(section.states) ?: return null
            if (!memos.mayHold(i, raw.palette) { section.states.maybeHas(states::contains) }) continue
            print = print * 31 + i
            print = print * 31 + raw.storage.raw.contentHashCode()
            // The palette's states too: the same numbers in the storage could stand for other states.
            val palette = raw.palette
            for (id in 0 until palette.size) print = print * 31 + System.identityHashCode(palette.valueFor(id))
        }
        return print
    }

    override fun sendBiomes(player: Player, chunks: Collection<Pair<Int, Int>>, twinOf: (String) -> String?) {
        val level = (player.world as CraftWorld).handle
        val biomes = level.registryAccess().lookupOrThrow(Registries.BIOME)
        val swapped = IdentityHashMap<Holder<Biome>, Holder<Biome>>()
        fun swap(holder: Holder<Biome>): Holder<Biome> = swapped.getOrPut(holder) {
            val key = holder.unwrapKey().getOrNull()?.identifier()?.toString() ?: return@getOrPut holder
            val twin = twinOf(key)?.let(Identifier::tryParse) ?: return@getOrPut holder
            biomes.get(MojangKey.create(Registries.BIOME, twin)).getOrNull() ?: holder
        }
        val data = chunks.mapNotNull { (chunkX, chunkZ) ->
            val chunk = level.chunkSource.getChunkNow(chunkX, chunkZ) ?: return@mapNotNull null
            val buffer = FriendlyByteBuf(Unpooled.buffer())
            for (section in chunk.sections) {
                @Suppress("UNCHECKED_CAST")
                val cells = (section.biomes as PalettedContainer<Holder<Biome>>).copy()
                for (y in 0..3) for (z in 0..3) for (x in 0..3) cells.getAndSetUnchecked(x, y, z, swap(cells.get(x, y, z)))
                cells.write(buffer)
            }
            val bytes = ByteArray(buffer.readableBytes())
            buffer.readBytes(bytes)
            ClientboundChunksBiomesPacket.ChunkBiomeData(chunk.pos, bytes)
        }
        val connection = (player as CraftPlayer).handle.connection
        for (batch in data.chunked(BIOME_BATCH)) connection.send(ClientboundChunksBiomesPacket(batch))
    }

    override fun readBox(
        world: World,
        x0: Int,
        y0: Int,
        z0: Int,
        x1: Int,
        y1: Int,
        z1: Int,
        cells: IntArray,
        states: MutableList<BlockData>
    ): Boolean {
        val level = (world as CraftWorld).handle
        val sizeX = x1 - x0 + 1
        val sizeZ = z1 - z0 + 1
        val numbers = IdentityHashMap<BlockState, Int>()
        val outside = Blocks.VOID_AIR.defaultBlockState()
        for (chunkX in (x0 shr 4)..(x1 shr 4)) {
            for (chunkZ in (z0 shr 4)..(z1 shr 4)) {
                val chunk = level.chunkSource.getChunkNow(chunkX, chunkZ) ?: return false
                val sections = chunk.sections
                val fromX = maxOf(x0, chunkX shl 4)
                val toX = minOf(x1, (chunkX shl 4) + 15)
                val fromZ = maxOf(z0, chunkZ shl 4)
                val toZ = minOf(z1, (chunkZ shl 4) + 15)
                for (y in y0..y1) {
                    val section = sections.getOrNull(chunk.getSectionIndex(y))
                    for (z in fromZ..toZ) {
                        var i = (fromX - x0) + sizeX * ((z - z0) + sizeZ * (y - y0))
                        for (x in fromX..toX) {
                            val state = section?.getBlockState(x and 15, y and 15, z and 15) ?: outside
                            cells[i++] = numbers.getOrPut(state) {
                                states += CraftBlockData.createData(state)
                                states.size - 1
                            }
                        }
                    }
                }
            }
        }
        return true
    }

    /** The server's own states of [wanted], by identity: worked out once for the same collection, as callers ask again and again. */
    private fun serverStates(wanted: Collection<BlockData>): Set<BlockState> {
        lastStates?.takeIf { it.first === wanted }?.let { return it.second }
        val states = Collections.newSetFromMap(IdentityHashMap<BlockState, Boolean>())
        for (data in wanted) states += (data as CraftBlockData).state
        lastStates = wanted to states
        return states
    }

    private var lastStates: Pair<Collection<BlockData>, Set<BlockState>>? = null

    /**
     * Whether each of a chunk's sections may hold any of the states asked about, as last worked out: by its
     * palette's identity and size, since a palette only grows, and a new one is a new object. The same answer
     * then holds without running the question over every entry of every section's palette.
     */
    private class SectionMemos(val states: Set<BlockState>, size: Int) {
        private val palettes = arrayOfNulls<Any>(size)
        private val sizes = IntArray(size)
        private val answers = BooleanArray(size)

        inline fun mayHold(section: Int, palette: Palette<*>, ask: () -> Boolean): Boolean {
            if (palettes[section] === palette && sizes[section] == palette.size) return answers[section]
            val answer = ask()
            palettes[section] = palette
            sizes[section] = palette.size
            answers[section] = answer
            return answer
        }
    }

    private val memos = WeakHashMap<LevelChunk, SectionMemos>()

    private fun sectionMemos(chunk: LevelChunk, states: Set<BlockState>): SectionMemos {
        val known = memos[chunk]
        if (known != null && known.states === states) return known
        return SectionMemos(states, chunk.sections.size).also { memos[chunk] = it }
    }

    override fun findStates(world: World, chunkX: Int, chunkZ: Int, wanted: Map<BlockData, Int>, found: ChunkGrid): Boolean {
        val chunk = (world as CraftWorld).handle.chunkSource.getChunkNow(chunkX, chunkZ) ?: return false
        // The server's own states, by identity: a state is one object for as long as the server runs.
        val numbers = IdentityHashMap<BlockState, Int>()
        for ((data, number) in wanted) numbers[(data as CraftBlockData).state] = number
        val bottom = chunk.minSectionY
        for ((i, section) in chunk.sections.withIndex()) {
            if (section.hasOnlyAir() || !section.states.maybeHas(numbers::containsKey)) continue
            val y0 = (bottom + i) shl 4
            // Block by block: PalettedContainer.getAll answers each value the section holds once, not each block.
            for (y in 0..15) {
                for (z in 0..15) {
                    for (x in 0..15) {
                        val number = numbers[section.getBlockState(x, y, z)] ?: continue
                        found[x, y0 + y, z] = number
                    }
                }
            }
        }
        return true
    }

    override fun goalPriorities(mob: Mob): Map<Goal<Mob>, Int> = dev.netherforge.plugin.paper.version.goalPriorities(mob)

    override fun saveAdvancements(player: Player) = (player as CraftPlayer).handle.advancements.save()

    override val registries: ServerRegistries = MojangRegistries

    override val worlds = Mojang.worlds

    override fun watchRegistryErrors(refused: (String) -> Unit): AutoCloseable = RegistryErrorLog.watch(refused)

    override fun prepareDimension(creator: org.bukkit.WorldCreator, type: String) = Mojang.prepareDimension(creator, type)

    override fun stopClocks(clocks: Collection<String>) = Mojang.stopClocks(clocks)

    override fun sendClock(player: Player, clock: String, ticks: Long) = Mojang.sendClock((player as CraftPlayer).handle, clock, ticks)

    // The same on every supported server: a static `tick()` that sets its last tick to now.
    override fun holdWatchdog() = WatchdogThread.tick()

    override fun watchSlots(player: Player, size: Int, changed: (Set<Int>) -> Unit) {
        (player as CraftPlayer).handle.containerMenu.addSlotListener(
            object : ContainerListener {
                override fun slotChanged(menu: AbstractContainerMenu, slot: Int, stack: ItemStack) {
                    if (slot in 0 until size) changed(setOf(slot))
                }

                override fun dataChanged(menu: AbstractContainerMenu, id: Int, value: Int) {}
            }
        )
    }

    // Paper's own switch, the same on every supported server: `block-updates.disable-noteblock-updates`.
    override fun noteBlockUpdatesDisabled(): Boolean = GlobalConfiguration.get().blockUpdates.disableNoteblockUpdates

    override fun setNoteBlockUpdatesDisabled(disabled: Boolean): Boolean {
        GlobalConfiguration.get().blockUpdates.disableNoteblockUpdates = disabled
        return GlobalConfiguration.get().blockUpdates.disableNoteblockUpdates == disabled
    }

    // Paper's own switch beside it: `block-updates.disable-tripwire-updates`.
    override fun tripwireUpdatesDisabled(): Boolean = GlobalConfiguration.get().blockUpdates.disableTripwireUpdates

    override fun setTripwireUpdatesDisabled(disabled: Boolean): Boolean {
        GlobalConfiguration.get().blockUpdates.disableTripwireUpdates = disabled
        return GlobalConfiguration.get().blockUpdates.disableTripwireUpdates == disabled
    }

    /** What the note block's own `setInstrument` works out: a mob head above plays its own, else what's below (a base block, or the harp when that's a head). */
    override fun noteInstrument(block: Block): Instrument {
        val level = (block.world as CraftWorld).handle
        val pos = BlockPos(block.x, block.y, block.z)
        val above = level.getBlockState(pos.above()).instrument()
        val chosen = if (above.worksAboveNoteBlock()) {
            above
        } else {
            val below = level.getBlockState(pos.below()).instrument()
            if (below.worksAboveNoteBlock()) NoteBlockInstrument.HARP else below
        }
        // The API's enum lists the game's in the same order, which is how Paper converts the block data's too.
        return Instrument.entries[chosen.ordinal]
    }

    override fun useItemOn(player: Player, hand: EquipmentSlot, block: Block, face: BlockFace, point: Vector): Boolean {
        val handle = (player as CraftPlayer).handle
        val side = if (hand == EquipmentSlot.OFF_HAND) InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND
        val stack = handle.getItemInHand(side)
        if (stack.isEmpty) return false
        val hit = BlockHitResult(Vec3(point.x, point.y, point.z), Direction.valueOf(face.name), BlockPos(block.x, block.y, block.z), false)
        val result = stack.useOn(UseOnContext(handle.level(), handle, side, stack, hit))
        if (result.consumesAction()) player.swingHand(hand)
        return result.consumesAction()
    }

    override fun startUsingItem(player: Player, hand: EquipmentSlot) {
        val side = if (hand == EquipmentSlot.OFF_HAND) InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND
        (player as CraftPlayer).handle.startUsingItem(side)
    }

    override fun playNote(block: Block) {
        val level = (block.world as CraftWorld).handle
        val pos = BlockPos(block.x, block.y, block.z)
        // What the note block does when it plays: the event the sound and particle come from, and the vibration sculk hears.
        level.blockEvent(pos, Blocks.NOTE_BLOCK, 0, 0)
        level.gameEvent(null, GameEvent.NOTE_BLOCK_PLAY, pos)
    }
}

/** Chunks a chunk-biomes packet carries at most: the game's own sends one per chunk it changed. */
private const val BIOME_BATCH = 64

/** How many bytes a map pixel can be: every map colour index. */
private const val MAP_COLORS = 256
