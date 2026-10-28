package dev.netherforge.format.resourcepack

import dev.netherforge.format.ProblemCode
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.Names
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * A resource pack's model files (`resource_packs/<id>/models/<path>.json`): the game's own JSON block and item
 * models, with `elements` (boxes of sixteenths of a block, each face a texture), as Blockbench and other modellers
 * export them. An item look or a block look names one with `model` (`crystal.json`).
 *
 * Format doesn't model the file: it checks what the game would refuse or draw as the missing model (an element
 * outside the bounds, a rotation or face that isn't one, a face whose texture variable the model never gives), warns
 * about display transforms the game clamps, and otherwise passes the file through as written. Only its textures
 * change: a value naming a file in the pack's `textures/` folder (`gems/ruby` for `textures/gems/ruby.png`, as
 * Blockbench writes a texture it saved beside the model) becomes the pack's own resource (`<ns>:<pack>/gems/ruby`);
 * anything else is the game's (`block/stone`, `minecraft:item/stick`), left as it is. Keys the game ignores
 * (Blockbench's `groups`, `texture_size`, `credit`) go through untouched.
 *
 * What the game takes is the same for every supported version (read off 1.21.11's and 26.x's client): an element's
 * corners within [MIN_COORDINATE]..[MAX_COORDINATE] on each axis, and its `rotation` either one axis and any angle
 * (`{ "origin", "axis", "angle" }`) or any angles about all three (`{ "origin", "x", "y", "z" }`, applied X, Y, Z).
 */
object PackModels {
    /** The folder of a pack the model files are in. */
    const val FOLDER = "models"
    const val EXTENSION = ".json"

    /** The bounds the game holds an element's `from` and `to` to, on each axis, in sixteenths of a block. */
    const val MIN_COORDINATE = -16.0
    const val MAX_COORDINATE = 32.0

    /** What the game clamps a display transform's translation to, either way, in sixteenths of a block. */
    const val MAX_TRANSLATION = 80.0

    /** What the game clamps a display transform's scale to, either way. */
    const val MAX_SCALE = 4.0

    /** The faces of an element, as the game names them. */
    val FACES = listOf("down", "up", "north", "south", "west", "east")

    private val AXES = listOf("x", "y", "z")

    /** The ways a face's texture may be turned on it. */
    private val FACE_ROTATIONS = setOf(0, 90, 180, 270)

    /** How deep `#a` → `#b` → … may go before it's a loop. */
    private const val MAX_TEXTURE_CHAIN = 16

    /** A model's path under `models/` (`creatures/crystal.json`): a relative path to a `.json` that stays inside the folder. */
    val MODEL_PATH = Regex("^(${Names.SEGMENT_BODY}/)*${Names.SEGMENT_BODY}\\.json$")
    const val MODEL_PATH_RULE = "a .json path of letters, digits, _, - and . inside this resource pack's models/ folder"

    fun isModelPath(path: String): Boolean = MODEL_PATH.matches(path)

    /** One thing wrong with a model file: where in it (a JSON path), and what. */
    class Mistake(val code: ProblemCode, val path: String, val message: String)

    /** A model file as the pack builds it: its JSON, and which of its texture variables are the pack's textures (by path under `textures/`, `.png` included). */
    class Model(val json: JsonObject, val textures: Map<String, String>) {
        /** The pack textures it draws, each once. */
        val packTextures: Set<String> get() = textures.values.toCollection(LinkedHashSet())

        /** The pack texture breaking a block of it throws up: its `particle`'s, else the first of its own. */
        val particle: String? get() = textures["particle"] ?: textures.values.firstOrNull()
    }

    /**
     * Reads [json] (a model file, already checked by [validate]) as the pack builds it, with [textures] the files
     * under the pack's `textures/` folder: which of its texture variables are the pack's.
     */
    fun read(json: JsonObject, textures: Set<String>): Model {
        val vars = json["textures"] as? JsonObject ?: JsonObject(emptyMap())
        val pack = vars.mapNotNull { (name, value) ->
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            packTexture(text, textures)?.let { name to it }
        }.toMap()
        return Model(json, pack)
    }

    /** The pack texture a model's texture value names (`gems/ruby` → `gems/ruby.png`), or null for the game's or a `#variable`. */
    private fun packTexture(value: String, textures: Set<String>): String? {
        if (value.startsWith("#") || ':' in value) return null
        return "$value.png".takeIf { it in textures }
    }

    /**
     * [model] as the built pack holds it: every texture variable naming a pack texture written as the client's
     * resource ([resource], from the path under `textures/`), the rest as written.
     */
    fun write(model: Model, resource: (texture: String) -> String): JsonObject {
        if (model.textures.isEmpty()) return model.json
        val vars = model.json["textures"] as JsonObject
        val rewritten = JsonObject(vars.mapValues { (name, value) -> model.textures[name]?.let { JsonPrimitive(resource(it)) } ?: value })
        return JsonObject(model.json + ("textures" to rewritten))
    }

    /** What's wrong with [element], a model file's JSON, for the game. */
    fun validate(element: JsonElement): List<Mistake> {
        val out = mutableListOf<Mistake>()
        fun mistake(path: String, message: String, code: ProblemCode = ProblemCodes.RESOURCE_PACK_MODEL) {
            out += Mistake(code, path, message)
        }
        val model = element as? JsonObject
        if (model == null) {
            mistake("$", "A model is a JSON object with elements, as Blockbench exports one", ProblemCodes.RESOURCE_PACK_MODEL_FILE)
            return out
        }
        val parent = model["parent"]
        if (parent != null && (parent !is JsonPrimitive || !parent.isString)) mistake("$.parent", "parent is a model's id")
        val vars = model["textures"]
        if (vars != null &&
            vars !is JsonObject
        ) {
            mistake("$.textures", "textures is an object of names and textures: { \"0\": \"gems/ruby\" }")
        }
        val named = vars as? JsonObject ?: JsonObject(emptyMap())
        for ((name, value) in named) {
            val at = CanonicalJson.childPath("$.textures", name)
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (text == null) {
                mistake(at, "A texture is a string: a texture in this pack's textures/ (gems/ruby), the game's, or #another")
                continue
            }
            // A namespace other than the game's is one nothing serves: the pack's are named by their path alone.
            val namespace = text.substringBefore(':', "")
            if (!text.startsWith("#") && namespace.isNotEmpty() && namespace != GameIds.NAMESPACE) {
                mistake(
                    at,
                    "\"$text\" is in no namespace the client has: name this pack's textures by their path under textures/ " +
                        "(\"${text.substringAfter(
                            ':'
                        )}\" for textures/${text.substringAfter(':')}.png), the game's with ${GameIds.NAMESPACE}:",
                    ProblemCodes.RESOURCE_PACK_MODEL_TEXTURE
                )
            }
            // A plain path that isn't the pack's is the game's, which only its client can say it has.
        }
        val elements = model["elements"]
        if (elements != null && elements !is JsonArray) mistake("$.elements", "elements is a list of boxes")
        (elements as? JsonArray)?.forEachIndexed { index, item -> element(item, "$.elements[$index]", named, parent != null, ::mistake) }
        val display = model["display"]
        if (display != null && display !is JsonObject) mistake("$.display", "display is an object of transforms by where the item is shown")
        (display as? JsonObject)?.forEach { (context, transform) ->
            display(transform, CanonicalJson.childPath("$.display", context), ::mistake)
        }
        for (key in listOf("ambientocclusion")) {
            val value = model[key]
            if (value != null && (value as? JsonPrimitive)?.booleanOrNull == null) mistake("$.$key", "$key is true or false")
        }
        return out
    }

    private fun element(
        item: JsonElement,
        at: String,
        textures: JsonObject,
        hasParent: Boolean,
        mistake: (String, String, ProblemCode) -> Unit
    ) {
        val element = item as? JsonObject
        if (element == null) {
            mistake(at, "An element is an object: { \"from\", \"to\", \"faces\" }", ProblemCodes.RESOURCE_PACK_MODEL)
            return
        }
        for (corner in listOf("from", "to")) {
            val point = vector(element[corner])
            if (point == null) {
                mistake("$at.$corner", "$corner is three numbers, [x, y, z] in sixteenths of a block", ProblemCodes.RESOURCE_PACK_MODEL)
            } else if (point.any { it < MIN_COORDINATE || it > MAX_COORDINATE }) {
                mistake(
                    "$at.$corner",
                    "$corner is outside ${number(MIN_COORDINATE)} to ${number(MAX_COORDINATE)}, which the game refuses to load " +
                        "(the whole model then draws as missing): scale the model down in its display, or split it into parts",
                    ProblemCodes.RESOURCE_PACK_MODEL_BOUNDS
                )
            }
        }
        element["rotation"]?.let { rotation(it, "$at.rotation", mistake) }
        val shade = element["shade"]
        if (shade != null &&
            (shade as? JsonPrimitive)?.booleanOrNull == null
        ) {
            mistake("$at.shade", "shade is true or false", ProblemCodes.RESOURCE_PACK_MODEL)
        }
        val emission = element["light_emission"]
        if (emission != null && (emission as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..15 } == null) {
            mistake("$at.light_emission", "light_emission is a whole number from 0 to 15", ProblemCodes.RESOURCE_PACK_MODEL)
        }
        val faces = element["faces"] as? JsonObject
        if (faces == null || faces.isEmpty()) {
            mistake("$at.faces", "An element needs faces: { \"north\": { \"texture\": \"#0\" }, … }", ProblemCodes.RESOURCE_PACK_MODEL)
            return
        }
        for ((name, value) in faces) {
            val face = CanonicalJson.childPath("$at.faces", name)
            if (name !in FACES) {
                mistake(face, "\"$name\" isn't a face: ${FACES.joinToString()}", ProblemCodes.RESOURCE_PACK_MODEL)
                continue
            }
            val body = value as? JsonObject
            val texture = (body?.get("texture") as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (body == null || texture == null) {
                mistake(
                    face,
                    "A face is { \"texture\": \"#0\" }, and maybe uv, rotation, cullface and tintindex",
                    ProblemCodes.RESOURCE_PACK_MODEL
                )
                continue
            }
            // With a parent, the parent may give the variable: only a model on its own can be held to its own.
            if (!hasParent && texture.startsWith("#") && resolve(texture, textures) == null) {
                mistake(
                    "$face.texture",
                    "\"$texture\" is a texture this model doesn't give: add \"${texture.removePrefix("#")}\" to its textures",
                    ProblemCodes.RESOURCE_PACK_MODEL_TEXTURE
                )
            }
            body["uv"]?.let { uv ->
                val numbers = (uv as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
                if (numbers?.size != 4 || (uv as JsonArray).size != 4) {
                    mistake(
                        "$face.uv",
                        "uv is four numbers: [x1, y1, x2, y2] on its texture, in sixteenths",
                        ProblemCodes.RESOURCE_PACK_MODEL
                    )
                }
            }
            body["rotation"]?.let {
                if ((it as? JsonPrimitive)?.intOrNull !in FACE_ROTATIONS) {
                    mistake("$face.rotation", "A face's rotation is 0, 90, 180 or 270", ProblemCodes.RESOURCE_PACK_MODEL)
                }
            }
            body["cullface"]?.let {
                if ((it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content !in FACES) {
                    mistake("$face.cullface", "cullface is a face: ${FACES.joinToString()}", ProblemCodes.RESOURCE_PACK_MODEL)
                }
            }
            body["tintindex"]?.let {
                if ((it as? JsonPrimitive)?.intOrNull ==
                    null
                ) {
                    mistake("$face.tintindex", "tintindex is a whole number", ProblemCodes.RESOURCE_PACK_MODEL)
                }
            }
        }
    }

    /** One axis and any angle, or any angles about all three; with an origin either way. */
    private fun rotation(value: JsonElement, at: String, mistake: (String, String, ProblemCode) -> Unit) {
        val rotation = value as? JsonObject
        if (rotation == null) {
            mistake(at, ROTATION_RULE, ProblemCodes.RESOURCE_PACK_MODEL)
            return
        }
        if (vector(rotation["origin"]) ==
            null
        ) {
            mistake("$at.origin", "origin is three numbers, the point it turns about", ProblemCodes.RESOURCE_PACK_MODEL)
        }
        val axis = (rotation["axis"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val angle = (rotation["angle"] as? JsonPrimitive)?.doubleOrNull
        val euler = AXES.map { (rotation[it] as? JsonPrimitive)?.doubleOrNull }
        val single = rotation.containsKey("axis") || rotation.containsKey("angle")
        when {
            single && (axis !in AXES || angle == null || !angle.isFinite()) -> mistake(at, ROTATION_RULE, ProblemCodes.RESOURCE_PACK_MODEL)
            single &&
                AXES.any {
                    rotation.containsKey(it)
                } -> mistake(at, "A rotation is one axis and an angle or x, y and z, not both", ProblemCodes.RESOURCE_PACK_MODEL)
            !single && (euler.any { it == null || !it.isFinite() }) -> mistake(at, ROTATION_RULE, ProblemCodes.RESOURCE_PACK_MODEL)
        }
        val rescale = rotation["rescale"]
        if (rescale != null &&
            (rescale as? JsonPrimitive)?.booleanOrNull == null
        ) {
            mistake("$at.rescale", "rescale is true or false", ProblemCodes.RESOURCE_PACK_MODEL)
        }
    }

    private const val ROTATION_RULE =
        "A rotation is { \"origin\": [x, y, z], \"axis\": \"x\", \"y\" or \"z\", \"angle\": degrees } or { \"origin\", \"x\", \"y\", \"z\" } in degrees"

    /** A display transform: rotation, translation and scale, the last two as far as the game takes them. */
    private fun display(value: JsonElement, at: String, mistake: (String, String, ProblemCode) -> Unit) {
        val transform = value as? JsonObject
        if (transform == null) {
            mistake(at, "A display transform is { \"rotation\", \"translation\", \"scale\" }", ProblemCodes.RESOURCE_PACK_MODEL)
            return
        }
        for (part in listOf("rotation", "translation", "scale")) {
            val raw = transform[part] ?: continue
            val numbers = vector(raw)
            if (numbers == null) {
                mistake("$at.$part", "$part is three numbers", ProblemCodes.RESOURCE_PACK_MODEL)
                continue
            }
            val limit = when (part) {
                "translation" -> MAX_TRANSLATION
                "scale" -> MAX_SCALE
                else -> continue
            }
            if (numbers.any { it < -limit || it > limit }) {
                mistake(
                    "$at.$part",
                    "The game clamps a display's $part to ±${number(limit)}, so it won't be what this says: " +
                        "scale the centity node (or the model's elements) instead",
                    ProblemCodes.RESOURCE_PACK_MODEL_DISPLAY
                )
            }
        }
    }

    /** `#name` followed through the model's own [textures] to a texture, or null when it leads nowhere (or loops). */
    private fun resolve(reference: String, textures: JsonObject): String? {
        var at = reference
        repeat(MAX_TEXTURE_CHAIN) {
            if (!at.startsWith("#")) return at
            at = (textures[at.removePrefix("#")] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        }
        return null
    }

    private fun vector(element: JsonElement?): List<Double>? {
        val array = element as? JsonArray ?: return null
        if (array.size != 3) return null
        return array.map { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.doubleOrNull?.takeIf(Double::isFinite) ?: return null }
    }

    private fun number(value: Double) = CanonicalJson.formatNumber(value.toString())
}
