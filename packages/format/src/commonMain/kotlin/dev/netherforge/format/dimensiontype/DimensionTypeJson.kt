package dev.netherforge.format.dimensiontype

import dev.netherforge.format.datapack.DatapackContext
import dev.netherforge.format.game.GameIds
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A dimension type in the game's own datapack format (`dimension_type/<id>.json`), for the start-up datapack.
 *
 * Read off each supported server jar's own `data/minecraft/dimension_type/` files and its `DimensionType` codec, not
 * guessed. Every supported version (1.21.11 on) keeps the heights, light, sky and monster light as fields of the type
 * and the game's rules (beds, respawn anchors, piglins, raids, how hot it is) and the sky's, fog's and clouds' colours
 * as **environment attributes**. Two things moved with the data pack format:
 * - 26.1 ([CLOCK_FORMAT]) requires `has_ender_dragon_fight`, gives a type its `default_clock` (the overworld's,
 *   so `/time` and a world's time work as in the overworld) and the colour of the light it has with none
 *   (`ambient_light_color`, the overworld's);
 * - 26.3 ([STRAW_BED_FORMAT]) calls a bed's blowing up `destroy_on_use` (it was `explodes`) and adds the straw bed's
 *   own rule, which an older server refuses as an unknown attribute.
 *
 * What the file doesn't set is the overworld's, the attributes too: its cave sounds and music, and nether portals
 * spawning zombified piglins, so a type of `{}` is the game's overworld.
 *
 * The day is the overworld's timelines (`#minecraft:in_overworld`: the sun, the moon), or, with a fixed time, only
 * what every dimension has (`#minecraft:universal`).
 */
object DimensionTypeJson {
    /** Where the game reads a datapack's dimension types, under `data/<namespace>/`. */
    const val FOLDER = "dimension_type"

    /** The game's own overworld type, which a main world that names a dimension replaces. */
    const val OVERWORLD = "minecraft:overworld"

    /** The data pack format (26.1's) from which a type has a `default_clock` and must say `has_ender_dragon_fight`. */
    const val CLOCK_FORMAT = 101

    /** The data pack format (26.3's) from which a bed rule blows up with `destroy_on_use` and the straw bed has its own. */
    const val STRAW_BED_FORMAT = 121

    private const val NO_SLEEP = "block.minecraft.bed.no_sleep"

    /** The overworld's light colour where there's no light, from 26.1. */
    private const val AMBIENT_LIGHT_COLOR = "#0a0a0a"

    /** The overworld's cave sounds and music: every supported version's `overworld.json` has these. */
    private const val CAVE_SOUND = "minecraft:ambient.cave"
    private const val MOOD_TICK_DELAY = 6000
    private const val MOOD_BLOCK_SEARCH_EXTENT = 8
    private const val MOOD_OFFSET = 2.0
    private const val MUSIC_GAME = "minecraft:music.game"
    private const val MUSIC_CREATIVE = "minecraft:music.creative"
    private const val MUSIC_MIN_DELAY = 12000
    private const val MUSIC_MAX_DELAY = 24000

    fun of(file: DimensionTypeFile, ctx: DatapackContext): JsonElement = buildJsonObject {
        put("ambient_light", file.ambientLightOrDefault)
        put("attributes", attributes(file, ctx))
        put("coordinate_scale", file.coordinateScaleOrDefault)
        if (ctx.formatAtLeast(CLOCK_FORMAT)) {
            put("default_clock", OVERWORLD)
            put("has_ender_dragon_fight", false)
        }
        put("has_ceiling", file.ceilingOrDefault)
        put("has_fixed_time", file.fixedTimeOrDefault)
        put("has_skylight", file.skyLightOrDefault)
        put("height", file.heightOrDefault)
        put("infiniburn", "#" + GameIds.normalize(file.infiniburnOrDefault.removePrefix("#")))
        put("logical_height", file.logicalHeightOrDefault)
        put("min_y", file.minYOrDefault)
        put("monster_spawn_block_light_limit", file.monsterSpawnBlockLightOrDefault)
        val (low, high) = file.monsterSpawnLightMinOrDefault to file.monsterSpawnLightMaxOrDefault
        if (low == high) {
            put("monster_spawn_light_level", low)
        } else {
            putJsonObject("monster_spawn_light_level") {
                put("type", "minecraft:uniform")
                put("max_inclusive", high)
                put("min_inclusive", low)
            }
        }
        put("skybox", file.skyOrDefault.id)
        put("timelines", if (file.fixedTimeOrDefault) "#minecraft:universal" else "#minecraft:in_overworld")
    }

    /** The game's environment attributes the type sets: the dimension's own, which a biome's replace where it has them. */
    private fun attributes(file: DimensionTypeFile, ctx: DatapackContext): JsonObject = buildJsonObject {
        val strawBeds = ctx.formatAtLeast(STRAW_BED_FORMAT)
        putJsonObject("minecraft:audio/ambient_sounds") {
            putJsonObject("mood") {
                put("block_search_extent", MOOD_BLOCK_SEARCH_EXTENT)
                put("offset", MOOD_OFFSET)
                put("sound", CAVE_SOUND)
                put("tick_delay", MOOD_TICK_DELAY)
            }
        }
        putJsonObject("minecraft:audio/background_music") {
            put("creative", music(MUSIC_CREATIVE))
            put("default", music(MUSIC_GAME))
        }
        put("minecraft:gameplay/bed_rule", bedRule(file.bedWorksOrDefault, straw = false, strawBeds))
        put("minecraft:gameplay/can_start_raid", file.raidsOrDefault)
        put("minecraft:gameplay/nether_portal_spawns_piglin", true)
        put("minecraft:gameplay/piglins_zombify", !file.piglinSafeOrDefault)
        put("minecraft:gameplay/respawn_anchor_works", file.respawnAnchorWorksOrDefault)
        if (strawBeds) put("minecraft:gameplay/straw_bed_rule", bedRule(file.bedWorksOrDefault, straw = true, strawBeds))
        if (file.ultrawarmOrDefault) {
            put("minecraft:gameplay/fast_lava", true)
            put("minecraft:gameplay/snow_golem_melts", true)
            put("minecraft:gameplay/water_evaporates", true)
            putJsonObject("minecraft:visual/default_dripstone_particle") { put("type", "minecraft:dripping_dripstone_lava") }
        }
        if (ctx.formatAtLeast(CLOCK_FORMAT)) put("minecraft:visual/ambient_light_color", AMBIENT_LIGHT_COLOR)
        val colors = file.colors ?: DimensionTypeColors()
        put("minecraft:visual/cloud_color", argb(colors.cloudsOrDefault))
        put("minecraft:visual/cloud_height", file.cloudHeightOrDefault)
        put("minecraft:visual/fog_color", colors.fogOrDefault.lowercase())
        put("minecraft:visual/sky_color", colors.skyOrDefault.lowercase())
    }

    private fun music(sound: String): JsonObject = buildJsonObject {
        put("max_delay", MUSIC_MAX_DELAY)
        put("min_delay", MUSIC_MIN_DELAY)
        put("sound", sound)
    }

    /**
     * Where beds work: sleep when it's dark and set the spawn (a straw bed only sleeps, and goes once left); where
     * they don't: no sleep, no spawn, and the bed blows up when used.
     */
    private fun bedRule(works: Boolean, straw: Boolean, destroyOnUse: Boolean): JsonObject = buildJsonObject {
        if (works) {
            put("can_set_spawn", if (straw) "never" else "always")
            put("can_sleep", "when_dark")
            if (straw) put("destroy_on_leave", true)
            putJsonObject("error_message") { put("translate", NO_SLEEP) }
        } else {
            put("can_set_spawn", "never")
            put("can_sleep", "never")
            put(if (destroyOnUse) "destroy_on_use" else "explodes", true)
        }
    }

    /** `#rrggbb` as the game's `#aarrggbb`, opaque; one with its alpha already as it is. */
    private fun argb(color: String): String = (if (color.length == 7) "#ff" + color.drop(1) else color).lowercase()
}
