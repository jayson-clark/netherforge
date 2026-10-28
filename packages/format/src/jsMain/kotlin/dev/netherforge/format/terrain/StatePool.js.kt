package dev.netherforge.format.terrain

/** JS has one thread: a list is enough. */
internal actual class StatePool<T : Any> actual constructor() {
    private val items = ArrayDeque<T>()

    actual fun take(): T? = items.removeLastOrNull()

    actual fun give(item: T) {
        items.addLast(item)
    }
}
