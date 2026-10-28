package dev.netherforge.plugin.paper.contract

import dev.netherforge.plugin.contract.AdvancementOpsContract
import dev.netherforge.plugin.contract.AttributeOpsContract
import dev.netherforge.plugin.contract.BlockOpsContract
import dev.netherforge.plugin.contract.BorderOpsContract
import dev.netherforge.plugin.contract.BossBarOpsContract
import dev.netherforge.plugin.contract.BotActionsContract
import dev.netherforge.plugin.contract.BotOpsContract
import dev.netherforge.plugin.contract.CommandOpsContract
import dev.netherforge.plugin.contract.DatapackOpsContract
import dev.netherforge.plugin.contract.DialogOpsContract
import dev.netherforge.plugin.contract.EntityOpsContract
import dev.netherforge.plugin.contract.InventoryOpsContract
import dev.netherforge.plugin.contract.ItemViewOpsContract
import dev.netherforge.plugin.contract.LootOpsContract
import dev.netherforge.plugin.contract.MannequinOpsContract
import dev.netherforge.plugin.contract.MenuOpsContract
import dev.netherforge.plugin.contract.MobGoalOpsContract
import dev.netherforge.plugin.contract.ParticleOpsContract
import dev.netherforge.plugin.contract.PathfindingOpsContract
import dev.netherforge.plugin.contract.PauseOpsContract
import dev.netherforge.plugin.contract.PerformanceOpsContract
import dev.netherforge.plugin.contract.PermissionOpsContract
import dev.netherforge.plugin.contract.PlatformInfoContract
import dev.netherforge.plugin.contract.PlayerListOpsContract
import dev.netherforge.plugin.contract.PlayerOpsContract
import dev.netherforge.plugin.contract.PlayerViewOpsContract
import dev.netherforge.plugin.contract.ProjectItemOpsContract
import dev.netherforge.plugin.contract.RecipeOpsContract
import dev.netherforge.plugin.contract.ResourcePackOpsContract
import dev.netherforge.plugin.contract.ScreenOpsContract
import dev.netherforge.plugin.contract.ServerAdminOpsContract
import dev.netherforge.plugin.contract.SidebarOpsContract
import dev.netherforge.plugin.contract.SoundOpsContract
import dev.netherforge.plugin.contract.StructureOpsContract
import dev.netherforge.plugin.contract.TeamOpsContract
import dev.netherforge.plugin.contract.TextOpsContract
import dev.netherforge.plugin.contract.WorldEntityOpsContract
import dev.netherforge.plugin.contract.WorldManagerOpsContract
import dev.netherforge.plugin.contract.WorldOpsContract

/*
 * Every Platform contract suite, run against Paper's PaperPlatform from
 * inside a server ([ContractPlugin]). The same suites run against the fake
 * in the runtime's tests: where they disagree, Paper is the truth.
 */

class PaperPlatformInfoTest : PlatformInfoContract() {
    override fun connect() = PaperContractServer.current
}

class PaperTextOpsTest : TextOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperPerformanceOpsTest : PerformanceOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperPauseOpsTest : PauseOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperWorldOpsTest : WorldOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperBlockOpsTest : BlockOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperParticleOpsTest : ParticleOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperSoundOpsTest : SoundOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperEntityOpsTest : EntityOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperPlayerOpsTest : PlayerOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperWorldEntityOpsTest : WorldEntityOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperMannequinOpsTest : MannequinOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperInventoryOpsTest : InventoryOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperBossBarOpsTest : BossBarOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperSidebarOpsTest : SidebarOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperTeamOpsTest : TeamOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperPlayerListOpsTest : PlayerListOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperMenuOpsTest : MenuOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperDialogOpsTest : DialogOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperResourcePackOpsTest : ResourcePackOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperProjectItemOpsTest : ProjectItemOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperItemViewOpsTest : ItemViewOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperRecipeOpsTest : RecipeOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperDatapackOpsTest : DatapackOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperLootOpsTest : LootOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperScreenOpsTest : ScreenOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperAttributeOpsTest : AttributeOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperPathfindingOpsTest : PathfindingOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperMobGoalOpsTest : MobGoalOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperPlayerViewOpsTest : PlayerViewOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperServerAdminOpsTest : ServerAdminOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperPermissionOpsTest : PermissionOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperAdvancementOpsTest : AdvancementOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperWorldManagerOpsTest : WorldManagerOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperBorderOpsTest : BorderOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperStructureOpsTest : StructureOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperCommandOpsTest : CommandOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperBotOpsTest : BotOpsContract() {
    override fun connect() = PaperContractServer.current
}

class PaperBotActionsTest : BotActionsContract() {
    override fun connect() = PaperContractServer.current
}

/** Every suite, in the order they run. */
object PaperContracts {
    val ALL = listOf(
        PaperPlatformInfoTest::class.java,
        PaperTextOpsTest::class.java,
        PaperPerformanceOpsTest::class.java,
        PaperPauseOpsTest::class.java,
        PaperWorldOpsTest::class.java,
        PaperBlockOpsTest::class.java,
        PaperParticleOpsTest::class.java,
        PaperSoundOpsTest::class.java,
        PaperEntityOpsTest::class.java,
        PaperPlayerOpsTest::class.java,
        PaperWorldEntityOpsTest::class.java,
        PaperMannequinOpsTest::class.java,
        PaperInventoryOpsTest::class.java,
        PaperBossBarOpsTest::class.java,
        PaperSidebarOpsTest::class.java,
        PaperTeamOpsTest::class.java,
        PaperPlayerListOpsTest::class.java,
        PaperMenuOpsTest::class.java,
        PaperDialogOpsTest::class.java,
        PaperResourcePackOpsTest::class.java,
        PaperProjectItemOpsTest::class.java,
        PaperItemViewOpsTest::class.java,
        PaperRecipeOpsTest::class.java,
        PaperLootOpsTest::class.java,
        PaperScreenOpsTest::class.java,
        PaperDatapackOpsTest::class.java,
        PaperDatapackCheckTest::class.java,
        PaperAttributeOpsTest::class.java,
        PaperPathfindingOpsTest::class.java,
        PaperMobGoalOpsTest::class.java,
        PaperPlayerViewOpsTest::class.java,
        PaperServerAdminOpsTest::class.java,
        PaperPermissionOpsTest::class.java,
        PaperAdvancementOpsTest::class.java,
        PaperWorldManagerOpsTest::class.java,
        PaperBorderOpsTest::class.java,
        PaperStructureOpsTest::class.java,
        PaperCommandOpsTest::class.java,
        PaperBotOpsTest::class.java,
        PaperBotActionsTest::class.java,
        PaperWorldGeneratorsTest::class.java,
        PaperNoteBlocksTest::class.java,
        PaperAdventureItemsTest::class.java,
        PaperStorageTest::class.java,
        PaperSkiesTest::class.java,
        PaperCarriersTest::class.java,
        // Last: it checks that between them the suites saw every event.
        PaperGameEventsTest::class.java
    )
}
