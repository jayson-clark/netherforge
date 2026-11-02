package dev.netherforge.network

import java.util.concurrent.CompletionStage

/**
 * What the hub needs of the proxy it runs on: which servers it has, who's online where, and moving a player. The
 * proxy plugin implements it over its proxy's API and tells the hub of players joining, moving and leaving
 * ([Hub.playerMoved], [Hub.playerLeft]); tests have their own.
 */
interface HubProxy {
    /** Every server the proxy has, by name. A server must be one of them to connect to the hub. */
    fun servers(): List<String>

    /** Everyone online now, when the hub starts. */
    fun players(): List<PlayerState>

    /**
     * Moves the online player [id] (a UUID) to [server]. Completes with null once they're there, or why they
     * aren't (already there, the server is down, another plugin cancelled it). From any thread.
     */
    fun send(id: String, server: String): CompletionStage<String?>

    /** Adds [name] at [host]:[port] to the proxy's servers, as a server registers itself. Null once it has, or why not. */
    fun register(name: String, host: String, port: Int): String? = "this proxy can't add servers while it runs"

    /** Takes [name] (one [register] added) off the proxy's servers. */
    fun unregister(name: String) {}

    /**
     * Shows player [id] [text] (MiniMessage) as [kind]: `chat`, `title` (with [subtitle]) or `actionbar`. Null once
     * shown, or why not.
     */
    fun message(id: String, kind: String, text: String, subtitle: String?): String? = "this proxy can't show players messages"

    /** Disconnects player [id] from the network with [message]. Null once done, or why not. */
    fun kick(id: String, message: String): String? = "this proxy can't kick players"

    /** Whether [name] is one of the proxy's own servers (its config's), which no server may register over. */
    fun isFixed(name: String): Boolean = true
}

/** Where the hub writes what happened: the proxy's log. */
interface HubLog {
    fun info(message: String)

    fun warn(message: String)
}

/** What the hub holds servers and packages to; the proxy plugin's owner sets them. */
data class NetworkLimits(
    /** The most one value may be as JSON: a message, a request or its reply, a map's value, a sent player's data. */
    val maxValueBytes: Int = DEFAULT_MAX_VALUE_BYTES,
    /** How many keys one package may keep across its maps. */
    val maxKeysPerPackage: Int = 10_000,
    /** How many bytes of values one package may keep across its maps. */
    val maxBytesPerPackage: Long = 16L * 1024 * 1024,
    /** Frames waiting to be written to one server; past it the server is too slow and is dropped. */
    val maxQueuedFrames: Int = 50_000,
    /** How long a request waits for the server asked before the hub answers it failed. */
    val requestTimeoutMillis: Long = 30_000,
    /** How long a server that registered itself may be gone (not a clean shutdown) before it comes off the proxy. */
    val unregisterAfterMillis: Long = 60_000,
    /** How long the hub waits for an admin client to place players before the group's own balancing does. */
    val placementTimeoutMillis: Long = 2_000
) {
    companion object {
        const val DEFAULT_MAX_VALUE_BYTES = 64 * 1024
    }
}
