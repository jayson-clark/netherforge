package dev.netherforge.format.codegen

import dev.netherforge.format.Problem
import dev.netherforge.format.bridge.Bridge
import dev.netherforge.format.bridge.BridgeEvent
import dev.netherforge.format.bridge.BridgeMethod
import dev.netherforge.format.bridge.BridgeStream
import dev.netherforge.format.centity.DayPeriod
import dev.netherforge.format.centity.SpawnTime
import dev.netherforge.format.editor.CanonicalResult
import dev.netherforge.format.editor.CutsceneResult
import dev.netherforge.format.editor.EffectStepResult
import dev.netherforge.format.editor.FitResult
import dev.netherforge.format.editor.LootPreview
import dev.netherforge.format.editor.PackageInputs
import dev.netherforge.format.editor.PackagesNeeded
import dev.netherforge.format.editor.PoseResult
import dev.netherforge.format.editor.ProjectOutline
import dev.netherforge.format.editor.ProjectValidation
import dev.netherforge.format.editor.ResourcePacksPreview
import dev.netherforge.format.editor.SettingValueResult
import dev.netherforge.format.editor.StyledText
import dev.netherforge.format.editor.TerrainPreviewResult
import dev.netherforge.format.editor.TerrainStructureInput
import dev.netherforge.format.editor.TextLayout
import dev.netherforge.format.editor.Usages
import dev.netherforge.format.editor.WorkspaceResult
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.item.HoldDef
import dev.netherforge.format.item.TooltipComponent
import dev.netherforge.format.item.UseAnimation
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.loot.LootRange
import dev.netherforge.format.math.VEC3_SERIAL_NAME
import dev.netherforge.format.project.ClassifiedPath
import dev.netherforge.format.project.ImageInfo
import dev.netherforge.format.project.Kinds
import dev.netherforge.format.recipe.Ingredient
import dev.netherforge.format.ref.RefTarget
import dev.netherforge.format.wasm.WasmInfo
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/**
 * Writes the JSON Schemas for every document kind and the TypeScript types the
 * editor uses, both read off the serializers' descriptors, and the constants
 * the editor needs (defaults, limits, naming rules; see [Constants]), so the
 * Kotlin model stays the only authority.
 *
 *     contract <output-dir>
 */
fun main(args: Array<String>) {
    require(args.size == 1) { "Usage: contract <output-dir>" }
    val out = File(args[0])
    val schemaDir = File(out, "schema").apply { mkdirs() }

    // A kind that was renamed or removed mustn't leave its old schema behind for the editor to bundle.
    val schemas = Kinds.documents.map { "${it.id}.schema.json" }.toSet()
    schemaDir.listFiles { file -> file.name.endsWith(".schema.json") && file.name !in schemas }?.forEach { it.delete() }

    for (kind in Kinds.documents) {
        val file = File(schemaDir, "${kind.id}.schema.json")
        file.writeText(CanonicalJson.print(Schema(kind.serializer.descriptor, kind.id).document()) + "\n")
    }

    val ts = TypeScript()
    for (kind in Kinds.documents) ts.declare(kind.serializer.descriptor)
    ts.declare(Problem.serializer().descriptor)
    ts.declare(GameDataBundle.serializer().descriptor)
    for (result in EDITOR_RESULTS) ts.declare(result)
    ts.bridge(Bridge.allRequests, Bridge.events, Bridge.streams)
    File(out, "types.ts").writeText(ts.render())
    File(out, "constants.ts").writeText(Constants.render())
    println("Wrote contract to ${out.path}")
}

/** What format's JS exports return (see jsMain's *Exports.kt). */
private val EDITOR_RESULTS = listOf(
    CanonicalResult.serializer().descriptor,
    ProjectOutline.serializer().descriptor,
    ProjectValidation.serializer().descriptor,
    PoseResult.serializer().descriptor,
    EffectStepResult.serializer().descriptor,
    CutsceneResult.serializer().descriptor,
    TerrainPreviewResult.serializer().descriptor,
    ResourcePacksPreview.serializer().descriptor,
    ImageInfo.serializer().descriptor,
    WasmInfo.serializer().descriptor,
    FitResult.serializer().descriptor,
    LootPreview.serializer().descriptor,
    SettingValueResult.serializer().descriptor,
    TextLayout.serializer().descriptor,
    StyledText.serializer().descriptor,
    ClassifiedPath.serializer().descriptor,
    Usages.serializer().descriptor,
    PackagesNeeded.serializer().descriptor,
    WorkspaceResult.serializer().descriptor,
    // Arguments rather than results: what the host read and fetched of a project's packages.
    PackageInputs.serializer().descriptor,
    // An argument too: a structure the editor read, for the terrain preview.
    TerrainStructureInput.serializer().descriptor,
    // An argument rather than a result: what findUsages and renameRefs are asked about.
    RefTarget.serializer().descriptor
)

