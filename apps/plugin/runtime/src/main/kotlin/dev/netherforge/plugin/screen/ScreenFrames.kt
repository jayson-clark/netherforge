package dev.netherforge.plugin.screen

import dev.netherforge.plugin.platform.EntityRole
import dev.netherforge.plugin.platform.EntityTag
import dev.netherforge.plugin.platform.Platform
import java.util.UUID

/**
 * Screens' item frames: one a map, tagged [EntityRole.SCREEN], never saved with their chunk. Each is hung where its
 * screen is placed when its chunk's entities are loaded and it isn't there ([hang]), taken down when the screen
 * moves or goes ([takeDown]), and known by the entity it is ([owner]), which is how a click on one finds its screen.
 */
internal class ScreenFrames(private val platform: Platform) {
    private val owners = HashMap<UUID, Pair<ScreenState, Int>>()

    /** The screen [entity] is a frame of, and which of its tiles. */
    fun owner(entity: UUID): Pair<ScreenState, Int>? = owners[entity]

    /** Hangs every frame of [screen] that isn't up and whose chunk is loaded. */
    fun hang(screen: ScreenState) {
        screen.rehang = false
        val placement = screen.placement ?: return
        for (tile in 0 until screen.tiles) {
            val existing = screen.frames[tile]
            if (existing != null && platform.entities.isLoaded(existing)) continue
            val at = placement.locationOf(tile % screen.columns, tile / screen.columns)
            if (!platform.worlds.entitiesLoaded(at)) continue
            existing?.let {
                owners.remove(it)
                platform.entities.remove(it)
            }
            val tag = EntityTag(screen.uuid, "${tile % screen.columns},${tile / screen.columns}", EntityRole.SCREEN)
            val frame = platform.screens.hang(at, placement.facing, screen.maps[tile], screen.settings.glowing, tag)
            screen.frames[tile] = frame
            if (frame != null) owners[frame] = screen to tile
        }
    }

    fun takeDown(screen: ScreenState) {
        for ((tile, frame) in screen.frames.withIndex()) {
            frame ?: continue
            owners.remove(frame)
            platform.entities.remove(frame)
            screen.frames[tile] = null
        }
    }

    /** Our frames from a chunk that came back: anything of a screen's no screen owns is left over, and goes. */
    fun loaded(tagged: Map<UUID, EntityTag>) {
        for ((entity, tag) in tagged) {
            if (tag.role == EntityRole.SCREEN && entity !in owners) platform.entities.remove(entity)
        }
    }

    /** A frame that went (broken, or with its chunk): hung again when it can be. */
    fun gone(id: UUID) {
        val (screen, tile) = owners.remove(id) ?: return
        if (screen.frames.getOrNull(tile) == id) {
            screen.frames[tile] = null
            screen.rehang = true
        }
    }
}
