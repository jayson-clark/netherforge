package dev.netherforge.network

/** What the network's owner sets for one group (`group.<name>.*` in `hub.properties`). */
data class GroupSettings(
    /** How the hub picks an instance to send players to, unless the sender says. */
    val balance: Balance = Balance.LEAST,
    /** Where its instances' players go when one stops: a group (or server) name. */
    val fallback: String = DEFAULT_FALLBACK,
    /** How many sends may wait for one of its instances at once. */
    val maxWaiting: Int = DEFAULT_MAX_WAITING
) {
    companion object {
        const val DEFAULT_FALLBACK = "lobby"
        const val DEFAULT_MAX_WAITING = 500
    }
}

/** Something whatever runs the network's servers may want to hear of: the hub's webhook sends it ([HubEvents]). */
sealed interface HubEvent {
    val name: String

    /** A server became ready, [startupMillis] after its process started. */
    data class ServerReady(val server: String, val group: String?, val startupMillis: Long?) : HubEvent {
        override val name get() = "server_ready"
    }

    /** A server has been empty longer than its idle time: it may be stopped. */
    data class ServerIdle(val server: String, val group: String?) : HubEvent {
        override val name get() = "server_idle"
    }

    /** A server's NetherForge disconnected. */
    data class ServerGone(val server: String, val group: String?) : HubEvent {
        override val name get() = "server_gone"
    }

    /** A player reported another: for whoever moderates the network. */
    data class Reported(val report: Report) : HubEvent {
        override val name get() = "report"
    }

    /** Players wait to be sent to [group] (or a server of that name), which has no instance with room: start one. */
    data class GroupWanted(val group: String, val waiting: Int) : HubEvent {
        override val name get() = "group_wanted"
    }
}
