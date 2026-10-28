@file:JsExport

package dev.netherforge.format

import dev.netherforge.format.editor.WorkspaceResult
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.workspace.NetworkUse
import dev.netherforge.format.workspace.NetworkUses
import dev.netherforge.format.workspace.WorkspaceFile
import dev.netherforge.format.workspace.WorkspaceKind
import dev.netherforge.format.workspace.Workspaces
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

// A workspace: several projects kept together under one netherforge-workspace.json.

/**
 * Reads and checks a workspace file [text] against [projectsJson], every folder under the
 * workspace (relative, `/`-separated) that holds a `netherforge.json`: a [WorkspaceResult].
 */
fun checkWorkspace(text: String, projectsJson: String): String {
    val projects = CanonicalJson.json.decodeFromString(ListSerializer(String.serializer()), projectsJson)
    return when (val parsed = WorkspaceKind.parse(text, WorkspaceFile.FILE_NAME)) {
        is CanonicalJson.Parsed.Failed -> encode(WorkspaceResult.serializer(), WorkspaceResult(null, emptyList(), listOf(parsed.problem)))
        is CanonicalJson.Parsed.Ok -> {
            val check = Workspaces.check(parsed.value, projects)
            encode(WorkspaceResult.serializer(), WorkspaceResult(parsed.value, check.members, check.problems))
        }
    }
}

/** The network names [source] (a Lua script) uses as literals: [NetworkUse]s, as JSON. */
fun networkUses(source: String): String = encode(ListSerializer(NetworkUse.serializer()), NetworkUses.of(source))

/**
 * What's wrong with [scriptsJson]'s (Lua source by project path) network names, given
 * [serversJson], the servers of the workspace's dev network: problems, as JSON.
 */
fun checkNetworkUses(scriptsJson: String, serversJson: String): String {
    val scripts = CanonicalJson.json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), scriptsJson)
    val servers = CanonicalJson.json.decodeFromString(ListSerializer(String.serializer()), serversJson)
    return encode(ListSerializer(Problem.serializer()), NetworkUses.check(scripts, servers))
}

/** Whether [name] can name a server in a workspace's dev network. */
fun isWorkspaceServerName(name: String): Boolean = Workspaces.isServerName(name)
