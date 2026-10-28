package dev.netherforge.plugin.integration

import dev.netherforge.format.bridge.BotAction
import dev.netherforge.format.bridge.BotJoinParams
import dev.netherforge.format.bridge.BotParams
import dev.netherforge.format.bridge.BotState
import dev.netherforge.format.bridge.BotsExtension
import dev.netherforge.plugin.integration.support.Bots
import dev.netherforge.plugin.integration.support.PaperServer
import dev.netherforge.plugin.integration.support.Scenario
import dev.netherforge.plugin.integration.support.VersionIndependent
import dev.netherforge.plugin.integration.support.eventually
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * Cutscenes on a real server: a bot spectates the camera display as it moves along the path
 * (the server moves a spectating player with what they look through), can't leave it, and is
 * put back (game mode, where they stood) however it ends: it finishes, a script stops it, the
 * player quits mid-way (their saved data is what's put back) or the server stops.
 */
@VersionIndependent
class CutsceneScenario : Scenario("cutscenes") {
    override val server = PaperServer(onlineMode = true, maxPlayers = 2)

    /** Over the server's current bridge: a restart makes a new one. */
    private val bots get() = Bots(editor)

    /** Where the runner stands, adventure mode, before each cutscene. */
    private lateinit var home: BotState

    private fun join() {
        val (joined, _) = editor.request(BotsExtension.join, BotJoinParams(RUNNER))
        assertTrue(joined.ok, joined.error)
    }

    /** At [at], to a block. */
    private fun near(state: BotState, at: BotState) = abs(state.x - at.x) <= 1.0 && abs(state.y - at.y) <= 1.0 && abs(state.z - at.z) <= 1.0

    /** Runs `/probe` and waits for the bot to be looking through the camera, high above where it stood. */
    private fun probe() {
        bots.act(RUNNER, BotAction.Command("/probe"))
        editor.logged("probe started", "6.0")
        bots.eventually(RUNNER) { it.gameMode == "spectator" && it.y > home.y + 30 }
    }

    /** Where the server has the runner now (`/probewhere`): the camera's place while a cutscene plays. */
    private fun where(): Triple<Double, Double, Double> {
        bots.act(RUNNER, BotAction.Command("/probewhere"))
        val (x, y, z) = editor.line("probe at").map { it.toDouble() }
        return Triple(x, y, z)
    }

    /** The bot is where it was, in the game mode it was in. */
    private fun backHome() {
        bots.eventually(RUNNER) { it.gameMode == "adventure" && near(it, home) }
    }

    @Test
    @Order(1)
    fun `a cutscene moves the player along the path, can't be left, and puts them back when it finishes`() {
        join()
        // Away from the spawn, so a rejoin at the spawn would show.
        val spawn = bots.state(RUNNER)
        editor.run("gamemode adventure $RUNNER")
        editor.run("tp $RUNNER ${spawn.x + 25} ${spawn.y} ${spawn.z + 25}")
        home = bots.eventually(RUNNER) { it.gameMode == "adventure" && abs(it.x - (spawn.x + 25)) < 1 }

        probe()
        // The server moves a spectating player to the camera each tick: 48 blocks east over six seconds, 40 up.
        val first = where()
        assertTrue(abs(first.second - (home.y + 40)) < 1.5, "high above where they stood: $first")
        // Polled rather than after a sleep: a loaded server's ticks come late, and the camera with them.
        val second = eventually("the camera moving east from $first", poll = ::where) { it.first > first.first + 1.5 }
        assertTrue(abs(second.second - first.second) < 0.5, "level: $first then $second")
        editor.logged("probe cue", "middle")
        bots.eventually(RUNNER) { it.subtitle?.contains("Halfway there") == true }
        // Sneaking lets go of a camera in the game; this one isn't skippable, so the player is put back in it.
        bots.act(RUNNER, BotAction.Sneak(true))
        bots.eventually(RUNNER) { it.gameMode == "spectator" }
        bots.act(RUNNER, BotAction.Sneak(false))

        editor.logged("probe ended", "finished", "adventure")
        backHome()
    }

    @Test
    @Order(2)
    fun `a script stopping it puts the player back at once`() {
        probe()
        bots.act(RUNNER, BotAction.Command("/probestop"))
        editor.logged("probe ended", "stopped", "adventure")
        editor.logged("probe stopped", "true")
        backHome()
    }

    @Test
    @Order(3)
    fun `a player who quits mid-cutscene is saved where they were, and in the game mode they had`() {
        probe()
        assertTrue(editor.request(BotsExtension.leave, BotParams(RUNNER)).first.ok)
        editor.logged("probe ended", "player_left", "adventure")
        // What the server saved at the quit is what they come back to.
        join()
        backHome()
    }

    @Test
    @Order(4)
    fun `the server stopping mid-cutscene saves players where they were too`() {
        probe()
        restart()
        join()
        backHome()
    }

    private companion object {
        const val RUNNER = "Runner"
    }
}
