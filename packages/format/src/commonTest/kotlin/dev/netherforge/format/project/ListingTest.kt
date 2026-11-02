package dev.netherforge.format.project

import dev.netherforge.format.testManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListingTest {
    private fun problems(listing: String, files: Map<String, String> = emptyMap()) =
        Projects.load(MapProjectSource(mapOf("netherforge.json" to testManifest(""", "listing": $listing""")) + files))
            .problems.filter { it.code == "project.listing" }

    @Test
    fun aListingThatSaysWhatOneTakesHasNoProblems() {
        val listing = """{ "description": "Capture the flag.", "authors": ["Ann"], "icon": "listing/icon.png",
            "screenshots": ["listing/one.png"], "tags": ["pvp", "team-game"], "players": { "min": 2, "max": 16 } }"""
        val files = mapOf("listing/icon.png" to "png", "listing/one.png" to "png")
        assertEquals(emptyList(), problems(listing, files))
        val manifest = Projects.load(
            MapProjectSource(mapOf("netherforge.json" to testManifest(""", "listing": $listing""")) + files)
        ).manifest!!
        assertEquals(ListingPlayers(2, 16), manifest.listing!!.players)
    }

    @Test
    fun picturesThatArentThereTagsThatArentAndCountsThatDontAddUpAreProblems() {
        val found = problems(
            """{ "icon": "listing/icon.png", "screenshots": ["../outside.png", "shot.jpg"], "tags": ["PvP"], "players": { "min": 4, "max": 2 } }"""
        )
        val at = found.map { it.path }
        assertTrue("$.listing.icon" in at, at.toString())
        assertTrue("$.listing.screenshots[0]" in at && "$.listing.screenshots[1]" in at, at.toString())
        assertTrue("$.listing.tags[0]" in at, at.toString())
        assertTrue("$.listing.players.max" in at, at.toString())
    }
}