/** The short type name for a descriptor; sealed subclasses are named after their parent. */
private fun typeName(descriptor: SerialDescriptor, sealedParent: SerialDescriptor? = null): String {
    descriptor.annotations.filterIsInstance<TsName>().firstOrNull()?.let { return it.name }
    val serial = descriptor.serialName.removeSuffix("?")
    if (sealedParent != null && '.' !in serial) {
        val pascal = serial.split('_').joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }
        val suffix = Regex("[A-Z][a-z0-9]*").findAll(shortName(sealedParent).removeSuffix("Def")).last().value
        return pascal + suffix
    }
    return shortName(descriptor)
}

/** Arbitrary JSON: `unknown` in TypeScript, unconstrained in a schema. */
private const val JSON_ELEMENT = "kotlinx.serialization.json.JsonElement"

private fun shortName(descriptor: SerialDescriptor) = descriptor.serialName.removeSuffix("?").substringAfterLast('.')

private class Schema(root: SerialDescriptor, val kindId: String) {
    private val defs = linkedMapOf<String, JsonElement>()
    private val rootRef = ref(root)

    fun document(): JsonObject = buildJsonObject {
        put("\$schema", "https://json-schema.org/draft/2020-12/schema")
        put("title", "NetherForge $kindId file")
        put("\$ref", rootRef)
        put("\$defs", JsonObject(defs))
    }

    private fun ref(descriptor: SerialDescriptor, sealedParent: SerialDescriptor? = null): String {
        val name = typeName(descriptor, sealedParent)
        if (name !in defs) {
            defs[name] = JsonPrimitive("pending")
            defs[name] = objectSchema(descriptor, sealedParent)
        }
        return "#/\$defs/$name"
    }

