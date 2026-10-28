package dev.netherforge.plugin.centity

import dev.netherforge.format.centity.Channel
import dev.netherforge.format.centity.ResolvedPhysics
import dev.netherforge.format.game.Box
import dev.netherforge.format.math.Matrix4
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.math.degreesToRadians
import dev.netherforge.format.math.radiansToDegrees
import dev.netherforge.plugin.binding.CollideEvent
import dev.netherforge.plugin.binding.Events
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.NodeEvent
import dev.netherforge.plugin.physics.BodyState
import dev.netherforge.plugin.physics.PhysicsCollider
import dev.netherforge.plugin.physics.PhysicsSolver
import dev.netherforge.plugin.physics.Quat
import dev.netherforge.plugin.physics.RigidBody
import dev.netherforge.plugin.physics.boundsOf
import dev.netherforge.plugin.platform.Platform
import kotlin.math.abs
import kotlin.math.floor

/**
 * The pass that steps every physics body on one instance, once a tick, after
 * animation and before scripts.
 *
 * Every body is measured against one composition first and only then moved,
 * so they're handed to the solver together: that is what lets two bodies of
 * one centity share momentum. A body's velocity and orientation live on the
 * instance ([Instance.body]) beside its transform, and are rebuilt when the
 * definition is.
 *
 * Bodies simulate relative to the anchor along the world's axes (the frame of
 * [Instance.placed]): the centity's space turned by its yaw. So gravity,
 * velocities, impulses and world blocks need no turning, and a turned centity
 * collides with the world exactly as an unturned one would. What a body runs
 * into, in that frame:
 *
 *  - the world's blocks, by their live collision shapes, asked of the
 *    platform for the region the body can reach this tick, in loaded chunks
 *    only;
 *  - the hitboxes of every centity nearby, each box its own obstacle (a stair
 *    platform is a step to roll over, not a cube). The body's own subtree is
 *    left out (its children move with it), and so are the other bodies of its
 *    own instance, which the solver pairs with it instead. Bodies of another
 *    instance are immovable scenery: momentum is shared only within one.
 *
 * After the step, a node's `collide` handlers hear what its body started
 * touching (a landing, a hit, not resting contact), and `wake` and `sleep`
 * when it changed between moving and settled.
 *
 * Physics writes the node's translation and rotation through the same setters
 * a script uses. The rotation it wrote is remembered; when the node's rotation
 * reads differently next tick, a script or clip set it, and the body's
 * orientation is re-seeded from the pose rather than snapped back.
 */
class Physics(private val platform: Platform, private val centities: Centities) {

    private class Snapshot(
        val index: Int,
        val entry: PhysicsSolver.Entry,
        val state: BodyState,
        val hitbox: List<Box>?,
        val scale: Vec3,
        val parentInverse: Matrix4,
        val parentRotation: Quat,
        val startedAt: Vec3,
        val startedFacing: Quat
    ) {
        val body get() = entry.body
        val physics: ResolvedPhysics get() = entry.physics
    }

    /** A body started touching something: what [Physics] tells its node's `collide` handlers, in world terms. */
    private class Collision(val index: Int, val other: Int?, val position: Vec3, val normal: Vec3, val speed: Double)

    /** Advances every body on [instance] by [seconds], then tells scripts what happened to them. */
    fun step(instance: Instance, seconds: Double) {
        moveKinematic(instance, seconds)
        val bodies = snapshot(instance)
        if (bodies.isEmpty()) return
        val collisions = advance(instance, bodies, seconds)
        report(instance, bodies, collisions)
    }

    private fun advance(instance: Instance, bodies: List<Snapshot>, seconds: Double): List<Collision> {
        gatherObstacles(instance, bodies, seconds)
        if (bodies.none { it.entry.active }) return emptyList()

        PhysicsSolver.solve(bodies.map { it.entry }, seconds)

        val collisions = mutableListOf<Collision>()
        for (snapshot in bodies) {
            val tracked = snapshot.state
            val body = snapshot.body
            if (snapshot.entry.wokenByContact) tracked.wake()
            if (!snapshot.entry.active) continue
            collisions += collisions(instance, snapshot, bodies)
            tracked.velocity = body.velocity
            tracked.angularVelocity = body.angularVelocity
            tracked.orientation = body.orientation
            tracked.onGround = snapshot.entry.onGround
            tracked.considerSleeping(snapshot.physics)
            if (stirred(snapshot)) write(instance, snapshot)
        }
        reanchor(instance, bodies)
        return collisions
    }

