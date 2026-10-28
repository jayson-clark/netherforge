package dev.netherforge.plugin.scoreboard
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.session.RuntimeService
import java.util.UUID

/**
 * Each online player's sidebar as scripts set it: its title, lines and
 * whether it shows. Forgotten when they leave (the server's per-player
 * scoreboard goes with them).
 */
internal class Sidebars(private val platform: Platform) : RuntimeService {
    override val name get() = "sidebars"

    class State {
        var title: String? = null
        var lines: List<String> = emptyList()
        var visible = false
    }

    private val states = HashMap<UUID, State>()

    fun state(player: UUID): State? = states[player]

    /** Changes [player]'s sidebar and shows it, or hides it as [change] leaves it. */
    fun change(player: UUID, change: State.() -> Unit) {
        val state = states.getOrPut(player) { State() }
        val was = state.visible
        state.change()
        if (state.visible) {
            platform.sidebars.show(player, state.title.orEmpty(), state.lines)
        } else if (was) {
            platform.sidebars.hide(player)
        }
    }

    override fun playerQuit(player: PlayerRef) {
        states.remove(player.uuid)
    }

    /** The session is ending: everyone gets the main scoreboard back. */
    override fun stop() {
        for ((player, state) in states) if (state.visible) platform.sidebars.hide(player)
        states.clear()
    }
}
