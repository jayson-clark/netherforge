package dev.netherforge.format.particle

import dev.netherforge.format.TestFiles
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.loadTestProject
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.fail

/**
 * What the showcase's effects spawn, tick by tick from seed 7, without looping: `testdata/particles/samples.json`
 * (`UPDATE_GOLDEN=1` rewrites it), which the Rust port's sampler is held to. Numbers agree to 1e-12 (relative,
 * past 1): the samples go through `sin` and `cos`, whose last bits differ between the JVM's, JS's and Rust's libm.
 */
class EffectSamplesGoldenTest {
    @Test
    fun theShowcasesEffectsSampleAsTheGoldenSays() {
        val effects = loadTestProject("examples", "showcase").running(ParticleEffectKind)
        val samples = effects.entries.sortedBy { it.key }.associate { (id, effect) ->
            val sampler = EffectSampler(effect, 7)
            id to List(effect.duration) { sampler.step(loop = false) }
        }
        val serializer = MapSerializer(String.serializer(), ListSerializer(ListSerializer(EffectSpawn.serializer())))
        val actual = CanonicalJson.write(serializer, samples)
        val path = "packages/format/testdata/particles/samples.json"
        if (TestFiles.updateGolden) {
            if (actual != TestFiles.read(path)) TestFiles.write(path, actual)
            return
        }
        val golden = Json.parseToJsonElement(TestFiles.read(path)!!)
        near(golden, Json.parseToJsonElement(actual), "$")?.let { fail("$path differs at $it. Run with UPDATE_GOLDEN=1 to accept.") }
    }

    /** Where [got] first differs from [want], numbers within 1e-12 of each other. */
    private fun near(want: JsonElement, got: JsonElement, at: String): String? = when {
        want is JsonObject && got is JsonObject ->
            if (want.keys != got.keys) {
                "$at: keys ${got.keys}, not ${want.keys}"
            } else {
                want.keys.firstNotNullOfOrNull { near(want.getValue(it), got.getValue(it), "$at.$it") }
            }
        want is JsonArray && got is JsonArray ->
            if (want.size != got.size) {
                "$at: ${got.size} items, not ${want.size}"
            } else {
                want.indices.firstNotNullOfOrNull { near(want[it], got[it], "$at[$it]") }
            }
        number(want) != null && number(got) != null -> {
            val (a, b) = number(want)!! to number(got)!!
            if (abs(a - b) > 1e-12 * max(abs(a), 1.0)) "$at: $b, not $a" else null
        }
        else -> if (want == got) null else "$at: $got, not $want"
    }

    private fun number(element: JsonElement): Double? = (element as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
}
