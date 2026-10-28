package dev.netherforge.plugin.api

import dev.netherforge.format.centity.BodyDef
import dev.netherforge.format.centity.LoopMode
import dev.netherforge.format.centity.ResolvedBody
import dev.netherforge.format.centity.TextDisplay
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.math.radiansToDegrees
import dev.netherforge.plugin.binding.ActiveBuff
import dev.netherforge.plugin.binding.AnimationOptions
import dev.netherforge.plugin.binding.BodySettings
import dev.netherforge.plugin.binding.Bounds
import dev.netherforge.plugin.binding.CentityApi
import dev.netherforge.plugin.binding.CentityDamageOptions
import dev.netherforge.plugin.binding.CentityPathOptions
import dev.netherforge.plugin.binding.LaunchOptions
import dev.netherforge.plugin.binding.LocationOrVec3
import dev.netherforge.plugin.binding.LocationOrVec3OrEntityOrCentity
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.LuaLocation
import dev.netherforge.plugin.binding.LuaPlace
import dev.netherforge.plugin.binding.place
import dev.netherforge.plugin.centity.Centities
import dev.netherforge.plugin.centity.CentityPaths
import dev.netherforge.plugin.centity.Flight
import dev.netherforge.plugin.centity.Instance
import dev.netherforge.plugin.centity.Mover
import dev.netherforge.plugin.centity.PathSettings
import dev.netherforge.plugin.centity.PathTarget
import dev.netherforge.plugin.centity.QueryFilter
import dev.netherforge.plugin.data.ScriptData
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaValue
import dev.netherforge.plugin.session.ProjectSession
import dev.netherforge.plugin.stats.StatSheets
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import kotlin.math.atan2

/** The live instance a handle names, or null once it's removed. */
internal fun ProjectSession.instance(self: LuaHandle.Centity): Instance? = centities.find(self.id)?.takeIf { !it.removed }

/** Adds a node a script described under [parent] (a root when null) and returns its handle; the name must be free. */
internal fun ProjectSession.addNode(instance: Instance, name: String, parent: Int?, definition: LuaValue?): LuaHandle.Node {
    val node = nodeFrom(name, parent ?: -1, definition, platform.game)
    if (instance.indexOf(name) != null) throw LuaApiException("bad argument 'name' (the centity already has a node called \"$name\")")
    centities.addNode(instance, node)
    return LuaHandle.Node(instance.id.toString(), name)
}

internal class CentityImpl(private val session: ProjectSession) : CentityApi {
    private val centities get() = session.centities

    private fun instance(self: LuaHandle.Centity) = session.instance(self)

    override fun id(self: LuaHandle.Centity): String = self.id

    override fun kind(self: LuaHandle.Centity): String? = instance(self)?.centity?.let(session.names::spell)

    override fun exists(self: LuaHandle.Centity): Boolean = instance(self) != null

    override fun isNatural(self: LuaHandle.Centity): Boolean = instance(self)?.natural == true

    override fun isTemporary(self: LuaHandle.Centity): Boolean = instance(self)?.temporary == true

    override fun lifetime(self: LuaHandle.Centity): Long? {
        val expires = instance(self)?.expires ?: return null
        return maxOf(0L, expires - session.scripts.now)
    }

    override fun setLifetime(self: LuaHandle.Centity, ticks: Long?): Boolean {
        if (ticks != null && ticks < 1) throw LuaApiException("bad argument 'ticks' (at least 1 expected, got $ticks)")
        val instance = instance(self) ?: return false
        centities.setLifetime(instance, ticks)
        return true
    }

    override fun keep(self: LuaHandle.Centity): Boolean = instance(self)?.let(centities::keep) == true

    override fun data(self: LuaHandle.Centity): Any? {
        val instance = instance(self) ?: return null
        return session.data.table(ScriptData.Owner.Centity(instance.id), "centity ${instance.centity} ${instance.id}")
    }

