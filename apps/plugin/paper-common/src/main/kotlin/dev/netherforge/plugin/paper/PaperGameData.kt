package dev.netherforge.plugin.paper

import dev.netherforge.format.game.BlockInfo
import dev.netherforge.format.game.BlockState
import dev.netherforge.format.game.Box
import dev.netherforge.format.game.GameData
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.game.ParticleDataKind
import dev.netherforge.format.game.ParticleInfo
import dev.netherforge.format.math.Vec3
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.block.BlockType
import org.bukkit.block.data.BlockData
import org.bukkit.inventory.ItemStack
import dev.netherforge.format.game.RegistryKey as GameRegistry

/**
 * [GameData] answered by the running server's own registries, so it's right
 * for whatever version this is and nothing is bundled in the jar.
 *
 * Registries and tags come from [registries] (the adapter's own, see
 * [PaperVersion]), by name; the rest from
 * Paper's API. Block properties come from enumerating a block's states (the API has no
 * property list), collision boxes from asking each state for its shape. Both
 * are cached: a block state never changes shape while the server runs.
 */
class PaperGameData(private val registries: ServerRegistries, private val readMapColors: () -> List<Int> = { emptyList() }) : GameData {
    /** The game's map colours, read off the server once. */
    override val mapColors: List<Int> by lazy { readMapColors() }

    override val minecraftVersion: String = Bukkit.getMinecraftVersion()

    private val blockRegistry get() = RegistryAccess.registryAccess().getRegistry(RegistryKey.BLOCK)
    private val itemRegistry get() = RegistryAccess.registryAccess().getRegistry(RegistryKey.ITEM)
    private val particleRegistry get() = RegistryAccess.registryAccess().getRegistry(RegistryKey.PARTICLE_TYPE)

    private val blocks = HashMap<String, BlockInfo?>()
    private val collision = HashMap<String, List<Box>?>()

    override fun block(id: String): BlockInfo? = blocks.getOrPut(id) { blockType(id)?.let(::infoOf) }

    override fun registry(key: GameRegistry): Set<String>? = registries.ids(key)

    override fun tag(key: GameRegistry, id: String): Set<String>? = registries.tag(key, id)

    override fun itemDurability(id: String): Int? =
        key(id)?.let { itemRegistry.get(it) }?.let { it.getDefaultData(DataComponentTypes.MAX_DAMAGE) ?: 0 }

    override fun particle(id: String): ParticleInfo? = key(id)?.let { particleRegistry.get(it) }?.let { ParticleInfo(kindOf(it)) }

    /** What a spawn of [particle] carries, from the API's data type. A spell is sent as its colour. */
    private fun kindOf(particle: Particle): ParticleDataKind = when (particle.dataType) {
        Void::class.java -> ParticleDataKind.NONE
        Particle.DustOptions::class.java -> ParticleDataKind.DUST
        Particle.DustTransition::class.java -> ParticleDataKind.DUST_TRANSITION
        Color::class.java, Particle.Spell::class.java -> ParticleDataKind.COLOR
        BlockData::class.java -> ParticleDataKind.BLOCK
        ItemStack::class.java -> ParticleDataKind.ITEM
        else -> ParticleDataKind.OTHER
    }

    override fun collisionBoxes(state: BlockState): List<Box>? = collision.getOrPut(state.toString()) {
        runCatching { shapeOf(Bukkit.createBlockData(state.toString())) }.getOrNull()
    }

    /** From the server jar's own `version.json` ([ServerVersionFile]). */
    override val resourcePackFormat: List<Int>? get() = ServerVersionFile.resourcePackFormat

    /** From the server jar's own `version.json` ([ServerVersionFile]). */
    override val dataPackFormat: List<Int>? get() = ServerVersionFile.dataPackFormat

    private fun key(id: String): NamespacedKey? = NamespacedKey.fromString(id)

    private fun blockType(id: String): BlockType? = key(id)?.let { blockRegistry.get(it) }

    private fun infoOf(type: BlockType): BlockInfo {
        val values = LinkedHashMap<String, LinkedHashSet<String>>()
        for (data in type.createBlockDataStates()) {
            for ((name, value) in propertiesOf(data)) values.getOrPut(name) { LinkedHashSet() } += value
        }
        return BlockInfo(
            properties = values.mapValues { it.value.toList() },
            defaults = propertiesOf(type.createBlockData())
        )
    }

    private fun propertiesOf(data: BlockData): Map<String, String> = BlockState.parse(data.asString)?.properties ?: emptyMap()

    /**
     * A state's collision boxes, freestanding: asked of the block data with
     * nothing around it, which is what a display floating in the world is.
     */
    private fun shapeOf(data: BlockData): List<Box> = data.getCollisionShape(sampleLocation()).boundingBoxes.map {
        Box(Vec3(it.minX, it.minY, it.minZ), Vec3(it.maxX, it.maxY, it.maxZ))
    }

    private fun sampleLocation(): Location {
        val world = Bukkit.getWorlds().first()
        return Location(world, 0.0, world.maxHeight - 1.0, 0.0)
    }

    /**
     * Everything the editor caches for this version: every registry and tag
     * the server has ([ServerRegistries]), and the facts that aren't id sets.
     * Collision is stored once
     * under the bare id for a block whose every state has the same shape (most
     * of them), and per canonical state otherwise; a block with more states
     * than [MAX_STATES] keeps its default state's shape under the id and its
     * first [MAX_STATES] states.
     */
    fun export(): GameDataBundle {
        val blockInfos = LinkedHashMap<String, BlockInfo>()
        val shapes = LinkedHashMap<String, List<Box>>()
        for (type in blockRegistry.stream().toList().sortedBy { it.key.toString() }) {
            val id = type.key.toString()
            val info = block(id) ?: continue
            blockInfos[id] = info
            val states = type.createBlockDataStates().take(MAX_STATES)
            val byState = states.associate { canonical(it) to runCatching { shapeOf(it) }.getOrDefault(emptyList()) }
            if (byState.values.distinct().size <= 1) {
                shapes[id] = byState.values.firstOrNull() ?: emptyList()
            } else {
                if (states.size == MAX_STATES) shapes[id] = runCatching { shapeOf(type.createBlockData()) }.getOrDefault(emptyList())
                shapes.putAll(byState)
            }
        }
        return GameDataBundle(
            minecraft = minecraftVersion,
            blocks = blockInfos,
            registries = registries.allIds(),
            tags = registries.allTags(),
            collision = shapes,
            packFormat = resourcePackFormat,
            dataPackFormat = dataPackFormat,
            particles = particleRegistry.stream().toList().sortedBy { it.key.toString() }.associate { it.key.toString() to kindOf(it) },
            mapColors = mapColors
        )
    }

    private fun canonical(data: BlockData): String = BlockState.parse(data.asString)?.toString() ?: data.asString

    private companion object {
        const val MAX_STATES = 1024
    }
}
