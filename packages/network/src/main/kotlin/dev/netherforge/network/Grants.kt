package dev.netherforge.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a server may do with one channel or map, as a [Share] grants it. */
@Serializable
enum class Access(val word: String) {
    /** Hear a channel's messages; keep a copy of a map. */
    @SerialName("read")
    READ("read"),

    /** Publish on a channel; write a map. Reading comes with it. */
    @SerialName("write")
    WRITE("write"),

    /** Ask on a channel (`channel:request`). */
    @SerialName("request")
    REQUEST("request"),

    /** Be asked on a channel: answer its requests. */
    @SerialName("answer")
    ANSWER("answer");

    companion object {
        fun of(word: String): Access? = entries.firstOrNull { it.word == word }
    }
}

/**
 * Channels and maps shared with a server it doesn't own: [name] is `namespace:name`, or a pattern ending in `*`
 * (`events:*`, or `lobby:queue/` and a star), and [access] what it may do with each.
 */
@Serializable
data class Share(val name: String, val access: Set<Access>) {
    fun matches(full: String): Boolean = if (name.endsWith("*")) full.startsWith(name.dropLast(1)) else full == name

    /** As `hub.properties` spells it: `lobby:return(request+read)`. */
    override fun toString() = "$name(${access.sortedBy { it.ordinal }.joinToString("+") { it.word }})"

    companion object {
        /**
         * The shares in [text], `ns:name(access+access), ns:other(read)`; a bare name is `read`. Throws saying what's
         * wrong with an entry that isn't one.
         */
        fun parseList(text: String?): List<Share> {
            if (text.isNullOrBlank()) return emptyList()
            return text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map(::parse)
        }

        fun parse(entry: String): Share {
            val open = entry.indexOf('(')
            val name = (if (open < 0) entry else entry.substring(0, open)).trim()
            val words = if (open < 0) {
                listOf("read")
            } else {
                require(entry.endsWith(")")) { "\"$entry\" has a ( with no ) after it" }
                entry.substring(open + 1, entry.length - 1).split('+').map { it.trim() }
            }
            val access = words.map { word ->
                Access.of(word) ?: throw IllegalArgumentException("\"$entry\": \"$word\" isn't read, write, request or answer")
            }.toSet()
            val pattern = name.removeSuffix("*")
            require(
                NetworkNames.isFull(name) ||
                    (name.endsWith("*") && pattern.indexOf(':') > 0 && NetworkNames.isNamespace(pattern.substringBefore(':')))
            ) {
                "\"$name\" isn't a channel or map's full name (namespace:name, or namespace:* for all of a namespace's)"
            }
            return Share(name, access)
        }
    }
}

/**
 * What a server may do in the network, from its credential: the hub holds every frame to it, and tells the server
 * (in the [HubFrame.Welcome]) so its scripts get the refusal at their line.
 *
 * A [trusted] server may do everything, as every server could before credentials. Any other has its [owns]
 * namespaces' channels and maps whole (every [Access]), [shares] of others', other servers' players only with
 * [presence], and sends players only where [sendTo] says.
 */
@Serializable
data class Grants(
    /** The preset it came from (`trusted`, `standard`, `isolated`), for scripts and the log. */
    val role: String,
    val trusted: Boolean = false,
    val owns: List<String> = emptyList(),
    val shares: List<Share> = emptyList(),
    /** Whether it sees players on other servers. Without, it sees only its own. */
    val presence: Boolean = true,
    /**
     * Where it may send players: server names (and groups'), [BACK] for where the player came from, or null for
     * anywhere. Only a [trusted] server sends players who aren't on it.
     */
    val sendTo: List<String>? = null
) {
    /** Whether it may do [access] on [full] (a channel's or map's `namespace:name`). */
    fun allows(full: String, access: Access): Boolean {
        if (trusted || NetworkNames.namespaceOf(full) in owns) return true
        return shares.any { share ->
            share.matches(full) && (access in share.access || (access == Access.READ && Access.WRITE in share.access))
        }
    }

    /** Whether it may send a player to [server] (in [group]), who came to where they are from [cameFrom]. */
    fun maySendTo(server: String, group: String?, cameFrom: String?): Boolean {
        val to = sendTo ?: return true
        if (trusted) return true
        return to.any { target ->
            when {
                target == BACK -> cameFrom != null && cameFrom.equals(server, ignoreCase = true)
                target == "*" -> true
                else -> target.equals(server, ignoreCase = true) || (group != null && target.equals(group, ignoreCase = true))
            }
        }
    }

    /** One line for the log and `/nfhub credentials list`. */
    fun describe(): String = buildString {
        append(role)
        if (trusted) return@buildString
        if (owns.isNotEmpty()) append(", owns ").append(owns.joinToString())
        if (shares.isNotEmpty()) append(", shared ").append(shares.joinToString())
        if (!presence) append(", no presence")
        sendTo?.let { append(", sends to ").append(if (it.isEmpty()) "nowhere" else it.joinToString()) }
    }

    companion object {
        /** In [sendTo]: back where the player came from. */
        const val BACK = "back"

        val TRUSTED = Grants(TRUSTED_ROLE, trusted = true)

        const val TRUSTED_ROLE = "trusted"
        const val STANDARD_ROLE = "standard"
        const val ISOLATED_ROLE = "isolated"
        val ROLES = listOf(TRUSTED_ROLE, STANDARD_ROLE, ISOLATED_ROLE)

        /**
         * A preset's grants: `trusted` everything; `standard` its [owns], [shared] with standard servers, presence,
         * sending anywhere; `isolated` only what's [shared] with isolated servers, no presence, sending players only
         * back where they came from. [sendTo] and [presence] replace the preset's when given.
         */
        fun preset(
            role: String,
            owns: List<String> = emptyList(),
            shared: List<Share> = emptyList(),
            presence: Boolean? = null,
            sendTo: List<String>? = null
        ): Grants = when (role) {
            TRUSTED_ROLE -> TRUSTED
            STANDARD_ROLE -> Grants(role, owns = owns, shares = shared, presence = presence ?: true, sendTo = sendTo)
            ISOLATED_ROLE -> Grants(role, owns = owns, shares = shared, presence = presence ?: false, sendTo = sendTo ?: listOf(BACK))
            else -> throw IllegalArgumentException("\"$role\" isn't a role: trusted, standard or isolated")
        }
    }
}

/** A server's own way into the network: an [id] it says in its hello, the [secret] it proves, and its [grants]. */
data class Credential(val id: String, val secret: String, val grants: Grants) {
    override fun toString() = "Credential($id, ${grants.describe()})"
}
