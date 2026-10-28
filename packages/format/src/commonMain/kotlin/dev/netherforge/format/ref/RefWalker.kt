package dev.netherforge.format.ref

import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.text.GlyphTags
import dev.netherforge.format.text.MiniMessage
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One reference a document makes: what it names ([kind]), as written
 * ([text]), and the JSON [path] of the string holding it. A glyph tag is
 * inside MiniMessage text: [path] is the text's, and [start] and [end] where
 * the whole tag (`<glyph:ui/coin>`) is in it.
 */
@Serializable
data class FoundRef(val kind: RefKind, val path: String, val text: String, val start: Int? = null, val end: Int? = null)

/**
 * Finds every reference in a document by walking its JSON alongside its
 * serializer's descriptors: a string under a [Ref] property is one, and so is
 * each `<glyph:…>` tag in a string under a [MiniMessage] property, however
 * deep. So a new reference is found everywhere (validation, find usages,
 * rename, delete) by being marked in the model.
 *
 * It walks JSON rather than the model so the editor can hand it a document
 * as it is (a model with errors in it), and so a rename rewrites exactly the
 * strings it names and leaves the rest of the document alone.
 */
object RefWalker {

    /** Every reference in [element], a document whose shape is [descriptor], in document order. */
    fun find(descriptor: SerialDescriptor, element: JsonElement): List<FoundRef> {
        val out = mutableListOf<FoundRef>()
        Walk(emptySet()) {
            out += it
            null
        }.walk(element, descriptor, "$", null)
        return out
    }

    /** Every reference in [value]. */
    fun <T> find(serializer: KSerializer<T>, value: T): List<FoundRef> =
        find(serializer.descriptor, CanonicalJson.json.encodeToJsonElement(serializer, value))

    /**
     * [element] with every reference [replace] answers new text for rewritten
     * to it (a glyph tag becomes `<glyph:new>`, or goes for `""`), and
     * everything else as it was. A value whose type's serial name is in
     * [skip] is left whole, references and all.
     */
    fun rewrite(
        descriptor: SerialDescriptor,
        element: JsonElement,
        skip: Set<String> = emptySet(),
        replace: (FoundRef) -> String?
    ): JsonElement = Walk(skip, replace).walk(element, descriptor, "$", null)

    /** What the property a value sits under is marked as. */
    private sealed interface Mark {
        data class Reference(val kind: RefKind) : Mark

        data object Text : Mark
    }

    /** One walk: what it [replace]s, and the types whose values it leaves whole ([skip], by serial name). */
    private class Walk(private val skip: Set<String>, private val replace: (FoundRef) -> String?) {
        fun walk(element: JsonElement, descriptor: SerialDescriptor, path: String, mark: Mark?): JsonElement {
            if (descriptor.serialName in skip) return element
            if (element is JsonPrimitive && element.isString) {
                return when (mark) {
                    null -> element
                    is Mark.Reference -> replace(FoundRef(mark.kind, path, element.content))?.let(::JsonPrimitive) ?: element
                    Mark.Text -> glyphs(element.content, path)?.let(::JsonPrimitive) ?: element
                }
            }
            if (descriptor.isInline) return walk(element, descriptor.getElementDescriptor(0), path, mark)
            return when (descriptor.kind) {
                StructureKind.LIST -> if (element is JsonArray) {
                    val item = descriptor.getElementDescriptor(0)
                    JsonArray(element.mapIndexed { i, child -> walk(child, item, "$path[$i]", mark) })
                } else {
                    element
                }
                // A marked map's values are what it marks (a biome's features, lists by step), never its keys.
                StructureKind.MAP -> if (element is JsonObject) {
                    val value = descriptor.getElementDescriptor(1)
                    JsonObject(element.mapValues { (key, child) -> walk(child, value, CanonicalJson.childPath(path, key), mark) })
                } else {
                    element
                }
                StructureKind.CLASS, StructureKind.OBJECT -> if (element is JsonObject) {
                    JsonObject(
                        element.mapValues { (key, child) ->
                            val index = descriptor.getElementIndex(key)
                            if (index < 0) return@mapValues child
                            walk(
                                child,
                                descriptor.getElementDescriptor(index),
                                CanonicalJson.childPath(path, key),
                                markOf(descriptor, index)
                            )
                        }
                    )
                } else {
                    element
                }
                PolymorphicKind.SEALED -> {
                    val type = (
                        (element as? JsonObject)?.get(
                            CanonicalJson.json.configuration.classDiscriminator
                        ) as? JsonPrimitive
                        )?.content
                    val subclasses = descriptor.getElementDescriptor(1)
                    val index = type?.let(subclasses::getElementIndex) ?: -1
                    if (index >= 0) walk(element, subclasses.getElementDescriptor(index), path, mark) else element
                }
                else -> element
            }
        }

        /** [text] with its glyph tags rewritten as [replace] answers, or null when it answers nothing new. */
        private fun glyphs(text: String, path: String): String? =
            GlyphTags.rewrite(text) { tag -> replace(FoundRef(RefKind.GLYPH, path, tag.reference, tag.start, tag.end)) }
    }

    private fun markOf(descriptor: SerialDescriptor, index: Int): Mark? {
        for (annotation in descriptor.getElementAnnotations(index)) {
            if (annotation is Ref) return Mark.Reference(annotation.kind)
            if (annotation is MiniMessage) return Mark.Text
        }
        return null
    }
}
