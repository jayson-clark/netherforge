package dev.netherforge.format.screen

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.game.GameData
import dev.netherforge.format.project.DocumentResourceKind
import dev.netherforge.format.project.KindContext
import dev.netherforge.format.project.Layout
import dev.netherforge.format.project.ResourceContext
import dev.netherforge.format.project.ScriptSpec

/** `screens/<id>/screen.json`: a wall of maps scripts draw on and play videos on. */
object ScreenKind : DocumentResourceKind<ScreenFile, ScreenFile>(
    "screen",
    "screens",
    Layout.Folder(ScreenFile.FILE_NAME),
    ScreenFile.serializer(),
    ScreenFile.SCHEMA
) {
    override fun canonical(value: ScreenFile) = value.copy(schema = schemaRef)

    override val script = ScriptSpec("Screen") {
        "-- Runs when the server loads the screen: draw it here, and listen for its clicks.\n\n" +
            "this:clear(\"#101018\")\nthis:text(8, 8, \"Hello!\", { color = \"#ffffff\" })\n\n" +
            "this:on(\"click\", function(event)\nend)\n"
    }

    override fun scriptOf(value: ScreenFile) = value.script

    override fun validate(value: ScreenFile, ctx: ResourceContext) = ScreenValidator.validate(value, ctx.sink, ctx.files)

    /** Two screens hanging in the same block: the later one (by id) covers part of the earlier one. */
    override fun crossCheck(value: ScreenFile, ctx: KindContext) {
        val place = value.place ?: return
        val mine = blocks(value)
        for ((other, file) in ctx.models(ScreenKind)) {
            if (other >= ctx.id || file.place?.world != place.world) continue
            if (blocks(file).any { it in mine }) {
                ctx.sink.report(ProblemCodes.SCREEN_PLACE, "It hangs where screen \"$other\" does, in ${place.world}", "$.place")
                return
            }
        }
    }

    /** The blocks a placed screen's maps hang in, as `x,y,z`. */
    private fun blocks(file: ScreenFile): Set<String> {
        val place = file.place ?: return emptySet()
        val facing = place.facing
        return buildSet {
            for (column in 0 until file.width) {
                for (row in 0 until file.height) {
                    add("${place.x + facing.rightX * column},${place.y - row},${place.z + facing.rightZ * column}")
                }
            }
        }
    }

    override fun compile(id: String, value: ScreenFile, ctx: ResourceContext) = value

    /** Two maps across, one down, hanging nowhere until a script or the file says where. */
    override fun template(id: String, game: GameData?) = mapOf(
        pathOf(id) to write(ScreenFile(width = 2, height = 1, background = "#101018"))
    )
}
