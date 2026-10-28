package dev.netherforge.plugin.sky

import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.format.sky.SkyFile
import dev.netherforge.format.sky.SkyKind
import dev.netherforge.format.sky.SkyNight
import dev.netherforge.format.sky.SkyTwins
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.SkyView
import dev.netherforge.plugin.session.ReloadBatch
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.SessionProject
import dev.netherforge.plugin.session.TickPhase
import java.util.UUID

/**
 * The project's skies (`skies/<id>.json`) over worlds and players: which one each world and each player has
 * (`world:set_sky`, `player:set_sky`), and what each online player is shown of it, a step of its fade at a time.
 *
 * A sky is shown as its twin biomes (format's `SkyTwins`), which the server learns only as it starts, from the
 * start-up datapack: a sky the server started with has twins for the steps its file had then, and one added since
 * has none (setting it is an error until a restart). Nothing here is kept: a restart (or the session stopping) puts
 * everyone back under the world's own biomes.
 */
class Skies(
    private val platform: Platform,
    /** The project's namespace, where every sky's twins are. */
    private val namespace: () -> String
) : RuntimeService {
    override val name get() = "skies"

    /** Every sky the project's files have, as the project names it (`blood_moon`, `acme:eclipse`). */
    private val files = HashMap<String, SkyFile>()

    /** What a world or a player was last given: a sky, or none, and over how long it fades; [order] says which came last. */
    private class Target(val sky: String?, val fade: Int, val order: Long)

    private val worlds = HashMap<String, Target>()
    private val players = HashMap<UUID, Target>()
    private var changes = 0L

    /** What a player is shown now, and how it's getting where it's going. */
    private class Viewer(var world: String?) {
        var sky: String? = null
        var step = 0
        var wantSky: String? = null
        var wantFade = 0
        var interval = 0
        var nextAt = 0L
        var shown: SkyView? = null
    }

    private val viewers = HashMap<UUID, Viewer>()
    private var now = 0L

    override fun define(project: SessionProject) {
        files.clear()
        files += project.running(SkyKind)
    }

    override val reloads: Set<KindSpec<*, *>> get() = setOf(SkyKind)

    override fun reload(kind: KindSpec<*, *>, ids: Set<String>, batch: ReloadBatch) {
        for (id in ids) {
            val file = batch.snapshot.running(SkyKind)[id]
            if (file != null) files[id] = file else files -= id
        }
    }

    /** Checks [name] (as the project names it) can be set: an error for one the project has no file for, or none the server started with. */
    fun check(name: String) {
        if (name !in files) throw LuaApiException("there's no sky \"$name\" (skies/${name.substringAfter(':')}.json)")
        if (steps(name) == 0) {
            if (platform.datapacks.refused?.leavesOut(namespace())?.sky?.invoke(keyOf(name)) == true) {
                throw LuaApiException(
                    "sky \"$name\" isn't on the server: it refused the sky's twin biomes as it started (its problems say why), " +
                        "so it runs without it until the file changes and the server restarts"
                )
            }
            throw LuaApiException(
                "sky \"$name\" is new since the server started: a sky's colours are biomes, which the game learns as it starts, so restart it"
            )
        }
    }

    /** The steps of [name]'s fade the server has twins for: what its file said when the server started. */
    private fun steps(name: String): Int = started.getOrPut(name) {
        val key = keyOf(name)
        val prefix = "data/${namespace()}/worldgen/biome/${SkyTwins.FOLDER}/${key.namespace}/${key.path}/"
        platform.datapacks.started.keys.filter { it.startsWith(prefix) }
            .mapNotNull { it.removePrefix(prefix).substringBefore('/').toIntOrNull() }.maxOrNull() ?: 0
    }

    private val started = HashMap<String, Int>()

    /** [name]'s clock, when the server started with one (it holds its colours at night, on 26.1 or later); else null. */
    private fun clockOf(name: String): String? = clocks.getOrPut(name) {
        val key = SkyNight.keyOf(namespace(), keyOf(name))
        key.toString().takeIf { "data/${key.namespace}/${SkyNight.CLOCK_FOLDER}/${key.path}.json" in platform.datapacks.started }
    }

    private val clocks = HashMap<String, String?>()

    private fun keyOf(name: String) = requireNotNull(ResourceRef(name).resolve(namespace())) { "\"$name\" isn't a sky's name" }

    fun setWorld(world: String, sky: String, fade: Int) {
        check(sky)
        worlds[world] = Target(sky, fade, ++changes)
    }

    fun clearWorld(world: String, fade: Int) {
        if (worlds[world]?.sky == null) return
        worlds[world] = Target(null, fade, ++changes)
    }

    fun ofWorld(world: String): String? = worlds[world]?.sky

    fun setPlayer(player: UUID, sky: String, fade: Int) {
        check(sky)
        players[player] = Target(sky, fade, ++changes)
    }

    fun clearPlayer(player: UUID, fade: Int) {
        if (players[player]?.sky == null) return
        players[player] = Target(null, fade, ++changes)
    }

    fun ofPlayer(player: UUID): String? = players[player]?.sky

    override fun tick(phase: TickPhase) {
        if (phase != TickPhase.UPKEEP) return
        now++
        if (worlds.isEmpty() && players.isEmpty() && viewers.isEmpty()) return
        for (player in platform.players.online()) view(player.uuid)
    }

    /** Moves what [player] is shown a step towards what they should see, when it's due. */
    private fun view(player: UUID) {
        val world = platform.players.location(player)?.world
        val own = players[player]
        val there = world?.let { worlds[it] }
        // Their own sky over their world's; of the two, the fade of the one that changed last.
        val target = own?.takeIf { it.sky != null } ?: there
        val latest = listOfNotNull(own, there).maxByOrNull { it.order }
        val want = target?.sky
        val fade = latest?.fade ?: 0
        val viewer = viewers[player] ?: Viewer(world).also {
            viewers[player] = it
            jump(it, want)
        }
        if (viewer.world != world) {
            // Another world: its sky at once, as the client loads its chunks anew anyway.
            viewer.world = world
            jump(viewer, want)
        } else if (viewer.wantSky != want || viewer.wantFade != fade) {
            viewer.wantSky = want
            viewer.wantFade = fade
            if (fade <= 0) {
                jump(viewer, want)
            } else {
                val moves = (if (viewer.sky != null && viewer.sky != want) viewer.step else 0) +
                    (if (want != null) steps(want) - (if (viewer.sky == want) viewer.step else 0) else 0)
                viewer.interval = if (moves > 0) maxOf(1, fade / moves) else 0
                viewer.nextAt = now
            }
        }
        if (now >= viewer.nextAt) step(viewer, want)
        show(player, viewer)
    }

    private fun jump(viewer: Viewer, want: String?) {
        viewer.wantSky = want
        viewer.sky = want
        viewer.step = want?.let(::steps) ?: 0
    }

    /** One step: the sky shown out first when it isn't the one wanted, then the one wanted in. */
    private fun step(viewer: Viewer, want: String?) {
        val shown = viewer.sky
        when {
            shown != null && shown != want -> {
                viewer.step--
                if (viewer.step <= 0) {
                    viewer.sky = null
                    viewer.step = 0
                }
            }
            want != null && (shown == null || viewer.step < steps(want)) -> {
                viewer.sky = want
                viewer.step++
            }
            else -> return
        }
        viewer.nextAt = now + viewer.interval
    }

    private fun show(player: UUID, viewer: Viewer) {
        val sky = viewer.sky
        val view = if (sky == null || viewer.step <= 0) null else SkyView(namespace(), keyOf(sky).toString(), viewer.step, clockOf(sky))
        if (view == viewer.shown) return
        viewer.shown = view
        platform.skies.show(player, view)
    }

    override fun playerQuit(player: PlayerRef) {
        viewers.remove(player.uuid)
        players.remove(player.uuid)
    }

    /** Everyone shown the world's own biomes again: what a stopped session's skies leave. */
    override fun stop() {
        for ((player, viewer) in viewers) if (viewer.shown != null) platform.skies.show(player, null)
        viewers.clear()
        worlds.clear()
        players.clear()
        started.clear()
    }
}
