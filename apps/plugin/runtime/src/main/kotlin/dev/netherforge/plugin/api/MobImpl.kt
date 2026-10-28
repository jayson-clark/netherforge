package dev.netherforge.plugin.api

import dev.netherforge.format.game.GameIds
import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.binding.GoalClearOptions
import dev.netherforge.plugin.binding.LocationOrVec3OrEntity
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.LuaPlace
import dev.netherforge.plugin.binding.MobApi
import dev.netherforge.plugin.binding.MobGoal
import dev.netherforge.plugin.binding.PathOptions
import dev.netherforge.plugin.binding.entityHandle
import dev.netherforge.plugin.binding.place
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.mob.MobGoals
import dev.netherforge.plugin.platform.EntityFlag
import dev.netherforge.plugin.platform.GoalControl
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.session.ProjectSession

/**
 * `Mob`: a mob's target, AI, walking (`move_to`) and goals. Everything
 * answers nil or false while it isn't in the world.
 */
internal class MobImpl(private val session: ProjectSession) : MobApi {
    private val entities get() = session.platform.worldEntities

    private fun flag(self: LuaHandle.Entity, flag: EntityFlag): Boolean = session.entityFlag(self, flag)

    private fun setFlag(self: LuaHandle.Entity, flag: EntityFlag, value: Boolean): Boolean = session.setEntityFlag(self, flag, value)

    override fun moveTo(self: LuaHandle.Mob, target: LocationOrVec3OrEntity, options: PathOptions?): Boolean {
        val speed = options?.speed ?: 1.0
        if (speed <= 0 || !speed.isFinite()) throw LuaApiException("options.speed must be more than 0, not $speed")
        val id = self.uuidOrNull() ?: return false
        val here = entities.info(id)?.location ?: return false
        val goal = when (target) {
            is LocationOrVec3OrEntity.Location -> LuaPlace.of(target.value)
            is LocationOrVec3OrEntity.Vec3 -> LuaPlace(null, target.value, null, null)
            is LocationOrVec3OrEntity.Entity -> {
                val other = target.value.uuidOrNull()?.takeIf { it != id } ?: return false
                if (entities.info(other)?.location?.world != here.world) return false
                return session.mobPaths.follow(id, other, speed)
            }
        }
        val place = goal.resolve(here.world)
        if (place.world != here.world) return false
        return session.mobPaths.moveTo(id, Vec3(place.x, place.y, place.z), speed)
    }

    override fun stopPathing(self: LuaHandle.Mob): Boolean = self.uuidOrNull()?.let { session.mobPaths.stop(it) } == true

    override fun hasPath(self: LuaHandle.Mob): Boolean = self.uuidOrNull()?.let { session.platform.pathfinding.hasPath(it) } == true

    override fun pathTarget(self: LuaHandle.Mob): Vec3? = self.uuidOrNull()?.let { session.platform.pathfinding.pathEnd(it) }

    override fun goals(self: LuaHandle.Mob): List<MobGoal>? {
        val goals = self.uuidOrNull()?.let(session.mobGoals::goals) ?: return null
        return goals.sortedWith(compareBy({ it.key }, { it.priority })).map { goal ->
            MobGoal(
                goal.key,
                goal.priority.toLong(),
                GoalControl.entries.filter { it in goal.controls }.map { it.luaName },
                goal.running
            )
        }
    }

    override fun removeGoal(self: LuaHandle.Mob, key: String): Boolean {
        val goal = goalKey(key, "key")
        return self.uuidOrNull()?.let { session.mobGoals.remove(it, goal) } == true
    }

    override fun clearGoals(self: LuaHandle.Mob, options: GoalClearOptions?): Boolean {
        val keep = options?.keep.orEmpty().mapIndexed { index, key -> goalKey(key, "options.keep[${index + 1}]") }.toSet()
        return self.uuidOrNull()?.let { session.mobGoals.clear(it, keep) } == true
    }

    /**
     * `mob:add_goal`, from the prelude's hand-written half, which checked the
     * definition's shape and kept its functions as [callbacks]: what's left to
     * check is the id, the priority and the controls. A mistake lets go of the
     * functions before it's raised.
     */
    fun addGoal(
        scope: Scope,
        self: LuaHandle.Mob,
        id: String,
        priority: Long,
        controls: List<String>,
        callbacks: MobGoals.Callbacks
    ): Boolean {
        val checked = runCatching {
            val key = goalId(id)
            if (priority !in Int.MIN_VALUE..Int.MAX_VALUE) throw LuaApiException("definition.priority is too big: $priority")
            val claimed = controls.mapIndexed { index, name ->
                GoalControl.of(name) ?: throw LuaApiException(
                    "definition.controls[${index + 1}] must be \"move\", \"look\", \"jump\" or \"target\", not \"$name\""
                )
            }.toSet()
            if (claimed.size != controls.size) throw LuaApiException("definition.controls names a control twice")
            if (GoalControl.TARGET in claimed && claimed.size > 1) {
                throw LuaApiException("a goal that claims \"target\" is a targeting goal, so it can't claim anything else")
            }
            Triple(key, priority.toInt(), claimed)
        }
        val (key, order, claimed) = checked.getOrElse { error ->
            callbacks.all().forEach(session.scripts::unref)
            throw error
        }
        val mob = self.uuidOrNull()
        if (mob == null) {
            callbacks.all().forEach(session.scripts::unref)
            return false
        }
        return session.mobGoals.add(scope, mob, key, order, claimed, callbacks)
    }

    /** A goal key a script gave to find goals by: a key without a namespace is the game's. */
    private fun goalKey(key: String, name: String): String {
        if (!GameIds.isValid(
                key
            )
        ) {
            throw LuaApiException("$name: \"$key\" isn't a goal key: lowercase letters, digits, _, -, . and /, with a namespace or not")
        }
        return GameIds.normalize(key)
    }

    /** A goal id a script gave to add a goal by, in the project's namespace, so it can't replace anything else's. */
    private fun goalId(id: String): String {
        if (!GameIds.isValid(id)) throw LuaApiException("\"$id\" isn't a goal id: lowercase letters, digits, _, -, . and /")
        return session.ownId(id, "goal")
    }

    override fun target(self: LuaHandle.Mob): LuaHandle.Entity? = self.uuidOrNull()?.let(entities::target)?.let {
        session.entityHandle(it)
    }

    override fun setTarget(self: LuaHandle.Mob, entity: LuaHandle.Entity?): Boolean {
        val id = self.uuidOrNull() ?: return false
        return entities.setTarget(id, entity?.uuidOrNull())
    }

    override fun hasAi(self: LuaHandle.Mob): Boolean = flag(self, EntityFlag.AI)

    override fun setAi(self: LuaHandle.Mob, ai: Boolean): Boolean = setFlag(self, EntityFlag.AI, ai)
}
