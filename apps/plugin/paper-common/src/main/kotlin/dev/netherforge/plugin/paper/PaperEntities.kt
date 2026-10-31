package dev.netherforge.plugin.paper

import dev.netherforge.format.centity.Billboard
import dev.netherforge.format.centity.BlockDisplay
import dev.netherforge.format.centity.DisplayDef
import dev.netherforge.format.centity.ImageDisplay
import dev.netherforge.format.centity.ItemDisplay
import dev.netherforge.format.centity.ItemTransform
import dev.netherforge.format.centity.ModelDisplay
import dev.netherforge.format.centity.RectDisplay
import dev.netherforge.format.centity.TextAlignment
import dev.netherforge.format.centity.TextDisplay
import dev.netherforge.format.math.Matrix4
import dev.netherforge.plugin.platform.DisplayLook
import dev.netherforge.plugin.platform.DisplayPose
import dev.netherforge.plugin.platform.EntityOps
import dev.netherforge.plugin.platform.EntityRole
import dev.netherforge.plugin.platform.EntityTag
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.StructureMarker
import io.papermc.paper.entity.TeleportFlag
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Marker
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import org.bukkit.Location as BukkitLocation
import org.bukkit.entity.BlockDisplay as BukkitBlockDisplay
import org.bukkit.entity.ItemDisplay as BukkitItemDisplay
import org.bukkit.entity.TextDisplay as BukkitTextDisplay

/**
 * Display and interaction entities, through the Paper API only.
 *
 * Every entity is stamped with its [EntityTag] in persistent data, which
 * survives restarts and is how a click or a chunk load finds its way back to
 * the instance and node that own the entity.
 */
class PaperEntities(private val plugin: Plugin) : EntityOps {
    private val instanceKey = NamespacedKey(plugin, "instance")
    private val nodeKey = NamespacedKey(plugin, "node")
    private val roleKey = NamespacedKey(plugin, "role")

    fun tagOf(entity: Entity): EntityTag? {
        val data = entity.persistentDataContainer
        val instance =
            data.get(instanceKey, PersistentDataType.STRING)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        val node = data.get(nodeKey, PersistentDataType.STRING) ?: return null
        val role = data.get(roleKey, PersistentDataType.STRING)?.let { runCatching { EntityRole.valueOf(it) }.getOrNull() } ?: return null
        return EntityTag(instance, node, role)
    }

    fun stamp(entity: Entity, tag: EntityTag) {
        val data = entity.persistentDataContainer
        data.set(instanceKey, PersistentDataType.STRING, tag.instance.toString())
        data.set(nodeKey, PersistentDataType.STRING, tag.node)
        data.set(roleKey, PersistentDataType.STRING, tag.role.name)
    }

    private fun bukkit(location: Location): BukkitLocation? = Bukkit.getWorld(location.world)?.let {
        BukkitLocation(it, location.x, location.y, location.z, location.yaw.toFloat(), location.pitch.toFloat())
    }

    override fun spawnDisplay(at: Location, display: DisplayDef, pose: DisplayPose, tag: EntityTag): UUID? {
        val location = bukkit(at) ?: return null
        val world = location.world
        val entity: Display = when (display) {
            // Stamped as it's made, so the spawn event already sees it's ours (and scripts never hear of it).
            is BlockDisplay -> {
                val data = runCatching { Bukkit.createBlockData(display.block) }.getOrNull() ?: return null
                world.spawn(location, BukkitBlockDisplay::class.java) {
                    stamp(it, tag)
                    it.block = data
                }
            }
            is ItemDisplay -> {
                val stack = itemStack(display.item) ?: return null
                world.spawn(location, BukkitItemDisplay::class.java) {
                    stamp(it, tag)
                    it.setItemStack(stack)
                    it.itemDisplayTransform = itemTransform(display.itemTransform)
                }
            }
            is ModelDisplay -> {
                val stack = modelStack(display) ?: return null
                world.spawn(location, BukkitItemDisplay::class.java) {
                    stamp(it, tag)
                    it.setItemStack(stack)
                    it.itemDisplayTransform = itemTransform(display.itemTransform ?: ItemTransform.NONE)
                }
            }
            is TextDisplay -> world.spawn(location, BukkitTextDisplay::class.java) {
                stamp(it, tag)
                apply(it, display)
            }
            // The runtime draws these as text displays (DisplayDrawing) before they get here.
            is RectDisplay, is ImageDisplay -> return null
        }
        applyPose(entity, pose)
        return entity.uniqueId
    }

