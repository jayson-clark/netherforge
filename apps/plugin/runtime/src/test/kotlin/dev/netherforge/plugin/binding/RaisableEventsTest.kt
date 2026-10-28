package dev.netherforge.plugin.binding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `nf.test.raise` takes any event of `nf` the spec declares, but those only their own script hears:
 * generated from the event registry, so a new event is raisable without anyone listing it.
 */
class RaisableEventsTest {
    @Test
    fun `every nf event but a local one can be raised, and nothing else`() {
        val nf = Events.all.filter { it.owner == "nf" && !it.local }.map { it.luaName }
        assertTrue(nf.size > 50, "only ${nf.size} events")
        assertEquals(nf.toSet(), RaisableEvents.byName.keys)
        assertTrue("unload" !in RaisableEvents.byName)
    }

    @Test
    fun `an event the server raises about a player is heard first by that player's handle`() {
        val raisable = assertNotNull(RaisableEvents.byName["player_chat"])
        assertEquals(Events.NF_PLAYER_CHAT, raisable.event)
        assertEquals(Events.PLAYER_CHAT, raisable.first)
        assertNotNull(RaisableEvents.byName["tick"]).let { assertEquals(null, it.first) }
    }
}
