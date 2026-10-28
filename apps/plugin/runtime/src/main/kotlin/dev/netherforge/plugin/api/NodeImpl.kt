package dev.netherforge.plugin.api

import dev.netherforge.format.centity.Billboard
import dev.netherforge.format.centity.BlockDisplay
import dev.netherforge.format.centity.Channel
import dev.netherforge.format.centity.Easing
import dev.netherforge.format.centity.ItemDisplay
import dev.netherforge.format.centity.ModelDisplay
import dev.netherforge.format.centity.TextAlignment
import dev.netherforge.format.centity.TextDisplay
import dev.netherforge.format.game.BlockState
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.math.degreesToRadians
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.plugin.binding.Brightness
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.NodeApi
import dev.netherforge.plugin.binding.RopeOptions
import dev.netherforge.plugin.binding.StretchOptions
import dev.netherforge.plugin.binding.TextStyle
import dev.netherforge.plugin.binding.TweenOptions
import dev.netherforge.plugin.binding.Vec3OrNodeOrEntity
import dev.netherforge.plugin.centity.Centities
import dev.netherforge.plugin.centity.Instance
import dev.netherforge.plugin.centity.Physics
import dev.netherforge.plugin.centity.RopeEnd
import dev.netherforge.plugin.centity.Ropes
import dev.netherforge.plugin.centity.StrungRope
import dev.netherforge.plugin.centity.length
import dev.netherforge.plugin.centity.lookRotation
import dev.netherforge.plugin.centity.normalized
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaValue
import dev.netherforge.plugin.physics.BodyState
import dev.netherforge.plugin.physics.Quat
import dev.netherforge.plugin.platform.DisplayBrightness
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.session.ProjectSession
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

internal class NodeImpl(private val session: ProjectSession) : NodeApi {
    override fun name(self: LuaHandle.Node): String = self.name

    override fun centity(self: LuaHandle.Node): LuaHandle.Centity = LuaHandle.Centity(self.centity)

    /** The node's instance and index, or null once its centity is removed. */
    private fun node(self: LuaHandle.Node): Pair<Instance, Int>? {
        val instance = session.instance(LuaHandle.Centity(self.centity)) ?: return null
        val index = instance.indexOf(self.name) ?: return null
        return instance to index
    }

    override fun parent(self: LuaHandle.Node): LuaHandle.Node? {
        val (instance, index) = node(self) ?: return null
        val parent = instance.definition.nodes[index].parentIndex
        return if (parent < 0) null else LuaHandle.Node(self.centity, instance.definition.nodes[parent].name)
    }

    override fun addChild(self: LuaHandle.Node, name: String, definition: LuaValue?): LuaHandle.Node? {
        val (instance, index) = node(self) ?: return null
        return session.addNode(instance, name, index, definition)
    }

    override fun remove(self: LuaHandle.Node): Boolean {
        val (instance, index) = node(self) ?: return false
        if (!instance.isAdded(index)) {
            throw LuaApiException("node \"${self.name}\" is declared by centity.json; only nodes a script added can be removed")
        }
        return session.centities.removeNode(instance, self.name)
    }

    override fun children(self: LuaHandle.Node): List<LuaHandle.Node> {
        val (instance, index) = node(self) ?: return emptyList()
        return instance.definition.nodes.filter { it.parentIndex == index }.map { LuaHandle.Node(self.centity, it.name) }
    }

    private fun get(self: LuaHandle.Node, channel: Channel): Vec3? {
        val (instance, index) = node(self) ?: return null
        return instance.get(index, channel)
    }

    private fun set(self: LuaHandle.Node, channel: Channel, value: Vec3) {
        val (instance, index) = node(self) ?: return
        instance.set(index, channel, value)
    }

    override fun translation(self: LuaHandle.Node) = get(self, Channel.TRANSLATION)

    override fun rotation(self: LuaHandle.Node) = get(self, Channel.ROTATION)

    override fun scale(self: LuaHandle.Node) = get(self, Channel.SCALE)

