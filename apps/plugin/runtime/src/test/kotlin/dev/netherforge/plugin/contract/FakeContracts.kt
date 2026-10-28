package dev.netherforge.plugin.contract

import dev.netherforge.plugin.platform.ItemLook
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.testkit.FakePlatform
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/*
 * Every Platform contract suite, run against the testkit's fake. The same
 * suites run against Paper in each adapter's integration test, so the fake
 * can't say anything the real server doesn't.
 */

/** A fresh fake server for each test: its main thread is the test's, and its ticks run what's queued and the mobs' AI. */
class FakeContractServer : ContractServer {
    override val platform = FakePlatform()
    override val events = RecordingEvents()
    override val itemLooks: MutableMap<String, ItemLook> = ConcurrentHashMap()
    override val blockStates: MutableMap<String, String> = ConcurrentHashMap()
    override val origin = Location("world", 0.5, 64.0, 0.5)

    init {
        platform.bind(events, { null }, ContractServer.lookup(itemLooks), blockStates::get) { ContractServer.NAMESPACE }
        platform.worldManager.storage = directory()
    }

    override fun <T> main(block: () -> T): T = block()

    override fun ticks(count: Int) = repeat(count) {
        platform.scheduler.runPending()
        platform.tickWorld()
    }

    override fun unloadedChunk(): Pair<Int, Int> {
        platform.worlds.unloaded += Triple(origin.world, FAR, FAR)
        return FAR to FAR
    }

    override fun directory(): Path = Files.createTempDirectory(ROOT, "contract")

    private companion object {
        const val FAR = 10_000

        /** Every fake server's files, gone when the tests are. */
        val ROOT: Path = Files.createTempDirectory("netherforge-contract").also { root ->
            Runtime.getRuntime().addShutdownHook(Thread { root.toFile().deleteRecursively() })
        }
    }
}

class FakePlatformInfoTest : PlatformInfoContract() {
    override fun connect() = FakeContractServer()
}

class FakeDatapackOpsTest : DatapackOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakeLootOpsTest : LootOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakeScreenOpsTest : ScreenOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakeTextOpsTest : TextOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakePerformanceOpsTest : PerformanceOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakePauseOpsTest : PauseOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakeWorldOpsTest : WorldOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakeBlockOpsTest : BlockOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakeParticleOpsTest : ParticleOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakeSoundOpsTest : SoundOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakeMannequinOpsTest : MannequinOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakeEntityOpsTest : EntityOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakePlayerOpsTest : PlayerOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakeWorldEntityOpsTest : WorldEntityOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakeInventoryOpsTest : InventoryOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakeBossBarOpsTest : BossBarOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakeSidebarOpsTest : SidebarOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakeTeamOpsTest : TeamOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakePlayerListOpsTest : PlayerListOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakeMenuOpsTest : MenuOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakeDialogOpsTest : DialogOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakeResourcePackOpsTest : ResourcePackOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakeProjectItemOpsTest : ProjectItemOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakeItemViewOpsTest : ItemViewOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakeRecipeOpsTest : RecipeOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakeAttributeOpsTest : AttributeOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakePathfindingOpsTest : PathfindingOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakeMobGoalOpsTest : MobGoalOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakePlayerViewOpsTest : PlayerViewOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakeServerAdminOpsTest : ServerAdminOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakePermissionOpsTest : PermissionOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakeAdvancementOpsTest : AdvancementOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakeWorldManagerOpsTest : WorldManagerOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakeBorderOpsTest : BorderOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakeStructureOpsTest : StructureOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakeCommandOpsTest : CommandOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakeBotOpsTest : BotOpsContract() {
    override fun connect() = FakeContractServer()
}

class FakeBotActionsTest : BotActionsContract() {
    override fun connect() = FakeContractServer()
}
