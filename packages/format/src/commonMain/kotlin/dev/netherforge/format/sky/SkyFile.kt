package dev.netherforge.format.sky

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.biome.BiomeColors
import dev.netherforge.format.dimensiontype.DimensionTypeColors
import dev.netherforge.format.ref.ResourceKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * `skies/<id>.json`: a sky scripts can set over a world, or over one player's view of it (`world:set_sky`), for
 * the length of an event: a blood moon, an eclipse. The colours it gives replace every biome's own while it's set;
 * what it leaves out stays each biome's.
 *
 * The game's client takes these colours from the biomes it's told the world is made of, and learns biomes only as
 * it joins, so each sky is a set of **twin biomes** made at start (one per biome the server has, per step of its
 * [fade]), and a player under it is told the world is made of those: the server's own world never changes. A
 * new or changed sky needs a restart, as a biome does.
 */
@Serializable
data class SkyFile(
    @SerialName("\$schema") val schema: String? = null,
    /** The sky's colour, `#rrggbb`. */
    val sky: String? = null,
    /** The fog's: what the far land fades into. */
    val fog: String? = null,
    /** The clouds': `#rrggbb` (opaque), or `#aarrggbb` with how opaque they are first. */
    val clouds: String? = null,
    /** Water's own colour. */
    val water: String? = null,
    /** The fog under water. */
    val waterFog: String? = null,
    /**
     * The light the sky casts on the land, `#rrggbb`: a tint over everything the sky lights (the game's is white by
     * day, bluer by night, and night still darkens it).
     */
    val light: String? = null,
    /**
     * Whether night darkens its [sky] and [fog], as it does the game's (default true). False holds them through the
     * night: a red sky stays red. 26.1 and later ([NIGHT_FEATURE]), where each such sky is also a timeline of the
     * game's ([SkyNight]).
     */
    val darkens: Boolean? = null,
    /**
     * How many steps a fade to it takes (1 to [MAX_FADE]): each step is a set of twin biomes part of the way from
     * each biome's colours to the sky's, which the server moves a player through over a fade's ticks. Default
     * [DEFAULT_FADE]: more is smoother, and more biomes for players to load as they join.
     */
    val fade: Int? = null
) {
    val fadeOrDefault: Int get() = fade ?: DEFAULT_FADE

    /** Its colours by what they colour: the game's attribute of each, the one place they're named. */
    val colors: Map<SkyColor, String> get() = listOfNotNull(
        sky?.let { SkyColor.SKY to it },
        fog?.let { SkyColor.FOG to it },
        clouds?.let { SkyColor.CLOUDS to it },
        water?.let { SkyColor.WATER to it },
        waterFog?.let { SkyColor.WATER_FOG to it },
        light?.let { SkyColor.LIGHT to it }
    ).toMap()

    val darkensOrDefault: Boolean get() = darkens ?: true

    /** The colours night would darken that it holds instead, by what they colour; none when it [darkens]. */
    val heldAtNight: Map<SkyColor, String> get() = if (darkensOrDefault) emptyMap() else colors.filterKeys { it.heldAtNight }

    companion object {
        const val SCHEMA = "../.netherforge/schema/sky.schema.json"
        const val DEFAULT_FADE = 3
        const val MAX_FADE = 8

        /** The feature (the game's world clocks, 26.1) a sky that doesn't [darkens] needs. */
        const val NIGHT_FEATURE = "sky-night"
    }
}

/**
 * A colour a sky gives, and where a biome keeps it in the game's format: an environment attribute (every supported
 * version keeps these there, the dimension's when the biome has none), or water's own colour in `effects`. The
 * clouds' has an [alpha]: the game reads it only as `#aarrggbb`, and refuses a biome (so the whole start-up
 * datapack, so the server's start) that gives it `#rrggbb`.
 */
enum class SkyColor(
    val field: String,
    val attribute: String?,
    val effect: String?,
    val alpha: Boolean = false,
    /** What a biome and its dimension that give none of it have: the game's default, where it isn't the dimension's to say. */
    val fallback: String? = null,
    /**
     * Whether a sky that doesn't darken holds it through the night ([SkyNight]). The clouds aren't: a timeline can
     * only blend a colour over the game's by its alpha, which for the clouds is how see-through they are.
     */
    val heldAtNight: Boolean = false
) {
    SKY("sky", "minecraft:visual/sky_color", null, heldAtNight = true),
    FOG("fog", "minecraft:visual/fog_color", null, heldAtNight = true),
    CLOUDS("clouds", "minecraft:visual/cloud_color", null, alpha = true),
    WATER("water", null, "water_color"),
    WATER_FOG("waterFog", "minecraft:visual/water_fog_color", null),
    LIGHT("light", "minecraft:visual/sky_light_color", null, fallback = "#ffffff");

    /** How a sky file may write it. */
    val pattern: Regex get() = if (alpha) DimensionTypeColors.CLOUD_COLOR else BiomeColors.COLOR

    /** How the game writes it: `#aarrggbb` with an [alpha] (`#rrggbb` opaque), else `#rrggbb`; null for neither. */
    fun normalize(value: String): String? = when {
        !pattern.matches(value) -> null
        alpha && value.length == 7 -> "#ff" + value.drop(1).lowercase()
        else -> value.lowercase()
    }
}

