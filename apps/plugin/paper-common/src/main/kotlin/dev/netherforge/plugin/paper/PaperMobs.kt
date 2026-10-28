package dev.netherforge.plugin.paper

import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.platform.AttributeModifierData
import dev.netherforge.plugin.platform.AttributeOps
import dev.netherforge.plugin.platform.PathfindingOps
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attributable
import org.bukkit.attribute.AttributeInstance
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Mob
import java.util.UUID
import org.bukkit.Location as BukkitLocation

/**
 * [AttributeOps] with the Paper API: an entity's [AttributeInstance] for an
 * attribute from the server's registry, null when the entity hasn't got it.
 * Modifiers are keyed by their [NamespacedKey], and added the persistent
 * way, so they're saved with the entity, unless one says it isn't
 * (a transient modifier, gone when the entity unloads).
 */
class PaperAttributes(private val entities: PaperWorldEntities) : AttributeOps {
    private val registry get() = RegistryAccess.registryAccess().getRegistry(RegistryKey.ATTRIBUTE)

    private fun instance(id: UUID, attribute: String): AttributeInstance? {
        val type = NamespacedKey.fromString(attribute)?.let { registry.get(it) } ?: return null
        return (entities.entity(id) as? Attributable)?.getAttribute(type)
    }

    override fun value(id: UUID, attribute: String) = instance(id, attribute)?.value

    override fun base(id: UUID, attribute: String) = instance(id, attribute)?.baseValue

    override fun setBase(id: UUID, attribute: String, value: Double): Boolean {
        val instance = instance(id, attribute) ?: return false
        instance.baseValue = value
        return true
    }

    override fun modifiers(id: UUID, attribute: String): List<AttributeModifierData>? = instance(id, attribute)?.modifiers?.map {
        AttributeModifierData(it.key.toString(), it.amount, it.operation.netherforge())
    }

    override fun addModifier(id: UUID, attribute: String, modifier: AttributeModifierData): Boolean {
        val instance = instance(id, attribute) ?: return false
        val key = NamespacedKey.fromString(modifier.id) ?: return false
        instance.removeModifier(key)
        val made = AttributeModifier(key, modifier.amount, modifier.operation.paper())
        if (modifier.saved) instance.addModifier(made) else instance.addTransientModifier(made)
        return true
    }

    override fun removeModifier(id: UUID, attribute: String, modifier: String): Boolean {
        val instance = instance(id, attribute) ?: return false
        val key = NamespacedKey.fromString(modifier) ?: return false
        if (instance.getModifier(key) == null) return false
        instance.removeModifier(key)
        return true
    }
}

/** [PathfindingOps] with Paper's [Mob.getPathfinder]: the mob's own navigation, whoever started it. */
class PaperPathfinding(private val entities: PaperWorldEntities) : PathfindingOps {
    private fun mob(id: UUID) = entities.entity(id) as? Mob

    override fun moveTo(id: UUID, to: Vec3, speed: Double): Boolean {
        val mob = mob(id) ?: return false
        return mob.pathfinder.moveTo(BukkitLocation(mob.world, to.x, to.y, to.z), speed)
    }

    override fun stop(id: UUID): Boolean {
        val mob = mob(id) ?: return false
        mob.pathfinder.stopPathfinding()
        return true
    }

    override fun hasPath(id: UUID) = mob(id)?.pathfinder?.hasPath() == true

    override fun pathEnd(id: UUID): Vec3? {
        val pathfinder = mob(id)?.pathfinder?.takeIf { it.hasPath() } ?: return null
        return pathfinder.currentPath?.finalPoint?.let { Vec3(it.x, it.y, it.z) }
    }
}