    override fun spawnHitbox(at: Location, width: Double, height: Double, tag: EntityTag): UUID? {
        val location = bukkit(at) ?: return null
        val entity = location.world.spawn(location, Interaction::class.java) {
            stamp(it, tag)
            it.interactionWidth = width.toFloat()
            it.interactionHeight = height.toFloat()
            it.isResponsive = true
        }
        return entity.uniqueId
    }

    override fun spawnAnchor(at: Location, tag: EntityTag): UUID? {
        val location = bukkit(at) ?: return null
        // A text display with no text and no background draws nothing, but the client knows it, so riders sit on it there too.
        val entity = location.world.spawn(location, BukkitTextDisplay::class.java) {
            stamp(it, tag)
            it.text(Component.empty())
            it.isDefaultBackground = false
            it.backgroundColor = Color.fromARGB(0)
        }
        return entity.uniqueId
    }

    override fun mount(vehicle: UUID, passenger: UUID): Boolean {
        val carrier = Bukkit.getEntity(vehicle) ?: return false
        val rider = Bukkit.getEntity(passenger) ?: return false
        if (rider.vehicle == carrier) return true
        if (rider.vehicle != null) rider.leaveVehicle()
        return carrier.addPassenger(rider)
    }

    override fun dismount(passenger: UUID) {
        Bukkit.getEntity(passenger)?.takeIf { it.vehicle != null }?.leaveVehicle()
    }

    override fun isLoaded(id: UUID): Boolean = Bukkit.getEntity(id) != null

    override fun updateDisplay(id: UUID, display: DisplayDef): Boolean {
        val entity = Bukkit.getEntity(id) ?: return true
        when (display) {
            is BlockDisplay -> {
                if (entity !is BukkitBlockDisplay) return false
                runCatching { Bukkit.createBlockData(display.block) }.getOrNull()?.let { if (it != entity.block) entity.block = it }
            }
            is ItemDisplay -> {
                if (entity !is BukkitItemDisplay) return false
                itemStack(display.item)?.let { entity.setItemStack(it) }
                entity.itemDisplayTransform = itemTransform(display.itemTransform)
            }
            // A new look is a new item on the same entity: one metadata update, nothing respawned.
            is ModelDisplay -> {
                if (entity !is BukkitItemDisplay) return false
                modelStack(display)?.let { if (it != entity.itemStack) entity.setItemStack(it) }
                entity.itemDisplayTransform = itemTransform(display.itemTransform ?: ItemTransform.NONE)
            }
            is TextDisplay -> {
                if (entity !is BukkitTextDisplay) return false
                apply(entity, display)
            }
            // The runtime draws these as text displays (DisplayDrawing) before they get here.
            is RectDisplay, is ImageDisplay -> return false
        }
        return true
    }

