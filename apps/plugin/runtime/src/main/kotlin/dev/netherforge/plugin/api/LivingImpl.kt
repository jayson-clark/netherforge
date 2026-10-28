package dev.netherforge.plugin.api

import dev.netherforge.format.game.GameIds
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.game.has
import dev.netherforge.format.item.AttributeOperation
import dev.netherforge.plugin.binding.AttributeModifier
import dev.netherforge.plugin.binding.EffectOptions
import dev.netherforge.plugin.binding.LivingApi
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.StatusEffect
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.AttributeModifierData
import dev.netherforge.plugin.platform.EntityInfo
import dev.netherforge.plugin.platform.EntityNumber
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.StatusEffectData
import dev.netherforge.plugin.session.ProjectSession

/**
 * `Living`: a living entity's health, attributes, status effects and
 * equipment. Everything answers nil or false while it isn't in the world.
 */
internal class LivingImpl(private val session: ProjectSession) : LivingApi {
    private val entities get() = session.platform.worldEntities

    private fun info(self: LuaHandle.Entity): EntityInfo? = session.entityInfo(self)

    private fun number(self: LuaHandle.Entity, number: EntityNumber): Double? = session.entityNumber(self, number)

    override fun health(self: LuaHandle.Living): Double? = number(self, EntityNumber.HEALTH)

    override fun setHealth(self: LuaHandle.Living, health: Double): Boolean {
        val id = self.uuidOrNull() ?: return false
        val max = entities.number(id, EntityNumber.MAX_HEALTH) ?: return false
        if (health < 0 || health > max || health.isNaN()) throw LuaApiException("health must be from 0 to max_health() ($max), not $health")
        return entities.setNumber(id, EntityNumber.HEALTH, health)
    }

    override fun maxHealth(self: LuaHandle.Living): Double? = number(self, EntityNumber.MAX_HEALTH)

    override fun damage(self: LuaHandle.Living, amount: Double, source: LuaHandle.Entity?): Boolean {
        if (amount < 0 || !amount.isFinite()) throw LuaApiException("amount can't be below 0")
        val id = self.uuidOrNull() ?: return false
        if (info(self)?.living != true) return false
        return entities.damage(id, amount, source?.uuidOrNull())
    }

    override fun heal(self: LuaHandle.Living, amount: Double): Boolean {
        if (amount < 0 || !amount.isFinite()) throw LuaApiException("amount can't be below 0")
        val id = self.uuidOrNull() ?: return false
        val health = entities.number(id, EntityNumber.HEALTH) ?: return false
        val max = entities.number(id, EntityNumber.MAX_HEALTH) ?: return false
        return entities.setNumber(id, EntityNumber.HEALTH, minOf(max, health + amount))
    }

    private val attributes get() = session.platform.attributes

    /** An attribute's namespaced id, unless the server is known not to have it. */
    private fun attributeId(attribute: String): String {
        val id = GameIds.normalize(attribute)
        if (!GameIds.isValid(attribute) || session.platform.game.has(RegistryKey.ATTRIBUTE, id) == false) {
            throw LuaApiException("no attribute \"$attribute\" on this server")
        }
        return id
    }

    /**
     * A modifier id a script gave, in the project's namespace, so a script
     * can't replace or remove anything else's.
     */
    private fun modifierId(id: String): String {
        if (!GameIds.isValid(id)) throw LuaApiException("\"$id\" isn't a modifier id: lowercase letters, digits, _, -, . and /")
        return session.ownId(id, "modifier")
    }

    override fun attribute(self: LuaHandle.Living, attribute: String): Double? {
        val id = attributeId(attribute)
        return self.uuidOrNull()?.let { attributes.value(it, id) }
    }

    override fun attributeBase(self: LuaHandle.Living, attribute: String): Double? {
        val id = attributeId(attribute)
        return self.uuidOrNull()?.let { attributes.base(it, id) }
    }

    override fun setAttributeBase(self: LuaHandle.Living, attribute: String, value: Double): Boolean {
        val id = attributeId(attribute)
        if (!value.isFinite()) throw LuaApiException("value must be a number, not $value")
        return self.uuidOrNull()?.let { attributes.setBase(it, id, value) } == true
    }

    override fun attributeModifiers(self: LuaHandle.Living, attribute: String): List<AttributeModifier>? {
        val id = attributeId(attribute)
        val modifiers = self.uuidOrNull()?.let { attributes.modifiers(it, id) } ?: return null
        return modifiers.sortedBy { it.id }.map { AttributeModifier(it.id, it.amount, it.operation.name.lowercase()) }
    }

    override fun addAttributeModifier(
        self: LuaHandle.Living,
        attribute: String,
        id: String,
        amount: Double,
        operation: String?,
        saved: Boolean?
    ): Boolean {
        val attributeId = attributeId(attribute)
        val modifier = modifierId(id)
        if (!amount.isFinite()) throw LuaApiException("amount must be a number, not $amount")
        val op = operation?.let { name -> AttributeOperation.entries.first { it.name.lowercase() == name } } ?: AttributeOperation.ADD_VALUE
        val entity = self.uuidOrNull() ?: return false
        return attributes.addModifier(entity, attributeId, AttributeModifierData(modifier, amount, op, saved ?: true))
    }

    override fun removeAttributeModifier(self: LuaHandle.Living, attribute: String, id: String): Boolean {
        val attributeId = attributeId(attribute)
        val modifier = modifierId(id)
        return self.uuidOrNull()?.let { attributes.removeModifier(it, attributeId, modifier) } == true
    }

    override fun effects(self: LuaHandle.Living): List<StatusEffect> = self.uuidOrNull()?.let(entities::effects).orEmpty().map {
        StatusEffect(it.effect, it.ticks.toLong(), it.amplifier.toLong(), it.ambient, it.particles, it.icon)
    }

    /** A status effect's namespaced id, once the server is known to have it. */
    private fun effectId(effect: String): String {
        val id = GameIds.normalize(effect)
        if (!GameIds.isValid(effect) || !entities.effectExists(id)) throw LuaApiException("no status effect \"$effect\" on this server")
        return id
    }

    override fun hasEffect(self: LuaHandle.Living, effect: String): Boolean {
        val id = effectId(effect)
        return self.uuidOrNull()?.let(entities::effects)?.any { it.effect == id } == true
    }

    override fun addEffect(self: LuaHandle.Living, effect: String, ticks: Long, options: EffectOptions?): Boolean {
        val id = effectId(effect)
        if (ticks < -1 || ticks > Int.MAX_VALUE) throw LuaApiException("ticks must be at least 0 (or -1 for ever), not $ticks")
        val amplifier = options?.amplifier ?: 0
        if (amplifier !in 0..255) throw LuaApiException("options.amplifier must be from 0 to 255, not $amplifier")
        val entity = self.uuidOrNull() ?: return false
        return entities.addEffect(
            entity,
            StatusEffectData(
                id,
                ticks.toInt(),
                amplifier.toInt(),
                options?.ambient == true,
                options?.particles != false,
                options?.icon != false
            )
        )
    }

    override fun removeEffect(self: LuaHandle.Living, effect: String): Boolean {
        val id = effectId(effect)
        return self.uuidOrNull()?.let { entities.removeEffect(it, id) } == true
    }

    override fun equipment(self: LuaHandle.Living, slot: String): ItemData? = self.uuidOrNull()?.let { entities.equipment(it, slot) }

    override fun setEquipment(self: LuaHandle.Living, slot: String, item: ItemData?): Boolean =
        self.uuidOrNull()?.let { entities.setEquipment(it, slot, item) } == true
}
