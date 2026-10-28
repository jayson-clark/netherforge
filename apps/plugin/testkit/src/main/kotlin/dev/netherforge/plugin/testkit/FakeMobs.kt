package dev.netherforge.plugin.testkit

import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.game.has
import dev.netherforge.format.item.AttributeOperation
import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.platform.AttributeModifierData
import dev.netherforge.plugin.platform.AttributeOps
import dev.netherforge.plugin.platform.GoalCallbacks
import dev.netherforge.plugin.platform.GoalControl
import dev.netherforge.plugin.platform.GoalInfo
import dev.netherforge.plugin.platform.MobGoalOps
import dev.netherforge.plugin.platform.PathfindingOps
import java.util.UUID
import kotlin.math.floor

/**
 * The fake server's attributes: every living entity has each attribute in
 * [FakePlatform.GAME] but `minecraft:attack_damage` on a pig (as in the game,
 * where passive mobs have none); anything else has none. Values are worked out
 * the game's way, without its ranges.
 */
class FakeAttributes(private val platform: FakePlatform) : AttributeOps {
    class Instance(var base: Double) {
        val modifiers = LinkedHashMap<String, AttributeModifierData>()
    }

    /** Each entity's attributes, made with their defaults when first asked for. */
    val byEntity = HashMap<UUID, MutableMap<String, Instance>>()

    private fun has(body: FakeBody, attribute: String): Boolean {
        if (!body.living || platform.game.has(RegistryKey.ATTRIBUTE, attribute) != true) return false
        return !(attribute == "minecraft:attack_damage" && body.kind == "minecraft:pig")
    }

    fun instance(id: UUID, attribute: String): Instance? {
        val body = platform.worldEntities.body(id)?.takeIf { has(it, attribute) } ?: return null
        return byEntity.getOrPut(id) { HashMap() }.getOrPut(attribute) { Instance(default(body, attribute)) }
    }

    private fun default(body: FakeBody, attribute: String): Double = when (attribute) {
        "minecraft:movement_speed" -> if (body is FakePlayer) 0.1 else 0.25
        "minecraft:attack_damage" -> if (body is FakePlayer) 1.0 else 3.0
        "minecraft:scale", "minecraft:block_break_speed" -> 1.0
        else -> 0.0
    }

    override fun value(id: UUID, attribute: String): Double? {
        val instance = instance(id, attribute) ?: return null
        val modifiers = instance.modifiers.values
        fun sum(operation: AttributeOperation) = modifiers.filter { it.operation == operation }.sumOf { it.amount }
        val added = instance.base + sum(AttributeOperation.ADD_VALUE)
        var total = added + added * sum(AttributeOperation.ADD_MULTIPLIED_BASE)
        for (modifier in modifiers) if (modifier.operation == AttributeOperation.ADD_MULTIPLIED_TOTAL) total *= 1 + modifier.amount
        return total
    }

    override fun base(id: UUID, attribute: String) = instance(id, attribute)?.base

    override fun setBase(id: UUID, attribute: String, value: Double): Boolean {
        val instance = instance(id, attribute) ?: return false
        instance.base = value
        return true
    }

    override fun modifiers(id: UUID, attribute: String) = instance(id, attribute)?.modifiers?.values?.toList()

    override fun addModifier(id: UUID, attribute: String, modifier: AttributeModifierData): Boolean {
        val instance = instance(id, attribute) ?: return false
        instance.modifiers[modifier.id] = modifier
        return true
    }

    override fun removeModifier(id: UUID, attribute: String, modifier: String): Boolean =
        instance(id, attribute)?.modifiers?.remove(modifier) != null
}

/**
 * The fake server's mob navigation: every place is reachable by a mob (an
 * entity with AI) unless [blocked] says not, and nothing moves
 * until a test says: [arrive] puts a mob at its path's end, [stall] ends its
 * path where it is, [divert] sends it elsewhere as its AI would.
 */
class FakePathfinding(private val platform: FakePlatform) : PathfindingOps {
    /** Each walking mob's path end and speed. */
    val paths = LinkedHashMap<UUID, Pair<Vec3, Double>>()

    /** Places no path reaches. */
    var blocked: (Vec3) -> Boolean = { false }

    private fun mob(id: UUID): FakeBody? = platform.worldEntities.body(id)?.takeIf { it.mob }

    override fun moveTo(id: UUID, to: Vec3, speed: Double): Boolean {
        if (mob(id) == null || blocked(to)) return false
        paths[id] = to to speed
        return true
    }

    override fun stop(id: UUID): Boolean {
        if (mob(id) == null) return false
        paths.remove(id)
        return true
    }

    override fun hasPath(id: UUID) = mob(id) != null && id in paths

    /** As the game's path ends: at the block the mob was sent to (its corner), not the point. */
    override fun pathEnd(id: UUID) = if (mob(id) == null) {
        null
    } else {
        paths[id]?.first?.let { Vec3(floor(it.x), floor(it.y), floor(it.z)) }
    }

    /** The mob walks to the end of its path, and stops there. */
    fun arrive(id: UUID) {
        val (end, _) = paths.remove(id) ?: error("$id isn't walking")
        val body = mob(id)!!
        body.location = body.location.copy(x = end.x, y = end.y, z = end.z)
    }

    /** The mob stops where it is, short of its path's end. */
    fun stall(id: UUID) {
        paths.remove(id)
    }

    /** Its AI sends it somewhere else. */
    fun divert(id: UUID, to: Vec3) {
        paths[id] = to to 1.0
    }
}

