package dev.netherforge.format.workspace

import dev.netherforge.format.json.CanonicalJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkspaceTest {
    /** The folders under the workspace that hold a netherforge.json, as the host finds them. */
    private val projects = listOf("servers/hub", "servers/chess", "servers/terra", "packages/chess", "tools/scratch")

    private fun read(text: String): WorkspaceFile =
        assertIs<CanonicalJson.Parsed.Ok<WorkspaceFile>>(WorkspaceKind.parse(text, WorkspaceFile.FILE_NAME)).value

    private fun codes(workspace: WorkspaceFile) = Workspaces.check(workspace, projects).problems.map { it.code to it.path }

    @Test
    fun `projects are folders or every project directly in one, in order`() {
        val workspace = read("""{ "name": "Polsar", "projects": ["servers/*", "./packages/chess/", "servers/hub"] }""")
        val check = Workspaces.check(workspace, projects)
        assertEquals(emptyList(), check.problems)
        assertEquals(listOf("servers/chess", "servers/hub", "servers/terra", "packages/chess"), check.members)
        assertEquals(listOf("packages/chess"), Workspaces.expand(listOf("packages/*"), projects))
        assertEquals(listOf("tools/scratch"), Workspaces.expand(listOf("tools/*"), projects))
    }

    @Test
    fun `a workspace holds only what's under it`() {
        for (bad in listOf("../other", "/abs", "a/../b", "a\\b", "", ".", "servers/*/x", "a/./b", "C:/x")) {
            assertTrue(!Workspaces.isPattern(bad), bad)
        }
        for (good in listOf("servers/hub", "servers/*", "*", "my project/v2.1")) {
            assertTrue(Workspaces.isPattern(good), good)
        }
    }

    @Test
    fun `every mistake has its code, at its place`() {
        val workspace = read(
            """
            {
              "name": " ",
              "projects": ["../escape", "servers/missing", "empty/*", "servers/*"],
              "network": {
                "arrival": "lobby",
                "servers": { "hub": "servers/hub", "Bad Name": "servers/chess", "ghost": "servers/missing" }
              }
            }
            """.trimIndent()
        )
        assertEquals(
            listOf(
                "workspace.name" to "$.name",
                "workspace.project-path" to "$.projects[0]",
                "workspace.missing-project" to "$.projects[1]",
                "workspace.empty-pattern" to "$.projects[2]",
                "workspace.server-name" to "$.network.servers[\"Bad Name\"]",
                "workspace.server-project" to "$.network.servers.ghost",
                "workspace.arrival" to "$.network.arrival"
            ),
            codes(workspace)
        )
    }

    @Test
    fun `it's written canonically, each project once`() {
        val workspace =
            read(
                """{ "network": { "servers": { "terra": "servers/terra", "hub": "servers/hub" }, "arrival": "hub" }, "projects": ["servers/hub/", "servers/hub", "packages/*"], "name": "Polsar" }"""
            )
        assertEquals(
            """
            {
              "${'$'}schema": ".netherforge/schema/workspace.schema.json",
              "name": "Polsar",
              "projects": ["servers/hub", "packages/*"],
              "network": {
                "arrival": "hub",
                "servers": {
                  "hub": "servers/hub",
                  "terra": "servers/terra"
                }
              }
            }

            """.trimIndent(),
            WorkspaceKind.write(workspace)
        )
    }
}
