package dev.netherforge.plugin.contract

import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.plugin.platform.ItemLook
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.Platform
import java.nio.file.Path

/**
 * A server the [Platform] contract suites run against: the testkit's fake in
 * the runtime's tests, and real Paper in each adapter's integration test,
 * where the suites run inside the server (see the testing skill). The suites
 * only say what every [Platform] must do; this says how to drive one.
 *
 * Its platform is bound to [events] (and to [itemLooks] for project items)
 * and has bots, which the suites join as their players. Suites call the
 * platform only inside [main]; between calls they let the server run with
 * [ticks].
 */
interface ContractServer {
    val platform: Platform

    /** What the platform has told the runtime. */
    val events: RecordingEvents

    /** The project item looks the platform resolves stacks against (`Platform.bind`'s `items`), by id in [NAMESPACE]. */
    val itemLooks: MutableMap<String, ItemLook>

    /** The state each project block is held as (`Platform.bind`'s `blocks`), by name. */
    val blockStates: MutableMap<String, String>

    /**
     * Where the suites work: a spot standing on the ground of the default
     * world, in open, flat air, whose chunk and the two around it each way
     * stay loaded for the whole run.
     */
    val origin: Location

    companion object {
        /** The project namespace the suites bind the platform to: what it stamps and registers under. */
        const val NAMESPACE = "contract"

        /** `Platform.bind`'s `items`: [looks] by id, for a reference as a file writes it (`ruby`) or a stack carries it (`contract:ruby`). */
        fun lookup(looks: Map<String, ItemLook>): (String) -> ItemLook? = { ResourceRef(it).idIn(NAMESPACE)?.let(looks::get) }
    }

    /** Runs [block] on the server's main thread and answers what it did, or throws what it threw. */
    fun <T> main(block: () -> T): T

    /** Lets [count] server ticks go by. */
    fun ticks(count: Int)

    /** A chunk of [origin]'s world, in chunk coordinates, that isn't loaded and won't be while a suite looks. */
    fun unloadedChunk(): Pair<Int, Int>

    /** A new, empty directory for files a suite writes. */
    fun directory(): Path
}
