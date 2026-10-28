package dev.netherforge.plugin.paper.version

import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property

/**
 * Hears the report the server logs when its registries fail to load (`RegistryDataLoader`: `Registry loading
 * errors:` and, per registry and entry, what the game couldn't read), through an appender on the server's own
 * Log4j: the game raises no event for it, and gives up starting right after. The same on every supported server.
 */
internal object RegistryErrorLog {
    /** How the game's report starts, on every supported version. */
    private const val REPORT = "Registry loading errors:"

    fun watch(refused: (String) -> Unit): AutoCloseable {
        val context = LogManager.getContext(false) as LoggerContext
        val appender = object : AbstractAppender("NetherForgeRegistryErrors", null, null, true, Property.EMPTY_ARRAY) {
            override fun append(event: LogEvent) {
                val message = event.message?.formattedMessage ?: return
                if (event.level.isMoreSpecificThan(Level.ERROR) && message.startsWith(REPORT)) {
                    refused(message.removePrefix(REPORT).trim())
                }
            }
        }
        appender.start()
        val root = context.configuration.rootLogger
        root.addAppender(appender, Level.ERROR, null)
        context.updateLoggers()
        return AutoCloseable {
            root.removeAppender(appender.name)
            context.updateLoggers()
            appender.stop()
        }
    }
}