    /** Every text property, defaults included, so a property removed from the file goes back to vanilla. */
    private fun apply(entity: BukkitTextDisplay, display: TextDisplay) {
        entity.text(PaperText.displayText(display))
        entity.billboard = billboard(display.billboard ?: TextDisplay.DEFAULT_BILLBOARD)
        entity.alignment = when (display.alignment) {
            TextAlignment.LEFT -> BukkitTextDisplay.TextAlignment.LEFT
            TextAlignment.RIGHT -> BukkitTextDisplay.TextAlignment.RIGHT
            TextAlignment.CENTER, null -> BukkitTextDisplay.TextAlignment.CENTER
        }
        entity.lineWidth = display.lineWidth ?: TextDisplay.DEFAULT_LINE_WIDTH
        entity.isSeeThrough = display.seeThrough ?: false
        entity.isShadowed = display.shadow ?: false
        val background = display.background?.removePrefix("#")?.toLongOrNull(16)
        if (background == null) {
            entity.isDefaultBackground = true
        } else {
            entity.isDefaultBackground = false
            entity.backgroundColor = Color.fromARGB(background.toInt())
        }
    }

    private fun billboard(billboard: Billboard): Display.Billboard = when (billboard) {
        Billboard.FIXED -> Display.Billboard.FIXED
        Billboard.VERTICAL -> Display.Billboard.VERTICAL
        Billboard.HORIZONTAL -> Display.Billboard.HORIZONTAL
        Billboard.CENTER -> Display.Billboard.CENTER
    }

    private fun itemStack(id: String): ItemStack? = Material.matchMaterial(id)?.takeIf { it.isItem }?.let { ItemStack(it) }

    /** What a model display holds: a stack drawn as its look (the `item_model`, in the project's namespace). */
    private fun modelStack(display: ModelDisplay): ItemStack? {
        val look =
            PaperItems.resolve(display.model)?.let { runCatching { NamespacedKey(it.namespace, it.path) }.getOrNull() } ?: return null
        return itemStack(ModelDisplay.CARRIER)?.apply { editMeta { it.itemModel = look } }
    }

    private fun itemTransform(transform: ItemTransform?): BukkitItemDisplay.ItemDisplayTransform = when (transform) {
        ItemTransform.NONE -> BukkitItemDisplay.ItemDisplayTransform.NONE
        ItemTransform.THIRDPERSON_LEFTHAND -> BukkitItemDisplay.ItemDisplayTransform.THIRDPERSON_LEFTHAND
        ItemTransform.THIRDPERSON_RIGHTHAND -> BukkitItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND
        ItemTransform.FIRSTPERSON_LEFTHAND -> BukkitItemDisplay.ItemDisplayTransform.FIRSTPERSON_LEFTHAND
        ItemTransform.FIRSTPERSON_RIGHTHAND -> BukkitItemDisplay.ItemDisplayTransform.FIRSTPERSON_RIGHTHAND
        ItemTransform.HEAD -> BukkitItemDisplay.ItemDisplayTransform.HEAD
        ItemTransform.GUI -> BukkitItemDisplay.ItemDisplayTransform.GUI
        ItemTransform.GROUND -> BukkitItemDisplay.ItemDisplayTransform.GROUND
        ItemTransform.FIXED, null -> BukkitItemDisplay.ItemDisplayTransform.FIXED
    }

    override fun setPose(id: UUID, pose: DisplayPose) {
        (Bukkit.getEntity(id) as? Display)?.let { applyPose(it, pose) }
    }

    private fun applyPose(display: Display, pose: DisplayPose) {
        // Writing the delay is what tells the client to start easing now.
        display.interpolationDelay = 0
        display.interpolationDuration = pose.interpolationTicks
        val matrix = pose.matrix
        if (collapsed(matrix)) {
            // A zero-scale matrix has no rotation to decompose; say "nothing here" directly.
            val at = matrix.translation()
            display.transformation =
                Transformation(Vector3f(at.x.toFloat(), at.y.toFloat(), at.z.toFloat()), Quaternionf(), Vector3f(), Quaternionf())
        } else {
            display.setTransformationMatrix(Matrix4f().set(FloatArray(16) { matrix.values[it].toFloat() }))
        }
        display.displayWidth = pose.cullSize.toFloat()
        display.displayHeight = pose.cullSize.toFloat()
    }

    private fun collapsed(matrix: Matrix4): Boolean = matrix.scale().let { it.x < EPSILON || it.y < EPSILON || it.z < EPSILON }

