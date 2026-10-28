package dev.netherforge.plugin.platform

import dev.netherforge.format.centity.Billboard
import dev.netherforge.format.centity.DisplayDef
import dev.netherforge.format.math.Matrix4
import java.util.UUID

/**
 * Marks an entity as part of a centity, written into the entity's persistent
 * data so it survives restarts and can be traced back to its node.
 */
data class EntityTag(val instance: UUID, val node: String, val role: EntityRole)

/**
 * What an entity is to its instance: a node's display or hitbox, or the
 * instance's **anchor**, the invisible vehicle at the anchor its displays
 * (and the hitboxes that sit at the anchor) ride, so moving the centity is
 * one teleport. An anchor's tag has an empty node.
 *
 * A [SCREEN] entity is no centity's: it's an item frame a screen hangs in,
 * its tag's instance the screen and its node the map (`column,row`).
 */
enum class EntityRole { DISPLAY, HITBOX, ANCHOR, SCREEN }

/**
 * A `marker` entity in a structure that asks for a centity ([centity], as a
 * script would name it) where it stands: [at] is its position and facing.
 * The marker is an ordinary entity the structure's template holds (so it
 * is placed, rotated and mirrored with the structure); the adapter finds
 * the ones tagged `nf.centity.<centity>` ([TAG_PREFIX]). See `StructureSpawns`.
 */
data class StructureMarker(val id: UUID, val centity: String, val at: Location) {
    companion object {
        /** What a marker's tag starts with; the rest is the centity's name (`nf.centity.guard`, `nf.centity.acme:guard`). */
        const val TAG_PREFIX = "nf.centity."
    }
}

/**
 * How a display entity is placed relative to the anchor it stands on.
 *
 * Every display of a centity rides the instance's anchor entity and carries
 * its offset in [matrix], so moving the whole centity is one teleport.
 * [cullSize] widens the client's culling box to the whole assembly; without
 * it a display offset far from its entity vanishes at some camera angles.
 */
data class DisplayPose(val matrix: Matrix4, val interpolationTicks: Int, val cullSize: Double)

/**
 * How a display is drawn beyond what it shows ([DisplayDef]) and its pose:
 * what scripts change at runtime, all at its defaults for a fresh node.
 */
data class DisplayLook(
    val glowing: Boolean = false,
    /** `0xRRGGBB` for a block or item display's glow, or null for the default (white). */
    val glowColor: Int? = null,
    /** Fixed light levels, or null to be lit by where it stands. */
    val brightness: DisplayBrightness? = null,
    /** Null leaves it as its content says (a text display's `billboard`; fixed for the others). */
    val billboard: Billboard? = null,
    /** Minecraft's view range: a multiplier, 1 being 64 blocks at the client's 100% entity distance. */
    val viewRange: Double = 1.0,
    /** Ticks a teleport of the entity is eased over, 0–59. */
    val teleportTicks: Int = 0,
    /** A text display's text opacity, 0–255, or null for opaque. */
    val textOpacity: Int? = null,
    /** An item display's whole item, or null for its content's bare item. */
    val item: ItemData? = null
)

/** Block and sky light, each 0–15. */
data class DisplayBrightness(val block: Int, val sky: Int)

/**
 * The entities centities are made of: one display per node that draws
 * something, one interaction entity per node with a hitbox.
 */
interface EntityOps {
    /** Spawns a display at [at] showing [display] posed by [pose], or null if it can't (unknown block or item). */
    fun spawnDisplay(at: Location, display: DisplayDef, pose: DisplayPose, tag: EntityTag): UUID?

    /** Spawns an interaction entity: [width] square footprint, standing on [at]. */
    fun spawnHitbox(at: Location, width: Double, height: Double, tag: EntityTag): UUID?

    /**
     * Spawns an instance's anchor at [at]: an entity that draws nothing, which
     * the instance's other entities ride ([mount]). Riders stand exactly at
     * its position, so moving it with [teleport] moves them all.
     */
    fun spawnAnchor(at: Location, tag: EntityTag): UUID?

    /**
     * Makes [passenger] ride [vehicle]. True when it rides it now (already
     * did included); false when either can't be reached.
     */
    fun mount(vehicle: UUID, passenger: UUID): Boolean

    /** Gets [passenger] off whatever it rides, where it is. Nothing when it rides nothing or can't be reached. */
    fun dismount(passenger: UUID)

    /**
     * Whether the entity can be reached right now. False for one in an
     * unloaded chunk and for one that's gone; [WorldOps.entitiesLoaded] tells
     * the two apart.
     */
    fun isLoaded(id: UUID): Boolean

    /**
     * Shows [display] on an existing display entity. False when the entity is
     * the wrong kind for it (a block display asked to show text), which only
     * a respawn fixes.
     */
    fun updateDisplay(id: UUID, display: DisplayDef): Boolean

    fun setPose(id: UUID, pose: DisplayPose)

    /** Moves an entity, keeping everything else about it: what rides it comes too. One that rides something doesn't move. */
    fun teleport(id: UUID, to: Location)

    /**
     * Where an entity is now, as the game has it: a rider sits where its vehicle
     * puts it (above the feet of a player who carries it, lower when they sneak).
     * Null when it can't be reached.
     */
    fun location(id: UUID): Location?

    fun resizeHitbox(id: UUID, width: Double, height: Double)

    /** Removes the entity; false when it can't be reached (see [isLoaded]). */
    fun remove(id: UUID): Boolean

    /**
     * Whether the server saves the entity with its chunk. A temporary
     * centity's entities aren't, so a crash can't leave them behind;
     * `keep()` makes them so. Nothing for an entity that can't be reached.
     */
    fun setPersistent(id: UUID, persistent: Boolean)

    /**
     * Draws a display as [look] says. Called after [updateDisplay] whenever
     * either changed, so the look always has the last word over the content.
     */
    fun setLook(id: UUID, look: DisplayLook)

    /**
     * Hides an entity from one player, or shows it again. The server forgets
     * this when the player leaves and when the entity unloads, so the runtime
     * keeps its own record and says it again. Nothing for an offline player
     * or an entity out of reach.
     */
    fun setHidden(player: UUID, entity: UUID, hidden: Boolean)

    /** Every loaded entity carrying a tag, for cleaning up strays after a crash or a lost record. */
    fun loadedTagged(): Map<UUID, EntityTag>

    /** Every loaded [StructureMarker], in the order the server lists its entities: what's still waiting for its centity. */
    fun structureMarkers(): List<StructureMarker>
}
