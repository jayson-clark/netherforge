package dev.netherforge.format.particle

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.game.GameData
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.game.ParticleDataKind
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.project.Names
import dev.netherforge.format.validate.Rules
import kotlin.math.ceil

object ParticleEffectValidator {

    private val COLOR = Regex("^#[0-9a-f]{6}$")

    fun validate(file: ParticleEffectFile, sink: ProblemSink, game: GameData?) {
        if (file.duration !in ParticleEffectFile.MIN_DURATION..ParticleEffectFile.MAX_DURATION) {
            sink.report(
                ProblemCodes.PARTICLE_DURATION,
                "duration must be ${ParticleEffectFile.MIN_DURATION} to ${ParticleEffectFile.MAX_DURATION} ticks",
                "$.duration"
            )
        }
        if (file.emitters.isEmpty()) {
            sink.report(
                ProblemCodes.PARTICLE_EMITTERS_EMPTY,
                "This effect has no emitters, so it shows nothing",
                "$.emitters"
            )
        }

        for ((name, emitter) in file.emitters) {
            val at = CanonicalJson.childPath("$.emitters", name)
            if (!Names.isNodeName(name)) {
                sink.report(ProblemCodes.PARTICLE_EMITTER_NAME, "\"$name\" isn't a usable emitter name (${Names.NODE_NAME_RULE})", at)
            }
            validateEmitter(file, emitter, at, sink, game)
        }

        val worst = file.emitters.values.sumOf { worstPointsPerTick(it) }
        if (worst > ParticleEffectFile.MAX_POINTS_PER_TICK) {
            sink.report(
                ProblemCodes.PARTICLE_BUDGET,
                "This effect can spawn $worst points in one tick; the limit is ${ParticleEffectFile.MAX_POINTS_PER_TICK}",
                "$.emitters"
            )
        }
    }

    /**
     * The data kind of [emitter]'s particle when [game] knows it, or null when
     * it can't say (no game data, or an id it doesn't know, which is reported separately).
     */
    fun kindOf(emitter: EmitterDef, game: GameData?): ParticleDataKind? {
        if (game == null || !GameIds.isValid(emitter.particle)) return null
        return game.particle(GameIds.normalize(emitter.particle))?.data
    }

    /** The most points [emitter] can spawn in one tick: its burst, or its rate's highest value rounded up. */
    fun worstPointsPerTick(emitter: EmitterDef): Int {
        emitter.burst?.let { return it }
        val rate = emitter.rate ?: return 0
        val peak = maxOf(rate, emitter.curves?.rate.orEmpty().maxOfOrNull { it.value } ?: 0.0)
        return ceil(peak).toInt()
    }

