package dev.netherforge.plugin.centity

import dev.netherforge.format.centity.CentityCompiler
import dev.netherforge.format.centity.NodeDef
import dev.netherforge.format.centity.ResolvedHealth
import dev.netherforge.format.centity.TextDisplay
import dev.netherforge.format.centity.Transform
import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.binding.CentityDamageEvent
import dev.netherforge.plugin.binding.CentityDeathEvent
import dev.netherforge.plugin.binding.Events
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.script.Scripts
import kotlin.math.ceil

/**
 * A centity's health as it runs (`centity.json`'s `health`, or `set_max_health`'s): what it has and its most,
 * whether it's dead, and until when its bar shows. Runtime state on [Instance.health]; not saved.
 */
internal class Health(var max: Double, var current: Double, val removeOnDeath: Boolean, val bar: ResolvedHealth.Bar?) {
    var dead = false

    /** The server tick its bar hides at, or null while it's hidden (or always shown). */
    var shownUntil: Long? = null

    companion object {
        fun of(resolved: ResolvedHealth) = Health(resolved.max, resolved.max, resolved.removeOnDeath, resolved.bar)
    }
}

/**
 * Damage, healing and death for centities with health, and their bars: a text node (the file's own
 * [ResolvedHealth.BAR_NODE], or one added over its hitboxes) whose text is the bar, shown for a while after a
 * change.
 */
internal class Healths(private val scripts: Scripts, private val centities: Centities) {
    private fun handle(instance: Instance) = LuaHandle.Centity(instance.id.toString())

    /** Gives a new or reloaded [instance] the health its file says, keeping what it had (within the new most). */
    fun adopt(instance: Instance) {
        val resolved = instance.definition.health
        val had = instance.health
        instance.health = resolved?.let { r ->
            Health.of(r).also { fresh ->
                if (had != null) {
                    fresh.current = had.current.coerceAtMost(fresh.max)
                    fresh.dead = had.dead
                }
            }
        }
        val health = instance.health ?: return
        if (health.bar != null) {
            barNode(instance)
            draw(instance, health, show = false)
        }
    }

    /** Deals [amount] through the `damage` handlers; what was taken, or null when it has no health or is dead. */
    fun damage(instance: Instance, amount: Double, source: LuaHandle?, cause: String?): Double? {
        val health = instance.health ?: return null
        if (health.dead) return null
        val event = CentityDamageEvent(handle(instance), amount, source, cause)
        val cancelled = scripts.emit(Events.CENTITY_DAMAGE, handle(instance), event)
        if (instance.removed || instance.health !== health || health.dead) return null
        if (cancelled) return 0.0
        val taken = event.amount.coerceAtLeast(0.0).coerceAtMost(health.current).let { if (it.isFinite()) it else 0.0 }
        health.current -= taken
        draw(instance, health, show = true)
        if (health.current <= 0.0) {
            health.current = 0.0
            health.dead = true
            scripts.emit(Events.CENTITY_DEATH, handle(instance), CentityDeathEvent(handle(instance), source, cause))
            if (health.removeOnDeath && !instance.removed && health.dead) centities.remove(instance)
        }
        return taken
    }

    fun heal(instance: Instance, amount: Double): Double? {
        val health = instance.health ?: return null
        val gained = amount.coerceAtLeast(0.0).coerceAtMost(health.max - health.current)
        health.current += gained
        if (health.current > 0) health.dead = false
        draw(instance, health, show = gained > 0)
        return gained
    }

    fun set(instance: Instance, value: Double) {
        val health = instance.health ?: return
        health.current = value.coerceIn(0.0, health.max)
        if (health.current > 0) health.dead = false
        draw(instance, health, show = true)
    }

    fun setMax(instance: Instance, max: Double) {
        val health = instance.health ?: Health(max, max, removeOnDeath = true, bar = null).also { instance.health = it }
        health.max = max
        health.current = health.current.coerceAtMost(max)
        draw(instance, health, show = true)
    }

    /** Hides bars whose time to show is up: once a tick, from the centity pass. */
    fun hideBar(instance: Instance, now: Long) {
        val health = instance.health ?: return
        val until = health.shownUntil ?: return
        if (now < until) return
        health.shownUntil = null
        instance.indexOf(ResolvedHealth.BAR_NODE)?.let { instance.setVisible(it, false) }
    }

    /** Writes the bar's text and shows it (for its `showTicks`, or always), or keeps it as it is shown or hidden. */
    private fun draw(instance: Instance, health: Health, show: Boolean) {
        val bar = health.bar ?: return
        val index = instance.indexOf(ResolvedHealth.BAR_NODE) ?: return
        val display = instance.display(index) as? TextDisplay ?: return
        val full = if (health.max <= 0) 0 else ceil(health.current / health.max * bar.segments).toInt().coerceIn(0, bar.segments)
        val text = bar.filled + bar.glyph.repeat(full) + bar.empty + bar.glyph.repeat(bar.segments - full)
        if (display.text != text) instance.setDisplay(index, display.copy(text = text))
        when {
            bar.showTicks == 0 -> instance.setVisible(index, true)
            show -> {
                instance.setVisible(index, true)
                health.shownUntil = scripts.now + bar.showTicks
            }
            health.shownUntil == null -> instance.setVisible(index, false)
        }
    }

    /** The bar's node: the file's own text node of [ResolvedHealth.BAR_NODE]'s name, or one added a little over its hitboxes. */
    private fun barNode(instance: Instance) {
        if (instance.indexOf(ResolvedHealth.BAR_NODE) != null) return
        val bar = instance.health?.bar ?: return
        val top = centities.obstacles.of(instance).bounds?.max?.y ?: 0.0
        val node = NodeDef(
            transform = Transform(translation = Vec3(0.0, top + bar.offset, 0.0), scale = Vec3(BAR_SCALE, BAR_SCALE, BAR_SCALE)),
            display = TextDisplay(text = "", background = "#00000000", shadow = true)
        )
        centities.addNode(instance, CentityCompiler.compileNode(ResolvedHealth.BAR_NODE, node, -1))
    }

    private companion object {
        /** The added bar's text, a little smaller than a name tag. */
        const val BAR_SCALE = 0.7
    }
}
