package dev.netherforge.format.workspace

import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.lua.LuaStrings
import dev.netherforge.format.lua.LuaStrings.Token
import kotlinx.serialization.Serializable

/**
 * A name a script gives `nf.network` as a string literal: a server it sends players to
 * (`send_player(player, "chess")`), a channel or a shared map it gets (`channel("games")`).
 */
@Serializable
data class NetworkUse(val kind: Kind, val name: String, val line: Int) {
    @Serializable
    enum class Kind {
        @kotlinx.serialization.SerialName("server")
        SERVER,

        @kotlinx.serialization.SerialName("channel")
        CHANNEL,

        @kotlinx.serialization.SerialName("network_map")
        MAP
    }
}

/**
 * The network names scripts use, read from their source: what a workspace knows across its
 * projects (the servers its dev network has) that a project alone doesn't. Only literals count:
 * a name worked out at run time is the script's own business.
 */
object NetworkUses {
    /**
     * Every [NetworkUse] in [source]: a call of `send_player`, `channel` or `map` on `nf.network`
     * (or on a local named `network`), whose name argument is a string literal on its own.
     */
    fun of(source: String): List<NetworkUse> {
        val tokens = LuaStrings.tokens(source)
        val out = mutableListOf<NetworkUse>()
        for (i in 2 until tokens.size) {
            val fn = tokens[i]
            if (fn.kind != Token.Kind.NAME || !isPunct(tokens[i - 1], ".")) continue
            if (tokens[i - 2].kind != Token.Kind.NAME || tokens[i - 2].text != "network") continue
            val kind = when (fn.text) {
                "send_player" -> NetworkUse.Kind.SERVER
                "channel" -> NetworkUse.Kind.CHANNEL
                "map" -> NetworkUse.Kind.MAP
                else -> continue
            }
            val name = argument(tokens, i + 1, if (kind == NetworkUse.Kind.SERVER) 1 else 0) ?: continue
            out += NetworkUse(kind, name.text, name.line)
        }
        return out
    }

    /**
     * The [index]th argument of the call whose arguments start at [at] (its `(`, or a string
     * literal called with no parentheses), when it's a string literal and nothing more.
     */
    private fun argument(tokens: List<Token>, at: Int, index: Int): Token? {
        val open = tokens.getOrNull(at) ?: return null
        if (open.kind == Token.Kind.STRING) return open.takeIf { index == 0 }
        if (!isPunct(open, "(")) return null
        var depth = 0
        var argument = 0
        var start = at + 1
        for (k in at + 1 until tokens.size) {
            val token = tokens[k]
            when {
                token.kind == Token.Kind.PUNCT && token.text in OPENS -> depth++
                token.kind == Token.Kind.PUNCT && token.text in CLOSES && depth > 0 -> depth--
                depth == 0 && (isPunct(token, ",") || isPunct(token, ")")) -> {
                    if (argument == index) {
                        val only = tokens.subList(start, k)
                        return only.singleOrNull()?.takeIf { it.kind == Token.Kind.STRING }
                    }
                    if (isPunct(token, ")")) return null
                    argument++
                    start = k + 1
                }
            }
        }
        return null
    }

    private val OPENS = setOf("(", "{", "[")
    private val CLOSES = setOf(")", "}", "]")

    private fun isPunct(token: Token, text: String) = token.kind == Token.Kind.PUNCT && token.text == text

    /**
     * What's wrong with the network names in [scripts] (Lua source by project path) given the
     * workspace's dev network's [servers]: a player sent to a server it doesn't have.
     */
    fun check(scripts: Map<String, String>, servers: Collection<String>): List<Problem> {
        val problems = mutableListOf<Problem>()
        for ((file, source) in scripts) {
            for (use in of(source)) {
                if (use.kind != NetworkUse.Kind.SERVER || use.name in servers) continue
                problems += ProblemCodes.WORKSPACE_UNKNOWN_SERVER.at(
                    file,
                    "There's no server \"${use.name}\" in the workspace's network" +
                        if (servers.isEmpty()) "." else ": it has ${servers.sorted().joinToString(", ")}.",
                    line = use.line
                )
            }
        }
        return problems
    }
}
