package dev.netherforge.format.project

import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.json.CanonicalJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A server owner's (or a network's) rules over what projects may ask for, whatever they ask for: `policy.json` in the
 * plugin's folder, sent by the network's hub for the server's role, or given to `netherforge check --policy`. A
 * project whose `requires` it refuses doesn't load, or with [PolicyRequires.refused] `degrade` loads with the refused
 * calls failing as an undeclared requirement's do. Its [limits] replace the owner's own where they're stricter.
 */
@Serializable
data class Policy(val requires: PolicyRequires = PolicyRequires(), val limits: PolicyLimits = PolicyLimits()) {
    /** Why [requirement] (a [Requirement.id]: `moderation`, `http:api.example.com`, `plugin:vault`) is refused, or null. */
    fun refuses(requirement: String): String? {
        val kind = kindOf(requirement)
        requires.deny?.let { deny -> if (kind in deny) return "requires.deny lists \"$kind\"" }
        requires.allow?.let { allow -> if (kind !in allow) return "requires.allow doesn't list \"$kind\"" }
        val hosts = requires.http
        if (hosts != null && requirement.startsWith("http:")) {
            val host = requirement.removePrefix("http:")
            if (hosts.none { pattern -> Requirement.allows(pattern, host) || pattern == host }) {
                return "requires.http doesn't list \"$host\""
            }
        }
        return null
    }

    /** Whether a refusal stops the project loading (true) or leaves the refused calls failing. */
    val stops get() = requires.refused != PolicyRequires.DEGRADE

    /**
     * What [snapshot]'s tree declares that this refuses: a [ProblemCodes.RUNTIME_POLICY] on each declaring package's
     * manifest, an error when it stops the project loading, a warning when it degrades.
     */
    fun check(snapshot: ProjectSnapshot): List<Problem> = Requirement.combined(snapshot).flatMap { use ->
        val why = refuses(use.requirement) ?: return@flatMap emptyList()
        use.packages.map { namespace ->
            val file = if (namespace ==
                snapshot.namespace
            ) {
                ProjectManifest.FILE_NAME
            } else {
                PackagePaths.of(namespace, ProjectManifest.FILE_NAME)
            }
            val message = "Package \"$namespace\" requires ${use.requirement}, which the server's policy refuses ($why)" +
                if (stops) ", so the project doesn't load" else ": calls that need it fail"
            val problem = ProblemCodes.RUNTIME_POLICY.at(file, message, "$.requires")
            if (stops) problem else problem.copy(severity = dev.netherforge.format.Severity.WARNING)
        }
    }

    /** This and [other] together, the stricter of each: what both allow, what either denies, the lower limit. */
    infix fun and(other: Policy): Policy = Policy(
        PolicyRequires(
            allow = listOfNotNull(requires.allow, other.requires.allow).reduceOrNull { a, b -> a.intersect(b.toSet()).toList() },
            deny = listOfNotNull(requires.deny, other.requires.deny).reduceOrNull { a, b -> (a + b).distinct() },
            http = listOfNotNull(requires.http, other.requires.http).reduceOrNull { a, b -> a.filter { it in b } },
            refused = if (stops || other.stops) PolicyRequires.REFUSE else PolicyRequires.DEGRADE
        ),
        PolicyLimits(
            scriptMemoryMb = lower(limits.scriptMemoryMb, other.limits.scriptMemoryMb),
            instructionBudget = lower(limits.instructionBudget, other.limits.instructionBudget),
            callMillis = lower(limits.callMillis, other.limits.callMillis),
            networkMessagesPerMinute = lower(limits.networkMessagesPerMinute, other.limits.networkMessagesPerMinute),
            httpRequestsPerMinute = lower(limits.httpRequestsPerMinute, other.limits.httpRequestsPerMinute)
        )
    )

    companion object {
        const val FILE_NAME = "policy.json"

        /** The kinds `allow` and `deny` name: a requirement's id before its `:`, `plugins` for every plugin. */
        val KINDS = listOf("console", "moderation", "db", "web", "bots", "wasm", "network", "http", "plugins")

        fun kindOf(requirement: String): String = when {
            requirement.startsWith("plugin:") -> "plugins"
            else -> requirement.substringBefore(':')
        }

        /** [text] as a policy, or what's wrong with it. */
        fun parse(text: String): Result<Policy> = runCatching {
            val policy = CanonicalJson.json.decodeFromString(serializer(), text)
            for (kind in policy.requires.allow.orEmpty() + policy.requires.deny.orEmpty()) {
                require(kind in KINDS) { "\"$kind\" isn't a requirement's kind (${KINDS.joinToString()})" }
            }
            require(policy.requires.refused in setOf(PolicyRequires.REFUSE, PolicyRequires.DEGRADE)) {
                "requires.refused is \"${policy.requires.refused}\", not refuse or degrade"
            }
            policy
        }

        private fun lower(a: Int?, b: Int?): Int? = listOfNotNull(a, b).minOrNull()
    }
}

/** What a policy allows projects to require. */
@Serializable
data class PolicyRequires(
    /** The only requirement kinds a project may have; any when left out. */
    val allow: List<String>? = null,
    /** Kinds no project may have. */
    val deny: List<String>? = null,
    /** The only hosts (`*.example.com` for any under it) a project may declare for `http`; any when left out. */
    val http: List<String>? = null,
    /** `refuse`: a project it refuses doesn't load; `degrade`: it loads, and the refused calls fail. */
    val refused: String = REFUSE
) {
    companion object {
        const val REFUSE = "refuse"
        const val DEGRADE = "degrade"
    }
}

/** Limits a policy sets, replacing the server's own where lower; none when left out. */
@Serializable
data class PolicyLimits(
    /** How much memory every script together may use. */
    @SerialName("script-memory-mb") val scriptMemoryMb: Int? = null,
    /** The most instructions a script may have a call (its file's `budget` can't be more). */
    @SerialName("instruction-budget") val instructionBudget: Int? = null,
    /** How long one call into a script may run. */
    @SerialName("call-milliseconds") val callMillis: Int? = null,
    /** What each package may send the network a minute. */
    @SerialName("network-messages-per-minute") val networkMessagesPerMinute: Int? = null,
    /** What each package may make HTTP requests a minute. */
    @SerialName("http-requests-per-minute") val httpRequestsPerMinute: Int? = null
)
