package dev.netherforge.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The network's shared maps, held by the hub: `namespace:name` → key → value, each value with when it expires (if
 * it does). Every package is held to [NetworkLimits.maxKeysPerPackage] and [NetworkLimits.maxBytesPerPackage]
 * across its maps. Not thread-safe: the [Hub] calls it under its lock.
 */
class SharedMaps(private val limits: NetworkLimits, private val now: () -> Long) {
    class Entry(val value: JsonElement, val bytes: Int, val expiresAt: Long?)

    private val maps = HashMap<String, LinkedHashMap<String, Entry>>()
    private val keys = HashMap<String, Int>()
    private val bytes = HashMap<String, Long>()

    /** Whether anything changed since [takeDirty] last said so: what the hub saves. */
    private var dirty = false

    /** What a write did: the value now (null: none), or why it was refused. */
    sealed interface Outcome {
        data class Changed(val value: JsonElement?, val result: JsonElement) : Outcome

        /** Nothing changed (`insert` of a key that's there, `delete` of one that isn't). */
        data class Unchanged(val result: JsonElement) : Outcome

        data class Refused(val why: String) : Outcome
    }

    fun entries(map: String): Map<String, JsonElement> = maps[map].orEmpty().mapValues { it.value.value }

    fun write(write: ServerFrame.MapWrite): Outcome {
        val map = write.map
        val key = write.key
        val existing = maps[map]?.get(key)
        val expiresAt = write.expiresMillis?.let { now() + it }
        return when (write.op) {
            MapOp.SET -> {
                val value = write.value ?: return Outcome.Refused("a set needs a value")
                put(map, key, value, expiresAt)?.let { Outcome.Refused(it) } ?: Outcome.Changed(value, JsonPrimitive(true))
            }
            MapOp.INSERT -> {
                val value = write.value ?: return Outcome.Refused("an insert needs a value")
                if (existing != null) return Outcome.Unchanged(JsonPrimitive(false))
                put(map, key, value, expiresAt)?.let { Outcome.Refused(it) } ?: Outcome.Changed(value, JsonPrimitive(true))
            }
            MapOp.DELETE -> if (remove(map, key)) Outcome.Changed(null, JsonPrimitive(true)) else Outcome.Unchanged(JsonPrimitive(false))
            MapOp.ADD -> {
                val amount = write.amount?.takeIf { it.isFinite() } ?: return Outcome.Refused("an add needs a finite amount")
                val current = when (val value = existing?.value) {
                    null -> 0.0
                    is JsonPrimitive -> value.takeUnless { it.isString }?.doubleOrNull
                        ?: return Outcome.Refused("\"$key\" holds something that isn't a number")
                    else -> return Outcome.Refused("\"$key\" holds something that isn't a number")
                }
                val sum = current + amount
                val value = number(sum)
                // An add keeps the expiry the key had, unless it gives one.
                put(map, key, value, expiresAt ?: existing?.expiresAt)?.let { Outcome.Refused(it) } ?: Outcome.Changed(value, value)
            }
        }
    }

    /** Takes out every key past its expiry, as (map, key) pairs. */
    fun expire(): List<Pair<String, String>> {
        val time = now()
        val gone = ArrayList<Pair<String, String>>()
        for ((map, entries) in maps) {
            for ((key, entry) in entries) if (entry.expiresAt != null && entry.expiresAt <= time) gone += map to key
        }
        for ((map, key) in gone) remove(map, key)
        return gone
    }

    /** Sets [key], or says why it can't be. */
    private fun put(map: String, key: String, value: JsonElement, expiresAt: Long?): String? {
        val size = Protocol.size(value)
        if (size > limits.maxValueBytes) return "the value is $size bytes, over the network's limit of ${limits.maxValueBytes}"
        val namespace = NetworkNames.namespaceOf(map)
        val entries = maps.getOrPut(map, ::LinkedHashMap)
        val previous = entries[key]
        val keysAfter = keys.getOrDefault(namespace, 0) + (if (previous == null) 1 else 0)
        val bytesAfter = bytes.getOrDefault(namespace, 0) + size - (previous?.bytes ?: 0)
        if (keysAfter > limits.maxKeysPerPackage) {
            if (entries.isEmpty()) maps.remove(map)
            return "package \"$namespace\" already keeps ${limits.maxKeysPerPackage} keys in its maps, the network's limit"
        }
        if (bytesAfter > limits.maxBytesPerPackage) {
            if (entries.isEmpty()) maps.remove(map)
            return "package \"$namespace\" would keep $bytesAfter bytes in its maps, " +
                "over the network's limit of ${limits.maxBytesPerPackage}"
        }
        entries[key] = Entry(value, size, expiresAt)
        keys[namespace] = keysAfter
        bytes[namespace] = bytesAfter
        dirty = true
        return null
    }

    private fun remove(map: String, key: String): Boolean {
        val entries = maps[map] ?: return false
        val entry = entries.remove(key) ?: return false
        if (entries.isEmpty()) maps.remove(map)
        val namespace = NetworkNames.namespaceOf(map)
        keys[namespace] = keys.getOrDefault(namespace, 1) - 1
        bytes[namespace] = bytes.getOrDefault(namespace, entry.bytes.toLong()) - entry.bytes
        dirty = true
        return true
    }

    /** Whether there's anything to save, clearing it. */
    fun takeDirty(): Boolean = dirty.also { dirty = false }

    /** Everything, as it's saved. */
    fun snapshot(): SavedMaps = SavedMaps(
        maps.mapValues { (_, entries) -> entries.mapValues { (_, entry) -> SavedEntry(entry.value, entry.expiresAt) } }
    )

    /** Puts back what [saved] holds, leaving out what has expired meanwhile and what's over the limits now. */
    fun restore(saved: SavedMaps) {
        val time = now()
        for ((map, entries) in saved.maps) {
            if (!NetworkNames.isFull(map)) continue
            for ((key, entry) in entries) {
                if (entry.expiresAt != null && entry.expiresAt <= time) continue
                put(map, key, entry.value, entry.expiresAt)
            }
        }
        dirty = false
    }

    companion object {
        /** [value] as JSON: a whole number without a fraction, as Lua would write it. */
        fun number(value: Double): JsonPrimitive =
            if (value == Math.rint(value) && Math.abs(value) < 1e15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
    }
}

@Serializable
data class SavedEntry(val value: JsonElement, val expiresAt: Long? = null)

@Serializable
data class SavedMaps(val maps: Map<String, Map<String, SavedEntry>> = emptyMap(), val version: Int = 1)

/** Where the hub keeps its maps between runs. */
interface MapStore {
    fun load(): SavedMaps?

    fun save(maps: SavedMaps)
}

/** A JSON file, written whole to a temporary file beside it and moved over it, so a crash never leaves half of one. */
class FileMapStore(private val file: Path) : MapStore {
    override fun load(): SavedMaps? {
        if (!Files.exists(file)) return null
        return Protocol.json.decodeFromString(SavedMaps.serializer(), Files.readString(file))
    }

    override fun save(maps: SavedMaps) {
        Files.createDirectories(file.parent)
        val temporary = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temporary, Protocol.json.encodeToString(SavedMaps.serializer(), maps))
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
