package dev.netherforge.format.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionRangeTest {
    private fun admits(range: String, vararg versions: String) {
        val parsed = VersionRange.parse(range) ?: error("\"$range\" didn't parse")
        for (version in versions) assertTrue(parsed.admits(version), "$range should take $version")
    }

    private fun refuses(range: String, vararg versions: String) {
        val parsed = VersionRange.parse(range) ?: error("\"$range\" didn't parse")
        for (version in versions) assertFalse(parsed.admits(version), "$range shouldn't take $version")
    }

    @Test
    fun caretAndTildeKeepTheParts_semverSaysAreCompatible() {
        admits("^1.2.3", "1.2.3", "1.9.0", "1.2.3+build")
        refuses("^1.2.3", "1.2.2", "2.0.0", "0.9.9")
        admits("^0.2.3", "0.2.3", "0.2.9")
        refuses("^0.2.3", "0.3.0")
        admits("^0.0.3", "0.0.3")
        refuses("^0.0.3", "0.0.4")
        admits("~1.2.3", "1.2.3", "1.2.9")
        refuses("~1.2.3", "1.3.0")
    }

    @Test
    fun comparisonsAllHoldAndAlternativesEitherDoes() {
        admits(">=1.0.0 <2.0.0", "1.0.0", "1.99.0")
        refuses(">=1.0.0 <2.0.0", "2.0.0", "0.1.0")
        admits("1.2.3", "1.2.3")
        refuses("=1.2.3", "1.2.4")
        admits("^1.0.0 || ^3.0.0", "1.5.0", "3.1.0")
        refuses("^1.0.0 || ^3.0.0", "2.0.0")
        admits("*", "0.0.1", "99.0.0")
        refuses(">1.0.0", "1.0.0")
        admits("<=1.0.0", "1.0.0")
    }

    @Test
    fun aPrereleaseIsTakenOnlyWhenTheRangeNamesOneOfItsVersion() {
        refuses("^1.0.0", "1.5.0-rc.1", "2.0.0-beta.1")
        admits(">=1.1.0-beta.1 <2.0.0", "1.1.0-beta.2", "1.1.0")
        refuses(">=1.1.0-beta.1 <2.0.0", "1.2.0-beta.1")
        assertTrue(SemVer.parse("1.0.0-alpha")!! < SemVer.parse("1.0.0-alpha.1")!!)
        assertTrue(SemVer.parse("1.0.0-alpha.beta")!! < SemVer.parse("1.0.0-beta")!!)
        assertTrue(SemVer.parse("1.0.0-beta.11")!! > SemVer.parse("1.0.0-beta.2")!!)
        assertTrue(SemVer.parse("1.0.0-rc.1")!! < SemVer.parse("1.0.0")!!)
    }

    @Test
    fun whatIsntARangeDoesntParse() {
        for (text in listOf("", "one or two", "1.2", "^1", ">= 1.0.0", "1.0.0 ||", "x")) assertNull(VersionRange.parse(text), text)
        assertEquals("^1.2.0", VersionRange.parse(" ^1.2.0 ").toString())
    }
}