object SkyValidator {
    fun validate(file: SkyFile, sink: ProblemSink, requireFeature: (id: String, path: String) -> Unit = { _, _ -> }) {
        for (color in SkyColor.entries) {
            val value = file.colors[color] ?: continue
            if (!color.pattern.matches(value)) {
                val how = if (color.alpha) "#rrggbb or #aarrggbb, like \"#5a0a0a\" or \"#cc5a0a0a\"" else "#rrggbb, like \"#5a0a0a\""
                sink.report(ProblemCodes.SKY_COLOR, "\"$value\" isn't a colour written $how", "$.${color.field}")
            }
        }
        if (file.colors.isEmpty()) {
            sink.report(ProblemCodes.SKY_EMPTY, "A sky gives at least one colour: sky, fog, clouds, water, waterFog or light", "$")
        }
        val fade = file.fade
        if (fade != null && fade !in 1..SkyFile.MAX_FADE) {
            sink.report(ProblemCodes.SKY_FADE, "fade is 1 to ${SkyFile.MAX_FADE} steps, not $fade", "$.fade")
        }
        if (file.darkens == false) {
            requireFeature(SkyFile.NIGHT_FEATURE, "$.darkens")
            if (file.heldAtNight.isEmpty()) {
                sink.report(
                    ProblemCodes.SKY_NIGHT,
                    "darkens is false, but the sky gives no sky or fog colour to hold at night",
                    "$.darkens"
                )
            }
        }
    }
}

/**
 * The twin biomes of a sky: each biome the server has, as the game's JSON, with the sky's colours part of the way
 * ([step] of [SkyFile.fade]) from its own, no features, carvers or spawns (a twin is never generated, only shown),
 * everything else as it is (grass, foliage, sounds, music, weather).
 */
object SkyTwins {
    /** The folder a sky's twins go in, under the project's namespace: no biome's id has a `/`, so none is one. */
    const val FOLDER = "sky"

    /**
     * The key of [biome]'s twin at [step] of [sky], in the project's namespace [home], whoever's sky it is
     * (`showcase:sky/showcase/blood_moon/3/minecraft/plains`).
     */
    fun keyOf(home: String, sky: ResourceKey, step: Int, biome: ResourceKey): ResourceKey =
        ResourceKey(home, "$FOLDER/${sky.namespace}/${sky.path}/$step/${biome.namespace}/${biome.path}")

    /**
     * The sky [twin] is a twin of, when it's one ([keyOf] in [home]); null for any other biome. What a report
     * of the game's about a twin is about.
     */
    fun skyOf(home: String, twin: ResourceKey): ResourceKey? {
        if (twin.namespace != home) return null
        val parts = twin.path.split('/')
        // sky/<namespace>/<path...>/<step>/<biome namespace>/<biome path>
        if (parts.size < 6 || parts[0] != FOLDER || parts[parts.size - 3].toIntOrNull() == null) return null
        return ResourceKey(parts[1], parts.subList(2, parts.size - 3).joinToString("/"))
    }

    /**
     * [biome]'s JSON as its twin at [step] of [file]'s fade. [defaults] are what a biome that gives none of a colour
     * has (the overworld's: what its sky fades from).
     */
    fun twin(biome: JsonObject, file: SkyFile, step: Int, defaults: Map<SkyColor, String>): JsonObject {
        val fraction = step.toDouble() / file.fadeOrDefault
        val attributes = (biome["attributes"] as? JsonObject).orEmpty().filterKeys { it != SPAWNS }.toMutableMap()
        val effects = (biome["effects"] as? JsonObject).orEmpty().toMutableMap()
        for ((color, given) in file.colors) {
            // Checked already (a sky with a bad colour doesn't run); one that slipped through is left the biome's.
            val target = color.normalize(given) ?: continue
            val own = color.attribute?.let { attributes[it] } ?: color.effect?.let { effects[it] }
            val from = (own as? JsonPrimitive)?.contentOrNull?.let(color::normalize) ?: defaults[color] ?: color.fallback ?: target
            val value = JsonPrimitive(mix(from, target, fraction))
            color.attribute?.let { attributes[it] = value }
            color.effect?.let { effects[it] = value }
        }
        return buildJsonObject {
            for ((key, value) in biome) {
                when (key) {
                    "attributes" -> put(key, JsonObject(attributes))
                    "effects" -> put(key, JsonObject(effects))
                    // Nothing to generate: each step's list empty, as many as there were.
                    "features" -> put(key, JsonArray((value as? JsonArray).orEmpty().map { JsonArray(emptyList()) }))
                    // A tag, a list or an id: none, as an empty list, which every version reads.
                    "carvers" -> put(key, JsonArray(emptyList()))
                    "spawners", "spawn_costs" -> put(key, JsonObject(emptyMap()))
                    else -> put(key, value)
                }
            }
            if ("effects" !in biome && effects.isNotEmpty()) put("effects", JsonObject(effects))
            if ("attributes" !in biome && attributes.isNotEmpty()) put("attributes", JsonObject(attributes))
        }
    }

