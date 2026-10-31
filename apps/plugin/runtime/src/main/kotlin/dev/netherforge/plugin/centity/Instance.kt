package dev.netherforge.plugin.centity

import dev.netherforge.format.centity.Billboard
import dev.netherforge.format.centity.Channel
import dev.netherforge.format.centity.CompiledCentity
import dev.netherforge.format.centity.DisplayDef
import dev.netherforge.format.centity.Easing
import dev.netherforge.format.centity.TextDisplay
import dev.netherforge.format.centity.Transform
import dev.netherforge.format.math.Matrix4
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.math.degreesToRadians
import dev.netherforge.plugin.physics.BodyState
import dev.netherforge.plugin.physics.Quat
import dev.netherforge.plugin.platform.DisplayLook
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.script.Scope
import java.util.UUID
import kotlin.math.floor

/**
 * One spawned centity: which definition it runs, where it stands, the
 * entities it owns, and everything a script or an animation can change about
 * it.
 *
 * Per-node state is held in arrays aligned with [definition]'s node order
 * (parents first), and rebuilt by [redefine] when the definition is reloaded.
 * Entities are held by node *name*, because names are what survive a reload:
 * a node that's still there keeps its entities, and the reconcile pass works
 * out what's missing or stale.
 *
 * Local transforms are the source of truth, not matrices, so a script that
 * writes a rotation reads the same numbers back.
 */
class Instance(val id: UUID, declared: CompiledCentity, anchor: Location, yaw: Double = 0.0) {
    /** What the centity's file says: its nodes, clips and script. Only [redefine] changes it. */
    var declared: CompiledCentity = declared
        private set

    /**
     * What runs: [declared] with the nodes scripts added after its own, so a
     * declared node keeps its index (clips are written against those) and an
     * added one's parent always comes first. A reload goes back to [declared].
     */
    var definition: CompiledCentity = declared
        private set

    /**
     * Where every display stands: they ride the [anchorEntity] there, and
     * their offsets live in their transforms. Its own facing is always zero:
     * the centity's facing is [yaw], which turns the transforms instead, so
     * the entities themselves never turn.
     */
    var anchor: Location = anchor
        set(value) {
            field = value
            moved = true
            grid?.markDirty(this)
        }

    /**
     * Which way the centity faces, in degrees, Minecraft's convention (0
     * south, 90 west), wrapped into [-180, 180). It turns the centity's space
     * about the vertical through the anchor (see [space]); [turnTo] sets it.
     */
    var yaw: Double = wrapDegrees(yaw)
        private set

    val centity: String get() = definition.id

    /** Node name → display entity / interaction entity. */
    val displays = LinkedHashMap<String, UUID>()
    val hitboxes = LinkedHashMap<String, UUID>()

    /**
     * The invisible entity at the anchor that every display rides, and every
     * hitbox sitting at the anchor: moving the centity is one teleport of it.
     */
    var anchorEntity: UUID? = null

    /** The entities known to ride [anchorEntity] now (runtime state: a sync with [forceSync] finds out again). */
    internal val riders = HashSet<UUID>()

    /** The player this centity follows, if any (runtime state, see [Centities.follow]). */
    internal var follower: UUID? = null

    /** Whether [anchorEntity] rides [follower] now. While it does, the anchor isn't teleported: that would get it off. */
    internal var followMounted = false

    /** Entities to remove that were unreachable (unloaded chunk) when they became stale. */
    val orphans = LinkedHashSet<UUID>()

    /** The centity's script's scope on this instance, while it runs. */
    var scope: Scope? = null

    val animations = AnimationPlayer()

    /**
     * It appeared by itself, from its `spawning` rules, and is temporary: not
     * saved, its entities not persistent, removed when no player is near,
     * counted against its cap. `keep()` ends it ([Centities.keep]).
     */
    var natural = false
        internal set

    /**
     * A script spawned it temporary (`nf.centities.spawn`'s `temporary` or `lifetime`): not saved, its entities not
     * persistent, removed when [owner] (the scope that spawned it) is released or at [expires]. `keep()` ends it.
     */
    var temporary = false
        internal set