    override fun setTranslation(self: LuaHandle.Node, translation: Vec3) = set(self, Channel.TRANSLATION, translation)

    override fun setRotation(self: LuaHandle.Node, rotation: Vec3) = set(self, Channel.ROTATION, rotation)

    override fun setScale(self: LuaHandle.Node, scale: Vec3) = set(self, Channel.SCALE, scale)

    override fun stretchBetween(self: LuaHandle.Node, from: Vec3, to: Vec3, options: StretchOptions?): Boolean {
        val thickness = options?.thickness?.also {
            if (!(it > 0) || !it.isFinite()) throw LuaApiException("bad argument 'options.thickness' (more than 0, got $it)")
        }
        val (instance, index) = node(self) ?: return false
        if (instance.removed) return false
        return Ropes.stretch(instance, index, from, to, thickness)
    }

    override fun isRope(self: LuaHandle.Node): Boolean {
        val (instance, index) = node(self) ?: return false
        return instance.definition.nodes[index].rope != null
    }

    override fun ropeBetween(self: LuaHandle.Node, from: Vec3OrNodeOrEntity, to: Vec3OrNodeOrEntity, options: RopeOptions?): Boolean {
        val length = options?.length?.also {
            if (!(it > 0) || !it.isFinite()) throw LuaApiException("bad argument 'options.length' (more than 0, got $it)")
        }
        val (instance, index) = node(self) ?: return false
        if (instance.definition.nodes[index].rope == null) {
            throw LuaApiException("node \"${self.name}\" isn't a rope: give it \"rope\" in centity.json")
        }
        if (instance.removed) return false
        val ends = listOf(from, to).map { end ->
            when (end) {
                is Vec3OrNodeOrEntity.Vec3 -> RopeEnd.Point(end.value)
                is Vec3OrNodeOrEntity.Node -> RopeEnd.OfNode(
                    runCatching { UUID.fromString(end.value.centity) }.getOrNull() ?: return false,
                    end.value.name
                )
                is Vec3OrNodeOrEntity.Entity -> RopeEnd.OfEntity(end.value.uuidOrNull() ?: return false)
            }
        }
        val name = instance.definition.nodes[index].name
        // Laid on each of its ticks from the next, wherever its ends are then.
        instance.ropes[name] = StrungRope(ends[0], ends[1], length)
        return true
    }

    override fun releaseRope(self: LuaHandle.Node) {
        val (instance, index) = node(self) ?: return
        instance.ropes.remove(instance.definition.nodes[index].name)
    }

