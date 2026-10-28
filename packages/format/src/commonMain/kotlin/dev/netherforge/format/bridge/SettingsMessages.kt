package dev.netherforge.format.bridge

import dev.netherforge.format.settings.SettingDef
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Every package's server-owner settings on the dev server, each with the value it runs with. */
@Serializable
data class ServerSettings(val packages: List<PackageSettings>)

/** One package's settings: the project's own, or a dependency's. */
@Serializable
data class PackageSettings(
    val namespace: String,
    /** The package's name, `netherforge.json`'s. */
    val name: String,
    /** Where the server keeps its owner's values for it, relative to the server's folder. */
    val file: String,
    /** By name, as `netherforge.json` declares them. */
    val settings: Map<String, SettingState>
)

/** A setting as the server runs it. */
@Serializable
data class SettingState(
    val definition: SettingDef,
    /** What scripts read now: the owner's value, or the default. */
    val value: JsonElement,
    /** Whether the owner set it; otherwise [value] is the default. */
    val set: Boolean = false,
    /** Why the value in the settings file wasn't used, when it couldn't be. */
    val problem: String? = null
)

/** Sets a setting of [namespace]'s; a [value] left out puts it back to its default. */
@Serializable
data class SetSettingParams(val namespace: String, val setting: String, val value: JsonElement? = null)
