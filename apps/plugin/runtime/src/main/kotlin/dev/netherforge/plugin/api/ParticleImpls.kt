package dev.netherforge.plugin.api
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.particle.ParticleEffectKind
import dev.netherforge.plugin.binding.Caller
import dev.netherforge.plugin.binding.EffectApi
import dev.netherforge.plugin.binding.LocationOrVec3
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.LuaLocation
import dev.netherforge.plugin.binding.NfParticlesApi
import dev.netherforge.plugin.binding.ParticlePlayOptions
import dev.netherforge.plugin.binding.place
import dev.netherforge.plugin.particle.ActiveEffect
import dev.netherforge.plugin.particle.FollowTarget
import dev.netherforge.plugin.particle.ParticleEffects
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.session.ProjectSession
import java.util.UUID

/** What a handle `follow` takes names: a centity, a node, a player or another entity. */
internal fun followTarget(handle: LuaHandle): FollowTarget = when (handle) {
    is LuaHandle.Centity -> FollowTarget.Centity(handle.id)
    is LuaHandle.Node -> FollowTarget.Node(handle.centity, handle.name)
    // A handle whose id isn't a UUID names nobody: following it ends the effect at once.
    is LuaHandle.Player -> FollowTarget.Player(handle.uuidOrNull() ?: UUID(0, 0))
    is LuaHandle.Entity -> FollowTarget.Entity(handle.uuidOrNull() ?: UUID(0, 0))
    else -> error("an effect can't follow a ${handle.luaClass}")
}

/** `nf.particles`. */
internal class NfParticlesImpl(private val session: ProjectSession) : NfParticlesApi {
    override fun play(
        caller: Caller,
        effect: String,
        locationOrPosition: LocationOrVec3,
        options: ParticlePlayOptions?
    ): LuaHandle.Effect? {
        val place = locationOrPosition.place
        val scope = caller.scope
        val at = place.resolve(place.world?.name ?: session.scriptWorld(scope))
        val played = session.particles.play(
            session.names.resource(ParticleEffectKind, effect),
            at,
            scope.id,
            ParticleEffects.PlayOptions(
                viewers = options?.viewers?.mapNotNull { it.uuidOrNull() }?.toSet(),
                scale = options?.scale,
                loop = options?.loop,
                follow = options?.follow?.let(::followTarget),
                offset = options?.offset
            )
        )
        return played?.handle
    }
}

/** `Effect`: a handle to a playing effect, by number; once it has ended, nil and false. */
internal class EffectImpl(private val session: ProjectSession) : EffectApi {
    private fun effect(self: LuaHandle.Effect): ActiveEffect? = session.particles.find(self.number)

    override fun kind(self: LuaHandle.Effect): String = session.names.spell(self.kind)

    override fun isActive(self: LuaHandle.Effect): Boolean = effect(self) != null

    override fun stop(self: LuaHandle.Effect): Boolean = effect(self)?.let { session.particles.stop(it) } == true

    override fun location(self: LuaHandle.Effect): LuaLocation? = effect(self)?.let {
        LuaLocation(LuaHandle.World(it.world), it.origin, it.yaw, it.pitch)
    }

    /** A bare `Vec3` keeps its world and its facing. */
    override fun teleport(self: LuaHandle.Effect, locationOrPosition: LocationOrVec3): Boolean {
        val place = locationOrPosition.place
        val effect = effect(self) ?: return false
        val here = Location(effect.world, effect.origin.x, effect.origin.y, effect.origin.z, effect.yaw, effect.pitch)
        val to = if (place.world == null) {
            here.copy(x = place.position.x, y = place.position.y, z = place.position.z)
        } else {
            place.resolve(effect.world)
        }
        return session.particles.teleport(effect, to)
    }

    override fun follow(self: LuaHandle.Effect, target: LuaHandle, offset: Vec3?): Boolean {
        val follow = followTarget(target)
        val effect = effect(self) ?: return false
        return session.particles.follow(effect, follow, offset)
    }
}
