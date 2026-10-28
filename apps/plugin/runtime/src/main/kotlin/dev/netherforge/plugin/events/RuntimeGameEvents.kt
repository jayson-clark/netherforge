package dev.netherforge.plugin.events
import dev.netherforge.plugin.NetherForgeRuntime
import dev.netherforge.plugin.binding.EventSession
import dev.netherforge.plugin.binding.EventType
import dev.netherforge.plugin.binding.GameEventDispatch
import dev.netherforge.plugin.binding.InventoryKeys
import dev.netherforge.plugin.binding.LuaEvent
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.LuaLocation
import dev.netherforge.plugin.binding.StatusEffect
import dev.netherforge.plugin.binding.entityHandle
import dev.netherforge.plugin.binding.livingHandle
import dev.netherforge.plugin.binding.mobHandle
import dev.netherforge.plugin.platform.BlockRef
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.StatusEffectData
import dev.netherforge.plugin.session.block
import dev.netherforge.plugin.session.changed
import dev.netherforge.plugin.session.handle
import dev.netherforge.plugin.session.placed
import java.util.UUID

/**
 * The server's events ([dev.netherforge.plugin.platform.GameEvents]), raised to the session
 * running now by the generated [GameEventDispatch]. What's here is only what the session's
 * services do around some of them: they hear of a join before the scripts and of a quit after,
 * save a chunk's blocks once its scripts have had it, and give an opened inventory's stacks
 * their item's new look before anyone sees them.
 */
internal class RuntimeGameEvents(private val runtime: NetherForgeRuntime) : GameEventDispatch(Running(runtime)) {
    private val session get() = runtime.session

    /** Every service hears of them first (the pack, bars they see, what's hidden from them), then scripts. */
    override fun playerJoin(event: GameEvent.PlayerJoin) {
        session.playerJoined(event.player)
        super.playerJoin(event)
    }

    /** Scripts hear of it first, while what the services keep for the player is there; then the services forget them. */
    override fun playerQuit(event: GameEvent.PlayerQuit) {
        val session = session
        super.playerQuit(event)
        session.playerQuit(event.player)
    }

    /** Scripts first, so what they change in its blocks' data is saved with it. */
    override fun chunkUnload(event: GameEvent.Chunk) {
        val session = session
        super.chunkUnload(event)
        session.chunkUnloading(event.world, event.x, event.z)
    }

    /** The services first: the blocks in it are the project's, tracked, before a script asks. */
    override fun chunkLoad(event: GameEvent.ChunkLoad) {
        if (session.running) session.chunkLoaded(event.world, event.x, event.z, event.generated)
        super.chunkLoad(event)
    }

    /** What mining one of the project's blocks takes is set before scripts hear of it (they may then make it instant, or stop it). */
    override fun blockStartBreak(event: GameEvent.BlockStartBreak): Boolean {
        if (!session.running) return false
        val refused = session.customBlocks.startedBreaking(event)
        return super.blockStartBreak(event) || refused
    }

    /** A block that's held by what the server remembers of it doesn't go with a piston. */
    override fun pistonExtend(event: GameEvent.Piston): Boolean {
        val held = session.running && session.customBlocks.pushes(event.blocks)
        return super.pistonExtend(event) || held
    }

    override fun pistonRetract(event: GameEvent.Piston): Boolean {
        val held = session.running && session.customBlocks.pushes(event.blocks)
        return super.pistonRetract(event) || held
    }

    /** What scripts left breaking, the project's blocks (and the game's with drops of the project's) break with their drops. */
    override fun entityExplode(event: GameEvent.Explode): Boolean {
        val cancelled = super.entityExplode(event)
        if (!cancelled &&
            event.breaksBlocks &&
            session.running &&
            session.customBlocks.explodable()
        ) {
            session.customBlocks.exploded(event.blocks(), event.yield)
        }
        return cancelled
    }

    override fun blockExplode(event: GameEvent.Explode): Boolean {
        val cancelled = super.blockExplode(event)
        if (!cancelled &&
            event.breaksBlocks &&
            session.running &&
            session.customBlocks.explodable()
        ) {
            session.customBlocks.exploded(event.blocks(), event.yield)
        }
        return cancelled
    }

    /** The services first, so a world's configured spawn rates are in place before scripts hear of it. */
    override fun worldLoad(event: GameEvent.World) {
        if (session.running) session.worldLoaded(event.world)
        super.worldLoad(event)
    }

    /** Stacks in it from before the last change to an item take its new look as it's seen. */
    override fun playerOpenInventory(event: GameEvent.PlayerInventory): Boolean {
        if (session.running) event.inventory?.let(session.items::refresh)
        return super.playerOpenInventory(event)
    }

    /** The session running now, as the dispatch asks for it. */
    private class Running(private val runtime: NetherForgeRuntime) : EventSession {
        private val session get() = runtime.session

        override val running: Boolean get() = session.running

        override fun listening(stages: List<Pair<EventType<*>, LuaHandle?>>): Boolean {
            val scripts = session.scripts
            return stages.any { (event, target) -> scripts.listening(target, event) }
        }

        override fun <P : LuaEvent> emit(stages: List<Pair<EventType<out P>, LuaHandle?>>, payload: P): Boolean =
            session.scripts.emit(stages, payload)

        override fun player(player: PlayerRef) = session.handle(player)

        override fun entity(id: UUID) = session.entityHandle(id)

        override fun living(id: UUID) = session.livingHandle(id)

        override fun mob(id: UUID) = session.mobHandle(id)

        override fun block(block: BlockRef) = session.block(block)

        override fun world(name: String) = LuaHandle.World(name)

        override fun inventory(inventory: InventoryRef) = InventoryKeys.of(inventory)

        override fun location(location: Location) = LuaLocation.of(location)

        override fun statusEffect(effect: StatusEffectData) =
            StatusEffect(effect.effect, effect.ticks.toLong(), effect.amplifier.toLong(), effect.ambient, effect.particles, effect.icon)

        override fun placed(location: LuaLocation, was: Location) = session.placed(location, was)

        override fun <T : ItemData?> item(after: T, before: T): T {
            if (after == null || before == null) return after
            return if (session.changed(listOf(after), listOf(before))) after else before
        }

        override fun items(after: List<ItemData>, before: List<ItemData>) = if (session.changed(after, before)) after else before
    }
}