    private fun objectSchema(descriptor: SerialDescriptor, sealedParent: SerialDescriptor?): JsonElement = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        val required = mutableListOf<String>()
        put(
            "properties",
            buildJsonObject {
                if (sealedParent != null) {
                    put("type", buildJsonObject { put("const", descriptor.serialName) })
                    required += "type"
                }
                for (i in 0 until descriptor.elementsCount) {
                    val name = descriptor.getElementName(i)
                    put(name, schemaFor(descriptor.getElementDescriptor(i)))
                    if (!descriptor.isElementOptional(i)) required += name
                }
            }
        )
        if (required.isNotEmpty()) put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }

    fun schemaFor(descriptor: SerialDescriptor): JsonElement {
        if (descriptor.serialName.removeSuffix("?") == JSON_ELEMENT) return buildJsonObject { }
        if (descriptor.serialName.removeSuffix("?") == Ingredient.SERIAL_NAME) {
            // A string (an item id, or a tag after `#`), or a project item.
            return buildJsonObject {
                put(
                    "oneOf",
                    buildJsonArray {
                        add(buildJsonObject { put("type", "string") })
                        add(
                            buildJsonObject {
                                put("type", "object")
                                put("additionalProperties", false)
                                put("properties", buildJsonObject { put("item", buildJsonObject { put("type", "string") }) })
                                put("required", buildJsonArray { add(JsonPrimitive("item")) })
                            }
                        )
                    }
                )
            }
        }
        if (descriptor.serialName.removeSuffix("?") == LootRange.SERIAL_NAME) {
            // A whole number, or a range of them.
            return buildJsonObject {
                put(
                    "oneOf",
                    buildJsonArray {
                        add(buildJsonObject { put("type", "integer") })
                        add(
                            buildJsonObject {
                                put("type", "object")
                                put("additionalProperties", false)
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put("min", buildJsonObject { put("type", "integer") })
                                        put("max", buildJsonObject { put("type", "integer") })
                                    }
                                )
                                put(
                                    "required",
                                    buildJsonArray {
                                        add(JsonPrimitive("min"))
                                        add(JsonPrimitive("max"))
                                    }
                                )
                            }
                        )
                    }
                )
            }
        }
        if (descriptor.serialName.removeSuffix("?") == SpawnTime.SERIAL_NAME) {
            // A half of the day by name, or a range of ticks of the day.
            return buildJsonObject {
                put(
                    "oneOf",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                putJsonArray("enum") { DayPeriod.entries.forEach { add(JsonPrimitive(CanonicalJson.serialName(it))) } }
                            }
                        )
                        add(
                            buildJsonObject {
                                put("type", "object")
                                put("additionalProperties", false)
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put("min", buildJsonObject { put("type", "integer") })
                                        put("max", buildJsonObject { put("type", "integer") })
                                    }
                                )
                            }
                        )
                    }
                )
            }
        }
        if (descriptor.serialName.removeSuffix("?") == UseAnimation.SERIAL_NAME) {
            return buildJsonObject { putJsonArray("enum") { UseAnimation.ALL.forEach { add(JsonPrimitive(it)) } } }
        }
        if (descriptor.serialName.removeSuffix("?") == HoldDef.SERIAL_NAME) {
            // true, or a hold with a pose.
            return buildJsonObject {
                put(
                    "oneOf",
                    buildJsonArray {
                        add(buildJsonObject { put("type", "boolean") })
                        add(
                            buildJsonObject {
                                put("type", "object")
                                put("additionalProperties", false)
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put(
                                            "animation",
                                            buildJsonObject {
                                                putJsonArray("enum") { UseAnimation.ALL.forEach { add(JsonPrimitive(it)) } }
                                            }
                                        )
                                    }
                                )
                            }
                        )
                    }
                )
            }
        }
        if (descriptor.serialName.removeSuffix("?") == TooltipComponent.SERIAL_NAME) {
            // A string to read (the validator names a wrong one), but only the lines there are to write.
            return buildJsonObject { putJsonArray("enum") { TooltipComponent.ALL.forEach { add(JsonPrimitive(it)) } } }
        }
        if (descriptor.serialName.removeSuffix("?") == VEC3_SERIAL_NAME) {
            return buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject { put("type", "number") })
                put("minItems", 3)
                put("maxItems", 3)
            }
        }
        return when (val kind: SerialKind = descriptor.kind) {
            PrimitiveKind.STRING -> buildJsonObject { put("type", "string") }
            PrimitiveKind.BOOLEAN -> buildJsonObject { put("type", "boolean") }
            PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> buildJsonObject { put("type", "integer") }
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> buildJsonObject { put("type", "number") }
            SerialKind.ENUM -> buildJsonObject {
                putJsonArray("enum") { for (i in 0 until descriptor.elementsCount) add(JsonPrimitive(descriptor.getElementName(i))) }
            }
            StructureKind.LIST -> buildJsonObject {
                put("type", "array")
                put("items", schemaFor(descriptor.getElementDescriptor(0)))
            }
            StructureKind.MAP -> buildJsonObject {
                put("type", "object")
                val key = descriptor.getElementDescriptor(0)
                if (key.kind == SerialKind.ENUM) {
                    put("propertyNames", schemaFor(key))
                }
                put("additionalProperties", schemaFor(descriptor.getElementDescriptor(1)))
            }
            PolymorphicKind.SEALED -> buildJsonObject {
                val subclasses = descriptor.getElementDescriptor(1).elementDescriptors.toList()
                put("oneOf", buildJsonArray { for (sub in subclasses) add(buildJsonObject { put("\$ref", ref(sub, descriptor)) }) })
            }
            else -> buildJsonObject { put("\$ref", ref(descriptor)) }
        }
    }
}

private class TypeScript {
    private val declared = linkedMapOf<String, String>()

