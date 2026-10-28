package dev.netherforge.plugin.paper.version

import dev.netherforge.plugin.paper.DimensionStorage
import io.papermc.paper.world.PaperWorldLoader
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.network.protocol.game.ClientboundSetTimePacket
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.dedicated.DedicatedServerProperties
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.GsonHelper
import net.minecraft.world.clock.ClockNetworkState
import net.minecraft.world.clock.WorldClock
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.goal.GoalSelector
import net.minecraft.world.level.dimension.LevelStem
import net.minecraft.world.level.levelgen.WorldDimensions
import net.minecraft.world.level.levelgen.WorldGenSettings
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.level.storage.SavedDataStorage
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.craftbukkit.inventory.CraftItemStack
import java.util.Locale
import kotlin.jvm.optionals.getOrNull

/** [ServerInternals] on Minecraft 26.1.2. */
internal object Mojang : ServerInternals {
    override fun goalSelector(mob: Mob): GoalSelector = mob.goalSelector

    override val worlds = DimensionStorage

    /**
     * What 26.1.2's `CraftServer.createWorld` does for a world with no saved `world_gen_settings` (the preset made from
     * the world loader's worldgen registries, `{}` for no generator settings), with the level stem's type replaced,
     * saved where it reads them first: the dimension's own saved data.
     */
    override fun prepareDimension(creator: WorldCreator, type: String) {
        val server = MinecraftServer.getServer()
        val registryAccess = server.registryAccess()
        val stemKey = stemOf(creator.environment())
        val generatorSettings = creator.generatorSettings().ifEmpty { "{}" }
        val preset = DedicatedServerProperties.WorldDimensionData(
            GsonHelper.parse(generatorSettings),
            creator.type().name.lowercase(Locale.ROOT)
        ).create(server.worldLoaderContext.datapackWorldgen())
        val dimensionType = registryAccess.lookupOrThrow(Registries.DIMENSION_TYPE)
            .getOrThrow(ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.parse(type)))
        val stem = checkNotNull(preset.dimensions()[stemKey]) { "the world preset has no $stemKey" }
        val dimensions = WorldDimensions(preset.dimensions() + (stemKey to LevelStem(dimensionType, stem.generator())))
        val settings = WorldGenSettings(WorldOptions(creator.seed(), creator.generateStructures(), creator.bonusChest()), dimensions)
        val dimensionKey = PaperWorldLoader.dimensionKey(creator.key())
        val folder = server.storageSource.getDimensionPath(dimensionKey).resolve(LevelResource.DATA.id())
        val storage = SavedDataStorage(folder, server.fixerUpper, registryAccess)
        try {
            storage.set(WorldGenSettings.TYPE, settings)
            storage.saveAndJoin()
        } finally {
            storage.close()
        }
    }

    private fun stemOf(environment: World.Environment): ResourceKey<LevelStem> = when (environment) {
        World.Environment.NETHER -> LevelStem.NETHER
        World.Environment.THE_END -> LevelStem.END
        else -> LevelStem.OVERWORLD
    }

    override fun stopClocks(clocks: Collection<String>) {
        val server = MinecraftServer.getServer()
        for (key in clocks) {
            val clock = clockOf(server, key) ?: continue
            server.clockManager().setPaused(clock, true)
            server.clockManager().setTotalTicks(clock, 0)
        }
    }

    override fun sendClock(player: ServerPlayer, clock: String, ticks: Long) {
        val server = MinecraftServer.getServer()
        val holder = clockOf(server, clock) ?: return
        player.connection.send(ClientboundSetTimePacket(server.overworld().gameTime, mapOf(holder to ClockNetworkState(ticks, 0f, 0f))))
    }

    private fun clockOf(server: MinecraftServer, key: String): Holder<WorldClock>? {
        val id = Identifier.tryParse(key) ?: return null
        return server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).get(ResourceKey.create(Registries.WORLD_CLOCK, id)).getOrNull()
    }

    override fun mirror(stack: net.minecraft.world.item.ItemStack): org.bukkit.inventory.ItemStack = CraftItemStack.asCraftMirror(stack)
}