    /** The scope a [temporary] instance goes with, by id: whoever spawned it, or set its lifetime. */
    internal var owner: Int? = null

    /** The server tick a [temporary] instance with a lifetime is removed at, or null. */
    internal var expires: Long? = null

    /** Never saved, its entities never persistent: [natural] or [temporary]. */
    val transient: Boolean get() = natural || temporary

    /** Ticks it has been ticked since it was attached. */
    var age = 0L

    /** Whether its chunk was loaded when last looked at, for `chunk_load` and `chunk_unload`; null until its script starts. */
    var chunkLoaded: Boolean? = null

    var removed = false

    /** Its `remove` handlers are running. */
    var removing = false

    /** How far away its displays are drawn, in blocks (`set_view_range`). Kept across reloads. */
    var viewRange: Double = DEFAULT_VIEW_RANGE
        set(value) {
            field = value
            poseDirty = true
        }

    /**
     * Players it's hidden from (`hide_from`), online or not. The server
     * forgets a hidden entity when the player leaves or the entity unloads, so
     * this is the record [Centities] says it again from. Kept across reloads,
     * not across restarts.
     */
    val hiddenFrom = LinkedHashSet<UUID>()

    /** Where `move_to` is walking it, if anywhere: see [CentityPaths]. Runtime state, dropped by [redefine]. */
    internal var walk: Walk? = null

    /**
     * The doors its walks opened (`open_doors`), each block of them, to shut once it's through: its own rather than
     * a walk's, so a walk stopped or replaced on the way still shuts them behind it. Kept across reloads.
     */
    internal val openedDoors = ArrayList<Doorway>(0)

    /** The entities no longer match the definition (a reload happened while unloaded). */
    var needsReconcile = true

    /** Everything must be pushed again: entities just came back from disk, or were just respawned. */
    var forceSync = true

    /** The anchor moved; every entity follows. */
    var moved = false

    private var rest: Array<Transform> = emptyArray()
    private var pose: Array<Transform> = emptyArray()
    private var hidden: BooleanArray = BooleanArray(0)
    private var content: Array<DisplayDef?> = emptyArray()
    private var contentChanged: BooleanArray = BooleanArray(0)
    private var bodies: Array<BodyState?> = emptyArray()
    private var looks: Array<DisplayLook> = emptyArray()
    private var unclickable: BooleanArray = BooleanArray(0)
    private var interpolation: IntArray = IntArray(0)

    /**
     * Nodes whose pose something other than a clip changed since the last
     * sync (a script, physics, visibility): their displays are sent as they
     * are, since what comes next can't be known. [allEdited] is every node.
     */
    private var edited: BooleanArray = BooleanArray(0)
    private var allEdited = true

    /**
     * Nodes whose display jumps to its next pose rather than easing there: shown or hidden (else it would grow out of
     * or shrink into where it was collapsed), or moved with `instant`. Everything under one jumps with it.
     */
    private var jumps: BooleanArray = BooleanArray(0)

    /** The clips' [AnimationPlayer.version] at the last sync: a clip started, stopped or moved since means nothing planned holds. */
    private var syncedClips = -1L

    /**
     * Per node, the [age] its display's eased move lands on: until then the
     * client is on its way to a pose sync worked out ahead, and nothing is
     * sent for it while only clips move it. See [Keyframes].
     */
    internal var due: LongArray = LongArray(0)

    /** The pose changed since the last sync. */
    var poseDirty = true
        private set

    /** [placed], kept until the pose or facing changes. */
    private var placedCache: List<Matrix4>? = null

    /** Its health (`centity.json`'s `health`, or `set_max_health`'s): see [Healths]. Runtime state, never saved. */
    internal var health: Health? = null

    /** Its flight as a projectile (`launch`), while it flies: see [Flights]. Runtime state, never saved. */
    internal var flight: Flight? = null

    /** Its `body` as it runs, when it has one: see [Movers]. Runtime state: a reload starts it again from the file's. */
    var mover: Mover? = declared.body?.let(::Mover)

    /** What its hitboxes touch now (other instances' and entities' ids), while something listens to its `touch`: see [SpatialQueries.contacts]. */
    internal var touching: Set<UUID> = emptySet()