    override fun node(self: LuaHandle.Centity, name: String): LuaHandle.Node? =
        instance(self)?.indexOf(name)?.let { LuaHandle.Node(self.id, name) }

    private fun mover(self: LuaHandle.Centity): Mover? = instance(self)?.mover

    private val statCalls by lazy { StatCalls(session) }

    override fun stat(self: LuaHandle.Centity, name: String): Double = statCalls.stat(
        instance(self)?.let {
            StatSheets.Holder.Centity(it.id)
        },
        name
    )

    override fun stats(self: LuaHandle.Centity): Map<String, Double> = statCalls.stats(
        instance(self)?.let {
            StatSheets.Holder.Centity(it.id)
        }
    )

    override fun modifiers(self: LuaHandle.Centity, source: String): Map<String, Double>? = statCalls.modifiers(
        instance(self)?.let {
            StatSheets.Holder.Centity(it.id)
        },
        source
    )

    override fun setModifiers(self: LuaHandle.Centity, source: String, modifiers: Map<String, Double>?): Boolean =
        statCalls.setModifiers(instance(self)?.let { StatSheets.Holder.Centity(it.id) }, source, modifiers)

    override fun addBuff(self: LuaHandle.Centity, buff: String, ticks: Long): Boolean = statCalls.addBuff(
        instance(self)?.let {
            StatSheets.Holder.Centity(it.id)
        },
        buff,
        ticks
    )

    override fun removeBuff(self: LuaHandle.Centity, buff: String): Boolean = statCalls.removeBuff(
        instance(self)?.let {
            StatSheets.Holder.Centity(it.id)
        },
        buff
    )

    override fun hasBuff(self: LuaHandle.Centity, buff: String): Boolean = statCalls.hasBuff(
        instance(self)?.let {
            StatSheets.Holder.Centity(it.id)
        },
        buff
    )

    override fun buffTicks(self: LuaHandle.Centity, buff: String): Long? = statCalls.buffTicks(
        instance(self)?.let {
            StatSheets.Holder.Centity(it.id)
        },
        buff
    )

    override fun buffs(self: LuaHandle.Centity): List<ActiveBuff> = statCalls.buffs(
        instance(self)?.let {
            StatSheets.Holder.Centity(it.id)
        }
    )

    override fun health(self: LuaHandle.Centity): Double? = instance(self)?.health?.current

    override fun maxHealth(self: LuaHandle.Centity): Double? = instance(self)?.health?.max

    override fun setHealth(self: LuaHandle.Centity, health: Double): Boolean {
        if (!health.isFinite()) throw LuaApiException("bad argument 'health' (a finite number expected)")
        val instance = instance(self)?.takeIf { it.health != null } ?: return false
        centities.healths.set(instance, health)
        return true
    }

    override fun setMaxHealth(self: LuaHandle.Centity, maxHealth: Double): Boolean {
        if (!(maxHealth > 0) ||
            !maxHealth.isFinite()
        ) {
            throw LuaApiException("bad argument 'max_health' (more than 0 expected, got $maxHealth)")
        }
        val instance = instance(self) ?: return false
        centities.healths.setMax(instance, maxHealth)
        return true
    }

    override fun damage(self: LuaHandle.Centity, amount: Double, options: CentityDamageOptions?): Double? {
        if (!(amount >= 0) || !amount.isFinite()) throw LuaApiException("bad argument 'amount' (at least 0 expected, got $amount)")
        val instance = instance(self) ?: return null
        return centities.healths.damage(instance, amount, options?.source, options?.cause)
    }

    override fun heal(self: LuaHandle.Centity, amount: Double): Double? {
        if (!(amount >= 0) || !amount.isFinite()) throw LuaApiException("bad argument 'amount' (at least 0 expected, got $amount)")
        val instance = instance(self) ?: return null
        return centities.healths.heal(instance, amount)
    }

