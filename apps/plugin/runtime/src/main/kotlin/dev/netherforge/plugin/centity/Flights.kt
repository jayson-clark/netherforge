package dev.netherforge.plugin.centity

import dev.netherforge.format.centity.Channel
import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.binding.BlockPosition
import dev.netherforge.plugin.binding.Events
import dev.netherforge.plugin.binding.FlightBlockEvent
import dev.netherforge.plugin.binding.FlightEndEvent
import dev.netherforge.plugin.binding.FlightHitEvent
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.platform.BlockHit
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.script.Scripts
import java.util.UUID
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * A centity flying as a projectile (`centity:launch`): what it was launched with, resolved, and how its flight
 * is going. Runtime state on [Instance.flight], never saved; [Flights] moves it.
 */
internal class Flight(
    /** Blocks per second. */
    var velocity: Vec3,
    /** Blocks per second squared. */
    val gravity: Double,
    val gravityDelay: Long,
    /** Fraction of speed shed per second. */
    val drag: Double,
    val radius: Double,
    var pierce: Long,
    val rehit: Long?,
    internal val filter: QueryFilter,
    val owner: LuaHandle?,
    val blocks: Boolean,
    val bounce: Double?,
    val friction: Double,
    val maxBounces: Long?,
    val restSpeed: Double,
    val stick: Boolean,
    val homingRange: Double?,
    /** Degrees per second. */
    val homingTurn: Double,
    val lifetime: Long,
    val aim: String?,
    val removeOnEnd: Boolean
) {
    /** Ticks since it was launched. */
    var age = 0L

    var bounces = 0L

    /** Stuck in a block or lying where it came to rest: it no longer moves, and waits out its lifetime. */
    var still = false

    /** What it has hit (instances' and entities' ids) and when: each is hit once, or again after [rehit] ticks. */
    val hitAt = HashMap<UUID, Long>()
}

/**
 * Flies every launched centity a tick: homing, gravity and drag change its velocity, one swept query
 * ([SpatialQueries], a [QueryShape.Capsule] of its radius up to the first block in its way) finds what it
 * passes in order (`hit`, its `pierce`), and the block ends it, bounces it or holds it (`flight_end`,
 * `bounce`, `stick`).
 */
