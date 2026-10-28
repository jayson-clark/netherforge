package dev.netherforge.format.project

import dev.netherforge.format.TestFiles
import dev.netherforge.format.centity.CentityKind
import dev.netherforge.format.dialog.DialogKind
import dev.netherforge.format.item.ItemKind
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.menu.MenuKind
import dev.netherforge.format.module.ModuleKind
import dev.netherforge.format.particle.ParticleEffectKind
import dev.netherforge.format.recipe.RecipeKind
import dev.netherforge.format.resourcepack.ResourcePackKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the editor creates must load clean and already be in its saved form. */
class TemplatesTest {

    @Test
    fun newResourcesLoadCleanAndAreCanonical() {
        val files = linkedMapOf<String, String?>()
        files += Templates.newProject("Test", "26.3")
        files += Templates.newResource(CentityKind, "tower")
        files += Templates.newResource(MenuKind, "village_shop")
        files += Templates.newResource(DialogKind, "welcome")
        files += Templates.newResource(ParticleEffectKind, "sparkle")
        files += Templates.newResource(ResourcePackKind, "ui")
        files += Templates.newResource(ItemKind, "ruby_key")
        files += Templates.newResource(RecipeKind, "key_from_stick")
        files += Templates.newResource(ModuleKind, "greeter")
        val snapshot = Projects.load(MapProjectSource(files))
        assertEquals(emptyList(), snapshot.problems)
        assertEquals("Ruby key", snapshot.models(ItemKind).getValue("ruby_key").name)
        assertEquals("Village shop", snapshot.models(MenuKind).getValue("village_shop").title)
        assertEquals(listOf("sparkle"), snapshot.compiled(ParticleEffectKind).keys.toList())
        assertEquals(listOf("init.lua"), snapshot.models(ModuleKind).getValue("greeter").files)

        for ((path, text) in files) {
            val kind = Kinds.classify(path)?.document?.let(Kinds::document) ?: continue
            val parsed = kind.parse(text!!, path)
            assertTrue(parsed is CanonicalJson.Parsed.Ok, path)
            @Suppress("UNCHECKED_CAST")
            val write = (kind as DocumentKind<Any?>).write(parsed.value)
            assertEquals(text, write, "$path isn't canonical")
        }
    }

    @Test
    fun resourceScriptsTellLuaLanguageServerWhatThisIs() {
        for ((kind, className) in listOf(CentityKind to "Centity", MenuKind to "Menu", DialogKind to "Dialog", ItemKind to "ProjectItem")) {
            val text = Templates.script(kind, "x")
            assertTrue(text.startsWith("local this = this --[[@as $className]]\n"), text)
            assertTrue("this:on(" in text, text)
            // A file beside the script runs as part of it: the same `this`, and a table to require.
            val sibling = Templates.sibling(kind)
            assertTrue(sibling.startsWith("local this = this --[[@as $className]]\n"), sibling)
            assertTrue(sibling.endsWith("return M\n"), sibling)
        }
        assertTrue(!Templates.script(ModuleKind, "greeter").contains("this"))
        assertEquals("local M = {}\n\nreturn M\n", Templates.sibling(ModuleKind))
    }

    @Test
    fun newProjectsComeWithAgentInstructions() {
        val files = Templates.newProject("My server", "26.3")
        val agents = files.getValue(Templates.AGENTS_FILE)
        assertTrue(agents.startsWith("# My server\n"))
        assertTrue("${Templates.AGENT_DOCS}/README.md" in agents)
        assertTrue("node ${Templates.CLI_FILE} check" in agents)
        assertTrue("`get_problems`" in agents, "names the MCP tools")
        assertEquals("@AGENTS.md\n", files[Templates.CLAUDE_FILE])
        // `.mcp.json` and `opencode.json` carry this computer's MCP token: never committed.
        val ignored = files.getValue(".gitignore").lines()
        assertTrue(".mcp.json" in ignored, files.getValue(".gitignore"))
        assertTrue("opencode.json" in ignored, files.getValue(".gitignore"))
        assertTrue(".netherforge/" in ignored)
    }

    @Test
    fun newProjectsComeWithTheExamplesLuaLanguageServerSettings() {
        val files = Templates.newProject("My server", "26.3")
        assertEquals(TestFiles.read("examples/showcase/.luarc.json"), files[Templates.LUARC_FILE])
    }
}
