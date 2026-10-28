package dev.netherforge.format.biome

import dev.netherforge.format.ProblemCode
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.game.GameData
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.game.ParticleDataKind
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.game.has
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.ref.RefKind

/**
 * Checks a biome on its own: its climate's ranges, its colours, and, with
 * game data, that its particle, sounds, entity types and placed features are
 * the game's (each only shape-checked without it). That two of the project's
 * biomes agree on the order of their features is the biome kind's cross
 * check.
 */
object BiomeValidator {
    fun validate(file: BiomeFile, sink: ProblemSink, game: GameData?) {
        climate(file.climate, sink)
        colors(file.colors, sink)
        file.particle?.let { particle(it, sink, game) }
        sounds(file.sounds, sink, game)
        for ((category, spawns) in file.spawns) {
            val at = CanonicalJson.childPath("$.spawns", category.id)
            spawns.forEachIndexed { index, spawn -> spawn(spawn, "$at[$index]", sink, game) }
        }
        for ((entity, cost) in file.spawnCosts) {
            val at = CanonicalJson.childPath("$.spawnCosts", entity)
            id(entity, "entity type", RegistryKey.ENTITY_TYPE, ProblemCodes.BIOME_SPAWN_COST, at, sink, game)
            if (cost.charge <= 0.0 || cost.energyBudget <= 0.0) {
                sink.report(ProblemCodes.BIOME_SPAWN_COST, "A spawn cost's charge and energyBudget are each more than 0", at)
            }
        }
        for ((step, features) in file.features) {
            val at = CanonicalJson.childPath("$.features", step.id)
            val seen = HashSet<String>()
            features.forEachIndexed { index, ref ->
                val feature = ref.text
                // The game's are checked here; the project's (and packages') are references, which the loader checks.
                val shaped = !RefKind.PLACED_FEATURE.isGame(feature) ||
                    id(feature, "placed feature", RegistryKey.PLACED_FEATURE, ProblemCodes.BIOME_FEATURE, "$at[$index]", sink, game)
                if (shaped && !seen.add(feature)) {
                    sink.report(ProblemCodes.BIOME_FEATURE, "\"$feature\" is already in ${step.id}", "$at[$index]")
                }
            }
        }
    }

    private fun climate(climate: BiomeClimate, sink: ProblemSink) {
        val temperature = climate.temperature
        if (temperature != null && temperature !in BiomeClimate.MIN_TEMPERATURE..BiomeClimate.MAX_TEMPERATURE) {
            sink.report(
                ProblemCodes.BIOME_CLIMATE,
                "temperature is ${BiomeClimate.MIN_TEMPERATURE.toInt()} to ${BiomeClimate.MAX_TEMPERATURE.toInt()}",
                "$.climate.temperature"
            )
        }
        val downfall = climate.downfall
        if (downfall != null && downfall !in 0.0..1.0) sink.report(ProblemCodes.BIOME_CLIMATE, "downfall is 0 to 1", "$.climate.downfall")
    }

    private fun colors(colors: BiomeColors, sink: ProblemSink) {
        val all = listOf(
            "sky" to colors.sky,
            "fog" to colors.fog,
            "water" to colors.water,
            "waterFog" to colors.waterFog,
            "grass" to colors.grass,
            "foliage" to colors.foliage,
            "dryFoliage" to colors.dryFoliage
        )
        for ((name, color) in all) {
            if (color != null && !BiomeColors.COLOR.matches(color)) {
                sink.report(ProblemCodes.BIOME_COLOR, "\"$color\" isn't a colour written #rrggbb, like \"#3f76e4\"", "$.colors.$name")
            }
        }
    }