    /** Its hitboxes as [ObstacleGrid] measured them, kept until the pose, facing, visibility or a display changes. */
    internal var obstacles: ObstacleGrid.Obstacles? = null

    /** The grid it's filed in while it's in the world, and where it's filed: see [ObstacleGrid]. */
    internal var grid: ObstacleGrid? = null
    internal var order = 0L
    internal var gridStamp = 0L
    internal var gridWorld: String? = null
    internal var gridCells: LongArray? = null
    internal var gridOversized = false

    /** What each display entity was last shown: its matrix values and cull size. */
    internal val pushedPoses = HashMap<UUID, PushedPose>()
    internal val pushedHitboxes = HashMap<UUID, Hitboxes.Bounds>()
    internal val pushedLooks = HashMap<UUID, DisplayLook>()

    /**
     * Where each billboarded display was last put. One stands on its own at its
     * node rather than riding the anchor: the client turns a billboard to the
     * camera before its transformation, so the matrix can't carry its offset
     * or any rotation (see `Centities.billboardPose`).
     */
    internal val pushedSpots = HashMap<UUID, Location>()

    internal data class PushedPose(val matrix: List<Double>, val cullSize: Double)

    init {
        redefine(declared)
    }

    /** Whether node [index] was added by a script rather than declared by the file. */
    fun isAdded(index: Int): Boolean = index >= declared.nodes.size

    /**
     * Adds [node] (its parent, if any, already in [definition]) after every
     * other node, and returns its index.
     */
    fun addNode(node: CompiledCentity.Node): Int {
        reshape(definition.nodes + node)
        return definition.nodes.lastIndex
    }

    /**
     * Removes the added node [index] and every node below it; returns their
     * names. Everything else keeps its state, moved to its new index.
     */
    fun removeNode(index: Int): List<String> {
        require(isAdded(index)) { "node \"${definition.nodes[index].name}\" is declared by the file" }
        val doomed = HashSet<Int>()
        // Parents come first, so one pass finds every descendant.
        definition.nodes.forEachIndexed { at, node -> if (at == index || node.parentIndex in doomed) doomed += at }
        val renumber = IntArray(definition.nodes.size)
        var kept = 0
        for (at in definition.nodes.indices) renumber[at] = if (at in doomed) -1 else kept++
        val names = doomed.sorted().map { definition.nodes[it].name }
        reshape(
            definition.nodes.filterIndexed { at, _ -> at !in doomed }.map {
                CompiledCentity.Node(
                    it.name,
                    if (it.parentIndex <
                        0
                    ) {
                        -1
                    } else {
                        renumber[it.parentIndex]
                    },
                    it.transform,
                    it.display,
                    it.hitbox,
                    it.physics
                )
            },
            renumber
        )
        return names
    }

    /**
     * Moves onto [nodes] (declared nodes first, unchanged), carrying each
     * surviving node's state by name; [renumber] says where each old index
     * went (-1: removed), by default nowhere moved.
     */
    private fun reshape(nodes: List<CompiledCentity.Node>, renumber: IntArray? = null) {
        val old = definition
        val next = CompiledCentity(declared.id, declared.name, nodes, declared.animations, declared.script)
        val from = IntArray(nodes.size) { old.indexOf(nodes[it].name) ?: -1 }
        rest = Array(nodes.size) { if (from[it] >= 0) rest[from[it]] else nodes[it].transform }
        pose = Array(nodes.size) { if (from[it] >= 0) pose[from[it]] else nodes[it].transform }
        content = Array(nodes.size) { if (from[it] >= 0) content[from[it]] else nodes[it].display }
        bodies = Array(nodes.size) {
            if (from[it] >= 0) {
                bodies[from[it]]
            } else if (nodes[it].physics != null) {
                BodyState()
            } else {
                null
            }
        }
        looks = Array(nodes.size) { if (from[it] >= 0) looks[from[it]] else DisplayLook() }
        hidden = BooleanArray(nodes.size) { from[it] >= 0 && hidden[from[it]] }
        contentChanged = BooleanArray(nodes.size) { from[it] >= 0 && contentChanged[from[it]] }
        unclickable = BooleanArray(nodes.size) { from[it] >= 0 && unclickable[from[it]] }
        interpolation = IntArray(nodes.size) { if (from[it] >= 0) interpolation[from[it]] else DEFAULT_INTERPOLATION_TICKS }
        edited = BooleanArray(nodes.size)
        jumps = BooleanArray(nodes.size)
        due = LongArray(nodes.size)
        allEdited = true
        if (renumber != null) {
            // What a body was touching is named by other bodies' indices.
            for (body in bodies) {
                body?.touching = body.touching.mapNotNull { (other, facing) ->
                    if (other < 0) other to facing else renumber[other].takeIf { it >= 0 }?.let { it to facing }
                }.toSet()
            }
        }
        definition = next
        needsReconcile = true
        posed()
    }

