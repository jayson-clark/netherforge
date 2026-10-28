package dev.netherforge.format.centity

import dev.netherforge.format.math.Vec3

/**
 * Builds a [CompiledCentity] from a file [CentityValidator] passed without
 * errors. Throws [IllegalArgumentException] if handed one that didn't: that is
 * a caller bug, not a user mistake.
 */
object CentityCompiler {

    private const val MIN_LENGTH = 1e-4

    /** One node as it runs: [parentIndex] is its parent's place in the list it joins, or -1 for a root. A script adds nodes this way. */
    fun compileNode(name: String, def: NodeDef, parentIndex: Int) = CompiledCentity.Node(
        name = name,
        parentIndex = parentIndex,
        transform = def.transform ?: Transform.IDENTITY,
        display = def.display,
        hitbox = def.hitbox,
        physics = def.physics?.let { ResolvedPhysics.of(it) }
    )

    fun compile(id: String, file: CentityFile): CompiledCentity {
        require(file.nodes.isNotEmpty()) { "centity \"$id\" has no nodes" }
        require(CentityValidator.cyclic(file).isEmpty()) { "centity \"$id\" has a parent cycle" }

        // Parents first; siblings in name order so the result never depends on map order.
        val order = ArrayList<String>(file.nodes.size)
        val placed = HashSet<String>()
        val children = file.nodes.entries.groupBy({ it.value.parent }, { it.key }).mapValues { it.value.sorted() }
        val queue = ArrayDeque(children[null].orEmpty())
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (!placed.add(name)) continue
            order += name
            queue.addAll(children[name].orEmpty())
        }
        require(order.size == file.nodes.size) { "centity \"$id\" has nodes with unknown parents" }

        val index = order.withIndex().associate { (i, name) -> name to i }
        val nodes = order.map { name ->
            val def = file.nodes.getValue(name)
            compileNode(name, def, def.parent?.let { index.getValue(it) } ?: -1)
        }.toMutableList()
        // A rope draws nothing itself: its display is its links', children of it after every other node, laid
        // straight along its Z until a script strings it.
        for ((ropeIndex, name) in order.withIndex()) {
            val rope = file.nodes.getValue(name).rope ?: continue
            val node = nodes[ropeIndex]
            val length = rope.linkLength ?: RopeDef.DEFAULT_LINK_LENGTH
            val links = (1..rope.links).map { link ->
                nodes += CompiledCentity.Node(
                    name = RopeDef.linkName(name, link),
                    parentIndex = ropeIndex,
                    transform = Transform(translation = Vec3(0.0, 0.0, (link - 0.5) * length), scale = Vec3(1.0, 1.0, length)),
                    display = node.display,
                    hitbox = null,
                    physics = null
                )
                nodes.size - 1
            }
            nodes[ropeIndex] =
                CompiledCentity.Node(name, node.parentIndex, node.transform, null, null, null, CompiledCentity.Rope(links, length))
        }

        val animations = file.animations.entries.sortedBy { it.key }.map { (name, clip) ->
            val tracks = clip.tracks.entries.sortedBy { it.key }.flatMap { (node, channels) ->
                val nodeIndex = index[node] ?: throw IllegalArgumentException("animation \"$name\" drives unknown node \"$node\"")
                channels.entries.sortedBy { it.key.ordinal }.mapNotNull { (channel, keys) ->
                    if (keys.isEmpty()) null else CompiledAnimation.Track(nodeIndex, channel, keys.sortedBy { it.time })
                }
            }
            val lastKey = tracks.maxOfOrNull { it.keys.last().time } ?: 0.0
            CompiledAnimation(
                name = name,
                length = (clip.length ?: lastKey).coerceAtLeast(MIN_LENGTH),
                loop = clip.loop ?: LoopMode.ONCE,
                autoplay = clip.autoplay ?: false,
                tracks = tracks,
                blend = clip.blend ?: AnimationBlend.REPLACE
            )
        }

        return CompiledCentity(
            id,
            file.name,
            nodes,
            animations,
            file.script,
            file.spawning,
            file.body?.let(ResolvedBody::of),
            file.health?.let(ResolvedHealth::of),
            file.properties
        )
    }
}
