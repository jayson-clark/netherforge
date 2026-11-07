package dev.netherforge.format

import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.Kinds
import dev.netherforge.format.project.ProjectSnapshot
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.fail

/**
 * What each example compiles to, the form the server runs: every resource whose kind compiles it to something other
 * than its model, and the resource packs built together, as `packages/format/testdata/compiled/<example>.json`
 * (`UPDATE_GOLDEN=1` rewrites them). The Rust port (`crates/format`) is held to the same files.
 *
 * Written by reflection, so a compiled form needs nothing of its own to be in it: a model (anything kotlinx
 * serializes) as its file says it, sealed types tagged by `type`; any other class by its properties that hold a
 * value (not the ones worked out from others), in declaration order, nulls left out; a map in key order; an enum
 * by its serial name; bytes as their SHA-256.
 */
class CompiledGoldenTest {
    @Test
    fun theExamplesCompileAsTheGoldensSay() {
        val failures = mutableListOf<String>()
        for (example in TestFiles.dirs("examples")) {
            val actual = CanonicalJson.print(compiled(loadTestProject("examples", example))) + "\n"
            val path = "packages/format/testdata/compiled/$example.json"
            if (actual == TestFiles.read(path)) continue
            if (TestFiles.updateGolden) TestFiles.write(path, actual) else failures += path
        }
        if (failures.isNotEmpty()) fail("${failures.joinToString()} differ. Run with UPDATE_GOLDEN=1 to accept.")
    }

    private fun compiled(snapshot: ProjectSnapshot): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for (kind in Kinds.all) {
            val resources = snapshot.everywhere(kind).entries.sortedBy { it.key }.mapNotNull { (name, loaded) ->
                val compiled = loaded.compiled ?: return@mapNotNull null
                if (compiled::class == loaded.value!!::class) return@mapNotNull null
                name to dump(compiled)
            }
            if (resources.isNotEmpty()) out[kind.id] = JsonObject(resources.toMap())
        }
        out["resourcePacks"] = dump(snapshot.compiledResourcePacks)
        return JsonObject(out)
    }

    companion object {
        fun dump(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Char -> JsonPrimitive(value.toString())
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is ByteArray -> JsonPrimitive(sha256(value))
            is IntArray -> JsonArray(value.map { JsonPrimitive(it) })
            is DoubleArray -> JsonArray(value.map { JsonPrimitive(it) })
            is Enum<*> -> JsonPrimitive(serialName(value))
            is Collection<*> -> JsonArray(value.map(::dump))
            is Array<*> -> JsonArray(value.map(::dump))
            is Map<*, *> -> JsonObject(
                value.entries.map { (k, v) -> key(k) to dump(v) }.sortedBy { it.first }.toMap().filterValues { it != JsonNull }
            )
            is ClosedRange<*> -> JsonObject(mapOf("start" to dump(value.start), "endInclusive" to dump(value.endInclusive)))
            is Pair<*, *> -> JsonArray(listOf(dump(value.first), dump(value.second)))
            else -> serialized(value) ?: fields(value)
        }

        private fun key(key: Any?): String = when (key) {
            is Enum<*> -> serialName(key)
            else -> key.toString()
        }

        private fun serialName(value: Enum<*>): String =
            value.declaringJavaClass.getField(value.name).getAnnotation(SerialName::class.java)?.value ?: value.name

        /** [value] as kotlinx writes it, through its sealed parent's serializer when it has one (so it's tagged). */
        @Suppress("UNCHECKED_CAST")
        private fun serialized(value: Any): JsonElement? {
            val own = serializerOf(value.javaClass) ?: return null
            val sealed = supertypes(value.javaClass).firstNotNullOfOrNull { type ->
                serializerOf(type)?.takeIf { it.descriptor.kind == PolymorphicKind.SEALED }
            }
            return CanonicalJson.json.encodeToJsonElement((sealed ?: own) as KSerializer<Any>, value)
        }

        private fun serializerOf(type: Class<*>): KSerializer<*>? =
            if (type.typeParameters.isNotEmpty()) null else runCatching { serializer(type) }.getOrNull()

        private fun supertypes(type: Class<*>): List<Class<*>> = buildList {
            var at: Class<*>? = type
            while (at != null && at != Any::class.java) {
                at.interfaces.forEach { add(it) }
                at.superclass?.takeIf { it != Any::class.java }?.let { add(it) }
                at = at.superclass
            }
        }

        /** A class kotlinx doesn't serialize, by every property that holds a value and has a public getter. */
        private fun fields(value: Any): JsonObject {
            val out = LinkedHashMap<String, JsonElement>()
            val chain = generateSequence<Class<*>>(value.javaClass) { it.superclass?.takeIf { s -> s != Any::class.java } }
            for (type in chain.toList().reversed()) {
                for (field in type.declaredFields) {
                    if (Modifier.isStatic(field.modifiers) || field.isSynthetic || '$' in field.name) continue
                    if (getter(value.javaClass, field.name) == null) continue
                    field.isAccessible = true
                    val dumped = dump(field.get(value))
                    if (dumped != JsonNull) out[field.name] = dumped
                }
            }
            return JsonObject(out)
        }

        private fun getter(type: Class<*>, name: String) = runCatching {
            val capital = name.replaceFirstChar { it.uppercase() }
            type.methods.firstOrNull {
                // A value class's getter is mangled: `getSkin-<hash>`.
                it.parameterCount == 0 &&
                    (
                        it.name == "get$capital" ||
                            it.name.startsWith("get$capital-") ||
                            it.name == "is$capital" ||
                            (name.startsWith("is") && it.name == name)
                        )
            }
        }.getOrNull()

        private fun sha256(bytes: ByteArray) =
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