    /**
     * Moves onto a new definition: per-node state starts again from what the
     * file says, clips stop, and the nodes scripts added are gone (the
     * script's body runs again and adds what it wants). Entities are kept by
     * node name and fixed up by the next reconcile.
     */
    fun redefine(next: CompiledCentity) {
        declared = next
        definition = next
        rest = Array(next.nodes.size) { next.nodes[it].transform }
        pose = rest.copyOf()
        hidden = BooleanArray(next.nodes.size)
        content = Array(next.nodes.size) { next.nodes[it].display }
        contentChanged = BooleanArray(next.nodes.size)
        // A reload starts every body again from the file's pose, at rest.
        bodies = Array(next.nodes.size) { if (next.nodes[it].physics != null) BodyState() else null }
        looks = Array(next.nodes.size) { DisplayLook() }
        unclickable = BooleanArray(next.nodes.size)
        interpolation = IntArray(next.nodes.size) { DEFAULT_INTERPOLATION_TICKS }
        edited = BooleanArray(next.nodes.size)
        jumps = BooleanArray(next.nodes.size)
        due = LongArray(next.nodes.size)
        allEdited = true
        animations.stopAll()
        // A walk was sized from the old hitboxes, and its `path_end` handlers went with the old script.
        walk = null
        mover = next.body?.let(::Mover)
        needsReconcile = true
        forceSync = true
        posed()
    }

    fun indexOf(node: String): Int? = definition.indexOf(node)

    /** The physics state of a node with `physics`: velocity, orientation, sleep. Null for any other node. */
    fun body(index: Int): BodyState? = bodies.getOrNull(index)

    val hasBodies: Boolean get() = bodies.any { it != null }

    fun nodeNames(): List<String> = definition.nodes.map { it.name }

    // ---- transforms ---------------------------------------------------------

    fun get(index: Int, channel: Channel): Vec3 = when (channel) {
        Channel.TRANSLATION -> pose[index].translationOrDefault
        Channel.ROTATION -> pose[index].rotationOrDefault
        Channel.SCALE -> pose[index].scaleOrDefault
    }

    /** A channel of the rest pose: the authored transform, or what a script last set. */
    fun rest(index: Int, channel: Channel): Vec3 = when (channel) {
        Channel.TRANSLATION -> rest[index].translationOrDefault
        Channel.ROTATION -> rest[index].rotationOrDefault
        Channel.SCALE -> rest[index].scaleOrDefault
    }

    /**
     * Sets a channel of the rest pose. An animation driving the channel still overrides it while it plays. [instant]
     * shows it there at the next sync without easing.
     */
    fun set(index: Int, channel: Channel, value: Vec3, instant: Boolean = false) {
        rest[index] = rest[index].with(channel, value)
        pose[index] = pose[index].with(channel, value)
        edited[index] = true
        if (instant) jumps[index] = true
        posed()
    }

    /** Whether node [index]'s display jumps at the next sync: it or a node above it was shown, hidden or moved `instant`. */
    fun jumps(index: Int): Boolean {
        var at = index
        while (at >= 0) {
            if (jumps[at]) return true
            at = definition.nodes[at].parentIndex
        }
        return false
    }

