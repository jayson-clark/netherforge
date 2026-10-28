package dev.netherforge.plugin

import dev.netherforge.format.bridge.BridgeEvent
import dev.netherforge.format.bridge.BridgeStream
import dev.netherforge.format.bridge.ConsoleEntry
import dev.netherforge.format.bridge.Log
import dev.netherforge.format.bridge.LogLevel
import dev.netherforge.format.bridge.SourceRef
import dev.netherforge.plugin.bridge.BridgeOutput
import dev.netherforge.plugin.platform.PlatformLog

/**
 * The runtime's log: every line goes to the server console, and to the
 * editor's console stream too when a bridge is connected. A script's `log()`
 * keeps the file and line it was called from, so the editor can open it.
 */
class RuntimeLog(private val console: PlatformLog) {
    /** Where what the editor hears goes; set while a bridge exists. */
    @Volatile
    var bridge: BridgeOutput? = null

    fun info(message: String) {
        console.info(message)
        bridge?.console(Log(LogLevel.INFO, message))
    }

    fun warn(message: String) {
        console.warn(message)
        bridge?.console(Log(LogLevel.WARN, message))
    }

    fun error(message: String, cause: Throwable? = null) {
        console.error(message, cause)
        bridge?.console(Log(LogLevel.ERROR, message + (cause?.let { ": ${it.message ?: it::class.simpleName}" }.orEmpty())))
    }

    /** A line from a script. [label] names the script in the console. */
    fun script(label: String, message: String, source: SourceRef?) {
        console.info("[$label] $message")
        bridge?.console(Log(LogLevel.INFO, message, source))
    }

    /** An entry for the editor's console only (a script error, which the runtime logs its own way). */
    fun send(entry: ConsoleEntry) {
        bridge?.console(entry)
    }

    /** An item on a stream of the bridge's (the profiler's samples). */
    fun <T> stream(stream: BridgeStream<T>, item: T) {
        bridge?.stream(stream, item)
    }

    fun <P> notify(event: BridgeEvent<P>, params: P) {
        bridge?.notify(event, params)
    }
}