    private fun validateEmitter(file: ParticleEffectFile, emitter: EmitterDef, at: String, sink: ProblemSink, game: GameData?) {
        val kind = validateParticle(emitter, at, sink, game)

        // Timing
        val start = emitter.start ?: 0
        val end = emitter.end ?: file.duration
        if (emitter.start != null && (start < 0 || start >= file.duration)) {
            sink.report(
                ProblemCodes.PARTICLE_WINDOW,
                "start must be from 0 to before the effect's duration (${file.duration})",
                "$at.start"
            )
        }
        if (emitter.end != null && (end <= start || end > file.duration)) {
            sink.report(
                ProblemCodes.PARTICLE_WINDOW,
                "end must be after start and at most the effect's duration (${file.duration})",
                "$at.end"
            )
        }

        // Emission
        if ((emitter.burst == null) == (emitter.rate == null)) {
            sink.report(ProblemCodes.PARTICLE_EMISSION, "An emitter has exactly one of burst and rate", at)
        }
        if (emitter.burst != null && emitter.burst !in 1..EmitterDef.MAX_BURST) {
            sink.report(ProblemCodes.PARTICLE_BURST, "burst must be 1 to ${EmitterDef.MAX_BURST} points", "$at.burst")
        }
        if (emitter.rate != null && !rateOk(emitter.rate, allowZero = false)) {
            sink.report(
                ProblemCodes.PARTICLE_RATE,
                "rate must be more than 0 and at most ${number(EmitterDef.MAX_RATE)} points per tick",
                "$at.rate"
            )
        }
        if (emitter.every != null) {
            if (emitter.burst == null) {
                sink.report(ProblemCodes.PARTICLE_EVERY, "every repeats a burst, so it needs burst", "$at.every")
            } else if (emitter.every < 1) {
                sink.report(ProblemCodes.PARTICLE_EVERY, "every must be at least 1 tick", "$at.every")
            }
        }

        // Shape
        val shape = emitter.shape ?: PointShape
        val radiusCurve = !emitter.curves?.radius.isNullOrEmpty()
        when (shape) {
            is LineShape -> if (shape.to ==
                Vec3.ZERO
            ) {
                sink.report(ProblemCodes.PARTICLE_SHAPE, "A line's to can't be [0, 0, 0]", "$at.shape.to")
            }
            is BoxShape -> if (shape.size.x <= 0 || shape.size.y <= 0 || shape.size.z <= 0) {
                sink.report(ProblemCodes.PARTICLE_SHAPE, "A box's size must be more than 0 on every axis", "$at.shape.size")
            }
            is RingShape, is DiscShape, is SphereShape -> {
                val radius = radiusOf(shape)
                val type = typeOf(shape)
                when {
                    radius != null && radiusCurve -> sink.report(
                        ProblemCodes.PARTICLE_CURVE_CONFLICT,
                        "radius is set and curves.radius drives it too; keep one",
                        "$at.shape.radius"
                    )
                    radius != null && radius <= 0 -> sink.report(
                        ProblemCodes.PARTICLE_SHAPE,
                        "A $type's radius must be more than 0",
                        "$at.shape.radius"
                    )
                    radius == null && !radiusCurve -> sink.report(
                        ProblemCodes.PARTICLE_SHAPE,
                        "A $type needs a radius, or a radius curve",
                        "$at.shape"
                    )
                }
            }
            PointShape -> Unit
        }
        if (emitter.distribution == Distribution.EVEN && !evenAllowed(shape)) {
            sink.report(
                ProblemCodes.PARTICLE_DISTRIBUTION,
                "even spacing is only for a line, a ring, or a sphere with surface",
                "$at.distribution"
            )
        }
        if (emitter.spin != null && !spinAllowed(shape)) {
            sink.report(ProblemCodes.PARTICLE_SPIN, "Only a ring, a disc or a line can spin", "$at.spin")
        }

        // Motion
        val motion = emitter.motion ?: Motion.RANDOM
        if (motion == Motion.DIRECTION) {
            if (emitter.direction == null) {
                sink.report(ProblemCodes.PARTICLE_DIRECTION, "motion: \"direction\" needs a direction", at)
            } else if (emitter.direction == Vec3.ZERO) {
                sink.report(ProblemCodes.PARTICLE_DIRECTION, "direction can't be [0, 0, 0]", "$at.direction")
            }
        } else if (emitter.direction != null) {
            sink.report(ProblemCodes.PARTICLE_DIRECTION, "direction is only for motion: \"direction\"", "$at.direction")
        }
        if (emitter.count != null) {
            if (motion != Motion.RANDOM) {
                sink.report(ProblemCodes.PARTICLE_COUNT, "count is only for motion: \"random\"; the game ignores it otherwise", "$at.count")
            } else if (emitter.count !in 1..EmitterDef.MAX_COUNT) {
                sink.report(ProblemCodes.PARTICLE_COUNT, "count must be 1 to ${EmitterDef.MAX_COUNT}", "$at.count")
            }
        }
        if (emitter.spread != null) {
            if (motion != Motion.RANDOM) {
                sink.report(
                    ProblemCodes.PARTICLE_SPREAD,
                    "spread is only for motion: \"random\"; the game ignores it otherwise",
                    "$at.spread"
                )
            } else if (emitter.spread.x < 0 || emitter.spread.y < 0 || emitter.spread.z < 0) {
                sink.report(ProblemCodes.PARTICLE_SPREAD, "spread can't be negative", "$at.spread")
            }
        }
        if (emitter.speed != null && emitter.speed < 0) sink.report(ProblemCodes.PARTICLE_SPEED, "speed can't be negative", "$at.speed")

        validateCurves(file, emitter, kind, shape, at, sink)
    }

