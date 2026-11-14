package uk.co.magpie.instrument.vision

import org.junit.Assert.*
import org.junit.Test

class EvidenceTest {
    @Test fun objectLabelsNeverBecomeMaterialIdentities() {
        val result = explainEvidence("knife", NoMaterialModel().unavailable())
        assertTrue(result.contains("Material unknown"))
        assertTrue(result.contains("Inconclusive"))
        assertTrue(result.contains("not attributed"))
        assertFalse(result.contains("steel"))
        assertFalse(result.contains("%"))
    }
    @Test fun evenControlledScanDoesNotEstablishIdentity() {
        val result = explainEvidence("spoon", NoMaterialModel().unavailable(), controlledScan = true)
        assertTrue(result.contains("cannot establish material identity"))
    }
    @Test fun predictionsDoNotImplyValidatedMetalIdentification() {
        val result = explainEvidence("cup", MaterialResult.Predictions("test adapter", listOf(MaterialScore("test", .9f))))
        assertTrue(result.contains("requires validation"))
        assertTrue(result.contains("Inconclusive"))
    }
}