    /** A channel of a node's rest pose easing from [from] to [to] over [ticks] (and back, with [back]). */
    class Tween(val index: Int, val channel: Channel, val from: Vec3, val to: Vec3, val ticks: Int, val easing: Easing, val back: Boolean) {
        var tick = 0
        val done: Boolean get() = tick >= if (back) ticks * 2 else ticks

        /** Where it is at [tick]: out, then (with [back]) home again. */
        fun value(): Vec3 {
            val out = if (tick <= ticks) tick.toDouble() / ticks else 2.0 - tick.toDouble() / ticks
            val t = easing.apply(out.coerceIn(0.0, 1.0))
            return Vec3(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t, from.z + (to.z - from.z) * t)
        }
    }

    /** Its rope nodes strung between two ends, by node name: laid again each tick. */
    val ropes = HashMap<String, StrungRope>(0)

    /** The tweens easing its nodes, at most one per node and channel. */
    val tweens = ArrayList<Tween>(0)

    /** Starts [tween], replacing any of the same node and channel. */
    fun tween(tween: Tween) {
        tweens.removeAll { it.index == tween.index && it.channel == tween.channel }
        tweens += tween
    }

    /** Moves every tween on a tick, setting its channel's rest pose, and drops those that are done. */
    fun stepTweens() {
        val iterator = tweens.iterator()
        while (iterator.hasNext()) {
            val tween = iterator.next()
            tween.tick++
            set(tween.index, tween.channel, tween.value())
            if (tween.done) iterator.remove()
        }
    }

    /** The pose or facing changed: sync it, and measure it again. */
    private fun posed() {
        poseDirty = true
        placedCache = null
        reshaped()
    }

    /** Its hitboxes may have changed: measure and file them again. */
    private fun reshaped() {
        obstacles = null
        grid?.markDirty(this)
    }

    private fun Transform.with(channel: Channel, value: Vec3) = when (channel) {
        Channel.TRANSLATION -> copy(translation = value)
        Channel.ROTATION -> copy(rotation = value)
        Channel.SCALE -> copy(scale = value)
    }

    /** The pose as shown now (rest plus clips), for a clip blending in from it. */
    fun shownPose(): Array<Transform> = pose.copyOf()

    /** Recomputes the pose from rest plus the playing clips. */
    fun animate() {
        val next = rest.copyOf()
        animations.apply(next)
        if (!next.contentEquals(pose)) {
            pose = next
            posed()
        }
    }

    /** Each node's matrix into the centity's space, in node order. */
    fun compose(): List<Matrix4> = compose(pose)

    private fun compose(pose: Array<Transform>): List<Matrix4> {
        val world = ArrayList<Matrix4>(pose.size)
        definition.nodes.forEachIndexed { index, node ->
            val local = Matrix4.fromTrs(pose[index].translationOrDefault, pose[index].rotationOrDefault, pose[index].scaleOrDefault)
            world += if (node.isRoot) local else world[node.parentIndex].copy().mul(local)
        }
        return world
    }

    /**
     * [placed] as it will be [ticks] ticks from now if only the clips move
     * it: rest plus the clips advanced that far, nothing else changing.
     */
    fun placedAhead(ticks: Int): List<Matrix4> {
        val ahead = rest.copyOf()
        animations.applyAhead(ahead, ticks * Centities.SECONDS_PER_TICK)
        val composed = compose(ahead)
        if (yaw == 0.0) return composed
        val turn = turn()
        return composed.map { Matrix4().set(turn).mul(it) }
    }

    /**
     * Whether node [index]'s shown matrix changed since the last sync in a way
     * the clips alone don't account for: it, or a node above it, was set by
     * a script or physics, hidden or shown, or eased differently; or the
     * centity turned, or a clip was started, stopped or moved.
     */
    fun edited(index: Int): Boolean {
        if (allEdited || animations.version != syncedClips) return true
        var at = index
        while (at >= 0) {
            if (edited[at]) return true
            at = definition.nodes[at].parentIndex
        }
        return false
    }

    // ---- the centity's space --------------------------------------------------

