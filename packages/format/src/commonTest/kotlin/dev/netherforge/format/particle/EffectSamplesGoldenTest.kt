package dev.netherforge.format.particle

import dev.netherforge.format.TestFiles
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.loadTestProject
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.fail

/**
 * What the showcase's effects spawn, tick by tick from seed 7, without looping: `testdata/particles/samples.json`
 * (`UPDATE_GOLDEN=1` rewrites it), which the Rust port's sampler is held to.
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
        if (actual == TestFiles.read(path)) return
        if (TestFiles.updateGolden) TestFiles.write(path, actual) else fail("$path differs. Run with UPDATE_GOLDEN=1 to accept.")
    }
}
