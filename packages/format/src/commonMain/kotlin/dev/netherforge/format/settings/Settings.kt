package dev.netherforge.format.settings

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.Names
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The rules for `netherforge.json`'s `settings`: each name, description, range and default. */
object SettingsValidator {
    /** Checks [settings] (the manifest's, at `$.settings`) into [sink]. */
    fun validate(settings: Map<String, SettingDef>, sink: ProblemSink) {
        for ((name, setting) in settings) {
            val path = CanonicalJson.childPath("$.settings", name)
            if (!Names.isId(name)) {
                sink.report(ProblemCodes.PROJECT_SETTING_NAME, "\"$name\" isn't a setting name (${Names.ID_RULE})", path)
            }
            if (setting.description.isBlank()) {
                sink.report(
                    ProblemCodes.PROJECT_SETTING_DESCRIPTION,
                    "Setting \"$name\" has no description: whoever runs the project reads it beside the value",
                    "$path.description"
                )
            }
            if (!range(name, setting, path, sink)) continue
            if (setting is ChoiceSetting && !choices(name, setting, path, sink)) continue
            val read = setting.read(setting.defaultValue)
            if (read is SettingRead.Bad) {
                sink.report(ProblemCodes.PROJECT_SETTING_DEFAULT, "Setting \"$name\"'s default: ${read.message}", "$path.default")
            }
        }
    }

    /** False when the setting's range is backwards, so its default can't be checked against it. */
    private fun range(name: String, setting: SettingDef, path: String, sink: ProblemSink): Boolean {
        val (min, max) = when (setting) {
            is IntegerSetting -> setting.min?.toDouble() to setting.max?.toDouble()
            is NumberSetting -> setting.min to setting.max
            else -> return true
        }
        if (min == null || max == null || min <= max) return true
        sink.report(ProblemCodes.PROJECT_SETTING_RANGE, "Setting \"$name\"'s min is more than its max", "$path.min")
        return false
    }

    private fun choices(name: String, setting: ChoiceSetting, path: String, sink: ProblemSink): Boolean {
        if (setting.choices.isEmpty()) {
            sink.report(ProblemCodes.PROJECT_SETTING_CHOICES, "Setting \"$name\" has no choices", "$path.choices")
            return false
        }
        var ok = true
        val seen = HashSet<String>()
        setting.choices.forEachIndexed { index, choice ->
            val message = when {
                choice.isBlank() -> "Setting \"$name\"'s choice ${index + 1} is empty"
                !seen.add(choice) -> "Setting \"$name\" offers \"$choice\" twice"
                else -> null
            }
            if (message != null) {
                ok = false
                sink.report(ProblemCodes.PROJECT_SETTING_CHOICES, message, "$path.choices[$index]")
            }
        }
        return ok
    }
}

/**
 * A server's values for one package's settings: `plugins/NetherForge/settings/<namespace>.json`,
 * an object of the values its owner set, by setting name. A setting it
 * doesn't name has its default, so a package's new default reaches a server
 * whose owner never changed it.
 *
 * The file is the owner's: [read] reports what doesn't fit (a value of the
 * wrong kind, a setting the package doesn't declare) and keeps it all, and
 * [write] writes back every key it was given, so a value for a setting a
 * package dropped stays until the owner takes it out.
 */
object SettingValues {
    /** What a values file read as: [values] the owner set that fit, [file] everything it holds, and what didn't fit. */
    data class Read(val values: Map<String, JsonPrimitive>, val file: Map<String, JsonElement>, val problems: List<Problem>)

    /** A key of the values file that doesn't fit: [unknown] when no setting has its name, else a value of the wrong kind. */
    data class Problem(val setting: String, val message: String, val unknown: Boolean)

    /** Reads [text] against the [declared] settings; null when it isn't a JSON object. */
    fun read(text: String, declared: Map<String, SettingDef>): Read? {
        val json = runCatching { CanonicalJson.json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val values = LinkedHashMap<String, JsonPrimitive>()
        val problems = mutableListOf<Problem>()
        for ((name, value) in json) {
            val setting = declared[name]
            if (setting == null) {
                problems += Problem(name, "there's no setting \"$name\"", unknown = true)
                continue
            }
            when (val read = setting.read(value)) {
                is SettingRead.Ok -> values[name] = read.json
                is SettingRead.Bad -> problems += Problem(name, read.message, unknown = false)
            }
        }
        return Read(values, json, problems)
    }

    /** A values file holding [values], in canonical form (keys in order, numbers as everywhere). */
    fun write(values: Map<String, JsonElement>): String =
        CanonicalJson.print(JsonObject(values.keys.sorted().associateWith(values::getValue))) + "\n"
}