    /** Checks the particle and its data fields; returns its kind when known. */
    private fun validateParticle(emitter: EmitterDef, at: String, sink: ProblemSink, game: GameData?): ParticleDataKind? {
        val valid = GameIds.isValid(emitter.particle)
        val id = GameIds.normalize(emitter.particle)
        if (!valid) {
            sink.report(ProblemCodes.PARTICLE_ID, "\"${emitter.particle}\" isn't a particle id", "$at.particle")
        } else if (game != null && game.particle(id) == null) {
            sink.report(ProblemCodes.PARTICLE_UNKNOWN, "Minecraft ${game.minecraftVersion} has no particle \"$id\"", "$at.particle")
        }
        val kind = kindOf(emitter, game)
        if (kind == ParticleDataKind.OTHER) {
            sink.report(
                ProblemCodes.PARTICLE_UNSUPPORTED,
                "$id takes options effects can't send; play it from a script instead",
                "$at.particle"
            )
        }

        val present = mapOf(
            ParticleOptions.COLOR to (emitter.color != null),
            ParticleOptions.TO_COLOR to (emitter.toColor != null),
            ParticleOptions.SIZE to (emitter.size != null),
            ParticleOptions.BLOCK_STATE to (emitter.blockState != null),
            ParticleOptions.ITEM to (emitter.item != null)
        )
        if (kind != null && kind != ParticleDataKind.OTHER) {
            val takes = ParticleOptions.takes(kind)
            for (field in ParticleOptions.ALL) {
                if (present.getValue(field) &&
                    field !in takes
                ) {
                    sink.report(ProblemCodes.PARTICLE_OPTION, "$id takes no $field", "$at.$field")
                }
            }
            val required = ParticleOptions.required(kind)
            if (required != null && !present.getValue(required)) sink.report(ProblemCodes.PARTICLE_OPTION, "$id needs a $required", at)
        }

        emitter.color?.let { if (!COLOR.matches(it)) sink.report(ProblemCodes.PARTICLE_COLOR, COLOR_RULE, "$at.color") }
        emitter.toColor?.let { if (!COLOR.matches(it)) sink.report(ProblemCodes.PARTICLE_COLOR, COLOR_RULE, "$at.toColor") }
        emitter.size?.let { if (!sizeOk(it)) sink.report(ProblemCodes.PARTICLE_SIZE, SIZE_RULE, "$at.size") }
        emitter.blockState?.let { Rules.blockState(it, "$at.blockState", Rules.PARTICLE_BLOCK, sink, game) }
        emitter.item?.let { Rules.item(it, "$at.item", sink, game) }
        return kind
    }

