@file:JsExport

package dev.netherforge.format

import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.project.ClassifiedPath
import dev.netherforge.format.project.Kinds
import dev.netherforge.format.project.TemplateNeedsGame
import dev.netherforge.format.project.Templates
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonPrimitive

// What the editor creates, and where: new projects, resources and scripts from format's templates, and which
// resource a path is.

/** The files of a new, empty project, its namespace made from [name]: `{ path: text }`. */
fun newProjectFiles(name: String, minecraft: String): String = files(Templates.newProject(name, minecraft))

/** A project's `AGENTS.md` and `CLAUDE.md`, for one that doesn't have them yet: `{ path: text }`. */
fun newAgentFiles(name: String): String = files(Templates.newAgentFiles(name))

/**
 * What a project path is, as [Kinds.classify] answers: a [ClassifiedPath]
 * (JSON), or `null` for a file no resource or project file owns.
 */
fun classify(path: String): String = Kinds.classify(path)?.let { encode(ClassifiedPath.serializer(), it) } ?: "null"

/**
 * The files of a new resource of [kind] (a `KindSpec.id` whose kind has a template), named [id], given [gameDataJson]
 * (a [GameDataBundle], or null before an import): `{ "files": { path: text } }`, or `{ "needsGame": why }` for a kind
 * whose new files need game data there isn't ([TemplateNeedsGame]).
 */
fun newResourceFiles(kind: String, id: String, gameDataJson: String?, group: String?): String = try {
    "{\"files\":${files(Templates.newResource(kindOf(kind), id, gameDataJson?.let(::gameData), group))}}"
} catch (e: TemplateNeedsGame) {
    "{\"needsGame\":${JsonPrimitive(e.message.orEmpty())}}"
}

/**
 * Where a resource of [kind] named [id] is read from, in [group] folders as on disk (`(weapons)/(swords)`, or null):
 * its main file (`items/(weapons)/sword/item.json`), or its one file for a single-file kind (`recipes/ruby.json`).
 */
fun resourcePath(kind: String, id: String, group: String?): String = kindOf(kind).pathOf(id, group?.ifEmpty { null })

/** What a new script of [kind] (a kind whose resources run Lua) starts as; [id] is the resource's. */
fun newScript(kind: String, id: String): String = Templates.script(kindOf(kind), id)

/** What a new Lua file beside a [kind]'s script (one the script requires) starts as. */
fun newSiblingFile(kind: String): String = Templates.sibling(kindOf(kind))
