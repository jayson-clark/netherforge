package dev.netherforge.plugin.world

import dev.netherforge.plugin.platform.EntityTag
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.session.RuntimeService
import java.util.UUID

/**
 * Which players each entity is hidden from (`entity:hide_from`), kept here
 * because the server forgets it when the player leaves and when the entity's
 * chunk unloads: it's applied again when they join and when the entity loads.
 * What a script set on the server's things lasts as long as the session: every
 * entity shows again when it ends, and the next session's scripts hide what
 * they hide.
 */
internal class HiddenEntities(private val platform: Platform) : RuntimeService {
    override val name get() = "hidden entities"

    private val hidden = HashMap<UUID, MutableSet<UUID>>()

    fun isHidden(entity: UUID, player: UUID) = hidden[entity]?.contains(player) == true

    fun hide(entity: UUID, player: UUID): Boolean {
        if (!platform.worldEntities.hide(player, entity)) return false
        hidden.getOrPut(entity) { HashSet() } += player
        return true
    }

    fun show(entity: UUID, player: UUID): Boolean {
        if (!platform.worldEntities.show(player, entity)) return false
        hidden[entity]?.let {
            it -= player
            if (it.isEmpty()) hidden.remove(entity)
        }
        return true
    }

    override fun playerJoined(player: PlayerRef) {
        for ((entity, players) in hidden) if (player.uuid in players) platform.worldEntities.hide(player.uuid, entity)
    }

    override fun entitiesLoaded(tagged: Map<UUID, EntityTag>, untagged: List<UUID>) {
        if (hidden.isEmpty()) return
        for (entity in untagged) hidden[entity]?.forEach { platform.worldEntities.hide(it, entity) }
    }

    override fun entityGone(id: UUID) {
        hidden.remove(id)
    }

    /** The session is ending: everything hidden shows again. */
    override fun stop() {
        for ((entity, players) in hidden) for (player in players) platform.worldEntities.show(player, entity)
        hidden.clear()
    }
}