    /**
     * What a simulated body started touching this step: each thing it touches
     * now that it didn't after its last step. A body resting on the floor
     * touches it every step, so only the landing counts.
     */
    private fun collisions(instance: Instance, snapshot: Snapshot, bodies: List<Snapshot>): List<Collision> {
        val tracked = snapshot.state
        val touches = snapshot.entry.touches
        val now = touches.keys.map { (other, facing) -> (bodies.firstOrNull { it.entry === other }?.index ?: -1) to facing }.toSet()
        val began = now - tracked.touching
        tracked.touching = now
        if (began.isEmpty() || !centities.listening(instance, snapshot.index, Events.NODE_COLLIDE)) return emptyList()
        return touches.entries.mapNotNull { (key, touch) ->
            val other = bodies.firstOrNull { it.entry === key.first }?.index
            if ((other ?: -1) to key.second !in began) return@mapNotNull null
            Collision(snapshot.index, other, instance.atAnchor(touch.point), touch.normal, touch.speed)
        }
    }

    /** `collide`, then `wake` and `sleep` for each body whose state changed since scripts were last told. */
    private fun report(instance: Instance, bodies: List<Snapshot>, collisions: List<Collision>) {
        val id = instance.id.toString()
        // A handler may remove nodes, which renumbers the rest: go by name, and skip what's gone.
        val names = instance.definition.nodes.map { it.name }
        for (collision in collisions) {
            if (instance.removed) return
            val index = instance.indexOf(names[collision.index]) ?: continue
            val other = collision.other?.let { names[it] }
            centities.emitNode(
                Events.NODE_COLLIDE,
                instance,
                index,
                CollideEvent(
                    node = LuaHandle.Node(id, names[collision.index]),
                    other = other?.let { LuaHandle.Node(id, it) },
                    hitPosition = collision.position,
                    hitNormal = collision.normal,
                    speed = collision.speed
                )
            )
        }
        for (snapshot in bodies) {
            if (instance.removed) return
            val tracked = snapshot.state
            if (tracked.asleep == tracked.reportedAsleep) continue
            tracked.reportedAsleep = tracked.asleep
            val event = if (tracked.asleep) Events.NODE_SLEEP else Events.NODE_WAKE
            val name = names[snapshot.index]
            val index = instance.indexOf(name) ?: continue
            centities.emitNode(event, instance, index, NodeEvent(LuaHandle.Node(id, name)))
        }
    }

    /**
     * Keeps a travelling body near its anchor. Entities stand at the anchor, and
     * chunk loading, tracking range and the index all follow it, so a crate
     * that rolls away would otherwise leave all of that behind (and snap back
     * after a restart). Past [REANCHOR] blocks the anchor moves under the body
     * and every root node moves back by as much (turned into the centity's
     * space): no world position changes.
     * Not while a clip plays, which writes root translations itself.
     */
    private fun reanchor(instance: Instance, bodies: List<Snapshot>) {
        if (instance.animations.active) return
        val far = bodies.map { it.body.center }.maxByOrNull { it.x * it.x + it.z * it.z } ?: return
        if (far.x * far.x + far.z * far.z < REANCHOR * REANCHOR) return
        val shift = Vec3(floor(far.x), 0.0, floor(far.z))
        val back = instance.turn().invertAffine()!!.transformDirection(shift)
        for ((index, node) in instance.definition.nodes.withIndex()) {
            if (node.isRoot) instance.set(index, Channel.TRANSLATION, instance.get(index, Channel.TRANSLATION) - back)
        }
        centities.moveAnchor(instance, instance.anchor.offset(shift.x, 0.0, shift.z))
    }