    /**
     * Turns the centity to face [degrees]. Every node turns with it about the
     * anchor; a physics body keeps moving and spinning the way it was, turned
     * by as much, so a turn never jolts it.
     */
    fun turnTo(degrees: Double) {
        val next = wrapDegrees(degrees)
        if (next == yaw) return
        val by = Quat.aboutY(-degreesToRadians(next - yaw))
        for (body in bodies) {
            if (body == null) continue
            body.velocity = by.rotate(body.velocity)
            body.angularVelocity = by.rotate(body.angularVelocity)
            body.orientation = body.orientation?.let { (by * it).normalized() }
        }
        yaw = next
        allEdited = true
        posed()
    }

    /**
     * The centity's facing as a rotation, from the centity's axes to the
     * world's: its +Z (the way a centity faces) comes out along
     * `vec3.from_yaw_pitch(yaw, 0)`. Quarter turns are exact, so a centity
     * turned to face a compass direction reports whole numbers.
     */
    fun turn(): Matrix4 {
        val matrix = Matrix4()
        if (yaw == 0.0) return matrix
        val quarter = yaw / 90.0
        if (quarter != floor(quarter)) return matrix.rotateY(-degreesToRadians(yaw))
        // rotateY(-yaw) with its sine and cosine taken exactly.
        val (sin, cos) = when (quarter.toInt().mod(4)) {
            1 -> -1.0 to 0.0
            2 -> 0.0 to -1.0
            3 -> 1.0 to 0.0
            else -> 0.0 to 1.0
        }
        val v = matrix.values
        v[0] = cos
        v[2] = -sin
        v[8] = sin
        v[10] = cos
        return matrix
    }

    /**
     * The centity's own space as a matrix into the world: origin at the
     * anchor, turned by [yaw]. Root transforms are in this space, so every
     * conversion between a node and the world goes through here.
     */
    fun space(): Matrix4 = Matrix4().translate(anchor.x, anchor.y, anchor.z).mul(turn())

    /**
     * Each node's matrix relative to the anchor along the world's axes: [compose]
     * turned by [yaw]. What the entities are shown (they stand at the anchor,
     * unturned), what hitboxes are measured with, and the frame physics
     * simulates in, so world blocks, velocities and impulses need no turning.
     * Kept until the pose changes and shared by every caller: never mutate one.
     */
    fun placed(): List<Matrix4> = placedCache ?: run {
        val composed = compose()
        val turn = turn()
        (if (yaw == 0.0) composed else composed.map { Matrix4().set(turn).mul(it) }).also { placedCache = it }
    }

    /** A position in the centity's space, in the world. */
    fun toWorld(point: Vec3): Vec3 = space().transformPosition(point)

    /** A world position in the centity's space. */
    fun toCentity(point: Vec3): Vec3 = space().invertAffine()!!.transformPosition(point)

    /** A world position relative to the anchor, along the world's axes: in the frame of [placed]. */
    fun fromAnchor(point: Vec3): Vec3 = Vec3(point.x - anchor.x, point.y - anchor.y, point.z - anchor.z)

    /** A position in the frame of [placed], in the world. */
    fun atAnchor(offset: Vec3): Vec3 = Vec3(anchor.x + offset.x, anchor.y + offset.y, anchor.z + offset.z)

    /** Node [index]'s matrix into the world, as shown now: [compose]'s, placed by [space]. */
    fun worldMatrix(index: Int): Matrix4 = space().mul(compose()[index])

    /** The matrix into the world of the space node [index]'s translation is in: its parent's, or the centity's for a root. */
    fun parentWorldMatrix(index: Int): Matrix4 {
        val parent = definition.nodes[index].parentIndex
        return if (parent < 0) space() else worldMatrix(parent)
    }

    fun markSynced() {
        poseDirty = false
        forceSync = false
        moved = false
        contentChanged.fill(false)
        edited.fill(false)
        jumps.fill(false)
        allEdited = false
        syncedClips = animations.version
    }

    // ---- visibility and content ---------------------------------------------

    fun visible(index: Int): Boolean = !hidden[index]

    fun setVisible(index: Int, value: Boolean) {
        if (hidden[index] == !value) return
        hidden[index] = !value
        edited[index] = true
        jumps[index] = true
        poseDirty = true
        reshaped()
    }