    fun declare(descriptor: SerialDescriptor, sealedParent: SerialDescriptor? = null): String {
        val name = typeName(descriptor, sealedParent)
        if (name in declared) return name
        declared[name] = ""
        declared[name] = when (descriptor.kind) {
            PolymorphicKind.SEALED -> {
                val subs = descriptor.getElementDescriptor(1).elementDescriptors.map { declare(it, descriptor) }
                "export type $name = ${subs.joinToString(" | ")}\n"
            }
            // The values too, not only the type, so the editor can list them (a select's options).
            SerialKind.ENUM -> "export const ${name}Values = [${(0 until descriptor.elementsCount).joinToString(", ") {
                "'${descriptor.getElementName(it)}'"
            }}] as const\nexport type $name = (typeof ${name}Values)[number]\n"
            else -> buildString {
                append("export interface $name {\n")
                if (sealedParent != null) append("  type: '${descriptor.serialName}'\n")
                for (i in 0 until descriptor.elementsCount) {
                    val element = descriptor.getElementDescriptor(i)
                    val optional = descriptor.isElementOptional(i) || element.isNullable
                    append("  ${quoteKey(descriptor.getElementName(i))}${if (optional) "?" else ""}: ${typeOf(element)}\n")
                }
                append("}\n")
            }
        }
        return name
    }

    private fun quoteKey(key: String) = if (Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(key)) key else "'$key'"

    fun typeOf(descriptor: SerialDescriptor): String {
        if (descriptor.serialName.removeSuffix("?") == VEC3_SERIAL_NAME) return "Vec3"
        if (descriptor.serialName.removeSuffix("?") == Ingredient.SERIAL_NAME) return "string | { item: string }"
        if (descriptor.serialName.removeSuffix("?") == LootRange.SERIAL_NAME) return "LootRange"
        if (descriptor.serialName.removeSuffix("?") == SpawnTime.SERIAL_NAME) return "SpawnTime"
        if (descriptor.serialName.removeSuffix("?") == TooltipComponent.SERIAL_NAME) return "TooltipComponent"
        if (descriptor.serialName.removeSuffix("?") == UseAnimation.SERIAL_NAME) return "UseAnimation"
        if (descriptor.serialName.removeSuffix("?") == HoldDef.SERIAL_NAME) return "HoldDef"
        if (descriptor.serialName.removeSuffix("?") == JSON_ELEMENT) return "unknown"
        return when (descriptor.kind) {
            PrimitiveKind.STRING -> "string"
            PrimitiveKind.BOOLEAN -> "boolean"
            is PrimitiveKind -> "number"
            StructureKind.LIST -> {
                val item = typeOf(descriptor.getElementDescriptor(0))
                if (item.contains(' ')) "Array<$item>" else "$item[]"
            }
            StructureKind.MAP -> {
                val key = descriptor.getElementDescriptor(0)
                val value = typeOf(descriptor.getElementDescriptor(1))
                if (key.kind == SerialKind.ENUM) "Partial<Record<${declare(key)}, $value>>" else "Record<string, $value>"
            }
            else -> declare(descriptor)
        }
    }

    private val extra = StringBuilder()

    /**
     * `BridgeRequests` (method → what it takes and answers) and
     * `BridgeEvents` (method → a notification's params; a stream's carry a
     * batch), from format's declarations, so a method is declared once.
     */
    fun bridge(requests: List<BridgeMethod<*, *>>, events: List<BridgeEvent<*>>, streams: List<BridgeStream<*>>) {
        fun unit(descriptor: SerialDescriptor) = descriptor.serialName == "kotlin.Unit"
        extra.append("/**\n")
        extra.append(" * Every request the editor can send over the dev bridge, by method: its\n")
        extra.append(" * params (`undefined`: it takes none) and its result (`null`: it answers none).\n")
        extra.append(" */\n")
        extra.append("export interface BridgeRequests {\n")
        for (method in requests) {
            val params = method.params.descriptor.takeUnless(::unit)?.let(::typeOf) ?: "undefined"
            val result = method.result.descriptor.takeUnless(::unit)?.let(::typeOf) ?: "null"
            extra.append("  ${quoteKey(method.name)}: { params: $params; result: $result }\n")
        }
        extra.append("}\n")
        extra.append("\n/** Every notification the plugin sends, by method: its params. A stream's carry a batch of items. */\n")
        extra.append("export interface BridgeEvents {\n")
        for (event in events) extra.append("  ${quoteKey(event.name)}: ${typeOf(event.params.descriptor)}\n")
        for (stream in streams) {
            val item = typeOf(stream.item.descriptor)
            extra.append(
                "  ${quoteKey(stream.name)}: { ${BridgeStream.ITEMS}: ${if (item.contains(' ')) "Array<$item>" else "$item[]"} }\n"
            )
        }
        extra.append("}\n")
    }

    fun render(): String = buildString {
        append("// Generated by format's generateContract task from the Kotlin model. Do not edit.\n\n")
        append("export type Vec3 = [number, number, number]\n\n")
        append("/** A whole number, or a range of them (both ends included). */\n")
        append("export type LootRange = number | { min: number; max: number }\n\n")
        append("/** A half of the day, or a range of ticks of the day (a min above the max runs through midnight). */\n")
        append(
            "export type SpawnTime = ${DayPeriod.entries.joinToString(" | ") {
                "'${CanonicalJson.serialName(it)}'"
            }} | { min?: number; max?: number }\n\n"
        )
        append("/** A tooltip line an item can hide, by the data component that draws it. */\n")
        append("export const TooltipComponentValues = [${TooltipComponent.ALL.joinToString(", ") { "'$it'" }}] as const\n")
        append("export type TooltipComponent = (typeof TooltipComponentValues)[number]\n\n")
        append("/** A pose a hold item holds the player in: one of the game's use animations. */\n")
        append("export const UseAnimationValues = [${UseAnimation.ALL.joinToString(", ") { "'$it'" }}] as const\n")
        append("export type UseAnimation = (typeof UseAnimationValues)[number]\n\n")
        append("/** An item's hold: `true`, or one that poses the player. */\n")
        append("export type HoldDef = boolean | { animation?: UseAnimation }\n\n")
        for (body in declared.values) {
            append(body)
            append('\n')
        }
        append(extra)
    }.trimEnd() + "\n"
}