    private fun snapshot(instance: Instance): List<Snapshot> {
        val nodes = instance.definition.nodes
        if (nodes.none { it.physics != null }) return emptyList()
        val world = instance.placed()
        val turn = instance.turn()
        val out = mutableListOf<Snapshot>()
        for ((index, node) in nodes.withIndex()) {
            val tracked = instance.body(index) ?: continue
            // Frozen and kinematic bodies aren't solved; they're scenery (see [hitboxes]).
            if (tracked.scenery) continue
            val authoredPhysics = node.physics ?: continue
            val physics = if (tracked.gravity) authoredPhysics else authoredPhysics.copy(gravity = 0.0)
            val matrix = world[index]
            // A root's parent is the centity's space, turned by its yaw.
            val parentMatrix = if (node.parentIndex >= 0) world[node.parentIndex] else turn
            // A parent flattened to nothing has no inverse to write the answer back through; wait for it.
            val parentInverse = parentMatrix.invertAffine() ?: continue

            val authored = instance.get(index, Channel.ROTATION)
            if (tracked.orientation == null || tracked.writtenRotation != authored) {
                tracked.orientation = Quat.fromBasis(matrix)
                tracked.writtenRotation = authored
                tracked.wake()
            }
            val hitbox = hitboxOf(instance, index)
            val body = PhysicsCollider.bodyFor(
                physics,
                hitbox,
                matrix,
                tracked.orientation ?: Quat.IDENTITY,
                if (tracked.asleep) Vec3.ZERO else tracked.velocity,
                if (tracked.asleep) Vec3.ZERO else tracked.angularVelocity
            )
            val entry = PhysicsSolver.Entry(physics, body)
            entry.active = !tracked.asleep
            out += Snapshot(
                index,
                entry,
                tracked,
                hitbox,
                matrix.scale(),
                parentInverse,
                Quat.fromBasis(parentMatrix),
                body.center,
                body.orientation
            )
        }
        return out
    }

    /** The node's hitbox boxes in node space, or null without a hitbox (the collider is then the unit cube). */
    private fun hitboxOf(instance: Instance, index: Int): List<Box>? =
        instance.definition.nodes[index].hitbox?.let { centities.boxesOf(instance, index) }

    /**
     * Asks the world what is in each body's way. A sleeping body skips it unless
     * it's due its support check (about once a second) or something awake is
     * close enough to knock it; a sleeper that has lost its support wakes.
     */
    private fun gatherObstacles(instance: Instance, bodies: List<Snapshot>, seconds: Double) {
        for (snapshot in bodies) {
            val entry = snapshot.entry
            val tracked = snapshot.state
            if (!entry.active) {
                tracked.sinceSupportCheck++
                val due = tracked.sinceSupportCheck >= BodyState.SUPPORT_CHECK_STEPS
                if (!due && !nearAnythingAwake(snapshot, bodies)) continue
                if (due) tracked.sinceSupportCheck = 0
            }
            val region = PhysicsSolver.regionFor(snapshot.physics, snapshot.body, seconds)
            val obstacles = mutableListOf<Box>()
            if (snapshot.physics.blocks) blocks(instance, region, obstacles)
            if (snapshot.physics.entities) hitboxes(instance, snapshot.index, region, obstacles)
            entry.obstacles = obstacles
            if (!entry.active && !PhysicsSolver.supported(snapshot.body, obstacles)) {
                tracked.wake()
                entry.active = true
            }
        }
    }

    private fun blocks(instance: Instance, region: Box, out: MutableList<Box>) {
        val a = instance.anchor
        // A body the size of a house would walk a house-sized region every tick; past this it doesn't collide at all.
        val size = region.size
        if (size.x > MAX_SPAN || size.y > MAX_SPAN || size.z > MAX_SPAN) return
        val boxes = platform.worlds.collisionBoxes(
            a.world,
            a.x + region.min.x,
            a.y + region.min.y,
            a.z + region.min.z,
            a.x + region.max.x,
            a.y + region.max.y,
            a.z + region.max.z
        )
        val shift = Vec3(-a.x, -a.y, -a.z)
        for (box in boxes) out += Box(box.min + shift, box.max + shift)
    }

    private fun hitboxes(instance: Instance, bodyIndex: Int, region: Box, out: MutableList<Box>) {
        val anchor = instance.anchor
        // Only the instances whose boxes come near the region, and the body's
        // own (whose scenery bodies aren't filed), in the order they'd be
        // walked in: the solver meets obstacles in the order they're listed.
        val grid = centities.obstacles
        val candidates = nearby
        candidates.clear()
        grid.near(
            anchor.world,
            anchor.x + region.min.x,
            anchor.y + region.min.y,
            anchor.z + region.min.z,
            anchor.x + region.max.x,
            anchor.y + region.max.y,
            anchor.z + region.max.z,
            candidates
        )
        if (instance.gridStamp != grid.lastQuery) {
            candidates += instance
            candidates.sortBy { it.order }
        }
        for (other in candidates) {
            if (other.removed || other.anchor.world != anchor.world) continue
            val shift = Vec3(other.anchor.x - anchor.x, other.anchor.y - anchor.y, other.anchor.z - anchor.z)
            if (abs(shift.x) > SEARCH_RADIUS || abs(shift.y) > SEARCH_RADIUS || abs(shift.z) > SEARCH_RADIUS) continue
            if (other === instance) {
                ownHitboxes(instance, bodyIndex, region, out)
                continue
            }
            // Another instance's boxes are the same for every body that asks this tick; measured once.
            val obstacles = grid.of(other)
            val bounds = obstacles.bounds ?: continue
            if (!overlaps(Box(bounds.min + shift, bounds.max + shift), region)) continue
            for (box in obstacles.boxes) {
                val moved = Box(box.min + shift, box.max + shift)
                if (overlaps(moved, region)) out += moved
            }
        }
    }

