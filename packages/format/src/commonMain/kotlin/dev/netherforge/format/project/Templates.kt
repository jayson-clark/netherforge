package dev.netherforge.format.project

import dev.netherforge.format.centity.CentityKind
import dev.netherforge.format.game.GameData
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.module.ModuleKind

/**
 * The files NetherForge creates. Generated through the canonical writers so a
 * new file is already in its saved form.
 */
object Templates {

    /**
     * A new project named [name]: its namespace is [namespace], or one made
     * from the name ([namespaceFor]) when that's null, and it starts at
     * version [FIRST_VERSION].
     */
    fun newProject(name: String, minecraft: String, namespace: String? = null): Map<String, String> = linkedMapOf(
        ProjectManifest.FILE_NAME to
            ManifestKind.write(
                ProjectManifest(
                    formatVersion = FormatVersion.CURRENT,
                    name = name,
                    namespace = namespace ?: namespaceFor(name),
                    version = FIRST_VERSION,
                    minecraft = minecraft
                )
            ),
        ".gitignore" to GITIGNORE,
        LUARC_FILE to LUARC,
        "${CentityKind.folder}/.gitkeep" to "",
        "${ModuleKind.folder}/.gitkeep" to ""
    ) + newAgentFiles(name)

    /**
     * What a coding agent reads first: `AGENTS.md` (Codex, Cursor, Aider, …)
     * and a `CLAUDE.md` that includes it (Claude Code). They're the user's
     * files, committed; they point into [AGENT_DOCS], which the editor
     * rewrites for its own version every time it opens the project.
     */
    fun newAgentFiles(name: String): Map<String, String> = linkedMapOf(
        AGENTS_FILE to agentsMd(name),
        CLAUDE_FILE to "@$AGENTS_FILE\n"
    )

    /** The version a new project starts at. */
    const val FIRST_VERSION = "0.1.0"

