package dev.netherforge.format.biome

import dev.netherforge.format.datapack.DatapackContext
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.project.SpawnCategory
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.world.GenerationStep
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A biome in the game's own datapack format (`worldgen/biome/<id>.json`), for
 * the start-up datapack. Every supported version takes its sky, fog and
 * water fog colours, particles, sounds and music as **environment
 * attributes** and the rest of its colours as `effects`; where they keep
 * the mobs that spawn differs (see [SPAWNS_ATTRIBUTE_FORMAT]), so that part
 * is written for the server's data pack format.
 *
 * It has exactly what the file says: no carvers, the features listed (and
 * no others) in each step, and its spawns replacing the world's rather than
 * adding to them.
 */
object BiomeJson {
    /** Where the game reads a datapack's biomes, under `data/<namespace>/`. */
    const val FOLDER = "worldgen/biome"

    /**
     * The data pack format from which a biome's mobs are the
     * `minecraft:gameplay/natural_mob_spawns` attribute (a spawn's group a
     * `count`) rather than `spawners` and `spawn_costs` of its own (with
     * `minCount` and `maxCount`): 26.3's, the first supported release that
     * reads them so. The game ignores the other way's fields, so a biome
     * written for the wrong format would spawn nothing.
     */
    const val SPAWNS_ATTRIBUTE_FORMAT = 121

    fun of(file: BiomeFile, ctx: DatapackContext): JsonElement = buildJsonObject {
        val climate = file.climate
        put("temperature", climate.temperatureOrDefault)
        put("downfall", climate.downfallOrDefault)
        put("has_precipitation", climate.precipitationOrDefault)
        climate.temperatureModifier?.takeIf { it != TemperatureModifier.NONE }?.let { put("temperature_modifier", it.id) }
        val spawnsAsAttribute = ctx.formatAtLeast(SPAWNS_ATTRIBUTE_FORMAT)
        put("attributes", attributes(file, spawnsAsAttribute))
        put("effects", effects(file.colors))
        if (!spawnsAsAttribute) {
            put("spawners", spawners(file, spawnsAsAttribute = false))
            put("spawn_costs", spawnCosts(file))
        }
        putJsonArray("carvers") {}
        putJsonArray("features") {
            for (step in GenerationStep.entries) {
                add(buildJsonArray { for (feature in file.features[step].orEmpty()) add(ctx.resolve(feature).toString()) })
            }
        }
    }

    /**
     * Whether a running biome's [file] goes in the start-up datapack [ctx] builds: every placed feature it names
     * that isn't the game's is one a datapack in it defines. The game refuses the whole pack over a biome naming a
     * feature it doesn't have, so one whose datapack isn't there (it has errors, or isn't for this server's format)
     * waits for it.
     */
    fun writes(file: BiomeFile, ctx: DatapackContext): Boolean {
        val features = file.features.values.flatten().filterNot { RefKind.PLACED_FEATURE.isGame(it.text) }
        if (features.isEmpty()) return true
        val defined = ctx.passedThrough(RefKind.PLACED_FEATURE.registry!!)
        return features.all { ctx.resolve(it) in defined }
    }

