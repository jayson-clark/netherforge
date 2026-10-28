package dev.netherforge.plugin.paper.version

import dev.netherforge.plugin.paper.WorldFolders
import net.minecraft.SharedConstants
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.NbtUtils
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.dedicated.DedicatedServerProperties
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.GsonHelper
import net.minecraft.world.Difficulty
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.goal.GoalSelector
import net.minecraft.world.level.dimension.LevelStem
import net.minecraft.world.level.levelgen.WorldDimensions
import net.minecraft.world.level.levelgen.WorldGenSettings
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.storage.LevelResource
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.craftbukkit.inventory.CraftItemStack
import java.nio.file.Files
import java.util.Locale

/** [ServerInternals] on Minecraft 1.21.11. */
internal object Mojang : ServerInternals {
    override fun goalSelector(mob: Mob): GoalSelector = mob.goalSelector

    override val worlds = WorldFolders

    /**
     * What 1.21.11's `CraftServer.createWorld` does for a world folder with no `level.dat` (the preset made from the
     * world loader's worldgen registries, `{}` for no generator settings; the level's game mode and difficulty), with
     * the level stem's type replaced, saved as that `level.dat`: `createWorld` then reads the world's dimensions from
     * it as from any saved world, and makes it afresh (`initialized` false: the spawn is picked, the bonus chest
     * placed). Written as `PrimaryLevelData.setTagData` writes one, its fields that a new world has: that method can't
     * be called before the world exists (it stores the world's persistent data).
     */
    override fun prepareDimension(creator: WorldCreator, type: String) {
        val server = MinecraftServer.getServer()
        val registries = server.registryAccess()
        val stemKey = stemOf(creator.environment())
        val preset = DedicatedServerProperties.WorldDimensionData(
            GsonHelper.parse(creator.generatorSettings().ifEmpty { "{}" }),
            creator.type().name.lowercase(Locale.ROOT)
        ).create(server.worldLoaderContext.datapackWorldgen())
        val dimensionType = registries.lookupOrThrow(Registries.DIMENSION_TYPE)
            .getOrThrow(ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.parse(type)))
        val stem = checkNotNull(preset.dimensions()[stemKey]) { "the world preset has no $stemKey" }
        val dimensions = WorldDimensions(preset.dimensions() + (stemKey to LevelStem(dimensionType, stem.generator())))
        val options = WorldOptions(creator.seed(), creator.generateStructures(), creator.bonusChest())
        val version = SharedConstants.getCurrentVersion()
        val data = CompoundTag()
        data.put(
            "Version",
            CompoundTag().apply {
                putString("Name", version.name())
                putInt("Id", version.dataVersion().version())
                putBoolean("Snapshot", !version.stable())
                putString("Series", version.dataVersion().series())
            }
        )
        NbtUtils.addCurrentDataVersion(data)
        data.put(
            "WorldGenSettings",
            WorldGenSettings.encode(registries.createSerializationContext(NbtOps.INSTANCE), options, dimensions).getOrThrow()
        )
        data.putString("LevelName", creator.name())
        data.putInt("version", ANVIL_VERSION)
        data.putInt("GameType", Bukkit.getDefaultGameMode().value)
        data.putBoolean("hardcore", creator.hardcore())
        data.putByte("Difficulty", Difficulty.EASY.id.toByte())
        data.putBoolean("allowCommands", false)
        data.putBoolean("initialized", false)
        val folder = Bukkit.getWorldContainer().toPath().resolve(creator.name())
        Files.createDirectories(folder)
        NbtIo.writeCompressed(CompoundTag().apply { put("Data", data) }, folder.resolve(LevelResource.LEVEL_DATA_FILE.id))
    }

    /** The storage version `level.dat` says (the Anvil format's). */
    private const val ANVIL_VERSION = 19133

    private fun stemOf(environment: World.Environment): ResourceKey<LevelStem> = when (environment) {
        World.Environment.NETHER -> LevelStem.NETHER
        World.Environment.THE_END -> LevelStem.END
        else -> LevelStem.OVERWORLD
    }

    /** No world clocks before 26.1: nothing to stop. */
    override fun stopClocks(clocks: Collection<String>) {}

    /** No world clocks before 26.1: nothing to send. */
    override fun sendClock(player: ServerPlayer, clock: String, ticks: Long) {}

    override fun mirror(stack: net.minecraft.world.item.ItemStack): org.bukkit.inventory.ItemStack = CraftItemStack.asCraftMirror(stack)
}
