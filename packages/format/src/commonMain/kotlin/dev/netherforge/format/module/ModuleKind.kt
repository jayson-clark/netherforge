package dev.netherforge.format.module

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.game.GameData
import dev.netherforge.format.project.Contents
import dev.netherforge.format.project.FilesKind
import dev.netherforge.format.project.KindContext
import dev.netherforge.format.project.Layout
import dev.netherforge.format.project.Names
import dev.netherforge.format.project.ResourceContext
import dev.netherforge.format.project.ScriptSpec
import dev.netherforge.format.project.Templates

/**
 * `modules/<id>/`: a folder of Lua other scripts `require`, run from [ENTRY]
 * if it has one. It has no JSON file: any file makes a folder a module. One
 * without [ENTRY] is a library, warned about only while nothing requires it.
 */
object ModuleKind : FilesKind<ModuleInfo>("module", "modules", Layout.Folder(null), Contents.LUA) {
    /** The file a module runs on load. Without it, a module only runs when required. */
    const val ENTRY = "init.lua"

    override val script = ScriptSpec(null) { id -> "-- Runs when the server loads the \"$id\" module.\n" }

    override fun of(id: String, files: List<String>) = ModuleInfo(id, files.filter { it.endsWith(".lua") })

    override fun validate(value: ModuleInfo, ctx: ResourceContext) {
        for (file in value.files) {
            if (!Names.isLuaFile(file)) {
                ctx.problem(
                    ProblemCodes.MODULE_FILE_NAME,
                    ctx.pathIn(this, file),
                    "Module files must be named with letters, digits and _ so require() can reach them"
                )
            }
        }
        if (value.files.isEmpty()) {
            ctx.sink.report(ProblemCodes.MODULE_EMPTY, "Module \"${value.id}\" has no .lua files")
        }
    }

    /** A module with no [ENTRY] is a library: fine while some script requires it, never run otherwise. */
    override fun crossCheck(value: ModuleInfo, ctx: KindContext) {
        if (value.files.isNotEmpty() && !value.hasInit && value.id !in ctx.requiredModules) {
            ctx.sink.report(ProblemCodes.MODULE_NO_INIT, "Module \"${value.id}\" has no $ENTRY and no script requires it, so it never runs")
        }
    }

    /** Its [ENTRY], starting as the module's script template. */
    override fun template(id: String, game: GameData?) = mapOf(fileOf(id, ENTRY) to Templates.script(this, id))
}
