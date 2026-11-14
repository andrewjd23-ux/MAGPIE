package uk.co.magpie.instrument.vision

import org.junit.Assert.*
import org.junit.Test

class EvidenceTest {
    // Synthetic contract fixtures for logic tests only. No material model ships in the app.
    private val ready = MaterialModelStatus.Ready(VerifiedMaterialModel(
        "unit-test-only", "fixture", "test fixture", "synthetic decision tests",
        mapOf("steel" to MaterialFamily.METAL, "wood" to MaterialFamily.NONMETAL, "mixed" to MaterialFamily.UNSPECIFIED)))
    private fun scores(a: String, x: Float, b: String = "wood", y: Float = .1f) = MaterialResult.Predictions("unit-test-only", listOf(MaterialScore(a, x), MaterialScore(b, y)))
    @Test fun unavailableModelNeverInventsPredictionsOrDecisions() {
        val missing = NoMaterialModel().status
        assertEquals(MaterialDecision.UNKNOWN, decideMaterial(scores("steel", .99f), missing))
        assertTrue(validateMaterialResult(scores("steel", .99f), missing) is MaterialResult.Unavailable)
        val text = explainTarget(MaterialDecision.UNKNOWN, MaterialResult.Unavailable("Material model unavailable"))
        assertTrue(text.contains("Inconclusive"))
        assertTrue(text.contains("not attributed"))
        assertFalse(text.contains("steel"))
        assertFalse(text.contains("%"))
    }
    @Test fun onlyMaterialModelTrainedLabelsProduceSuspectedStates() {
        assertEquals(MaterialDecision.METAL_SUSPECTED, decideMaterial(scores("steel", .9f), ready))
        assertEquals(MaterialDecision.METAL_SUSPECTED, decideMaterial(scores("steel", .70f, "wood", .55f), ready))
        assertEquals(MaterialDecision.NONMETAL_SUSPECTED, decideMaterial(scores("wood", .9f, "steel"), ready))
        assertEquals(MaterialDecision.UNKNOWN, decideMaterial(scores("knife", .99f), ready)) // COCO category
        assertEquals(MaterialDecision.UNKNOWN, decideMaterial(scores("mixed", .99f), ready))
    }
    @Test fun ambiguousAndMalformedOutputsStayUnknown() {
        val inputs = listOf(scores("steel", .69f), scores("steel", .8f, "wood", .75f), scores("steel", Float.NaN),
            scores("steel", 1.1f), scores("steel", .9f, "steel"), scores("steel", .99f).copy(model = "wrong-model"),
            MaterialResult.Predictions("unit-test-only", listOf(MaterialScore("steel", .9f))))
        inputs.forEach { assertEquals(MaterialDecision.UNKNOWN, decideMaterial(it, ready)) }
        assertTrue(validateMaterialResult(scores("steel", Float.NaN), ready) is MaterialResult.Unavailable)
        assertTrue(validateMaterialResult(MaterialResult.Pending, ready) is MaterialResult.Unavailable)
    }
    @Test fun nonmetalDecisionSkipsMagneticFusionEvenIfScanWasPassedIn() {
        val scan = scanFixture(listOf(5.0, 5.0, 5.0))
        val text = explainTarget(MaterialDecision.NONMETAL_SUSPECTED, scores("wood", .9f, "steel"), scan)
        assertTrue(text.contains("Magnetic fusion is skipped"))
        assertFalse(text.contains("provisional hypothesis"))
        assertFalse(text.contains("repeatable response"))
    }
    @Test fun weakMagneticResponseNeverChangesVisualSuspicionToNonmetal() {
        val text = explainTarget(MaterialDecision.METAL_SUSPECTED, scores("steel", .9f), scanFixture(List(3) { .1 }))
        assertTrue(text.contains("METAL_SUSPECTED"))
        assertTrue(text.contains("never proof"))
        assertFalse(text.contains("NONMETAL_SUSPECTED"))
    }
    @Test fun materialInferenceIsAnExplicitCropOnlyOperationAndNeverRunsForMissingModel() {
        val missing = analyzeSelectedCrop(NoMaterialModel()) { error("Must not request pixels without an installed model") }
        assertEquals(MaterialDecision.UNKNOWN, missing.decision)
        assertTrue(missing.result is MaterialResult.Unavailable)
        var calls = 0
        val crop = MaterialInput(IntArray(6), 2, 3)
        val fake = object : MaterialClassifier {
            override val status = ready
            override fun classify(input: MaterialInput): MaterialResult {
                calls++
                assertSame(crop, input)
                return scores("steel", .9f)
            }
        }
        val result = analyzeSelectedCrop(fake) { crop }
        assertEquals(1, calls)
        assertEquals(MaterialDecision.METAL_SUSPECTED, result.decision)
    }
    @Test fun failingNativeMaterialAdapterIsUnavailableNotAFabricatedFallback() {
        val broken = object : MaterialClassifier {
            override val status = ready
            override fun classify(input: MaterialInput): MaterialResult = throw UnsatisfiedLinkError("test failure")
        }
        val result = analyzeSelectedCrop(broken) { MaterialInput(IntArray(1), 1, 1) }
        assertEquals(MaterialDecision.UNKNOWN, result.decision)
        assertTrue(result.result is MaterialResult.Unavailable)
    }

}
