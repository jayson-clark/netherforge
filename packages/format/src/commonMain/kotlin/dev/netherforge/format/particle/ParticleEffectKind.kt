package dev.netherforge.format.particle

import dev.netherforge.format.game.GameData
import dev.netherforge.format.item.ItemKind
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.DocumentResourceKind
import dev.netherforge.format.project.KindContext
import dev.netherforge.format.project.Layout
import dev.netherforge.format.project.ResourceContext

/** `particles/<id>/effect.json`: a particle effect scripts play. */
object ParticleEffectKind : DocumentResourceKind<ParticleEffectFile, CompiledEffect>(
    "particle_effect",
    "particles",
    Layout.Folder(ParticleEffectFile.FILE_NAME),
    ParticleEffectFile.serializer(),
    ParticleEffectFile.SCHEMA
) {
    // Curve keys in time order, like keyframes; emitters are a map, so the writer orders them.
    override fun canonical(value: ParticleEffectFile) = value.copy(
        schema = schemaRef,
        emitters = value.emitters.mapValues { (_, emitter) ->
            val curves = emitter.curves ?: return@mapValues emitter
            emitter.copy(
                curves = curves.copy(
                    rate = curves.rate?.sortedBy { it.time },
                    size = curves.size?.sortedBy { it.time },
                    speed = curves.speed?.sortedBy { it.time },
                    radius = curves.radius?.sortedBy { it.time },
                    color = curves.color?.sortedBy { it.time }
                )
            )
        }
    )

    override fun validate(value: ParticleEffectFile, ctx: ResourceContext) {
        ParticleEffectValidator.validate(value, ctx.sink, ctx.game)
    }

    override fun crossCheck(value: ParticleEffectFile, ctx: KindContext) {
        for ((name, emitter) in value.emitters) {
            emitter.item?.let { ItemKind.checkStack(it, CanonicalJson.childPath("$.emitters", name) + ".item", ctx) }
        }
    }

    override fun compile(id: String, value: ParticleEffectFile, ctx: ResourceContext) = ParticleEffectCompiler.compile(id, value, ctx.game)

    /**
     * A one-second burst of sparkles. The particle id is starter content the
     * user edits, like the other templates' sample text.
     */
    override fun template(id: String, game: GameData?) = mapOf(
        pathOf(id) to write(
            ParticleEffectFile(
                duration = 20,
                emitters = mapOf("main" to EmitterDef(particle = "minecraft:happy_villager", burst = 10, shape = SphereShape(radius = 0.5)))
            )
        )
    )
}