    override fun isDead(self: LuaHandle.Centity): Boolean = instance(self)?.health?.dead == true

    override fun hasBody(self: LuaHandle.Centity): Boolean = mover(self) != null

    override fun body(self: LuaHandle.Centity): BodySettings? = mover(self)?.settings?.let { b ->
        BodySettings(
            width = b.width,
            height = b.height,
            gravity = b.gravity,
            maxFallSpeed = b.maxFallSpeed,
            drag = b.drag,
            liquidDrag = b.liquidDrag,
            liquidGravity = b.liquidGravity,
            acceleration = b.acceleration,
            airAcceleration = b.airAcceleration,
            friction = b.friction,
            stepHeight = b.stepHeight,
            bounce = b.bounce,
            fly = b.fly,
            blocks = b.blocks
        )
    }

    override fun setBody(self: LuaHandle.Centity, settings: BodySettings): Boolean {
        for ((name, value) in listOf("width" to settings.width, "height" to settings.height)) {
            if (value != null &&
                (!(value > 0) || !value.isFinite())
            ) {
                throw LuaApiException("bad argument 'settings.$name' (more than 0 expected, got $value)")
            }
        }
        val instance = instance(self) ?: return false
        val old = instance.mover?.settings ?: ResolvedBody.of(BodyDef())
        val next = ResolvedBody.of(
            BodyDef(
                width = settings.width ?: old.width,
                height = settings.height ?: old.height,
                gravity = settings.gravity ?: old.gravity,
                maxFallSpeed = settings.maxFallSpeed ?: old.maxFallSpeed,
                drag = settings.drag ?: old.drag,
                liquidDrag = settings.liquidDrag ?: old.liquidDrag,
                liquidGravity = settings.liquidGravity ?: old.liquidGravity,
                acceleration = settings.acceleration ?: old.acceleration,
                airAcceleration = settings.airAcceleration ?: old.airAcceleration,
                friction = settings.friction ?: old.friction,
                stepHeight = settings.stepHeight ?: old.stepHeight,
                bounce = settings.bounce ?: old.bounce,
                fly = settings.fly ?: old.fly,
                blocks = settings.blocks ?: old.blocks
            )
        )
        val mover = instance.mover
        if (mover == null) instance.mover = Mover(next) else mover.settings = next
        return true
    }

    override fun velocity(self: LuaHandle.Centity): Vec3? {
        val instance = instance(self) ?: return null
        return instance.flight?.velocity ?: instance.mover?.velocity ?: Vec3.ZERO
    }

    override fun setVelocity(self: LuaHandle.Centity, velocity: Vec3): Boolean {
        instance(self)?.flight?.let { flight ->
            flight.velocity = finiteVelocity(velocity, "velocity")
            flight.still = false
            return true
        }
        val mover = mover(self) ?: return false
        mover.velocity = finiteVelocity(velocity, "velocity")
        if (velocity.y > 0) mover.onGround = false
        return true
    }

    override fun addVelocity(self: LuaHandle.Centity, velocity: Vec3): Boolean {
        instance(self)?.flight?.let { flight ->
            flight.velocity = flight.velocity + finiteVelocity(velocity, "velocity")
            flight.still = false
            return true
        }
        val mover = mover(self) ?: return false
        mover.velocity = mover.velocity + finiteVelocity(velocity, "velocity")
        if (velocity.y > 0) mover.onGround = false
        return true
    }

    override fun walkVelocity(self: LuaHandle.Centity): Vec3? = mover(self)?.walk

    override fun setWalkVelocity(self: LuaHandle.Centity, velocity: Vec3?): Boolean {
        val mover = mover(self) ?: return false
        mover.walk = velocity?.let { finiteVelocity(it, "velocity") }
        return true
    }