internal class Flights(
    private val platform: Platform,
    private val scripts: Scripts,
    private val spatial: SpatialQueries,
    private val entityHandle: (UUID) -> LuaHandle.Entity,
    private val remove: (Instance) -> Unit
) {
    private fun handle(instance: Instance) = LuaHandle.Centity(instance.id.toString())

    private fun here(instance: Instance) = Vec3(instance.anchor.x, instance.anchor.y, instance.anchor.z)

    /** Flies [instance] a tick, when it's flying. */
    fun step(instance: Instance) {
        val flight = instance.flight ?: return
        flight.age++
        if (flight.age > flight.lifetime) return end(instance, flight, "lifetime", here(instance))
        if (flight.still) return
        var v = flight.velocity
        flight.homingRange?.let { range -> v = home(instance, flight, v, range) }
        if (flight.age > flight.gravityDelay) v = Vec3(v.x, v.y - flight.gravity * SECONDS, v.z)
        if (flight.drag > 0) v = v * (1 - flight.drag).pow(SECONDS)
        val from = here(instance)
        val to = from + v * SECONDS
        val world = instance.anchor.world
        val span = QueryShape.length(to - from)
        val block = if (flight.blocks &&
            span > 1e-9
        ) {
            platform.worlds.raycastBlocks(world, from, (to - from) * (1 / span), span, false)
        } else {
            null
        }
        val reach = block?.position ?: to
        flight.velocity = v
        // What it passes on its way to the block (or to where it goes), in order.
        if (span > 1e-9 || flight.radius > 0) {
            val filter = flight.filter.copy(excludeCentities = flight.filter.excludeCentities + instance.id)
            for (found in spatial.find(world, QueryShape.Capsule(from, reach, flight.radius), filter)) {
                val id = when (found) {
                    is Found.Centity -> found.instance.id
                    is Found.Entity -> found.id
                }
                val last = flight.hitAt[id]
                if (last != null && (flight.rehit == null || flight.age - last < flight.rehit)) continue
                flight.hitAt[id] = flight.age
                val event = when (found) {
                    is Found.Centity -> FlightHitEvent(
                        handle(instance),
                        target = handle(found.instance),
                        node = LuaHandle.Node(found.instance.id.toString(), found.instance.definition.nodes[found.node].name),
                        position = found.reach.position,
                        velocity = v,
                        owner = flight.owner
                    )
                    is Found.Entity -> FlightHitEvent(
                        handle(instance),
                        entity = entityHandle(found.id),
                        position = found.reach.position,
                        velocity = v,
                        owner = flight.owner
                    )
                }
                val passed = scripts.emit(Events.CENTITY_HIT, handle(instance), event)
                // A handler removed it, stopped its flight or launched it again: this flight is over.
                if (instance.removed || instance.flight !== flight) return
                if (passed) continue
                if (flight.pierce > 0) {
                    flight.pierce--
                    continue
                }
                if (!move(instance, flight, found.reach.position)) return
                return end(instance, flight, "hit", found.reach.position, event.target, event.entity)
            }
        }
        if (block != null) return meet(instance, flight, block, v)
        if (move(instance, flight, to)) aim(instance, flight)
    }

    /** It meets [block] going at [v]: it sticks, bounces or ends there. */
    private fun meet(instance: Instance, flight: Flight, block: BlockHit, v: Vec3) {
        val n = block.normal
        val into = QueryShape.dot(v, n)
        val blockHandle = LuaHandle.Block(instance.anchor.world, BlockPosition.pack(block.x, block.y, block.z))
        val payload = FlightBlockEvent(handle(instance), blockHandle, block.position, n, -into, flight.owner)
        if (flight.stick) {
            if (!move(instance, flight, block.position)) return
            flight.velocity = Vec3.ZERO
            flight.still = true
            scripts.emit(Events.CENTITY_STICK, handle(instance), payload)
            return
        }
        val bounce = flight.bounce
        val max = flight.maxBounces
        if (bounce == null || (max != null && flight.bounces >= max)) {
            if (!move(instance, flight, block.position)) return
            return end(instance, flight, "block", block.position, block = blockHandle, normal = n)
        }
        // Off the face: what went into it comes back by `bounce`, what went along it keeps `friction`.
        val normalPart = n * into
        val along = v - normalPart
        var out = along * flight.friction - normalPart * bounce
        flight.bounces++
        if (n.y > FLOOR && QueryShape.length(out) < flight.restSpeed) {
            out = Vec3.ZERO
            flight.still = true
        }
        flight.velocity = out
        if (!move(instance, flight, block.position + n * SURFACE)) return
        scripts.emit(Events.CENTITY_BOUNCE, handle(instance), payload)
        if (!instance.removed && instance.flight === flight) aim(instance, flight)
    }

    /** Turns [v] toward the nearest thing it can hit within [range], by at most its turn rate. */
    private fun home(instance: Instance, flight: Flight, v: Vec3, range: Double): Vec3 {
        val speed = QueryShape.length(v)
        if (speed < 1e-9) return v
        val from = here(instance)
        val filter = flight.filter.copy(excludeCentities = flight.filter.excludeCentities + instance.id, limit = 1)
        val target = spatial.find(instance.anchor.world, QueryShape.Sphere(from, range), filter).firstOrNull() ?: return v
        val toward = target.reach.position - from
        val distance = QueryShape.length(toward)
        if (distance < 1e-9) return v
        val want = toward * (1 / distance)
        val now = v * (1 / speed)
        val angle = acos(QueryShape.dot(now, want).coerceIn(-1.0, 1.0))
        val most = Math.toRadians(flight.homingTurn) * SECONDS
        if (angle <= most) return want * speed
        // Rotate toward it in the plane of the two, by `most`.
        val axis = cross(now, want).let { a -> QueryShape.length(a).let { l -> if (l < 1e-9) return v else a * (1 / l) } }
        val turned = now * cos(most) + cross(axis, now) * sin(most) + axis * (QueryShape.dot(axis, now) * (1 - cos(most)))
        return turned * speed
    }

    /** Moves its anchor to [to]; ends it `unloaded` (and answers false) where its entities couldn't stand. */
    private fun move(instance: Instance, flight: Flight, to: Vec3): Boolean {
        val next = instance.anchor.copy(x = to.x, y = to.y, z = to.z)
        if (!platform.worlds.entitiesLoaded(next)) {
            end(instance, flight, "unloaded", here(instance))
            return false
        }
        instance.anchor = next
        return true
    }

    /** Points its `aim` node's +Z along its velocity. */
    fun aim(instance: Instance, flight: Flight) {
        val node = flight.aim ?: return
        val index = instance.indexOf(node) ?: return
        val v = flight.velocity
        if (QueryShape.length(v) < 1e-6) return
        val at = instance.worldMatrix(index).translation()
        val target = instance.parentWorldMatrix(index).invertAffine()?.transformPosition(at + v) ?: return
        val forward = target - instance.get(index, Channel.TRANSLATION)
        val length = QueryShape.length(forward)
        if (length < 1e-9) return
        instance.set(index, Channel.ROTATION, lookRotation(forward * (1 / length)))
    }

    /** Ends [instance]'s flight: `flight_end`, then, unless told not to, it's removed. */
    fun end(
        instance: Instance,
        flight: Flight,
        reason: String,
        position: Vec3,
        target: LuaHandle.Centity? = null,
        entity: LuaHandle.Entity? = null,
        block: LuaHandle.Block? = null,
        normal: Vec3? = null
    ) {
        if (instance.flight !== flight) return
        instance.flight = null
        scripts.emit(
            Events.CENTITY_FLIGHT_END,
            handle(instance),
            FlightEndEvent(handle(instance), reason, position, target, entity, block, normal, flight.owner)
        )
        if (flight.removeOnEnd && !instance.removed && instance.flight == null) remove(instance)
    }

    private fun cross(a: Vec3, b: Vec3) = Vec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)

    companion object {
        /** A tick, in seconds. */
        const val SECONDS = 1.0 / 20

        /** A face whose normal points this far up is a floor it can come to rest on. */
        const val FLOOR = 0.7

        /** How far off a face a bounce leaves it, so the next tick's ray doesn't start inside the block. */
        const val SURFACE = 0.01
    }
}