    /**
     * A namespace for a project called [name]: lowercased, every run of
     * other characters one `_`, cut to fit [Names.NAMESPACE]; `project` when
     * nothing usable is left, and `_project` added to a reserved one.
     */
    fun namespaceFor(name: String): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(64).trimEnd('_')
        return when {
            base.isEmpty() -> "project"
            base in Names.RESERVED_NAMESPACES -> base + "_project"
            else -> base
        }
    }

    const val AGENTS_FILE = "AGENTS.md"
    const val CLAUDE_FILE = "CLAUDE.md"

    /** The docs and an index the editor writes for agents, matching its version. */
    const val AGENT_DOCS = ".netherforge/docs"

    /**
     * What the editor writes for lua-language-server, the `workspace.library`
     * of [LUARC]: the API's stubs for its version (`nf.lua`, with what this
     * project can't use marked deprecated, and `surfaces/`) and the project's
     * own names (its nodes, buttons, centities...).
     */
    const val LUALS_LIBRARY = ".netherforge/luals"

    /** The `netherforge` command (`check`, `format`, `test`, `preview`...), a single Node script the editor writes. */
    const val CLI_FILE = ".netherforge/bin/netherforge.mjs"

    private fun agentsMd(name: String): String = """
        |# ${name.lines().first().trim().ifEmpty { "NetherForge project" }}
        |
        |A NetherForge project: Minecraft server content (centities, Lua modules,
        |menus, dialogs, resource packs) as plain files, run by the NetherForge
        |plugin on a Paper server. `netherforge.json` names the target Minecraft version.
        |
        |## Before you change anything
        |
        |Read `$AGENT_DOCS/README.md`. The NetherForge editor writes it when it opens
        |the project, and `npx @netherforge/cli setup` writes it without the editor,
        |with the file format, the Lua API and the guides for the exact version in use.
        |Trust it over anything you remember or find online. If `.netherforge/` is
        |missing, run `npx @netherforge/cli setup` in the project's folder.
        |
        |## Rules
        |
        |- Read the format page for a kind of file (`$AGENT_DOCS/format/<kind>.md`)
        |  before changing one. Every JSON file's `${'$'}schema` points at its JSON Schema.
        |- Use only the Lua API declared in `$LUALS_LIBRARY/nf.lua`. Scripts run in a
        |  sandbox without `io`, `os`, `package`, `load` or `debug`.
        |- Lua never goes inside JSON: scripts are `.lua` files next to the JSON that
        |  names them.
        |- Ids are folder names: lowercase letters, digits and `_`.
        |- Never edit `.netherforge/`: it's generated and not committed.
        |
        |## Check your work
        |
        |- `node $CLI_FILE check` validates the whole project as the editor does and
        |  prints `file:line: severity: message (at ${'$'}.json.path)`. Finish with no errors.
        |- `node $CLI_FILE format` rewrites project JSON in canonical form, so diffs
        |  stay small.
        |- `node $CLI_FILE test` runs the project's script tests (`*_test.lua`, written
        |  with `nf.test`: see `$AGENT_DOCS/guide/testing.md`) on a fake server and
        |  prints each test's verdict and the line it failed at. Add one for logic you
        |  write, and finish with every test passing.
        |- `node $CLI_FILE preview terrain/<id>.json --seed 42 --out map.png` draws a
        |  terrain as the editor's preview does (`--slice x --at 0` for the
        |  ground through a line), so you can look at terrain you changed. See
        |  `$AGENT_DOCS/guide/world-generation.md`.
        |- With the NetherForge editor open, its `netherforge` MCP server (`.mcp.json`, `opencode.json`)
        |  has tools for the rest: `get_problems` after editing, `reload` and then
        |  `get_console` to see a change run on the dev server, and
        |  `lookup_game_data` instead of guessing Minecraft ids. They hold
        |  tokens for it: never commit them.
        |- You can't see the game. Say what to look at in game after a reload
        |  when a change is visual.
        |
    """.trimMargin()

    /**
     * The files of a new resource of [kind], `{ path: text }`; [id] is
     * already checked, [game] the server's game data when there is some.
     * Each kind's [KindSpec.template] says what they are, and throws
     * [TemplateNeedsGame] when it needs [game] and there's none. In [group]
     * folders (`(weapons)`), its files are there instead of in the kind's own.
     */
    fun newResource(kind: KindSpec<*, *>, id: String, game: GameData? = null, group: String? = null): Map<String, String> {
        val files = requireNotNull(kind.template(id, game)) { "a new ${kind.id} isn't made from a template" }
        if (group.isNullOrEmpty()) return files
        require(kind.groupable) { "a ${kind.id} can't go in group folders" }
        // Every file a template makes is in the kind's folder: its resource's folder, its one file, its companion.
        return files.entries.associate { (path, text) ->
            val moved = "${kind.folder}/$group/" + path.removePrefix("${kind.folder}/")
            moved to rewrittenAt(moved, text)
        }
    }

    /** A template's document [text] written again as the file [path], so its `$schema` reaches out of the group folders. */
    private fun rewrittenAt(path: String, text: String): String {
        val kind = Kinds.classify(path)?.document?.let(Kinds::document) ?: return text
        return rewrittenAs(kind, path, text)
    }

    private fun <T> rewrittenAs(kind: DocumentKind<T>, path: String, text: String): String = when (val parsed = kind.parse(text, path)) {
        is CanonicalJson.Parsed.Ok -> kind.write(parsed.value, path)
        is CanonicalJson.Parsed.Failed -> text
    }

    /** `village_shop` → `Village shop`. */
    internal fun titleOf(id: String): String = id.replace('_', ' ').trim().replaceFirstChar { it.uppercaseChar() }.ifEmpty { id }

    const val GITIGNORE = "# What the NetherForge editor generates: schemas, agent docs, cached pictures.\n.netherforge/\n" +
        "# Connects coding agents to this computer's editor, with its token: not for sharing.\n.mcp.json\nopencode.json\n" +
        "# Bundles `netherforge build` writes, for a production server: built again from the project, never committed.\n/build/\n"

    const val LUARC_FILE = ".luarc.json"

    /**
     * lua-language-server's settings for the project (VS Code, Neovim): Lua
     * 5.4 without what the sandbox removes, `require` reaching `modules/`, and
     * the stubs the editor writes into [LUALS_LIBRARY] (the API's, with what
     * this project can't use marked deprecated, and the project's names) as
     * its library. `hint.awaitPropagate` makes a function that calls one that
     * waits (`---@async`) wait too, so `not-yieldable` flags one handed to
     * anything but `nf.task` (an event handler that waits). Written once, with
     * the project; it's the user's to change. The same as
     * `examples/showcase/.luarc.json`, which a test holds it to.
     */
    const val LUARC = """{
  "runtime.version": "Lua 5.4",
  "runtime.path": ["modules/?.lua", "modules/?/init.lua"],
  "runtime.builtin": { "io": "disable", "os": "disable", "debug": "disable" },
  "workspace.library": ["$LUALS_LIBRARY"],
  "diagnostics.disable": ["lowercase-global"],
  "diagnostics.neededFileStatus": { "not-yieldable": "Any" },
  "hint.awaitPropagate": true
}
"""

    /**
     * What a new script of a [kind] that runs Lua starts as ([KindSpec.script]);
     * [id] is the resource's (a module names itself with it).
     *
     * A resource script starts with a line telling lua-language-server what
     * `this` is: it's a different class in a centity, menu and dialog script,
     * and LuaLS (which ignores a `.luarc.json` in a subfolder) only knows the
     * union `Centity|Menu|Dialog` otherwise. A local alias cast with `@as`
     * narrows it for the whole file, closures included, with no warning (checked
     * with lua-language-server 3.15): `---@type` on the alias warns that the
     * union can't be assigned, and `---@cast` on the global is meant for locals
     * and warns when nothing after it reads `this` directly.
     */
    fun script(kind: KindSpec<*, *>, id: String): String {
        val script = requireNotNull(kind.script) { "a ${kind.id} runs no Lua" }
        return script.thisClass?.let(::surfaceHeader).orEmpty() + script.body(id)
    }

    /**
     * What a new Lua file beside a [kind]'s script starts as: a table to
     * `require`. Beside a resource's script it runs as part of that script,
     * so it gets the same header telling lua-language-server what `this` is.
     */
    fun sibling(kind: KindSpec<*, *>): String {
        val script = requireNotNull(kind.script) { "a ${kind.id} runs no Lua" }
        val header = script.thisClass?.let {
            surfaceHeader(it) + "-- Part of this resource's script: it runs once in each copy of the script that\n" +
                "-- requires it, with the script's globals and its `this`.\n\n"
        }
        return header.orEmpty() + "local M = {}\n\nreturn M\n"
    }

    /** The line that tells lua-language-server what `this` is in a resource script. */
    fun surfaceHeader(className: String): String = "local this = this --[[@as $className]]\n\n"
}