    override fun jump(self: LuaHandle.Centity, height: Double): Boolean {
        if (!(height >= 0) || !height.isFinite()) throw LuaApiException("bad argument 'height' (at least 0 expected, got $height)")
        val mover = mover(self) ?: return false
        mover.velocity = Vec3(mover.velocity.x, mover.jumpSpeed(height), mover.velocity.z)
        mover.onGround = false
        return true
    }

    override fun launch(self: LuaHandle.Centity, options: LaunchOptions): Boolean {
        finiteVelocity(options.velocity, "options.velocity")
        fun atLeast(value: Double?, least: Double, name: String) {
            if (value != null &&
                (!(value >= least) || !value.isFinite())
            ) {
                throw LuaApiException("bad argument 'options.$name' (at least $least expected, got $value)")
            }
        }
        fun within(value: Double?, name: String) {
            if (value != null && !(value in 0.0..1.0)) throw LuaApiException("bad argument 'options.$name' (0 to 1 expected, got $value)")
        }
        atLeast(options.radius, 0.0, "radius")
        atLeast(options.restSpeed, 0.0, "rest_speed")
        within(options.drag, "drag")
        within(options.bounce, "bounce")
        within(options.friction, "friction")
        for ((name, value) in listOf(
            "pierce" to options.pierce,
            "gravity_delay" to options.gravityDelay,
            "max_bounces" to options.maxBounces
        )) {
            if (value != null && value < 0) throw LuaApiException("bad argument 'options.$name' (at least 0 expected, got $value)")
        }
        for ((name, value) in listOf("rehit" to options.rehit, "lifetime" to options.lifetime)) {
            if (value != null && value < 1) throw LuaApiException("bad argument 'options.$name' (at least 1 expected, got $value)")
        }
        options.homing?.let {
            atLeast(it.range, 0.0, "homing.range")
            atLeast(it.turn, 0.0, "homing.turn")
        }
        val gravity = options.gravity ?: 0.0
        if (!gravity.isFinite()) throw LuaApiException("bad argument 'options.gravity' (a finite number expected)")
        val instance = instance(self) ?: return false
        options.aim?.let {
            if (instance.indexOf(it) ==
                null
            ) {
                throw LuaApiException("bad argument 'options.aim' (the centity has no node \"$it\")")
            }
        }
        var filter = options.hits?.let(session::queryFilter) ?: QueryFilter()
        val owner = options.owner
        when (owner) {
            is LuaHandle.Centity -> runCatching { UUID.fromString(owner.id) }.getOrNull()?.let {
                filter =
                    filter.copy(excludeCentities = filter.excludeCentities + it)
            }
            is LuaHandle.Entity -> owner.uuidOrNull()?.let { filter = filter.copy(excludeEntities = filter.excludeEntities + it) }
            else -> {}
        }
        // A query's own limit and line of sight aren't a flight's: it meets what's in its way, in order.
        filter = filter.copy(limit = Int.MAX_VALUE, lineOfSight = false)
        // A walk would fight it for the anchor.
        instance.walk = null
        instance.flight = Flight(
            velocity = options.velocity,
            gravity = gravity,
            gravityDelay = options.gravityDelay ?: 0,
            drag = options.drag ?: 0.0,
            radius = options.radius ?: DEFAULT_RADIUS,
            pierce = options.pierce ?: 0,
            rehit = options.rehit,
            filter = filter,
            owner = owner,
            blocks = options.blocks != false,
            bounce = options.bounce,
            friction = options.friction ?: 1.0,
            maxBounces = options.maxBounces,
            restSpeed = options.restSpeed ?: DEFAULT_REST_SPEED,
            stick = options.stick == true,
            homingRange = options.homing?.range,
            homingTurn = options.homing?.turn ?: DEFAULT_HOMING_TURN,
            lifetime = options.lifetime ?: DEFAULT_LIFETIME,
            aim = options.aim,
            removeOnEnd = options.removeOnEnd != false
        ).also { centities.flights.aim(instance, it) }
        return true
    }

