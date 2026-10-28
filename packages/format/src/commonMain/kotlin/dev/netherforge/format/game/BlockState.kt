package dev.netherforge.format.game

/** Namespaced-id helpers shared by everything that names a game object. */
object GameIds {
    /** The game's own namespace: what an id without one is in, and what a reference to one of the game's own is written in. */
    const val NAMESPACE = "minecraft"

    private val ID = Regex("""^([a-z0-9_.-]+:)?[a-z0-9_./-]+$""")

    fun isValid(id: String): Boolean = ID.matches(id)

    /** `stone` → `minecraft:stone`; an id that already has a namespace is left alone. */
    fun normalize(id: String): String = if (':' in id) id else "$NAMESPACE:$id"
}

/**
 * A block id with property values: `minecraft:oak_stairs[facing=east,half=top]`.
 *
 * [toString] is canonical (namespaced, properties sorted by name), which is
 * what [GameDataBundle] keys shapes by.
 */
data class BlockState(val id: String, val properties: Map<String, String> = emptyMap()) {

    override fun toString(): String = if (properties.isEmpty()) {
        id
    } else {
        properties.entries.sortedBy { it.key }.joinToString(",", "$id[", "]") { "${it.key}=${it.value}" }
    }

    /** This state with every unspecified property filled from [info]'s defaults. */
    fun withDefaults(info: BlockInfo): BlockState = BlockState(id, info.defaults + properties)

    companion object {
        private val PATTERN = Regex("""^([^\[\]]+)(?:\[([^\[\]]*)\])?$""")

        /** Null when [text] isn't shaped like a block state at all. */
        fun parse(text: String): BlockState? {
            val match = PATTERN.matchEntire(text.trim()) ?: return null
            val id = match.groupValues[1].trim()
            if (!GameIds.isValid(id)) return null
            val body = match.groupValues[2]
            val properties = LinkedHashMap<String, String>()
            if (body.isNotBlank()) {
                for (pair in body.split(',')) {
                    val parts = pair.split('=')
                    if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
                    properties[parts[0].trim()] = parts[1].trim()
                }
            }
            return BlockState(GameIds.normalize(id), properties)
        }
    }
}
