package dev.netherforge.network

/** What the network's owner sets for one group (`group.<name>.*` in `hub.properties`). */
data class GroupSettings(
    /** How the hub picks an instance to send players to, unless the sender says. */
    val balance: Balance = Balance.LEAST
)