    /** The colours an overworld biome that gives none has: the dimension type's own, from its JSON. */
    fun defaultsOf(dimensionType: JsonObject?): Map<SkyColor, String> {
        val attributes = dimensionType?.get("attributes") as? JsonObject ?: return emptyMap()
        return SkyColor.entries.mapNotNull { color ->
            val value = color.attribute?.let { attributes[it] } ?: return@mapNotNull null
            (value as? JsonPrimitive)?.contentOrNull?.let(color::normalize)?.let { color to it }
        }.toMap()
    }

    /**
     * [fraction] of the way from [from] to [to], each channel rounded: `#rrggbb`, or `#aarrggbb` (the alpha mixed
     * as a channel too) when both are. Both are written the same way ([SkyColor.normalize]).
     */
    fun mix(from: String, to: String, fraction: Double): String {
        require(from.length == to.length) { "\"$from\" and \"$to\" aren't written the same way" }
        if (fraction >= 1.0) return to.lowercase()
        val a = from.removePrefix("#").toLong(16)
        val b = to.removePrefix("#").toLong(16)
        val digits = from.length - 1
        var mixed = 0L
        for (shift in (digits / 2 - 1) * 8 downTo 0 step 8) {
            val x = (a shr shift) and 0xff
            val y = (b shr shift) and 0xff
            mixed = mixed or (kotlin.math.round(x + (y - x) * fraction).toLong().coerceIn(0, 255) shl shift)
        }
        return "#" + mixed.toString(16).padStart(digits, '0')
    }

    private const val SPAWNS = "minecraft:gameplay/natural_mob_spawns"

    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()
}

/**
 * A sky that doesn't darken at night ([SkyFile.darkens] false), on 26.1 and later: the game darkens the sky and the fog
 * with its day's timeline (`minecraft:day`, which multiplies them nearly to black), after every biome's own colour,
 * so no twin biome can hold them. Each such sky is also a world clock and a timeline on it, listed after the day in
 * the timelines of the overworld, the nether and the end: at tick 0 of its clock it changes nothing, and at the last
 * step of its fade it lays the sky's colours over the game's, the day's darkening included. The server keeps each
 * clock stopped at 0, and tells a player under the sky its clock is at the step of the fade they see.
 */
object SkyNight {
    /** The timeline tags the game's dimensions list theirs in: a sky's is added to each, after the day's. */
    val TAGS = listOf("in_overworld", "in_nether", "in_end")

    const val CLOCK_FOLDER = "world_clock"
    const val TIMELINE_FOLDER = "timeline"

    /** The key of [sky]'s clock, and of its timeline, in the project's namespace [home] (`showcase:sky/showcase/blood_moon`). */
    fun keyOf(home: String, sky: ResourceKey): ResourceKey = ResourceKey(home, "${SkyTwins.FOLDER}/${sky.namespace}/${sky.path}")

    /** The key of the clock the start-up datapack's file [path] is, when it's a sky's; else null. */
    fun clockIn(path: String): String? {
        val match = CLOCK_PATH.matchEntire(path) ?: return null
        return "${match.groupValues[1]}:${match.groupValues[2]}"
    }

    private val CLOCK_PATH = Regex("""^data/([^/]+)/$CLOCK_FOLDER/(${SkyTwins.FOLDER}/.+)\.json$""")

    /** The game's JSON of [file]'s timeline, on the clock [clock]: each colour it holds from unseen (tick 0) to all there (its last step). */
    fun timeline(clock: ResourceKey, file: SkyFile): JsonObject = buildJsonObject {
        put("clock", JsonPrimitive(clock.toString()))
        put(
            "tracks",
            buildJsonObject {
                for ((color, given) in file.heldAtNight.entries.sortedBy { it.key.attribute }) {
                    val rgb = color.normalize(given)?.drop(1) ?: continue
                    put(
                        checkNotNull(color.attribute),
                        buildJsonObject {
                            put(
                                "keyframes",
                                JsonArray(
                                    listOf(
                                        keyframe(0, "#00$rgb"),
                                        keyframe(file.fadeOrDefault, "#ff$rgb")
                                    )
                                )
                            )
                            put("modifier", JsonPrimitive("alpha_blend"))
                        }
                    )
                }
            }
        )
    }

    private fun keyframe(ticks: Int, value: String) = buildJsonObject {
        put("ticks", JsonPrimitive(ticks))
        put("value", JsonPrimitive(value))
    }
}