    override fun location(id: UUID): Location? = Bukkit.getEntity(id)?.location?.let {
        Location(it.world.name, it.x, it.y, it.z, it.yaw.toDouble(), it.pitch.toDouble())
    }

    override fun teleport(id: UUID, to: Location) {
        val entity = Bukkit.getEntity(id) ?: return
        // A rider stays on: Paper would take it off its vehicle to move it.
        if (entity.vehicle != null) return
        val location = bukkit(to) ?: return
        val riders = entity.passengers
        if (riders.isEmpty()) {
            entity.teleport(location)
        } else if (location.world == entity.world) {
            // Paper refuses to move an entity something rides unless told to keep them.
            entity.teleport(location, TeleportFlag.EntityState.RETAIN_PASSENGERS)
        } else {
            // Not across worlds, though: off, over, and on again.
            entity.eject()
            entity.teleport(location)
            for (rider in riders) {
                rider.teleport(location)
                entity.addPassenger(rider)
            }
        }
    }

    override fun resizeHitbox(id: UUID, width: Double, height: Double) {
        val interaction = Bukkit.getEntity(id) as? Interaction ?: return
        interaction.interactionWidth = width.toFloat()
        interaction.interactionHeight = height.toFloat()
    }

    override fun remove(id: UUID): Boolean {
        val entity = Bukkit.getEntity(id) ?: return false
        entity.remove()
        return true
    }

    override fun setPersistent(id: UUID, persistent: Boolean) {
        Bukkit.getEntity(id)?.isPersistent = persistent
    }

    override fun setLook(id: UUID, look: DisplayLook) {
        val display = Bukkit.getEntity(id) as? Display ?: return
        display.isGlowing = look.glowing
        // Glow colours show on block and item displays only.
        if (display !is BukkitTextDisplay) display.glowColorOverride = look.glowColor?.let(Color::fromRGB)
        display.brightness = look.brightness?.let { Display.Brightness(it.block, it.sky) }
        look.billboard?.let { display.billboard = billboard(it) }
        display.viewRange = look.viewRange.toFloat()
        display.teleportDuration = look.teleportTicks.coerceIn(0, 59)
        if (display is BukkitTextDisplay) {
            // A signed byte: -1 is fully opaque, as vanilla stores it.
            display.textOpacity = (look.textOpacity ?: 255).toByte()
        }
        if (display is BukkitItemDisplay) look.item?.let(PaperItems::toStack)?.let { display.setItemStack(it) }
    }

    override fun setHidden(player: UUID, entity: UUID, hidden: Boolean) {
        val who = Bukkit.getPlayer(player) ?: return
        val target = Bukkit.getEntity(entity) ?: return
        if (hidden) who.hideEntity(plugin, target) else who.showEntity(plugin, target)
    }

    override fun loadedTagged(): Map<UUID, EntityTag> = buildMap {
        for (world in Bukkit.getWorlds()) {
            for (entity in world.entities) {
                if (entity !is Display && entity !is Interaction && entity !is ItemFrame) continue
                tagOf(entity)?.let { put(entity.uniqueId, it) }
            }
        }
    }

    /** A structure marker: a `marker` entity tagged with the centity it asks for. */
    fun markerOf(entity: Entity): StructureMarker? {
        if (entity !is Marker) return null
        val tag = entity.scoreboardTags.firstOrNull { it.startsWith(StructureMarker.TAG_PREFIX) } ?: return null
        val at = entity.location
        return StructureMarker(
            entity.uniqueId,
            tag.removePrefix(StructureMarker.TAG_PREFIX),
            Location(at.world.name, at.x, at.y, at.z, at.yaw.toDouble(), at.pitch.toDouble())
        )
    }

    override fun structureMarkers(): List<StructureMarker> = Bukkit.getWorlds().flatMap { world -> world.entities.mapNotNull(::markerOf) }

    private companion object {
        const val EPSILON = 1e-6
    }
}