    override fun isLaunched(self: LuaHandle.Centity): Boolean = instance(self)?.flight != null

    override fun stopFlight(self: LuaHandle.Centity): Boolean {
        val instance = instance(self) ?: return false
        val flight = instance.flight ?: return false
        centities.flights.end(instance, flight, "stopped", Vec3(instance.anchor.x, instance.anchor.y, instance.anchor.z))
        return true
    }

    override fun isOnGround(self: LuaHandle.Centity): Boolean = mover(self)?.onGround == true

    override fun isInLiquid(self: LuaHandle.Centity): Boolean = mover(self)?.inLiquid == true

    override fun wall(self: LuaHandle.Centity): Vec3? = mover(self)?.wall

    private fun finiteVelocity(v: Vec3, name: String): Vec3 {
        if (!v.x.isFinite() ||
            !v.y.isFinite() ||
            !v.z.isFinite()
        ) {
            throw LuaApiException("bad argument '$name' (a finite velocity expected)")
        }
        return v
    }

    override fun properties(self: LuaHandle.Centity): Any? = instance(self)?.definition?.properties.orEmpty().let(::JsonObject).takeIf {
        instance(self) !=
            null
    }

    override fun bounds(self: LuaHandle.Centity): Bounds? {
        val instance = instance(self) ?: return null
        val box = centities.obstacles.of(instance).bounds ?: return null
        val a = instance.anchor
        return Bounds(Vec3(box.min.x + a.x, box.min.y + a.y, box.min.z + a.z), Vec3(box.max.x + a.x, box.max.y + a.y, box.max.z + a.z))
    }

    override fun location(self: LuaHandle.Centity): LuaLocation? =
        instance(self)?.let { LuaLocation(LuaHandle.World(it.anchor.world), Vec3(it.anchor.x, it.anchor.y, it.anchor.z), it.yaw) }

    override fun position(self: LuaHandle.Centity): Vec3? = instance(self)?.anchor?.let { Vec3(it.x, it.y, it.z) }

    override fun world(self: LuaHandle.Centity): LuaHandle.World? = instance(self)?.anchor?.world?.let { LuaHandle.World(it) }

    override fun yaw(self: LuaHandle.Centity): Double? = instance(self)?.yaw

    override fun setYaw(self: LuaHandle.Centity, degrees: Double) {
        instance(self)?.let { centities.turn(it, degrees) }
    }

    override fun lookAt(self: LuaHandle.Centity, point: Vec3): Boolean {
        val instance = instance(self) ?: return false
        val toward = instance.fromAnchor(point)
        // Straight above or below, any facing is as good as another: keep this one.
        if (toward.x * toward.x + toward.z * toward.z < 1e-18) return true
        centities.turn(instance, radiansToDegrees(atan2(-toward.x, toward.z)))
        return true
    }

    override fun toLocal(self: LuaHandle.Centity, point: Vec3): Vec3? = instance(self)?.toCentity(point)

    override fun toWorld(self: LuaHandle.Centity, point: Vec3): Vec3? = instance(self)?.toWorld(point)

    override fun teleport(self: LuaHandle.Centity, locationOrPosition: LocationOrVec3): Boolean {
        val instance = instance(self) ?: return false
        val place = locationOrPosition.place
        // The location's facing turns the centity; a bare position, or a location with no facing, keeps its own.
        return centities.teleport(instance, place.resolve(instance.anchor.world), place.yaw)
    }

    override fun follow(self: LuaHandle.Centity, player: LuaHandle.Player): Boolean {
        val instance = instance(self) ?: return false
        val who = runCatching { UUID.fromString(player.id) }.getOrNull() ?: return false
        return centities.follow(instance, who)
    }

    override fun stopFollowing(self: LuaHandle.Centity): Boolean = instance(self)?.let { centities.unfollow(it) } == true

