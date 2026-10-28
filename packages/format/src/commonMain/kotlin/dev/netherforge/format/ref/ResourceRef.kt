package dev.netherforge.format.ref

import dev.netherforge.format.project.Names
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.jvm.JvmInline

/**
 * A reference from one resource to another, as a file or a script writes it:
 * `ruby` for one in the project's own namespace, `acme:ruby` for one in
 * another (a package's). A pack's assets are named by pack and key: `ui/coin`,
 * `acme:ui/coin`.
 *
 * Any text reads as one; whether it's well formed for what it names is
 * [RefKind.isPath]'s question, asked where it's checked, so a typo is a
 * problem at its JSON path rather than a file that doesn't parse.
 */
@Serializable(with = ResourceRefSerializer::class)
@JvmInline
value class ResourceRef(val text: String) {
    /** The namespace written before `:`, or null when it names one of the project's own. */
    val namespace: String? get() = text.indexOf(':').takeIf { it >= 0 }?.let { text.substring(0, it) }

    /** What follows the namespace: the id, or `<pack>/<key>`. */
    val path: String get() = text.substringAfter(':')

    /** The key this names from inside namespace [home], or null when it isn't shaped like one. */
    fun resolve(home: String): ResourceKey? {
        if (text.count { it == ':' } > 1) return null
        val namespace = namespace ?: home
        if (namespace != home && !Names.isNamespace(namespace)) return null
        if (!RESOURCE_PATH.matches(path)) return null
        return ResourceKey(namespace, path)
    }

    /** The id this names among [home]'s own resources (`ruby`, `shop:ruby`), or null for another namespace's, or no id. */
    fun idIn(home: String): String? = resolve(home)?.takeIf { it.namespace == home && Names.isId(it.path) }?.path

    /**
     * The one name a server running [home] with its packages knows what
     * this names by: `ruby` for [home]'s own (written `ruby` or `shop:ruby`),
     * `acme:coin` for a package's. Null when it isn't shaped like a reference.
     */
    fun nameIn(home: String): String? = resolve(home)?.relativeTo(home)?.text

    override fun toString(): String = text

    companion object {
        /** Id segments joined by `/`: what may follow a namespace. Each kind narrows it ([RefKind.isPath]). */
        val RESOURCE_PATH = Regex("^[a-z0-9][a-z0-9_]{0,63}(/[a-z0-9][a-z0-9_]{0,63})*$")
    }
}

/** A reference resolved: always namespaced, `shop:ruby`, `shop:ui/coin`. */
data class ResourceKey(val namespace: String, val path: String) {
    /** How a file in namespace [home] writes this: without the namespace when it's [home]. */
    fun relativeTo(home: String): ResourceRef = ResourceRef(if (namespace == home) path else toString())

    /** For a pack asset: the pack (`ui`), the first segment of [path]. */
    val pack: String get() = path.substringBefore('/')

    /** For a pack asset: the key inside its pack (`coin`, `menu/open`). */
    val key: String get() = path.substringAfter('/')

    override fun toString(): String = "$namespace:$path"

    companion object {
        /** `namespace:path` as a key (what the server persists), or null when it isn't one. */
        fun parse(text: String): ResourceKey? = ResourceRef(text).takeIf { it.namespace != null }?.resolve("")
    }
}

/** A [ResourceRef] is the string it was written as. */
object ResourceRefSerializer : KSerializer<ResourceRef> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("dev.netherforge.format.ref.ResourceRef", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ResourceRef) = encoder.encodeString(value.text)

    override fun deserialize(decoder: Decoder): ResourceRef = ResourceRef(decoder.decodeString())
}
