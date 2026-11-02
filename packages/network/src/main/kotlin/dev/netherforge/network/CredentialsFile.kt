package dev.netherforge.network

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * One credential as `credentials.properties` keeps it: what the owner (or `/nfhub credentials`) wrote, before the
 * role's shares are added ([credential]).
 */
data class CredentialEntry(
    val id: String,
    val secret: String,
    val role: String,
    val owns: List<String> = emptyList(),
    val shares: List<Share> = emptyList(),
    val presence: Boolean? = null,
    val sendTo: List<String>? = null,
    /** The group its servers are instances of, whatever they say. */
    val group: String? = null,
    /** Whether its servers may add themselves to the proxy, as which names and from which addresses. */
    val register: Boolean = false,
    val names: List<String> = emptyList(),
    val addresses: List<String> = emptyList(),
    /** Whether its servers may ban and mute players network-wide. */
    val moderation: Boolean = false
) {
    /** What it may do: its role's preset, with what the network shares with that role ([roleShares]) and its own. */
    fun credential(roleShares: Map<String, List<Share>>): Credential = Credential(
        id,
        secret,
        Grants.preset(role, owns, roleShares[role].orEmpty() + shares, presence, sendTo, moderation),
        group,
        if (register) RegisterRule(names, addresses) else null
    )
}

/**
 * The hub's credentials, `credentials.properties` beside `hub.properties`: one server (or group of servers) each,
 * keyed `<id>.<field>`. The owner may edit it by hand and `/nfhub credentials reload`; `/nfhub credentials add` and
 * `revoke` rewrite it whole (sorted, with the header), so hand-written comments don't survive them.
 */
class CredentialsFile(val file: Path) {
    /** Every credential in the file; none when there's no file. Throws saying which line is wrong. */
    fun read(): List<CredentialEntry> {
        if (!Files.exists(file)) return emptyList()
        val properties = Properties()
        Files.newBufferedReader(file).use(properties::load)
        val ids = properties.stringPropertyNames().map { it.substringBefore('.') }.toSortedSet()
        return ids.map { id ->
            require(NetworkNames.isCredential(id)) { "$file: \"$id\" isn't a credential id (letters, digits, _ . -)" }
            fun text(field: String) = properties.getProperty("$id.$field")?.trim()?.takeIf { it.isNotEmpty() }
            fun list(field: String) = text(field)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            val secret = text("secret") ?: throw IllegalArgumentException("$file: $id has no secret")
            require(secret.length >= MIN_SECRET) { "$file: $id's secret is shorter than $MIN_SECRET characters" }
            val role = text("role") ?: Grants.STANDARD_ROLE
            require(role in Grants.ROLES) { "$file: $id's role \"$role\" isn't trusted, standard or isolated" }
            val owns = list("owns").orEmpty()
            for (namespace in owns) {
                require(NetworkNames.isNamespace(namespace)) {
                    "$file: $id owns \"$namespace\", which isn't a namespace"
                }
            }
            val shares = try {
                Share.parseList(text("shares"))
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("$file: $id's shares: ${e.message}")
            }
            CredentialEntry(
                id = id,
                secret = secret,
                role = role,
                owns = owns,
                shares = shares,
                presence = text("presence")?.lowercase()?.let { it == "true" },
                sendTo = list("send-to"),
                group = text("group")?.also { require(NetworkNames.isServer(it)) { "$file: $id's group \"$it\" isn't a group's name" } },
                register = text("register")?.lowercase() == "true",
                moderation = text("moderation")?.lowercase() == "true",
                names = list("names").orEmpty(),
                addresses = list("addresses").orEmpty().onEach {
                    require(Cidr.parse(it) != null) { "$file: $id's address \"$it\" isn't an IP address or range (10.0.0.0/8)" }
                }
            )
        }
    }

    /** Writes [entries] as the whole file, atomically. */
    fun write(entries: List<CredentialEntry>) {
        Files.createDirectories(file.parent)
        val text = buildString {
            append(HEADER)
            for (entry in entries.sortedBy { it.id }) {
                append('\n')
                append("${entry.id}.secret=${entry.secret}\n")
                append("${entry.id}.role=${entry.role}\n")
                if (entry.owns.isNotEmpty()) append("${entry.id}.owns=${entry.owns.joinToString(", ")}\n")
                if (entry.shares.isNotEmpty()) append("${entry.id}.shares=${entry.shares.joinToString(", ")}\n")
                entry.presence?.let { append("${entry.id}.presence=$it\n") }
                entry.sendTo?.let { append("${entry.id}.send-to=${it.joinToString(", ")}\n") }
                entry.group?.let { append("${entry.id}.group=$it\n") }
                if (entry.register) append("${entry.id}.register=true\n")
                if (entry.moderation) append("${entry.id}.moderation=true\n")
                if (entry.names.isNotEmpty()) append("${entry.id}.names=${entry.names.joinToString(", ")}\n")
                if (entry.addresses.isNotEmpty()) append("${entry.id}.addresses=${entry.addresses.joinToString(", ")}\n")
            }
        }
        val temporary = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temporary, text)
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val FILE = "credentials.properties"
        const val MIN_SECRET = 16

        private val HEADER = """
            |# NetherForge hub credentials: each server (or group of servers sharing one) joins with its own id and secret,
            |# in its plugins/NetherForge/config.yml as network.credential.id and network.credential.secret.
            |# /nfhub credentials add|revoke rewrite this file; after editing it by hand, /nfhub credentials reload.
            |#
            |#   <id>.secret    what the server proves it knows (never sent as it is)
            |#   <id>.role      trusted (everything), standard (its own namespaces and what's shared with standard
            |#                  servers in hub.properties) or isolated (only what's shared with it; sees only its players)
            |#   <id>.owns      namespaces whose channels and maps it has whole: arena, arena_stats
            |#   <id>.shares    more of others' for it alone: lobby:return(request), events:*(read)
            |#                  (read, write, request, answer; joined with +)
            |#   <id>.presence  true or false: whether it sees players on other servers
            |#   <id>.send-to   where it may send its players: servers, groups, back (where they came from), * (anywhere)
            |#   <id>.group     the group its servers are instances of (arena), whatever their config says
            |#   <id>.register  true: its servers may add themselves to the proxy as they start (not in velocity.toml)
            |#   <id>.moderation true: its servers' scripts may ban and mute players network-wide
            |#   <id>.names     the names they may take then: arena-* (any when empty)
            |#   <id>.addresses where the proxy may reach them: 10.0.0.0/8, 192.168.1.20 (any when empty)
            |
        """.trimMargin()
    }
}