    override fun moveTo(self: LuaHandle.Centity, target: LocationOrVec3OrEntityOrCentity, options: CentityPathOptions?): Boolean {
        val settings = pathSettings(options)
        val instance = instance(self) ?: return false
        fun place(place: LuaPlace): PathTarget? {
            val at = place.resolve(instance.anchor.world)
            return if (at.world == instance.anchor.world) PathTarget.Place(Vec3(at.x, at.y, at.z)) else null
        }
        val goal = when (target) {
            is LocationOrVec3OrEntityOrCentity.Location -> place(LuaPlace.of(target.value))
            is LocationOrVec3OrEntityOrCentity.Vec3 -> place(LuaPlace(null, target.value, null, null))
            is LocationOrVec3OrEntityOrCentity.Centity -> runCatching {
                UUID.fromString(target.value.id)
            }.getOrNull()?.let(PathTarget::Centity)
            is LocationOrVec3OrEntityOrCentity.Entity -> target.value.uuidOrNull()?.let(PathTarget::Entity)
        } ?: return false
        // A walk ends a follow, as a teleport does.
        centities.unfollow(instance)
        return centities.paths.start(instance, goal, settings)
    }

    /** `move_to`'s options, checked: a value out of range is an error. */
    private fun pathSettings(options: CentityPathOptions?): PathSettings {
        fun check(field: String, value: Double?, ok: (Double) -> Boolean, what: String): Double? {
            if (value != null && (!value.isFinite() || !ok(value))) throw LuaApiException("options.$field must be $what, not $value")
            return value
        }
        val speed =
            check("speed", options?.speed, {
                it > 0 && it <= CentityPaths.MAX_SPEED
            }, "more than 0 and at most ${CentityPaths.MAX_SPEED.toInt()}")
        val width =
            check("width", options?.width, {
                it > 0 && it <= CentityPaths.MAX_WIDTH
            }, "more than 0 and at most ${CentityPaths.MAX_WIDTH.toInt()}")
        val height =
            check("height", options?.height, {
                it > 0 && it <= CentityPaths.MAX_HEIGHT
            }, "more than 0 and at most ${CentityPaths.MAX_HEIGHT.toInt()}")
        val step = check("step_height", options?.stepHeight, { it >= 0 }, "at least 0")
        val drop = check("max_drop", options?.maxDrop, { it >= 0 }, "at least 0")
        val range =
            check("range", options?.range, {
                it > 0 && it <= CentityPaths.MAX_RANGE
            }, "more than 0 and at most ${CentityPaths.MAX_RANGE.toInt()}")
        return PathSettings(
            speed = speed ?: CentityPaths.DEFAULT_SPEED,
            fly = options?.fly == true,
            face = options?.face != false,
            width = width,
            height = height,
            stepHeight = step ?: CentityPaths.DEFAULT_STEP_HEIGHT,
            maxDrop = drop ?: CentityPaths.DEFAULT_MAX_DROP,
            range = range ?: CentityPaths.DEFAULT_RANGE,
            openDoors = options?.openDoors == true
        )
    }

    override fun stopPathing(self: LuaHandle.Centity): Boolean = instance(self)?.let { centities.paths.stop(it) } == true

    override fun hasPath(self: LuaHandle.Centity): Boolean = instance(self)?.walk != null

    override fun pathTarget(self: LuaHandle.Centity): Vec3? = instance(self)?.let { centities.paths.pathEnd(it) }

    override fun remove(self: LuaHandle.Centity) {
        instance(self)?.let { centities.remove(it) }
    }

    override fun addNode(self: LuaHandle.Centity, name: String, definition: LuaValue?): LuaHandle.Node? {
        val instance = instance(self) ?: return null
        return session.addNode(instance, name, null, definition)
    }

    override fun nodes(self: LuaHandle.Centity): List<LuaHandle.Node> =
        instance(self)?.nodeNames().orEmpty().map { LuaHandle.Node(self.id, it) }