    override fun tween(self: LuaHandle.Node, options: TweenOptions): Boolean {
        val targets = listOfNotNull(
            options.translation?.let { Channel.TRANSLATION to it },
            options.rotation?.let { Channel.ROTATION to it },
            options.scale?.let { Channel.SCALE to it }
        )
        if (targets.isEmpty()) throw LuaApiException("bad argument 'options' (a tween needs translation, rotation or scale)")
        if (options.ticks < 1) throw LuaApiException("bad argument 'options.ticks' (at least 1, got ${options.ticks})")
        val easing = options.easing?.let { name ->
            Easing.entries.firstOrNull { it.name.lowercase() == name }
                ?: throw LuaApiException(
                    "bad argument 'options.easing' (${Easing.entries.joinToString {
                        it.name.lowercase()
                    }}, got \"$name\")"
                )
        } ?: Easing.EASE_IN_OUT
        val (instance, index) = node(self) ?: return false
        if (instance.removed) return false
        val ticks = options.ticks.coerceAtMost(Int.MAX_VALUE / 2L).toInt()
        for ((channel, to) in targets) {
            instance.tween(Instance.Tween(index, channel, instance.rest(index, channel), to, ticks, easing, options.back == true))
        }
        return true
    }

    override fun isTweening(self: LuaHandle.Node): Boolean {
        val (instance, index) = node(self) ?: return false
        return instance.tweens.any { it.index == index }
    }

    override fun stopTween(self: LuaHandle.Node): Boolean {
        val (instance, index) = node(self) ?: return false
        return instance.tweens.removeAll { it.index == index }
    }

    // ---- coordinate spaces: through Instance.worldMatrix, so as shown now ----------

    override fun worldPosition(self: LuaHandle.Node): Vec3? {
        val (instance, index) = node(self) ?: return null
        return instance.worldMatrix(index).translation()
    }

    override fun worldRotation(self: LuaHandle.Node): Vec3? {
        val (instance, index) = node(self) ?: return null
        return Quat.fromBasis(instance.worldMatrix(index)).toEuler()
    }

    override fun toLocal(self: LuaHandle.Node, point: Vec3): Vec3? {
        val (instance, index) = node(self) ?: return null
        return instance.worldMatrix(index).invertAffine()?.transformPosition(point)
    }

    override fun toWorld(self: LuaHandle.Node, point: Vec3): Vec3? {
        val (instance, index) = node(self) ?: return null
        return instance.worldMatrix(index).transformPosition(point)
    }

    override fun worldDirection(self: LuaHandle.Node, direction: Vec3): Vec3? {
        val (instance, index) = node(self) ?: return null
        return Quat.fromBasis(instance.worldMatrix(index)).rotate(direction)
    }

    override fun lookAt(self: LuaHandle.Node, point: Vec3): Boolean {
        val (instance, index) = node(self) ?: return false
        // Where the point is from the node's origin, in the space its rotation is in: its parent's.
        val target = instance.parentWorldMatrix(index).invertAffine()?.transformPosition(point) ?: return false
        val forward = target - instance.get(index, Channel.TRANSLATION)
        if (forward.length() < 1e-9) return true
        instance.set(index, Channel.ROTATION, lookRotation(forward.normalized()))
        return true
    }

    override fun rotate(self: LuaHandle.Node, axis: Vec3, degrees: Double): Boolean {
        val length = axis.length()
        if (length == 0.0) throw LuaApiException("bad argument 'axis' (a rotation needs an axis that isn't zero)")
        val (instance, index) = node(self) ?: return false
        val half = degreesToRadians(degrees) / 2
        val k = axis * (sin(half) / length)
        // Pre-multiplied: the turn happens about the parent's axis, after the node's own rotation.
        val turned = Quat(k.x, k.y, k.z, cos(half)) * Quat.fromEuler(instance.get(index, Channel.ROTATION))
        instance.set(index, Channel.ROTATION, turned.normalized().toEuler())
        return true
    }

    override fun isVisible(self: LuaHandle.Node): Boolean {
        val (instance, index) = node(self) ?: return false
        return instance.visible(index)
    }

    override fun setVisible(self: LuaHandle.Node, visible: Boolean) {
        val (instance, index) = node(self) ?: return
        instance.setVisible(index, visible)
    }

    override fun isClickable(self: LuaHandle.Node): Boolean {
        val (instance, index) = node(self) ?: return false
        return instance.clickable(index)
    }

    override fun setClickable(self: LuaHandle.Node, clickable: Boolean) {
        val (instance, index) = node(self) ?: return
        instance.setClickable(index, clickable)
    }

    override fun displayKind(self: LuaHandle.Node): String? {
        val (instance, index) = node(self) ?: return null
        return when (instance.display(index)) {
            is BlockDisplay -> "block"
            is ItemDisplay -> "item"
            is ModelDisplay -> "model"
            is TextDisplay -> "text"
            null -> null
        }
    }

    override fun model(self: LuaHandle.Node): String? {
        val (instance, index) = node(self) ?: return null
        return (instance.display(index) as? ModelDisplay)?.model?.text?.let(session.names::spell)
    }

    override fun setModel(self: LuaHandle.Node, model: String): Boolean {
        val (instance, index) = node(self) ?: return false
        val current = instance.display(index) as? ModelDisplay ?: return false
        // A look, as the calling script names it (any loaded package's, as `ns:<pack>/<key>`); the server holds it as the project spells it.
        val name = session.names.entryName(RefKind.ITEM_MODEL, model)
        if (name != current.model.text) instance.setDisplay(index, current.copy(model = ResourceRef(name)))
        return true
    }

    override fun displayBlock(self: LuaHandle.Node): String? {
        val (instance, index) = node(self) ?: return null
        return (instance.display(index) as? BlockDisplay)?.block
    }

    override fun setDisplayBlock(self: LuaHandle.Node, block: String): Boolean {
        val (instance, index) = node(self) ?: return false
        val current = instance.display(index) as? BlockDisplay ?: return false
        val state = validBlock(block) ?: return false
        instance.setDisplay(index, current.copy(block = state.toString()))
        return true
    }

    /** A block state the server knows, with only properties the block has; null otherwise. */
    private fun validBlock(text: String): BlockState? {
        val state = BlockState.parse(text) ?: return null
        val info = session.platform.game.block(state.id) ?: return null
        if (state.properties.any { (name, value) -> info.properties[name]?.contains(value) != true }) return null
        return state
    }

    override fun displayText(self: LuaHandle.Node): String? {
        val (instance, index) = node(self) ?: return null
        return (instance.display(index) as? TextDisplay)?.text
    }

    override fun setDisplayText(self: LuaHandle.Node, text: String): Boolean {
        val (instance, index) = node(self) ?: return false
        val current = instance.display(index) as? TextDisplay ?: return false
        instance.setDisplay(index, current.copy(text = text))
        return true
    }

    override fun displayItem(self: LuaHandle.Node): ItemData? {
        val (instance, index) = node(self) ?: return null
        val display = instance.display(index) as? ItemDisplay ?: return null
        return instance.look(index).item ?: ItemData(ItemDef(display.item))
    }

    override fun setDisplayItem(self: LuaHandle.Node, item: ItemData): Boolean {
        val (instance, index) = node(self) ?: return false
        val current = instance.display(index) as? ItemDisplay ?: return false
        // The content names the kind (what a respawn builds); the look carries the whole item over it.
        instance.setDisplay(index, current.copy(item = item.def.kind))
        instance.setLook(index, instance.look(index).copy(item = item.copy(def = item.def.copy(count = null))))
        return true
    }

    override fun displayTextStyle(self: LuaHandle.Node): TextStyle? {
        val (instance, index) = node(self) ?: return null
        val text = instance.display(index) as? TextDisplay ?: return null
        return TextStyle(
            background = text.background,
            opacity = (instance.look(index).textOpacity ?: OPAQUE).toLong(),
            shadow = text.shadow ?: false,
            alignment = (text.alignment ?: TextAlignment.CENTER).name.lowercase(),
            lineWidth = (text.lineWidth ?: TextDisplay.DEFAULT_LINE_WIDTH).toLong(),
            seeThrough = text.seeThrough ?: false
        )
    }

    override fun setDisplayTextStyle(self: LuaHandle.Node, style: TextStyle): Boolean {
        val background = style.background?.also {
            if (!ARGB.matches(it)) throw LuaApiException("bad field 'style.background' (#AARRGGBB expected, got \"$it\")")
        }
        val opacity = style.opacity?.also {
            if (it !in 0..OPAQUE) throw LuaApiException("bad field 'style.opacity' (0 to 255 expected, got $it)")
        }
        val alignment = style.alignment?.let { name ->
            TextAlignment.entries.firstOrNull { it.name.lowercase() == name }
                ?: throw LuaApiException("bad field 'style.alignment' (\"center\", \"left\" or \"right\" expected, got \"$name\")")
        }
        val lineWidth = style.lineWidth?.also {
            if (it < 1 || it > Int.MAX_VALUE) throw LuaApiException("bad field 'style.line_width' (at least 1 expected, got $it)")
        }
        val (instance, index) = node(self) ?: return false
        val current = instance.display(index) as? TextDisplay ?: return false
        instance.setDisplay(
            index,
            current.copy(
                background = background,
                shadow = style.shadow,
                alignment = alignment,
                lineWidth = lineWidth?.toInt(),
                seeThrough = style.seeThrough
            )
        )
        instance.setLook(index, instance.look(index).copy(textOpacity = opacity?.toInt()?.takeIf { it != OPAQUE }))
        return true
    }

    override fun isGlowing(self: LuaHandle.Node): Boolean {
        val (instance, index) = node(self) ?: return false
        return instance.display(index) != null && instance.look(index).glowing
    }

    override fun setGlowing(self: LuaHandle.Node, glowing: Boolean): Boolean {
        val (instance, index) = node(self) ?: return false
        if (instance.display(index) == null) return false
        instance.setLook(index, instance.look(index).copy(glowing = glowing))
        return true
    }

    override fun glowColor(self: LuaHandle.Node): String? {
        val (instance, index) = node(self) ?: return null
        val display = instance.display(index)
        if (display == null || display is TextDisplay) return null
        return instance.look(index).glowColor?.let(::formatRgb)
    }

    override fun setGlowColor(self: LuaHandle.Node, color: String?): Boolean {
        val rgb = color?.let { parseRgb(it, "color") }
        val (instance, index) = node(self) ?: return false
        val display = instance.display(index)
        // Minecraft draws no glow colour on a text display.
        if (display == null || display is TextDisplay) return false
        instance.setLook(index, instance.look(index).copy(glowColor = rgb))
        return true
    }

    override fun brightness(self: LuaHandle.Node): Brightness? {
        val (instance, index) = node(self) ?: return null
        if (instance.display(index) == null) return null
        return instance.look(index).brightness?.let { Brightness(it.block.toLong(), it.sky.toLong()) }
    }

    override fun setBrightness(self: LuaHandle.Node, brightness: Brightness?): Boolean {
        val levels = brightness?.let {
            fun level(value: Long?, field: String): Int {
                if (value == null) throw LuaApiException("bad field 'brightness.$field' (a light level from 0 to 15 is required)")
                if (value !in 0..15) throw LuaApiException("bad field 'brightness.$field' (0 to 15 expected, got $value)")
                return value.toInt()
            }
            DisplayBrightness(level(it.blockLight, "block_light"), level(it.skyLight, "sky_light"))
        }
        val (instance, index) = node(self) ?: return false
        if (instance.display(index) == null) return false
        instance.setLook(index, instance.look(index).copy(brightness = levels))
        return true
    }

    override fun billboard(self: LuaHandle.Node): String? {
        val (instance, index) = node(self) ?: return null
        if (instance.display(index) == null) return null
        return instance.billboard(index).name.lowercase()
    }

    override fun setBillboard(self: LuaHandle.Node, billboard: String): Boolean {
        val value = Billboard.entries.firstOrNull { it.name.lowercase() == billboard }
            ?: throw LuaApiException(
                "bad argument 'billboard' (\"fixed\", \"vertical\", \"horizontal\" or \"center\" expected, got \"$billboard\")"
            )
        val (instance, index) = node(self) ?: return false
        if (instance.display(index) == null) return false
        instance.setLook(index, instance.look(index).copy(billboard = value))
        return true
    }

    override fun interpolationTicks(self: LuaHandle.Node): Long? {
        val (instance, index) = node(self) ?: return null
        return instance.interpolation(index).toLong()
    }

    override fun setInterpolationTicks(self: LuaHandle.Node, ticks: Long): Boolean {
        if (ticks < 0 || ticks > Int.MAX_VALUE) throw LuaApiException("bad argument 'ticks' (at least 0 expected, got $ticks)")
        val (instance, index) = node(self) ?: return false
        instance.setInterpolation(index, ticks.toInt())
        return true
    }

    override fun velocity(self: LuaHandle.Node): Vec3? {
        val (instance, index) = node(self) ?: return null
        return instance.body(index)?.velocity ?: Vec3.ZERO
    }

    override fun angularVelocity(self: LuaHandle.Node): Vec3? {
        val (instance, index) = node(self) ?: return null
        return instance.body(index)?.angularVelocity?.let(Physics::toDegrees) ?: Vec3.ZERO
    }

    override fun setVelocity(self: LuaHandle.Node, velocity: Vec3) = motion(self, velocity, spin = false, add = false)

    override fun addVelocity(self: LuaHandle.Node, velocity: Vec3) = motion(self, velocity, spin = false, add = true)

    override fun setAngularVelocity(self: LuaHandle.Node, angularVelocity: Vec3) = motion(self, angularVelocity, spin = true, add = false)

    /** Sets or adds to a body's velocity, or (in degrees) its spin, and wakes it. */
    private fun motion(self: LuaHandle.Node, value: Vec3, spin: Boolean, add: Boolean): Boolean {
        val (instance, index) = node(self) ?: return false
        val body = instance.body(index) ?: return false
        if (spin) {
            val radians = Physics.toRadians(value)
            body.angularVelocity = if (add) body.angularVelocity + radians else radians
        } else {
            body.velocity = if (add) body.velocity + value else value
        }
        body.wake()
        return true
    }

    override fun mass(self: LuaHandle.Node): Double {
        val (instance, index) = node(self) ?: return 0.0
        return session.centities.physics.bodyOf(instance, index)?.mass ?: 0.0
    }

    // Physics runs along the world's axes, relative to the anchor; only the point needs moving.
    override fun applyImpulse(self: LuaHandle.Node, impulse: Vec3): Boolean {
        val (instance, index) = node(self) ?: return false
        return session.centities.physics.impulse(instance, index, impulse, null)
    }

    override fun applyImpulseAt(self: LuaHandle.Node, impulse: Vec3, point: Vec3): Boolean {
        val (instance, index) = node(self) ?: return false
        return session.centities.physics.impulse(instance, index, impulse, instance.fromAnchor(point))
    }

    override fun applyForce(self: LuaHandle.Node, force: Vec3): Boolean {
        val (instance, index) = node(self) ?: return false
        // A force for one tick is the impulse it adds up to over that tick.
        return session.centities.physics.impulse(instance, index, force * Centities.SECONDS_PER_TICK, null)
    }

    override fun hasGravity(self: LuaHandle.Node): Boolean {
        val (instance, index) = node(self) ?: return false
        return instance.body(index)?.gravity == true
    }

    override fun setGravity(self: LuaHandle.Node, gravity: Boolean): Boolean = body(self) {
        it.gravity = gravity
        it.wake()
    }

    override fun isKinematic(self: LuaHandle.Node): Boolean {
        val (instance, index) = node(self) ?: return false
        return instance.body(index)?.kinematic == true
    }

    override fun setKinematic(self: LuaHandle.Node, kinematic: Boolean): Boolean = body(self) {
        it.kinematic = kinematic
        it.wake()
    }

    override fun isPhysicsEnabled(self: LuaHandle.Node): Boolean {
        val (instance, index) = node(self) ?: return false
        return instance.body(index)?.enabled == true
    }

    override fun setPhysicsEnabled(self: LuaHandle.Node, enabled: Boolean): Boolean = body(self) {
        it.enabled = enabled
        it.wake()
    }

    /** Changes a node's body; false for a node without `physics`. */
    private fun body(self: LuaHandle.Node, change: (BodyState) -> Unit): Boolean {
        val (instance, index) = node(self) ?: return false
        val body = instance.body(index) ?: return false
        change(body)
        return true
    }

    override fun isOnGround(self: LuaHandle.Node): Boolean {
        val (instance, index) = node(self) ?: return false
        return instance.body(index)?.onGround == true
    }

    override fun isAsleep(self: LuaHandle.Node): Boolean {
        val (instance, index) = node(self) ?: return false
        return instance.body(index)?.asleep == true
    }

    override fun wake(self: LuaHandle.Node) {
        val (instance, index) = node(self) ?: return
        instance.body(index)?.wake()
    }
}
