package dev.netherforge.plugin.paper.contract

import dev.netherforge.format.dimensiontype.DimensionTypeJson
import dev.netherforge.plugin.contract.ContractServer
import dev.netherforge.plugin.contract.PlatformContract
import dev.netherforge.plugin.paper.PaperDatapacks
import dev.netherforge.plugin.paper.PaperSkies
import dev.netherforge.plugin.platform.SkyView
import org.junit.jupiter.api.Test

/**
 * Paper only: a sky's twins, and from 26.1 the world clock and timeline of one held at night, are what the server
 * reads as it starts (it would refuse to start on one it couldn't: [ContractBootstrap] gives it [ContractBiomes.BLOOD_MOON]);
 * and a player is shown it, its clock told them at their step and at 0 again after. The fake has no clocks.
 */
class PaperSkiesTest : PlatformContract() {
    override fun connect() = PaperContractServer.current

    @Test
    fun `a sky held at night is shown with its clock, and the server keeps the clock stopped`() {
        val player = join()
        val clocks = PaperDatapacks().format!!.first() >= DimensionTypeJson.CLOCK_FORMAT
        val clock = ContractBiomes.BLOOD_MOON_CLOCK.takeIf { clocks }
        val skies = platform.skies as PaperSkies
        val sky = "${ContractServer.NAMESPACE}:${ContractBiomes.BLOOD_MOON}"
        main {
            // Before 26.1 there's no clock to stop: nothing happens.
            skies.stopClocks(listOf(ContractBiomes.BLOOD_MOON_CLOCK))
            skies.show(player.uuid, SkyView(ContractServer.NAMESPACE, sky, 1, clock))
            skies.show(player.uuid, SkyView(ContractServer.NAMESPACE, sky, 3, clock))
            skies.show(player.uuid, null)
        }
    }
}