    /** The hitboxes a body of [instance] hits among its own nodes: not its subtree, and of its other bodies only the scenery. */
    private fun ownHitboxes(instance: Instance, bodyIndex: Int, region: Box, out: MutableList<Box>) {
        val skip = subtree(instance, bodyIndex)
        val world = instance.placed()
        for ((index, node) in instance.definition.nodes.withIndex()) {
            if (index in skip) continue
            if (node.physics != null) {
                // Its own bodies are paired by the solver, except the ones standing still for it.
                if (instance.body(index)?.scenery == true) {
                    sceneryBox(instance, index)?.let { box ->
                        if (overlaps(box, region)) out += box
                    }
                }
                continue
            }
            if (node.hitbox == null || !instance.shown(index)) continue
            for (box in centities.boxesOf(instance, index)) {
                val bounds = boundsOf(listOf(box), world[index])
                if (overlaps(bounds, region)) out += bounds
            }
        }
    }

    /** Reused by [hitboxes]: the main thread is the only one that steps physics. */
    private val nearby = ArrayList<Instance>()

    /** The box around a frozen or kinematic body's collider, relative to its own anchor: what its centity's other bodies hit. */
    private fun sceneryBox(instance: Instance, index: Int): Box? {
        val body = bodyOf(instance, index) ?: return null
        val extent = body.extents()
        var min = Vec3(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
        var max = Vec3(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        for (sx in SIGNS) {
            for (sy in SIGNS) {
                for (sz in SIGNS) {
                    val corner = body.center + body.orientation.rotate(Vec3(extent.x * sx, extent.y * sy, extent.z * sz))
                    min = Vec3(minOf(min.x, corner.x), minOf(min.y, corner.y), minOf(min.z, corner.z))
                    max = Vec3(maxOf(max.x, corner.x), maxOf(max.y, corner.y), maxOf(max.z, corner.z))
                }
            }
        }
        return Box(min, max)
    }

    /**
     * Moves every awake kinematic body by its own velocity and spin, as a
     * script asked: no gravity, nothing it hits. Written through the setters,
     * as the solver writes.
     */
    private fun moveKinematic(instance: Instance, seconds: Double) {
        val nodes = instance.definition.nodes
        var world: List<Matrix4>? = null
        for ((index, node) in nodes.withIndex()) {
            val physics = node.physics ?: continue
            val tracked = instance.body(index) ?: continue
            if (!tracked.kinematic || !tracked.enabled) continue
            if (tracked.velocity == Vec3.ZERO && tracked.angularVelocity == Vec3.ZERO) continue
            val placed = world ?: instance.placed().also { world = it }
            val matrix = placed[index]
            val parentMatrix = if (node.parentIndex >= 0) placed[node.parentIndex] else instance.turn()
            val parentInverse = parentMatrix.invertAffine() ?: continue
            val origin = matrix.translation() + tracked.velocity * seconds
            instance.set(index, Channel.TRANSLATION, parentInverse.transformPosition(origin))
            if (physics.rotates && tracked.angularVelocity != Vec3.ZERO) {
                val facing = (tracked.orientation ?: Quat.fromBasis(matrix)).integrate(tracked.angularVelocity, seconds)
                tracked.orientation = facing
                val euler = (Quat.fromBasis(parentMatrix).conjugate() * facing).toEuler()
                instance.set(index, Channel.ROTATION, euler)
                tracked.writtenRotation = euler
            }
            // Positions change under it; later nodes need the new composition.
            world = null
        }
    }

    private fun subtree(instance: Instance, root: Int): Set<Int> {
        val nodes = instance.definition.nodes
        return nodes.indices.filter { index ->
            var at = index
            while (at >= 0 && at != root) at = nodes[at].parentIndex
            at == root
        }.toSet()
    }

    private fun overlaps(a: Box, b: Box) = a.min.x < b.max.x &&
        a.max.x > b.min.x &&
        a.min.y < b.max.y &&
        a.max.y > b.min.y &&
        a.min.z < b.max.z &&
        a.max.z > b.min.z

    private fun nearAnythingAwake(snapshot: Snapshot, bodies: List<Snapshot>): Boolean {
        val reach = snapshot.body.boundingRadius()
        for (other in bodies) {
            if (other === snapshot || !other.entry.active) continue
            val d = other.body.center - snapshot.body.center
            val together = reach + other.body.boundingRadius() + WAKE_MARGIN
            if (d.x * d.x + d.y * d.y + d.z * d.z <= together * together) return true
        }
        return false
    }

    private fun stirred(snapshot: Snapshot): Boolean {
        val body = snapshot.body
        val was = snapshot.startedAt
        if (abs(body.center.x - was.x) > STILL || abs(body.center.y - was.y) > STILL || abs(body.center.z - was.z) > STILL) return true
        val facing = snapshot.startedFacing
        val now = body.orientation
        return abs(now.x - facing.x) > STILL_TURN ||
            abs(now.y - facing.y) > STILL_TURN ||
            abs(now.z - facing.z) > STILL_TURN ||
            abs(now.w - facing.w) > STILL_TURN
    }

    /** The body's pose back onto the node, through the setters a script uses, in its parent's frame. */
    private fun write(instance: Instance, snapshot: Snapshot) {
        val body = snapshot.body
        val origin = PhysicsCollider.originFor(snapshot.physics, snapshot.hitbox, body, snapshot.scale)
        val local = snapshot.parentInverse.transformPosition(origin)
        instance.set(snapshot.index, Channel.TRANSLATION, local)
        if (snapshot.physics.rotates) {
            val relative = snapshot.parentRotation.conjugate() * body.orientation
            val euler = relative.toEuler()
            instance.set(snapshot.index, Channel.ROTATION, euler)
            snapshot.state.writtenRotation = euler
        }
    }

    // ---- what scripts ask ------------------------------------------------------

    /** The body at [index] as it stands now, for mass and impulses; null for a node without physics. */
    fun bodyOf(instance: Instance, index: Int): RigidBody? {
        val physics = instance.definition.nodes[index].physics ?: return null
        val tracked = instance.body(index) ?: return null
        val matrix = instance.placed()[index]
        return PhysicsCollider.bodyFor(
            physics,
            hitboxOf(instance, index),
            matrix,
            tracked.orientation ?: Quat.fromBasis(matrix),
            tracked.velocity,
            tracked.angularVelocity
        )
    }

    /**
     * Pushes the body at [index] with [impulse] (along the world's axes), at
     * [point] (relative to the anchor, [Instance.fromAnchor]) or through its
     * centre. False without physics.
     */
    fun impulse(instance: Instance, index: Int, impulse: Vec3, point: Vec3?): Boolean {
        val body = bodyOf(instance, index) ?: return false
        val tracked = instance.body(index) ?: return false
        body.applyImpulse(impulse, point?.let { it - body.center } ?: Vec3.ZERO)
        tracked.velocity = body.velocity
        tracked.angularVelocity = body.angularVelocity
        tracked.wake()
        return true
    }

    companion object {
        /** Spin as scripts see it: degrees per second. */
        fun toDegrees(radians: Vec3) = Vec3(radiansToDegrees(radians.x), radiansToDegrees(radians.y), radiansToDegrees(radians.z))

        fun toRadians(degrees: Vec3) = Vec3(degreesToRadians(degrees.x), degreesToRadians(degrees.y), degreesToRadians(degrees.z))

        private val SIGNS = doubleArrayOf(-1.0, 1.0)

        private const val STILL = 1e-5
        private const val STILL_TURN = 1e-6
        private const val WAKE_MARGIN = 0.5

        /** How far a body may travel from its anchor before the anchor follows (see [reanchor]): a chunk. */
        const val REANCHOR = 16.0

        /** How far apart two anchors can be for one's hitboxes to stop the other's bodies, in blocks. */
        const val SEARCH_RADIUS = 48.0

        /** Blocks per axis one body's world query may cover. */
        private const val MAX_SPAN = 32.0
    }
}
