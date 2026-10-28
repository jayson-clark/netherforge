package dev.netherforge.plugin.paper.version

import dev.netherforge.plugin.paper.WorldStorage
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.goal.GoalSelector
import net.minecraft.world.item.ItemStack
import org.bukkit.WorldCreator

/**
 * What the shared server code here (`apps/plugin/paper-internals/src/main`)
 * reaches differently on each Minecraft version. Each adapter's `Mojang`
 * object (`apps/plugin/paper-<minecraft>/src/main`) answers it for its own
 * server; the bots have their own, `BotProtocol`.
 */
internal interface ServerInternals {
    /** A mob's goal selector (the one that isn't for targets). */
    fun goalSelector(mob: Mob): GoalSelector

    /** Where this version keeps a world's files: Paper's API is the same either way. */
    val worlds: WorldStorage

    /**
     * Saves the generation settings of the new world [creator] describes, as `createWorld` would make them for it (the
     * world preset of its type, its generator settings, seed and structures), but with dimension type [type] (a key
     * the server has: `showcase:deep`) for its environment's level stem. `createWorld` then finds a saved world's
     * settings and makes the world with that type: Bukkit has no other way to give a world a datapack's dimension type
     * (see [dev.netherforge.plugin.paper.PaperVersion.prepareDimension]). Where they're saved is the version's: the
     * world's own `world_gen_settings` saved data from 26.1, its `level.dat` before.
     */
    fun prepareDimension(creator: WorldCreator, type: String)

    /**
     * Stops each of the world clocks [clocks] (keys: `showcase:sky/showcase/blood_moon`) the server has at tick 0, as they'd
     * otherwise run from the start and be saved with the world: a sky held at night ([dev.netherforge.format.sky.SkyNight])
     * shows nothing at 0. Nothing on a version without world clocks (before 26.1).
     */
    fun stopClocks(clocks: Collection<String>)

    /** Tells [player] alone the world clock [clock] is at [ticks], not running. Nothing for a clock the server hasn't. */
    fun sendClock(player: ServerPlayer, clock: String, ticks: Long)

    /** [stack] as a Bukkit stack that is the very stack (no copy): `asCraftMirror`, named `asBukkitMirror` from 26.3. */
    fun mirror(stack: ItemStack): org.bukkit.inventory.ItemStack
}
