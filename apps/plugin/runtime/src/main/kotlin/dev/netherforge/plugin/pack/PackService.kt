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
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.project.Resource
import dev.netherforge.plugin.session.ReloadBatch
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.SessionProject

/**
 * A session's part in the resource pack ([Packs], which outlives it):
 * building it from the session's project, sending it to everyone online and
 * to whoever joins, and rebuilding it when a pack is saved.
 */
internal class PackService(private val packs: Packs, private val log: RuntimeLog) : RuntimeService {
    override val name get() = "resource pack"

    /** What the last build found wrong that the format didn't (the server's pack format unknown). */
    private var problems: List<Problem> = emptyList()

    /** First: menus' skins and scripts' glyphs read the build. A refused project builds nothing; the last build stays. */
    override fun define(project: SessionProject) {
        problems = if (project.refused) emptyList() else packs.rebuild(project.snapshot)
    }

    override fun start() {
        packs.sendAll()
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
        val before = packs.built?.sha1
        problems = packs.rebuild(batch.snapshot)
        val built = packs.built
        if (built != null && built.sha1 != before) packs.sendAll()
    }

    /**
     * Rebuilds the one resource pack all packs go into, and sends it to
     * everyone online whose client doesn't have this hash. Reported once per
     * pack that was saved; `reattached` is how many players it was sent to.
     */
    override fun reload(kind: KindSpec<*, *>, ids: Set<String>, batch: ReloadBatch) {
        val snapshot = batch.snapshot
        val before = packs.built?.sha1
        problems = packs.rebuild(snapshot)
        val built = packs.built
        val sent = if (built != null && built.sha1 != before) packs.sendAll() else 0
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
