package dev.netherforge.plugin.centity

import dev.netherforge.format.game.Box
import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.binding.ClickEvent
import dev.netherforge.plugin.binding.EventType
import dev.netherforge.plugin.binding.Events
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.Ray
import dev.netherforge.plugin.script.Scripts
import java.util.UUID

/**
 * Clicks on centities' hitboxes, and rays against them: a click names an interaction entity, and when the player's
 * line of sight is known it goes to the nearest node that line actually hits (the narrow phase for a `raycast`
 * node's boxes, the entity's bounds otherwise), then along its path: the node's handlers, each node above it, the
 * centity's, then `nf.on("centity_click")`, until one stops it.
 */
class CentityClicks internal constructor(
    private val scripts: Scripts,
    /** The instance an entity of ours belongs to, and its node's name. */
    private val owner: (UUID) -> Pair<Instance, String>?,
    private val obstacles: ObstacleGrid,
    private val boxesOf: (Instance, Int) -> List<Box>,
    private val loaded: (Instance) -> Boolean,
    /** Keeps a natural instance (`spawning.keepOnInteract`). */
    private val keep: (Instance) -> Boolean
) {
    /**
     * Routes a click on one of our entities. True when the entity is ours,
     * whether or not any script wanted the click.
     *
     * When the player's line of sight is known, the click goes to the nearest
     * node it actually hits, not just the entity the client picked: a node
     * whose box sits in front wins over a sibling whose padding is nearer, and
     * a click through the padding of a `raycast` node reaches nothing.
     */
    fun click(entity: UUID, player: PlayerRef, button: ClickButton, sight: Ray?): Boolean {
        val (instance, name) = owner(entity) ?: return false
        // The server keeps a hidden interaction from being clicked; this says so for any platform.
        if (instance.removed || player.uuid in instance.hiddenFrom) return true
        val clicked = instance.indexOf(name) ?: return true
        val target = pick(instance, clicked, sight) ?: return true
        // `spawning.keepOnInteract`: a click that reaches a node keeps a natural one, before scripts hear it.
        if (instance.natural && instance.definition.spawning?.keepOnInteractOrDefault == true) keep(instance)
        dispatchClick(instance, target, player, button)
        return true
    }

    /**
     * Whose hitbox [entity] is, and the node a click there by [player] along
     * [sight] would reach (null when none would: an unclickable node, or a
     * line of sight through a `raycast` node's padding), as [click] picks it.
     * Null when [entity] isn't one of a centity's hitboxes, or is hidden from [player].
     */
    fun clickTarget(entity: UUID, player: UUID, sight: Ray?): Pair<Instance, String?>? {
        val (instance, name) = owner(entity) ?: return null
        if (instance.removed || player in instance.hiddenFrom) return null
        val clicked = instance.indexOf(name) ?: return instance to null
        return instance to pick(instance, clicked, sight)?.let { instance.definition.nodes[it.index].name }
    }

    /**
     * Where a player would aim to click [instance]'s node [node] (its first
     * clickable, shown node with a hitbox when null): the node's interaction
     * entity, and the middle of its box in the world. Null when that node
     * has no hitbox out in the world to click.
     */
    fun aim(instance: Instance, node: String?): Pair<UUID, Vec3>? {
        val name = node ?: instance.definition.nodes.withIndex().firstOrNull { (index, it) ->
            it.hitbox != null && instance.shown(index) && instance.clickable(index) && it.name in instance.hitboxes
        }?.value?.name ?: return null
        val entity = instance.hitboxes[name] ?: return null
        val bounds = instance.pushedHitboxes[entity] ?: return entity to instance.atAnchor(Vec3(0.0, 0.0, 0.0))
        return entity to instance.atAnchor(Vec3(bounds.centerX, bounds.bottomY + bounds.height / 2, bounds.centerZ))
    }

    /**
     * The node a click lands on, with where the line of sight met it and the
     * face's normal, in the world (null when there's no line of sight to go by).
     */
    private data class Picked(val index: Int, val position: Vec3? = null, val normal: Vec3? = null)

    private fun pick(instance: Instance, clicked: Int, sight: Ray?): Picked? {
        if (sight == null || sight.world != instance.anchor.world) return if (instance.clickable(clicked)) Picked(clicked) else null
        // Relative to the anchor along the world's axes, where the entities are shown.
        val origin = instance.fromAnchor(Vec3(sight.x, sight.y, sight.z))
        val direction = Vec3(sight.dx, sight.dy, sight.dz)
        val best = nearestHitbox(instance, origin, direction, REACH)
        if (best != null) {
            // Back into the world: the ray was relative to the anchor.
            val (index, hit) = best
            return Picked(index, instance.atAnchor(origin + direction * hit.distance), hit.normal)
        }
        // A node without the narrow phase is its interaction entity, so a ray
        // that missed it is a stale look vector, not a miss.
        val node = instance.definition.nodes[clicked]
        return if (node.hitbox?.raycastEnabled == true || !instance.clickable(clicked)) null else Picked(clicked)
    }

    /**
     * The node of [instance] whose hitbox a ray hits first within
     * [maxDistance], with the hit: [origin] relative to the anchor along the
     * world's axes, [direction] a unit vector. A `raycast` node is tested
     * against its boxes, any other against the interaction entity's bounds:
     * the shapes a click is picked by. A node that isn't clickable is skipped.
     */
    private fun nearestHitbox(instance: Instance, origin: Vec3, direction: Vec3, maxDistance: Double): Pair<Int, Hitboxes.Hit>? {
        val world = instance.placed()
        var best: Pair<Int, Hitboxes.Hit>? = null
        for ((index, node) in instance.definition.nodes.withIndex()) {
            val hitbox = node.hitbox ?: continue
            if (!instance.shown(index) || !instance.clickable(index) || index !in world.indices) continue
            val boxes = boxesOf(instance, index)
            val hit = if (hitbox.raycastEnabled) {
                Hitboxes.hit(boxes, world[index], origin, direction, maxDistance)
            } else {
                Hitboxes.hit(Hitboxes.bounds(boxes, world[index]), origin, direction, maxDistance)
            } ?: continue
            if (best == null || hit.distance < best.second.distance) best = index to hit
        }
        return best
    }

    /** What a ray hit among the centities: [instance]'s node [index], where (in the world), the face's normal and how far along. */
    data class HitboxHit(val instance: Instance, val index: Int, val position: Vec3, val normal: Vec3, val distance: Double)

    /**
     * The first centity hitbox a ray in [world] from [origin] along the unit
     * [direction] hits within [maxDistance], tested with the shapes clicks
     * are; instances whose chunk isn't loaded aren't hit.
     */
    fun raycast(world: String, origin: Vec3, direction: Vec3, maxDistance: Double): HitboxHit? {
        var best: HitboxHit? = null
        // Only the instances whose boxes reach the box around the ray: the grid files them by where they are.
        val end = origin + direction * maxDistance
        val candidates = ArrayList<Instance>()
        obstacles.near(
            world,
            minOf(origin.x, end.x),
            minOf(origin.y, end.y),
            minOf(origin.z, end.z),
            maxOf(origin.x, end.x),
            maxOf(origin.y, end.y),
            maxOf(origin.z, end.z),
            candidates
        )
        for (instance in candidates) {
            if (instance.removed || instance.anchor.world != world || !loaded(instance)) continue
            val from = instance.fromAnchor(origin)
            val (index, hit) = nearestHitbox(instance, from, direction, best?.distance ?: maxDistance) ?: continue
            if (best != null && hit.distance >= best.distance) continue
            best = HitboxHit(instance, index, instance.atAnchor(from + direction * hit.distance), hit.normal, hit.distance)
        }
        return best
    }

    /**
     * A click along its path: the clicked node, each node above it, the
     * centity, then `nf.on("centity_click")`, until a handler stops it.
     */
    private fun dispatchClick(instance: Instance, picked: Picked, player: PlayerRef, button: ClickButton) {
        val nodes = instance.definition.nodes
        val id = instance.id.toString()
        val path = mutableListOf<Pair<EventType<ClickEvent>, LuaHandle?>>()
        var index = picked.index
        while (index >= 0) {
            path += Events.NODE_CLICK to LuaHandle.Node(id, nodes[index].name)
            index = nodes[index].parentIndex
        }
        path += Events.CENTITY_CLICK to LuaHandle.Centity(id)
        path += Events.NF_CENTITY_CLICK to null
        val event = ClickEvent(
            player = LuaHandle.Player(player.uuid.toString()),
            target = LuaHandle.Node(id, nodes[picked.index].name),
            click = button.luaName,
            hitPosition = picked.position,
            hitNormal = picked.normal
        )
        scripts.emit(path, event)
    }

    companion object {
        /** How far a click may reach for the narrow phase; past any reach the server allows. */
        const val REACH = 8.0
    }
}
