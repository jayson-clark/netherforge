package dev.netherforge.format

import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.Kinds
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

// What the export files share. Internal, so none of it is part of the JS surface.

internal fun sizes(json: String): Map<String, List<Int>> = CanonicalJson.json.decodeFromString(json)

internal fun gameData(json: String) = CanonicalJson.json.decodeFromString(GameDataBundle.serializer(), json)

internal fun kindOf(id: String) = requireNotNull(Kinds.byId(id)) { "there's no kind \"$id\"" }

internal fun files(files: Map<String, String>): String = encode(MapSerializer(String.serializer(), String.serializer()), files)

internal fun <T> encode(serializer: KSerializer<T>, value: T): String = CanonicalJson.json.encodeToString(serializer, value)