    /** The game's environment attributes the biome sets: each one a value, which replaces the world's. */
    private fun attributes(file: BiomeFile, spawnsAsAttribute: Boolean): JsonObject = buildJsonObject {
        val colors = file.colors
        colors.sky?.let { put("minecraft:visual/sky_color", it.lowercase()) }
        colors.fog?.let { put("minecraft:visual/fog_color", it.lowercase()) }
        colors.waterFog?.let { put("minecraft:visual/water_fog_color", it.lowercase()) }
        file.particle?.let { particle ->
            putJsonArray("minecraft:visual/ambient_particles") {
                add(
                    buildJsonObject {
                        putJsonObject("particle") { put("type", GameIds.normalize(particle.particle)) }
                        put("probability", particle.probability)
                    }
                )
            }
        }
        val sounds = file.sounds
        if (sounds.ambient != null || sounds.mood != null || sounds.additions != null) {
            putJsonObject("minecraft:audio/ambient_sounds") {
                sounds.ambient?.let { put("loop", GameIds.normalize(it)) }
                sounds.mood?.let { mood ->
                    putJsonObject("mood") {
                        put("sound", GameIds.normalize(mood.sound))
                        put("tick_delay", mood.tickDelayOrDefault)
                        put("block_search_extent", mood.blockSearchExtentOrDefault)
                        put("offset", mood.offsetOrDefault)
                    }
                }
                sounds.additions?.let { additions ->
                    putJsonObject("additions") {
                        put("sound", GameIds.normalize(additions.sound))
                        put("tick_chance", additions.chance)
                    }
                }
            }
        }
        sounds.music?.let { music ->
            putJsonObject("minecraft:audio/background_music") {
                putJsonObject("default") {
                    put("sound", GameIds.normalize(music.sound))
                    put("min_delay", music.minDelayOrDefault)
                    put("max_delay", music.maxDelayOrDefault)
                }
            }
        }
        if (spawnsAsAttribute) {
            putJsonObject("minecraft:gameplay/natural_mob_spawns") {
                put("spawn_costs", spawnCosts(file))
                put("spawns_by_category", spawners(file, spawnsAsAttribute = true))
            }
        }
    }

    private fun effects(colors: BiomeColors): JsonObject = buildJsonObject {
        put("water_color", colors.waterOrDefault.lowercase())
        colors.grass?.let { put("grass_color", it.lowercase()) }
        colors.foliage?.let { put("foliage_color", it.lowercase()) }
        colors.dryFoliage?.let { put("dry_foliage_color", it.lowercase()) }
        colors.grassModifier?.takeIf { it != GrassModifier.NONE }?.let { put("grass_color_modifier", it.id) }
    }

    /** The spawns by the game's own name for each category, in the categories' order. */
    private fun spawners(file: BiomeFile, spawnsAsAttribute: Boolean): JsonObject = buildJsonObject {
        for (category in SpawnCategory.entries) {
            val spawns = file.spawns[category] ?: continue
            putJsonArray(mobCategory(category)) {
                for (spawn in spawns) {
                    add(
                        buildJsonObject {
                            put("type", GameIds.normalize(spawn.entity))
                            put("weight", spawn.weightOrDefault)
                            count(spawn, spawnsAsAttribute)
                        }
                    )
                }
            }
        }
    }

    private fun JsonObjectBuilder.count(spawn: BiomeSpawn, spawnsAsAttribute: Boolean) {
        if (!spawnsAsAttribute) {
            put("minCount", spawn.groupMin)
            put("maxCount", spawn.groupMax)
        } else if (spawn.groupMin == spawn.groupMax) {
            put("count", spawn.groupMin)
        } else {
            putJsonObject("count") {
                put("type", "minecraft:uniform")
                put("min_inclusive", spawn.groupMin)
                put("max_inclusive", spawn.groupMax)
            }
        }
    }

    private fun spawnCosts(file: BiomeFile): JsonObject = buildJsonObject {
        for ((entity, cost) in file.spawnCosts.entries.sortedBy { GameIds.normalize(it.key) }) {
            putJsonObject(GameIds.normalize(entity)) {
                put("charge", cost.charge)
                put("energy_budget", cost.energyBudget)
            }
        }
    }

    /** The game's name for a spawn category, as a biome's spawns are keyed (its `MobCategory`). */
    private fun mobCategory(category: SpawnCategory): String = when (category) {
        SpawnCategory.MONSTER -> "monster"
        SpawnCategory.ANIMAL -> "creature"
        SpawnCategory.WATER_ANIMAL -> "water_creature"
        SpawnCategory.WATER_AMBIENT -> "water_ambient"
        SpawnCategory.WATER_UNDERGROUND_CREATURE -> "underground_water_creature"
        SpawnCategory.AMBIENT -> "ambient"
        SpawnCategory.AXOLOTL -> "axolotls"
    }
}
