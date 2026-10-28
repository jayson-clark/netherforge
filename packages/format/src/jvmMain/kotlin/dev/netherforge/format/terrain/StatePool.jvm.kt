package dev.netherforge.format.terrain

import java.util.concurrent.ConcurrentLinkedQueue

internal actual class StatePool<T : Any> actual constructor() {
    private val items = ConcurrentLinkedQueue<T>()

    actual fun take(): T? = items.poll()

    actual fun give(item: T) {
        items.add(item)
    }
}