    override fun roots(self: LuaHandle.Centity): List<LuaHandle.Node> =
        instance(self)?.definition?.nodes.orEmpty().filter { it.isRoot }.map { LuaHandle.Node(self.id, it.name) }

    override fun playAnimation(self: LuaHandle.Centity, name: String, options: AnimationOptions?): Boolean {
        val speed = options?.speed ?: 1.0
        if (!speed.isFinite() || speed < 0) throw LuaApiException("bad field 'options.speed' (a speed can't be negative)")
        val from = options?.fromTick ?: 0.0
        if (!from.isFinite() || from < 0) throw LuaApiException("bad field 'options.from_tick' (a tick can't be negative)")
        val blend = options?.blendTicks ?: 0L
        if (blend < 0) throw LuaApiException("bad field 'options.blend_ticks' (ticks can't be negative)")
        val instance = instance(self) ?: return false
        val clip = clip(instance, name)
        val loop = when (options?.loop) {
            true -> LoopMode.LOOP
            false -> if (clip.loop == LoopMode.LOOP) LoopMode.ONCE else clip.loop
            null -> null
        }
        instance.animations.play(
            clip,
            speed = speed,
            from = from * Centities.SECONDS_PER_TICK,
            loop = loop,
            blendSeconds = blend * Centities.SECONDS_PER_TICK,
            pose = instance.shownPose()
        )
        instance.animate()
        centities.animationStarted(instance, name)
        return true
    }

    /** The clip called [name]; one the centity doesn't have is an error. */
    private fun clip(instance: Instance, name: String) =
        instance.definition.animation(name) ?: throw LuaApiException(noAnimation(instance, name))

    override fun pauseAnimation(self: LuaHandle.Centity, name: String): Boolean {
        val instance = instance(self) ?: return false
        clip(instance, name)
        return instance.animations.pause(name)
    }

    override fun resumeAnimation(self: LuaHandle.Centity, name: String): Boolean {
        val instance = instance(self) ?: return false
        clip(instance, name)
        return instance.animations.resume(name)
    }

    override fun animationTick(self: LuaHandle.Centity, name: String): Double? {
        val instance = instance(self) ?: return null
        clip(instance, name)
        return instance.animations.position(name)?.let { it / Centities.SECONDS_PER_TICK }
    }

    override fun seekAnimation(self: LuaHandle.Centity, name: String, tick: Double): Boolean {
        if (!tick.isFinite() || tick < 0) throw LuaApiException("bad argument 'tick' (a tick can't be negative)")
        val instance = instance(self) ?: return false
        clip(instance, name)
        if (!instance.animations.seek(name, tick * Centities.SECONDS_PER_TICK)) return false
        instance.animate()
        return true
    }

    override fun animationSpeed(self: LuaHandle.Centity, name: String): Double? {
        val instance = instance(self) ?: return null
        clip(instance, name)
        return instance.animations.speed(name)
    }

    override fun setAnimationSpeed(self: LuaHandle.Centity, name: String, multiplier: Double): Boolean {
        if (!multiplier.isFinite() || multiplier < 0) throw LuaApiException("bad argument 'multiplier' (a speed can't be negative)")
        val instance = instance(self) ?: return false
        clip(instance, name)
        return instance.animations.setSpeed(name, multiplier)
    }

    // ---- looks, over every node --------------------------------------------------

    /** Indices of the nodes that draw something. */
    private fun Instance.drawn(): List<Int> = definition.nodes.indices.filter { display(it) != null }

    /** Indices of the block and item displays: the ones a glow colour shows on. */
    private fun Instance.coloured(): List<Int> = drawn().filter { display(it) !is TextDisplay }

    override fun isGlowing(self: LuaHandle.Centity): Boolean {
        val instance = instance(self) ?: return false
        val drawn = instance.drawn()
        return drawn.isNotEmpty() && drawn.all { instance.look(it).glowing }
    }

