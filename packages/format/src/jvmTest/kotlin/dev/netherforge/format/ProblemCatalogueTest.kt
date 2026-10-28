package dev.netherforge.format

import dev.netherforge.format.codegen.ProblemCatalogue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** The catalogue page is [ProblemCodes] written out, and every page it links to exists. */
class ProblemCatalogueTest {
    @Test
    fun theDocsPageListsEveryCode() {
        val rendered = ProblemCatalogue.render()
        if (TestFiles.read(ProblemCatalogue.PATH) != rendered) {
            if (TestFiles.updateGolden) {
                TestFiles.write(ProblemCatalogue.PATH, rendered)
            } else {
                fail("${ProblemCatalogue.PATH} is out of date. Run with UPDATE_GOLDEN=1 to rewrite it.")
            }
        }
    }

    @Test
    fun everyCodeExplainsItselfOnAPageThatExists() {
        for (code in ProblemCodes.all) {
            assertTrue(code.summary.endsWith("."), "${code.code}'s summary is a sentence")
            assertTrue(TestFiles.read("docs/${code.page}") != null, "${code.code} points at docs/${code.page}, which doesn't exist")
        }
        assertEquals(ProblemCodes.all.size, ProblemCodes.all.map { it.anchor }.toSet().size, "two codes share an anchor")
    }
}