    private fun validateCurves(
        file: ParticleEffectFile,
        emitter: EmitterDef,
        kind: ParticleDataKind?,
        shape: EmitterShape,
        at: String,
        sink: ProblemSink
    ) {
        val curves = emitter.curves ?: return
        val takes = kind?.let { ParticleOptions.takes(it) }

        /** One channel: allowed here, not beside its constant, keys inside the timeline and at distinct ticks. */
        fun channel(
            name: String,
            times: List<Int>?,
            allowed: Boolean,
            where: String,
            constant: Boolean,
            value: (index: Int, path: String) -> Unit
        ) {
            if (times.isNullOrEmpty()) return
            val path = "$at.curves.$name"
            if (!allowed) sink.report(ProblemCodes.PARTICLE_CURVE_CHANNEL, "There's no $name curve $where", path)
            if (constant) sink.report(ProblemCodes.PARTICLE_CURVE_CONFLICT, "$name is set and has a curve too; keep one", path)
            val seen = mutableSetOf<Int>()
            times.forEachIndexed { index, time ->
                if (time !in 0..file.duration) {
                    sink.report(
                        ProblemCodes.PARTICLE_CURVE_TIME,
                        "A key's time must be 0 to the effect's duration (${file.duration})",
                        "$path[$index].time"
                    )
                } else if (!seen.add(time)) {
                    sink.report(ProblemCodes.PARTICLE_CURVE_DUPLICATE, "Two keys are at tick $time", "$path[$index].time")
                }
                value(index, "$path[$index]")
            }
        }

        val rate = curves.rate.orEmpty()
        channel("rate", rate.map { it.time }, emitter.rate != null, "on an emitter without rate", constant = false) { i, path ->
            if (!rateOk(rate[i].value, allowZero = true)) {
                sink.report(ProblemCodes.PARTICLE_RATE, "A rate key must be 0 to ${number(EmitterDef.MAX_RATE)}", "$path.value")
            }
        }
        val size = curves.size.orEmpty()
        val takesSize = takes == null || ParticleOptions.SIZE in takes
        channel("size", size.map { it.time }, takesSize, "for ${emitter.particle}", emitter.size != null) { i, path ->
            if (!sizeOk(size[i].value)) sink.report(ProblemCodes.PARTICLE_SIZE, SIZE_RULE, "$path.value")
        }
        val speed = curves.speed.orEmpty()
        channel("speed", speed.map { it.time }, true, "", emitter.speed != null) { i, path ->
            if (speed[i].value < 0) sink.report(ProblemCodes.PARTICLE_SPEED, "speed can't be negative", "$path.value")
        }
        // A radius set beside its curve is reported at the shape (see validateEmitter).
        val radius = curves.radius.orEmpty()
        channel("radius", radius.map { it.time }, hasRadius(shape), "on a ${typeOf(shape)}", constant = false) { i, path ->
            if (radius[i].value <= 0) sink.report(ProblemCodes.PARTICLE_SHAPE, "A radius must be more than 0", "$path.value")
        }
        val color = curves.color.orEmpty()
        val takesColor = takes == null || ParticleOptions.COLOR in takes
        channel("color", color.map { it.time }, takesColor, "for ${emitter.particle}", emitter.color != null) { i, path ->
            if (!COLOR.matches(color[i].color)) sink.report(ProblemCodes.PARTICLE_COLOR, COLOR_RULE, "$path.color")
        }
    }

    private const val COLOR_RULE = "Colours are #rrggbb, lowercase"
    private val SIZE_RULE = "size must be ${number(EmitterDef.MIN_SIZE)} to ${number(EmitterDef.MAX_SIZE)}"

    private fun rateOk(rate: Double, allowZero: Boolean) = (if (allowZero) rate >= 0 else rate > 0) && rate <= EmitterDef.MAX_RATE

    private fun sizeOk(size: Double) = size >= EmitterDef.MIN_SIZE && size <= EmitterDef.MAX_SIZE

    private fun number(value: Double) = CanonicalJson.formatNumber(value.toString())

    internal fun radiusOf(shape: EmitterShape): Double? = when (shape) {
        is RingShape -> shape.radius
        is DiscShape -> shape.radius
        is SphereShape -> shape.radius
        else -> null
    }

    private fun typeOf(shape: EmitterShape): String = when (shape) {
        PointShape -> "point"
        is LineShape -> "line"
        is RingShape -> "ring"
        is DiscShape -> "disc"
        is SphereShape -> "sphere"
        is BoxShape -> "box"
    }

    internal fun hasRadius(shape: EmitterShape) = shape is RingShape || shape is DiscShape || shape is SphereShape

    internal fun evenAllowed(shape: EmitterShape) =
        shape is LineShape || shape is RingShape || (shape is SphereShape && shape.surface == true)

    internal fun spinAllowed(shape: EmitterShape) = shape is RingShape || shape is DiscShape || shape is LineShape
}
