package dev.netherforge.format.cutscene

import dev.netherforge.format.centity.Easing
import dev.netherforge.format.centity.Keyframe
import dev.netherforge.format.game.GameData
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.project.DocumentResourceKind
import dev.netherforge.format.project.Layout
import dev.netherforge.format.project.ResourceContext

/** `cutscenes/<id>.json`: a camera path scripts play for a player. One file, nothing beside it. */
object CutsceneKind : DocumentResourceKind<CutsceneFile, CompiledCutscene>(
    "cutscene",
    "cutscenes",
    Layout.SingleFile(".json"),
    CutsceneFile.serializer(),
    CutsceneFile.SCHEMA
) {
    // Keys in time order, like a centity's keyframes; cues keep a time's order (it's the order they fire in).
    override fun canonical(value: CutsceneFile) = value.copy(
        schema = schemaRef,
        camera = Camera(
            position = value.camera.position.sortedBy { it.time },
            rotation = value.camera.rotation.sortedBy { it.time }
        ),
        cues = value.cues.sortedBy { it.time }
    )

    override fun validate(value: CutsceneFile, ctx: ResourceContext) {
        CutsceneValidator.validate(value, ctx.sink)
    }

    override fun compile(id: String, value: CutsceneFile, ctx: ResourceContext) = CutsceneCompiler.compile(id, value)

    /**
     * A two-second dolly forward, looking slightly down. Starter content the
     * user replaces in the cutscene editor: its coordinates are the origin's,
     * to be played with an `origin` or edited to where the shot is.
     */
    override fun template(id: String, game: GameData?) = mapOf(
        pathOf(id) to write(
            CutsceneFile(
                camera = Camera(
                    position = listOf(
                        Keyframe(0.0, Vec3(0.0, 64.0, 0.0)),
                        Keyframe(2.0, Vec3(0.0, 64.0, 8.0), Easing.EASE_IN_OUT)
                    ),
                    rotation = listOf(RotationKey(0.0, yaw = 0.0, pitch = 10.0))
                )
            )
        )
    )
}
