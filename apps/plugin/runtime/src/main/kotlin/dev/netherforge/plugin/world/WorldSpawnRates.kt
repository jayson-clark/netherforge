package dev.netherforge.plugin.world

import dev.netherforge.format.game.GameIds
import dev.netherforge.format.project.WorldConfig
import dev.netherforge.plugin.platform.GameRuleType
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.session.RuntimeService
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The spawn rates and game rules `netherforge.json`'s `worlds` sets (`spawnLimits`,
 * `spawnIntervals`, `gameRules`): put in place for every world it names that's loaded when
 * the session starts (so a reload re-applies them, over what scripts set with
 * `world:set_spawn_limit`), and for each one loaded or created later.
 * Categories it doesn't name keep whatever the world has.
 */
internal class WorldSpawnRates(
    private val platform: Platform,
    /** Told about a game rule it can't set. */
    private val warn: (String) -> Unit = {},
    private val worlds: () -> Map<String, WorldConfig>?
) : RuntimeService {
    override val name get() = "spawn rates"

    override fun start() {
        for (world in worlds().orEmpty().keys) {
            if (platform.worlds.exists(world)) apply(world)
        }
    }

    override fun worldLoaded(world: String) = apply(world)

    private fun apply(world: String) {
        val config = worlds()?.get(world) ?: return
        for ((category, limit) in config.spawnLimits.orEmpty()) platform.worlds.setSpawnLimit(world, category, limit)
        for ((category, ticks) in config.spawnIntervals.orEmpty()) platform.worlds.setSpawnInterval(world, category, ticks)
        for ((rule, value) in config.gameRules.orEmpty()) setRule(world, rule, value)
    }

    /** Sets one of `gameRules`, as the rule takes it; a rule the server lacks, or a value of the wrong kind, is said in the log. */
    private fun setRule(world: String, rule: String, value: JsonPrimitive) {
        val id = GameIds.normalize(rule)
        val typed: Any? = when (platform.worlds.gameRuleType(id)) {
            null -> return warn("netherforge.json: world \"$world\" sets game rule \"$rule\", which this server's Minecraft doesn't have")
            GameRuleType.BOOLEAN -> value.takeIf { !it.isString }?.booleanOrNull
            GameRuleType.INTEGER -> value.takeIf { !it.isString }?.intOrNull
        }
        if (typed == null) return warn("netherforge.json: world \"$world\"'s game rule \"$rule\" doesn't take $value")
        platform.worlds.setGameRule(world, id, typed)
    }
}
