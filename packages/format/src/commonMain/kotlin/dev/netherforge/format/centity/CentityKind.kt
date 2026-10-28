package dev.netherforge.format.centity

import dev.netherforge.format.game.GameData
import dev.netherforge.format.project.DocumentResourceKind
import dev.netherforge.format.project.Layout
import dev.netherforge.format.project.ResourceContext
import dev.netherforge.format.project.ScriptSpec

/** `centities/<id>/centity.json`: a composed, scripted entity. */
object CentityKind : DocumentResourceKind<CentityFile, CompiledCentity>(
    "centity",
    "centities",
    Layout.Folder(CentityFile.FILE_NAME),
    CentityFile.serializer(),
    CentityFile.SCHEMA
) {
    override fun canonical(value: CentityFile) = value.copy(
        schema = schemaRef,
        spawning = value.spawning?.let { spawning ->
            spawning.copy(
                worlds = spawning.worlds?.distinct()?.sorted(),
                biomes = spawning.biomes?.distinct()?.sorted(),
                blocks = spawning.blocks?.distinct()?.sorted(),
                moonPhase = spawning.moonPhase?.distinct()?.sorted(),
                weather = spawning.weather?.distinct()?.sorted()
            )
        },
        animations = value.animations.mapValues { (_, clip) ->
            clip.copy(tracks = clip.tracks.mapValues { (_, channels) -> channels.mapValues { (_, keys) -> keys.sortedBy { it.time } } })
        }
    )

    override val script = ScriptSpec("Centity") {
        "-- Runs once per instance, every time it starts: spawning, a restart, a reload.\n\nthis:on(\"click\", function(event)\nend)\n"
    }

    override fun scriptOf(value: CentityFile) = value.script

    override fun validate(value: CentityFile, ctx: ResourceContext) = CentityValidator.validate(value, ctx.sink, ctx.files, ctx.game)

    override fun compile(id: String, value: CentityFile, ctx: ResourceContext) = CentityCompiler.compile(id, value)

    /** One stone block with a hitbox. */
    override fun template(id: String, game: GameData?) = mapOf(
        pathOf(id) to write(CentityFile(nodes = mapOf("root" to NodeDef(display = BlockDisplay("minecraft:stone"), hitbox = HitboxDef()))))
    )
}
