package dev.netherforge.plugin.testkit

import dev.netherforge.plugin.platform.SkyOps
import dev.netherforge.plugin.platform.SkyView
import java.util.UUID

/** [SkyOps] on the fake: what each player is shown now, and every change in order. */
class FakeSkies : SkyOps {
    val shown = HashMap<UUID, SkyView>()
    val changes = mutableListOf<Pair<UUID, SkyView?>>()

    override fun show(player: UUID, sky: SkyView?) {
        if (sky == null) shown.remove(player) else shown[player] = sky
        changes += player to sky
    }
}
