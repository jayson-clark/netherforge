package dev.netherforge.format

import dev.netherforge.format.codegen.FeatureCatalogue
import kotlin.test.Test
import kotlin.test.fail

/** The feature page is [dev.netherforge.format.game.FeatureTable.CURRENT] written out. */
class FeatureCatalogueTest {
    @Test
    fun theDocsPageListsEveryFeature() {
        val rendered = FeatureCatalogue.render()
        if (TestFiles.read(FeatureCatalogue.PATH) != rendered) {
            if (TestFiles.updateGolden) {
                TestFiles.write(FeatureCatalogue.PATH, rendered)
            } else {
                fail("${FeatureCatalogue.PATH} is out of date. Run with UPDATE_GOLDEN=1 to rewrite it.")
            }
        }
    }
}
