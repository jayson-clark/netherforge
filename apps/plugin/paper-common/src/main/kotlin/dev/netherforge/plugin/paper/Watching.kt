package dev.netherforge.plugin.paper

import dev.netherforge.plugin.platform.WatchedEvent
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.java.JavaPlugin
import java.util.EnumSet

/**
 * A handler for a watched event ([WatchedEvent], from the API spec): in place
 * of `@EventHandler`, which would register it for good. [Watching] registers it
 * only while a script listens, since the server raises these all the time and
 * a listener nobody needs still costs.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Watched(val event: WatchedEvent, val priority: EventPriority = EventPriority.NORMAL, val ignoreCancelled: Boolean = false)

/**
 * Registers and unregisters [listener]'s [Watched] handlers as the runtime
 * says (`Platform.watch`). Every [WatchedEvent] needs exactly one: a watched
 * event in the spec without its handler fails here, when the plugin starts.
 */
class Watching(private val plugin: JavaPlugin, private val listener: Listener) {
    private class Handler(val type: Class<out Event>, val executor: EventExecutor, val watched: Watched)

    private val handlers: Map<WatchedEvent, Handler> = run {
        val found = listener.javaClass.methods.mapNotNull { method ->
            val watched = method.getAnnotation(Watched::class.java) ?: return@mapNotNull null
            val type = method.parameterTypes.singleOrNull()?.takeIf(Event::class.java::isAssignableFrom)
                ?: error("@Watched ${method.name} must take one event")
            val event = type.asSubclass(Event::class.java)
            watched.event to Handler(event, EventExecutor.create(method, event), watched)
        }
        val twice = found.groupBy({ it.first }).filterValues { it.size > 1 }.keys
        check(twice.isEmpty()) { "More than one @Watched handler for $twice" }
        val missing = WatchedEvent.entries - found.map { it.first }.toSet()
        check(missing.isEmpty()) { "No @Watched handler for $missing" }
        found.toMap()
    }

    /**
     * Each watched event's own key in the server's handler lists, so it can be
     * unregistered alone; its executor calls [listener]'s handler.
     */
    private val keys: Map<WatchedEvent, Listener> = WatchedEvent.entries.associateWith { object : Listener {} }

    private val registered = EnumSet.noneOf(WatchedEvent::class.java)

    /** Starts or stops delivering [event]. On the main thread. */
    fun watch(event: WatchedEvent, listening: Boolean) {
        if (listening == event in registered) return
        val key = keys.getValue(event)
        if (!listening) {
            registered -= event
            HandlerList.unregisterAll(key)
            return
        }
        registered += event
        val handler = handlers.getValue(event)
        val call = EventExecutor { _, raised -> handler.executor.execute(listener, raised) }
        plugin.server.pluginManager.registerEvent(
            handler.type,
            key,
            handler.watched.priority,
            call,
            plugin,
            handler.watched.ignoreCancelled
        )
    }
}