    /** Hidden when it or any ancestor is. */
    fun shown(index: Int): Boolean {
        var at = index
        while (at >= 0) {
            if (hidden[at]) return false
            at = definition.nodes[at].parentIndex
        }
        return true
    }

    fun display(index: Int): DisplayDef? = content[index]

    fun setDisplay(index: Int, display: DisplayDef) {
        content[index] = display
        contentChanged[index] = true
        // Its fit may change with it (a rect's size, an anchor), so its pose is sent now too.
        edited[index] = true
        poseDirty = true
        reshaped()
    }

    fun contentChanged(index: Int): Boolean = contentChanged[index]

    /** Node [index]'s new content wasn't sent this sync (past a budget): send it at the next. */
    fun deferContent(index: Int) {
        contentChanged[index] = true
        poseDirty = true
    }

    /** How node [index]'s display turns to the camera: a script's look, else a text display's own, else fixed. */
    fun billboard(index: Int): Billboard {
        val own = (content[index] as? TextDisplay)?.let { it.billboard ?: TextDisplay.DEFAULT_BILLBOARD } ?: Billboard.FIXED
        return looks[index].billboard ?: own
    }

    /** Whether node [index]'s display turns to the camera at all. */
    fun billboarded(index: Int): Boolean = content[index] != null && billboard(index) != Billboard.FIXED

    /** What a script changed about how node [index]'s display is drawn. */
    fun look(index: Int): DisplayLook = looks[index]

    fun setLook(index: Int, look: DisplayLook) {
        looks[index] = look
        poseDirty = true
    }

    /**
     * What node [index]'s display is drawn with: its own look, plus the
     * centity's view range and its easing as the teleport duration.
     */
    fun shownLook(index: Int): DisplayLook = looks[index].copy(
        viewRange = viewRange / DEFAULT_VIEW_RANGE,
        teleportTicks = interpolation[index].coerceAtMost(MAX_TELEPORT_TICKS)
    )

    /** Whether node [index]'s hitbox takes clicks (`set_clickable`). */
    fun clickable(index: Int): Boolean = !unclickable[index]

    fun setClickable(index: Int, value: Boolean) {
        if (unclickable[index] == !value) return
        unclickable[index] = !value
        poseDirty = true
    }

    /** Ticks node [index]'s display eases to a new pose over. */
    fun interpolation(index: Int): Int = interpolation[index]

    fun setInterpolation(index: Int, ticks: Int) {
        interpolation[index] = ticks
        edited[index] = true
        poseDirty = true
    }

    /**
     * Ticks a move of the whole centity is eased over (the anchor entity's
     * teleport, which everything riding it follows): its roots' easing, the
     * least of them, at most what Minecraft eases a teleport over.
     */
    fun moveTicks(): Int {
        var ticks = MAX_TELEPORT_TICKS
        definition.nodes.forEachIndexed { index, node -> if (node.isRoot) ticks = minOf(ticks, interpolation[index]) }
        return ticks.coerceAtLeast(0)
    }

    /** Every entity this instance owns or still has to clean up. */
    fun entityIds(): List<UUID> = listOfNotNull(anchorEntity) + displays.values + hitboxes.values + orphans

    /** The entities a player sees of it: its anchor, displays and hitboxes. */
    fun shownEntities(): List<UUID> = listOfNotNull(anchorEntity) + displays.values + hitboxes.values

    companion object {
        /** Minecraft's default view range for a display, in blocks: a multiplier of 1. */
        const val DEFAULT_VIEW_RANGE = 64.0

        /** Displays ease to each new pose over this many ticks unless a script says otherwise, which smooths 20 Hz updates. */
        const val DEFAULT_INTERPOLATION_TICKS = 1

        /** The longest teleport Minecraft eases a display over. */
        const val MAX_TELEPORT_TICKS = 59

        /** [degrees] wrapped into [-180, 180), as Minecraft wraps a facing; anything not finite is 0. */
        fun wrapDegrees(degrees: Double): Double {
            if (!degrees.isFinite()) return 0.0
            return (degrees + 180.0).mod(360.0) - 180.0 + 0.0
        }
    }
}
