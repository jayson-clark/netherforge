package dev.netherforge.plugin.bridge

import dev.netherforge.format.bridge.BridgeEvent
import dev.netherforge.format.bridge.BridgeStream
import dev.netherforge.format.bridge.ConsoleEntry

/** What the runtime tells the editor, unasked. Safe from any thread; never blocks. */
interface BridgeOutput {
    /** A line for the editor's console, on the batched `console` stream. */
    fun console(entry: ConsoleEntry)

    /** An item for [stream], batched with those waiting ([item] mustn't change after either). */
    fun <T> stream(stream: BridgeStream<T>, item: T)

    /** A notification ([params] mustn't change after: it's encoded later, on the bridge's thread). */
    fun <P> notify(event: BridgeEvent<P>, params: P)
}