    override fun setGlowing(self: LuaHandle.Centity, glowing: Boolean): Boolean {
        val instance = instance(self) ?: return false
        for (index in instance.drawn()) instance.setLook(index, instance.look(index).copy(glowing = glowing))
        return true
    }

    override fun glowColor(self: LuaHandle.Centity): String? {
        val instance = instance(self) ?: return null
        val colours = instance.coloured().map { instance.look(it).glowColor }.distinct()
        return colours.singleOrNull()?.let(::formatRgb)
    }

    override fun setGlowColor(self: LuaHandle.Centity, color: String?): Boolean {
        val rgb = color?.let { parseRgb(it, "color") }
        val instance = instance(self) ?: return false
        val coloured = instance.coloured()
        for (index in coloured) instance.setLook(index, instance.look(index).copy(glowColor = rgb))
        return coloured.isNotEmpty()
    }

    // ---- who sees it ---------------------------------------------------------------

    private fun playerId(player: LuaHandle.Player) = UUID.fromString(player.id)

    override fun hideFrom(self: LuaHandle.Centity, player: LuaHandle.Player): Boolean {
        val instance = instance(self) ?: return false
        centities.setHidden(instance, playerId(player), true)
        return true
    }

    override fun showTo(self: LuaHandle.Centity, player: LuaHandle.Player): Boolean {
        val instance = instance(self) ?: return false
        centities.setHidden(instance, playerId(player), false)
        return true
    }

    override fun isHiddenFrom(self: LuaHandle.Centity, player: LuaHandle.Player): Boolean =
        instance(self)?.hiddenFrom?.contains(playerId(player)) == true

    override fun viewRange(self: LuaHandle.Centity): Double? = instance(self)?.viewRange

    override fun setViewRange(self: LuaHandle.Centity, blocks: Double): Boolean {
        if (!blocks.isFinite() || blocks < 0) throw LuaApiException("bad argument 'blocks' (a view range can't be negative)")
        val instance = instance(self) ?: return false
        instance.viewRange = blocks
        return true
    }

    private fun noAnimation(instance: Instance, name: String): String {
        val known = instance.definition.animations.map { it.name }
        return "${instance.centity} has no animation \"$name\"" + if (known.isEmpty()) "" else " (it has: ${known.joinToString()})"
    }

    override fun stopAnimation(self: LuaHandle.Centity, name: String?) {
        val instance = instance(self) ?: return
        if (name == null) instance.animations.stopAll() else instance.animations.stop(name)
        instance.animate()
    }

    override fun isAnimationPlaying(self: LuaHandle.Centity, name: String): Boolean = instance(self)?.animations?.isPlaying(name) == true

    override fun playingAnimations(self: LuaHandle.Centity): List<String> = instance(self)?.animations?.names().orEmpty()

    override fun animations(self: LuaHandle.Centity): List<String> = instance(self)?.definition?.animations?.map { it.name }.orEmpty()
}

/** A text display's opacity when it's fully opaque. */
internal const val OPAQUE = 255

private val RGB = Regex("^#[0-9a-fA-F]{6}$")
internal val ARGB = Regex("^#[0-9a-fA-F]{8}$")

/** `#RRGGBB` as `0xRRGGBB`; anything else is an error naming [parameter]. */
internal fun parseRgb(text: String, parameter: String): Int {
    if (!RGB.matches(text)) throw LuaApiException("bad argument '$parameter' (#RRGGBB expected, got \"$text\")")
    return text.substring(1).toInt(16)
}

internal fun formatRgb(rgb: Int): String = "#%06x".format(rgb and 0xFFFFFF)

/** What a launch without them flies with. */
private const val DEFAULT_RADIUS = 0.1
private const val DEFAULT_REST_SPEED = 1.0
private const val DEFAULT_HOMING_TURN = 180.0
private const val DEFAULT_LIFETIME = 600L