    private fun particle(particle: AmbientParticle, sink: ProblemSink, game: GameData?) {
        val at = "$.particle"
        if (id(particle.particle, "particle", RegistryKey.PARTICLE_TYPE, ProblemCodes.BIOME_PARTICLE, "$at.particle", sink, game)) {
            val data = game?.particle(GameIds.normalize(particle.particle))?.data
            if (data != null && data != ParticleDataKind.NONE) {
                sink.report(
                    ProblemCodes.BIOME_PARTICLE,
                    "\"${particle.particle}\" takes options (${data.name.lowercase()}), which a biome's particle can't give: use one that takes none",
                    "$at.particle"
                )
            }
        }
        if (particle.probability <= 0.0 || particle.probability > 1.0) {
            sink.report(ProblemCodes.BIOME_PARTICLE, "probability is more than 0 and at most 1", "$at.probability")
        }
    }

    private fun sounds(sounds: BiomeSounds, sink: ProblemSink, game: GameData?) {
        fun sound(id: String, at: String) = id(id, "sound event", RegistryKey.SOUND_EVENT, ProblemCodes.BIOME_SOUND, at, sink, game)
        sounds.ambient?.let { sound(it, "$.sounds.ambient") }
        sounds.mood?.let { mood ->
            sound(mood.sound, "$.sounds.mood.sound")
            if (mood.tickDelayOrDefault < 1) sink.report(ProblemCodes.BIOME_SOUND, "tickDelay is at least 1", "$.sounds.mood.tickDelay")
            if (mood.blockSearchExtentOrDefault < 0) {
                sink.report(ProblemCodes.BIOME_SOUND, "blockSearchExtent can't be negative", "$.sounds.mood.blockSearchExtent")
            }
            if (mood.offsetOrDefault < 0.0) sink.report(ProblemCodes.BIOME_SOUND, "offset can't be negative", "$.sounds.mood.offset")
        }
        sounds.additions?.let { additions ->
            sound(additions.sound, "$.sounds.additions.sound")
            if (additions.chance !in 0.0..1.0) sink.report(ProblemCodes.BIOME_SOUND, "chance is 0 to 1", "$.sounds.additions.chance")
        }
        sounds.music?.let { music ->
            sound(music.sound, "$.sounds.music.sound")
            if (music.minDelayOrDefault < 0) sink.report(ProblemCodes.BIOME_SOUND, "minDelay can't be negative", "$.sounds.music.minDelay")
            if (music.maxDelay != null && music.maxDelay < music.minDelayOrDefault) {
                sink.report(ProblemCodes.BIOME_SOUND, "maxDelay is below minDelay", "$.sounds.music.maxDelay")
            }
        }
    }

    private fun spawn(spawn: BiomeSpawn, at: String, sink: ProblemSink, game: GameData?) {
        id(spawn.entity, "entity type", RegistryKey.ENTITY_TYPE, ProblemCodes.BIOME_SPAWN, "$at.entity", sink, game)
        if (spawn.weightOrDefault < 1) sink.report(ProblemCodes.BIOME_SPAWN, "weight is at least 1", "$at.weight")
        val group = spawn.group ?: return
        if ((group.min ?: 1) < 1 || (group.max ?: 1) < 1) {
            sink.report(ProblemCodes.BIOME_SPAWN, "A group is at least 1", "$at.group")
        } else if (group.min != null && group.max != null && group.min > group.max) {
            sink.report(ProblemCodes.BIOME_SPAWN, "The group's min is above its max", "$at.group")
        }
    }

    /** One of the game's ids: well formed, and with game data, one [registry] has. Whether it's well formed. */
    private fun id(
        text: String,
        what: String,
        registry: RegistryKey,
        code: ProblemCode,
        at: String,
        sink: ProblemSink,
        game: GameData?
    ): Boolean {
        if (!GameIds.isValid(text)) {
            sink.report(code, "\"$text\" isn't ${article(what)} $what id, like \"${GameIds.NAMESPACE}:…\"", at)
            return false
        }
        val id = GameIds.normalize(text)
        if (game?.has(registry, id) == false) sink.report(code, "Minecraft ${game.minecraftVersion} has no $what \"$id\"", at)
        return true
    }

    private fun article(noun: String) = if (noun.first() in "aeiou") "an" else "a"
}