/**
 * The fake server's mob AI: each mob (an entity with AI) has the game's goals for its kind once anything asks, in two selectors as
 * the game keeps them (targeting goals, and the rest), and [think] runs them
 * the way the game's goal selector does: running goals that shouldn't
 * continue stop, then each idle goal whose controls are free or held by a
 * less important goal (a bigger priority) may start, stopping those, then
 * every running goal ticks. A goal claiming nothing claims an "unknown"
 * control, as on Paper. The game's own goals here never start.
 * Whoever drives the fake server calls [think] after the runtime's tick, as
 * the server ticks entities after plugins.
 *
 * Like the real server, changing a mob's goals while it thinks is an error
 * (the game's loop would break): the runtime must wait.
 */
class FakeMobGoals(private val platform: FakePlatform) : MobGoalOps {
    class Goal(val key: String, val priority: Int, val controls: Set<GoalControl>, val callbacks: GoalCallbacks?) {
        var running = false

        /** What it locks: its controls, or the "unknown" one when it claims none. */
        val locks: Set<String> get() = if (controls.isEmpty()) setOf("unknown") else controls.map { it.name }.toSet()

        val targeting get() = GoalControl.TARGET in controls
    }

    /** Each mob's goals: index 0 its regular goals, 1 its targeting goals. */
    val byMob = LinkedHashMap<UUID, List<MutableList<Goal>>>()

    /** The game's goals for each kind: key, priority, controls. */
    private val vanilla = mapOf(
        "minecraft:zombie" to listOf(
            Triple("minecraft:float", 0, setOf(GoalControl.JUMP)),
            Triple("minecraft:melee_attack", 2, setOf(GoalControl.MOVE, GoalControl.LOOK)),
            Triple("minecraft:random_stroll", 7, setOf(GoalControl.MOVE)),
            Triple("minecraft:look_at_player", 8, setOf(GoalControl.LOOK)),
            Triple("minecraft:hurt_by_target", 1, setOf(GoalControl.TARGET)),
            Triple("minecraft:nearest_attackable_target", 2, setOf(GoalControl.TARGET))
        ),
        "minecraft:pig" to listOf(
            Triple("minecraft:float", 0, setOf(GoalControl.JUMP)),
            Triple("minecraft:panic", 1, setOf(GoalControl.MOVE)),
            Triple("minecraft:random_stroll", 6, setOf(GoalControl.MOVE)),
            Triple("minecraft:look_at_player", 7, setOf(GoalControl.LOOK))
        )
    )

    private var thinking = false

    private fun mob(id: UUID): FakeBody? = platform.worldEntities.body(id)?.takeIf { it.mob }

    private fun selectors(id: UUID): List<MutableList<Goal>>? {
        val body = mob(id) ?: return null
        return byMob.getOrPut(id) {
            val goals = vanilla[body.kind].orEmpty().map { (key, priority, controls) -> Goal(key, priority, controls, null) }
            listOf(goals.filterNot { it.targeting }.toMutableList(), goals.filter { it.targeting }.toMutableList())
        }
    }

    private fun changing() = check(!thinking) { "a mob's goals changed while its AI was going through them" }

    /** The goal with [key] on [mob], for a test to look at. */
    fun goal(mob: UUID, key: String): Goal? = byMob[mob]?.flatten()?.firstOrNull { it.key == key }

    override fun goals(mob: UUID): List<GoalInfo>? = selectors(mob)?.flatten()?.map {
        GoalInfo(it.key, it.priority, it.controls, it.running)
    }

    private fun removeWhere(mob: UUID, which: (Goal) -> Boolean): List<String>? {
        val selectors = selectors(mob) ?: return null
        changing()
        val removed = mutableListOf<String>()
        for (selector in selectors) {
            for (goal in selector.filter(which)) {
                if (goal.running) {
                    goal.running = false
                    goal.callbacks?.stop()
                }
                selector.remove(goal)
                removed += goal.key
            }
        }
        return removed
    }

    override fun remove(mob: UUID, key: String) = removeWhere(mob) { it.key == key }

    override fun remove(mob: UUID, goal: GoalCallbacks): Boolean = removeWhere(mob) { it.callbacks === goal }?.isNotEmpty() == true

    override fun clear(mob: UUID, keep: Set<String>) = removeWhere(mob) { it.key !in keep }

    override fun add(mob: UUID, key: String, priority: Int, controls: Set<GoalControl>, goal: GoalCallbacks): Boolean {
        val selectors = selectors(mob) ?: return false
        changing()
        remove(mob, key)
        val added = Goal(key, priority, controls, goal)
        selectors[if (added.targeting) 1 else 0] += added
        return true
    }

    /** Every mob's AI thinks once, as the server ticks it. */
    fun think() {
        for (id in byMob.keys.toList()) if (mob(id) != null) think(id)
    }

    fun think(mob: UUID) {
        val selectors = selectors(mob) ?: return
        thinking = true
        try {
            for (selector in selectors) think(selector)
        } finally {
            thinking = false
        }
    }

    private fun think(selector: List<Goal>) {
        for (goal in selector) {
            if (goal.running && goal.callbacks?.shouldContinue() != true) {
                goal.running = false
                goal.callbacks?.stop()
            }
        }
        val locked = HashMap<String, Goal>()
        for (goal in selector) if (goal.running) goal.locks.forEach { locked[it] = goal }
        for (goal in selector) {
            val callbacks = goal.callbacks ?: continue
            if (goal.running) continue
            if (goal.locks.any { lock -> locked[lock]?.let { it.priority <= goal.priority } == true }) continue
            if (!callbacks.shouldStart()) continue
            for (lock in goal.locks) {
                locked[lock]?.takeIf { it.running }?.let {
                    it.running = false
                    it.callbacks?.stop()
                }
                locked[lock] = goal
            }
            goal.running = true
            callbacks.start()
        }
        for (goal in selector) if (goal.running) goal.callbacks?.tick()
    }
}
