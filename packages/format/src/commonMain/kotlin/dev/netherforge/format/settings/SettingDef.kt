package dev.netherforge.format.settings

import dev.netherforge.format.json.CanonicalJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * One server-owner setting a package declares in `netherforge.json`'s
 * `settings`: something whoever runs the project changes without touching
 * its scripts (`nf.config("max_players")`), like a plugin's `config.yml`.
 * The server keeps the owner's values in `plugins/NetherForge/settings/<namespace>.json`
 * ([SettingValues]); a setting the owner never set has its [default].
 *
 * A sealed `type` per kind of value, so a new kind (a time zone, say) is one
 * more subclass: its JSON, its checks ([read], [parse]) and its value as a
 * script gets it ([value]). Everything that edits a value (the editor's form,
 * the in-game dialog, `/nf settings set`) goes through those.
 */
@Serializable
sealed interface SettingDef {
    /** What it's for, in a sentence: whoever runs the project reads it beside the value. */
    val description: String

    /** [default] as JSON: the value until the server's owner sets another. */
    val defaultValue: JsonPrimitive

    /** What a value of this setting is, for messages: `a whole number from 2 to 16`. */
    val expects: String

    /**
     * [json] as a value of this setting, in its one written form (a whole
     * number written `2.0` is `2`), or why it can't be one.
     */
    fun read(json: JsonElement): SettingRead

    /** A value typed as words (`/nf settings set`, a dialog's text box): `true`, `12`, `easy`. */
    fun parse(text: String): SettingRead

    /** A value [read] accepted, as Kotlin hands it to a script: a `Boolean`, `Long`, `Double` or `String`. */
    fun value(json: JsonPrimitive): Any

    /** A value [read] accepted, as people read it: `true`, `12`, `0.5`, `easy`. */
    fun show(json: JsonPrimitive): String = if (json.isString) json.content else CanonicalJson.formatNumber(json.content)
}

/** What a value read as: its one written form, or why it can't be this setting's. */
sealed interface SettingRead {
    data class Ok(val json: JsonPrimitive) : SettingRead

    data class Bad(val message: String) : SettingRead
}

/** On or off. */
@Serializable
@SerialName("boolean")
data class BooleanSetting(override val description: String, val default: Boolean) : SettingDef {
    override val defaultValue get() = JsonPrimitive(default)
    override val expects get() = "true or false"

    override fun read(json: JsonElement): SettingRead {
        val primitive = json as? JsonPrimitive
        val value = primitive?.takeIf { !it.isString }?.booleanOrNull ?: return bad(json, expects)
        return SettingRead.Ok(JsonPrimitive(value))
    }

    override fun parse(text: String): SettingRead = when (text.trim().lowercase()) {
        "true", "on", "yes" -> SettingRead.Ok(JsonPrimitive(true))
        "false", "off", "no" -> SettingRead.Ok(JsonPrimitive(false))
        else -> SettingRead.Bad("\"${text.trim()}\" isn't $expects")
    }

    override fun value(json: JsonPrimitive): Any = json.content == "true"
}

/** A whole number, optionally within [min] and [max] (both included). */
@Serializable
@SerialName("integer")
data class IntegerSetting(
    override val description: String,
    val default: Long,
    /** The least it may be. */
    val min: Long? = null,
    /** The most it may be. */
    val max: Long? = null
) : SettingDef {
    override val defaultValue get() = JsonPrimitive(default)
    override val expects get() = "a whole number" + range(min, max)

    override fun read(json: JsonElement): SettingRead {
        val number = (json as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return bad(json, expects)
        return whole(number, json.toString())
    }

    override fun parse(text: String): SettingRead {
        val number = text.trim().toDoubleOrNull() ?: return SettingRead.Bad("\"${text.trim()}\" isn't $expects")
        return whole(number, text.trim())
    }

    private fun whole(number: Double, shown: String): SettingRead {
        val value = number.toLong()
        if (number.isNaN() || number.isInfinite() || value.toDouble() != number) return SettingRead.Bad("$shown isn't $expects")
        if (min != null && value < min || max != null && value > max) return SettingRead.Bad("$value isn't $expects")
        return SettingRead.Ok(JsonPrimitive(value))
    }

    override fun value(json: JsonPrimitive): Any = json.content.toDouble().toLong()
}

/** Any number, optionally within [min] and [max] (both included). */
@Serializable
@SerialName("number")
data class NumberSetting(
    override val description: String,
    val default: Double,
    /** The least it may be. */
    val min: Double? = null,
    /** The most it may be. */
    val max: Double? = null
) : SettingDef {
    override val defaultValue get() = JsonPrimitive(default)
    override val expects get() = "a number" + range(min?.let(::shown), max?.let(::shown))

    override fun read(json: JsonElement): SettingRead {
        val number = (json as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return bad(json, expects)
        return within(number)
    }

    override fun parse(text: String): SettingRead {
        val number = text.trim().toDoubleOrNull() ?: return SettingRead.Bad("\"${text.trim()}\" isn't $expects")
        return within(number)
    }

    private fun within(number: Double): SettingRead {
        if (number.isNaN() || number.isInfinite()) return SettingRead.Bad("$number isn't $expects")
        if (min != null && number < min || max != null && number > max) return SettingRead.Bad("${shown(number)} isn't $expects")
        return SettingRead.Ok(JsonPrimitive(number))
    }

    override fun value(json: JsonPrimitive): Any = json.content.toDouble()
}

/** Any text. */
@Serializable
@SerialName("string")
data class StringSetting(override val description: String, val default: String) : SettingDef {
    override val defaultValue get() = JsonPrimitive(default)
    override val expects get() = "text"

    override fun read(json: JsonElement): SettingRead {
        val primitive = (json as? JsonPrimitive)?.takeIf { it.isString } ?: return bad(json, expects)
        return SettingRead.Ok(primitive)
    }

    override fun parse(text: String): SettingRead = SettingRead.Ok(JsonPrimitive(text))

    override fun value(json: JsonPrimitive): Any = json.content
}

/** One of a fixed list of words. */
@Serializable
@SerialName("choice")
data class ChoiceSetting(
    override val description: String,
    val default: String,
    /** What it may be, in the order they're offered. */
    val choices: List<String>
) : SettingDef {
    override val defaultValue get() = JsonPrimitive(default)
    override val expects get() = "one of " + choices.joinToString(", ")

    override fun read(json: JsonElement): SettingRead {
        val primitive = (json as? JsonPrimitive)?.takeIf { it.isString } ?: return bad(json, expects)
        return parse(primitive.content)
    }

    override fun parse(text: String): SettingRead =
        if (text in choices) SettingRead.Ok(JsonPrimitive(text)) else SettingRead.Bad("\"$text\" isn't $expects")

    override fun value(json: JsonPrimitive): Any = json.content
}

private fun bad(json: JsonElement, expects: String) = SettingRead.Bad("$json isn't $expects")

private fun shown(number: Double) = CanonicalJson.formatNumber(number.toString())

private fun range(min: Any?, max: Any?): String = when {
    min != null && max != null -> " from $min to $max"
    min != null -> " of at least $min"
    max != null -> " of at most $max"
    else -> ""
}
