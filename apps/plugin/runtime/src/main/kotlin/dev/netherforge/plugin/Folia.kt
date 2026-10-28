package dev.netherforge.plugin

/**
 * Folia (Paper with regionised threading) isn't supported:
 * every script, handler and task runs on the server's main thread in one Lua
 * state, and Folia has no main thread to run them on. The adapter checks
 * [detected] before anything else and refuses to enable with [REFUSAL].
 */
object Folia {
    /** The class Folia's documentation says a plugin should look for to tell it's on Folia. */
    const val REGIONIZED_SERVER = "io.papermc.paper.threadedregions.RegionizedServer"

    const val REFUSAL = "NetherForge doesn't run on Folia: its scripts all run on the server's main thread, in one Lua " +
        "state, and Folia has no main thread. Run the project on Paper instead. NetherForge is disabling itself."

    /** Whether this server is Folia, asking [hasClass] whether a class exists. */
    fun detected(hasClass: (String) -> Boolean = ::classExists): Boolean = hasClass(REGIONIZED_SERVER)

    private fun classExists(name: String): Boolean = try {
        Class.forName(name, false, Folia::class.java.classLoader)
        true
    } catch (e: ClassNotFoundException) {
        false
    }
}
