package dev.netherforge.plugin.pack

import dev.netherforge.format.Problem
import dev.netherforge.format.Severity
import dev.netherforge.format.block.BlockKind
import dev.netherforge.format.bridge.PackBuild
import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.project.VanillaKind
import dev.netherforge.format.resourcepack.ResourcePackKind
import dev.netherforge.format.screen.VideoKind
import dev.netherforge.plugin.RuntimeLog
import dev.netherforge.plugin.binding.Events
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.PlayerResourcePackStatusEvent
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.project.Resource
import dev.netherforge.plugin.script.Scripts
import dev.netherforge.plugin.session.ReloadBatch
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.SessionProject

/**
 * A session's part in the resource pack ([Packs], which outlives it):
 * building it from the session's project, sending it to everyone online and
 * to whoever joins, and rebuilding it when a pack is saved.
 */
internal class PackService(
    private val packs: Packs,
    private val log: RuntimeLog,
    private val scripts: Scripts,
    /** An `onDemand` pack's name as the server knows it, as the script reading it writes it. */
    private val spell: (String) -> String
) : RuntimeService {
    override val name get() = "resource pack"

    /** What the last build found wrong that the format didn't (the server's pack format unknown). */
    private var problems: List<Problem> = emptyList()

    /** First: menus' skins and scripts' glyphs read the build. A refused project builds nothing; the last build stays. */
    override fun define(project: SessionProject) {
        problems = if (project.refused) emptyList() else packs.rebuild(project.snapshot)
    }

    override fun start() {
        packs.onStatus = { player, pack, status ->
            val handle = LuaHandle.Player(player.toString())
            scripts.emit(
                listOf(Events.PLAYER_RESOURCE_PACK_STATUS to handle, Events.NF_PLAYER_RESOURCE_PACK_STATUS to null),
                PlayerResourcePackStatusEvent(handle, pack?.let(spell), status)
            )
        }
        packs.sendAll()
    }

    override fun stop() {
        packs.onStatus = null
    }

    override fun playerJoined(player: PlayerRef) {
        packs.send(player.uuid)
    }

    override fun playerQuit(player: PlayerRef) = packs.forget(player.uuid)

    override fun problems(): List<Problem> = problems

    override val reloads: Set<KindSpec<*, *>> get() = setOf(ResourcePackKind)

    /**
     * The blocks the pack draws (their states, their models) change with the project's blocks, the game's own
     * looks of their carriers with `client/vanilla.json`, and the sounds it carries with videos converted again:
     * it's built again after any of them.
     */
    override val follows: Set<String> get() = setOf(BlockKind.id, VanillaKind.id, VideoKind.id)

    override fun followed(kind: String, ids: Set<String>, batch: ReloadBatch) {
        problems = packs.rebuild(batch.snapshot)
        // Only what changed goes: the project's pack to whoever lacks this build, an `onDemand` one to whoever has an older one.
        packs.sendAll()
    }

    /**
     * Rebuilds the one resource pack all packs go into, and sends it to
     * everyone online whose client doesn't have this hash. Reported once per
     * pack that was saved; `reattached` is how many players it was sent to.
     */
    override fun reload(kind: KindSpec<*, *>, ids: Set<String>, batch: ReloadBatch) {
        val snapshot = batch.snapshot
        problems = packs.rebuild(snapshot)
        val built = packs.built
        val sent = packs.sendAll()
        val broken = snapshot.everywhere(ResourcePackKind).isNotEmpty() && snapshot.compiledResourcePacks.isEmpty()
        if (broken) log.warn("A resource pack has errors; players keep the last good resource pack")
        val info = built?.let { PackBuild(it.sha1, it.url, it.bytes.size) }
        for (id in ids) {
            val resource = Resource(ResourcePackKind, id)
            batch.report(
                resource,
                ok = !broken && problems.none { it.severity == Severity.ERROR },
                reattached = sent,
                problems = batch.problemsUnder(resource, snapshot.problems) + problems,
                pack = info
            )
        }
    }
}
