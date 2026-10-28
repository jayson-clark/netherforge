package dev.netherforge.plugin.centity

import dev.netherforge.plugin.platform.Location
import java.util.UUID

/**
 * A spawned centity as the store keeps it, so it comes back after a restart.
 *
 * Two halves keep an instance recoverable. Every entity it owns carries a tag
 * in its persistent data (instance id, node, role), which is how a click is
 * traced to its node and how strays are recognised. The record is the index:
 * which instances exist, what they are, where they're anchored and which
 * entities are whose, including instances in chunks that aren't loaded.
 */
data class InstanceRecord(
    val id: UUID,
    /** The centity, named in full (`shop:turret`), so a record outlives the project's namespace changing. */
    val centity: String,
    /** The anchor; its own facing is always 0. */
    val anchor: Location,
    /** Which way the centity faces, in degrees. */
    val yaw: Double = 0.0,
    /** Node name → display entity. */
    val displays: Map<String, UUID> = emptyMap(),
    /** Node name → interaction entity. */
    val hitboxes: Map<String, UUID> = emptyMap(),
    /** The anchor entity its displays ride, or null when it has none yet (made before anchors existed: the next reconcile makes one). */
    val anchorEntity: UUID? = null,
    /** Entities still waiting to be removed because their chunk was unloaded. */
    val orphans: List<UUID> = emptyList(),
    /** Appeared by itself and is still temporary: never in the store, only carried from one session to the next. */
    val natural: Boolean = false
)
